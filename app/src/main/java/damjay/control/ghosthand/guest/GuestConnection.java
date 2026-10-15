package damjay.control.ghosthand.guest;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import damjay.control.ghosthand.net.Frame;
import damjay.control.ghosthand.net.FrameCodec;

/**
 * One guest TCP session, with no Android dependency so its threading is testable.
 *
 * <p>The caller (usually the UI) only starts threads and offers immutable frames
 * to a bounded FIFO. The reader connects and receives; exactly one writer drains
 * the FIFO immediately, including HELLO and watchdog PINGs. Neither a touch nor
 * a clipboard/navigation command ever opens, writes or flushes a socket inline.
 * There is no timer/batch delay and no thread per touch event.
 *
 * <p>Closing is also asynchronous. A separate closer closes the socket, which
 * unblocks BOTH a stalled write and a stalled read/connect (interrupt alone does
 * not unblock socket I/O). Pending commands are discarded, not sent after a
 * disconnect. TCP EOF is the goodbye; attempting an inline BYE was another
 * NetworkOnMainThreadException in the old guest.
 *
 * <p>Every reconnect gets a NEW instance, socket, FIFO and workers. No queued
 * command or late failure from this session can be retargeted to the next host.
 */
public final class GuestConnection {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 1_000;
    static final int MAX_PENDING_FRAMES = 128;

    public interface Listener {
        /** Reader thread; never the producer/UI thread. */
        void onFrame(GuestConnection connection, Frame frame);

        /** Reader thread, once per socket read timeout. */
        void onReadTimeout(GuestConnection connection);

        /** Closer thread, exactly once; also called for a local close. */
        void onClosed(GuestConnection connection, String reason);
    }

    private final String host;
    private final int port;
    private final Socket socket;
    private final Listener listener;
    private final BlockingQueue<Frame> outgoing;
    private final Object stateLock = new Object();
    private final Thread reader;
    private final Thread writer;
    private boolean started;
    private volatile boolean open = true;

    public GuestConnection(String host, int port, Frame hello, Listener listener) {
        // An unconnected Socket does no network I/O (including DNS).
        this(host, port, hello, listener, new Socket(), MAX_PENDING_FRAMES);
    }

    /** Socket/capacity injection for deterministic blocking-I/O tests. */
    GuestConnection(String host, int port, Frame hello, Listener listener,
                    Socket socket, int capacity) {
        this.host = host;
        this.port = port;
        this.socket = socket;
        this.listener = listener;
        outgoing = new ArrayBlockingQueue<>(capacity);
        // Install HELLO before the object can be published to any producer.
        outgoing.add(hello);
        reader = new Thread(this::readLoop, "ghosthand-guest-reader");
        writer = new Thread(this::writeLoop, "ghosthand-guest-writer");
        reader.setDaemon(true);
        writer.setDaemon(true);
    }

    /** Returns immediately; safe to call on the main thread. Starts at most once. */
    public void start() {
        synchronized (stateLock) {
            if (!started && open) {
                started = true;
                reader.start();
            }
        }
    }

    /**
     * Nonblocking offer, safe on the main thread. The frame/payload must not be
     * mutated afterwards. FIFO preserves DOWN/MOVE/UP, both pointer transitions,
     * clipboard and PING ordering, with no interleaved codec headers.
     *
     * <p>If the peer cannot keep up, fail the session rather than block the UI,
     * grow an unbounded backlog, or silently skip pointer transitions on a "healthy" link.
     */
    public boolean send(Frame frame) {
        synchronized (stateLock) {
            if (!open) {
                return false;
            }
            if (outgoing.offer(frame)) {
                return true;
            }
            close("outgoing control queue full - connection too slow");
            return false;
        }
    }

    public boolean isOpen() {
        return open;
    }

    /** Stops accepting immediately, but never waits for or performs socket I/O. */
    public void close(final String reason) {
        synchronized (stateLock) {
            if (!open) {
                return;
            }
            open = false;
            outgoing.clear();
            reader.interrupt();
            writer.interrupt(); // wakes queue.take(); socket.close wakes write()
            Thread closer = new Thread(() -> {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // The peer may already have closed it.
                } finally {
                    listener.onClosed(this, reason);
                }
            }, "ghosthand-guest-close");
            closer.setDaemon(true);
            closer.start();
        }
    }

    private void readLoop() {
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setReceiveBufferSize(512 * 1024);
            // DNS and the connect itself both run here, never on the caller.
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            synchronized (stateLock) {
                if (!open) {
                    return;
                }
                writer.start();
            }
            InputStream in = socket.getInputStream();
            while (open) {
                Frame frame;
                try {
                    frame = FrameCodec.readFully(in);
                } catch (SocketTimeoutException timeout) {
                    if (open) {
                        listener.onReadTimeout(this);
                    }
                    continue;
                }
                if (open) {
                    listener.onFrame(this, frame);
                }
            }
        } catch (EOFException e) {
            close("host closed the connection");
        } catch (FrameCodec.ProtocolException e) {
            close("protocol error: " + e.getMessage());
        } catch (IOException e) {
            close("connection failed: " + e.getMessage());
        } catch (RuntimeException e) {
            // Invalid address/port or a platform failure: report and clean up,
            // rather than leave a writer and a socket behind after the reader dies.
            close("connection failed: " + e);
        } finally {
            close("connection finished");
        }
    }

    /** The ONLY place guest bytes are written, including HELLO and PING. */
    private void writeLoop() {
        try {
            OutputStream out = socket.getOutputStream();
            while (open) {
                Frame frame = outgoing.take();
                if (!open) {
                    return;
                }
                FrameCodec.write(out, frame);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            close("send failed: " + e.getMessage());
        } catch (RuntimeException e) {
            close("send failed: " + e);
        } finally {
            close("writer stopped");
        }
    }
}
