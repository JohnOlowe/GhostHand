#!/usr/bin/env bash
# build.sh -- one command, no arguments, from a fresh clone to a signed APK.
#
#   bash build.sh              setup -> unit tests -> release APK (R8, ~2.2 MB)
#   bash build.sh --debug      same but unshrunk (D8, ~5.4 MB) - the safe fallback
#   bash build.sh --publish    ... and push it to the history-less `apk` branch
#   bash build.sh --quick      skip the unit tests
#   bash build.sh --both       build BOTH configurations into separate files
#
# --release (default) -> app/build/app.apk            R8-shrunk, ~2.2 MB - what to install
# --debug             -> app/build/app-debug.apk      unshrunk + debuggable, ~5.4 MB
# --both              -> app/build/app.apk + app/build/app-debug.apk
#
# app/build/app.apk is ALWAYS the release build - the debug one never overwrites it - so
# the two files cannot be confused by a later publish.
#
# --publish implies --both, because the `apk` branch carries both APKs: the shrunk one
# for a normal download and the unobfuscated one for when something about the shrunk
# build is suspect. Both are signed with the same key, so either installs over the
# other (publish-apk.sh refuses to publish a pair that would not).
#
# The APK is verified in both configurations (verify_apk.py): R8 can delete code the
# framework reaches by name, and the release APK is the only artifact a phone ever
# sees, so the artifact itself is checked rather than trusted.
#
# No Gradle and no Android SDK: see toolchain/README.md. AndroidX (appcompat,
# Material 3, RecyclerView, ConstraintLayout) is fetched from a committed Gradle
# cache by toolchain/androidx.sh and linked automatically - see RECIPE.md §9.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

API=34
MIN_API=26
SOURCE=8            # 8 = compile against the real Android API surface (see RECIPE.md)
VERSION_CODE=1
VERSION_NAME=0.2.0-androidx
MODE="release"      # release | debug | both
PUBLISH=0
RUN_TESTS=1

while [ $# -gt 0 ]; do
  case "$1" in
    --debug) MODE="debug"; shift ;;
    --release) MODE="release"; shift ;;
    --both) MODE="both"; shift ;;
    --publish) PUBLISH=1; shift ;;
    --quick|--no-tests) RUN_TESTS=0; shift ;;
    --api) API="$2"; shift 2 ;;
    --min-api) MIN_API="$2"; shift 2 ;;
    --version-name) VERSION_NAME="$2"; shift 2 ;;
    --version-code) VERSION_CODE="$2"; shift 2 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

# Publishing hands out both APKs, so publishing builds both. (Unless the caller asked
# for --debug explicitly, which cannot be published alone - say so instead of guessing.)
if [ "$PUBLISH" = "1" ] && [ "$MODE" != "both" ]; then
  if [ "$MODE" = "debug" ]; then
    echo "--publish builds both configurations; ignoring --debug" >&2
  fi
  MODE="both"
fi

bold() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }

bold "1/5 toolchain"
if [ -x toolchain/vendor/jre/bin/java ] && [ -s toolchain/vendor/ecj.jar ]; then
  echo "already installed in toolchain/vendor (delete it to force a re-download)"
else
  bash toolchain/setup.sh --api "$API"
fi

bold "2/5 AndroidX"
if [ -s toolchain/vendor/androidx/androidx.jar ]; then
  echo "already assembled in toolchain/vendor/androidx"
else
  # One-off: ~35 s, ~33 MB. Fetches appcompat/material/recyclerview/... AARs from a
  # Gradle cache committed to GitHub (Maven is unreachable here) and rewrites them
  # into a classpath jar + compiled resources + a per-library R-class list.
  bash toolchain/androidx.sh
fi

if [ "$RUN_TESTS" = "1" ]; then
  bold "3/5 unit tests"
  bash toolchain/test.sh app --source "$SOURCE"
else
  bold "3/5 unit tests (skipped)"
fi

# One configuration, one file. The debug and release APKs are deliberately kept in
# separate files rather than overwriting each other, because --publish hands out both.
build_apk() {   # $1 = release|debug, $2 = output file
  local cfg="$1" out="$2" extra=()
  [ "$cfg" = "release" ] && extra+=(--release)
  echo "  -> $cfg: $out"
  VERSION_CODE="$VERSION_CODE" VERSION_NAME="$VERSION_NAME" \
    bash toolchain/build.sh app --api "$API" --min-api "$MIN_API" --source "$SOURCE" \
         --out "$out" --verify "${extra[@]+"${extra[@]}"}"
}

APK="app/build/app.apk"
DEBUG_APK="app/build/app-debug.apk"
VERIFY=()

bold "4/5 APK"
case "$MODE" in
  debug)   build_apk debug   "$DEBUG_APK"; VERIFY=("$DEBUG_APK") ;;
  release) build_apk release "$APK";       VERIFY=("$APK") ;;
  both)    build_apk release "$APK"
           build_apk debug   "$DEBUG_APK"; VERIFY=("$APK" "$DEBUG_APK") ;;
esac

bold "5/5 verify the artifact"
for f in "${VERIFY[@]}"; do
  if ! python3 verify_apk.py "$f"; then
    echo "the APK above is missing things the app needs - not installing or publishing it" >&2
    exit 1
  fi
done

bold "done"
for f in "${VERIFY[@]}"; do
  printf '  %-28s %s\n' "$f" "$(du -h "$f" | cut -f1)"
done
echo
echo "  install:  adb install -r ${VERIFY[0]}"
echo "  ship it:  bash publish-apk.sh   (replaces the single artifact on the 'apk' branch)"

if [ "$PUBLISH" = "1" ]; then
  bold "publishing"
  bash publish-apk.sh --apk "$APK" --debug-apk "$DEBUG_APK"
fi
