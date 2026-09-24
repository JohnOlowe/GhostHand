# GhostHand

**One phone becomes the screen. The other becomes the hands.**

GhostHand streams one Android phone's screen to another over WiFi - no cables, no
cloud, no accounts. Two phones on the same network, one APK, two roles:

| | |
|---|---|
| **Host** | Captures its own screen (`MediaProjection`), encodes it to H.264 (`MediaCodec`) and serves it over TCP. |
| **Guest** | Finds the host with mDNS (or an IP you type), decodes the H.264 and draws it full screen. |

**Milestones 1 and 2 (this build): mirroring *and* touch.** The guest already sent touch
events; the host now actually injects them, so dragging on the guest moves the real cursor
on the host's screen. The last hop needs a permission an ordinary app cannot have, and the
whole mechanism is explained in [Touch injection](#touch-injection) - including the R8
keep-rule trap that silently deleted it from the release APK.

Package `damjay.control.ghosthand` · minSdk 26 (Android 8.0) · targetSdk 34 · Java, no Kotlin · **AndroidX + Material 3**

---

## Quick start

```bash
git clone https://github.com/JohnOlowe/GhostHand.git
cd GhostHand
bash build.sh                 # setup + AndroidX + 63 unit tests + signed APK (~2.5 min cold)
```

`build.sh` installs the toolchain into `toolchain/vendor` (JRE, Eclipse compiler, aapt2,
D8/R8, apksigner) and harvests AndroidX into `toolchain/vendor/androidx`, then produces a
**signed, zip-aligned, R8-shrunk APK** at `app/build/app.apk` (2.2 MB). Want both
configurations - the shrunk one and the unobfuscated one - in one go?

```bash
bash build.sh --both          # + app/build/app-debug.apk (5.4 MB, debuggable)

adb install -r app/build/app.apk     # on BOTH phones
```

Then: open GhostHand on phone A -> **Host** -> *Start mirroring* -> allow screen capture.
On phone B -> **Guest** -> tap the host in the list (or type the IP shown on phone A) ->
watch.

---

## Where the APK lives (and why it is not in `main`)

The APK is a few megabytes and different on every build. Committing it to `main` would add
a new, non-delta-compressible blob to history on every push, so `git clone` would
eventually drag along every APK ever built.

So the APKs live on their own branch, and **each publish replaces that branch with a single
parentless commit**:

```
 apk:  *                                     <- always exactly 1 commit, both APKs
 main: *---*---*---*---*---*---*             <- source history, never a binary
```

**Two builds, both in that one commit:**

| | file | what it is |
|---|---|---|
| **2.2 MB** | `GhostHand.apk` | the **release** build - R8 shrunk, renamed and inlined. What to install. |
| **5.4 MB** | `GhostHand-debug.apk` | the **debug** build - nothing shrunk, nothing renamed, and genuinely `android:debuggable` (real class names in a stack trace, `run-as`, attachable debugger). |

Both are the *same app*: same package, same versionCode, same key, same signature scheme -
so **either one installs straight over the other, in either order**, keeping your data.
Whichever downloads more reliably for you is the right one to take.

```bash
# get the current builds: one fetch, both files
git fetch origin apk
git show FETCH_HEAD:GhostHand.apk > GhostHand.apk                 # smaller
git show FETCH_HEAD:GhostHand-debug.apk > GhostHand-debug.apk     # bigger, unobfuscated

# publish a new pair (force-pushes one orphan commit over the last)
bash build.sh --publish
```

`publish-apk.sh` builds the commit with git plumbing (`hash-object` -> `mktree` ->
`commit-tree`, pushed with `--force`), so nothing in your working tree is touched and the
branch can never grow a second commit. It refuses to publish an APK that fails the structural
check, or one whose signature does not verify, and it **refuses the pair outright if the two
would not be installable over each other** - it compares the package name, the versionCode
and the signing certificate before writing anything, because a mismatch there is
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` on the phone, which is a bad place to discover it. It
writes both SHA-256s into the branch's README.

Every APK - from any branch, any machine - is signed with the same key
(`keystore/damjay_debug.keystore`, the PalmPay-Clone stable key), so a new build **installs
straight over the previous one**. No uninstall, no `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

---

## How to build and test (no Android SDK anywhere)

There is no Gradle wrapper here and no SDK install step, because the build environment
cannot reach `dl.google.com`, Maven Central or `maven.google.com`. Instead `toolchain/`
assembles a working Android toolchain from PyPI, npm and GitHub:

| step | tool | from |
|---|---|---|
| run Java | Temurin **JRE** (no `javac`) | PyPI `jdk4py` |
| compile Java | Eclipse **ECJ** batch compiler | npm `@vscjava/java-language-server` |
| compile XML / resources / manifest | **aapt2** | PyPI `aapt2` |
| Android platform API | **android.jar** (API 34) | npm `@drxiaozhi/minapk` |
| **AndroidX** (appcompat, Material 3, RecyclerView, ConstraintLayout, lifecycle) | **69 AARs → one classpath jar + compiled resources + per-library `R` classes** | a Gradle cache committed to a GitHub repo |
| lambda support at `-source 8` | **core-lambda-stubs.jar** | compiled locally from a 37-line stub |
| bytecode → dex | **D8** (debug) / **R8** (release) | npm `@drxiaozhi/minapk` |
| sign | **apksigner** | npm `@drxiaozhi/minapk` |
| align | **zipalign** (reimplemented in Python) | `toolchain/zipalign.py` |
| run unit tests | **JUnit 4.13.2** | npm `@vscjava/java-language-server` |

```bash
bash toolchain/setup.sh              # once: verifies every tool by running it
bash toolchain/androidx.sh           # once: fetches + assembles AndroidX (~35 s, in vendor/)
bash toolchain/check.sh  app         # fast "does it compile?" loop (~20 s)
bash toolchain/test.sh   app         # JUnit unit tests (~2 s, no device needed)
bash toolchain/build.sh  app --release --verify
python3 verify_apk.py    app/build/app.apk   # did R8 keep what the app needs?
bash build.sh --both                         # release + debug APKs
```

Two things AGP would have done for you are done explicitly here, because there is no AGP:
`app/proguard.pro` carries the keep rules R8 needs
([why](#r8-and-the-keep-rules-nobody-generated-for-us)), and a non-release build passes
`--debug-mode` to `aapt2` so the "debug" APK really is `android:debuggable="true"`. Without
that flag it is only *unshrunk* - no attachable debugger, no `run-as`, nothing in logcat
saying it is debuggable - which is a trap when the whole point of the file is being able to
see what it is doing.

`toolchain/README.md` documents each script; `RECIPE.md` explains where the tools come from
and which routes are dead ends. This is the supported build path - it runs identically on a
laptop and in CI, and there is no GitHub Actions workflow to wait on.

---

## The AndroidX port

The first build of this app was framework-only (`android.app.Activity`, `findViewById`,
`Notification.Builder`, `@android:style/Theme.Material`), because AndroidX cannot be
resolved without Maven. It now uses AndroidX and Material 3 - the interesting part is
*how* AndroidX arrives, and what is still missing.

### Where AndroidX comes from, and what has to be done to it

An AAR is a zip of `classes.jar`, `res/`, `R.txt` and a manifest. The Android Gradle Plugin
earns its keep by: putting every `classes.jar` on the classpath, compiling every `res/` and
merging the tables, and **generating each library's `R` class** (AndroidX AARs ship `R.txt`
with `0x0` placeholders and no `R.class` at all). `toolchain/androidx.sh` does all three:

| output | what it is | who consumes it |
|---|---|---|
| `vendor/androidx/androidx.jar` | 56 jars merged flat (4 318 classes) | ECJ `-classpath`, and **program input** to D8/R8 |
| `vendor/androidx/res/*.zip` | one `aapt2 compile` zip per library | `aapt2 link -R …` |
| `vendor/androidx/packages.txt` | 46 library package names | `aapt2 link --extra-packages …` |

That last row is the trick worth knowing: `--extra-packages` makes aapt2 emit a **correct
`R.java` per library** - including populated `styleable` arrays - which is exactly the input
Material's own code compiles against. Without it, `Theme.Material3.*` and every
`MaterialCardView` attribute would fail to resolve.

### What each AndroidX piece replaced

| before (framework-only) | now | why it matters here |
|---|---|---|
| `android.app.Activity` | `AppCompatActivity` | AppCompat's view inflater builds every widget from the layout, which is what lets the Material 3 theme reach views the platform would otherwise render plain. |
| `startActivityForResult` + `onActivityResult` + hand-rolled request codes | `registerForActivityResult(new ActivityResultContracts.RequestPermission())` and `…StartActivityForResult()` | the MediaProjection consent dialog and the Android 13 notification grant get typed callbacks that survive process recreation; request codes and `onActivityResult` branching disappear. |
| `Switch` | `com.google.android.material.materialswitch.MaterialSwitch` | Material 3 switch styling, still a `CompoundButton` so the listener code is unchanged. |
| `Spinner` | `TextInputLayout` (`ExposedDropdownMenu`) + `MaterialAutoCompleteTextView` | the Material 3 dropdown; `setSimpleItems(R.array.host_resolution_options)` feeds it the string-array directly. |
| `EditText` (IP field) | `TextInputLayout` + `TextInputEditText` | hints, focus and errors are drawn by the component: `boxHost.setError(...)` replaces hand-written validation text. |
| `LinearLayout` + inflating rows by hand | `RecyclerView` + a real adapter | discovered hosts are recycled, and rows are updated by `notifyDataSetChanged()` instead of tearing the list down on every mDNS update. |
| `Toast` | `Snackbar` | anchored to a view, dismissible, themed. |
| `setSystemUiVisibility(FLAG_FULLSCREEN …)` | `WindowCompat` + `WindowInsetsControllerCompat` | **on API 30+ the old flags are ignored**, so a framework-only build silently stayed windowed on a modern phone. The compat controller uses `WindowInsetsController` where it exists and the legacy flags below 30. |
| `Notification.Builder` | `NotificationCompat.Builder` | small-icon masking and action behaviour normalised across API levels. |
| `@android:style/Theme.Material.NoActionBar` | `Theme.Material3.DayNight.NoActionBar` | full Material 3 palette: colours are Material *roles* (`colorPrimary`, `colorSurface`, `colorOnSurfaceVariant`) declared once in the theme, with `values/colors.xml` + `values-night/colors.xml` supplying light and dark. No per-widget styling. |

### What is still *not* available: view binding

`androidx.viewbinding` is generated code: the Android Gradle Plugin reads the layouts at
build time and writes `ActivityHostBinding.java`. Nothing in this toolchain generates Java
from XML (aapt2 emits `R.java` and nothing else), so the views are still found with
`findViewById`. The ids in the layouts remain the contract between XML and Java, and
`toolchain/xmlcheck.py` is what keeps that contract honest - it resolves every
`@style`, `@string` and `@drawable` reference against both the project and the AndroidX
resources *before* aapt2 ever runs.

### R8 and the keep rules nobody generated for us

The release build is shrunk and optimised by R8 (5.4 MB debug -> **2.2 MB** release). With
AGP, the "keep the manifest components" rules are generated automatically; here there is no
AGP, so `app/proguard.pro` carries them by hand. Without those few lines R8 would rename or
strip the very classes Android instantiates from the manifest and the app would die with
`ClassNotFoundException` on launch. The file also keeps the names of all `View` subclasses,
because `AppCompatViewInflater` resolves widget classes **by name from the XML**.

Three rules in there are load-bearing and easy to get wrong:

* `-keepattributes InnerClasses` **requires** `EnclosingMethod` in the same directive, or R8
  refuses the build outright (`Attribute InnerClasses requires EnclosingMethod attribute`);
* `-keepnames class damjay.control.ghosthand.**` protects the classes the platform looks up
  by name (`android:name=".HostActivity"`, the service, saved-state keys);
* the `InjectionAccessibilityService` keep pair - without it R8 deletes the entire touch
  feature from the release APK while the debug APK keeps working. That story is told in full
  under [the keep rule that saved the release build](#the-keep-rule-that-saved-the-release-build-a-genuinely-nasty-one),
  and it is now enforced by a check rather than by vigilance.

The published APK is verified structurally after shrinking, in two ways: `apktool` decodes it
back to smali and resources to confirm the components are there **under their original names**,
and `verify_apk.py` asserts the classes, the framework call strings and the accessibility
resource are still in the artifact (`build.sh` runs it; `publish-apk.sh` refuses to publish
without it).

---

## The map: which file does what

```
app/src/main/java/damjay/control/ghosthand/
├── MainActivity.java            pick a role; the launcher activity
├── HostActivity.java            host dashboard: permission flow, stats, touch switch
├── GuestActivity.java           guest screen: discovery, video surface, touch capture
│
├── net/                         the wire protocol - NO android.* imports, unit-tested
│   ├── GhostProtocol.java       constants + sizing maths (16-byte header, port, fit)
│   ├── Frame.java               one parsed message (type, flags, pts, payload)
│   ├── Record.java              hand-rolled TLV key/value payload bag
│   └── FrameCodec.java          Frame <-> bytes, streaming and blocking
│
├── host/                        everything that runs on the phone being watched
│   ├── ScreenCaptureService.java  foreground service; owns projection, display, hub
│   ├── ScreenEncoder.java         MediaCodec H.264 encoder fed by a Surface
│   ├── ClientHub.java             TCP server; one ClientConnection per guest
│   ├── ClientConnection.java      per-guest queue + writer thread + reader thread
│   ├── HostController.java        mDNS advertise + "my IP addresses" listing
│   ├── TouchInjector.java         gesture planner: guest points -> one stroke - NO android.*
│   └── InjectionAccessibilityService.java  the only thing allowed to inject input
│
└── guest/                       everything that runs on the phone doing the watching
    ├── GuestController.java       mDNS discovery + socket + frame dispatch
    └── VideoDecoder.java          MediaCodec H.264 decoder + frame pacing
```

Read it in this order and the whole system falls out: `net/` (the language the two phones
speak) -> `host/` (one side of the conversation) -> `guest/` (the other side) -> the three
activities (the humans).

### How the pieces are wired

```
 HOST PHONE                                          GUEST PHONE
 ───────────                                         ───────────
 HostActivity
   │ ContextCompat.startForegroundService(ACTION_START, token)
   ▼
 ScreenCaptureService ── MediaProjection ──► VirtualDisplay
   │                                              │ renders into
   │                                              ▼
   │                                         ScreenEncoder
   │                                              │ pushes encoded frames
   │                                              ▼
   │ mDNS advert "_ghosthand._tcp"          ClientHub (ServerSocket :47811)
   │                                              │  one queue per guest
   │                                              ▼
   │                                        ClientConnection.writer ─┐
   │                                                                 │ TCP
   └── (activity listens for ACTION_STATE/ACTION_STATS)              │
                                                                     ▼
                                                          GuestController.reader
                                                            │                │
                                          VIDEO (reader thread)      control frames
                                                            ▼                ▼
                                                     VideoDecoder     GuestActivity
                                                            │           (main thread)
                                                            ▼
                                                     SurfaceView
```

* **Host -> guest** runs one way and continuously: `VIDEO` frames plus occasional
  `VIDEO_CONFIG`, `GEOMETRY`, `STATS`, `PONG`, `BYE`.
* **Guest -> host** carries `HELLO`, `PING` and `TOUCH`.
* The service and the activity never touch each other's objects - commands go in by
  `startForegroundService(intent)`, state comes back by **package-private broadcast**, which
  is why Home-ing out of `HostActivity` does not interrupt the stream.

---

## The wire protocol

Every message is one 16-byte header plus a payload. Big-endian throughout, which is exactly
what `DataInputStream` and `ByteBuffer` do by default.

```
 byte  0        1         2        3        4..11                12..15
      +--------+---------+--------+--------+-------------------+----------------+
      | MAGIC  | VERSION |  TYPE  | FLAGS  | PRESENTATION TIME | PAYLOAD LENGTH |
      |  0x47  |  0x01   |        |        |   int64, µs       |     int32      |
      +--------+---------+--------+--------+-------------------+----------------+
      |                        PAYLOAD (0..8 MiB)                              |
      +------------------------------------------------------------------------+
```

| # | type | direction | payload |
|---|---|---|---|
| 1 | `HELLO` | guest -> host | `Record{device, version, w, h}` |
| 2 | `TOUCH` | guest -> host | `Record{action, x, y, pointer, timeMs}` |
| 3 | `PING` | guest -> host | 8-byte token |
| 4 | `WELCOME` | host -> guest | `Record{device, w, h, fps, version, mode}` |
| 5 | `PONG` | host -> guest | the same token |
| 6 | `VIDEO_CONFIG` | host -> guest | `Record{w, h, csd0, csd1}` - raw SPS/PPS, no start codes |
| 7 | `VIDEO` | host -> guest | one H.264 access unit; `FLAGS` bit 0 = key frame |
| 8 | `GEOMETRY` | host -> guest | `Record{w, h, rotation}` after a rotate/resize |
| 9 | `STATS` | host -> guest | `Record{fps, kbps, dropped, clients, uptimeMs}` |
| 10 | `BYE` | either | UTF-8 reason |

Design notes that matter:

* **One header for everything.** Video, pings and touches share a socket, so there is one
  connection to lose, one place to detect a dead peer, and no ordering surprises between
  "the picture" and "the finger".
* **`Record` is a hand-rolled TLV bag**, not JSON or Java serialisation: no reflection, no
  class names on the wire, no schema to keep in sync between two APK versions. Unknown keys
  are simply ignored, which is what makes a version skew survivable.
* **PTS is 8 bytes** because `MediaCodec` hands out microseconds as a `long`; truncating to
  4 bytes wraps after ~71 minutes.
* **Framing is strict, not self-resynchronising.** A bad magic byte or a payload length over
  8 MiB marks the stream broken and drops the connection. Hunting for the next `0x47` in an
  arbitrary byte stream is how you decode half a video frame as a touch event.

---

## How the host works

### 1. Permission, then a foreground service

Screen capture is not a normal runtime permission.
`MediaProjectionManager.createScreenCaptureIntent()` shows a system dialog, and the
`resultCode` + `Intent` you get back is a **single-use token for one projection**.
`HostActivity` runs the dialog through an `ActivityResultLauncher` and forwards the token to
the service:

```java
projectionLauncher.launch(manager.createScreenCaptureIntent());
// … on RESULT_OK:
intent.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.getData());
ContextCompat.startForegroundService(this, intent);
```

The capture has to live in a foreground service for two reasons: Android 10+ kills background
apps that hold a projection, and it has to keep streaming when you press Home. Order matters
on Android 14:

```java
startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
projection = manager.getMediaProjection(resultCode, data);   // only now
```

Calling `getMediaProjection` first throws `SecurityException` on API 34+.

### 2. VirtualDisplay -> encoder

```
screen ──► VirtualDisplay ──(input Surface)──► MediaCodec encoder ──► H.264 NAL units
```

A `VirtualDisplay` is the system's own compositor output: everything on the physical screen
is drawn into a `Surface` instead. Hand that Surface to `MediaCodec` as an **input** surface
and the encoder consumes frames without a single pixel crossing into Java heap.

```java
encoder = new ScreenEncoder(w, h, bitrate, 30, this);
encoder.start();
projection.createVirtualDisplay("GhostHand", w, h, densityDpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, encoder.getInputSurface(), null, null);
```

* `AUTO_MIRROR` is what makes the system *distrust* secure windows (banking apps draw black).
  The host UI can switch to `PUBLIC` at runtime - that is a property of the display, so only
  the `VirtualDisplay` is recreated; the encoder keeps running.
* Sizing goes through `GhostProtocol.fitCaptureSize(w, h, maxSide)`: scale so the longest side
  matches the chosen preset, then round **down to a multiple of 16**. H.264 works in 16x16
  macroblocks, and an odd size gets padded and smears the right and bottom edges.
* Bitrate comes from `suggestedBitrate(w, h, fps)` - roughly 0.1 bit per pixel per frame,
  clamped to 0.8-16 Mbps. 720p30 lands near 2.8 Mbps, which any decent WiFi link carries with
  room to spare.
* The encoder is configured with `KEY_I_FRAME_INTERVAL = 2` (a key frame every two seconds, so
  a guest that connects mid-stream syncs quickly) and `KEY_LATENCY = 1` (no B-frames, no
  reordering: latency beats compression for interactive use).

### 3. Rotation is a restart, not a resize

Encoders cannot change their output size after `configure()` - the only parameters that can
change live are bitrate, sync-frame requests, suspend and low-latency mode. So when the host
rotates, `onConfigurationChanged` releases the `VirtualDisplay` and the encoder and builds
fresh ones at the new size. The `MediaProjection`, the `ServerSocket` and every guest socket
survive, so the viewer sees a brief pause, then a new `VIDEO_CONFIG` and a key frame - not a
disconnect.

### 4. One queue per guest, and dropping the right thing

`ClientHub` accepts sockets and gives each guest a `ClientConnection` with two threads: a
**reader** (drains `HELLO`/`PING`/`TOUCH`, answers `PING` from the reader itself so latency is
measured without a thread hop) and a **writer** (blocks on a `BlockingQueue`).

The queue is bounded at 120 frames. When a guest's link is slower than 30 fps, the writer
cannot keep up, and something has to give:

* **`VIDEO` frames are dropped** - in fact the hub drops video to make room rather than
  blocking the capture thread, because a mirroring host must never stall the encoder waiting
  for the slowest viewer.
* **Control frames (`VIDEO_CONFIG`, `GEOMETRY`, `STATS`, `PONG`, `BYE`) are never dropped.**
  Losing a `VIDEO_CONFIG` would leave that guest unable to decode anything until the next key
  frame. Losing a `GEOMETRY` would leave it permanently letterboxed wrong.
* The guest has a matching recovery path: when `VideoDecoder` drops a frame it "re-arms" a
  gate and waits for the next IDR before rendering again, so a dropped P-frame can never
  produce a permanently corrupt picture.

---

## How the guest works

### 1. Finding the host

Two ways in, because mDNS does not survive every router (AP isolation, client isolation,
corporate WiFi):

1. **mDNS/NSD.** The host advertises `_ghosthand._tcp.` on port 47811. `GuestController` uses
   `NsdManager.discoverServices`, and resolves hosts through a **serial queue** - NSD refuses
   to run many resolves at once, and a parallel fan-out fails with `FAILURE_ALREADY_ACTIVE` on
   most devices. Results are handed to a `RecyclerView` adapter.
2. **Manual IP.** The host screen shows its addresses (`HostController` enumerates
   `NetworkInterface` IPv4 addresses, preferring site-local 192.168.x.x/10.x.x.x). Type one in
   and connect. This is the fallback that always works when both phones are on the same subnet.

### 2. Decoding

`VideoDecoder` is a two-thread design: a **decoder thread** that blocks in
`dequeueOutputBuffer` and renders with a manually-pinned PTS, and the reader thread that feeds
input buffers. `MediaCodec` is not thread-safe, so only the decoder thread touches it while
the reader thread only ever calls `offerVideo(...)`, which puts the frame into a small
array-deque and returns. Two details that decide whether the picture looks right:

* **The output Surface is only valid between `surfaceCreated` and `surfaceDestroyed`.**
  Rendering into a released surface throws, so the decoder is started and stopped by those
  callbacks, and it needs *both* a live surface and the host's SPS/PPS before it can start at
  all - hence `startDecoderIfNeeded()` on both paths.
* **The decoder renders straight to the Surface.** `releaseOutputBuffer(index, true)` pushes
  the decoded frame to the display; the guest never sees a Bitmap.

`GuestActivity` then letterboxes the picture: it sizes `videoContainer` to the stream's aspect
ratio inside the window and matches the device orientation to the stream's, so a portrait
phone mirrored onto a portrait phone fills the screen exactly. Full-screen mode goes through
`WindowInsetsControllerCompat`, which is also what makes it work on Android 11+, where the old
`SYSTEM_UI_FLAG_*` bits are ignored.

### 3. Latency and liveness

Every 3 seconds the guest sends a `PING` (the host echoes it as `PONG` with the same token),
and the round-trip time is shown in the UI. The socket is set to a 1-second read timeout so
the reader thread wakes up regularly: if **20 seconds** pass with no traffic in either
direction, the guest declares the link dead and closes it, rather than showing a frozen last
frame forever.

---

## Touch injection

The guest converts every touch to **normalised** coordinates and sends them; the host turns
them back into a real gesture and hands it to Android. Both halves have a subtlety worth
knowing about.

### The guest half: normalised, not pixels

```java
float nx = event.getX() / (float) view.getWidth();    // 0.0 .. 1.0
controller.sendTouch(0 /*down*/, nx, ny, uptimeMs);   // x10000 on the wire
```

Normalised, not pixels, because the two phones almost never share a resolution and the
letterbox offsets must not leak into the coordinates. `ACTION_MOVE` is throttled (dropped if
the finger moved less than 0.4% of the view, or less than 16 ms has passed): 60 Hz of drag
events would otherwise compete with the video for a WiFi link that is already saturated.

### The host half: `dispatchGesture` needs a permission app code cannot get

Writing into another app's input stream is exactly the thing Android is built to prevent. The
only no-root, no-adb door is an **`AccessibilityService`** with
`android:canPerformGestures="true"`, which can post a `GestureDescription` to the input system:

```java
GestureDescription.StrokeDescription stroke =
        new GestureDescription.StrokeDescription(path, startMs, durationMs);
service.dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),
                        callback, null /* handler: dispatch on the calling thread */);
```

That is a big capability, so the user has to switch it on by hand in Settings > Accessibility.
Three things follow from that, and all three are handled in code:

* **The service may not be running.** `InjectionAccessibilityService.isEnabled(context)`
  reads `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` - reliable *before* the service has
  even bound - and `HostActivity` re-checks it in `onResume()` so the card is correct the
  moment you come back from Settings. The button deep-links to
  `Settings.ACTION_ACCESSIBILITY_SETTINGS`.
* **The service must be findable from a static context.** The framework instantiates it, so it
  cannot be created by our code; it publishes itself as `static instance()` from
  `onServiceConnected` and clears that in `onUnbind`/`onDestroy`.
* **Nothing may leak across a teardown.** If the guest lifts its finger while the stream is
  dying, a half-finished drag could be injected into whatever app is on top afterwards.
  `stopEverything()` calls `touchInjector.reset()`, which can only produce a `CANCEL`.

Two manifest attributes carry the whole feature and neither is optional:
`android:exported="true"` (the *system* is a different process and has to reach the service)
and `android.permission.BIND_ACCESSIBILITY_SERVICE` (the security boundary - it means only
the system may bind, which is why `exported="true"` is not a hole).

### `TouchInjector`: the part that is pure Java, and therefore testable

Everything above needs a phone. The *decision* of what gesture to send does not, so it lives
in a class that imports no `android.*` at all and is covered by unit tests:

```
DOWN(x0,y0)  MOVE(x1,y1)  MOVE(x2,y2)  UP(x2,y2)
                  |
                  v   TouchInjector.plan()
        Gesture { xs[], ys[], offsetsMs[], durationMs }
                  |
                  v   InjectionAccessibilityService.inject()
        GestureDescription -> dispatchGesture()
```

The rules it enforces, each born from a bug that happened in practice:

| rule | why |
|---|---|
| A **tap is a one-point stroke** held for the real press duration. | `dispatchGesture` has no "click"; a stroke of one point whose length is the press is the only faithful translation. |
| MOVE points closer than **6 px** are merged. | A slow finger emits hundreds of nearly identical points; each one would become a `Path` segment and a slop-sensitive jitter. |
| ...but a move under **12 px** does **not** move the tap. | Otherwise a shaky press walks the tap across the screen and its duration collapses to 0. The press **anchor** is tracked separately from the trailing point for exactly this reason. |
| Points are capped at **64** per gesture, evenly sampled. | `dispatchGesture` rejects enormous `Path`s, and a drop is cheaper than a rejection. |
| Duration is clamped to **40 ms .. 30 s**. | Below the floor a stroke is a mis-tap; above the ceiling the input system refuses it. |

A drag is dispatched on finger **lift**, not continuously. `dispatchGesture` posts a completed
gesture, so a *live* drag (the host screen following your finger in real time) is not possible
without streaming each segment - which would mean many small gestures and a visible stutter.
This is the honest limitation of the no-root path and it is noted in the UI; the root/adb
alternative (`input swipe`) is left for a later milestone.

### The keep rule that saved the release build (a genuinely nasty one)

The finished feature worked perfectly in the debug APK and did **nothing** in the release APK -
no crash, no error, just a service that reported success and moved nothing. The reason is worth
internalising:

```
R8 cannot see Android.                                    release APK, before the keep rule:
  ...so nothing instantiates the AccessibilityService.     InjectionAccessibilityService:
  ...so its onServiceConnected() looks like dead code.       static instance()   -> always null
  ...so `instance` is provably null at every read.           onServiceConnected  -> DELETED
  ...so every reader, and dispatchGesture() behind it,       dispatchGesture     -> DELETED
     is provably dead and gets deleted.                     displaySize         -> DELETED
```

Every step is locally correct and the *build still succeeds*, which is why this class of bug is
so expensive: the compiler, `apksigner` and a structurally-valid dex all tell you the APK is
fine. The fix is two keep rules in `app/proguard.pro`:

```proguard
-keep class damjay.control.ghosthand.host.InjectionAccessibilityService { *; }
-keep class damjay.control.ghosthand.host.InjectionAccessibilityService$* { *; }
```

The second line matters too: `GestureResultCallback` is an inner class the framework calls back
into, and a keep rule on the outer class does not cover it.

**And then the lesson was turned into a test.** Trusting a keep rule is how this happened in the
first place, so `verify_apk.py` checks the *artifact* on every build - `build.sh` runs it, and
`publish-apk.sh` refuses to publish without it:

```bash
python3 verify_apk.py app/build/app.apk
# ok  APK VERIFY PASSED  (15 classes, 14 call strings, 5 resources present)
```

It asserts three things that a compiler cannot: the framework-facing **classes** are present
under their original names (the manifest looks them up by string), the **call strings** the app
depends on are still in the dex (`dispatchGesture`, `onServiceConnected`, `displaySize`, ...),
and the **resources** survived linking (`canPerformGestures` in the compiled XML, the service
entry in the binary manifest). Deliberately deleting the two keep rules above makes it fail
with exactly the three methods R8 removed - the regression is reproducible and now caught
before anything is signed or shipped.

---

## Tests

`net/` and `host/TouchInjector` deliberately import nothing from `android.*`, so the wire
format **and the gesture planner** can be tested on a plain JVM - no device, no emulator:

```bash
bash toolchain/test.sh app --source 8
# JUnit version 4.13.2
# .......................................................
# OK (63 tests)
```

What is covered:

* `FrameCodecTest` - round-trip of every header field, negative and large PTS, empty payloads,
  **a frame delivered one byte at a time**, three frames coalesced into a single read, a frame
  split inside the header, foreign bytes on the port, an unsupported version, an absurd payload
  length, truncated input, `reset()`.
* `RecordTest` - every value type, nested records, missing keys, wrong-type lookups, full `long`
  range, **byte arrays with embedded zeros and 0xFF** (SPS/PPS are full of both), UTF-8 beyond
  ASCII, truncated blobs.
* `GhostProtocolTest` - sizing maths (16-alignment for any screen/preset combination, aspect
  preservation, no upscaling, encoder minimums), bitrate clamp, and that header size, port,
  service type and type numbers stay sane and distinct.
* `TouchInjectorTest` (23 tests) - tap and drag planning, the slop rules (a sub-12 px wobble
  during a press must **not** move the tap or shorten its duration - a real bug this caught),
  the 40 ms/30 s duration clamps, monotonic non-decreasing offsets, point reduction past 64,
  a MOVE arriving with no DOWN, `reset()` producing a CANCEL, coordinate clamping at the
  screen edges, and a full scroll round trip from one normalised `MOVE` to one stroke.

**What cannot be tested here:** anything that needs the Android runtime (ART) - and that now
includes `dispatchGesture` itself. There is no emulator in this environment, so
`MediaProjection`, `MediaCodec`, `dispatchGesture`, the activities and the AndroidX widgets are
verified by compilation, dexing (D8 and R8 both accept the bytecode, R8 with the keep rules
above), and structurally: `apktool` decodes the APK back to smali and resources, `verify_apk.py`
asserts the injection classes and call strings survived shrinking, and every
`findViewById(R.id.x)` is cross-checked against the layouts. On-device behaviour still needs two
phones.

---

## Troubleshooting

| symptom | cause |
|---|---|
| Guest list is empty | mDNS blocked (AP isolation) - type the IP shown on the host instead. Both phones must be on the *same* network, and "guest WiFi" networks usually isolate clients from each other. |
| "that does not look like an IP address" | the guest needs `192.168.x.x`, not a hostname. |
| Black screen in a secure app | expected: `AUTO_MIRROR` is distrusted by the system for banking/DRM windows. Switch the host to `PUBLIC` mode. |
| Picture freezes and then recovers | the decoder dropped a frame and is waiting for the next key frame (2 s). `dropped` in the guest's stats tells you it happened. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | you are installing an APK signed with a different key. Every build here uses `keystore/damjay_debug.keystore`; delete the old app once and carry on. |
| Notification missing on Android 13+ | `POST_NOTIFICATIONS` was denied. The stream still works; only the notification is hidden. |
| `LambdaMetafactory cannot be resolved` | `toolchain/vendor/core-lambda-stubs.jar` is missing - re-run `bash toolchain/setup.sh`. |
| `no AARs found` / AndroidX errors | re-run `bash toolchain/androidx.sh` (it needs GitHub reachable for the ~11 MB blobless clone). |
| Dragging on the guest does nothing, host log shows touches arriving | the accessibility service is off. Host dashboard > **Touch control** > *Open settings* > enable "GhostHand touch control". |
| Touch worked in the debug APK, not the release one | R8 deleted the injection path - the `InjectionAccessibilityService` keep pair is missing from `app/proguard.pro`. `python3 verify_apk.py` catches this in one second. |
| R8 fails with an `InnerClasses`/`EnclosingMethod` message | `app/proguard.pro` lost that pair from `-keepattributes`; they must stay together. |
| Release build crashes on launch with `ClassNotFoundException` | a manifest component was shrunk or renamed: check the `-keep` lines at the top of `app/proguard.pro`. |

---

## Repository layout

```
app/                        the Android application
  src/main/AndroidManifest.xml
  src/main/java/...         4 packages: net, host, guest + 3 activities
  src/main/res/             layouts (Material 3), drawables, values + values-night
  src/main/res/xml/         accessibility_service_config.xml (canPerformGestures=true)
  src/test/java/...         JUnit tests for the wire protocol + the gesture planner
  proguard.pro              keep rules for the R8 release build (no AGP to generate them)
toolchain/                  the Android build system (no SDK, no Gradle) - see its README
keystore/                   the stable debug key every build is signed with
gradle.properties           signing credentials (single source of truth)
build.sh                    one command: setup -> AndroidX -> tests -> signed APK -> verify it
verify_apk.py               assert R8 kept the classes/calls/resources the app needs
publish-apk.sh              push the APK to the single-commit `apk` branch
RECIPE.md                   how the SDK-less toolchain was assembled, and what fails
sample/                     toy framework-only project used as a toolchain smoke test
sample-androidx/            toy AndroidX project: AppCompat + Material 3 + RecyclerView
```

## Roadmap

1. **Mirroring** - done.
2. **Touch injection** - done: single-finger taps and drags through
   `AccessibilityService.dispatchGesture()`. A drag is dispatched on lift (see
   [Touch injection](#touch-injection)); live streaming of a drag needs a root or adb helper.
3. **Multi-touch, pressure and hardware keys** - the `TOUCH` record already reserves a
   `pointer` field, and `GestureDescription` supports several strokes at once, so pinch/zoom is
   a `TouchInjector` change plus a guest-side pointer-id map.
4. **Audio** - `AudioPlaybackCapture` on the host, `AudioTrack` on the guest, same `ClientHub`
   fan-out.
5. **Hardening** - the protocol is plaintext and unauthenticated on a trusted LAN; a pairing
   step and TLS would be the next step before this is used anywhere else. Touch injection
   raises the stakes: an unauthenticated guest can now drive the host.
