#!/usr/bin/env python3
"""androidx_assemble.py -- turn exploded AARs into what the toolchain consumes.

Produces, under --out:

    androidx.jar      every library's classes.jar merged with the plain jars
                      (annotations, collection, lifecycle-common, ...) into one
                      fat jar: it goes on ECJ's -classpath, into D8/R8 as a
                      program input, and is what makes the APK self-contained.
    res/<name>.zip    per-library `aapt2 compile` output: aapt2 link takes each
                      with -R so the APK gets the merged resource table.
    packages.txt      the library package names (`androidx.appcompat`,
                      `com.google.android.material`, ...). aapt2 link
                      --extra-packages X emits X's R.java, which is how AGP
                      gives each library its R class; we do the same, so
                      library code compiles unmodified.

Usage:
    androidx_assemble.py --stage DIR --jars DIR --out DIR --aapt2 PATH [--quiet]
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import zipfile

SKIP_ENTRY = re.compile(r"^(META-INF/|module-info\.class$|.*\.kotlin_module$|kotlin/)")
PKG_RE = re.compile(r'package="([^"]+)"')
IGNORE_JAR = ("-sources", "-javadoc", " (1)")


def r_class_re(packages):
    """Matcher for the R classes of `packages` (`pkg/R.class`, `pkg/R$string.class`)."""
    if not packages:
        return None
    return re.compile(r"^(%s)/R(\$.*)?\.class$"
                      % "|".join(re.escape(p.replace(".", "/")) for p in packages))


def merge_jar(jars, out_path, quiet, strip=None):
    """Write one jar with the contents of all inputs; first definition wins.

    Per-input counts are printed and an input that contributes nothing is an
    error: a merge that silently drops one of ten inputs still exits 0 and still
    produces a jar - the failure only shows up later as a NoClassDefFoundError.
    """
    names = {}
    collisions = 0
    empty = []
    counts = []
    stripped = 0
    for jar in jars:
        contributed = 0
        dropped = 0
        pat = (strip or {}).get(os.path.abspath(jar))
        with zipfile.ZipFile(jar) as z:
            for item in z.infolist():
                if item.is_dir() or SKIP_ENTRY.match(item.filename):
                    continue
                if pat and pat.match(item.filename):
                    # A from-source library is compiled against an R class of its
                    # own (AGP generates a stub for the same reason), but that
                    # class must not ship: aapt2 link --extra-packages generates
                    # the real one for the app, and shipping both is a
                    # duplicate-class failure in D8. Same thing AGP does.
                    dropped += 1
                    continue
                if item.filename in names:
                    collisions += 1
                    continue
                names[item.filename] = (jar, item)
                contributed += 1
        stripped += dropped
        if dropped and not quiet:
            print("        %-58s %6d R class(es) dropped (generated at link time)"
                  % (os.path.basename(jar), dropped))
        counts.append((jar, contributed))
        if contributed == 0 and dropped == 0:
            empty.append(jar)
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as out:
        for filename, (jar, item) in names.items():
            with zipfile.ZipFile(jar) as z:
                out.writestr(filename, z.read(item.filename))
    if not quiet:
        for jar, contributed in counts:
            print("        %-58s %6d entries" % (os.path.basename(jar), contributed))
    print("        %d entries merged from %d jars (%d duplicate names skipped)"
          % (len(names), len(jars), collisions))
    # A jar that contributes *only* duplicates can be legitimate (the same
    # library present twice); a jar that contributes nothing at all cannot.
    for jar in empty:
        print("        \033[31merror\033[0m %s contributed no entries - wrong path, "
              "or an empty/wrong artifact" % jar)
    return len(names), empty


def compile_res(aapt2, res_dir, out_zip):
    proc = subprocess.run([aapt2, "compile", "--dir", res_dir, "-o", out_zip],
                          capture_output=True, text=True)
    if proc.returncode != 0:
        print(proc.stdout + proc.stderr)
        return False
    return True


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--stage", required=True, help="dir of exploded AARs")
    ap.add_argument("--jars", default="", help="dir of plain jars from the dump")
    ap.add_argument("--out", required=True)
    ap.add_argument("--aapt2", required=True)
    ap.add_argument("--quiet", action="store_true")
    ap.add_argument("--extra-jar", action="append", default=[], metavar="JAR",
                    help="also merge this jar (repeatable): a dependency compiled "
                         "from upstream source, which Maven cannot provide here")
    ap.add_argument("--extra-package", action="append", default=[], metavar="PKG",
                    help="also emit an R class for this package (repeatable): any "
                         "from-source dependency that declares resources needs its "
                         "package in aapt2's --extra-packages list")
    ap.add_argument("--extra-res", action="append", default=[], metavar="DIR",
                    help="also compile this res/ directory and ship it with the "
                         "harvest (repeatable) - needed by a from-source "
                         "dependency that declares resources, whose source tree "
                         "Maven would otherwise have packed into an AAR")
    ap.add_argument("--extra-res-name", default="extra",
                    help="basename for the compiled --extra-res zip "
                         "(default: extra; a second dir needs its own name)")
    args = ap.parse_args(argv)

    os.makedirs(args.out, exist_ok=True)
    res_out = os.path.join(args.out, "res")
    os.makedirs(res_out, exist_ok=True)

    stages = sorted(d for d in os.listdir(args.stage)
                    if os.path.isdir(os.path.join(args.stage, d)))
    jars = [os.path.join(args.stage, d, "classes.jar") for d in stages]
    # AAR libs/*.jar: a library may bundle its own dependencies there (emoji2
    # ships EmojiCompat's generated flatbuffer classes this way). AGP unpacks
    # them onto the compile classpath; skipping them leaves the library's own
    # classes referencing types that exist nowhere - a NoClassDefFoundError on
    # first use. Found by running verify_apk.py over a built APK.
    for d in stages:
        libs_dir = os.path.join(args.stage, d, "libs")
        if os.path.isdir(libs_dir):
            for name in sorted(os.listdir(libs_dir)):
                if name.endswith(".jar"):
                    jars.append(os.path.join(libs_dir, name))
    for extra in args.extra_jar:
        if not os.path.isfile(extra):
            print("        \033[31merror\033[0m --extra-jar %s does not exist" % extra)
            return 1
        jars.append(extra)
    jars = [j for j in jars if os.path.isfile(j)]
    if args.jars:
        for name in sorted(os.listdir(args.jars)):
            if name.endswith(".jar") and not any(x in name for x in IGNORE_JAR):
                jars.append(os.path.join(args.jars, name))
    if not jars:
        print("        no classes.jar found under %s" % args.stage)
        return 1

    strip = {}
    if args.extra_package:
        matcher = r_class_re(set(args.extra_package))
        for extra in args.extra_jar:
            strip[os.path.abspath(extra)] = matcher
    classes, empty_inputs = merge_jar(jars, os.path.join(args.out, "androidx.jar"),
                                      args.quiet, strip)

    compiled = failed = 0
    for d in stages:
        res_dir = os.path.join(args.stage, d, "res")
        if not os.path.isdir(res_dir) or not os.listdir(res_dir):
            continue
        if compile_res(args.aapt2, res_dir, os.path.join(res_out, d + ".zip")):
            compiled += 1
        else:
            failed += 1
            print("        aapt2 compile FAILED for %s" % d)

    for i, res_dir in enumerate(args.extra_res):
        if not os.path.isdir(res_dir):
            print("        \033[31merror\033[0m --extra-res %s is not a directory" % res_dir)
            return 1
        name = args.extra_res_name if i == 0 else "%s-%d" % (args.extra_res_name, i + 1)
        if compile_res(args.aapt2, res_dir, os.path.join(res_out, name + ".zip")):
            compiled += 1
        else:
            failed += 1
            print("        aapt2 compile FAILED for --extra-res %s" % res_dir)

    packages = set(args.extra_package)
    for d in stages:
        manifest = os.path.join(args.stage, d, "AndroidManifest.xml")
        if os.path.isfile(manifest):
            with open(manifest, encoding="utf-8", errors="ignore") as fh:
                m = PKG_RE.search(fh.read())
            if m:
                packages.add(m.group(1))
    with open(os.path.join(args.out, "packages.txt"), "w", encoding="utf-8") as fh:
        for pkg in sorted(packages):
            fh.write(pkg + "\n")

    # Ship the toolchain's known-dangling baseline with the harvest, whichever
    # way the harvest was built: verify_apk.py reads it from ANDROIDX_DIR, and a
    # custom/hand-built directory otherwise loses the note and fails builds for a
    # limitation of the *harvest* rather than of the app.
    baseline_src = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "androidx-known-dangling.txt")
    if os.path.isfile(baseline_src):
        shutil.copyfile(baseline_src, os.path.join(args.out, "known-dangling.txt"))

    size = os.path.getsize(os.path.join(args.out, "androidx.jar"))
    print("        androidx.jar %.1f MB, %d class entries" % (size / 1e6, classes))
    print("        resources: %d libraries compiled, %d failed" % (compiled, failed))
    print("        packages: %d (for aapt2 --extra-packages)" % len(packages))
    return 1 if (failed or empty_inputs) else 0


if __name__ == "__main__":
    sys.exit(main())
