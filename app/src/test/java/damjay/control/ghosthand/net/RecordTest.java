package damjay.control.ghosthand.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link Record}, the tiny TLV key/value bag every non-video message
 * carries (HELLO, WELCOME, STATS, TOUCH, GEOMETRY...).
 *
 * <p>Keeping it hand-rolled instead of using Java serialisation or JSON means:
 * no reflection, no class names on the wire, nothing to keep in sync between two
 * APK versions. The price is that the encoder and decoder have to agree exactly,
 * which is what these tests pin down - especially the two-byte-decoder-byte case,
 * because the video config carries raw SPS/PPS bytes that are full of zeros.
 */
public class RecordTest {

    @Test
    public void roundTripsEveryValueType() {
        byte[] blob = { 0x00, 0x01, (byte) 0xFF, 0x00, 0x7F };
        Record nested = Record.create().putInt("inner", 7L).putString("note", "hi");

        Record original = Record.create()
                .putInt("w", 1280)
                .putInt("h", 720)
                .putInt("negative", -5)
                .putString("device", "Pixel 7")
                .putString("empty", "")
                .putBytes("csd0", blob)
                .putRecord("child", nested);

        Record decoded = Record.fromBytes(original.toBytes());

        assertEquals(1280L, decoded.getInt("w", -1));
        assertEquals(720L, decoded.getInt("h", -1));
        assertEquals(-5L, decoded.getInt("negative", 0));
        assertEquals("Pixel 7", decoded.getString("device", null));
        assertEquals("", decoded.getString("empty", "fallback"));
        assertArrayEquals(blob, decoded.getBytes("csd0"));
        assertEquals(7L, decoded.getRecord("child").getInt("inner", -1));
        assertEquals("hi", decoded.getRecord("child").getString("note", null));
        assertEquals(original.size(), decoded.size());
    }

    @Test
    public void missingKeysReturnTheFallbackAndNull() {
        Record r = Record.create().putInt("present", 1L);

        assertFalse(r.has("absent"));
        assertTrue(r.has("present"));
        assertEquals(42L, r.getInt("absent", 42L));
        assertEquals("fallback", r.getString("absent", "fallback"));
        // byte[] and Record accessors return an empty container rather than null, so
        // callers never need a null check before iterating or calling .length.
        assertEquals(0, r.getBytes("absent").length);
        assertEquals(0, r.getRecord("absent").size());

        // Asking for the wrong type is a miss, not an exception: a newer peer may
        // send a key we read as another type, and that must not kill the connection.
        Record mismatched = Record.fromBytes(Record.create().putString("x", "text").toBytes());
        assertEquals(0L, mismatched.getInt("x", 0L));
    }

    @Test
    public void integersUseTheFullLongRange() {
        long[] values = { 0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 47811L, 1L << 40 };
        for (long value : values) {
            Record decoded = Record.fromBytes(Record.create().putInt("v", value).toBytes());
            assertEquals("value " + value, value, decoded.getInt("v", 0L));
        }
    }

    /** The exact case that matters: NAL bytes are full of zeros and 0xFF runs. */
    @Test
    public void byteArraysSurviveWithEmbeddedZerosAndHighBytes() {
        byte[] sps = new byte[64];
        for (int i = 0; i < sps.length; i++) {
            sps[i] = (byte) (i * 4);          // includes 0x00, 0x80, 0xC0, 0xFC...
        }
        Record decoded = Record.fromBytes(Record.create().putBytes("csd0", sps).toBytes());
        assertArrayEquals(sps, decoded.getBytes("csd0"));
    }

    @Test
    public void utf8SurvivesNonAsciiText() {
        String text = "Chloé's Pixel · 東京 · emoji \uD83D\uDCF1";
        Record decoded = Record.fromBytes(Record.create().putString("device", text).toBytes());
        assertEquals(text, decoded.getString("device", null));
    }

    @Test
    public void emptyRecordIsAnEmptyBag() {
        Record empty = Record.create();
        assertEquals(0, empty.size());
        assertEquals(0, empty.toBytes().length);

        Record decoded = Record.fromBytes(empty.toBytes());
        assertEquals(0, decoded.size());
        assertFalse(decoded.has("anything"));
    }

    @Test
    public void overwritingAKeyReplacesTheValue() {
        Record r = Record.create().putInt("w", 1L).putInt("w", 2L).putString("w", "three");
        Record decoded = Record.fromBytes(r.toBytes());
        assertEquals(1, decoded.size());
        assertEquals("three", decoded.getString("w", null));
    }

    @Test
    public void nestedRecordsGoSeveralLevelsDeep() {
        Record deep = Record.create()
                .putRecord("a", Record.create()
                        .putRecord("b", Record.create().putInt("c", 3L)));
        Record decoded = Record.fromBytes(deep.toBytes());
        assertEquals(3L, decoded.getRecord("a").getRecord("b").getInt("c", -1));
    }

    @Test
    public void aTruncatedBlobDoesNotThrow() {
        // A hostile or half-written payload must degrade to "empty bag", because the
        // caller usually just falls back to defaults.
        byte[] truncated = { 0x00, 0x00, 0x03, 'f', 'p', 's' };
        Record decoded = Record.fromBytes(truncated);
        assertNotNull(decoded);
        assertEquals(0, decoded.size());
        assertEquals(0L, decoded.getInt("fps", 0L));
    }

    @Test
    public void fromNullIsAnEmptyRecord() {
        Record decoded = Record.fromBytes(null);
        assertNotNull(decoded);
        assertEquals(0, decoded.size());
    }

    // ------------------------------------------------------------------
    // The shapes the app actually sends
    // ------------------------------------------------------------------

    @Test
    public void welcomePayloadRoundTripsAsTheHostBuildsIt() {
        Record decoded = Record.fromBytes(Record.create()
                .putString("device", "Pixel 7")
                .putInt("w", 720)
                .putInt("h", 1280)
                .putInt("fps", 30)
                .putInt("version", GhostProtocol.VERSION)
                .putInt("mode", 0)
                .toBytes());

        assertEquals("Pixel 7", decoded.getString("device", null));
        assertEquals(720L, decoded.getInt("w", 0L));
        assertEquals(1280L, decoded.getInt("h", 0L));
        assertEquals(30L, decoded.getInt("fps", 0L));
        assertEquals((long) GhostProtocol.VERSION, decoded.getInt("version", -1L));
    }

    @Test
    public void touchPayloadRoundTripsNormalisedCoordinates() {
        // Coordinates travel as 0..10000, so a guest screen of any DPI agrees with
        // the host about where the finger was.
        Record decoded = Record.fromBytes(Record.create()
                .putInt("action", 0)
                .putInt("x", 5000)
                .putInt("y", 2500)
                .putInt("pointer", 0)
                .putInt("timeMs", 123456L)
                .toBytes());

        assertEquals(5000L, decoded.getInt("x", -1L));
        assertEquals(2500L, decoded.getInt("y", -1L));
        assertEquals(123456L, decoded.getInt("timeMs", -1L));
    }
}
