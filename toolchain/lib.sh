#!/usr/bin/env bash
# lib.sh -- shared helpers for check.sh / build.sh. Sourced, not executed.

set -euo pipefail

TC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ=""
SRC_DIR=""
RES_DIR=""
MANIFEST=""
JAVA_SRC_LEVEL=8
MIN_API=""

# Signing defaults. Every one of these is overridable from the environment, and
# resolve_signing() below prefers a keystore committed in the project.
SIGN_KEYSTORE="${SIGN_KEYSTORE:-}"
SIGN_ALIAS="${SIGN_ALIAS:-androiddebugkey}"
SIGN_STORE_PASS="${SIGN_STORE_PASS:-android}"
SIGN_KEY_PASS="${SIGN_KEY_PASS:-android}"

msg()  { printf '\033[1;36m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
ok()   { printf '    \033[32mok\033[0m %s\n' "$*"; }
die()  { printf '\033[31m    error:\033[0m %s\n' "$*" >&2; exit 1; }

load_env() {
  if [ -f "$TC_DIR/env.sh" ]; then
    # shellcheck disable=SC1091
    . "$TC_DIR/env.sh"
  else
    export GH_TOOLCHAIN="$TC_DIR/vendor"
    export JAVA_HOME="$GH_TOOLCHAIN/jre"
    export ECJ_JAR="$GH_TOOLCHAIN/ecj.jar"
    export D8_JAR="$GH_TOOLCHAIN/d8.jar"
    export APKSIGNER_JAR="$GH_TOOLCHAIN/apksigner.jar"
    export APKTOOL_JAR="$GH_TOOLCHAIN/apktool.jar"
    export ANDROID_JAR="$GH_TOOLCHAIN/android.jar"
    export ANDROID_CLASSPATH_JAR="$GH_TOOLCHAIN/android-classpath.jar"
    export AAPT2="$GH_TOOLCHAIN/aapt2"
    export DEBUG_KEYSTORE="$GH_TOOLCHAIN/debug.keystore"
  fi
  [ -x "$JAVA_HOME/bin/java" ] || die "no JRE at $JAVA_HOME -- run: bash toolchain/setup.sh"
  [ -s "$ECJ_JAR" ]           || die "no ECJ at $ECJ_JAR -- run: bash toolchain/setup.sh"
  [ -x "$AAPT2" ]             || die "no aapt2 at $AAPT2 -- run: bash toolchain/setup.sh"
  JAVA=("$JAVA_HOME/bin/java")
}

# resolve_signing PROJECT_ROOT -- prefer the repo's own keystore over the vendor
# debug key, so every build of this project carries one stable signature and a new
# APK installs cleanly over the previous one. Explicit SIGN_* env vars still win.
#
# Credentials are read from the project's gradle.properties when present (the work
# was originally set up for Gradle, and keeping one source of truth for the key
# means the two build systems can never disagree).
resolve_signing() {
  local proj="$1" root props ks
  root="$(dirname "$proj")"
  for props in "$proj/gradle.properties" "$root/gradle.properties"; do
    [ -s "$props" ] || continue
    local file alias_ store key
    file=$(sed -n 's/^GHOSTHAND_STORE_FILE=//p'     "$props" | head -1)
    store=$(sed -n 's/^GHOSTHAND_STORE_PASSWORD=//p' "$props" | head -1)
    alias_=$(sed -n 's/^GHOSTHAND_KEY_ALIAS=//p'    "$props" | head -1)
    key=$(sed -n 's/^GHOSTHAND_KEY_PASSWORD=//p'    "$props" | head -1)
    [ -n "$store" ] && SIGN_STORE_PASS="$store"
    [ -n "$alias_" ] && SIGN_ALIAS="$alias_"
    # PKCS12 has no separate key password (keytool prints a warning and ignores the
    # distinction), so a missing/truncated key password falls back to the store one.
    [ -n "$key" ] && SIGN_KEY_PASS="$key" || SIGN_KEY_PASS="$SIGN_STORE_PASS"
    [ -n "$file" ] && [ -s "$root/$file" ] && SIGN_KEYSTORE="$root/$file"
    break
  done

  for ks in "$SIGN_KEYSTORE" "$proj/keystore/damjay_debug.keystore" \
            "$root/keystore/damjay_debug.keystore"; do
    if [ -n "$ks" ] && [ -s "$ks" ]; then SIGN_KEYSTORE="$ks"; break; fi
  done
  [ -n "$SIGN_KEYSTORE" ] || SIGN_KEYSTORE="$DEBUG_KEYSTORE"

  # A truncated key password is a real trap from the original Gradle config: PKCS12
  # keys open with the *store* password, so if the store password is 32 chars and
  # only a 31-char one is available, prefer the store password.
  if [ "${#SIGN_KEY_PASS}" -lt "${#SIGN_STORE_PASS}" ]; then
    SIGN_KEY_PASS="$SIGN_STORE_PASS"
  fi
}

# sign_apk ALIGNED_APK OUT_APK -- apksigner with whichever key resolve_signing picked.
sign_apk() {
  local aligned="$1" out="$2" v1=false
  # v1 (JAR) signatures are only needed below API 24, and apksigner writes
  # META-INF/* after alignment, so enabling them late would break zipalign.
  [ "${MIN_API:-24}" -lt 24 ] && v1=true
  "$JAVA" -jar "$APKSIGNER_JAR" sign \
    --ks "$SIGN_KEYSTORE" --ks-key-alias "$SIGN_ALIAS" \
    --ks-pass "pass:$SIGN_STORE_PASS" --key-pass "pass:$SIGN_KEY_PASS" \
    --v1-signing-enabled "$v1" --v2-signing-enabled true \
    --out "$out" "$aligned" 2>/dev/null \
    || die "apksigner failed (keystore $SIGN_KEYSTORE, alias $SIGN_ALIAS)"
}

# Accept both the flat layout (res/, src/, AndroidManifest.xml) and the Gradle
# layout (src/main/...). Sets PROJ SRC_DIR RES_DIR MANIFEST ASSETS_DIR.
detect_layout() {
  local dir="$1"
  PROJ="$(cd "$dir" && pwd)" || die "no such project: $dir"
  if [ -d "$PROJ/src/main" ]; then
    SRC_DIR="$PROJ/src/main/java"
    [ -d "$SRC_DIR" ] || SRC_DIR="$PROJ/src/main"
    RES_DIR="$PROJ/src/main/res"
    MANIFEST="$PROJ/src/main/AndroidManifest.xml"
  else
    SRC_DIR="$PROJ/src"
    RES_DIR="$PROJ/res"
    MANIFEST="$PROJ/AndroidManifest.xml"
  fi
  ASSETS_DIR="$(dirname "$MANIFEST")/assets"
  [ -f "$MANIFEST" ] || die "no AndroidManifest.xml under $dir"
}

# count_java -> number of .java files in the project sources
count_java() { find "$SRC_DIR" -name '*.java' 2>/dev/null | wc -l | tr -d ' '; }

# aapt2_compile_res OUT_ZIP
aapt2_compile_res() {
  local out="$1"
  if [ -d "$RES_DIR" ]; then
    "$AAPT2" compile --dir "$RES_DIR" -o "$out" || die "aapt2 compile failed (see errors above)"
  else
    python3 - "$out" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'w') as z:
    pass
PY
  fi
}

