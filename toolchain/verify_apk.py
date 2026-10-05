#!/usr/bin/env python3
"""verify_apk.py -- check a built APK for the failures no compiler can see.

    python3 toolchain/verify_apk.py APK --manifest ANDROIDMANIFEST.XML
                             [--project DIR] [--allowlist FILE] [--kind release|debug]

Everything else in this toolchain checks *inputs* (does it compile? do the
resources link? is the dex valid?). This checks the finished artifact, because
there are defects that compile, link, dex, zipalign and sign perfectly and then
crash on a phone:

1. **Self-containment.** The dex may name types that no artifact in the APK
   defines. R8 only *warns* about a missing class, and a `-dontwarn` line makes
   even that warning go away, so the build stays green and the app dies with
   `ClassNotFoundException`/`NoClassDefFoundError` on the code path that touches
   it. Reference case: AndroidX is partly compiled from Kotlin, so
   `ComponentActivity`'s constructor reaches `kotlin.jvm.internal.Intrinsics`;
   without kotlin-stdlib in the dex every launch crashed. Every type the dex
   names under android*/com.google*/kotlin*/kotlinx*/org.jetbrains* must be
   defined in the APK or declared in packaging-allowlist.txt with a reason
   (and a stale entry fails, so the file cannot rot). Types the *toolchain*
   cannot provide - see --baseline - are excused but counted out loud.

2. **Dex count against the declared minSdkVersion.** Dalvik (API < 21) loads
   exactly one dex file: an APK that claims minSdk 19 and ships classes2.dex
   installs fine and dies with NoClassDefFoundError.

3. **Manifest components.** R8 never reads AndroidManifest.xml, so an activity
   or service the framework instantiates by name looks unused and gets deleted.
   Every component the manifest declares must exist in the dex (this is the
   failure that shipped once: a release APK with no launcher class).

4. **Gutted vector drawables.** With `--min-sdk-version` below 21, aapt2 moves a
   `<vector>`'s API-21 attributes into res/drawable-v21/ and leaves the base file
   empty unless `--no-version-vectors` (which lib.sh passes). A pre-21 device
   inflates the base, so that is a launch crash on exactly the devices the low
   minSdk was for.

Exit code: 0 = intact, 1 = problems (do not install), 2 = usage/IO error.
"""

import argparse
import os
import re
import struct
import subprocess
import sys
import zipfile

BASELINE_NAME = "toolchain/androidx-known-dangling.txt"
DEFAULT_PREFIXES = ("androidx/", "com/google/", "kotlin/", "kotlinx/", "org/jetbrains/")
ALLOW_SCOPES = ("both", "release", "debug")
COMPONENT_TAGS = ("application", "activity", "activity-alias", "service",
                  "receiver", "provider")


# --------------------------------------------------------------------------- dex

