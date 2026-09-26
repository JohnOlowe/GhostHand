package damjay.control.ghosthand.guest;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import damjay.control.ghosthand.net.Frame;
import damjay.control.ghosthand.net.FrameCodec;
import damjay.control.ghosthand.net.GhostProtocol;
import damjay.control.ghosthand.net.Record;

/**
 * Guest side networking: find hosts, connect to one, and turn the byte stream into
 * callbacks the activity can react to.
 *
 * <p><b>Discovery.</b> {@link NsdManager#discoverServices} listens for mDNS
 * adverts of type {@code _ghosthand._tcp.}. Discovery only yields a service
 * <em>name</em>; you must then {@code resolveService} it to learn the host and
 * port. Resolution is asynchronous and rate-limited by the platform, so we resolve
 * one service at a time from a small work queue - firing off ten concurrent
 * resolves is the classic way to make NSD silently fail.
 *
 * <p><b>Connection.</b> One background thread owns the socket:
 * <pre>
 *   connect(host, port)
 *     -> new Socket(...)                     (5 s timeout)
 *     -> send HELLO
 *     -> readFully() loop:
 *          WELCOME      -> onConnected
 *          VIDEO_CONFIG -> onVideoConfig (activity starts the decoder)
 *          VIDEO        -> onVideoData   (straight into VideoDecoder, no main thread)
 *          GEOMETRY     -> onGeometry
 *          STATS        -> onStats
 *          PONG         -> latency sample
 * </pre>
 *
 * <p><b>Threading contract.</b> Everything except {@link Listener#onVideoData} is
 * delivered on the main thread, so the activity can touch views directly. Video
 * frames arrive on the reader thread on purpose: hopping to the main thread 30
 * times a second would add latency and jank, and {@link VideoDecoder} has its own
 * queue and thread anyway.
 */
public class GuestController {

    private static final String TAG = "GhostHand/GuestCtl";

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 1_000; // so the watchdog can run
    private static final long PING_INTERVAL_MS = 3_000;
    private static final long READ_STALL_LIMIT_MS = 20_000;

    /** One discovered host, as shown in the list. */
    public static final class DiscoveredHost {
        public final String name;
        public final String host;
        public final int port;

        DiscoveredHost(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }

        public String key() {
            return host + ":" + port;
        }

        @Override
        public String toString() {
            return name + " (" + host + ":" + port + ")";
        }
    }

    public interface Listener {
        /** mDNS found or lost a host. Main thread. */
        void onHostsChanged(List<DiscoveredHost> hosts);

        void onDiscoveryError(String message);

        /** TCP + HELLO/WELCOME succeeded. Main thread. */
        void onConnected(String hostName, int width, int height);

        /** SPS/PPS arrived: the decoder can be created. Main thread. */
        void onVideoConfig(byte[] sps, byte[] pps, int width, int height);

        /**
         * One encoded frame. <b>Reader thread</b> - hand it straight to
         * {@link VideoDecoder#offerVideo} and do not touch views here.
         */
        void onVideoData(byte[] data, int length, boolean key, long ptsUs);

        /** Capture size/rotation changed. Main thread. */
        void onGeometry(int width, int height, int rotation);

        /** Host telemetry. Main thread. */
        void onStats(Record stats);

        /** Round-trip time of the last PING/PONG pair. Main thread. */
        void onLatency(long millis);

        void onDisconnected(String reason);

        /**
         * The host asked for our clipboard (its "From guest" button). Read the local
         * clipboard and answer with {@link GuestController#sendClipboard}. Main thread.
         */
        void onClipboardRequest();

        /**
         * Clipboard text arrived from the host - either the answer to a pull or a
         * push the host initiated. {@code ok = false} means the host could not read
         * ITS clipboard (Android 10+ blocks background reads); the local clipboard
         * must not be touched in that case. Main thread.
         */
        void onPeerClipboard(String text, boolean ok);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    // discovery
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private boolean discovering;
    private final Map<String, DiscoveredHost> hosts = new LinkedHashMap<>();
    private final List<NsdServiceInfo> resolveQueue = new ArrayList<>();
    private boolean resolving;

    // connection
    private Socket socket;
    private Thread readerThread;
    private volatile boolean connected;
    private volatile long lastDataAtMs;
    private volatile long pingToken;
    private volatile long pingSentAtMs;
    private String connectedHostName = "";
    private volatile int streamWidth;
    private volatile int streamHeight;
    private volatile long lastPingAtMs;

    /** Counts received video frames so the UI can show a real fps number.
     *  Written on the reader thread, read on the main thread - hence volatile. */
    private int fpsCounter;
    private long fpsWindowStartMs;
    private volatile int currentFps;

    public GuestController(Listener listener) {
        this.listener = listener;
    }

    // =============================== discovery ===============================

