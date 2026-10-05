#!/usr/bin/env bash
# build.sh -- build a signed, zip-aligned APK with no Gradle, no SDK, no Maven.
#
#   bash toolchain/build.sh PROJECT_DIR [--api 34] [--min-api 24] [--release]
#                                      [--source 8] [--out FILE] [--verify]
#                                      [--no-androidx] [--package NAME]
#
# Pipeline: xmllint -> aapt2 compile -> aapt2 link (+R.java) -> ECJ -> D8/R8
#           -> classes.dex into the APK -> zipalign.py -> apksigner -> verify
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"
load_env

TARGET_API=34
OUT=""
VERIFY=0
PROJ_ARG=""
while [ $# -gt 0 ]; do
  case "$1" in
    --api) TARGET_API="$2"; shift 2 ;;
    --min-api) MIN_API="$2"; shift 2 ;;
    --source) JAVA_SRC_LEVEL="$2"; shift 2 ;;
    --release) RELEASE=1; shift ;;
    --out) OUT="$2"; shift 2 ;;
    --verify) VERIFY=1; shift ;;
    --no-androidx) ANDROIDX_OFF=1; shift ;;
    --package) PKG_ARG="$2"; shift 2 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    -*) die "unknown option $1" ;;
    *) PROJ_ARG="$1"; shift ;;
  esac
done
[ -n "$PROJ_ARG" ] || die "usage: build.sh PROJECT_DIR [options]"
RELEASE="${RELEASE:-0}"
androidx_setup

detect_layout "$PROJ_ARG"
BUILD="$PROJ/build"
STAGE="$BUILD/stage"
APK_NAME="$(basename "$PROJ")"
UNSIGNED="$BUILD/$APK_NAME-unsigned.apk"
ALIGNED="$BUILD/$APK_NAME-aligned.apk"
FINAL="${OUT:-$BUILD/$APK_NAME.apk}"
mkdir -p "$BUILD" "$STAGE"
resolve_package "$PROJ" "${PKG_ARG:-}"
manifest_for_link "$BUILD"
START=$(date +%s)

msg "building $PROJ"
info "java $JAVA_SRC_LEVEL, min API $MIN_API, target API $TARGET_API, $( [ "$RELEASE" = 1 ] && echo 'R8 release' || echo 'D8 debug' )"
info "androidx: ${ANDROIDX_STATE:-none}"
[ -n "${ANDROIDX_RES_NOTE:-}" ] && info "$ANDROIDX_RES_NOTE"

# 1. XML pre-flight ---------------------------------------------------------
msg "1/7 XML lint"
python3 "$HERE/xmlcheck.py" "$PROJ" --package "${MANIFEST_PACKAGE:-}" \
    "${ANDROIDX_XML_ARGS[@]+"${ANDROIDX_XML_ARGS[@]}"}" \
  || die "XML problems above (aapt2 would fail on most of them too)"

# 2. resources --------------------------------------------------------------
# The libraries' own minSdkVersion: Gradle merges those manifests, we do not, so a
# dependency built for a newer platform would only show up as a NoSuchMethodError on
# the oldest device. Invisible exactly when the AndroidX set changes.
if [ -n "$ANDROIDX_CLASSES" ] && [ -f "$HERE/aar_floor.py" ]; then
  python3 "$HERE/aar_floor.py" "$MIN_API" "$ANDROIDX_DIR/aar" || die "an AndroidX library needs a newer platform than min-api $MIN_API"
fi

msg "2/7 aapt2 compile (res/**, *.xml -> flat resource table)"
aapt2_compile_res "$STAGE/res.zip"
info "$(unzip -l "$STAGE/res.zip" | tail -1 | awk '{print $2}') compiled resource entries"

# 3. link -------------------------------------------------------------------
msg "3/7 aapt2 link (manifest + resources -> base APK, emit R.java)"
RES_ZIP="$STAGE/res.zip"
rm -f "$UNSIGNED"
# AGP marks a debug build android:debuggable="true"; aapt2 only does it when
# asked, and without it the "debug" APK is merely unshrunk - no attached
# debugger, no `run-as`, nothing in logcat saying it is debuggable.
LINK_EXTRA=()
[ "$RELEASE" = "1" ] || LINK_EXTRA+=(--debug-mode)
aapt2_link "$UNSIGNED" "$STAGE/gen" --min-sdk-version "$MIN_API" --target-sdk-version "$TARGET_API" \
  "${LINK_EXTRA[@]+"${LINK_EXTRA[@]}"}" \
  --version-code "${VERSION_CODE:-1}" --version-name "${VERSION_NAME:-1.0}"

