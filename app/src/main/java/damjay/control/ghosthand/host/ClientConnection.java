package damjay.control.ghosthand.host;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import damjay.control.ghosthand.net.Frame;
import damjay.control.ghosthand.net.FrameCodec;
import damjay.control.ghosthand.net.GhostProtocol;

/**
 * One connected guest, seen from the host.
 *
 * <p>Two threads touch this object:
 * <ul>
 *   <li>a <b>writer</b> thread that drains an in-memory queue and pushes bytes into
 *       the socket,</li>
 *   <li>a <b>reader</b> thread that blocks in {@link FrameCodec#readFully} waiting
 *       for guest -> host frames (PING, TOUCH, BYE).</li>
 * </ul>
 *
 * <p>The queue is the important design decision. It is bounded
 * ({@link #MAX_QUEUE}) and its offer policy is: never block the producer (the
 * encoder), and when we are behind, throw away <em>video</em> frames rather than
 * control frames. Dropping video costs a few glitchy macroblocks until the next key
 * frame; blocking the encoder costs the whole session.
 */
public class ClientConnection {

    private static final String TAG = "GhostHand/Client";

    /** How many frames we are willing to buffer for one slow guest. */
    private static final int MAX_QUEUE = 120;

    public interface Listener {
        void onClientClosed(ClientConnection connection, String reason);

        /** A frame the guest sent us (PING/TOUCH/BYE). Called on the reader thread. */
        void onClientFrame(ClientConnection connection, Frame frame);
    }

    private final Socket socket;
    private final String name;
    private final Listener listener;
    private final BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(MAX_QUEUE);
    private final AtomicLong sentBytes = new AtomicLong();
    private final AtomicLong droppedFrames = new AtomicLong();

    private OutputStream out;
    private Thread writerThread;
    private Thread readerThread;
    private volatile boolean open = true;

    public ClientConnection(Socket socket, String name, Listener listener) throws IOException {
        this.socket = socket;
        this.name = name;
        this.listener = listener;
        // Small buffer + TCP_NODELAY: we want frames on the wire immediately rather
        // than coalesced for throughput. Latency beats bandwidth for mirroring.
        socket.setTcpNoDelay(true);
        this.out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return socket.getInetAddress() == null ? "?" : socket.getInetAddress().getHostAddress();
    }

    public OutputStream getOutputStream() {
        return out;
    }

    public long getSentBytes() {
        return sentBytes.get();
    }

    public long getDroppedFrames() {
        return droppedFrames.get();
    }

    /** Starts the writer + reader threads. */
    public void start() {
        writerThread = new Thread(this::writeLoop, "ghosthand-client-w-" + name);
        writerThread.setDaemon(true);
        writerThread.start();

        readerThread = new Thread(this::readLoop, "ghosthand-client-r-" + name);
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * Hands a frame to this guest. Never blocks.
     *
     * @return true if the frame was queued, false if it was dropped or the
     *         connection is already closed.
     */
    public boolean send(Frame frame) {
        if (!open) {
            return false;
        }
        if (queue.offer(frame)) {
            return true;
        }
        // Queue full: this guest is slower than the encoder.
        if (frame.type == GhostProtocol.TYPE_VIDEO) {
            droppedFrames.incrementAndGet();
            return false;
        }
        // Control frames matter more than one more video frame: evict the oldest
        // video frame and try again.
        Frame head = queue.peek();
        if (head != null && head.type == GhostProtocol.TYPE_VIDEO) {
            queue.poll();
            droppedFrames.incrementAndGet();
            return queue.offer(frame);
        }
        return false;
    }

    private void writeLoop() {
        try {
            while (open) {
                Frame frame = queue.poll(250, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    continue;
                }
                FrameCodec.write(out, frame);
                sentBytes.addAndGet(frame.length() + GhostProtocol.HEADER_SIZE);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (SocketException e) {
            close("socket: " + e.getMessage());
            return;
        } catch (IOException e) {
            close("write failed: " + e.getMessage());
            return;
        }
        Log.i(TAG, "writer for " + name + " finished");
    }

    private void readLoop() {
        try {
            // No SO_TIMEOUT here: we want to block indefinitely and only wake up
            // when the guest sends something or closes the socket.
            while (open) {
                Frame frame = FrameCodec.readFully(socket.getInputStream());
                if (!open) {
                    break;
                }
                if (listener != null) {
                    listener.onClientFrame(this, frame);
                }
            }
        } catch (java.io.EOFException e) {
            close("guest disconnected");
        } catch (FrameCodec.ProtocolException e) {
            close("protocol error: " + e.getMessage());
        } catch (IOException e) {
            if (open) {
                close("read failed: " + e.getMessage());
            }
        }
    }

    /**
     * Idempotent shutdown. The hub calls this from any thread; whoever gets here
     * first flips {@code open} and notifies the listener exactly once.
     */
    public synchronized void close(String reason) {
        if (!open) {
            return;
        }
        open = false;
        queue.clear();
        try {
            socket.shutdownInput();
        } catch (IOException ignored) {
            // best effort
        }
        try {
            socket.shutdownOutput();
        } catch (IOException ignored) {
            // best effort
        }
        try {
            socket.close(); // unblocks both read() and write()
        } catch (IOException ignored) {
            // best effort
        }
        Thread w = writerThread;
        if (w != null && w != Thread.currentThread()) {
            w.interrupt();
        }
        Thread r = readerThread;
        if (r != null && r != Thread.currentThread()) {
            r.interrupt();
        }
        Log.i(TAG, "closed " + name + ": " + reason);
        if (listener != null) {
            listener.onClientClosed(this, reason);
        }
    }

    public boolean isOpen() {
        return open;
    }
}