    public void startDiscovery(Context context) {
        if (discovering) {
            return;
        }
        Object service = context.getSystemService(Context.NSD_SERVICE);
        if (!(service instanceof NsdManager)) {
            postDiscoveryError("this device has no NSD/mDNS support - enter the IP manually");
            return;
        }
        nsdManager = (NsdManager) service;

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.i(TAG, "discovery started for " + serviceType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                Log.i(TAG, "found service " + info.getServiceName());
                enqueueResolve(info);
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
                removeHostByName(info.getServiceName());
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                discovering = false;
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                discovering = false;
                postDiscoveryError("discovery could not start (errorCode=" + errorCode + ")");
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "stopDiscoveryFailed errorCode=" + errorCode);
            }
        };

        try {
            nsdManager.discoverServices(GhostProtocol.NSD_SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            discovering = true;
        } catch (RuntimeException e) {
            discovering = false;
            postDiscoveryError("discovery failed: " + e.getMessage());
        }
    }

    public void stopDiscovery() {
        if (!discovering || nsdManager == null || discoveryListener == null) {
            return;
        }
        try {
            nsdManager.stopServiceDiscovery(discoveryListener);
        } catch (RuntimeException e) {
            Log.w(TAG, "stopServiceDiscovery: " + e.getMessage());
        }
        discovering = false;
        synchronized (resolveQueue) {
            resolveQueue.clear();
        }
    }

    public boolean isDiscovering() {
        return discovering;
    }

    /** Resolves one service at a time; NSD hates concurrent resolves. */
    private void enqueueResolve(NsdServiceInfo info) {
        synchronized (resolveQueue) {
            for (NsdServiceInfo queued : resolveQueue) {
                if (queued.getServiceName().equals(info.getServiceName())) {
                    return;
                }
            }
            resolveQueue.add(info);
        }
        pumpResolveQueue();
    }

