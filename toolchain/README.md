# toolchain/ — Android Java + XML builds with no SDK, no Gradle, no Maven

Everything in here runs with **no root, no apt and no Android SDK**: the tools come
from PyPI, npm and GitHub. See [`../RECIPE.md`](../RECIPE.md) for the full write-up
and the reasoning behind each choice.

```bash
bash toolchain/setup.sh                     # fetch + verify everything (~10 s, ~170 MB)
                                            #   --api 35          another platform level
                                            #   --vendor /tmp/tc  keep it out of the repo
                                            #   KEEP_TMP=1        keep the download cache

bash toolchain/check.sh  sample              # fast test-compile (XML + Java + D8)
bash toolchain/test.sh   sample              # compile AND run JUnit 4 unit tests
bash toolchain/build.sh  sample --verify     # signed, aligned, verified APK
```

All three accept a flat project (`AndroidManifest.xml`, `res/`, `src/`) or the
Gradle layout (`src/main/AndroidManifest.xml`, `src/main/res`, `src/main/java`).

| file | what it is |
|---|---|
| `setup.sh` | downloads JRE, ECJ, JUnit, D8/R8, apksigner, android.jar, aapt2, apktool; runs each one to prove it works; writes `env.sh` |
| `env.sh` | generated; `source` it to get `JAVA_HOME`, `ECJ_JAR`, `D8_JAR`, `AAPT2`, … |
| `lib.sh` | shared build helpers (layout detection, aapt2/ECJ/dex wrappers) |
| `check.sh` | XML lint → aapt2 compile/link → ECJ → D8. The "does it compile?" loop, ~2 s |
| `test.sh` | adds `test/` (or `src/test/java`) sources and runs JUnit 4 on the JRE |
| `build.sh` | the full pipeline: resources, manifest, R.java, Java, dex, zipalign, sign, verify |
| `xmlcheck.py` | stdlib-only Android XML/manifest linter (runs before aapt2) |
| `zipalign.py` | pure-Python zipalign (rewrite + `-c` check), since build-tools is unreachable |
| `filter_android_jar.py` | strips JRE-owned packages from android.jar for `-source 11/17` |

## Options

```
build.sh DIR [--api N] [--min-api N] [--source 8|11|17|21|25] [--release]
             [--out FILE] [--verify]
check.sh DIR [--api N] [--min-api N] [--source N] [--no-dex] [--no-xml-lint]
test.sh  DIR [--source N] [--filter SomeTest]
```

## Source levels

| `--source` | Behaviour |
|---|---|
| `8` (default) | `android.jar` is the **bootclasspath**: `java.*` resolves to Android's stubs, so a Java API Android does not have is a real error. API-accurate. |
| `11`+ | Records, sealed types, `var`, text blocks, pattern matching, `Stream.toList()` all compile and dex. `java.*` comes from the JRE instead, so **API checking is loosened** (the module system forbids `-bootclasspath` at 9+). |

## Caveats worth knowing

* The platform classes in `android.jar` are stubs that throw `Stub!` at runtime — JVM
  unit tests must exercise framework-free logic (which is where the value is anyway).
* `android.jar` is API 34 by default; `--api 30…37` fetches another level from GitHub
  only when you run `setup.sh --api N` (the jar then lives in your vendor dir).
* The APK is signed with the bundled **debug** keystore (alias `androiddebugkey`,
  password `android`) — fine for installing/testing, not for publishing.
* `vendor/` is gitignored and reproducible: deleting it and re-running `setup.sh`
  takes ~10 seconds.
