# How to test-compile Java **and XML for Android** in a locked-down sandbox

A recipe you can hand to another model. Everything here was executed in a Debian 12
sandbox with **no root, no apt, no Android SDK manager, no Gradle, no Maven**, and
no network access except a small egress allowlist.

Working implementation lives in [`toolchain/`](toolchain/) — `setup.sh`, `check.sh`,
`build.sh`, `test.sh`, `xmlcheck.py`, `zipalign.py` — plus a runnable
[`sample/`](sample/) app. Timings from the sandbox: **setup 10 s, check 2 s, unit
tests 2 s, APK build 6 s.**

---

## 0. What you end up with

| Capability | Tool | Where it came from |
|---|---|---|
| Run Java (no root) | Temurin **JRE 25** | PyPI `jdk4py` |
| Compile Java (`javac`) | Eclipse **ECJ 3.45** batch compiler | npm `@vscjava/java-language-server` |
| Compile **XML / resources / manifest** | **aapt2 2.19** Linux binary | PyPI `aapt2` |
| Android platform API (bootclasspath) | **android.jar** API 34 (and 30–37) | npm `@drxiaozhi/minapk`, GitHub `Sable/android-platforms` |
| Java bytecode → `classes.dex` | **D8 8.2.2** (+ R8 for release) | npm `@drxiaozhi/minapk` |
| Sign the APK | **apksigner** + debug keystore | npm `@drxiaozhi/minapk` |
| Align the APK | **zipalign** — reimplemented in Python | this repo, `toolchain/zipalign.py` |
| Disassemble/verify an APK | **apktool 2.4.1** | npm `apktool-jar` |
| Run unit tests on the JVM | **JUnit 4.13.2 + Hamcrest** | npm `@vscjava/java-language-server` |
| Android XML lint before compiling | `xmlcheck.py` | this repo (stdlib only) |

`bash toolchain/setup.sh` fetches all of it (~170 MB, 10 s), verifies every tool by
running it, and writes `toolchain/env.sh`.

---

## 1. Map the egress allowlist first

Probe before planning. `curl -s -o /dev/null -w '%{http_code}' --max-time 6 <url>`:

```
200 https://pypi.org/simple/          <- Java runtime, aapt2 (NOT the Android SDK)
200 https://registry.npmjs.org/       <- ECJ, D8, R8, apksigner, android.jar, apktool, JUnit
200 https://github.com/               <- plus codeload.github.com: real git clone works
000 https://deb.debian.org/           <- apt dead
000 https://api.adoptium.net/         <- no JDK downloads
000 https://download.java.net/        <- no JDK downloads
000 https://repo1.maven.org/maven2/   <- Maven Central dead
000 https://dl.google.com/            <- no SDK, no build-tools, no platform zips
000 https://maven.google.com/         <- dead: no AGP, no R8 from Google
000 https://services.gradle.org/      <- dead: no Gradle wrapper
000 https://raw.githubusercontent.com/ <- dead, but *clone* and the GitHub API work
000 https://objects.githubusercontent.com/  <- so GitHub *releases* are unusable
```

Two consequences worth internalising:

* **The allowlist varies per sandbox.** The first pass at this recipe saw only
  PyPI + npm. This one also has GitHub, which adds a second, independent source for
  `android.jar` and for tool-tarball repos. Probe every time; never assume.
* **GitHub *release* assets are unreachable** (`objects.githubusercontent.com` is
  dead) while `git clone` and `api.github.com` are alive. So you fetch **files
  committed into repos**, never release attachments:
  `git clone --depth 1 --filter=blob:none --no-checkout <repo>` + `git checkout HEAD -- path`
  pulls a single blob — that is how `Sable/android-platforms` gives you an
  `android.jar` for *any* API level (30…37), 27 MB in ~2 s.
  (`gh api /repos/O/R/contents/PATH --jq .content | base64 -d` works too, for files < 1 MB.)

## 2. Get a Java runtime from PyPI — `jdk4py`

`pip download --no-deps --only-binary :all: jdk4py` gives a wheel with a full
Temurin **JRE** at `jdk4py/java-runtime`. Unzip it, `chmod +x bin/*`, done — no
installer, no root. It is a JRE: `java`, `keytool`, `jfr` exist, **`javac`, `jar`,
`jlink` do not**. It runs cleanly (`ldd` shows no missing libs) and includes
`java.desktop` (so AWT/Swing/headless rendering still work for GUI tests).

## 3. Get a compiler from npm — Eclipse **ECJ**, not javac