    private void pumpResolveQueue() {
        synchronized (resolveQueue) {
            if (resolving || resolveQueue.isEmpty() || nsdManager == null) {
                return;
            }
            resolving = true;
        }
        NsdServiceInfo info;
        synchronized (resolveQueue) {
            info = resolveQueue.remove(0);
        }
        final String name = info.getServiceName();
        try {
            nsdManager.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.w(TAG, "resolve failed for " + name + " errorCode=" + errorCode);
                    finishResolve();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    // getHost() is the whole point of resolving: discovery alone
                    // only gives us a name, not a routable address.
                    java.net.InetAddress address = serviceInfo.getHost();
                    if (address != null) {
                        addHost(new DiscoveredHost(name, address.getHostAddress(),
                                serviceInfo.getPort()));
                    } else {
                        Log.w(TAG, "resolved " + name + " but got no address");
                    }
                    finishResolve();
                }
            });
        } catch (RuntimeException e) {
            Log.w(TAG, "resolveService threw: " + e.getMessage());
            finishResolve();
        }
    }

    private void finishResolve() {
        synchronized (resolveQueue) {
            resolving = false;
        }
        pumpResolveQueue();
    }

    private void addHost(DiscoveredHost host) {
        final List<DiscoveredHost> snapshot;
        synchronized (hosts) {
            hosts.put(host.key(), host);
            snapshot = new ArrayList<>(hosts.values());
        }
        main.post(() -> listener.onHostsChanged(snapshot));
    }

    private void removeHostByName(String name) {
        final List<DiscoveredHost> snapshot;
        synchronized (hosts) {
            String doomed = null;
            for (Map.Entry<String, DiscoveredHost> e : hosts.entrySet()) {
                if (e.getValue().name.equals(name)) {
                    doomed = e.getKey();
                    break;
                }
            }
            if (doomed != null) {
                hosts.remove(doomed);
            }
            snapshot = new ArrayList<>(hosts.values());
        }
        main.post(() -> listener.onHostsChanged(snapshot));
    }

    public void clearHosts() {
        synchronized (hosts) {
            hosts.clear();
        }
        main.post(() -> listener.onHostsChanged(new ArrayList<>()));
    }

    private void postDiscoveryError(final String message) {
        main.post(() -> listener.onDiscoveryError(message));
    }

    // =============================== connection ==============================

    /**
     * Dials a host. Returns immediately; the result comes back through
     * {@link Listener#onConnected} or {@link Listener#onDisconnected}.
     */
    public void connect(final String host, final int port, final String deviceName) {
        if (readerThread != null && readerThread.isAlive()) {
            disconnect("reconnecting");
        }
        connected = true;
        lastDataAtMs = SystemClock.elapsedRealtime();
        readerThread = new Thread(() -> connectionLoop(host, port, deviceName), "ghosthand-guest");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void connectionLoop(String host, int port, String deviceName) {
        Socket s = null;
        try {
            s = new Socket();
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            // Receive buffer big enough for a couple of video frames; the socket
            // should never be the thing that runs dry.
            s.setReceiveBufferSize(512 * 1024);
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            // A short read timeout lets the loop below run a watchdog even when the
            // host sends nothing (screen completely static, or peer vanished).
            s.setSoTimeout(READ_TIMEOUT_MS);
            socket = s;

            InputStream in = s.getInputStream();

            Record hello = Record.create()
                    .putString("device", deviceName)
                    .putString("version", "1")
                    .putInt("protocol", GhostProtocol.VERSION);
            FrameCodec.write(s.getOutputStream(),
                    new Frame(GhostProtocol.TYPE_HELLO, (byte) 0, 0L, hello.toBytes()));

            Log.i(TAG, "connected to " + host + ":" + port);

            while (connected && !Thread.currentThread().isInterrupted()) {
                Frame frame;
                try {
                    frame = FrameCodec.readFully(in);
                } catch (java.net.SocketTimeoutException timeout) {
                    watchdog();
                    continue;
                }
                lastDataAtMs = SystemClock.elapsedRealtime();
                handleFrame(frame);
            }
        } catch (EOFException e) {
            reportDisconnected("host closed the connection");
        } catch (FrameCodec.ProtocolException e) {
            reportDisconnected("protocol error: " + e.getMessage());
        } catch (IOException e) {
            if (connected) {
                reportDisconnected("connection failed: " + e.getMessage());
            }
        } finally {
            closeSocketQuietly(s);
            if (connected) {
                connected = false;
            }
        }
    }

    private void handleFrame(Frame frame) {
        switch (frame.type) {
            case GhostProtocol.TYPE_WELCOME: {
                Record w = frame.asRecord();
                connectedHostName = w.getString("device", "?");
                streamWidth = (int) w.getInt("w", 0);
                streamHeight = (int) w.getInt("h", 0);
                final int width = streamWidth;
                final int height = streamHeight;
                final String name = connectedHostName;
                main.post(() -> listener.onConnected(name, width, height));
                break;
            }
            case GhostProtocol.TYPE_VIDEO_CONFIG: {
                Record cfg = frame.asRecord();
                final byte[] sps = cfg.getBytes("csd0");
                final byte[] pps = cfg.getBytes("csd1");
                final int width = streamWidth;
                final int height = streamHeight;
                if (sps.length == 0) {
                    reportDisconnected("host sent an empty decoder config");
                    return;
                }
                main.post(() -> listener.onVideoConfig(sps, pps, width, height));
                break;
            }
            case GhostProtocol.TYPE_VIDEO:
                // Deliberately NOT posted to the main thread - see class docs.
                listener.onVideoData(frame.payload, frame.payload.length,
                        frame.isKeyframe(), frame.ptsUs);
                countFrame();
                break;
            case GhostProtocol.TYPE_CLIPBOARD_GET:
                // The host wants our clipboard. Protocol code never touches the
                // system clipboard itself - the activity reads it and answers via
                // sendClipboard(), on the thread that owns UI services.
                main.post(listener::onClipboardRequest);
                break;
            case GhostProtocol.TYPE_CLIPBOARD_SET: {
                Record c = frame.asRecord();
                final String text = c.getString("text", "");
                final boolean ok = c.getInt("ok", 0) != 0;
                main.post(() -> listener.onPeerClipboard(text, ok));
                break;
            }
            case GhostProtocol.TYPE_GEOMETRY: {
                Record g = frame.asRecord();
                streamWidth = (int) g.getInt("w", streamWidth);
                streamHeight = (int) g.getInt("h", streamHeight);
                final int w = streamWidth;
                final int h = streamHeight;
                final int r = (int) g.getInt("rotation", 0);
                main.post(() -> listener.onGeometry(w, h, r));
                break;
            }
            case GhostProtocol.TYPE_STATS:
                final Record stats = frame.asRecord();
                main.post(() -> listener.onStats(stats));
                break;
            case GhostProtocol.TYPE_PONG: {
                long token = frame.payload.length >= 8 ? readLong(frame.payload, 0) : 0L;
                if (token == pingToken && pingSentAtMs > 0) {
                    final long rtt = SystemClock.elapsedRealtime() - pingSentAtMs;
                    pingSentAtMs = 0;
                    main.post(() -> listener.onLatency(rtt));
                }
                break;
            }
            case GhostProtocol.TYPE_BYE:
                reportDisconnected("host said goodbye: " + frame.asText());
                break;
            default:
                Log.i(TAG, "ignoring " + frame);
                break;
        }
    }

    /**
     * Runs on every read timeout: sends a PING (which also keeps NAT/WiFi power
     * saving from forgetting the flow) and gives up if the host has gone quiet for
     * too long.
     */
    private void watchdog() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastDataAtMs > READ_STALL_LIMIT_MS) {
            reportDisconnected("host stopped responding");
            return;
        }
        if (pingSentAtMs == 0 && now - lastPingAtMs >= PING_INTERVAL_MS) {
            lastPingAtMs = now;
            pingToken = now;
            pingSentAtMs = now;
            byte[] token = new byte[8];
            putLong(token, 0, pingToken);
            sendFrame(new Frame(GhostProtocol.TYPE_PING, (byte) 0, now, token));
        }
    }

    /** A tap on the mirrored image. Coordinates are normalised to 0..10000. */
    public void sendTouch(int action, float normalizedX, float normalizedY, long timeMs) {
        Record r = Record.create()
                .putInt("action", action)
                .putInt("xN", (long) (normalizedX * 10000f))
                .putInt("yN", (long) (normalizedY * 10000f))
                .putInt("timeMs", timeMs);
        sendFrame(new Frame(GhostProtocol.TYPE_TOUCH, (byte) 0, timeMs * 1000L, r.toBytes()));
    }

    /**
     * Asks the host to perform a system navigation action (back / home / recents /
     * notification shade). The guest sends the *command*, never a synthetic swipe:
     * edge gestures cannot survive letterboxing (the video rarely reaches the host
     * screen's true edge), and performGlobalAction() on the host is exact.
     */
    public void sendGlobalAction(int action) {
        Record r = Record.create().putInt("action", action);
        sendFrame(new Frame(GhostProtocol.TYPE_GLOBAL_ACTION, (byte) 0, 0L, r.toBytes()));
    }

    /**
     * Pull: ask the host for its clipboard. The host replies with one
     * {@link GhostProtocol#TYPE_CLIPBOARD_SET} frame, which lands in
     * {@link Listener#onPeerClipboard}.
     */
    public void requestClipboard() {
        sendFrame(new Frame(GhostProtocol.TYPE_CLIPBOARD_GET, (byte) 0, 0L, new byte[0]));
    }

    /**
     * Push: hand the peer text to copy. Used both for "send mine to the host" and
     * for answering the host's request. Empty text with {@code ok = false} means
     * "I could not read my clipboard" and never overwrites the peer's copy.
     */
    public void sendClipboard(String text, boolean ok) {
        Record r = Record.create()
                .putString("text", ok ? GhostProtocol.clipText(text) : "")
                .putInt("ok", ok ? 1 : 0);
        sendFrame(new Frame(GhostProtocol.TYPE_CLIPBOARD_SET, (byte) 0, 0L, r.toBytes()));
    }

    private void sendFrame(Frame frame) {
        Socket s = socket;
        if (s == null || !connected) {
            return;
        }
        try {
            // Synchronised because watchdog (reader thread) and sendTouch (main
            // thread) can both write; two interleaved headers would corrupt the stream.
            synchronized (s) {
                FrameCodec.write(s.getOutputStream(), frame);
            }
        } catch (IOException e) {
            Log.w(TAG, "send failed: " + e.getMessage());
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public String getConnectedHostName() {
        return connectedHostName;
    }

    public int getStreamWidth() {
        return streamWidth;
    }

    public int getStreamHeight() {
        return streamHeight;
    }

    public int getCurrentFps() {
        return currentFps;
    }

    private void countFrame() {
        long now = SystemClock.elapsedRealtime();
        if (fpsWindowStartMs == 0) {
            fpsWindowStartMs = now;
        }
        fpsCounter++;
        long elapsed = now - fpsWindowStartMs;
        if (elapsed >= 1000) {
            currentFps = (int) (fpsCounter * 1000L / elapsed);
            fpsCounter = 0;
            fpsWindowStartMs = now;
        }
    }

    /** Stops everything: reader thread, socket, discovery. */
    public void disconnect(String reason) {
        connected = false;
        Thread t = readerThread;
        readerThread = null;
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                byte[] payload = reason.getBytes(StandardCharsets.UTF_8);
                FrameCodec.write(s.getOutputStream(),
                        new Frame(GhostProtocol.TYPE_BYE, (byte) 0, 0L, payload));
            } catch (IOException ignored) {
                // the peer is probably already gone
            }
            closeSocketQuietly(s);
        }
        if (t != null) {
            t.interrupt();
        }
        stopDiscovery();
    }

    private void closeSocketQuietly(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    private void reportDisconnected(final String reason) {
        connected = false;
        main.post(() -> listener.onDisconnected(reason));
    }

    private static void putLong(byte[] d, int off, long v) {
        for (int i = 0; i < 8; i++) {
            d[off + i] = (byte) (v >>> (56 - 8 * i));
        }
    }

    private static long readLong(byte[] d, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (d[off + i] & 0xFFL);
        }
        return v;
    }

    /** For logs and the status line. */
    public String describeStream() {
        return String.format(Locale.US, "%dx%d", streamWidth, streamHeight);
    }
}