def _uleb128(buf, pos):
    result = shift = 0
    while True:
        byte = buf[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, pos
        shift += 7


def dex_types(blob):
    """(defined, referenced) type descriptors in one dex file."""
    string_ids_size, string_ids_off = struct.unpack_from("<II", blob, 56)
    type_ids_size, type_ids_off = struct.unpack_from("<II", blob, 64)
    class_defs_size, class_defs_off = struct.unpack_from("<II", blob, 96)

    strings = []
    for i in range(string_ids_size):
        off = struct.unpack_from("<I", blob, string_ids_off + 4 * i)[0]
        _, pos = _uleb128(blob, off)
        end = blob.index(b"\x00", pos)
        strings.append(blob[pos:end].decode("utf-8", "replace"))

    types = []
    for i in range(type_ids_size):
        idx = struct.unpack_from("<I", blob, type_ids_off + 4 * i)[0]
        types.append(strings[idx] if idx < len(strings) else "")

    referenced = {t for t in types if t.startswith("L") and t.endswith(";")}
    defined = set()
    for i in range(class_defs_size):
        idx = struct.unpack_from("<I", blob, class_defs_off + 32 * i)[0]
        if idx < len(types):
            defined.add(types[idx])
    return defined, referenced


def apk_type_tables(apk):
    with zipfile.ZipFile(apk) as z:
        dexes = sorted(n for n in z.namelist() if n.endswith(".dex"))
        defined, referenced = set(), set()
        for name in dexes:
            d, r = dex_types(z.read(name))
            defined |= {t[1:-1] for t in d}
            referenced |= {t[1:-1] for t in r}
    return dexes, defined, referenced


# --------------------------------------------------------------------- manifest

def _local(tag):
    return tag.rsplit("}", 1)[-1]


def manifest_components(manifest_path):
    """Fully-qualified class names the manifest makes the framework instantiate."""
    import xml.etree.ElementTree as ET
    android = "{http://schemas.android.com/apk/res/android}"
    tree = ET.parse(manifest_path)
    root = tree.getroot()
    package = root.get("package") or ""
    out = []

    def qualify(name):
        if not name:
            return None
        if name.startswith("."):
            return package + name
        if "." not in name:
            return package + "." + name
        return name

    for elem in root.iter():
        if _local(elem.tag) not in COMPONENT_TAGS:
            continue
        for attr in ("name", "targetActivity"):
            fqcn = qualify(elem.get(android + attr))
            if fqcn:
                out.append(fqcn)
    return sorted(set(out))


# -------------------------------------------------------------------- allowlist

def read_allowlist(path):
    """{pattern: (scope, reason)} -- `pattern [both|release|debug] reason`."""
    entries = {}
    if not path or not os.path.isfile(path):
        return entries
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split(None, 2)
            pattern = parts[0]
            scope = "both"
            reason = ""
            if len(parts) > 1:
                if parts[1] in ALLOW_SCOPES:
                    scope = parts[1]
                    reason = parts[2] if len(parts) > 2 else ""
                else:
                    reason = line[len(pattern):].strip()
            entries[pattern] = (scope, reason)
    return entries


def allowed(pattern, name):
    if pattern.endswith("/**"):
        return name.startswith(pattern[:-3])
    if pattern.endswith("/*"):
        rest = name[len(pattern) - 2:]
        return name.startswith(pattern[:-2]) and "/" not in rest
    return name == pattern


# ---------------------------------------------------------------------- checks

def check_self_contained(defined, referenced, allowlist, baseline, kind, log):
    allow = {p: v for p, v in allowlist.items()
             if kind is None or v[0] in ("both", kind)}
    base = {p: v for p, v in baseline.items()
            if kind is None or v[0] in ("both", kind)}
    undefined = [t for t in referenced if t.startswith(DEFAULT_PREFIXES)
                 and t not in defined]
    missing = sorted(t for t in undefined
                     if not any(allowed(p, t) for p in allow)
                     and not any(allowed(p, t) for p in base))
    used = {p for p in allow if any(allowed(p, t) for t in undefined)}
    used_base = {p for p in base if any(allowed(p, t) for t in undefined)}
    excused = sorted(t for t in undefined if t not in set(missing)
                     and not any(allowed(p, t) for p in allow))
    problems = 0
    if missing:
        log("    %s %d type(s) the dex names but the APK does not define:"
            % (red("FAIL"), len(missing)))
        for t in missing[:15]:
            log("        %s" % t)
        if len(missing) > 15:
            log("        ... and %d more" % (len(missing) - 15))
        log("        (a missing class is a NoClassDefFoundError on the code path that"
            " touches it: add the artifact, or declare it in packaging-allowlist.txt)")
        problems += 1
    if excused:
        # Not a failure, and deliberately still loud: the classes really are
        # absent, so code that uses one of these families crashes on that path.
        log("    note %d type(s) excused by the toolchain baseline "
            "(%s): code that uses them fails at runtime" % (len(excused), BASELINE_NAME))
        for t in excused[:3]:
            log("         %s" % t)
        if len(excused) > 3:
            log("         ... and %d more" % (len(excused) - 3))
    stale = sorted(p for p in allow if p not in used)
    if stale:
        log("    %s %d allowlist entr(y/ies) no longer suppress anything: %s"
            % (red("FAIL"), len(stale), ", ".join(stale[:5])))
        problems += 1
    stale_base = sorted(p for p in base if p not in used_base)
    if stale_base:
        log("    note %d baseline entr(y/ies) no longer fire (the harvest or the "
            "shrinker changed; the file can shrink): %s"
            % (len(stale_base), ", ".join(stale_base[:5])))
    return problems


def check_dex_count(dexes, min_sdk, log):
    if min_sdk is None:
        log("    minSdkVersion not read - dex count not checked against it")
        return 0
    if min_sdk < 21 and len(dexes) > 1:
        log("    %s minSdkVersion %d but the APK ships %d dex files (%s):"
            % (red("FAIL"), min_sdk, len(dexes), ", ".join(dexes)))
        log("        Android < 5.0 loads only classes.dex, so the rest is missing at"
            " runtime (shrink with R8/debug floor 21+, or add multidex for API 21+)")
        return 1
    log("    ok   minSdkVersion %d, %d dex file(s) - loadable on the declared floor"
        % (min_sdk, len(dexes)))
    return 0


def check_components(manifest_path, defined, log):
    if not manifest_path or not os.path.isfile(manifest_path):
        log("    manifest not given - component check skipped")
        return 0
    missing = [c for c in manifest_components(manifest_path)
               if c.replace(".", "/") not in defined]
    if missing:
        log("    %s %d manifest component(s) not in the dex: %s"
            % (red("FAIL"), len(missing), ", ".join(missing)))
        log("        (R8 does not read AndroidManifest.xml: lib.sh generates keep"
            " rules from it at release-dex time for exactly this reason)")
        return 1
    log("    ok   %d manifest components present in the dex"
        % len(manifest_components(manifest_path)))
    return 0


def check_vectors(apk, log):
    with zipfile.ZipFile(apk) as z:
        names = set(z.namelist())
        base_re = re.compile(r"^res/drawable/([^/]+)\.xml$")
        problems = 0
        checked = 0
        for name in sorted(names):
            m = base_re.match(name)
            if not m:
                continue
            stem = m.group(1)
            variants = [n for n in names
                        if re.match(r"^res/drawable-[^/]+/%s\.xml$" % re.escape(stem), n)]
            if not variants:
                continue
            base = z.read(name).decode("utf-8", "ignore")
            if "<vector" not in base or "viewportWidth" in base:
                continue
            gutted_variant = any("viewportWidth" in z.read(v).decode("utf-8", "ignore")
                                 for v in variants)
            checked += 1
            if gutted_variant:
                log("    %s %s lost its vector attributes to %s (aapt2 versioned it"
                    " without --no-version-vectors)" % (red("FAIL"), name, variants[0]))
                problems += 1
        if checked and not problems:
            log("    ok   %d versioned drawable(s) checked, no gutted vector base"
                % checked)
        elif not checked:
            log("    ok   no versioned vector drawables in the APK")
    return problems


def declared_debuggable(apk, aapt2):
    """True/False/None from the APK's own manifest: which config is this APK?"""
    try:
        out = subprocess.run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
                             capture_output=True, text=True).stdout
    except OSError:
        return None
    if "debuggable" not in out:
        return False
    return bool(re.search(r"debuggable\(0x[0-9a-f]+\)=\(type 0x12\)0x0*1", out))


