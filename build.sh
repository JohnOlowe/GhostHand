#!/usr/bin/env bash
# build.sh -- one command, no arguments, from a fresh clone to a signed APK.
#
#   bash build.sh              set up (if needed) -> unit tests -> signed APK
#   bash build.sh --publish    ... and publish it to the history-less `apk` branch
#   bash build.sh --quick      skip the unit tests
#
# There is no Gradle and no Android SDK involved: see toolchain/README.md.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

API=34
MIN_API=26
SOURCE=8            # 8 = compile against the real Android API surface (see RECIPE.md)
VERSION_CODE=1
VERSION_NAME=0.1.0-mirror
PUBLISH=0
RUN_TESTS=1

while [ $# -gt 0 ]; do
  case "$1" in
    --publish) PUBLISH=1; shift ;;
    --quick|--no-tests) RUN_TESTS=0; shift ;;
    --api) API="$2"; shift 2 ;;
    --min-api) MIN_API="$2"; shift 2 ;;
    --version-name) VERSION_NAME="$2"; shift 2 ;;
    --version-code) VERSION_CODE="$2"; shift 2 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

bold() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }

bold "1/3 toolchain"
if [ -x toolchain/vendor/jre/bin/java ] && [ -s toolchain/vendor/ecj.jar ]; then
  echo "already installed in toolchain/vendor (delete it to force a re-download)"
else
  bash toolchain/setup.sh --api "$API"
fi

if [ "$RUN_TESTS" = "1" ]; then
  bold "2/3 unit tests"
  bash toolchain/test.sh app --source "$SOURCE"
else
  bold "2/3 unit tests (skipped)"
fi

bold "3/3 APK"
VERSION_CODE="$VERSION_CODE" VERSION_NAME="$VERSION_NAME" \
  bash toolchain/build.sh app --api "$API" --min-api "$MIN_API" --source "$SOURCE" --verify

APK="app/build/app.apk"
bold "done"
echo "  $APK  ($(du -h "$APK" | cut -f1))"
echo
echo "  install:  adb install -r $APK"
echo "  ship it:  bash publish-apk.sh   (replaces the single artifact on the 'apk' branch)"

if [ "$PUBLISH" = "1" ]; then
  bold "publishing"
  bash publish-apk.sh
fi
