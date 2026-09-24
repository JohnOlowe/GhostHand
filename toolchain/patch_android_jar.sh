#!/usr/bin/env bash
# patch_android_jar.sh -- make a stock android.jar usable by the Eclipse compiler.
#
#   bash toolchain/patch_android_jar.sh [ANDROID_JAR] [JRE_HOME]
#
# WHY THIS EXISTS
# ---------------
# Every published android.jar (the SDK's stub jar, Sable/android-platforms,
# JordanSamhi/Android-platforms - all checked) contains java/lang/invoke/
# MethodHandles, CallSite, MethodType ... but NOT LambdaMetafactory.
#
# javac never notices: it treats LambdaMetafactory as an implicitly-declared
# bootstrap method and only needs it at *runtime*. ECJ is stricter and aborts with
#
#   "The type java.lang.invoke.LambdaMetafactory cannot be resolved.
#    It is indirectly referenced from required .class files"
#
# the moment a compilation unit contains a lambda. Since this toolchain has no
# javac (the jdk4py package is a JRE), the fix is to put the one missing class
# into the jar we compile against.
#
# IS THAT SAFE?  Yes:
#   * android.jar is a *compile-time* bootclasspath only. Nothing from it is
#     packaged into the APK - D8 dexes only our own .class files.
#   * java.lang.invoke.LambdaMetafactory genuinely exists on every Android device
#     from API 26 up, and D8 backports lambda bootstrap below that. So the class
#     we borrow from the JRE for type resolution describes a real Android runtime
#     type; we never ship its JRE implementation.
#
# The class is lifted out of the JRE's own runtime image (jrt:/), which is the only
# place a JRE keeps its platform classes - there is no rt.jar any more.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

JAR="${1:-${ANDROID_JAR:-$HERE/vendor/android.jar}}"
JRE="${2:-${JAVA_HOME:-$HERE/vendor/jre}}"
[ -s "$JAR" ] || { echo "no android.jar at $JAR" >&2; exit 1; }
[ -x "$JRE/bin/java" ] || { echo "no JRE at $JRE" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 1. A tiny Java program (compiled with ECJ, since there is no javac) that walks
#    jrt:/modules/java.base and copies the classes we need out to a directory.
cat > "$WORK/DumpJrt.java" <<'JAVA'
import java.io.*;
import java.net.URI;
import java.nio.file.*;

public class DumpJrt {
    public static void main(String[] args) throws Exception {
        FileSystem fs = FileSystems.getFileSystem(URI.create("jrt:/"));
        Path src = fs.getPath("modules/java.base/" + args[1]);
        File out = new File(args[0]);
        out.mkdirs();
        int n = 0;
        for (Path p : (Iterable<Path>) Files.walk(src)::iterator) {
            if (!p.toString().endsWith(".class")) continue;
            Path rel = src.relativize(p);
            File dst = new File(out, args[1] + "/" + rel.toString());
            dst.getParentFile().mkdirs();
            try (InputStream in = Files.newInputStream(p);
                 OutputStream os = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
            }
            n++;
        }
        System.out.println("dumped " + n + " class(es) from " + args[1]);
    }
}
JAVA

ECJ="${ECJ_JAR:-$HERE/vendor/ecj.jar}"
[ -s "$ECJ" ] || { echo "no ECJ at $ECJ" >&2; exit 1; }
"$JRE/bin/java" -jar "$ECJ" -source 8 -target 8 -proc:none -nowarn \
    -d "$WORK/classes" "$WORK/DumpJrt.java" 2>/dev/null
"$JRE/bin/java" -cp "$WORK/classes" DumpJrt "$WORK/out" "java/lang/invoke" >/dev/null

# 2. Rebuild the jar: original entries first (minus the ones we are about to
#    replace), then the JRE-sourced java/lang/invoke classes that were missing.
python3 - "$JAR" "$WORK/out" <<'PY'
import os, sys, zipfile

jar, outdir = sys.argv[1], sys.argv[2]

# Only these are needed for ECJ to resolve lambdas and serialisable lambdas; the
# other ~280 java.lang.invoke classes are JRE internals we must not pretend
# Android has.
WANTED = {
    "java/lang/invoke/LambdaMetafactory.class",
    "java/lang/invoke/SerializedLambda.class",
    "java/lang/invoke/LambdaConversionException.class",
    "java/lang/invoke/MethodHandleInfo.class",
}

have = {}
for root, _dirs, files in os.walk(outdir):
    for name in files:
        path = os.path.join(root, name)
        arc = os.path.relpath(path, outdir).replace(os.sep, "/")
        if arc in WANTED:
            have[arc] = path

missing = WANTED - set(have)
if missing:
    print("warning: could not source %s from the JRE" % sorted(missing))

with zipfile.ZipFile(jar) as zin:
    existing = [i for i in zin.infolist() if i.filename not in have]
    tmp = jar + ".tmp"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in existing:
            zout.writestr(item, zin.read(item.filename))
        for arc, path in sorted(have.items()):
            zout.write(path, arc)
os.replace(tmp, jar)
print("patched %s: +%d java/lang/invoke class(es)" % (jar, len(have)))
PY

# 3. Sanity check: the jar must still be readable and must now contain the class.
python3 - "$JAR" <<'PY'
import sys, zipfile
names = set(zipfile.ZipFile(sys.argv[1]).namelist())
need = "java/lang/invoke/LambdaMetafactory.class"
if need not in names:
    print("FAILED: %s still missing from %s" % (need, sys.argv[1]))
    sys.exit(1)
print("verified: %s present (%d entries total)" % (need, len(names)))
PY