def declared_min_sdk(apk, aapt2):
    try:
        out = subprocess.run([aapt2, "dump", "badging", apk],
                             capture_output=True, text=True).stdout
    except OSError:
        return None
    m = re.search(r"sdkVersion:'(\d+)'", out)
    return int(m.group(1)) if m else None


def red(text):
    return "\033[31m%s\033[0m" % text


def find_aapt2(explicit):
    if explicit:
        return explicit
    here = os.path.dirname(os.path.abspath(__file__))
    for cand in (os.environ.get("AAPT2", ""), os.path.join(here, "vendor", "aapt2")):
        if cand and os.path.isfile(cand):
            return cand
    return "aapt2"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("apk")
    ap.add_argument("--manifest", default="")
    ap.add_argument("--allowlist", default="")
    ap.add_argument("--baseline", action="append", default=[], metavar="FILE",
                    help="toolchain-owned list of types the vendored AndroidX "
                         "names but cannot provide (androidx-known-dangling.txt); "
                         "excused types are printed, stale entries are not fatal")
    ap.add_argument("--kind", choices=ALLOW_SCOPES, default=None,
                    help="which configuration this APK is (default: infer from the "
                         "manifest's android:debuggable, else unknown)")
    ap.add_argument("--aapt2", default="")
    args = ap.parse_args(argv)

    if not os.path.isfile(args.apk):
        print("no such APK: %s" % args.apk, file=sys.stderr)
        return 2

    log = print
    aapt2 = find_aapt2(args.aapt2)
    kind = args.kind
    if kind is None:
        debuggable = declared_debuggable(args.apk, aapt2)
        kind = None if debuggable is None else ("debug" if debuggable else "release")
    with zipfile.ZipFile(args.apk) as z:
        entries = z.namelist()
    dexes = sorted(n for n in entries if n.endswith(".dex"))
    if not dexes:
        log("    %s no .dex in %s" % (red("FAIL"), args.apk))
        return 1

    log("    %s: %d dex, %d entries" % (args.apk, len(dexes), len(entries)))
    dexes, defined, referenced = apk_type_tables(args.apk)
    allowlist = read_allowlist(args.allowlist)
    baseline = {}
    for path in args.baseline:
        baseline.update(read_allowlist(path))
    problems = check_self_contained(defined, referenced, allowlist, baseline, kind, log)
    problems += check_components(args.manifest, defined, log)
    problems += check_dex_count(dexes, declared_min_sdk(args.apk, aapt2), log)
    problems += check_vectors(args.apk, log)

    if problems:
        print("\033[31mAPK VERIFY FAILED\033[0m (%d problem(s)) - do not install or publish"
              % problems)
        return 1
    print("    \033[32mok\033[0m  APK verify passed: self-contained, components present, "
          "floor consistent")
    return 0


if __name__ == "__main__":
    sys.exit(main())
