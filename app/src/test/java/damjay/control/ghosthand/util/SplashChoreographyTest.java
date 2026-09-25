package damjay.control.ghosthand.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The splash timeline, checked without a screen.
 *
 * <p>These are the properties that a screenshot cannot tell you: that the loop has no
 * seam, that the hand is actually on the glass when the ripples are born, that every
 * ripple is finished before the cycle restarts, and that the hand is clear of the glass
 * when it starts descending again. Each of them has been broken at least once by moving a
 * constant by a few dozen milliseconds.
 */
public class SplashChoreographyTest {

    private static final float EPS = 1e-4f;

    @Test
    public void phaseWrapsAndNeverGoesNegative() {
        assertEquals(0L, SplashChoreography.phase(0));
        assertEquals(500L, SplashChoreography.phase(500));
        assertEquals(100L, SplashChoreography.phase(SplashChoreography.LOOP_MS + 100L));
        assertEquals(SplashChoreography.LOOP_MS - 1L,
                SplashChoreography.phase(5L * SplashChoreography.LOOP_MS - 1L));
        // A clock that has been running for an hour is still in range.
        assertEquals(0L, SplashChoreography.phase(3600L * SplashChoreography.LOOP_MS));
    }

    @Test
    public void handDescendsMonotonicallyThenRestsOnTheGlass() {
        float previous = Float.MAX_VALUE;
        for (long t = 0; t < SplashChoreography.CONTACT_MS; t += 10) {
            float offset = SplashChoreography.handOffset(t);
            assertTrue("hand must get lower over time at t=" + t, offset < previous);
            assertTrue("hand must stay above the glass: " + offset, offset >= 0f);
            previous = offset;
        }
        assertEquals(SplashChoreography.RAISED_FRACTION,
                SplashChoreography.handOffset(0), EPS);
        assertEquals(0f, SplashChoreography.handOffset(SplashChoreography.CONTACT_MS), EPS);
    }

    @Test
    public void theFingerIsOnTheGlassWhileTheRipplesSpread() {
        long contact = SplashChoreography.CONTACT_MS;
        assertFalse(SplashChoreography.isTouching(contact - 1));
        assertTrue(SplashChoreography.isTouching(contact));
        assertTrue(SplashChoreography.isTouching(SplashChoreography.LIFT_START_MS - 1));
        assertFalse(SplashChoreography.isTouching(SplashChoreography.LIFT_START_MS));
        // While touching, the hand is at the glass or pressed very slightly into it.
        for (long t = contact; t < SplashChoreography.LIFT_START_MS; t += 10) {
            float offset = SplashChoreography.handOffset(t);
            assertTrue("hand must be at the glass, not above it: " + offset, offset <= 0f);
            assertTrue("press must stay subtle: " + offset,
                    offset >= -SplashChoreography.PRESS_FRACTION - EPS);
        }
    }

    @Test
    public void everyRippleIsBornAfterContactAndFinishesWithinTheLoop() {
        for (int i = 0; i < SplashChoreography.RIPPLE_COUNT; i++) {
            long spawn = SplashChoreography.rippleSpawnMs(i);
            assertTrue("ripple " + i + " must follow the tap", spawn >= SplashChoreography.CONTACT_MS);
            // Ripples are staggered, so the newest is always the widest... the *smallest*.
            if (i > 0) {
                assertEquals(SplashChoreography.RIPPLE_STAGGER_MS,
                        spawn - SplashChoreography.rippleSpawnMs(i - 1));
            }
            // Before it exists there is nothing to draw...
            assertTrue(SplashChoreography.rippleProgress(spawn - 1, i) < 0f);
            assertEquals(0f, SplashChoreography.rippleAlpha(spawn - 1, i), EPS);
            assertEquals(0f, SplashChoreography.rippleWidthFraction(spawn - 1, i), EPS);
            // ...and by the time the hand comes back up, it is gone.
            float atLift = SplashChoreography.rippleProgress(SplashChoreography.LIFT_END_MS, i);
            assertEquals("ripple " + i + " must be finished when the hand is up",
                    1.0f, atLift, EPS);
            assertEquals(0f, SplashChoreography.rippleAlpha(SplashChoreography.LIFT_END_MS, i), EPS);
        }
    }

