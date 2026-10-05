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

msg()  { printf '\033[1;36m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
ok()   { printf '    \033[32mok\033[0m %s\n' "$*"; }
die()  { printf '\033[31m    error:\033[0m %s\n' "$*" >&2; exit 1; }

load_env() {
  # env.sh (generated) re-exports the vendor paths unconditionally, so a caller's
  # `ANDROID_JAR=/path/to/other.jar bash toolchain/check.sh ...` is discarded
  # without a word: the level does not change and nothing says why. Compare and
  # report; the supported route to another platform is `setup.sh --api N`.
  local pre_android_jar="${ANDROID_JAR:-}"
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
    export JUNIT_JAR="$GH_TOOLCHAIN/junit.jar"
    export HAMCREST_JAR="$GH_TOOLCHAIN/hamcrest.jar"
    export LAMBDA_STUBS_JAR="$GH_TOOLCHAIN/core-lambda-stubs.jar"
  fi
  [ -x "$JAVA_HOME/bin/java" ] || die "no JRE at $JAVA_HOME -- run: bash toolchain/setup.sh"
  [ -s "$ECJ_JAR" ]           || die "no ECJ at $ECJ_JAR -- run: bash toolchain/setup.sh"
  [ -x "$AAPT2" ]             || die "no aapt2 at $AAPT2 -- run: bash toolchain/setup.sh"
  if [ -n "$pre_android_jar" ] && [ "$pre_android_jar" != "$ANDROID_JAR" ]; then
    info "note: ANDROID_JAR=$pre_android_jar was overridden by env.sh -> $ANDROID_JAR"
    info "      (for another platform level run: bash toolchain/setup.sh --api N)"
  fi
  JAVA=("$JAVA_HOME/bin/java")
  androidx_setup
}


# --- AndroidManifest package, when the project is a real AGP app ------------
# Since AGP 7 the manifest needs no package="..." attribute: the module declares
# it in build.gradle(.kts) as `namespace = "..."`. aapt2 does not know that and
# refuses to link ("<manifest> must have a 'package' attribute"), so a project
# built by Gradle is not buildable here until the namespace is put back - and
# hand-patching the real manifest breaks the Gradle build the check exists to
# mirror. Resolve the namespace (explicit --package wins, then the manifest,
# then build.gradle[.kts]) and, when it is missing from the manifest, link a
# patched COPY under the build directory. The project's own files are untouched.

# resolve_package PROJECT_DIR [EXPLICIT] -> sets MANIFEST_PACKAGE / MANIFEST_GRADLE
resolve_package() {
  local proj="$1" explicit="${2:-}" f m
  MANIFEST_PACKAGE=""
  MANIFEST_GRADLE=""
  if [ -n "$explicit" ]; then
    MANIFEST_PACKAGE="$explicit"
    return 0
  fi
  if [ -f "$MANIFEST" ]; then
    m=$(sed -n 's/.*<manifest[^>]*package="\([^"]*\)".*/\1/p' "$MANIFEST" | sed -n '1p')
    if [ -n "$m" ]; then
      MANIFEST_PACKAGE="$m"
      return 0
    fi
  fi
  for f in "$proj/build.gradle.kts" "$proj/build.gradle" \
           "$proj/app/build.gradle.kts" "$proj/app/build.gradle" \
           "$proj/../build.gradle.kts" "$proj/../build.gradle"; do
    [ -f "$f" ] || continue
    m=$(python3 - "$f" <<'GRADLE'
import re, sys
text = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
m = re.search(r"namespace\s*=?\s*[\"']([^\"']+)[\"']", text)
print(m.group(1) if m else "")
GRADLE
)
    if [ -n "$m" ]; then
      MANIFEST_PACKAGE="$m"
      MANIFEST_GRADLE="$f"
      return 0
    fi
  done
  return 0
}

# manifest_for_link BUILD_DIR -- point MANIFEST at a copy carrying package="...")
# when the project's manifest has none. Safe to call repeatedly.
manifest_for_link() {
  local outdir="$1"
  [ -n "${MANIFEST_PACKAGE:-}" ] || return 0
  [ -f "$MANIFEST" ] || return 0
  grep -q '<manifest[^>]*package="' "$MANIFEST" && return 0
  mkdir -p "$outdir"
  if python3 - "$MANIFEST" "$outdir/AndroidManifest.xml" "$MANIFEST_PACKAGE" <<'PATCH'
import re, sys
src, dst, pkg = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(src, encoding="utf-8").read()
if 'package="' in text.split(">", 1)[0]:
    print("    manifest already has a package attribute")
else:
    text = re.sub(r"<manifest\b", '<manifest package="%s"' % pkg, text, count=1)
    open(dst, "w", encoding="utf-8").write(text)
PATCH
  then
    MANIFEST="$outdir/AndroidManifest.xml"
    info "manifest has no package= (AGP namespace); linking a copy with package=\"$MANIFEST_PACKAGE\""
  fi
  return 0
}

# --- AndroidX (optional) ----------------------------------------------------
# toolchain/androidx.sh installs vendor/androidx/{androidx.jar,res/*.zip,
# packages.txt}. When present, every AndroidX reference resolves without any
# flag: the jar goes on ECJ's classpath and into the dexer, the compiled
# resources go to aapt2 link with -R, and --extra-packages makes aapt2 emit
# each library's R class (the same trick AGP uses). ANDROIDX_OFF=1 skips all of
# it, which is what --no-androidx sets.
androidx_setup() {
  # remember whether the caller named a directory, so a missing one is an error
  # instead of "AndroidX simply not installed here"
  ANDROIDX_DIR_EXPLICIT="${ANDROIDX_DIR:-}"
  ANDROIDX_DIR="${ANDROIDX_DIR:-$GH_TOOLCHAIN/androidx}"
  ANDROIDX_CLASSES=""
  ANDROIDX_LIBS=()
  ANDROIDX_ARGS=()
  ANDROIDX_STATE="off (--no-androidx)"
  [ "${ANDROIDX_OFF:-0}" = "1" ] && return 0
  # The contract is a *directory* holding androidx.jar + packages.txt (+ res/*.zip
  # when the harvest has resources) - the layout androidx.sh writes. Pieces missing
  # do not fail here: the jar silently drops off the classpath and the first
  # symptom is aapt2 complaining, many seconds later, that
  # `style/Theme.Material3.DayNight.NoActionBar` does not exist - which blames the
  # app instead of the incomplete vendor directory. So check the contract now.
  if [ -n "${ANDROIDX_DIR_EXPLICIT:-}" ] && [ ! -d "$ANDROIDX_DIR" ]; then
    die "ANDROIDX_DIR=$ANDROIDX_DIR does not exist
  the contract is a directory holding androidx.jar + packages.txt (+ res/*.zip).
  Build it with: bash toolchain/androidx.sh
  (or --no-androidx for a platform-only build)"
  fi
  if [ -d "$ANDROIDX_DIR" ]; then
    if [ ! -s "$ANDROIDX_DIR/androidx.jar" ]; then
      die "ANDROIDX_DIR=$ANDROIDX_DIR has no androidx.jar
  an incomplete AndroidX directory fails late and confusingly (the jar is not on
  the classpath, so every androidx.* reference is a compile error), so it is
  rejected here. Rebuild it: bash toolchain/androidx.sh"
    fi
    if [ ! -f "$ANDROIDX_DIR/packages.txt" ]; then
      die "ANDROIDX_DIR=$ANDROIDX_DIR has no packages.txt
  aapt2 gets each vendored package through --extra-packages; without it library
  resources are unresolvable: 'resource style/Theme.Material3.DayNight.NoActionBar
  not found'. Rebuild it: bash toolchain/androidx.sh"
    fi
  fi
  ANDROIDX_CLASSES="$ANDROIDX_DIR/androidx.jar"
  # Kotlin's runtime is not optional next to AndroidX: activity/fragment/
  # lifecycle are compiled from Kotlin, so their classes call
  # kotlin.jvm.internal.Intrinsics on ordinary paths (see setup.sh step 8b). It is
  # a program input - the classes must land in the dex. The annotations jar is a
  # *library* input: CLASS-retention metadata, never loaded on a device.
  if [ -n "$ANDROIDX_CLASSES" ] && [ -s "$GH_TOOLCHAIN/kotlin-stdlib.jar" ]; then
    ANDROIDX_CLASSES="$ANDROIDX_CLASSES:$GH_TOOLCHAIN/kotlin-stdlib.jar"
    [ -s "$GH_TOOLCHAIN/kotlin-annotations.jar" ] \
      && ANDROIDX_LIBS+=(--lib "$GH_TOOLCHAIN/kotlin-annotations.jar")
  fi
  if [ -d "$ANDROIDX_DIR/res" ]; then
    for z in "$ANDROIDX_DIR"/res/*.zip; do
      [ -s "$z" ] && ANDROIDX_ARGS+=(-R "$z")
    done
  fi
  if [ -f "$ANDROIDX_DIR/packages.txt" ]; then
    while IFS= read -r pkg; do
      [ -n "$pkg" ] && ANDROIDX_ARGS+=(--extra-packages "$pkg")
    done < "$ANDROIDX_DIR/packages.txt"
  fi
  # Library res/ dirs, so xmlcheck can resolve @style/Theme.Material3... and
  # friends before aapt2 is asked to.
  ANDROIDX_XML_ARGS=()
  if [ -n "$ANDROIDX_CLASSES" ] && [ -d "$ANDROIDX_DIR/aar" ]; then
    for d in "$ANDROIDX_DIR"/aar/*/res; do
      [ -d "$d" ] && ANDROIDX_XML_ARGS+=(--extra-res "$d")
    done
  fi
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
  # The floor is the manifest's own minSdkVersion unless --min-api overrides it:
  # it decides aapt2's versioning rules, D8 --min-api and whether apksigner must
  # write a v1 signature. A flag default that disagrees with the artifact is how
  # an APK ends up claiming a floor it cannot run on (apksigner rejected one).
  if [ -z "${MIN_API:-}" ]; then
    MIN_API="$(python3 - "$MANIFEST" <<'MSDK'
import re, sys
text = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
m = re.search(r'minSdkVersion\s*=\s*"(\d+)"', text)
print(m.group(1) if m else "24")
MSDK
)"
  fi
  androidx_apply
}

# AndroidX is linked in only when the project mentions it somewhere (source,
# resources or manifest): an `androidx.` symbol, a Material Components theme,
# AppCompat, `com.google.android.material`. Framework themes such as
# `@android:style/Theme.Material.Light` do not count. GH_ANDROIDX=on forces it
# in, GH_ANDROIDX=off forces it out.
androidx_apply() {
  local mode="${GH_ANDROIDX:-auto}"
  case "$mode" in
    off) ANDROIDX_CLASSES=""; ANDROIDX_ARGS=(); ANDROIDX_XML_ARGS=(); ANDROIDX_LIBS=(); return 0 ;;
    on)  return 0 ;;
  esac
  [ -n "$ANDROIDX_CLASSES" ] || return 0
  local paths=("$SRC_DIR" "$MANIFEST")
  [ -d "$RES_DIR" ] && paths+=("$RES_DIR")
  [ -d "${TEST_DIR:-}" ] && paths+=("$TEST_DIR")
  # Deliberately narrow: `@android:style/Theme.Material.Light` is the *framework*
  # theme and must not drag AndroidX in, while any TextAppearance.Material3 or
  # Theme.AppCompat reference must.
  if ! grep -rqEl 'androidx\.[a-z]|com\.google\.android\.material|Theme\.AppCompat|Theme\.Material3|MaterialComponents' "${paths[@]}" 2>/dev/null; then
    ANDROIDX_CLASSES=""; ANDROIDX_ARGS=(); ANDROIDX_XML_ARGS=(); ANDROIDX_LIBS=()
    ANDROIDX_STATE="skipped (project does not mention AndroidX; GH_ANDROIDX=on forces it)"
  else
    ANDROIDX_STATE="$ANDROIDX_DIR"
  fi
  return 0
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
  [ "${#ANDROIDX_ARGS[@]}" -gt 0 ] && args+=("${ANDROIDX_ARGS[@]}")
  [ -d "$ASSETS_DIR" ] && args+=(-A "$ASSETS_DIR")
  # Below API 21 aapt2 "versions" every <vector> unless told not to: it moves
  # viewportWidth/fillColor/pathData into res/drawable-v21/ and leaves the base
  # file as an empty <vector>. A pre-21 device inflates the base, so AppCompat's
  # VdcInflateDelegate fails and the platform fallback has never heard of
  # <vector>: Resources$NotFoundException at launch. Measured with our own
  # aapt2: link --min-sdk-version 19 emits drawable-v21/ic_star.xml carrying the
  # geometry and leaves res/drawable/ic_star.xml empty; with this flag the base
  # file keeps all 531 bytes. aapt2's own help: "Use this only when building with
  # vector drawable support library" - which is this build.
  args+=(--no-version-vectors)
  [ -n "${MIN_API:-}" ] && args+=(--min-sdk-version "$MIN_API")
  [ -n "${TARGET_API:-}" ] && args+=(--target-sdk-version "$TARGET_API")
  "$AAPT2" link "${args[@]}" "$@" || die "aapt2 link failed (this is the XML/resource compile step)"
}