# 4. Java -------------------------------------------------------------------
msg "4/7 ECJ compile Java ($(count_java) sources + generated R.java)"
ecj_compile "$STAGE/classes" "$STAGE/gen"
ok "$(find "$STAGE/classes" -name '*.class' | wc -l | tr -d ' ') class files"

# 5. dex --------------------------------------------------------------------
msg "5/7 $( [ "$RELEASE" = 1 ] && echo 'R8 (shrink + dex)' || echo 'D8 (dex)' )"
CLASSES_DIR="$STAGE/classes"
DEX_ARGS=()
[ "$RELEASE" = "1" ] && DEX_ARGS=(--pg-map-output "$BUILD/mapping.txt")
dex "$STAGE/dex" "${DEX_ARGS[@]}"
cp "$STAGE/dex/classes.dex" "$STAGE/classes.dex"
info "classes.dex $(du -h "$STAGE/classes.dex" | cut -f1)"

python3 - "$UNSIGNED" "$STAGE/dex" <<'PY'
import os, sys, zipfile
apk, dexdir = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    for name in sorted(os.listdir(dexdir)):
        if name.endswith('.dex'):
            z.write(os.path.join(dexdir, name), name)
            print("    + %s" % name)
PY

# 6. align ------------------------------------------------------------------
msg "6/7 zipalign (pure Python: 4-byte alignment, .so pages)"
python3 "$HERE/zipalign.py" -f -p 4 "$UNSIGNED" "$ALIGNED"
python3 "$HERE/zipalign.py" -c -p 4 "$ALIGNED" | sed 's/^/    /'

# 7. sign -------------------------------------------------------------------
msg "7/7 apksigner (debug key) + verification"
# v1 (JAR) signatures are only useful below API 24 and are written *after*
# alignment, so enable them only when the minSdk actually needs them.
V1=false; [ "${MIN_API:-24}" -lt 24 ] && V1=true
"$JAVA" -jar "$APKSIGNER_JAR" sign \
  --ks "$DEBUG_KEYSTORE" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled "$V1" --v2-signing-enabled true \
  --out "$FINAL" "$ALIGNED" 2>/dev/null
"$JAVA" -jar "$APKSIGNER_JAR" verify --print-certs "$FINAL" 2>/dev/null | sed 's/^/    /'
python3 "$HERE/zipalign.py" -c -p 4 --ignore-regex '^META-INF/' "$FINAL" | sed 's/^/    /'

msg "result"
"$AAPT2" dump badging "$FINAL" 2>/dev/null | sed -n '1,6p' | sed 's/^/    /'
info "APK: $FINAL ($(du -h "$FINAL" | cut -f1))"

msg "verify: is the artifact actually intact?"
# The vendored AndroidX closure names libraries this sandbox cannot fetch
# (kotlinx.coroutines, from lifecycle's optional dispatcher). Those dangles are a
# property of the harvest, not of the app, so the toolchain excuses them from a
# file that travels with it - loudly, and without the project having to restate
# the limitation. A *project* entry stays fatal when stale; the baseline does not.
BASELINE_ARGS=()
[ -s "$ANDROIDX_DIR/known-dangling.txt" ] \
  && BASELINE_ARGS=(--baseline "$ANDROIDX_DIR/known-dangling.txt")
python3 "$HERE/verify_apk.py" "$FINAL" --manifest "$MANIFEST" \
  --allowlist "$PROJ/packaging-allowlist.txt" \
  "${BASELINE_ARGS[@]+"${BASELINE_ARGS[@]}"}" \
  --kind "$( [ "$RELEASE" = 1 ] && echo release || echo debug )" \
  || die "the APK is not installable (see the failures above)"

if [ "$VERIFY" = "1" ]; then
  msg "extra verification"
  if [ -s "$APKTOOL_JAR" ]; then
    rm -rf "$BUILD/apktool-out"
    "$JAVA" -jar "$APKTOOL_JAR" d -f -o "$BUILD/apktool-out" "$FINAL" >/dev/null 2>&1 \
      && ok "apktool decoded the APK back to smali + resources (independent sanity check)" \
      || info "apktool decode failed (not fatal)"
  fi
  UNSIGNED_ENTRIES=$(python3 "$HERE/zipalign.py" -c -v "$FINAL" | grep -c off= || true)
  ok "$UNSIGNED_ENTRIES entries, all aligned, v2 signature present"
fi

printf '\n\033[1;32mBUILD OK\033[0m  (%ss)  %s\n' "$(( $(date +%s) - START ))" "$FINAL"
