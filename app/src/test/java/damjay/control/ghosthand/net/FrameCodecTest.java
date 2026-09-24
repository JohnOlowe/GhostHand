package damjay.control.ghosthand.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the wire format: {@link FrameCodec} framing and {@link Record} payloads.
 *
 * <p>These run on a plain JVM in {@code toolchain/test.sh} - none of the classes
 * touched here reference {@code android.*}, which is the whole point of keeping the
 * protocol in its own package.
 *
 * <p>The interesting cases are the ones that only appear on a real socket: a frame
 * split across three TCP reads, three frames coalesced into one read, and a
 * corrupted magic byte. A TCP stream is a byte river, not a message queue, and every
 * "the video froze after a while" bug starts with code that forgot it.
 */
public class FrameCodecTest {

    /**
     * Collects everything a codec emits. Handy because {@link FrameCodec.Listener}
     * has two methods and every test cares about at least one of them.
     */
    private static final class Recorder implements FrameCodec.Listener {
        final List<Frame> frames = new ArrayList<Frame>();
        final List<String> errors = new ArrayList<String>();

        @Override
        public void onFrame(Frame frame) {
            frames.add(frame);
        }

        @Override
        public void onProtocolError(String message) {
            errors.add(message);
        }
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Round trip
    // ------------------------------------------------------------------

    @Test
    public void writeThenReadRestoresEveryHeaderField() throws IOException {
        byte[] payload = bytes(1, 2, 3, 4, 5);
        Frame original = new Frame(GhostProtocol.TYPE_VIDEO,
                GhostProtocol.FLAG_KEYFRAME, 1234567890123L, payload);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.write(out, original);

        assertEquals(GhostProtocol.HEADER_SIZE + payload.length, out.size());

        Frame decoded = FrameCodec.readFully(
                new ByteArrayInputStream(out.toByteArray()));

        assertNotNull(decoded);
        assertEquals(GhostProtocol.TYPE_VIDEO, decoded.type);
        assertEquals(GhostProtocol.FLAG_KEYFRAME, decoded.flags);
        assertEquals(1234567890123L, decoded.ptsUs);
        assertTrue(decoded.isKeyframe());
        assertArrayEquals(payload, decoded.payload);
    }

    @Test
    public void headerLayoutIsStableAtSixteenBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.write(out, new Frame(GhostProtocol.TYPE_PING, (byte) 0, 0L, null));
        byte[] wire = out.toByteArray();

        assertEquals(16, wire.length);
        assertEquals(GhostProtocol.MAGIC, wire[0]);
        assertEquals(GhostProtocol.VERSION, wire[1]);
        assertEquals(GhostProtocol.TYPE_PING, wire[2]);
        assertEquals(0, wire[3]);
        // pts (int64, big-endian) then length (int32)
        for (int i = 4; i < 12; i++) {
            assertEquals("pts byte " + i, 0, wire[i]);
        }
        for (int i = 12; i < 16; i++) {
            assertEquals("length byte " + i, 0, wire[i]);
        }
    }

    @Test
    public void negativePresentationTimestampsSurvive() throws IOException {
        // MediaCodec can hand out a negative pts for the very first buffer.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.write(out, new Frame(GhostProtocol.TYPE_VIDEO, (byte) 0, -42L, bytes(9)));

        Frame decoded = FrameCodec.readFully(new ByteArrayInputStream(out.toByteArray()));
        assertNotNull(decoded);
        assertEquals(-42L, decoded.ptsUs);
    }

    @Test
    public void emptyPayloadIsLegal() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.write(out, new Frame(GhostProtocol.TYPE_BYE, (byte) 0, 7L, null));

