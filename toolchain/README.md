# toolchain/ — Android Java + XML builds with no SDK, no Gradle, no Maven

Everything in here runs with **no root, no apt and no Android SDK**: the tools come
from PyPI, npm and GitHub. See [`../RECIPE.md`](../RECIPE.md) for the full write-up
and the reasoning behind each choice (section 9 covers AndroidX).

```bash
bash toolchain/setup.sh                     # fetch + verify everything (~11 s, ~175 MB)
                                            #   --api 35          another platform level
                                            #   --vendor /tmp/tc  keep it out of the repo
                                            #   KEEP_TMP=1        keep the download cache

bash toolchain/check.sh  sample              # fast test-compile (XML + Java + D8)
bash toolchain/test.sh   sample              # compile AND run JUnit 4 unit tests
bash toolchain/build.sh  sample --verify     # signed, aligned, verified APK

# AndroidX: once, from a committed Gradle cache on GitHub (no Maven, ~31 s)
bash toolchain/androidx.sh
bash toolchain/check.sh  sample-androidx
bash toolchain/build.sh  sample-androidx --release --verify
```

All three accept a flat project (`AndroidManifest.xml`, `res/`, `src/`) or the
Gradle layout (`src/main/AndroidManifest.xml`, `src/main/res`, `src/main/java`).

| file | what it is |
|---|---|
| `setup.sh` | downloads JRE, ECJ, JUnit, D8/R8, apksigner, android.jar, aapt2, apktool, lambda stubs, Kotlin runtime; runs each one to prove it works; writes `env.sh` |
| `env.sh` | generated; `source` it to get `JAVA_HOME`, `ECJ_JAR`, `D8_JAR`, `AAPT2`, … |
| `lib.sh` | shared build helpers (layout detection, aapt2/ECJ/dex wrappers, AndroidX wiring, signing) |
| `check.sh` | XML lint → aapt2 compile/link → ECJ → D8. The "does it compile?" loop, ~2 s |
| `test.sh` | adds `test/` (or `src/test/java`) sources and runs JUnit 4 on the JRE |
| `build.sh` | the full pipeline: resources, manifest, R.java, Java, dex, zipalign, sign, **verify** |
| `verify_apk.py` | checks the finished APK: self-containment, dex count vs the declared floor, manifest components, vector bases |
| `manifest_keep.py` | generates R8 keep rules from AndroidManifest.xml (R8 never reads it) |
| `aar_floor.py` | fails the build if a vendored AAR needs a newer platform than `minSdk` |
| `xmlcheck.py` | stdlib-only Android XML/manifest linter (runs before aapt2) |
| `zipalign.py` | pure-Python zipalign (rewrite + `-c` check), since build-tools is unreachable |
| `filter_android_jar.py` | strips JRE-owned packages from android.jar for `-source 11/17` |
| `lambda-stubs/` | the one class `android.jar` lacks (`LambdaMetafactory`); `setup.sh` compiles it into `vendor/core-lambda-stubs.jar` |
| `androidx.sh` | fetches AndroidX and assembles `vendor/androidx/` (one-off, needs the network) |
| `androidx_fetch.py` | blobless clone of the source repo + fetch of the selected AARs/jars |
| `select_androidx.py` | picks the highest version of each wanted artifact, skips `-sources`/`-javadoc`/test-only |
| `extract_aar.py` | explodes AARs (classes.jar, res/, R.txt, AndroidManifest.xml, libs/, assets/) |
| `androidx_assemble.py` | merges the jars (including each AAR's `libs/*.jar`), compiles every library `res/`, writes `packages.txt` |

## Options

```
build.sh DIR [--api N] [--min-api N] [--source 8|11|17|21|25] [--release]
             [--out FILE] [--verify] [--no-androidx]
check.sh DIR [--api N] [--min-api N] [--source N] [--no-dex] [--no-xml-lint]
             [--no-androidx] [--full-dex]
test.sh  DIR [--source N] [--filter SomeTest] [--no-androidx]
```

`minSdkVersion` comes from the project's manifest (that is what the artifact will
claim); `--min-api` overrides it. It decides aapt2's resource versioning, D8's
`--min-api` and whether apksigner must write a v1 signature — a flag default that
disagrees with the manifest is how an APK ends up claiming a floor it cannot run on.

## Checking the artifact, not just the inputs

Compiling, linking, dexing, aligning and signing all succeed on APKs that are
broken on a phone. `verify_apk.py` (run by `build.sh`, after signing) looks at the
finished artifact for the four failures that survive all of it:

1. **Self-containment.** Every type the dex names under `androidx.*`,
   `com.google.*`, `kotlin.*`, `kotlinx.*` or `org.jetbrains.*` must be defined in
   the APK, or declared in `packaging-allowlist.txt` with a reason. R8 only *warns*
   about a missing class, and one `-dontwarn` silences even that: the first
   AndroidX build from this toolchain named 119 types it did not ship (63 of them
   `kotlin/*`, starting with `kotlin.jvm.internal.Intrinsics` on the
   `ComponentActivity` constructor path) and the only symptom was a
   `ClassNotFoundException` on a real phone. Entries are scoped `both`/`release`/
   `debug`, and an entry that stops suppressing anything fails the build, so the
   file cannot rot the way that `-dontwarn` did.
