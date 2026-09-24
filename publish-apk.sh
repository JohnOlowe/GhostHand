#!/usr/bin/env bash
# publish-apk.sh -- publish the built APK to a branch that NEVER accumulates history.
#
#   bash publish-apk.sh [--apk FILE] [--branch apk] [--remote origin] [--no-push]
#
# WHY A SEPARATE BRANCH
# ---------------------
# The APK is ~110 KB and changes on every build. Committing it to `main` would add a
# new blob to history on every push, so `git clone` would hand you every APK ever
# built, forever. Binary blobs do not delta-compress like source does, so that grows
# without bound.
#
# Instead the APK lives on its own branch, and every publish REPLACES that branch
# with a single parentless commit:
#
#     apk:  *  (this build)          <- always exactly one commit, always one APK
#     main: *---*---*---*---*        <- source history only, never a binary
#
# A fresh clone of `main` downloads source and nothing else; anyone who wants the
# APK clones/fetches `apk` and pays for exactly one copy of it.
#
# HOW IT WORKS WITHOUT SWITCHING BRANCHES
# ---------------------------------------
# git plumbing writes the commit object directly - no checkout, no working-tree
# changes, nothing to stash:
#   hash-object -w   store the APK as a blob
#   mktree           build a tree from "mode sha path" lines
#   commit-tree      make a commit with NO parent (that is what makes it orphan)
#   push --force     point the remote branch at it, discarding the previous one
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

BRANCH="apk"
REMOTE="origin"
APK="app/build/app.apk"
NAME="GhostHand-debug.apk"
PUSH=1

while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    --branch) BRANCH="$2"; shift 2 ;;
    --remote) REMOTE="$2"; shift 2 ;;
    --name) NAME="$2"; shift 2 ;;
    --no-push) PUSH=0; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

[ -s "$APK" ] || { echo "no APK at $APK -- build it first:" >&2
                   echo "  bash toolchain/build.sh app --api 34 --min-api 26 --source 8" >&2
                   exit 1; }

# ---------------------------------------------------------------- sanity checks
VERSION="$(printf '%s' "$(basename "$APK")")"
if [ -x "toolchain/vendor/jre/bin/java" ] && [ -s "toolchain/vendor/apksigner.jar" ]; then
  if ! toolchain/vendor/jre/bin/java -jar toolchain/vendor/apksigner.jar verify "$APK" >/dev/null 2>&1; then
    echo "refusing to publish: $APK is not a validly signed APK" >&2
    exit 1
  fi
  SIGNER="$(toolchain/vendor/jre/bin/java -jar toolchain/vendor/apksigner.jar verify --print-certs "$APK" 2>/dev/null \
            | sed -n 's/^Signer #1 certificate DN: //p')"
else
  SIGNER="(apksigner not installed - signature not re-checked)"
fi

SHA256="$(sha256sum "$APK" | awk '{print $1}')"
SIZE="$(du -h "$APK" | cut -f1)"
SOURCE_REV="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
SOURCE_DESC="$(git log -1 --pretty=%s 2>/dev/null || echo unknown)"
STAMP="$(date -u '+%Y-%m-%d %H:%M UTC')"

# ------------------------------------------------------------- build the branch
BLOB="$(git hash-object -w "$APK")"

README_FILE="$(mktemp)"
trap 'rm -f "$README_FILE"' EXIT
cat > "$README_FILE" <<EOF
# GhostHand - build artifact (generated, do not edit)

This branch exists only to hand out the APK. It is **replaced on every publish** and
therefore always has exactly **one commit** and exactly **one APK**. That is on
purpose: build output must never accumulate in the source history, or every clone
would drag along every APK ever built.

| | |
|---|---|
| File | \`$NAME\` |
| Size | $SIZE |
| SHA-256 | \`$SHA256\` |
| Built | $STAMP |
| Source | \`$SOURCE_REV\` - $SOURCE_DESC |
| Signer | $SIGNER |

## Install

\`\`\`bash
git fetch origin $BRANCH
git show FETCH_HEAD:$NAME > GhostHand-debug.apk    # one file, one fetch
adb install -r GhostHand-debug.apk
\`\`\`

Every build is signed with the same key (\`keystore/damjay_debug.keystore\`), so a new
APK installs straight over the previous one - no uninstalling.

## Build it yourself

\`\`\`bash
bash toolchain/setup.sh                       # once, ~15 s: JRE + ECJ + aapt2 + D8 + apksigner
bash toolchain/test.sh  app                   # JUnit unit tests
bash toolchain/build.sh app --verify          # signed, aligned APK
\`\`\`
EOF
README_BLOB="$(git hash-object -w "$README_FILE")"

TREE="$(printf '100644 blob %s\t%s\n100644 blob %s\tREADME.md\n' \
        "$BLOB" "$NAME" "$README_BLOB" | git mktree)"

COMMIT_MSG="GhostHand $NAME @ $SOURCE_REV

size:   $SIZE
sha256: $SHA256
signed: $SIGNER
built:  $STAMP

Single-commit branch: each publish replaces the previous one on purpose, so the
repository never accumulates build output in its history."

# No -p (parent) argument: that is what makes this an orphan commit, so the branch
# is exactly one commit deep however many times it is published.
COMMIT="$(git commit-tree "$TREE" -m "$COMMIT_MSG")"

echo "apk    : $APK ($SIZE)"
echo "sha256 : $SHA256"
echo "commit : $COMMIT"

if [ "$PUSH" = "1" ]; then
  echo "pushing -> $REMOTE $BRANCH (force: replaces the previous artifact commit)"
  git push --force "$REMOTE" "$COMMIT:refs/heads/$BRANCH"
  echo
  echo "The branch now holds exactly one commit and one APK:"
  echo "  git ls-tree -r --long $COMMIT"
else
  echo "(not pushed; --no-push was given)"
fi