        Frame decoded = FrameCodec.readFully(new ByteArrayInputStream(out.toByteArray()));
        assertNotNull(decoded);
        assertEquals(0, decoded.length());
        assertEquals(GhostProtocol.TYPE_BYE, decoded.type);
    }

    // ------------------------------------------------------------------
    // Streaming decode
    // ------------------------------------------------------------------

    /** One byte at a time: the worst fragmentation a real socket can produce. */
    @Test
    public void feedReassemblesAByteAtATime() {
        FrameCodec codec = new FrameCodec();
        Recorder rec = new Recorder();
        codec.setListener(rec);

        Frame sent = new Frame(GhostProtocol.TYPE_VIDEO, GhostProtocol.FLAG_KEYFRAME,
                999L, bytes(10, 20, 30, 40));
        byte[] wire = encode(sent);

        for (byte b : wire) {
            codec.feed(new byte[] { b }, 0, 1);
        }

        assertEquals(1, rec.frames.size());
        assertArrayEquals(sent.payload, rec.frames.get(0).payload);
        assertEquals(999L, rec.frames.get(0).ptsUs);
        assertTrue(rec.frames.get(0).isKeyframe());
        assertFalse(codec.isBroken());
    }

    /** Three frames arriving in a single read must all come out, in order. */
    @Test
    public void feedSplitsCoalescedFrames() {
        FrameCodec codec = new FrameCodec();
        Recorder rec = new Recorder();
        codec.setListener(rec);

        byte[] a = encode(new Frame(GhostProtocol.TYPE_WELCOME, (byte) 0, 1L, bytes(1, 1)));
        byte[] b = encode(new Frame(GhostProtocol.TYPE_STATS, (byte) 0, 2L, bytes(2, 2, 2)));
        byte[] c = encode(new Frame(GhostProtocol.TYPE_PONG, (byte) 0, 3L, null));

        byte[] all = new byte[a.length + b.length + c.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        System.arraycopy(c, 0, all, a.length + b.length, c.length);

        codec.feed(all, 0, all.length);

        assertEquals(3, rec.frames.size());
        assertEquals(GhostProtocol.TYPE_WELCOME, rec.frames.get(0).type);
        assertEquals(GhostProtocol.TYPE_STATS, rec.frames.get(1).type);
        assertEquals(GhostProtocol.TYPE_PONG, rec.frames.get(2).type);
        assertEquals(0, rec.frames.get(2).length());
    }

    /** A frame delivered in two arbitrary chunks, split inside the header. */
    @Test
    public void feedHandlesSplitInsideHeader() {
        FrameCodec codec = new FrameCodec();
        Recorder rec = new Recorder();
        codec.setListener(rec);

        byte[] wire = encode(new Frame(GhostProtocol.TYPE_HELLO, (byte) 0, 5L, bytes(7, 7, 7)));
        codec.feed(wire, 0, 3);                       // half a header
        assertEquals(0, rec.frames.size());             // nothing may be emitted yet
        codec.feed(wire, 3, wire.length - 3);

        assertEquals(1, rec.frames.size());
        assertArrayEquals(bytes(7, 7, 7), rec.frames.get(0).payload);
    }

    /**
     * Framing is strict, not self-resynchronising: a peer that sends a byte stream
     * which is not GhostHand gets an error and a closed connection rather than a
     * silent attempt to hunt for a 0x47. A half-read frame plus a header from
     * somewhere else would otherwise be decoded as a plausible-looking video frame.
     */
    @Test
    public void feedReportsForeignBytesInsteadOfResyncing() {
        FrameCodec codec = new FrameCodec();
        Recorder rec = new Recorder();
        codec.setListener(rec);

        // Looks like the start of an HTTP request, i.e. some other app on the port.
        byte[] foreign = bytes(0x47, 0x45, 0x54, 0x20, 0x2F, 0x20, 0x48, 0x54,
                0x54, 0x50, 0x2F, 0x31, 0x2E, 0x31, 0x0D, 0x0A);
        codec.feed(foreign, 0, foreign.length);

        assertTrue("the codec must report the mismatch", codec.isBroken());
        assertEquals(1, rec.errors.size());
        assertEquals(0, rec.frames.size());

        // And it stays quiet afterwards rather than trying to interpret the rest.
        codec.feed(foreign, 0, foreign.length);
        assertEquals(1, rec.errors.size());
    }

    @Test
    public void feedRejectsAnUnsupportedVersion() {
        FrameCodec codec = new FrameCodec();
        byte[] hostile = bytes(GhostProtocol.MAGIC, 0x7F, GhostProtocol.TYPE_VIDEO, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        codec.feed(hostile, 0, hostile.length);
        assertTrue("a foreign version must mark the stream broken",
                codec.isBroken());
    }

    @Test
    public void feedRejectsAnAbsurdPayloadLength() {
        FrameCodec codec = new FrameCodec();
        // Length far beyond MAX_PAYLOAD: allocating it would OOM the guest.
        int bogus = GhostProtocol.MAX_PAYLOAD + 1;
        byte[] hostile = bytes(GhostProtocol.MAGIC, GhostProtocol.VERSION,
                GhostProtocol.TYPE_VIDEO, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                (bogus >>> 24) & 0xFF, (bogus >>> 16) & 0xFF,
                (bogus >>> 8) & 0xFF, bogus & 0xFF);
        codec.feed(hostile, 0, hostile.length);
        assertTrue(codec.isBroken());
    }

    @Test
    public void resetClearsTheBrokenFlagAndThePartialFrame() {
        FrameCodec codec = new FrameCodec();
        codec.feed(bytes(GhostProtocol.MAGIC, 0x7F, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                0, 16);
        assertTrue(codec.isBroken());
        // A partial frame is also discarded, so the next stream starts clean.
        codec.feed(bytes(0x01, 0x02, 0x03), 0, 3);

        codec.reset();
        assertFalse(codec.isBroken());

        Recorder rec = new Recorder();
        codec.setListener(rec);
        byte[] wire = encode(new Frame(GhostProtocol.TYPE_WELCOME, (byte) 0, 1L, bytes(2)));
        codec.feed(wire, 0, wire.length);
        assertEquals(1, rec.frames.size());
        assertEquals(0, rec.errors.size());
    }

    // ------------------------------------------------------------------
    // Blocking reader
    // ------------------------------------------------------------------

    /**
     * A clean end of stream is a disconnect, not "no more frames for now": the
     * reader thread relies on EOFException to tell "the guest closed the socket"
     * apart from "the read timed out" (which is a SocketTimeoutException and only
     * arms the watchdog).
     */
    @Test
    public void readFullyThrowsEofAtCleanEndOfStream() {
        try {
            FrameCodec.readFully(new ByteArrayInputStream(new byte[0]));
            fail("expected EOFException");
        } catch (IOException expected) {
            assertTrue("expected EOFException, got " + expected.getClass().getSimpleName(),
                    expected instanceof java.io.EOFException);
        }
    }

    @Test
    public void readFullyFailsOnBadMagic() {
        byte[] impostor = bytes(0x48, GhostProtocol.VERSION, 1, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);   // 'H' instead of 'G'
        try {
            FrameCodec.readFully(new ByteArrayInputStream(impostor));
            fail("expected a ProtocolException");
        } catch (IOException expected) {
            assertTrue(expected instanceof FrameCodec.ProtocolException);
            assertTrue(expected.getMessage().contains("magic"));
        }
    }

    @Test
    public void readFullyFailsOnAnUnsupportedVersion() {
        byte[] wire = bytes(GhostProtocol.MAGIC, 0x7F, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        try {
            FrameCodec.readFully(new ByteArrayInputStream(wire));
            fail("expected a ProtocolException");
        } catch (IOException expected) {
            assertTrue(expected instanceof FrameCodec.ProtocolException);
        }
    }

    @Test
    public void readFullyFailsOnTruncatedInput() {
        byte[] wire = encode(new Frame(GhostProtocol.TYPE_VIDEO, (byte) 0, 1L, bytes(1, 2, 3, 4)));
        InputStream truncated = new ByteArrayInputStream(wire, 0, wire.length - 2);
        try {
            FrameCodec.readFully(truncated);
            fail("expected an EOF failure for a truncated payload");
        } catch (IOException expected) {
            // EOFException / ProtocolException - both are IOException.
        }
    }

    private static byte[] encode(Frame frame) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            FrameCodec.write(out, frame);
            return out.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