# aapt2_link OUT_APK GEN_DIR [extra args...]
aapt2_link() {
  local out="$1" gen="$2"; shift 2
  mkdir -p "$gen"
  local args=(-o "$out" -I "$ANDROID_JAR" --manifest "$MANIFEST"
              --auto-add-overlay --java "$gen")
  [ -n "${RES_ZIP:-}" ] && [ -s "$RES_ZIP" ] && args+=(-R "$RES_ZIP")
  [ -d "$ASSETS_DIR" ] && args+=(-A "$ASSETS_DIR")
  [ -n "${MIN_API:-}" ] && args+=(--min-sdk-version "$MIN_API")
  [ -n "${TARGET_API:-}" ] && args+=(--target-sdk-version "$TARGET_API")
  "$AAPT2" link "${args[@]}" "$@" || die "aapt2 link failed (this is the XML/resource compile step)"
}

# ecj_compile OUT_CLASSES GEN_DIR [extra args...]   -- javac, 100% Java, no JDK
ecj_compile() {
  local out="$1" gen="$2"; shift 2
  mkdir -p "$out"
  local files
  files=$(find "$SRC_DIR" "$gen" -name '*.java' 2>/dev/null)
  [ -n "$files" ] || die "no .java files found under $SRC_DIR"
  local common=(-encoding UTF-8 -proc:none -parameters -d "$out")
  if [ "$JAVA_SRC_LEVEL" -le 8 ]; then
    # <=8: -bootclasspath makes java.* resolve against android.jar, exactly like
    # javac with a real Android SDK. Above 8 ECJ refuses -bootclasspath.
    "$JAVA" -jar "$ECJ_JAR" -source "$JAVA_SRC_LEVEL" -target "$JAVA_SRC_LEVEL" \
      -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
      "${common[@]}" "$@" $files
  else
    # Above source level 8 the module system makes -bootclasspath illegal, so
    # android.* comes from the filtered jar while java.* comes from the JRE.
    # Language features are checked strictly; the Java API surface is not
    # restricted to Android's (see toolchain/README.md).
    local cp="$ANDROID_CLASSPATH_JAR"
    [ -s "$cp" ] || cp="$ANDROID_JAR"
    "$JAVA" -jar "$ECJ_JAR" -source "$JAVA_SRC_LEVEL" -target "$JAVA_SRC_LEVEL" \
      -classpath "$cp" "${common[@]}" "$@" $files
  fi
}

# dex OUT_DIR  (D8 keeps it quick; --release uses R8 with shrinking)
dex() {
  local out="$1"; shift
  rm -rf "$out"; mkdir -p "$out"
  local min_api="${MIN_API:-24}"
  local classes; classes=$(find "$CLASSES_DIR" -name '*.class')
  [ -n "$classes" ] || die "nothing to dex: $CLASSES_DIR is empty"
  if [ "${RELEASE:-0}" = "1" ]; then
    local pgconf="$PROJ/proguard.pro"
    local extra=()
    [ -f "$pgconf" ] && extra=(--pg-conf "$pgconf")
    "$JAVA" -cp "$D8_JAR" com.android.tools.r8.R8 --release --dex \
      --min-api "$min_api" --lib "$ANDROID_JAR" "${extra[@]}" "$@" \
      --output "$out" $classes || die "R8 failed"
  else
    "$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
      --min-api "$min_api" --lib "$ANDROID_JAR" "$@" \
      --output "$out" $classes || die "D8 failed (Java bytecode the dexer rejects?)"
  fi
}
