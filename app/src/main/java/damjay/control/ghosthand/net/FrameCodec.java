package damjay.control.ghosthand.net;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Turns {@link Frame}s into bytes and bytes back into {@link Frame}s.
 *
 * <p>Two directions, two very different situations:
 *
 * <ul>
 *   <li><b>Writing</b> happens on a socket's own writer thread with a blocking
 *       {@link OutputStream}, so {@link #write(OutputStream, Frame)} just writes the
 *       12-byte header and the payload with two {@code write()} calls.</li>
 *   <li><b>Reading</b> happens on a socket reader thread with a blocking
 *       {@link InputStream} ({@link #readFully}), but the same byte-parsing logic is
 *       also exposed as an incremental {@link #feed(byte[], int, int)} state machine
 *       so it can be unit tested without a socket and reused if we ever move to UDP
 *       or WebSocket transport.</li>
 * </ul>
 *
 * <p>The incremental reader is a two-state machine: accumulate
 * {@link GhostProtocol#HEADER_SIZE} (16) header bytes, read the payload length out
 * of them, then accumulate exactly that many payload bytes and emit a frame.
 * Anything left over stays buffered for the next chunk, which is what makes TCP's
 * arbitrary segment boundaries a non-issue.
 */
public final class FrameCodec {

    /** Called for every complete frame the incremental reader assembles. */
    public interface Listener {
        void onFrame(Frame frame);

        /** Protocol violation (bad magic/version, or an impossible length). */
        void onProtocolError(String message);
    }

    private final ByteArrayOutputStream headerBuf = new ByteArrayOutputStream(GhostProtocol.HEADER_SIZE);
    private byte[] payloadBuf;
    private int payloadFilled;
    private int payloadExpected;
    private byte pendingType;
    private byte pendingFlags;
    private long pendingPtsUs;
    private boolean broken;
    private Listener listener;

    public FrameCodec() {
        // no listener yet
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isBroken() {
        return broken;
    }

    /** Drops any partially received frame. Call after a decoder/listener restart. */
    public void reset() {
        headerBuf.reset();
        payloadBuf = null;
        payloadFilled = 0;
        payloadExpected = 0;
        broken = false;
    }

    // ------------------------------- writing --------------------------------

    /**
     * Writes one frame. The header is built in a 16-byte scratch array and written
     * first, then the payload: two {@code write()} calls per frame, no copying of
     * the payload itself.
     *
     * @throws IOException if the peer went away (the caller then closes the socket).
     */
    public static void write(OutputStream out, Frame frame) throws IOException {
        byte[] payload = frame.payload;
        byte[] header = new byte[GhostProtocol.HEADER_SIZE];
        header[0] = GhostProtocol.MAGIC;
        header[1] = GhostProtocol.VERSION;
        header[2] = frame.type;
        header[3] = frame.flags;
        putLong(header, 4, frame.ptsUs);      // bytes 4..11  presentation time
        putInt(header, 12, payload.length);    // bytes 12..15 payload length
        out.write(header);
        if (payload.length > 0) {
            out.write(payload);
        }
        out.flush();
    }

    /**
     * Blocking read of exactly one frame from {@code in}.
     *
     * @throws EOFException      clean disconnect (peer closed the socket)
     * @throws IOException       socket error, or read timeout (SocketTimeoutException)
     * @throws ProtocolException bad magic/version or an impossible payload length
     */
    public static Frame readFully(InputStream in) throws IOException {
        DataInputStream data = (in instanceof DataInputStream)
                ? (DataInputStream) in
                : new DataInputStream(in);
        byte magic = data.readByte();
        if (magic != GhostProtocol.MAGIC) {
            throw new ProtocolException("bad magic 0x" + Integer.toHexString(magic & 0xFF)
                    + " - is that a GhostHand peer?");
        }
        byte version = data.readByte();
        if (version != GhostProtocol.VERSION) {
            throw new ProtocolException("unsupported protocol version " + version
                    + " (this build speaks " + GhostProtocol.VERSION + ")");
        }
        byte type = data.readByte();
        byte flags = data.readByte();
        long ptsUs = data.readLong();
        int len = data.readInt();
        if (len < 0 || len > GhostProtocol.MAX_PAYLOAD) {
            throw new ProtocolException("implausible payload length " + len);
        }
        byte[] payload = new byte[len];
        data.readFully(payload);
        return new Frame(type, flags, ptsUs, payload);
    }

    /** Signals "the peer is not speaking our protocol". */
    public static class ProtocolException extends IOException {
        public ProtocolException(String message) {
            super(message);
        }
    }

    // --------------------------- incremental reading -------------------------

    /**
     * Feeds raw socket bytes in; complete frames come back through
     * {@link Listener#onFrame(Frame)}. Safe to call with any chunk size, including
     * one byte at a time.
     */
    public void feed(byte[] data, int offset, int length) {
        if (broken || data == null) {
            return;
        }
        int i = offset;
        int end = offset + length;
        while (i < end && !broken) {
            if (payloadExpected == 0) {
                // still collecting the header
                headerBuf.write(data[i++] & 0xFF);
                if (headerBuf.size() == GhostProtocol.HEADER_SIZE) {
                    if (!parseHeader(headerBuf.toByteArray())) {
                        break; // error already reported
                    }
                    headerBuf.reset();
                }
            } else {
                int want = payloadExpected - payloadFilled;
                int take = Math.min(want, end - i);
                System.arraycopy(data, i, payloadBuf, payloadFilled, take);
                payloadFilled += take;
                i += take;
                if (payloadFilled == payloadExpected) {
                    Frame frame = new Frame(pendingType, pendingFlags, pendingPtsUs, payloadBuf);
                    payloadBuf = null;
                    payloadFilled = 0;
                    payloadExpected = 0;
                    if (listener != null) {
                        listener.onFrame(frame);
                    }
                }
            }
        }
    }

    private boolean parseHeader(byte[] h) {
        if (h[0] != GhostProtocol.MAGIC) {
            fail("bad magic 0x" + Integer.toHexString(h[0] & 0xFF));
            return false;
        }
        if (h[1] != GhostProtocol.VERSION) {
            fail("unsupported protocol version " + h[1]);
            return false;
        }
        pendingType = h[2];
        pendingFlags = h[3];
        pendingPtsUs = readLong(h, 4);
        int len = readInt(h, 12);
        if (len < 0 || len > GhostProtocol.MAX_PAYLOAD) {
            fail("implausible payload length " + len);
            return false;
        }
        if (len == 0) {
            // Zero-length payload: emit immediately and stay in "want a header" state.
            payloadExpected = 0;
            if (listener != null) {
                listener.onFrame(new Frame(pendingType, pendingFlags, pendingPtsUs, new byte[0]));
            }
            return true;
        }
        payloadExpected = len;
        payloadFilled = 0;
        payloadBuf = new byte[len];
        return true;
    }

    private void fail(String message) {
        broken = true;
        headerBuf.reset();
        payloadBuf = null;
        payloadExpected = 0;
        payloadFilled = 0;
        if (listener != null) {
            listener.onProtocolError(message);
        }
    }

    private static void putInt(byte[] d, int off, int v) {
        d[off] = (byte) (v >>> 24);
        d[off + 1] = (byte) (v >>> 16);
        d[off + 2] = (byte) (v >>> 8);
        d[off + 3] = (byte) v;
    }

    private static void putLong(byte[] d, int off, long v) {
        for (int i = 0; i < 8; i++) {
            d[off + i] = (byte) (v >>> (56 - 8 * i));
        }
    }

    private static int readInt(byte[] d, int off) {
        return ((d[off] & 0xFF) << 24)
                | ((d[off + 1] & 0xFF) << 16)
                | ((d[off + 2] & 0xFF) << 8)
                | (d[off + 3] & 0xFF);
    }

    private static long readLong(byte[] d, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (d[off + i] & 0xFFL);
        }
        return v;
    }
}