2. **Dex count against the declared floor.** Dalvik (API < 21) loads `classes.dex`
   and nothing else: a `minSdk 19` APK with `classes2.dex` installs and then dies
   with `NoClassDefFoundError`.
3. **Manifest components.** R8 never reads `AndroidManifest.xml`, so an activity it
   instantiates by name looks unused and gets deleted — a signed, installable APK
   with no launcher class. `lib.sh` generates keep rules from the manifest at
   release-dex time (`manifest_keep.py`, as AGP does) and the checker re-derives
   the same list from the APK, so bypassing the generator is caught too.
4. **Gutted vector drawables.** Below API 21 aapt2 moves `<vector>`'s API-21
   attributes into `res/drawable-v21/` and leaves the base file empty unless
   `--no-version-vectors` (which `lib.sh` always passes): a pre-21 device inflates
   the base and crashes with `Resources$NotFoundException`.

## AndroidX (optional, automatic once installed)

`toolchain/androidx.sh` builds `vendor/androidx/`:

| path | what it is | how it is used |
|---|---|---|
| `androidx.jar` | every library's `classes.jar` + each AAR's `libs/*.jar` + the plain jars (annotation, collection, collection-ktx, lifecycle-common, …) merged | ECJ `-classpath`, D8/R8 program input |
| `res/*.zip` | `aapt2 compile` output per library | `aapt2 link -R` per library |
| `packages.txt` | the 42 library packages | `aapt2 link --extra-packages` → a correct `R.java` per library, styleables included |
| `aar/`, `src/` | exploded AARs, downloaded artifacts | rebuild inputs (`--force`) |

The artifact list (`select_androidx.py`) is deliberately short and evidence-based:
navigation-* is absent because nothing outside navigation referenced it, and
slidingpanelayout/window followed it out (only navigation referenced
slidingpanelayout, only slidingpanelayout referenced window). `lifecycle-viewmodel-savedstate`
stays even for an app with no ViewModels, because `ComponentActivity`'s constructor
calls `SavedStateHandleSupport.enableSavedStateHandles()` and R8 fails without it.

A project gets AndroidX automatically when it mentions `androidx.`,
`Theme.AppCompat`, `Theme.Material3`, `MaterialComponents` or
`com.google.android.material` anywhere in `src/`, `res/` or the manifest — the plain
`sample/` therefore keeps building in 2 s with an 8 KB dex. Force it either way with
`GH_ANDROIDX=on|off` (or `--no-androidx`).

Not done (deliberately, and documented): library `<provider>`/`<receiver>` entries are
not manifest-merged, resources are merged non-namespaced with `--auto-add-overlay`,
and dependency versions are whatever the harvested cache contains.

## The Kotlin runtime, and why a Java-only app has one

`setup.sh` step 8b vendors `kotlin-stdlib.jar` and `kotlin-annotations.jar` from the
official Kotlin distribution on npm (`kotlin-compiler`, pinned to 1.9.25 — the
generation the vendored AndroidX was built against; the 2.x stdlib raises its own
Android floor). The app's own source can stay 100% Java, but Google compiles much of
AndroidX from Kotlin, so classes we call reach `kotlin.jvm.internal.Intrinsics` on
ordinary paths. Gradle adds the stdlib transitively; here `lib.sh` appends it to the
AndroidX classpath as a **program input** (it must land in the dex), and the
annotations jar goes in as a **library** input (CLASS-retention metadata, never
loaded on a device, so it does not ship).

Without it the build still succeeded — R8 only reports a missing class — and the APK
died at launch. That is what point 1 of the artifact check above makes impossible.

## Source levels

| `--source` | Behaviour |
|---|---|
| `8` (default) | `android.jar` is the **bootclasspath**: `java.*` resolves to Android's stubs, so a Java API Android does not have is a real error. API-accurate. Lambdas work via the locally built `core-lambda-stubs.jar`. |
| `11`+ | Records, sealed types, `var`, text blocks, pattern matching, `Stream.toList()` all compile and dex. `java.*` comes from the JRE instead, so **API checking is loosened** (the module system forbids `-bootclasspath` at 9+). |

## Caveats worth knowing

* The platform classes in `android.jar` are stubs that throw `Stub!` at runtime — JVM
  unit tests must exercise framework-free logic (which is where the value is anyway).
* `android.jar` is API 34 by default; `--api 30…37` fetches another level from GitHub
  only when you run `setup.sh --api N` (the jar then lives in your vendor dir).
* The APK is signed with the bundled **debug** keystore (alias `androiddebugkey`,
  password `android`) — fine for installing/testing, not for publishing.
* Compilation says nothing about the *runtime* floor: the compiler only ever sees API
  34, so a call added in a newer API compiles happily into a `min-api 19` dex. The
  free check here is the AAR audit (`aar_floor.py`) plus `minSdk` consistency in
  `verify_apk.py`; checking every `android.*` reference against an API-19 jar needs a
  second `android.jar` and a class-file reader.
* `vendor/` is gitignored and reproducible: deleting it and re-running `setup.sh`
  takes ~11 s, and `androidx.sh` rebuilds the AndroidX part in ~31 s.
