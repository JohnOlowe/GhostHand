package damjay.control.ghosthand.host;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import damjay.control.ghosthand.net.Frame;
import damjay.control.ghosthand.net.FrameCodec;
import damjay.control.ghosthand.net.GhostProtocol;
import damjay.control.ghosthand.net.Record;

/**
 * The host's little TCP server.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>accept guest connections on one port ({@link GhostProtocol#DEFAULT_PORT}),</li>
 *   <li>do the HELLO/WELCOME handshake and immediately ship the current
 *       VIDEO_CONFIG so a new guest can start decoding at once,</li>
 *   <li>fan every encoded frame out to all connected guests.</li>
 * </ol>
 *
 * <p><b>Why one thread per client plus a queue?</b> TCP writes block. If the encoder
 * thread wrote straight to the sockets, a single guest on a weak WiFi signal would
 * stall {@code MediaCodec} (its output buffers would never be released) and freeze
 * the capture for everybody. So each {@link ClientConnection} owns a bounded queue
 * and its own writer thread: a slow guest only hurts itself, and we drop its oldest
 * video frames instead of letting latency pile up.
 *
 * <p>Thread summary on the host:
 * <pre>
 *   main                  UI + startForegroundService
 *   ghosthand-accept      ServerSocket.accept()
 *   ghosthand-handshake-N reads HELLO, writes WELCOME
 *   ghosthand-encoder     MediaCodec dequeue -> hub.broadcast()
 *   ghosthand-client-N    one writer thread per guest
 * </pre>
 */
public class ClientHub implements ClientConnection.Listener {

    private static final String TAG = "GhostHand/Hub";

    public interface Listener {
        /** Always called on the main thread. */
        void onClientCountChanged(int clients);

        void onLog(String message);

        /**
         * A touch the guest sent. Delivered on a socket reader thread (not the main
         * thread) because it is on the latency-critical path: a round trip through
         * the main looper before injection would be visible to the user's finger.
         * The implementation is responsible for getting onto the main thread only
         * where the platform requires it (dispatchGesture does).
         */
        void onGuestTouch(String clientName, int action, int xNormalized, int yNormalized,
                          long timeMs);

        /**
         * A navigation button the guest pressed (back/home/recents/shade). Delivered
         * on the socket reader thread; the implementation hops to whatever thread the
         * platform requires, exactly like {@link #onGuestTouch}.
         */
        void onGuestGlobalAction(String clientName, int action);

        /**
         * The guest asked for the host's clipboard (its "From guest" pull). Reader
         * thread; the implementation replies through {@code from.send(...)}.
         */
        void onGuestClipboardGet(ClientConnection from);

        /** The guest pushed text for the host's clipboard, or answered a request. */
        void onGuestClipboardSet(ClientConnection from, String text, boolean ok);
    }

    private final int port;
    private final Listener listener;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private final CopyOnWriteArrayList<ClientConnection> clients = new CopyOnWriteArrayList<>();

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

    /** Last SPS/PPS blob, replayed to every guest that joins later. */
    private volatile byte[] videoConfig;
    /** Current capture geometry, replayed on join and pushed on change. */
    private volatile Record geometry;

