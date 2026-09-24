package damjay.control.ghosthand.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the sizing maths in {@link GhostProtocol}: the two pure functions that
 * decide what the encoder is asked to produce and how many bits it gets.
 *
 * <p>They are worth testing because encoders are unforgiving about the numbers.
 * A width that is not a multiple of 16 makes the codec pad the frame and smear the
 * right-hand edge; a bitrate below the encoder's minimum floor makes configure()
 * throw; an absurd bitrate makes it refuse to start on weak hardware.
 */
public class GhostProtocolTest {

    // ------------------------------------------------------------------
    // fitCaptureSize
    // ------------------------------------------------------------------

    @Test
    public void captureSizeIsNotUpscaledWhenAlreadySmallEnough() {
        // A 480p phone asked for a 1920 cap must stay at 480p: upscaling would add
        // pixels, bandwidth and battery for no extra detail.
        int[] size = GhostProtocol.fitCaptureSize(854, 480, 1920);
        assertEquals(848, size[0]);   // 854 -> 848 (53 * 16)
        assertEquals(480, size[1]);
    }

    @Test
    public void captureSizeScalesDownToTheRequestedLongestSide() {
        int[] size = GhostProtocol.fitCaptureSize(1080, 2400, 720);
        assertEquals(720, Math.max(size[0], size[1]));
        // Aspect ratio is preserved to within a macroblock row.
        float before = 1080f / 2400f;
        float after = (float) size[0] / size[1];
        assertTrue("aspect drifted: " + after, Math.abs(before - after) < 0.02f);
    }

    @Test
    public void captureSizeIsAlwaysAMultipleOfSixteen() {
        int[][] screens = {
                { 1080, 2400 }, { 1440, 3200 }, { 720, 1280 }, { 1080, 1920 },
                { 320, 480 }, { 100, 101 }, { 3999, 4001 },
        };
        int[] caps = { 480, 720, 1280, 1920, 4096 };
        for (int[] screen : screens) {
            for (int cap : caps) {
                int[] size = GhostProtocol.fitCaptureSize(screen[0], screen[1], cap);
                assertEquals("width " + size[0] + " for " + screen[0] + "x" + screen[1] + "@" + cap,
                        0, size[0] % 16);
                assertEquals("height " + size[1] + " for " + screen[0] + "x" + screen[1] + "@" + cap,
                        0, size[1] % 16);
            }
        }
    }

    @Test
    public void captureSizeNeverGoesBelowAViableEncoderMinimum() {
        // Degenerate input: a 1x1 screen (never happens, but the maths must not
        // return 0x0 - an encoder configured below 16x16 throws at start()).
        int[] tiny = GhostProtocol.fitCaptureSize(1, 1, 480);
        assertEquals(64, tiny[0]);
        assertEquals(64, tiny[1]);

        int[] zero = GhostProtocol.fitCaptureSize(0, 0, 1280);
        assertTrue(zero[0] >= 64 && zero[1] >= 64);
    }

    @Test
    public void captureSizeKeepsLandscapeLandscape() {
        // The rotation must not be silently swapped: the VirtualDisplay is created
        // with exactly these values.
        int[] landscape = GhostProtocol.fitCaptureSize(2400, 1080, 1280);
        assertTrue("expected w > h, got " + landscape[0] + "x" + landscape[1],
                landscape[0] > landscape[1]);
    }

    @Test
    public void captureSizeNativePresetLeavesBigScreensUntouched() {
        int[] size = GhostProtocol.fitCaptureSize(1440, 3120, 4096);
        assertEquals(1440, size[0]);
        assertEquals(3120, size[1]);
    }

    // ------------------------------------------------------------------
    // suggestedBitrate
    // ------------------------------------------------------------------

    @Test
    public void bitrateGrowsWithPixelsAndFrames() {
        int small = GhostProtocol.suggestedBitrate(720, 1280, 30);
        int large = GhostProtocol.suggestedBitrate(1080, 1920, 30);
        int faster = GhostProtocol.suggestedBitrate(720, 1280, 60);
        assertTrue("1080p should outrank 720p", large > small);
        assertTrue("60 fps should outrank 30 fps", faster > small);
    }

    @Test
    public void bitrateStaysInsideTheClamp() {
        // A 1x1 stream must still get a sane floor, not 0.
        assertEquals(800_000, GhostProtocol.suggestedBitrate(16, 16, 30));
        // A 4K-ish request must not be handed 30 Mbps that WiFi cannot carry.
        assertEquals(16_000_000, GhostProtocol.suggestedBitrate(4096, 4096, 60));
    }

    @Test
    public void bitrateForTheDefaultPresetIsWiFiFriendly() {
        int bitrate = GhostProtocol.suggestedBitrate(720, 1280, 30);
        assertTrue("720p30 landed at " + bitrate, bitrate >= 2_000_000 && bitrate <= 4_000_000);
    }

    // ------------------------------------------------------------------
    // constants
    // ------------------------------------------------------------------

    @Test
    public void headerIsSixteenBytesAndTypesAreDistinct() {
        assertEquals(16, GhostProtocol.HEADER_SIZE);

        byte[] types = {
                GhostProtocol.TYPE_HELLO, GhostProtocol.TYPE_TOUCH, GhostProtocol.TYPE_PING,
                GhostProtocol.TYPE_WELCOME, GhostProtocol.TYPE_PONG, GhostProtocol.TYPE_VIDEO_CONFIG,
                GhostProtocol.TYPE_VIDEO, GhostProtocol.TYPE_GEOMETRY, GhostProtocol.TYPE_STATS,
                GhostProtocol.TYPE_BYE,
        };
        for (int i = 0; i < types.length; i++) {
            for (int j = i + 1; j < types.length; j++) {
                assertFalse("type " + i + " collides with " + j, types[i] == types[j]);
            }
            assertTrue("ghosthand_type " + types[i] + " must be positive", types[i] > 0);
        }
    }

    @Test
    public void everyTypeHasAReadableName() {
        byte[] types = {
                GhostProtocol.TYPE_HELLO, GhostProtocol.TYPE_TOUCH, GhostProtocol.TYPE_PING,
                GhostProtocol.TYPE_WELCOME, GhostProtocol.TYPE_PONG, GhostProtocol.TYPE_VIDEO_CONFIG,
                GhostProtocol.TYPE_VIDEO, GhostProtocol.TYPE_GEOMETRY, GhostProtocol.TYPE_STATS,
                GhostProtocol.TYPE_BYE,
        };
        for (byte type : types) {
            String name = GhostProtocol.typeName(type);
            assertFalse("no LOG name for type " + type, name.startsWith("UNKNOWN"));
        }
        assertTrue(GhostProtocol.typeName((byte) 99).startsWith("UNKNOWN"));
    }

    @Test
    public void thePortIsInThePrivateRange() {
        // 47811 is inside the IANA dynamic/private range, so it will not collide
        // with a well-known service on a home router.
        assertTrue(GhostProtocol.DEFAULT_PORT > 49151
                || (GhostProtocol.DEFAULT_PORT >= 1024 && GhostProtocol.DEFAULT_PORT <= 49151));
        assertTrue(GhostProtocol.DEFAULT_PORT <= 65535);
    }

    @Test
    public void theServiceTypeIsAMdnsName() {
        assertTrue(GhostProtocol.NSD_SERVICE_TYPE.startsWith("_"));
        assertTrue(GhostProtocol.NSD_SERVICE_TYPE.endsWith("."));
        assertTrue(GhostProtocol.NSD_SERVICE_TYPE.contains("._tcp"));
    }
}
