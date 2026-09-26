#!/usr/bin/env python3
"""manifest_keep.py -- generate R8 keep rules from AndroidManifest.xml.

R8 never reads AndroidManifest.xml. The activities, services, receivers and
providers Android instantiates *by name* look to R8 like unused classes, so the
release build deletes them: the APK still builds, signs and verifies, and then
dies at launch with `ClassNotFoundException` on the component the framework
tried to create. AGP generates keep rules from the merged manifest; this does
the same from ours, so a component cannot be forgotten the way a hand-written
list in proguard.pro can.

    python3 toolchain/manifest_keep.py AndroidManifest.xml [--out FILE]

Rules go to stdout (or --out); the component count goes to stderr.
"""

import argparse
import os
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
COMPONENT_TAGS = ("activity", "activity-alias", "service", "receiver", "provider")


def local(tag):
    return tag.rsplit("}", 1)[-1]


def qualify(package, name):
    """Android's own rule: '.Foo' and 'Foo' are both <package>.Foo."""
    if not name:
        return None
    if name.startswith("."):
        return package + name
    if "." not in name:
        return package + "." + name
    return name


def keep_rules(manifest_path):
    """[(class_name, why)] for every class the manifest makes the framework load."""
    root = ET.parse(manifest_path).getroot()
    package = root.get("package") or ""
    out = []
    for elem in root.iter():
        tag = local(elem.tag)
        if tag == "application":
            fqcn = qualify(package, elem.get(ANDROID + "name"))
            if fqcn:
                out.append((fqcn, "application class"))
        elif tag in COMPONENT_TAGS:
            fqcn = qualify(package, elem.get(ANDROID + "name"))
            if fqcn:
                out.append((fqcn, "%s declared in the manifest" % tag))
            target = qualify(package, elem.get(ANDROID + "targetActivity"))
            if target:
                out.append((target, "activity-alias target"))
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("manifest")
    ap.add_argument("--out", default="")
    args = ap.parse_args(argv)

    if not os.path.isfile(args.manifest):
        print("no such manifest: %s" % args.manifest, file=sys.stderr)
        return 2

    rules = keep_rules(args.manifest)
    lines = ["# Generated from %s by toolchain/manifest_keep.py -- do not edit."
             % os.path.basename(args.manifest),
             "# A component added to the manifest is covered the moment it is declared.",
             ""]
    seen = set()
    for fqcn, why in rules:
        if fqcn in seen:
            continue
        seen.add(fqcn)
        lines.append("# %s" % why)
        lines.append("-keep class %s { *; }" % fqcn)
        # The framework also loads nested classes of a component (an
        # AccessibilityService's gesture callbacks, for instance).
        lines.append("-keep class %s$* { *; }" % fqcn)
        lines.append("")

    text = "\n".join(lines)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write(text)
    else:
        sys.stdout.write(text)
    print("    manifest keep rules: %d component(s)%s"
          % (len(seen), " -> %s" % args.out if args.out else ""), file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
