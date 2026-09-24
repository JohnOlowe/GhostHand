#!/usr/bin/env bash
# build.sh -- one command, no arguments, from a fresh clone to a signed APK.
#
#   bash build.sh              setup -> unit tests -> release APK (R8, ~2.2 MB)
#   bash build.sh --debug      same but unshrunk (D8, ~5.4 MB) - the safe fallback
#   bash build.sh --publish    ... and push it to the history-less `apk` branch
#   bash build.sh --quick      skip the unit tests
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
RELEASE=1
PUBLISH=0
RUN_TESTS=1

while [ $# -gt 0 ]; do
  case "$1" in
    --debug) RELEASE=0; shift ;;
    --release) RELEASE=1; shift ;;
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

bold "4/5 APK"
EXTRA=()
[ "$RELEASE" = "1" ] && EXTRA+=(--release)
VERSION_CODE="$VERSION_CODE" VERSION_NAME="$VERSION_NAME" \
  bash toolchain/build.sh app --api "$API" --min-api "$MIN_API" --source "$SOURCE" \
       --verify "${EXTRA[@]+"${EXTRA[@]}"}"

APK="app/build/app.apk"

bold "5/5 verify the artifact"
python3 verify_apk.py "$APK"

bold "done"
echo "  $APK  ($(du -h "$APK" | cut -f1))"
echo
echo "  install:  adb install -r $APK"
echo "  ship it:  bash publish-apk.sh   (replaces the single artifact on the 'apk' branch)"

if [ "$PUBLISH" = "1" ]; then
  bold "publishing"
  bash publish-apk.sh
fi
