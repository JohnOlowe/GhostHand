package damjay.control.ghosthand.net;

/**
 * The GhostHand wire format: one length-prefixed binary header in front of every
 * message, so a single TCP socket can carry video, stats, pings and (later) touch
 * events without the two phones ever getting out of step.
 *
 * <pre>
 *  byte  0        1         2        3        4..11                12..15
 *  +--------+---------+--------+--------+-------------------+----------------+
 *  | MAGIC  | VERSION |  TYPE  | FLAGS  | PRESENTATION TIME | PAYLOAD LENGTH |
 *  |  0x47  |  0x01   |        |        |   (int64, us)     |   (int32)      |
 *  +--------+---------+--------+--------+-------------------+----------------+
 *  |                       PAYLOAD (0..n bytes)                             |
 *  +------------------------------------------------------------------------+
 * </pre>
 *
 * Everything is big-endian ("network byte order"), which is what
 * {@link java.io.DataInputStream} and {@link java.nio.ByteBuffer} do by default.
 *
 * <p>The presentation timestamp is 8 bytes because MediaCodec reports
 * {@code long} microseconds straight off the encoder; truncating it to 4 bytes
 * would wrap after ~71 minutes of streaming.
 *
 * <p>This class deliberately imports nothing from android.* so it can run in a
 * plain JVM unit test (see app/src/test).
 */
public final class GhostProtocol {

    /** 'G' - a cheap sanity check that we are not reading somebody else's stream. */
    public static final byte MAGIC = 0x47;

    /** Bumped whenever the header or a payload layout changes incompatibly. */
    public static final byte VERSION = 1;

    /**
     * Header size in bytes: MAGIC + VERSION + TYPE + FLAGS (4 bytes),
     * presentation timestamp (int64), payload length (int32) = 16.
     */
    public static final int HEADER_SIZE = 16;

    /**
     * Refuse absurd lengths before allocating. 8 MiB covers any sane H.264 access
     * unit at phone resolutions; a corrupt/hostile length would otherwise make us
     * try to allocate gigabytes and OOM the guest.
     */
    public static final int MAX_PAYLOAD = 8 * 1024 * 1024;

    /** Default TCP port the host listens on. */
    public static final int DEFAULT_PORT = 47811;

    /** mDNS/NSD service type used for automatic discovery on the LAN. */
    public static final String NSD_SERVICE_TYPE = "_ghosthand._tcp.";

    // ------------------------------- frame types ----------------------------
    // guest -> host
    /** First frame a guest sends. Payload: {@link Record} (appName, version, w, h). */
    public static final byte TYPE_HELLO = 1;
    /** A touch event. Payload: {@link Record} (action, x, y, pointer, pressure, timeMs). */
    public static final byte TYPE_TOUCH = 2;
    /** Latency probe. Payload: 8-byte token echoed back in PONG. */
    public static final byte TYPE_PING = 3;
    // host -> guest
    /** Answer to HELLO. Payload: {@link Record} (device, w, h, fps, version, mode). */
    public static final byte TYPE_WELCOME = 4;
    /** Latency answer. Payload: same token as the PING. */
    public static final byte TYPE_PONG = 5;
    // either direction
    /**
     * H.264 decoder configuration: the SPS (csd-0) and PPS (csd-1) NAL units the
     * encoder emits once at start and again on every key frame request. Payload:
     * {@link Record} (w, h, "csd0", "csd1") - no start codes, raw NAL bytes.
     */
    public static final byte TYPE_VIDEO_CONFIG = 6;
    /** One encoded H.264 access unit. flags bit 0 = key frame, ptsUs in the header. */
    public static final byte TYPE_VIDEO = 7;
    /** Capture geometry changed (rotation/resize). Payload: {@link Record} (w, h, rotation). */
    public static final byte TYPE_GEOMETRY = 8;
    /** Host telemetry. Payload: {@link Record} (fps, kbps, dropped, clients, uptimeMs). */
    public static final byte TYPE_STATS = 9;
    /** Polite shutdown. Payload: optional UTF-8 reason. */
    public static final byte TYPE_BYE = 10;
    /** Guest -> host: perform a system navigation action. Record{action}, see GLOBAL_*. */
    public static final byte TYPE_GLOBAL_ACTION = 11;

    // Wire codes for TYPE_GLOBAL_ACTION. They deliberately equal
    // AccessibilityService.GLOBAL_ACTION_* on every platform this app hosts on
    // (BACK/HOME/RECENTS exist since API 16, NOTIFICATIONS since 18; the host floor
    // is 21), so the host forwards the number to performGlobalAction() untouched.
    /** "Back" - what the guest's Back button sends. */
    public static final int GLOBAL_BACK = 1;
    /** "Home" - jump to the host's launcher. */
    public static final int GLOBAL_HOME = 2;
    /** The recents/overview screen. */
    public static final int GLOBAL_RECENTS = 3;
    /** The notification shade - the "swipe down from the top" gesture as a button. */
    public static final int GLOBAL_NOTIFICATIONS = 4;

    /** flags bit 0: this VIDEO frame is an IDR (a guest can start decoding here). */
    public static final byte FLAG_KEYFRAME = 1;

    private GhostProtocol() {
        // constants only
    }

    /** Human-readable type name, for logs. */
    public static String typeName(byte type) {
        switch (type) {
            case TYPE_HELLO: return "HELLO";
            case TYPE_TOUCH: return "TOUCH";
            case TYPE_PING: return "PING";
            case TYPE_WELCOME: return "WELCOME";
            case TYPE_PONG: return "PONG";
            case TYPE_VIDEO_CONFIG: return "VIDEO_CONFIG";
            case TYPE_VIDEO: return "VIDEO";
            case TYPE_GEOMETRY: return "GEOMETRY";
            case TYPE_STATS: return "STATS";
            case TYPE_BYE: return "BYE";
            case TYPE_GLOBAL_ACTION: return "GLOBAL_ACTION";
            default: return "UNKNOWN(" + type + ")";
        }
    }

    /**
     * Scale a requested capture resolution so its longest side is at most
     * {@code maxLongestSide} and both sides are multiples of 16 (video encoders are
     * happiest with 16-aligned macroblock grids; odd sizes get padded and can smear
     * the right/bottom edge).
     *
     * @return int[2] = {width, height}
     */
    public static int[] fitCaptureSize(int screenW, int screenH, int maxLongestSide) {
        int w = Math.max(1, screenW);
        int h = Math.max(1, screenH);
        int longest = Math.max(w, h);
        if (longest > maxLongestSide) {
            float scale = (float) maxLongestSide / (float) longest;
            w = Math.round(w * scale);
            h = Math.round(h * scale);
        }
        w = Math.max(64, (w / 16) * 16);
        h = Math.max(64, (h / 16) * 16);
        return new int[] { w, h };
    }

    /**
     * Bits-per-second budget for a capture size. ~0.10 bit per pixel per frame is a
     * comfortable H.264 CBR point for UI content: 720x1280@30 lands near 2.8 Mbps,
     * which any decent 2.4/5 GHz WiFi link carries with room to spare.
     */
    public static int suggestedBitrate(int width, int height, int fps) {
        long bps = (long) width * (long) height * (long) fps / 10L;
        return (int) Math.min(Math.max(bps, 800_000L), 16_000_000L);
    }
}