# ecj_compile OUT_CLASSES GEN_DIR [extra args...]   -- javac, 100% Java, no JDK
ecj_compile() {
  local out="$1" gen="$2"; shift 2
  # Start from an empty directory. ECJ only writes the classes its sources need,
  # so a class whose source was deleted or renamed stays on disk and gets dexed
  # into the APK: bytecode nothing in src/ explains, referencing types that may
  # no longer exist. Verified the hard way - a source file deleted between two
  # builds still shipped its string literal in the dex. javac and AGP both clean
  # their output directory; this now does too.
  rm -rf "$out"
  mkdir -p "$out"
  local files
  files=$(find "$SRC_DIR" "$gen" -name '*.java' 2>/dev/null)
  [ -n "$files" ] || die "no .java files found under $SRC_DIR"
  local common=(-encoding UTF-8 -proc:none -parameters -d "$out")
  if [ "$JAVA_SRC_LEVEL" -le 8 ]; then
    # <=8: -bootclasspath makes java.* resolve against android.jar, exactly like
    # javac with a real Android SDK. Above 8 ECJ refuses -bootclasspath.
    local boot="$ANDROID_JAR"
    # android.jar has no LambdaMetafactory, so -source 8 + any lambda needs the
    # stub jar setup.sh builds (AGP: core-lambda-stubs.jar from build-tools).
    [ -s "${LAMBDA_STUBS_JAR:-}" ] && boot="$boot:$LAMBDA_STUBS_JAR"
    "$JAVA" -jar "$ECJ_JAR" -source "$JAVA_SRC_LEVEL" -target "$JAVA_SRC_LEVEL" \
      -bootclasspath "$boot" \
      -classpath "$ANDROID_JAR${ANDROIDX_CLASSES:+:$ANDROIDX_CLASSES}" \
      "${common[@]}" "$@" $files
  else
    # Above source level 8 the module system makes -bootclasspath illegal, so
    # android.* comes from the filtered jar while java.* comes from the JRE.
    # Language features are checked strictly; the Java API surface is not
    # restricted to Android's (see toolchain/README.md).
    local cp="$ANDROID_CLASSPATH_JAR"
    [ -s "$cp" ] || cp="$ANDROID_JAR"
    [ -n "$ANDROIDX_CLASSES" ] && cp="$cp:$ANDROIDX_CLASSES"
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
  # AndroidX classes are program input, not a library: they must land in the dex.
  # ANDROIDX_CLASSES is a colon-joined classpath (what ECJ wants); the dexers
  # take one path per argument. Passing the joined string to D8/R8 fails with
  # NoSuchFileException: /a.jar:/b.jar - checked, so it is split here.
  local ax=()
  if [ -n "$ANDROIDX_CLASSES" ] && [ "${DEX_SKIP_ANDROIDX:-0}" != "1" ]; then
    IFS=':' read -r -a ax <<< "$ANDROIDX_CLASSES"
  fi
  if [ "${RELEASE:-0}" = "1" ]; then
    local pgconf="$PROJ/proguard.pro"
    local extra=()
    [ -f "$pgconf" ] && extra=(--pg-conf "$pgconf")
    # R8 never reads AndroidManifest.xml: an activity the framework instantiates
    # by name looks unused and gets deleted, producing a signed APK whose
    # launcher class does not exist (verified: remove the hand-written keeps and
    # the release dex loses MainActivity while the build still says BUILD OK).
    # AGP generates these rules from the merged manifest; so do we.
    local keeper
    keeper="$(mktemp "${TMPDIR:-/tmp}/ghosthand-manifest-keep.XXXXXX")"
    if python3 "$TC_DIR/manifest_keep.py" "${MANIFEST:?}" --out "$keeper" 2>&1 | sed 's/^/    /'; then
      extra+=(--pg-conf "$keeper")
    else
      rm -f "$keeper"; die "could not generate manifest keep rules"
    fi
    "$JAVA" -cp "$D8_JAR" com.android.tools.r8.R8 --release --dex \
      --min-api "$min_api" --lib "$ANDROID_JAR" "${ANDROIDX_LIBS[@]+"${ANDROIDX_LIBS[@]}"}" \
      "${extra[@]}" "$@" --output "$out" $classes "${ax[@]}" \
      || { rm -f "$keeper"; die "R8 failed"; }
    rm -f "$keeper"
  else
    "$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
      --min-api "$min_api" --lib "$ANDROID_JAR" "${ANDROIDX_LIBS[@]+"${ANDROIDX_LIBS[@]}"}" \
      "$@" --output "$out" $classes "${ax[@]}" \
      || die "D8 failed (Java bytecode the dexer rejects?)"
  fi
}