    public ClientHub(int port, Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    // ------------------------------ lifecycle -------------------------------

    /** Binds the listening socket and starts accepting. */
    public void start() throws IOException {
        serverSocket = new ServerSocket();
        // Lets us restart the service quickly without TIME_WAIT refusing the bind.
        serverSocket.setReuseAddress(true);
        // 0.0.0.0: every interface (WiFi, hotspot, USB tethering, ...).
        serverSocket.bind(new InetSocketAddress(port), 8);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "ghosthand-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        log("listening on port " + getPort());
    }

    public int getPort() {
        ServerSocket s = serverSocket;
        return (s != null && s.isBound()) ? s.getLocalPort() : port;
    }

    public boolean isRunning() {
        return running;
    }

    public int getClientCount() {
        return clients.size();
    }

    public void stop() {
        running = false;

        // Tell each guest why the picture is going away, then drop them.
        byte[] reason = "host stopped".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (ClientConnection c : clients) {
            c.send(new Frame(GhostProtocol.TYPE_BYE, (byte) 0, 0L, reason));
            c.close("host stopped");
        }
        clients.clear();

        Thread t = acceptThread;
        acceptThread = null;
        if (serverSocket != null) {
            try {
                serverSocket.close(); // unblocks accept()
            } catch (IOException ignored) {
                // shutting down
            }
            serverSocket = null;
        }
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        log("server stopped");
    }

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (SocketException e) {
                if (running) {
                    log("accept failed: " + e.getMessage());
                }
                break; // socket closed by stop()
            } catch (IOException e) {
                if (running) {
                    log("accept failed: " + e.getMessage());
                }
                continue;
            }
            final Socket s = socket;
            Thread handshake = new Thread(() -> handleNewClient(s), "ghosthand-handshake");
            handshake.setDaemon(true);
            handshake.start();
        }
    }

    /**
     * Handshake: the guest says HELLO first, we answer WELCOME plus whatever it
     * needs to start decoding right now. Doing this on its own thread keeps
     * accept() free for the next guest.
     */
    private void handleNewClient(Socket socket) {
        String peer = socket.getInetAddress() == null
                ? "?" : socket.getInetAddress().getHostAddress();
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);

            Frame first = FrameCodec.readFully(socket.getInputStream());
            String name = peer;
            if (first.type == GhostProtocol.TYPE_HELLO) {
                Record hello = first.asRecord();
                name = hello.getString("device", peer);
                log("guest '" + name + "' connected from " + peer);
            } else {
                log("guest from " + peer + " opened with " + GhostProtocol.typeName(first.type));
            }

            ClientConnection connection = new ClientConnection(socket, name, this);
            OutputStream out = connection.getOutputStream();

            Record welcome = Record.create()
                    .putString("device", android.os.Build.MODEL)
                    .putString("version", "1")
                    .putInt("protocol", GhostProtocol.VERSION);
            Record g = geometry;
            if (g != null) {
                welcome.putInt("w", g.getInt("w", 0));
                welcome.putInt("h", g.getInt("h", 0));
                welcome.putInt("rotation", g.getInt("rotation", 0));
            }
            FrameCodec.write(out, new Frame(GhostProtocol.TYPE_WELCOME, (byte) 0, 0L, welcome.toBytes()));

            byte[] config = videoConfig;
            if (config != null && config.length > 0) {
                FrameCodec.write(out, buildVideoConfigFrame(config));
            }
            if (g != null) {
                FrameCodec.write(out, new Frame(GhostProtocol.TYPE_GEOMETRY, (byte) 0, 0L, g.toBytes()));
            }

            clients.add(connection);
            connection.start();
            postClientCount();
            log("streaming to " + clients.size() + " guest(s)");
        } catch (IOException e) {
            log("handshake with " + peer + " failed: " + e.getMessage());
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing else to do
            }
        }
    }

    // ------------------------------ broadcasting ----------------------------

    /** Called by the service when the encoder emits its SPS/PPS. */
    public void setVideoConfig(byte[] config) {
        this.videoConfig = config;
        broadcast(buildVideoConfigFrame(config));
    }

    /** Frame that asks every connected guest for its clipboard. */
    public static Frame buildClipboardGetFrame() {
        return new Frame(GhostProtocol.TYPE_CLIPBOARD_GET, (byte) 0, 0L, new byte[0]);
    }

    /**
     * Frame carrying text for the peer's clipboard. {@code ok = false} is the honest
     * "could not read mine" answer and must never overwrite the receiver's copy.
     */
    public static Frame buildClipboardSetFrame(String text, boolean ok) {
        Record r = Record.create()
                .putString("text", ok ? GhostProtocol.clipText(text) : "")
                .putInt("ok", ok ? 1 : 0);
        return new Frame(GhostProtocol.TYPE_CLIPBOARD_SET, (byte) 0, 0L, r.toBytes());
    }

    /** Ask every guest for its clipboard. */
    public void clipboardGet() {
        broadcast(buildClipboardGetFrame());
    }

    /** Push text to every guest's clipboard. */
    public void clipboardSet(String text, boolean ok) {
        broadcast(buildClipboardSetFrame(text, ok));
    }

    /** Builds the VIDEO_CONFIG frame from an Annex-B SPS+PPS blob. */
    public static Frame buildVideoConfigFrame(byte[] annexBConfig) {
        List<byte[]> nals = ScreenEncoder.splitStartCodes(annexBConfig);
        byte[] sps = nals.size() > 0 ? nals.get(0) : new byte[0];
        byte[] pps = nals.size() > 1 ? nals.get(1) : new byte[0];
        Record r = Record.create()
                .putBytes("csd0", sps)
                .putBytes("csd1", pps)
                .putInt("nalCount", nals.size());
        return new Frame(GhostProtocol.TYPE_VIDEO_CONFIG, (byte) 0, 0L, r.toBytes());
    }

    /** Called when the capture size/rotation changed. */
    public void setGeometry(int width, int height, int rotation) {
        Record g = Record.create()
                .putInt("w", width)
                .putInt("h", height)
                .putInt("rotation", rotation);
        this.geometry = g;
        broadcast(new Frame(GhostProtocol.TYPE_GEOMETRY, (byte) 0, 0L, g.toBytes()));
    }

    /** Host telemetry, roughly once per second. */
    public void broadcastStats(Record stats) {
        broadcast(new Frame(GhostProtocol.TYPE_STATS, (byte) 0, 0L, stats.toBytes()));
    }

    /**
     * Fans a frame out to every guest. Cheap: it only appends to per-client queues,
     * so the encoder thread never blocks on the network.
     */
    public void broadcast(Frame frame) {
        for (ClientConnection c : clients) {
            c.send(frame);
        }
    }

    // --------------------------- ClientConnection.Listener -------------------

    @Override
    public void onClientClosed(ClientConnection connection, String reason) {
        if (clients.remove(connection)) {
            postClientCount();
            log("guest '" + connection.getName() + "' left (" + reason + ")");
        }
    }

    @Override
    public void onClientFrame(ClientConnection connection, Frame frame) {
        // Guest -> host traffic: PING, TOUCH and GLOBAL_ACTION.
        switch (frame.type) {
            case GhostProtocol.TYPE_PING:
                connection.send(new Frame(GhostProtocol.TYPE_PONG, (byte) 0,
                        System.currentTimeMillis(), frame.payload));
                break;
            case GhostProtocol.TYPE_TOUCH: {
                Record t = frame.asRecord();
                Listener l = listener;
                if (l == null) {
                    break;
                }
                l.onGuestTouch(connection.getName(),
                        (int) t.getInt("action", TouchInjector.ACTION_CANCEL),
                        (int) t.getInt("xN", 0),
                        (int) t.getInt("yN", 0),
                        t.getInt("timeMs", System.currentTimeMillis()));
                break;
            }
            case GhostProtocol.TYPE_GLOBAL_ACTION: {
                Record g = frame.asRecord();
                Listener l = listener;
                if (l == null) {
                    break;
                }
                l.onGuestGlobalAction(connection.getName(),
                        (int) g.getInt("action", 0));
                break;
            }
            case GhostProtocol.TYPE_CLIPBOARD_GET: {
                Listener l = listener;
                if (l != null) {
                    l.onGuestClipboardGet(connection);
                }
                break;
            }
            case GhostProtocol.TYPE_CLIPBOARD_SET: {
                Record c = frame.asRecord();
                Listener l = listener;
                if (l != null) {
                    l.onGuestClipboardSet(connection,
                            c.getString("text", ""), c.getInt("ok", 0) != 0);
                }
                break;
            }
            case GhostProtocol.TYPE_BYE:
                connection.close("guest said bye");
                break;
            default:
                log("ignored " + GhostProtocol.typeName(frame.type) + " from guest");
                break;
        }
    }

    // -------------------------------- helpers --------------------------------

    private void postClientCount() {
        final int n = clients.size();
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onClientCountChanged(n);
            }
        });
    }

    private void log(String message) {
        Log.i(TAG, message);
        final String m = message;
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onLog(m);
            }
        });
    }

    /** Snapshot for debugging/tests. */
    public List<String> clientNames() {
        List<String> names = new ArrayList<>();
        for (ClientConnection c : clients) {
            names.add(c.getName());
        }
        return names;
    }

    /** Total video frames dropped across all guests (i.e. how lossy we are). */
    public long getTotalDropped() {
        long total = 0;
        for (ClientConnection c : clients) {
            total += c.getDroppedFrames();
        }
        return total;
    }

    /** Total bytes pushed onto the wire, for the host's own readout. */
    public long getTotalSentBytes() {
        long total = 0;
        for (ClientConnection c : clients) {
            total += c.getSentBytes();
        }
        return total;
    }
}
