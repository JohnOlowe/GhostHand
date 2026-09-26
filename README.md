# GhostHand - build artifacts (generated, do not edit)

This branch exists only to hand out the APKs. It is **replaced on every publish** and
therefore always has exactly **one commit** and exactly **two APKs**.
That is on purpose: build output must never accumulate in the source history, or every
clone would drag along every APK ever built.

| File | Size | Runs on | What it is | SHA-256 |
|---|---|---|---|---|
| `GhostHand.apk` | 2.5M | Android 19\+ | **release**, R8-shrunk and optimised, one dex file | `f12916f95d5c5c3f94ef19d5868cdb099de8b9c8cc520770ab43023cfc8a401e` |
| `GhostHand-debug.apk` | 5.9M | Android 21\+ | **debug**, unshrunk: nothing renamed or removed, six dex files | `96ab72138e00dbfee171f16679b17f73d11bfa171b3644afcc667745a6f8eb16` |

Both are the same app - package `damjay.control.ghosthand`, versionCode `8`, signed
by the same key - **so either installs straight over the other**, in either order, keeping
your data. Two differences are worth knowing:

* R8 renames and inlines things on its way to the smaller file. That is the point of it.
* The debug build is unshrunk, so it carries whole libraries as separate dex files, and
  Dalvik (Android 4.4) only ever loads the first one. That is why it declares Android
  21 as its floor and the release build does not: the release build is a
  single dex file and runs on 4.4.

## Install

```bash
git fetch origin apk
git show FETCH_HEAD:GhostHand.apk > GhostHand.apk             # smaller, R8-shrunk
git show FETCH_HEAD:GhostHand-debug.apk > GhostHand-debug.apk  # bigger, unobfuscated

adb install -r GhostHand.apk        # or GhostHand-debug.apk - either order works
```

Every build is signed with the same key (`keystore/damjay_debug.keystore`), so a new
APK installs straight over the previous one - no uninstalling, no lost settings.

## Which one do I want

* **Want it to be small, or you are shipping it:** `GhostHand.apk`.
* **Something is behaving oddly and you want to see:**
  `GhostHand-debug.apk` - nothing is renamed or removed, so a stack trace points at real class
  names and the accessibility service, encoder and protocol classes are all present as
  written. It is the bigger download and it is slower, and it logs more.
* **Download of the big one failing:** take `GhostHand.apk`. Same app, same key, smaller file -
  and it is the one that runs on the oldest phones.
* **Android 4.4:** take `GhostHand.apk`. The debug build will refuse to install there
  (INSTALL_FAILED_OLDER_SDK) because it needs the platform's multi-dex loading.

## Build them yourself

```bash
bash build.sh                                 # setup + AndroidX + 63 unit tests + release APK
bash build.sh --both                          # both APKs into app/build/
bash build.sh --publish                       # both, then replace this branch
```

| | |
|---|---|
| Built | 2026-09-26 20:30 UTC |
| Source | `4cfde5e` - Add guest-driven host rotation and a two-way clipboard bridge |
| Signer | O=Photo Triage, CN=Photo Triage Stable Key |
