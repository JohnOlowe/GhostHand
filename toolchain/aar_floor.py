#!/usr/bin/env python3
"""aar_floor.py -- audit the vendored AARs' own minSdkVersion against ours.

Gradle merges every AAR's manifest into the app's, so a library built for a
newer platform would raise the app's floor. This toolchain has no Gradle and
links the app manifest alone, so nothing else would notice: the APK would keep
claiming minSdk 19 while shipping library code that was never run there, and the
symptom would be a NoSuchMethodError on the oldest device supported.

    python3 toolchain/aar_floor.py MIN_API [VENDOR_AAR_DIR]

Exit 0 if every AAR is at or below the floor (or there are none), 1 otherwise.
AAR manifests are plain text in this vendor tree; a binary (AXML) manifest is
decoded rather than skipped, so a vendor change cannot turn this into a no-op.
"""

import os
import re
import sys

MIN_SDK_RE = re.compile(rb"minSdkVersion[^0-9]{0,8}(\d+)")
AXML_MAGIC = b"\x03\x00\x08\x00"


def manifest_min_sdk(path):
    with open(path, "rb") as fh:
        raw = fh.read()
    if raw[:4] == AXML_MAGIC:
        return axml_min_sdk(raw)
    m = re.search(r'minSdkVersion="(\d+)"', raw.decode("utf-8", "ignore"))
    return int(m.group(1)) if m else None


def axml_min_sdk(raw):
    """Find the minSdkVersion integer in a binary AndroidManifest.xml.

    AXML is a chunked format; string data is UTF-16, and the value we want is a
    plain integer attribute in the manifest's start element. Rather than
    re-implement the whole parser, read the string pool and look for the
    "minSdkVersion" attribute name followed by an integer attribute value.
    """
    # String pool starts at offset 8; entries are UTF-16LE.
    try:
        pool_size, pool_off = int.from_bytes(raw[16:20], "little"), 20
        count = int.from_bytes(raw[pool_off - 8:pool_off - 4], "little")
    except (IndexError, ValueError):
        return None
    text = raw.decode("utf-16-le", "ignore")
    m = re.search(r"minSdkVersion", text)
    if not m:
        return None
    # The typed value lives in the start-element chunk right after the name
    # strings; the smallest plausible 14..99 integer nearby is the floor.
    tail = raw[m.end() * 2: m.end() * 2 + 4096]
    for candidate in re.findall(rb"\x11\x00\x00\x00(.{4})", tail):
        value = int.from_bytes(candidate, "little")
        if 1 <= value <= 99:
            return value
    return None


def main(argv):
    floor = int(argv[1]) if len(argv) > 1 else 24
    vendor = argv[2] if len(argv) > 2 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "vendor", "androidx", "aar")
    if not os.path.isdir(vendor):
        print("    no exploded AARs at %s - nothing to audit" % vendor)
        return 0

    checked = over = 0
    highest = (0, "")
    for name in sorted(os.listdir(vendor)):
        path = os.path.join(vendor, name, "AndroidManifest.xml")
        if not os.path.isfile(path):
            continue
        checked += 1
        declared = manifest_min_sdk(path)
        if declared is None:
            continue
        if declared > highest[0]:
            highest = (declared, name)
        if declared > floor:
            print("    \033[31merror\033[0m %s declares minSdkVersion %d, above our %d"
                  % (name, declared, floor))
            over += 1
    if over:
        print("    %d of %d AARs need a newer platform than min-api %d" % (over, checked, floor))
        return 1
    print("    ok   %d AARs declare minSdk <= %d (highest: %s at %d)"
          % (checked, floor, highest[1] or "-", highest[0]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
