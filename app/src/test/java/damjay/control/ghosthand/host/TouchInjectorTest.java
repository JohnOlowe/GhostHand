package damjay.control.ghosthand.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link TouchInjector}: the tap-or-drag decision, the polyline it builds,
 * and the normalised-to-pixel mapping.
 *
 * <p>This is the half of touch injection that can be tested without a device, which
 * is why it was written as pure Java in the first place: the {@code dispatchGesture}
 * call needs an accessibility grant and a real screen, but <em>deciding what the
 * guest did</em> is arithmetic. If a drag comes out as a tap, this is where it shows.
 *
 * <p>Every timestamp below is milliseconds on a monotonic clock, exactly what the
 * guest's {@code SystemClock.uptimeMillis()} produces.
 */
public class TouchInjectorTest {

    /** A 1080x2400 phone, i.e. what toPixels() multiplies into. */
    private static final int SCREEN_W = 1080;
    private static final int SCREEN_H = 2400;

    private static TouchInjector injector() {
        return new TouchInjector();
    }

    /** Feeds a whole DOWN/…/UP sequence and returns the gesture it produced. */
    private static TouchInjector.Gesture gesture(TouchInjector in, int[][] points, long[] times) {
        TouchInjector.Gesture result = null;
        for (int i = 0; i < points.length; i++) {
            int action = i == 0 ? TouchInjector.ACTION_DOWN
                    : (i == points.length - 1 ? TouchInjector.ACTION_UP
                    : TouchInjector.ACTION_MOVE);
            result = in.onTouch(action, points[i][0], points[i][1], times[i]);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // tap vs drag
    // ------------------------------------------------------------------

    @Test
    public void aQuickPressThatDoesNotMoveIsATap() {
        TouchInjector in = injector();
        TouchInjector.Gesture g = gesture(in, new int[][] { { 500, 900 }, { 500, 900 } },
                new long[] { 1000L, 1080L });

        assertNotNull(g);
        assertTrue("expected a tap, got " + g, g.isTap());
        assertEquals(1, g.pointCount());
        assertEquals(500, g.startX());
        assertEquals(900, g.startY());
        assertEquals(80L, g.durationMs);
    }

    @Test
    public void aPressHeldDownWithoutMovingStaysATapWithTheRealDuration() {
        // A long press on an icon is still a single-point stroke, just a long one.
        TouchInjector in = injector();
        TouchInjector.Gesture g = gesture(in, new int[][] { { 300, 300 }, { 300, 300 } },
                new long[] { 0L, 700L });

        assertNotNull(g);
        assertTrue(g.isTap());
        assertEquals("the press duration must survive", 700L, g.durationMs);
    }

    @Test
    public void aFingerWobblingInsideTheSlopRadiusIsStillATap() {
        // Real fingers move a few pixels while tapping. If that counted as a drag,
        // every button press would become a tiny scroll instead of a click.
        TouchInjector in = injector();
        TouchInjector.Gesture g = gesture(in,
                new int[][] { { 500, 500 }, { 505, 503 }, { 508, 496 }, { 502, 500 } },
                new long[] { 0L, 30L, 60L, 90L });

        assertNotNull(g);
        assertTrue("wobble must not become a drag: " + g, g.isTap());
        // The tap injects where the finger *landed* (the DOWN position), the same way
        // Android dispatches a click at the ACTION_DOWN point - so a wobble can never
        // walk the tap target across the screen.
        assertEquals(500, g.startX());
        assertEquals(500, g.startY());
        // …and the press still lasted its full 90 ms.
        assertEquals(90L, g.durationMs);
    }

    @Test
    public void aLongWobblyPressDoesNotWalkTheTapAcrossTheScreen() {
        // Regression: the trail folds sub-threshold movements into its last point, so
        // a tap's recorded position used to creep - here 30 moves of 5 px would have
        // slid the tap target 150 px from where the finger landed. It must also still
        // report the real press duration rather than the folded timestamp.
        TouchInjector in = injector();
        int[][] points = new int[32][];
        long[] times = new long[32];
        points[0] = new int[] { 540, 1200 };
        times[0] = 0L;
        for (int i = 1; i <= 30; i++) {
            points[i] = new int[] { 540 + i * 5, 1200 };   // 5 px each: all fold away
            times[i] = i * 30L;
        }
        points[31] = new int[] { 690, 1200 };
        times[31] = 1000L;

        TouchInjector.Gesture g = gesture(in, points, times);

        assertNotNull(g);
        assertTrue("still inside the 12 px slop: " + g, g.isTap());
        assertEquals("the tap must land where the finger landed", 540, g.startX());
        assertEquals(1200, g.startY());
        assertEquals("the press lasted a second", 1000L, g.durationMs);
    }

    @Test
    public void movementPastTheSlopRadiusIsADrag() {
        TouchInjector in = injector();
        TouchInjector.Gesture g = gesture(in,
                new int[][] { { 100, 2000 }, { 300, 2000 }, { 700, 1900 } },
                new long[] { 0L, 60L, 140L });

        assertNotNull(g);
        assertFalse(g.isTap());
        assertEquals(3, g.pointCount());
        assertEquals(100, g.startX());
        assertEquals(700, g.endX());
        assertEquals(1900, g.endY());
        assertTrue("path should be ~600 px, was " + g.pathLengthPx(),
                g.pathLengthPx() > 550f);
    }

    @Test
    public void aFastFlickIsStillADrag() {
        // 20 px in 8 ms is fast, but it is movement: injecting it as a tap would
        // lose the scroll entirely.
        TouchInjector in = injector();
        TouchInjector.Gesture g = gesture(in,
                new int[][] { { 100, 100 }, { 120, 100 } }, new long[] { 0L, 8L });

        assertNotNull(g);
        assertFalse(g.isTap());
        assertEquals(2, g.pointCount());
    }

    // ------------------------------------------------------------------
    // timing
    // ------------------------------------------------------------------

    @Test
    public void dragOffsetsAreRelativeToTheStartAndNeverGoBackwards() {
        TouchInjector in = injector();
        TouchInjector.Gesture g = gesture(in,
                new int[][] { { 0, 0 }, { 100, 0 }, { 250, 50 }, { 400, 50 } },
                new long[] { 5000L, 5040L, 5120L, 5250L });

        assertNotNull(g);
        assertEquals(0L, g.offsetsMs[0]);
        for (int i = 1; i < g.pointCount(); i++) {
            assertTrue("offset " + i + " went backwards",
                    g.offsetsMs[i] >= g.offsetsMs[i - 1]);
        }
        assertEquals(40L, g.offsetsMs[1]);
        assertEquals(120L, g.offsetsMs[2]);
        assertEquals(250L, g.durationMs);
    }

    @Test
    public void aVeryShortGestureIsClampedToTheMinimumDuration() {
        // Android drops strokes that are too short to be a real touch, so a
        // 2 ms tap has to be stretched.
        TouchInjector in = injector();
        TouchInjector.Gesture g = gesture(in, new int[][] { { 10, 10 }, { 10, 10 } },
                new long[] { 0L, 2L });

        assertNotNull(g);
        assertTrue(g.isTap());
        assertEquals(TouchInjector.Policy.defaults().minDurationMs, g.durationMs);
    }

    @Test
    public void anAbsurdlyLongGestureIsClampedToTheMaximum() {
        TouchInjector in = injector();
        TouchInjector.Gesture g = gesture(in,
                new int[][] { { 0, 0 }, { 500, 0 } }, new long[] { 0L, 120_000L });

        assertNotNull(g);
        assertEquals(TouchInjector.Policy.defaults().maxDurationMs, g.durationMs);
    }

    @Test
    public void aStalledClockDoesNotProduceNegativeTime() {
        // Timestamps come from another device's clock, which can repeat or go
        // backwards if the guest's uptime resets mid-session.
        TouchInjector in = injector();
        TouchInjector.Gesture g = gesture(in,
                new int[][] { { 0, 0 }, { 200, 0 }, { 400, 0 } },
                new long[] { 9000L, 9000L, 8000L });

        assertNotNull(g);
        assertTrue(g.durationMs >= 0);
        for (long offset : g.offsetsMs) {
            assertTrue("offset must not be negative: " + offset, offset >= 0L);
        }
    }

    // ------------------------------------------------------------------
    // point reduction
    // ------------------------------------------------------------------

    @Test
    public void noisePointsCloserThanTheMinimumSegmentAreMerged() {
        // A finger resting on the screen emits ~60 MOVE events a second. All of them
        // in the stroke would be waste, and most of them are 0-2 px apart.
        TouchInjector in = injector();
        int[][] points = new int[41][];
        long[] times = new long[41];
        points[0] = new int[] { 100, 100 };
        times[0] = 0L;
        for (int i = 1; i <= 39; i++) {
            points[i] = new int[] { 100 + (i % 2), 100 };   // 1 px jitter
            times[i] = i * 5L;
        }
        points[40] = new int[] { 400, 100 };                // then a real move
        times[40] = 400L;

        TouchInjector.Gesture g = gesture(in, points, times);

        assertNotNull(g);
        assertFalse(g.isTap());
        assertTrue("40 jitter points should collapse, got " + g.pointCount(),
                g.pointCount() <= 4);
        assertEquals(400, g.endX());
    }

    @Test
    public void aLongDragIsReducedToThePointCapKeepingBothEnds() {
        TouchInjector in = injector();
        int n = 600;
        int[][] points = new int[n][];
        long[] times = new long[n];
        for (int i = 0; i < n; i++) {
            // 8 px per sample: above minSegmentPx (6), so every point is a real move.
            points[i] = new int[] { 0, i * 8 };
            times[i] = i * 10L;
        }

        TouchInjector.Gesture g = gesture(in, points, times);

        assertNotNull(g);
        assertEquals(TouchInjector.Policy.defaults().maxPoints, g.pointCount());
        assertEquals("the start must be exact", 0, g.startY());
        assertEquals("the end must be exact", (n - 1) * 8, g.endY());
    }

    // ------------------------------------------------------------------
    // stream robustness
    // ------------------------------------------------------------------

    @Test
    public void aMoveWithoutADownStartsWithThatMove() {
        // The guest reconnected mid-drag, or the DOWN frame was lost. Dropping the
        // rest of the drag would be worse than starting from where we joined.
        TouchInjector in = injector();
        assertNull(in.onTouch(TouchInjector.ACTION_MOVE, 10, 10, 0L));
        assertTrue(in.isTracking());
        TouchInjector.Gesture g = in.onTouch(TouchInjector.ACTION_UP, 300, 10, 60L);
        assertNotNull(g);
        assertFalse(g.isTap());
        assertEquals(10, g.startX());
        assertEquals(300, g.endX());
    }

    @Test
    public void anUpWithoutADownProducesNothing() {
        TouchInjector in = injector();
        assertNull(in.onTouch(TouchInjector.ACTION_UP, 10, 10, 0L));
    }

    @Test
    public void cancelDropsTheGesture() {
        TouchInjector in = injector();
        assertNull(in.onTouch(TouchInjector.ACTION_DOWN, 10, 10, 0L));
        assertNull(in.onTouch(TouchInjector.ACTION_MOVE, 100, 10, 30L));
        assertNull(in.onTouch(TouchInjector.ACTION_CANCEL, 100, 10, 40L));
        assertFalse(in.isTracking());
        assertNull("a cancelled gesture must not be injected later",
                in.onTouch(TouchInjector.ACTION_UP, 120, 10, 50L));
    }

    @Test
    public void resetThrowsAwayAMidDragGesture() {
        // What the capture service calls when a session stops: injecting the tail of
        // a drag after teardown would tap whatever app the user opened next.
        TouchInjector in = injector();
        assertNull(in.onTouch(TouchInjector.ACTION_DOWN, 10, 10, 0L));
        assertNull(in.onTouch(TouchInjector.ACTION_MOVE, 200, 10, 50L));
        in.reset();
        assertFalse(in.isTracking());
        assertNull(in.onTouch(TouchInjector.ACTION_UP, 200, 10, 60L));
    }

    @Test
    public void consecutiveGesturesDoNotLeakIntoEachOther() {
        TouchInjector in = injector();

        TouchInjector.Gesture first = gesture(in, new int[][] { { 10, 10 }, { 10, 10 } },
                new long[] { 0L, 50L });
        assertNotNull(first);
        assertTrue(first.isTap());

        TouchInjector.Gesture second = gesture(in,
                new int[][] { { 50, 50 }, { 400, 50 } }, new long[] { 100L, 200L });
        assertNotNull(second);
        assertFalse(second.isTap());
        assertEquals("the second gesture must start at its own DOWN", 50, second.startX());
        assertEquals(2, second.pointCount());
    }

    @Test
    public void unknownActionsAreIgnored() {
        TouchInjector in = injector();
        assertNull(in.onTouch(99, 5, 5, 0L));
        assertFalse(in.isTracking());
    }

    // ------------------------------------------------------------------
    // coordinate mapping
    // ------------------------------------------------------------------

    @Test
    public void normalizedCoordinatesMapToTheRealScreenNotTheCaptureSize() {
        // The guest normalises against what it sees - the whole host screen, even
        // when we stream a scaled-down copy of it. So the middle of the guest's view
        // is the middle of the host panel, whatever the capture resolution is.
        assertEquals(0, TouchInjector.toPixels(0L, SCREEN_W));
        assertEquals(540, TouchInjector.toPixels(5000L, SCREEN_W));
        assertEquals(1200, TouchInjector.toPixels(5000L, SCREEN_H));
        assertEquals(1079, TouchInjector.toPixels(10000L, SCREEN_W));
        assertEquals(2399, TouchInjector.toPixels(10000L, SCREEN_H));
    }

    @Test
    public void outOfRangeCoordinatesAreClampedOntoTheScreen() {
        // A guest with a different aspect ratio can report 0.9999 of a wider screen;
        // injecting at x = width would be off-panel, where nothing receives it.
        assertEquals(0, TouchInjector.toPixels(-500L, SCREEN_W));
        assertEquals(SCREEN_W - 1, TouchInjector.toPixels(99999L, SCREEN_W));
    }

    @Test
    public void aZeroSizeScreenDoesNotDivideByZero() {
        assertEquals(0, TouchInjector.toPixels(5000L, 0));
        assertEquals(0, TouchInjector.toPixels(5000L, -10));
    }

    @Test
    public void mappingIsMonotonic() {
        int previous = -1;
        for (long n = 0; n <= 10000; n += 250) {
            int px = TouchInjector.toPixels(n, SCREEN_W);
            assertTrue("mapping went backwards at " + n, px >= previous);
            previous = px;
        }
    }

    // ------------------------------------------------------------------
    // the gesture a real drag of the guest produces
    // ------------------------------------------------------------------

    @Test
    public void aScrollGestureCarriesTheWholePath() {
        // The full round trip: the guest drags a finger up the view, the host
        // receives normalised coordinates, and this is the gesture that gets injected.
        TouchInjector in = injector();
        int[][] points = new int[6][];
        long[] times = new long[6];
        // 0.50 -> 0.30 of the screen height, sampled every 20 ms.
        long[] normalizedY = { 5000, 4600, 4200, 3800, 3400, 3000 };
        for (int i = 0; i < points.length; i++) {
            points[i] = new int[] {
                    TouchInjector.toPixels(5000, SCREEN_W),
                    TouchInjector.toPixels(normalizedY[i], SCREEN_H),
            };
            times[i] = i * 20L;
        }

        TouchInjector.Gesture g = gesture(in, points, times);

        assertNotNull(g);
        assertFalse(g.isTap());
        assertEquals("start at 50% of the screen height", 1200, g.startY());
        assertEquals("end at 30%", 720, g.endY());
        assertEquals(100L, g.durationMs);
        assertTrue(g.pathLengthPx() >= 480f);
        assertTrue(g.toString().contains("drag"));
    }
}
