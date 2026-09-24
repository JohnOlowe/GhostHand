# GhostHand

**One phone becomes the screen. The other becomes the hands.**

GhostHand streams one Android phone's screen to another over WiFi - no cables, no
cloud, no accounts. Two phones on the same network, one APK, two roles:

| | |
|---|---|
| **Host** | Captures its own screen (`MediaProjection`), encodes it to H.264 (`MediaCodec`) and serves it over TCP. |
| **Guest** | Finds the host with mDNS (or an IP you type), decodes the H.264 and draws it full screen. |

**Milestone 1 (this build): screen mirroring.** Touch passthrough is designed into
the protocol already - the guest sends real touch events and the host logs them -
but injecting them into another app is milestone 2 (see
[Touch, and why it is not done yet](#touch-and-why-it-is-not-done-yet)).

Package `damjay.control.ghosthand` · minSdk 26 (Android 8.0) · targetSdk 34 · Java, no Kotlin.

---

## Quick start

```bash
git clone https://github.com/JohnOlowe/GhostHand.git
cd GhostHand
bash build.sh                 # ~20 s from a fresh clone, no SDK, no Gradle, no Maven
```

`build.sh` installs the toolchain into `toolchain/vendor` (JRE, Eclipse compiler,
aapt2, D8, apksigner - about 170 MB, from PyPI/npm/GitHub), runs the unit tests and
produces a **signed, zip-aligned APK** at `app/build/app.apk`.

```bash
adb install -r app/build/app.apk     # on BOTH phones
```

Then: open GhostHand on phone A -> **Host** -> *Start mirroring* -> allow screen
capture. On phone B -> **Guest** -> tap the host in the list (or type the IP shown
on phone A) -> watch.

---

## Where the APK lives (and why it is not in `main`)

The APK is ~115 KB and it is different on every build. Committing it to `main` would
add a new (non-delta-compressible) blob to history on every push, so `git clone`
would eventually drag along every APK ever built.

So the APK lives on its own branch, and **each publish replaces that branch with a
single parentless commit**:

```
 apk:  *                                     <- always exactly 1 commit, 1 APK
 main: *---*---*---*---*---*---*             <- source history, never a binary
```

```bash
# get the current build: one fetch, one file
git fetch origin apk
git show FETCH_HEAD:GhostHand-debug.apk > GhostHand-debug.apk

# publish a new one (force-pushes one orphan commit over the last)
bash publish-apk.sh
```

`publish-apk.sh` builds the commit with git plumbing (`hash-object` -> `mktree` ->
`commit-tree`, pushed with `--force`), so nothing in your working tree is touched and
the branch can never grow a second commit.

Every APK - from any branch, any machine - is signed with the same key
(`keystore/damjay_debug.keystore`, the PalmPay-Clone stable debug key), so a new
build **installs straight over the previous one**. No uninstall, no
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

---

## How to build and test (no Android SDK anywhere)

There is no Gradle wrapper here and no SDK install step, because the sandbox this
was built in cannot reach `dl.google.com` or Maven Central. Instead `toolchain/`
assembles a working Android toolchain from PyPI, npm and GitHub:

| step | tool | from |
|---|---|---|
| run Java | Temurin **JRE** (no `javac`) | PyPI `jdk4py` |
| compile Java | Eclipse **ECJ** batch compiler | npm `@vscjava/java-language-server` |
| compile XML / resources / manifest | **aapt2** | PyPI `aapt2` |
| Android platform API | **android.jar** (API 34) | npm `@drxiaozhi/minapk` |
| bytecode -> dex | **D8** | npm `@drxiaozhi/minapk` |
| sign | **apksigner** | npm `@drxiaozhi/minapk` |
| align | **zipalign** (reimplemented in Python) | `toolchain/zipalign.py` |
| run unit tests | **JUnit 4.13.2** | npm `@vscjava/java-language-server` |

```bash
bash toolchain/setup.sh              # once; verifies every tool by running it
bash toolchain/test.sh  app          # JUnit unit tests for the wire protocol
bash toolchain/build.sh app --verify # full pipeline + independent verification
bash toolchain/check.sh app          # fast "does it compile?" loop
```

`toolchain/README.md` documents each script; `RECIPE.md` explains where the tools
come from and what does not work. This is the supported build path - it runs
identically on a laptop and in CI, and there is no GitHub Actions workflow to wait on.

Two consequences worth knowing:

* **Framework-only code.** AndroidX cannot be downloaded here, so the app uses
  `android.app.Activity`, `findViewById` and `Notification.Builder` directly, and
  themes come from `@android:style/Theme.Material.NoActionBar`. Nothing but the
  platform `android.jar` is needed to compile it.
* **`toolchain/patch_android_jar.sh`.** Stock `android.jar` files ship
  `java.lang.invoke` *without* `LambdaMetafactory`. javac tolerates that; ECJ does
  not. The script adds the missing class to the compile-time bootclasspath (nothing
  from `android.jar` is ever packaged into the APK). `setup.sh` calls it for you.

---

## The map: which file does what

```
app/src/main/java/damjay/control/ghosthand/
├── MainActivity.java            pick a role; the launcher activity
├── HostActivity.java            host dashboard: permission flow + live stats
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
│   └── HostController.java        mDNS advertise + "my IP addresses" listing
│
└── guest/                       everything that runs on the phone doing the watching
    ├── GuestController.java       mDNS discovery + socket + frame dispatch
    └── VideoDecoder.java          MediaCodec H.264 decoder + frame pacing
```

Read it in this order and the whole system falls out: `net/` (the language the two
phones speak) -> `host/` (one side of the conversation) -> `guest/` (the other side)
-> the three activities (the humans).

### How the pieces are wired

```
 HOST PHONE                                          GUEST PHONE
 ───────────                                         ───────────
 HostActivity
   │ startForegroundService(ACTION_START, token)
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
  `startForegroundService(intent)`, state comes back by **package-private broadcast**,
  which is why Home-ing out of `HostActivity` does not interrupt the stream.

---

## The wire protocol

Every message is one 16-byte header plus a payload. Big-endian throughout, which is
exactly what `DataInputStream` and `ByteBuffer` do by default.

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

* **One header for everything.** Video, pings and touches share a socket, so there is
  one connection to lose, one place to detect a dead peer, and no ordering surprises
  between "the picture" and "the finger".
* **`Record` is a hand-rolled TLV bag**, not JSON or Java serialisation: no
  reflection, no class names on the wire, no schema to keep in sync between two APK
  versions. Unknown keys are simply ignored, which is what makes a version skew
  survivable.
* **PTS is 8 bytes** because `MediaCodec` hands out microseconds as a `long`;
  truncating to 4 bytes wraps after ~71 minutes.
* **Framing is strict, not self-resynchronising.** A bad magic byte or a payload
  length over 8 MiB marks the stream broken and drops the connection. Hunting for the
  next `0x47` in an arbitrary byte stream is how you decode half a video frame as a
  touch event.

---

## How the host works

### 1. Permission, then a foreground service

Screen capture is not a normal runtime permission. `MediaProjectionManager
.createScreenCaptureIntent()` shows a system dialog, and the `resultCode` + `Intent`
you get back is a **single-use token for one projection**. `HostActivity` runs the
dialog and forwards the token to the service:

```java
intent.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
intent.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
startForegroundService(intent);
```

The capture has to live in a foreground service for two reasons: Android 10+ kills
background apps that hold a projection, and it has to keep streaming when you press
Home. Order matters on Android 14:

```java
startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
projection = manager.getMediaProjection(resultCode, data);   // only now
```

Calling `getMediaProjection` first throws `SecurityException` on API 34+.

### 2. VirtualDisplay -> encoder

```
screen ──► VirtualDisplay ──(input Surface)──► MediaCodec encoder ──► H.264 NAL units
```

A `VirtualDisplay` is the system's own compositor output: everything on the physical
screen is drawn into a `Surface` instead. Hand that Surface to `MediaCodec` as an
**input** surface and the encoder consumes frames without a single pixel crossing
into Java heap.

```java
encoder = new ScreenEncoder(w, h, bitrate, 30, this);
encoder.start();
projection.createVirtualDisplay("GhostHand", w, h, densityDpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, encoder.getInputSurface(), null, null);
```

* `AUTO_MIRROR` is what makes the system *distrust* secure windows (banking apps draw
  black). The host UI can switch to `PUBLIC` at runtime - that is a property of the
  display, so only the `VirtualDisplay` is recreated; the encoder keeps running.
* Sizing goes through `GhostProtocol.fitCaptureSize(w, h, maxSide)`: scale so the
  longest side matches the chosen preset, then round **down to a multiple of 16**.
  H.264 works in 16x16 macroblocks, and an odd size gets padded and smears the right
  and bottom edges.
* Bitrate comes from `suggestedBitrate(w, h, fps)` - roughly 0.1 bit per pixel per
  frame, clamped to 0.8-16 Mbps. 720p30 lands near 2.8 Mbps, which any decent WiFi
  link carries with room to spare.
* The encoder is configured with `KEY_I_FRAME_INTERVAL = 2` (a key frame every two
  seconds, so a guest that connects mid-stream syncs quickly) and `KEY_LATENCY = 1`
  (no B-frames, no reordering: latency beats compression for interactive use).

### 3. Rotation is a restart, not a resize

Encoders cannot change their output size after `configure()` - the only parameters
that can change live are bitrate, sync-frame requests, suspend and low-latency mode.
So when the host rotates, `onConfigurationChanged` releases the `VirtualDisplay` and
the encoder and builds fresh ones at the new size. The `MediaProjection`, the
`ServerSocket` and every guest socket survive, so the viewer sees a brief pause, then
a new `VIDEO_CONFIG` and a key frame - not a disconnect.

### 4. One queue per guest, and dropping the right thing

`ClientHub` accepts sockets and gives each guest a `ClientConnection` with two
threads: a **reader** (drains `HELLO`/`PING`/`TOUCH`, answers `PING` from the reader
itself so latency is measured without a thread hop) and a **writer** (blocks on a
`BlockingQueue`).

The queue is bounded at 120 frames. When a guest's link is slower than 30 fps, the
writer cannot keep up, and something has to give:

* **`VIDEO` frames are dropped** - in fact the hub drops video to make room rather
  than blocking the capture thread, because a mirroring host must never stall the
  encoder waiting for the slowest viewer.
* **Control frames (`VIDEO_CONFIG`, `GEOMETRY`, `STATS`, `PONG`, `BYE`) are never
  dropped.** Losing a `VIDEO_CONFIG` would leave that guest unable to decode
  anything until the next key frame. Losing a `GEOMETRY` would leave it permanently
  letterboxed wrong.
* The guest has a matching recovery path: when `VideoDecoder` drops a frame it
  "re-arms" a gate and waits for the next IDR before rendering again, so a dropped
  P-frame can never produce a permanently corrupt picture.

`ClientHub.setGeometry(...)` is also how the guest learns the host's rotation - the
`GEOMETRY` frame is re-broadcast to everyone.

---

## How the guest works

### 1. Finding the host

Two ways in, because mDNS does not survive every router (AP isolation, client
isolation, corporate WiFi):

1. **mDNS/NSD.** The host advertises `_ghosthand._tcp.` on port 47811.
   `GuestController` uses `NsdManager.discoverServices`, and resolves hosts through a
   **serial queue** - NSD refuses to run many resolves at once, and a parallel fan-out
   fails with `FAILURE_ALREADY_ACTIVE` on most devices.
2. **Manual IP.** The host screen shows its addresses (`HostController` enumerates
   `NetworkInterface` IPv4 addresses, preferring site-local 192.168.x.x/10.x.x.x).
   Type one in and connect. This is the fallback that always works when both phones
   are on the same subnet.

### 2. Decoding

`VideoDecoder` is a two-thread design: a **decoder thread** that blocks in
`dequeueOutputBuffer` and renders with a manually-pinned PTS, and the reader thread
that feeds input buffers. `MediaCodec` is not thread-safe, so only the decoder thread
touches it while the reader thread only ever calls `offerVideo(...)`, which puts the
frame into a small array-deque and returns.

Two details that decide whether the picture looks right:

* **The output Surface is only valid between `surfaceCreated` and
  `surfaceDestroyed`.** Rendering into a released surface throws, so the decoder is
  started and stopped by those callbacks, and it needs *both* a live surface and the
  host's SPS/PPS before it can start at all - hence `startDecoderIfNeeded()` on both
  paths.
* **The decoder's input Surface is the video.** `releaseOutputBuffer(index, true)`
  pushes the decoded frame straight to the display; the guest never sees a Bitmap.

`GuestActivity` then letterboxes the picture: it sizes `videoContainer` to the
stream's aspect ratio inside the window and matches the device orientation to the
stream's, so a portrait phone mirrored onto a portrait phone fills the screen exactly.

### 3. Latency and liveness

Every 3 seconds the guest sends a `PING` (the host echoes it as `PONG` with the same
token), and the round-trip time is shown in the UI. The socket is set to a 1-second
read timeout so the reader thread wakes up regularly: if **20 seconds** pass with no
traffic in either direction, the guest declares the link dead and closes it, rather
than showing a frozen last frame forever.

---

## Touch, and why it is not done yet

The guest already sends touch events. Drag on the mirrored picture and it converts
each gesture to **normalised** coordinates:

```java
float nx = event.getX() / (float) view.getWidth();    // 0.0 .. 1.0
controller.sendTouch(0 /*down*/, nx, ny, uptimeMs);   // x10000 on the wire
```

Normalised, not pixels, because the two phones almost never share a resolution and
the letterbox offsets must not leak into the coordinates. `ACTION_MOVE` is throttled
(dropped if the finger moved less than 0.4% of the view, or less than 16 ms has
passed): 60 Hz of drag events would otherwise compete with the video for a WiFi link
that is already saturated.

**The host receives these today** - watch its log while you drag on the guest and
every event arrives, correctly scaled. What is missing is the last hop: *injecting*
them. An ordinary app cannot write into another app's input stream, so milestone 2
needs one of:

* an `AccessibilityService` with `dispatchGesture()` (no root, but requires the user
  to enable the service in Settings and cannot do everything a shell can), or
* a shell/root helper running `input tap`/`input swipe` (full fidelity, needs adb or
  root).

That choice is deliberately left open; the transport is finished either way.

---

## Tests

`net/` deliberately imports nothing from `android.*`, which means the wire format can
be tested on a plain JVM - no device, no emulator, ~20 ms:

```bash
bash toolchain/test.sh app
# JUnit version 4.13.2
# ........................................
# OK (40 tests)
```

What is covered:

* `FrameCodecTest` - round-trip of every header field, negative and large PTS,
  empty payloads, **a frame delivered one byte at a time**, three frames coalesced
  into a single read, a frame split inside the header, foreign bytes on the port,
  an unsupported version, an absurd payload length, truncated input, `reset()`.
* `RecordTest` - every value type, nested records, missing keys, wrong-type lookups,
  full `long` range, **byte arrays with embedded zeros and 0xFF** (SPS/PPS are full of
  both), UTF-8 beyond ASCII, truncated blobs.
* `GhostProtocolTest` - sizing maths (16-alignment for any screen/preset
  combination, aspect preservation, no upscaling, encoder minimums), bitrate clamp,
  and that header size, port, service type and type numbers stay sane and distinct.

**What cannot be tested here:** anything that needs the Android runtime (ART). There
is no emulator in this environment, so `MediaProjection`, `MediaCodec` and the
activities are verified by compilation, dexing (`D8` accepts the bytecode) and
structurally - the APK is decoded back to smali and resources by `apktool` during
`--verify`, and every `findViewById(R.id.x)` is cross-checked against the layouts.
On-device behaviour still needs two phones.

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
| `toolchain/` complains about `LambdaMetafactory` | run `bash toolchain/patch_android_jar.sh`, or just re-run `bash toolchain/setup.sh`. |

---

## Repository layout

```
app/                        the Android application
  src/main/AndroidManifest.xml
  src/main/java/...         4 packages: net, host, guest + 3 activities
  src/main/res/             layouts, drawables, values (framework theme, no AndroidX)
  src/test/java/...         JUnit tests for the wire protocol
toolchain/                  the Android build system (no SDK, no Gradle) - see its README
keystore/                   the stable debug key every build is signed with
gradle.properties           signing credentials (single source of truth)
build.sh                    one command: setup -> test -> signed APK
publish-apk.sh              push the APK to the single-commit `apk` branch
RECIPE.md                   how the SDK-less toolchain was assembled, and what fails
sample/                     a toy project used by toolchain/check.sh as a smoke test
```

## Roadmap

1. **Mirroring** - done, this build.
2. **Touch injection** - transport done; needs an `AccessibilityService` or a shell
   helper on the host.
3. **Multi-touch and pressure** - the `TOUCH` record already has a `pointer` field.
4. **Audio** - `AudioPlaybackCapture` on the host, `AudioTrack` on the guest, same
   `ClientHub` fan-out.
5. **Hardening** - the protocol is plaintext and unauthenticated on a trusted LAN;
   a pairing step and TLS would be the next step before this is used anywhere else.
