# JADX, Apktool, and CFR in the restricted sandbox

This repository can build and smoke-test all three tools without an Android SDK,
Gradle, Maven, root, or GitHub Actions. The reproducible entry point is
`toolchain/reverse-tools.sh`; it fetches/builds its tools into a fresh temporary
directory **outside this checkout**, keeps only the output paths you requested,
and removes the toolchain, caches, test APKs, and intermediates on exit.

## Use it

```bash
# Empirically rebuild the sample, then smoke-test all three tools.
bash toolchain/reverse-tools.sh test

# APK -> Java/resources with JADX and resources/smali with Apktool.
bash toolchain/reverse-tools.sh all /path/to/app.apk /tmp/app-reverse

# CFR takes JVM .class/.jar, not DEX/APK; pass that separately if available.
bash toolchain/reverse-tools.sh all /path/to/app.apk /tmp/app-reverse \
  --cfr /path/to/classes.jar

# Individual operations:
bash toolchain/reverse-tools.sh jadx /path/to/app.apk /tmp/app-java
bash toolchain/reverse-tools.sh apktool /path/to/app.apk /tmp/app-smali --rebuild
bash toolchain/reverse-tools.sh cfr /path/to/library.jar /tmp/library-java
```

`--rebuild` writes `OUT_DIR/repacked-unsigned.apk`; it is deliberately not signed.
The script refuses to overwrite existing output directories. Successful outputs
remain where requested; **tools are deleted automatically** at process exit. If a
command fails, its temporary logs are printed before cleanup. The base Android
vendor cache alone was about 221 MB in this audit, with temporary source/package
files on top; the fresh full test took about 31 seconds. All of that scratch space
is external, is not a deliverable, and is deleted.

For repeatable integration with the existing Java/XML build scripts, keep the
whole toolchain external too:

```bash
bash toolchain/setup.sh --vendor /tmp/ghosthand-tools/vendor
# setup.sh writes /tmp/ghosthand-tools/env.sh for that external vendor directory.
GH_TOOLCHAIN_ENV=/tmp/ghosthand-tools/env.sh bash toolchain/check.sh sample
GH_TOOLCHAIN_ENV=/tmp/ghosthand-tools/env.sh bash toolchain/build.sh sample --verify
rm -rf /tmp/ghosthand-tools sample/build
```

`GH_TOOLCHAIN_ENV` makes an external `--vendor` install usable without a symlink
or generated `toolchain/env.sh` in the checkout. Prefer the one-shot reverse-tools
script when you want cleanup to happen automatically.

## What the script fetches/builds

| Tool | Source in this sandbox | Version used in the test | Role |
|---|---|---:|---|
| Java runtime + ECJ compiler | GhostHand's existing `toolchain/setup.sh` (PyPI `jdk4py`; Eclipse compiler from npm `@vscjava/java-language-server`) | Temurin JRE **25.0.2**; ECJ **3.45** | Runs the Java tools, builds CFR from source, and creates the temporary sample APK |
| Apktool | npm [`apktool-jar@2.4.1`](https://www.npmjs.com/package/apktool-jar/v/2.4.1), extracted to the temporary external vendor directory by `setup.sh` | **2.4.1** | Decodes APK resources/manifest and DEX to editable XML + smali; can rebuild an unsigned APK |
| JADX | npm [`@mishguru/jadx-node@3.1.0`](https://www.npmjs.com/package/@mishguru/jadx-node/v/3.1.0), which bundles the Jadx CLI distribution | **Jadx 1.1.0** | Decompiles APK/DEX to Java source and resources |
| CFR | Official [`leibnitz27/cfr`](https://github.com/leibnitz27/cfr) Git tag `0.152`, checked at commit `68477be3ff7171ee17ddd1a26064b9b253f1604f` | **0.152**, built from the checked-out source with ECJ (`-source 8 -target 8`) | Decompiles JVM `.class` files and JARs to Java source |

The helper pins and checks the Jadx npm tarball's SHA-512 integrity value and the
extracted Apktool JAR's SHA-512. It verifies the CFR Git commit before compiling.
A registry integrity hash detects a changed download; **it is not a vendor
signature or provenance proof**. CFR is built from the upstream source tag rather
than a mirrored binary.

## Why these versions are not the newest vendor releases

The sandbox currently allows GitHub Git/API, PyPI, and the npm registry, but
blocks Maven Central, Bitbucket, `www.benf.org`, and GitHub release-asset hosts.
The official Jadx release zip redirects to an inaccessible release-asset host;
the official Apktool release jar has the same problem. The existing npm Apktool
package only supplies 2.4.1. Consequently:

- **JADX:** the actual smoke test used the NPM-bundled **1.1.0**, not the current
  upstream release. This package dates from 2020 and may not handle newer DEX
  features or formats. Use the current official Jadx distribution from
  [skylot/jadx releases](https://github.com/skylot/jadx/releases) when the release
  downloads are reachable; the fallback is for proving that the sandbox can
  execute a real JADX CLI, not for claiming current-version coverage.
- **Apktool:** **2.4.1** is also old. The current upstream v3 release cannot be
  fetched here. The test establishes decode and rebuild on GhostHand's small API
  34 sample only; it is not a compatibility claim for every APK. Use an official
  current Apktool release when its release/Maven download is reachable.
- **CFR:** the source is the upstream 0.152 tag, and the built tool runs on the
  temporary JRE. CFR understands Java bytecode, **not Dalvik DEX**. For Android
  APKs, use JADX directly; CFR is useful for `.class`/`.jar` inputs or after a
  separate DEX-to-JVM-bytecode conversion.

The third-party NPM jars are convenient test inputs, not authenticated upstream
release artifacts. The scripts do not store them in Git, and clean their copies
when the operation ends.

## Sandbox-specific Apktool rebuild workaround

Apktool 2.4.1 decodes with its Java code, then starts the bundled native `aapt`
when it rebuilds resources. On this Debian sandbox, Java 25's default
`posix_spawn` child-process launcher is blocked, so the first rebuild attempt
failed even though `aapt` itself runs from a shell. For these Apktool invocations
the helper sets:

```text
-Djdk.lang.Process.launchMechanism=FORK
```

The JDK's legacy `FORK` launcher succeeds here. It is scoped to the Apktool JVM;
normal Java/JADX/CFR invocations and the system environment are unchanged. The
helper also puts Apktool framework files and Java preferences under its temp
folder instead of leaving state in `~/.local` or `~/.java`.

## Results observed from a fresh disposable tool cache

On Debian 12 x86-64 with the repository's sample project:

```text
Apktool 2.4.1: decoded manifest/resources/smali, rebuilt an unsigned round-trip APK
               (5 entries: AndroidManifest.xml, resources.arsc, classes.dex, ...)
JADX 1.1.0:    decoded the APK to Java + resources; TapCounter.java contained its
               expected method and string
CFR 0.152:     compiled 733 upstream Java sources with ECJ and decompiled the
               pre-dex TapCounter.class back to Java
```

`bash toolchain/reverse-tools.sh test` prints `REVERSE TOOLS OK`, then deletes the
JRE/compiler, downloaded packages, source clone, temporary APKs, and test outputs.
A separate `all`-mode run also preserved requested outputs under `/tmp/app-reverse`
while leaving no tool temp directory behind. These are functional CLI/decompilation
checks; they do not recover original source, prove every decompiler result correct,
or exercise Android runtime behavior.