    @Test
    public void ripplesGrowAndFadeMonotonically() {
        int index = 1;
        long spawn = SplashChoreography.rippleSpawnMs(index);
        float previousWidth = -1f;
        float previousAlpha = Float.MAX_VALUE;
        for (long t = spawn; t <= SplashChoreography.LIFT_END_MS; t += 10) {
            float width = SplashChoreography.rippleWidthFraction(t, index);
            float alpha = SplashChoreography.rippleAlpha(t, index);
            assertTrue("ripples only ever widen, at t=" + t, width >= previousWidth - EPS);
            assertTrue("ripples only ever fade, at t=" + t, alpha <= previousAlpha + EPS);
            assertTrue("a ripple stays inside the view", width <= SplashChoreography.RIPPLE_MAX_WIDTH_FRACTION + EPS);
            previousWidth = width;
            previousAlpha = alpha;
        }
        // The newest ripple is the widest... no: the oldest is. The stagger means
        // ripple 0 is always at least as far along as ripple 1, which is what makes a
        // train of ripples rather than three overlapping ones.
        for (long t = SplashChoreography.CONTACT_MS + 100; t < SplashChoreography.LIFT_END_MS; t += 20) {
            assertTrue(SplashChoreography.rippleWidthFraction(t, 0)
                    >= SplashChoreography.rippleWidthFraction(t, 1) - EPS);
            assertTrue(SplashChoreography.rippleWidthFraction(t, 1)
                    >= SplashChoreography.rippleWidthFraction(t, 2) - EPS);
        }
    }

    @Test
    public void outOfRangeRipplesAreSimplyAbsent() {
        assertEquals(-1f, SplashChoreography.rippleProgress(500, -1), EPS);
        assertEquals(-1f, SplashChoreography.rippleProgress(500, SplashChoreography.RIPPLE_COUNT), EPS);
        assertEquals(0f, SplashChoreography.rippleAlpha(500, -1), EPS);
        assertEquals(0f, SplashChoreography.rippleAlpha(500, 99), EPS);
    }

    @Test
    public void theLoopIsSeamless() {
        // The whole animation is a function of the clock modulo the loop, so a second
        // later must look exactly like the first - that is what lets it repeat forever.
        for (long t = 0; t < SplashChoreography.LOOP_MS; t += 7) {
            long later = t + SplashChoreography.LOOP_MS;
            assertEquals(SplashChoreography.handOffset(t),
                    SplashChoreography.handOffset(later), EPS);
            assertEquals(SplashChoreography.reflectionAlpha(t),
                    SplashChoreography.reflectionAlpha(later), EPS);
            for (int i = 0; i < SplashChoreography.RIPPLE_COUNT; i++) {
                assertEquals(SplashChoreography.rippleWidthFraction(t, i),
                        SplashChoreography.rippleWidthFraction(later, i), EPS);
                assertEquals(SplashChoreography.rippleAlpha(t, i),
                        SplashChoreography.rippleAlpha(later, i), EPS);
            }
        }
    }

    @Test
    public void theHandIsFullyRaisedBeforeItDescendsAgain() {
        // The last stretch of the loop is quiet: no ripple, hand up. Without it the next
        // descent would begin from wherever the lift happened to stop.
        long t = SplashChoreography.LIFT_END_MS;
        assertEquals(SplashChoreography.RAISED_FRACTION, SplashChoreography.handOffset(t), EPS);
        assertEquals(0f, SplashChoreography.reflectionAlpha(t), EPS);
        for (long rest = SplashChoreography.LIFT_END_MS; rest < SplashChoreography.LOOP_MS; rest += 10) {
            assertEquals(SplashChoreography.RAISED_FRACTION,
                    SplashChoreography.handOffset(rest), EPS);
        }
    }

    @Test
    public void reflectionFollowsTheHandDown() {
        assertEquals(0f, SplashChoreography.reflectionAlpha(0), EPS);
        assertEquals(SplashChoreography.REFLECTION_ALPHA,
                SplashChoreography.reflectionAlpha(SplashChoreography.CONTACT_MS), EPS);
        assertEquals(SplashChoreography.REFLECTION_ALPHA,
                SplashChoreography.reflectionAlpha(SplashChoreography.LIFT_START_MS - 1), EPS);
        assertEquals(0f, SplashChoreography.reflectionAlpha(SplashChoreography.LIFT_END_MS), EPS);
    }

    @Test
    public void theTravellingWaveIsStillBeforeTheTapAndQuietFarAway() {
        assertEquals(0f, SplashChoreography.travellingWave(0.2f,
                SplashChoreography.CONTACT_MS - 1, 0f), EPS);
        // Far from the fingertip the glass barely moves, however long we wait.
        for (long t = SplashChoreography.CONTACT_MS; t < 2 * SplashChoreography.LOOP_MS; t += 33) {
            float far = Math.abs(SplashChoreography.travellingWave(0.48f, t, 0f));
            assertTrue("the far edge must stay still: " + far, far < 0.2f);
            assertTrue(Math.abs(SplashChoreography.travellingWave(0.5f, t, 1.5f)) <= 1.0f);
        }
    }
}