The trick from last time still holds and is still the key insight: **toolchains hide
inside packages whose *purpose* is to bundle them.** `@vscjava/java-language-server`
(VS Code's Java language server, ~51 MB) is JDT, and JDT ships the Eclipse batch
compiler:

```
package/server/plugins/org.eclipse.jdt.core.compiler.batch_3.45.0.v20260224-0835.jar
```

ECJ is pure Java, runs on any modern JRE, and takes javac-shaped flags. The same
tarball also contains **`org.junit_4.13.2`** and **`org.hamcrest_3.0.0`**, which is
how you get *runnable* unit tests with no Maven in sight.

**Android specifics that matter:**

```bash
# source ≤ 8: full Android fidelity. android.jar IS the bootclasspath, so
# java.* resolves to Android's stubs and a Java API Android lacks is an error.
java -jar ecj.jar -source 8 -target 8 -encoding UTF-8 -proc:none \
     -bootclasspath android.jar -classpath android.jar \
     -d out $(find src -name '*.java') gen/com/example/R.java
```

* **You must generate `R.java` first** (see step 5) or every `R.string.x` is a
  compile error.
* **Above source 8 ECJ refuses `-bootclasspath`** ("option -bootclasspath not
  supported at compliance level 9 and above"), and putting android.jar on the
  *classpath* at 9+ fails with *"The package java.util is accessible from more than
  one module: `<unnamed>`, java.base"* (100 errors from the platform alone).
  `--patch-module java.base=android.jar` does **not** fix it — it merges rather than
  replaces, so the JRE's extra APIs leak through. The workaround that does work:
  hand ECJ an android.jar with the JRE-owned packages stripped
  (`java/ javax/ jdk/ sun/ com/sun/ org/w3c/ org/xml/ org/ietf/ org/omg/`) — see
  `toolchain/filter_android_jar.py`. Then `android.*` comes from Android and
  `java.*` from the JRE.
* Consequence to be honest about: at **source 17** records, sealed types, `var`,
  text blocks, pattern matching and `Stream.toList()` all compile and dex fine, but
  Java *API* checking is loosened (a Java-17-only API Android lacks will compile).
  Default to **source 8** when you want API-accurate checking; use 17 when you need
  language features. Both modes are exercised in `toolchain/check.sh`.

Dead ends from experience: JDK 8's `tools.jar` on npm (`dataslope-tools-jar`) dies on
a modern JRE (wants a Java 8 `rt.jar` bootclasspath); TeaVM-javac (WASM, in
`@tracecode/tracejvm`) compiles on node but has no `-target`, so its bytecode is
always current-version and unusable as Android dex input.

## 4. Get the Android tooling from npm, as jars

`@drxiaozhi/minapk` (40 MB) is an APK-builder package whose `tools/` folder is the
whole Android half of the pipeline: **`d8.jar` (D8 *and* R8 8.2.2), `apksigner.jar`,
`android.jar` (API 34, 26 MB), `ecj-3.45.0.jar`, `debug.keystore`**. All plain JVM
bytecode, so they run on the jdk4py JRE:

```bash
java -cp d8.jar com.android.tools.r8.D8 --version   # D8 8.2.2-dev
java -cp d8.jar com.android.tools.r8.R8 --version   # R8 8.2.2-dev
java -jar apksigner.jar verify --print-certs app.apk
```

Trap worth knowing: the npm package **`d8-termux` also ships a file called
`d8.jar`, but it is a DEX container** (`classes.dex`, `classes2.dex`, …) meant to run
under Android's `app_process`. `com.android.tools.r8.D8` does not exist there. Check
for a `META-INF/MANIFEST.MF` and real `.class` entries before believing a jar.

## 5. Compile XML with **aapt2** from PyPI

`pip download aapt2` is a 5.6 MB wheel containing three prebuilt binaries
(`aapt2/bin/{Darwin,Linux,Windows}/`). The Linux one is a normal glibc executable
and prints `Android Asset Packaging Tool (aapt) 2.19-7832930`. That single binary is
your XML compiler, resource compiler, manifest validator, R.java generator and, with
`dump xmltree` / `dump badging`, binary-XML inspector:

```bash
aapt2 compile --dir res -o res.zip                    # every res XML -> .flat (+ validates it)
aapt2 link -o app-unsigned.apk -I android.jar \
     --manifest AndroidManifest.xml -R res.zip \
     --java gen --auto-add-overlay \
     --min-sdk-version 24 --target-sdk-version 34 \
     --version-code 1 --version-name 1.0
```

* `--auto-add-overlay` is required when you pass `-R res.zip`, otherwise you get
  *"resource string/app_name does not override an existing resource"*.
* aapt2 reads the manifest **and** every resource: undefined `@string`, `@color`,
  `@style`, bad attribute names, malformed XML and API-31+ `android:exported`
  problems are all link-time errors. It is the authoritative XML check.
* `aapt2` has **no `-f`**; for a different API level fetch another android.jar
  (step 1) and pass `-I`. Newer aapt2 (2.20) is also inside npm `aaptjs3`
  (`package/bin/x64/linux/aapt2`) if you want two independent sources.
* Add `xmlcheck.py` (stdlib-only) *in front of* aapt2 for fast, specific
  diagnostics: well-formedness with line numbers, manifest structure, duplicate
  `<string>` names, unescaped apostrophes, and `@type/name` references that do not
  resolve (`@string/app_nam` typos) — aapt2 fails on the first error, this reports
  them all at once.

## 6. `javac`-free dexing and signing, then alignment

```bash
java -cp d8.jar com.android.tools.r8.D8 --min-api 24 --lib android.jar \
     --output build/dex $(find build/classes -name '*.class')
java -cp d8.jar com.android.tools.r8.R8 --release --dex --min-api 24 \
     --lib android.jar --pg-conf proguard.pro --output build/dex <classes>
```

* D8/R8 **desugar**: Java 17 class files (major 61) dex fine at `--min-api 24`, so
  language features survive the round trip.
* Get `classes.dex` into the APK without the `jar` tool: Python's `zipfile` appends
  it (`z.write('build/dex/classes.dex', 'classes.dex')`). Remember that a runnable
  jar would need `META-INF/MANIFEST.MF` with **CRLF** line endings and a trailing
  blank line (`Main-Class: movies.Main`) — same trick as before, just for dex it is
  the APK itself that is the zip.
* **`zipalign` is the one build-tools binary PyPI and npm do not have.** The Linux
  `zipalign`s you find on GitHub are Android/bionic ELFs (`Nanolx/NanoDroid`
  `Full/zipalign.x86` fails with *"cannot execute: required file not found"* —
  missing `/system/bin/linker`), and real `zipalign` is glibc-only from build-tools.
  It is ~40 lines of `struct.pack` to reimplement: rewrite the zip so every entry's
  data offset is 4-byte aligned (4096 for `.so` with `-p`), padding via a 0xD935
  extra field, recomputing local-header offsets and the central directory. Then
  **align before signing** — v2 signing preserves layout, and if you enable v1 (only
  needed below minSdk 24) apksigner writes `META-INF/*` *after* alignment, so a
  post-sign check has to ignore `^META-INF/` (real zipalign has the same ordering).
* Verify, don't hope: `apksigner verify --print-certs` (v2 block present) +
  `aapt2 dump badging` (`package:`, `launchable-activity:`) + `apktool d`
  (independent decode of your own APK back to smali + resources).

## 7. Test *behaviour*, not just compilation

* **JUnit on the JVM**: compile the app's pure-Java classes (no `android.*` calls at
  runtime — the stubs in android.jar throw `Stub!`) together with `src/test/java`
  and run `org.junit.runner.JUnitCore`. The JUnit + Hamcrest jars come from the same
  language-server tarball as ECJ, so this costs nothing extra. `toolchain/test.sh`
  does exactly this and prints `OK (4 tests)`.
* **GUI without X11**: the JRE has `java.desktop`, so building Swing/Compose-style
  panels headlessly and painting a `JComponent` into a `BufferedImage` still works
  with `-Djava.awt.headless=true`; only top-level `JFrame` window screenshots need a
  real X server (Xvfb is unavailable when apt is blocked).
* **Anything Android runtime**: impossible without a device/emulator — you have no
  `adb`, no ART. Compilation, dex validity, APK structure and pure-Java logic are the
  honest limits here, and they cover the great majority of "does this build?" work.

## 8. How to find more packages like these

`curl https://pypi.org/simple/` is a ~45 MB index of every package name (grep it),
`https://registry.npmjs.org/-/v1/search?text=...` searches npm, and
`gh api /search/code -f q='filename:r8.jar'` searches GitHub code. The heuristic
that keeps paying off: **look for packages whose purpose is bundling** — language
servers, playground/runner images, "build APK without Android Studio" tools, JRE
shims — not packages named after the tool you want. `aapt2` on PyPI, `apktool-jar` on
npm, `d8.jar` inside an APK-builder, `junit.jar` inside a language server, and the
whole Android platform jar inside a Bun-to-APK package were all found that way.

---

## Appendix: end-to-end, copy-pasteable

```bash
git clone <this repo> && cd GhostHand
bash toolchain/setup.sh                 # ~10 s, PyPI + npm + GitHub only

bash toolchain/check.sh  sample          # 2 s: XML lint -> aapt2 -> ECJ -> D8
bash toolchain/test.sh   sample          # 2 s: JUnit 4 on the bundled JRE
bash toolchain/build.sh  sample --verify # 6 s: signed, aligned, verified APK

# a real project (flat or Gradle layout), another API level, Java 17 language mode:
bash toolchain/setup.sh --api 35    --vendor /tmp/tc
bash toolchain/build.sh  ~/myapp --api 35 --min-api 24 --source 17 --release --verify
```
