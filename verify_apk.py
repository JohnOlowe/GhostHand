#!/usr/bin/env python3
"""verify_apk.py -- check that a built APK still contains what the app needs.

    python3 verify_apk.py [app/build/app.apk]

Why this exists
---------------
The release build is shrunk by R8, and R8's call graph starts from the code it can
see. Android is not in that graph: the classes it instantiates (activities, services,
the accessibility service) and the methods it calls (lifecycle callbacks, onClick,
onReceive) are only reachable if the keep rules say so.

Getting that wrong does not break the build. It produces a *smaller, valid, signed
APK* that crashes or silently does nothing on a real phone - which is exactly the
class of bug that cannot be caught here, because there is no device or emulator.

It is not hypothetical: `InjectionAccessibilityService` was reduced to two static
methods by R8 (its `onServiceConnected` was dead code to R8, so the static `instance`
field looked like it could never be set, so the `dispatchGesture` call was
unreachable, so the whole injection path was deleted) - and only in the release APK.
Everything compiled, signed and "verified" happily.

So the build checks the artifact instead of trusting it:

  * required **classes**      - is the class still in the dex at all, and under its
                                original name (the framework looks them up by name)
  * required **method names** - string-table check for the calls that must survive
                                (a renamed call to a library method would be a
                                NoSuchMethodError on the device)
  * required **resources**    - the accessibility config, which carries the
                                canPerformGestures flag the whole feature hinges on

Exit code 0 = the APK is intact, 1 = something was shrunk away, 2 = usage/IO error.
"""

import sys
import zipfile

# Classes the framework instantiates by name from AndroidManifest.xml, plus the core
# of each subsystem. A missing one means the app is broken in a way no compiler sees.
REQUIRED_CLASSES = [
    # framework entry points (android:name=".Something")
    "damjay/control/ghosthand/MainActivity",
    "damjay/control/ghosthand/HostActivity",
    "damjay/control/ghosthand/GuestActivity",
    "damjay/control/ghosthand/host/ScreenCaptureService",
    "damjay/control/ghosthand/host/InjectionAccessibilityService",
    # the protocol layer: pure Java, so if this is missing the app cannot talk at all
    "damjay/control/ghosthand/net/GhostProtocol",
    "damjay/control/ghosthand/net/FrameCodec",
    "damjay/control/ghosthand/net/Record",
    # host side
    "damjay/control/ghosthand/host/ScreenEncoder",
    "damjay/control/ghosthand/host/ClientHub",
    "damjay/control/ghosthand/host/ClientConnection",
    "damjay/control/ghosthand/host/HostController",
    "damjay/control/ghosthand/host/TouchInjector",
    # guest side
    "damjay/control/ghosthand/guest/GuestController",
    "damjay/control/ghosthand/guest/VideoDecoder",
]

# Method and field names that must appear in the dex string table. These are either
# calls into the framework (which cannot be renamed without becoming a
# NoSuchMethodError at runtime) or lifecycle methods the framework calls by name.
REQUIRED_STRINGS = [
    # MediaProjection / capture
    "getMediaProjection",
    "createVirtualDisplay",
    "startForeground",
    # MediaCodec, both directions
    "createEncoderByType",
    "createDecoderByType",
    # touch injection - the exact call R8 deleted once
    "dispatchGesture",
    "onServiceConnected",
    "onAccessibilityEvent",
    "displaySize",
    # framework callbacks implemented by our components
    "onStartCommand",
    "onConfigurationChanged",
    "onRequestPermissionsResult",
    # the guest -> host control channel
    "onGuestTouch",
    # discovery
    "discoverServices",
]

# Resources that have to survive the link step. These are compiled to binary XML, so
# this is a byte search and not an XML parse - and the encoding differs by file:
# aapt2 writes the manifest's string pool as UTF-16LE while attribute *names* in a
# compiled res XML stay ASCII. Both are tried, so the check cannot silently pass
# because of an encoding guess.
#
# Note there is no "mediaProjection" here on purpose: aapt2 resolves that attribute
# value to its integer flag (0x20) during compilation, so the *string* is legitimately
# gone from the built manifest. Only values aapt2 cannot resolve stay as text.
REQUIRED_RESOURCE_STRINGS = [
    ("res/xml/accessibility_service_config.xml", "canPerformGestures"),
    ("AndroidManifest.xml", "InjectionAccessibilityService"),
    ("AndroidManifest.xml", "android.accessibilityservice.AccessibilityService"),
    ("AndroidManifest.xml", "BIND_ACCESSIBILITY_SERVICE"),
    ("AndroidManifest.xml", ".host.ScreenCaptureService"),
]


def fail(message):
    print("\033[31m    FAIL\033[0m %s" % message)
    return 1


def main(argv):
    apk = argv[1] if len(argv) > 1 else "app/build/app.apk"
    try:
        archive = zipfile.ZipFile(apk)
    except (OSError, zipfile.BadZipFile) as exc:
        print("cannot read %s: %s" % (apk, exc), file=sys.stderr)
        return 2

    names = archive.namelist()
    dexes = sorted(n for n in names if n.endswith(".dex"))
    if not dexes:
        return fail("%s contains no classes.dex" % apk)

    dex_bytes = b"".join(archive.read(n) for n in dexes)
    print("    %s: %d dex, %d entries, %.1f MB of dex"
          % (apk, len(dexes), len(names), len(dex_bytes) / 1048576.0))

    problems = 0

    missing_classes = [c for c in REQUIRED_CLASSES
                       if ("L%s;" % c).encode() not in dex_bytes]
    if missing_classes:
        for c in missing_classes:
            problems += fail("class shrunk away or renamed: %s" % c)
        print("    (fix: a -keep rule in app/proguard.pro for each class above)")

    missing_strings = [s for s in REQUIRED_STRINGS if s.encode() not in dex_bytes]
    if missing_strings:
        for s in missing_strings:
            problems += fail("no reference to '%s' anywhere in the dex" % s)
        print("    (fix: a -keep rule covering the class that makes this call -"
              " R8 deletes the caller when it cannot see the callee as reachable)")

    for entry, needle in REQUIRED_RESOURCE_STRINGS:
        if entry not in names:
            problems += fail("missing resource entry: %s" % entry)
            continue
        data = archive.read(entry)
        if needle.encode() in data or needle.encode("utf-16-le") in data:
            continue
        problems += fail("%s no longer contains '%s'" % (entry, needle))

    if problems:
        print("\033[31mAPK VERIFY FAILED\033[0m  (%d problem(s)) - do not install or "
              "publish this build" % problems)
        return 1

    print("\033[32m    ok\033[0m  APK VERIFY PASSED  (%d classes, %d call strings, "
          "%d resources present)"
          % (len(REQUIRED_CLASSES), len(REQUIRED_STRINGS),
             len(REQUIRED_RESOURCE_STRINGS)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
