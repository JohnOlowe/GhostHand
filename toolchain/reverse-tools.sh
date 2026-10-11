#!/usr/bin/env bash
# reverse-tools.sh -- fetch, exercise, and use JADX, Apktool, and CFR without
# installing them into the checkout. All downloaded/build tools live in a
# disposable external temp directory; requested decompilation output is kept.
#
#   bash toolchain/reverse-tools.sh test
#   bash toolchain/reverse-tools.sh jadx APK OUT_DIR
#   bash toolchain/reverse-tools.sh apktool APK OUT_DIR [--rebuild]
#   bash toolchain/reverse-tools.sh cfr CLASS_OR_JAR OUT_DIR
#   bash toolchain/reverse-tools.sh all APK OUT_DIR [--cfr CLASS_OR_JAR]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HERE="$ROOT/toolchain"
MODE="${1:-help}"
[ $# -eq 0 ] || shift

usage() {
  cat <<'USAGE'
Usage:
  bash toolchain/reverse-tools.sh test
      Build a sample APK in a temporary directory; smoke-test Apktool decode +
      rebuild, JADX APK-to-Java, and CFR on the sample's pre-dex .class file.
      Temporary tools and test outputs are removed automatically.

  bash toolchain/reverse-tools.sh jadx APK OUT_DIR
      Decompile DEX/APK to Java and resources with JADX.

  bash toolchain/reverse-tools.sh apktool APK OUT_DIR [--rebuild]
      Decode an APK to resources + smali. --rebuild also writes
      OUT_DIR/repacked-unsigned.apk (not signed).

  bash toolchain/reverse-tools.sh cfr CLASS_OR_JAR OUT_DIR
      Decompile JVM .class/.jar bytecode with CFR. CFR does not read DEX/APK.

  bash toolchain/reverse-tools.sh all APK OUT_DIR [--cfr CLASS_OR_JAR]
      Decode the APK with both JADX and Apktool; optionally decompile a separate
      JVM .class/.jar input with CFR into OUT_DIR/cfr.

Every invocation downloads/bootstrap-builds its tools in a fresh temp directory
outside the repository and removes that directory on exit. Requested output
folders are separate and are not deleted. Network access to PyPI, npm, and GitHub
Git is required. See REVERSE-TOOLS.md for version/provenance caveats.
USAGE
}

if [ "$MODE" = help ] || [ "$MODE" = -h ] || [ "$MODE" = --help ]; then
  usage
  exit 0
fi

fail() { printf 'reverse-tools: %s\n' "$*" >&2; exit 1; }
for bin in bash curl git python3 realpath sha512sum sort tar; do
  command -v "$bin" >/dev/null 2>&1 || fail "missing required command: $bin"
done

case "$MODE" in
  test) [ $# -eq 0 ] || fail "test takes no extra arguments" ;;
  jadx|cfr)
    [ $# -eq 2 ] || { usage >&2; exit 2; }
    [ -f "$1" ] || fail "input does not exist: $1"
    ;;
  apktool)
    [ $# -eq 2 ] || [ $# -eq 3 ] || { usage >&2; exit 2; }
    [ -f "$1" ] || fail "APK does not exist: $1"
    [ $# -eq 2 ] || [ "$3" = --rebuild ] || fail "unknown apktool option: $3"
    ;;
  all)
    [ $# -eq 2 ] || [ $# -eq 4 ] || { usage >&2; exit 2; }
    [ -f "$1" ] || fail "APK does not exist: $1"
    if [ $# -eq 4 ]; then
      [ "$3" = --cfr ] || fail "expected --cfr CLASS_OR_JAR"
      [ -f "$4" ] || fail "CFR input does not exist: $4"
    fi
    ;;
  *) usage >&2; exit 2 ;;
esac

TMP_PARENT="${TMPDIR:-/tmp}"
mkdir -p "$TMP_PARENT"
WORK="$(mktemp -d "$TMP_PARENT/ghosthand-reverse.XXXXXXXX")" || fail "could not create temp directory"
WORK="$(realpath "$WORK")"
case "$WORK/" in
  "$ROOT/"*) rm -rf -- "$WORK"; fail "temp directory must be outside the repository" ;;
esac
cleanup() {
  local status=$?
  trap - EXIT
  rm -rf -- "$WORK"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# Keep even package-manager/JVM caches and user preferences inside WORK. A run
# cannot silently leave a second copy under ~/.cache, ~/.local, or ~/.java.
export HOME="$WORK/home"
export XDG_CACHE_HOME="$WORK/xdg-cache"
export XDG_CONFIG_HOME="$WORK/xdg-config"
export XDG_DATA_HOME="$WORK/xdg-data"
export PIP_CACHE_DIR="$WORK/pip-cache"
mkdir -p "$HOME" "$XDG_CACHE_HOME" "$XDG_CONFIG_HOME" "$XDG_DATA_HOME" "$PIP_CACHE_DIR"

printf '==> Bootstrap Android Java tools into disposable external temp dir\n'
KEEP_TMP=0 bash "$HERE/setup.sh" --vendor "$WORK/vendor" >"$WORK/bootstrap.log" 2>&1 \
  || { tail -100 "$WORK/bootstrap.log" >&2; fail "toolchain bootstrap failed"; }
JAVA="$WORK/vendor/jre/bin/java"
ECJ="$WORK/vendor/ecj.jar"
APKTOOL="$WORK/vendor/apktool.jar"
[ -x "$JAVA" ] && [ -s "$ECJ" ] && [ -s "$APKTOOL" ] \
  || fail "bootstrap did not produce the expected Java, ECJ, and Apktool files"
# setup.sh writes its environment next to an external --vendor directory.
GH_ENV="$WORK/env.sh"
[ -s "$GH_ENV" ] || fail "setup.sh did not write $GH_ENV"

# This pinned npm package bundles a runnable Jadx CLI distribution. Verify the
# npm registry integrity value before extracting any binary.
JADX_TGZ="$WORK/jadx-node.tgz"
JADX_SRI='sha512-Q3/hsNEE7uaY6K8oGvHE4ridtx+M7ndBvj98PG6C8l0EKs/DiqATaOFheQ48v07SlhFWZV9/vozQ1XUj7Rs10g=='
curl -fLsS --retry 3 --max-time 600 \
  'https://registry.npmjs.org/@mishguru/jadx-node/-/jadx-node-3.1.0.tgz' \
  -o "$JADX_TGZ" || fail "could not download pinned @mishguru/jadx-node 3.1.0"
python3 - "$JADX_TGZ" "$JADX_SRI" <<'PY' || fail "JADX npm tarball failed SHA-512 integrity check"
import base64, hashlib, sys
path, expected = sys.argv[1:]
actual = "sha512-" + base64.b64encode(hashlib.sha512(open(path, "rb").read()).digest()).decode()
if actual != expected:
    print("expected:", expected, "\nactual:  ", actual, file=sys.stderr)
    raise SystemExit(1)
PY
mkdir -p "$WORK/jadx"
tar xzf "$JADX_TGZ" -C "$WORK/jadx" --strip-components=2 package/resources/jadx-1.1.0
JADX="$WORK/jadx/jadx-1.1.0/bin/jadx"
[ -f "$JADX" ] || fail "JADX executable missing from pinned package"
chmod +x "$JADX"
export JAVA_HOME="$WORK/vendor/jre"
export PATH="$JAVA_HOME/bin:$PATH"
JADX_VERSION=$("$JADX" --version 2>&1 | tail -1 | tr -d '\r')
[ "$JADX_VERSION" = 1.1.0 ] || fail "expected bundled Jadx 1.1.0, got: $JADX_VERSION"

# Compile CFR from its upstream 0.152 tag because the official Maven / website
# downloads are blocked here. No CFR binary is checked into the repository.
CFR_COMMIT='68477be3ff7171ee17ddd1a26064b9b253f1604f'
CFR_SRC="$WORK/cfr-source"
git clone -q --depth 1 --branch 0.152 https://github.com/leibnitz27/cfr.git "$CFR_SRC" \
  >"$WORK/cfr-clone.log" 2>&1 || { cat "$WORK/cfr-clone.log" >&2; fail "could not fetch CFR source tag 0.152"; }
[ "$(git -C "$CFR_SRC" rev-parse HEAD)" = "$CFR_COMMIT" ] \
  || fail "CFR tag resolved to an unexpected commit"
CFR_GEN="$WORK/cfr-generated/org/benf/cfr/reader/util/CfrVersionInfo.java"
CFR_CLASSES="$WORK/cfr-classes"
CFR_JAR="$WORK/cfr-0.152.jar"
mkdir -p "$(dirname "$CFR_GEN")" "$CFR_CLASSES"
python3 - "$CFR_SRC/src-templates/org/benf/cfr/reader/util/CfrVersionInfo.java" "$CFR_GEN" "$CFR_COMMIT" <<'PY'
import sys
src, dst, commit = sys.argv[1:]
text = open(src, encoding="utf-8").read()
for old, new in (("${project.version}", "0.152"),
                 ("${git.commit.id.abbrev}", commit[:8]),
                 ("${git.dirty}", "false")):
    if old not in text:
        raise SystemExit("CFR version template changed; refusing an unreviewed build")
    text = text.replace(old, new)
open(dst, "w", encoding="utf-8").write(text)
PY
mapfile -d '' CFR_SOURCES < <(find "$CFR_SRC/src" -type f -name '*.java' -print0 | sort -z)
CFR_SOURCES+=("$CFR_GEN")
printf '    compiling %s CFR Java sources with the bundled ECJ\n' "${#CFR_SOURCES[@]}"
"$JAVA" -jar "$ECJ" -source 8 -target 8 -encoding UTF-8 -proc:none -nowarn \
  -d "$CFR_CLASSES" "${CFR_SOURCES[@]}" >"$WORK/cfr-compile.log" 2>&1 \
  || { tail -100 "$WORK/cfr-compile.log" >&2; fail "CFR source compilation failed"; }
python3 - "$CFR_CLASSES" "$CFR_SRC/LICENSE" "$CFR_JAR" <<'PY'
import os, sys, zipfile
root, license_path, output = sys.argv[1:]
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as jar:
    jar.writestr("META-INF/MANIFEST.MF",
                 "Manifest-Version: 1.0\r\nMain-Class: org.benf.cfr.reader.Main\r\n\r\n")
    jar.write(license_path, "META-INF/licenses/CFR-LICENSE")
    for base, _dirs, files in os.walk(root):
        for name in sorted(files):
            path = os.path.join(base, name)
            jar.write(path, os.path.relpath(path, root))
PY
CFR_VERSION_OUTPUT=$("$JAVA" -jar "$CFR_JAR" --version 2>&1 || true)
CFR_VERSION=$(printf '%s\n' "$CFR_VERSION_OUTPUT" | grep -m1 -E '^CFR 0\.152([[:space:]]|$)' | tr -d '\r' || true)
[ "$CFR_VERSION" = 'CFR 0.152' ] \
  || { printf '%s\n' "$CFR_VERSION_OUTPUT" >&2; fail "CFR source build did not run (reported '$CFR_VERSION')"; }

APKTOOL_VERSION=$("$JAVA" -jar "$APKTOOL" --version 2>&1 || true)
printf '%s\n' "$APKTOOL_VERSION" | grep -q '2\.4\.1' \
  || fail "expected pinned Apktool 2.4.1"
printf '    ready: Jadx %s, Apktool 2.4.1, %s (CFR source commit %s)\n' \
  "$JADX_VERSION" "$CFR_VERSION" "${CFR_COMMIT:0:8}"

# Run Apktool under the legacy JDK child-process launcher. The Debian sandbox
# blocks JDK 25 posix_spawn when Apktool rebuilds resources; FORK works and is
# only applied to these Apktool JVM invocations.
apktool() {
  local opts="${JAVA_TOOL_OPTIONS:-}"
  opts="${opts:+$opts }-Djdk.lang.Process.launchMechanism=FORK"
  JAVA_TOOL_OPTIONS="$opts" "$JAVA" -Djava.util.prefs.userRoot="$WORK/java-prefs" \
    -Djava.util.prefs.systemRoot="$WORK/java-prefs-system" -jar "$APKTOOL" "$@"
}

run_jadx() {
  local apk="$1" out="$2"
  [ -f "$apk" ] || fail "APK does not exist: $apk"
  [ ! -e "$out" ] || fail "refusing to overwrite existing output path: $out"
  "$JADX" -d "$out" "$apk"
}
run_apktool() {
  local apk="$1" out="$2" rebuild="${3:-0}"
  [ -f "$apk" ] || fail "APK does not exist: $apk"
  [ ! -e "$out" ] || fail "refusing to overwrite existing output path: $out"
  apktool d -f -p "$WORK/apktool-framework" -o "$out" "$apk"
  if [ "$rebuild" = 1 ]; then
    apktool b -f -p "$WORK/apktool-framework" -o "$out/repacked-unsigned.apk" "$out"
  fi
}
run_cfr() {
  local input="$1" out="$2"
  [ -f "$input" ] || fail "CFR input does not exist: $input"
  [ ! -e "$out" ] || fail "refusing to overwrite existing output path: $out"
  "$JAVA" -Djava.util.prefs.userRoot="$WORK/java-prefs" \
    -Djava.util.prefs.systemRoot="$WORK/java-prefs-system" \
    -jar "$CFR_JAR" "$input" --outputdir "$out"
}

case "$MODE" in
  test)
    mkdir -p "$WORK/project"
    python3 - "$ROOT/sample" "$WORK/project" <<'PY'
import shutil, sys
shutil.copytree(sys.argv[1], sys.argv[2], dirs_exist_ok=True,
                ignore=shutil.ignore_patterns("build"))
PY
    printf '==> Build disposable sample APK and verify it\n'
    env -u ANDROIDX_DIR GH_ANDROIDX=off GH_TOOLCHAIN_ENV="$GH_ENV" \
      bash "$HERE/build.sh" "$WORK/project" --out "$WORK/sample.apk" --verify \
      >"$WORK/sample-build.log" 2>&1 \
      || { tail -100 "$WORK/sample-build.log" >&2; fail "sample APK build failed"; }
    grep -q 'BUILD OK' "$WORK/sample-build.log" || fail "sample APK build did not report success"

    printf '==> Apktool decode + round-trip rebuild smoke test\n'
    apktool d -f -p "$WORK/apktool-framework" -o "$WORK/apktool-out" "$WORK/sample.apk" \
      >"$WORK/apktool-decode.log" 2>&1 \
      || { tail -80 "$WORK/apktool-decode.log" >&2; fail "Apktool decode failed"; }
    [ -s "$WORK/apktool-out/AndroidManifest.xml" ] \
      || fail "Apktool did not decode AndroidManifest.xml"
    [ -s "$WORK/apktool-out/smali/com/example/ghosthand/TapCounter.smali" ] \
      || fail "Apktool did not baksmali the sample class"
    apktool b -f -p "$WORK/apktool-framework" -o "$WORK/apktool-roundtrip.apk" "$WORK/apktool-out" \
      >"$WORK/apktool-build.log" 2>&1 \
      || { tail -100 "$WORK/apktool-build.log" >&2; fail "Apktool rebuild failed"; }
    python3 - "$WORK/apktool-roundtrip.apk" <<'PY' || fail "Apktool rebuilt APK is incomplete"
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as apk:
    names = set(apk.namelist())
    required = {"AndroidManifest.xml", "classes.dex", "resources.arsc"}
    missing = sorted(required - names)
    if missing:
        print("missing entries:", missing, file=sys.stderr)
        raise SystemExit(1)
    print("    round-trip APK: %d entries, manifest + resources.arsc + classes.dex" % len(names))
PY

    printf '==> JADX APK/DEX to Java smoke test\n'
    "$JADX" -d "$WORK/jadx-out" "$WORK/sample.apk" >"$WORK/jadx.log" 2>&1 \
      || { tail -100 "$WORK/jadx.log" >&2; fail "JADX failed"; }
    [ -s "$WORK/jadx-out/sources/com/example/ghosthand/TapCounter.java" ] \
      || fail "JADX did not write the expected Java source"
    grep -q 'tapped once' "$WORK/jadx-out/sources/com/example/ghosthand/TapCounter.java" \
      || fail "JADX output did not preserve the sample behavior"

    printf '==> CFR JVM .class to Java smoke test\n'
    CLASS="$WORK/project/build/stage/classes/com/example/ghosthand/TapCounter.class"
    [ -s "$CLASS" ] || fail "ECJ did not leave the pre-dex class file"
    "$JAVA" -Djava.util.prefs.userRoot="$WORK/java-prefs" \
      -Djava.util.prefs.systemRoot="$WORK/java-prefs-system" \
      -jar "$CFR_JAR" "$CLASS" --outputdir "$WORK/cfr-out" \
      >"$WORK/cfr.log" 2>&1 \
      || { tail -80 "$WORK/cfr.log" >&2; fail "CFR decompilation failed"; }
    [ -s "$WORK/cfr-out/com/example/ghosthand/TapCounter.java" ] \
      || fail "CFR did not write the expected Java source"
    grep -q 'tapped once' "$WORK/cfr-out/com/example/ghosthand/TapCounter.java" \
      || fail "CFR output did not preserve the sample behavior"

    printf '\nREVERSE TOOLS OK\n'
    printf '  Apktool 2.4.1: decoded smali/resources and rebuilt a round-trip APK\n'
    printf '  JADX %s: decompiled APK DEX to Java and resources\n' "$JADX_VERSION"
    printf '  %s: decompiled the sample JVM .class (CFR source %s)\n' \
      "$CFR_VERSION" "${CFR_COMMIT:0:8}"
    printf '  all test APKs, sources, tools, and caches will be deleted on exit\n'
    ;;
  jadx)
    [ $# -eq 2 ] || { usage >&2; exit 2; }
    run_jadx "$1" "$2"
    printf 'JADX wrote Java/resources to %s\n' "$2"
    ;;
  apktool)
    [ $# -eq 2 ] || [ $# -eq 3 ] || { usage >&2; exit 2; }
    rebuild=0
    if [ $# -eq 3 ]; then
      [ "$3" = --rebuild ] || fail "unknown apktool option: $3"
      rebuild=1
    fi
    run_apktool "$1" "$2" "$rebuild"
    printf 'Apktool wrote smali/resources to %s\n' "$2"
    [ "$rebuild" = 0 ] || printf 'Unsigned rebuilt APK: %s/repacked-unsigned.apk\n' "$2"
    ;;
  cfr)
    [ $# -eq 2 ] || { usage >&2; exit 2; }
    run_cfr "$1" "$2"
    printf 'CFR wrote Java source to %s\n' "$2"
    ;;
  all)
    [ $# -ge 2 ] || { usage >&2; exit 2; }
    APK="$1"
    OUT="$2"
    shift 2
    CFR_INPUT=""
    if [ $# -gt 0 ]; then
      [ $# -eq 2 ] && [ "$1" = --cfr ] || { usage >&2; exit 2; }
      CFR_INPUT="$2"
      [ -f "$CFR_INPUT" ] || fail "CFR input does not exist: $CFR_INPUT"
    fi
    [ -f "$APK" ] || fail "APK does not exist: $APK"
    mkdir -p "$OUT"
    run_jadx "$APK" "$OUT/jadx"
    run_apktool "$APK" "$OUT/apktool" 0
    if [ -n "$CFR_INPUT" ]; then run_cfr "$CFR_INPUT" "$OUT/cfr"; fi
    printf 'JADX + Apktool wrote results under %s\n' "$OUT"
    [ -z "$CFR_INPUT" ] || printf 'CFR wrote JVM bytecode source under %s/cfr\n' "$OUT"
    ;;
esac
