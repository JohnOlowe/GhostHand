# toolchain/ — Android Java + XML builds with no SDK, no Gradle, no Maven

Everything in here runs with **no root, no apt and no Android SDK**: the tools come
from PyPI, npm and GitHub. See [`../RECIPE.md`](../RECIPE.md) for the full write-up
and the reasoning behind each choice (section 9 covers AndroidX).

```bash
bash toolchain/setup.sh                     # fetch + verify everything (~10 s, ~170 MB)
                                            #   --api 35          another platform level
                                            #   --vendor /tmp/tc  keep it out of the repo
                                            #   KEEP_TMP=1        keep the download cache

bash toolchain/check.sh  sample              # fast test-compile (XML + Java + D8)
bash toolchain/test.sh   sample              # compile AND run JUnit 4 unit tests
bash toolchain/build.sh  sample --verify     # signed, aligned, verified APK

# AndroidX: once, from a committed Gradle cache on GitHub (no Maven, ~35 s)
bash toolchain/androidx.sh
bash toolchain/check.sh  sample-androidx
bash toolchain/build.sh  sample-androidx --release --verify
```

All three accept a flat project (`AndroidManifest.xml`, `res/`, `src/`) or the
Gradle layout (`src/main/AndroidManifest.xml`, `src/main/res`, `src/main/java`).

| file | what it is |
|---|---|
| `setup.sh` | downloads JRE, ECJ, JUnit, D8/R8, apksigner, android.jar, aapt2, apktool, lambda stubs; runs each one to prove it works; writes `env.sh` |
| `env.sh` | generated; `source` it to get `JAVA_HOME`, `ECJ_JAR`, `D8_JAR`, `AAPT2`, … |
| `lib.sh` | shared build helpers (layout detection, aapt2/ECJ/dex wrappers, AndroidX wiring) |
| `check.sh` | XML lint → aapt2 compile/link → ECJ → D8. The "does it compile?" loop, ~2 s |
| `test.sh` | adds `test/` (or `src/test/java`) sources and runs JUnit 4 on the JRE |
| `build.sh` | the full pipeline: resources, manifest, R.java, Java, dex, zipalign, sign, verify |
| `xmlcheck.py` | stdlib-only Android XML/manifest linter (runs before aapt2) |
| `zipalign.py` | pure-Python zipalign (rewrite + `-c` check), since build-tools is unreachable |
| `filter_android_jar.py` | strips JRE-owned packages from android.jar for `-source 11/17` |
| `lambda-stubs/` | the one class `android.jar` lacks (`LambdaMetafactory`); `setup.sh` compiles it into `vendor/core-lambda-stubs.jar` |
| `androidx.sh` | fetches AndroidX and assembles `vendor/androidx/` (one-off, needs the network) |
| `resolve_signing` (in `lib.sh`) | picks the keystore to sign with: `SIGN_KEYSTORE`/`SIGN_*` env vars first, then the project's `gradle.properties` + `keystore/`, then the bundled debug key |
| `androidx_fetch.py` | blobless clone of the source repo + fetch of the selected AARs/jars |
| `select_androidx.py` | picks the highest version of each wanted artifact, skips `-sources`/`-javadoc`/KTX/test-only |
| `extract_aar.py` | explodes AARs (classes.jar, res/, R.txt, AndroidManifest.xml, libs/, assets/) |
| `androidx_assemble.py` | merges the jars, compiles every library `res/`, writes `packages.txt` |

## Options

```
build.sh DIR [--api N] [--min-api N] [--source 8|11|17|21|25] [--release]
             [--out FILE] [--verify] [--no-androidx]
check.sh DIR [--api N] [--min-api N] [--source N] [--no-dex] [--no-xml-lint]
             [--no-androidx] [--full-dex]
test.sh  DIR [--source N] [--filter SomeTest] [--no-androidx]
```

Without `--release` the build is a debug build in both senses: D8 instead of R8, **and**
`android:debuggable="true"` — `aapt2 link --debug-mode` sets that, which is what AGP does
for a debug variant. Leave it out and you get an APK that is merely unshrunk: no attachable
debugger, no `run-as`, and nothing in logcat indicating it is debuggable.

## AndroidX (optional, automatic once installed)

`toolchain/androidx.sh` builds `vendor/androidx/`:

| path | what it is | how it is used |
|---|---|---|
| `androidx.jar` | every library's `classes.jar` + the plain jars (annotation, collection, lifecycle-common, …) merged | ECJ `-classpath`, D8/R8 program input |
| `res/*.zip` | `aapt2 compile` output per library | `aapt2 link -R` per library |
| `packages.txt` | the 46 library packages | `aapt2 link --extra-packages` → a correct `R.java` per library, styleables included |
| `aar/`, `src/` | exploded AARs, downloaded artifacts | rebuild inputs (`--force`) |

A project gets AndroidX automatically when it mentions `androidx.`,
`Theme.AppCompat`, `Theme.Material3`, `MaterialComponents` or
`com.google.android.material` anywhere in `src/`, `res/` or the manifest — the plain
`sample/` therefore keeps building in 2 s with an 8 KB dex. Force it either way with
`GH_ANDROIDX=on|off` (or `--no-androidx`).

Not done (deliberately, and documented): library `<provider>`/`<receiver>` entries are
not manifest-merged, resources are merged non-namespaced with `--auto-add-overlay`,
and dependency versions are whatever the harvested cache contains.

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
* Signing: by default the APK is signed with the bundled **debug** keystore (alias
  `androiddebugkey`, password `android`) — fine for installing/testing, not for
  publishing. A project that commits its own key wins instead: `resolve_signing()`
  reads `GHOSTHAND_STORE_FILE` / `_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` from the
  project's or the repo root's `gradle.properties`, and falls back to
  `keystore/damjay_debug.keystore` if it finds one. That is what keeps every GhostHand
  build signed with one stable key, so a new APK installs over the previous one.
  PKCS12 has no separate key password (keytool says so and ignores the difference), so a
  key password shorter than the store password is treated as a typo and replaced by the
  store password.
* `vendor/` is gitignored and reproducible: deleting it and re-running `setup.sh`
  takes ~10 s, and `androidx.sh` rebuilds the AndroidX part in ~35 s.
