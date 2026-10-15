# GhostHand — current APK pair

Source commit: `654557e7c56f8f77fabac723241c17a0ad16d966`

Only this tip commit contains the generated APKs. Each publication removes older builds from this branch's reachable history.

| File | Size | API floor | What it is | SHA-256 |
|---|---|---|---|---|
| `GhostHand.apk` | 2.6M | API 19 | **release**, R8-shrunk and optimised, one dex file | `193b6fb0a71aebd2e30184d132a727cac38c50f7e7078a826d5ce9a3ef98a44a` |
| `GhostHand-debug.apk` | 5.9M | API 21 | **debug**, unshrunk: nothing renamed or removed, six dex files | `1d597325f76fad5d66dfb54fafe04aca7a369d4e4df7f480675a71909b620e08` |

Both are the same app - package `damjay.control.ghosthand`, versionCode `15`, signed
by the same key - **so either installs straight over the other**, in either order, keeping
your data. Two differences are worth knowing:

* R8 renames and inlines things on its way to the smaller file. That is the point of it.
* The debug build is unshrunk, so it carries whole libraries as separate dex files, and
  Dalvik (Android 4.4) only ever loads the first one. That is why it declares API
  21 as its floor and the release build does not: the release build is a
  single dex file and runs on 4.4.

| | |
|---|---|
| Package | `damjay.control.ghosthand` |
| VersionCode | `15` |
| Built/published | 2026-10-15 16:41 UTC |
| Signer | O=Photo Triage, CN=Photo Triage Stable Key |

Install either APK over your existing app; both retain the same signing key.
On Android 4.4 use GhostHand.apk (API 19). The plain debug build needs API 21.
