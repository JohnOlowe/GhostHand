package damjay.control.ghosthand.guest;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * The grey/green KitKat screen came from feeding {@code MediaFormat} a bare SPS NAL.
 * KitKat's MediaCodec (extractCSD -> queueCSDInputBuffer) memcpy's csd-0/csd-1
 * verbatim into codec-config input buffers, and an H.264 decoder that receives an SPS
 * with neither an Annex-B start code nor the avcC 0x01 header parses nothing. These
 * tests pin the normalization: bare NALs get a 4-byte start code, anything already
 * framed is left alone.
 */
public class VideoDecoderCsdTest {

    /** A real baseline SPS NAL header: forbidden_zero_bit=0, nal_ref_idc=3, type=7. */
    private static final byte[] BARE_SPS = new byte[] {
            0x67, 0x42, 0x00, 0x1e, (byte) 0xab, 0x40, 0x50, 0x17, (byte) 0xb0
    };

    private static final byte[] START_CODE = new byte[] { 0x00, 0x00, 0x00, 0x01 };

    @Test
    public void bareNalGetsFourByteStartCode() {
        byte[] out = VideoDecoder.withStartCode(BARE_SPS);
        assertEquals(4 + BARE_SPS.length, out.length);
        for (int i = 0; i < 3; i++) {
            assertEquals("start code byte " + i, 0, out[i]);
        }
        assertEquals(1, out[3]);
        for (int i = 0; i < BARE_SPS.length; i++) {
            assertEquals("payload byte " + i, BARE_SPS[i], out[4 + i]);
        }
    }

    @Test
    public void alreadyPrefixedNalIsUntouched() {
        byte[] framed = new byte[START_CODE.length + BARE_SPS.length];
        System.arraycopy(START_CODE, 0, framed, 0, 4);
        System.arraycopy(BARE_SPS, 0, framed, 4, BARE_SPS.length);
        byte[] out = VideoDecoder.withStartCode(framed);
        assertSame("idempotent: no double prefix", framed, out);
    }

    @Test
    public void threeByteStartCodeAlsoRecognised() {
        byte[] framed = new byte[] { 0, 0, 1, 0x67, 0x42 };
        byte[] out = VideoDecoder.withStartCode(framed);
        assertSame("00 00 01 framing is already Annex-B", framed, out);
    }

    @Test
    public void emptyAndNullBecomeEmpty() {
        assertEquals(0, VideoDecoder.withStartCode(null).length);
        assertEquals(0, VideoDecoder.withStartCode(new byte[0]).length);
    }

    @Test
    public void bareNalNeverAliasesTheInput() {
        byte[] out = VideoDecoder.withStartCode(BARE_SPS);
        assertNotSame(out, BARE_SPS);
        byte[] expect = new byte[4 + BARE_SPS.length];
        System.arraycopy(START_CODE, 0, expect, 0, 4);
        System.arraycopy(BARE_SPS, 0, expect, 4, BARE_SPS.length);
        assertArrayEquals(expect, out);
    }
}
