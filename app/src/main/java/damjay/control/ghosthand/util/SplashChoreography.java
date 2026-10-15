package damjay.control.ghosthand.util;

/**
 * The splash animation's timeline, as arithmetic.
 *
 * <p><b>Why this class is separate from the view.</b> The animation is a loop with one
 * hand, one glass line and three ripples, and "does it look right" is a question only a
 * screen can answer. But "does the hand touch the glass at the same instant the first
 * ripple is born", "does every ripple finish before the loop restarts", "is the hand
 * fully raised again before the next pass" - those are ordinary arithmetic, and they are
 * exactly what breaks when someone nudges a constant. So the numbers live here, in plain
 * Java with no {@code android.*} import at all, and {@code SplashChoreographyTest}
 * checks them on the JVM. The view draws whatever this returns.
 *
 * <p><b>The loop.</b> One cycle is {@link #LOOP_MS} milliseconds:
 *
 * <pre>
 *   0                    300      760        1000      1200
 *   |── hand descends ───|        |          |         |
 *                        |─ press ─|         |         |
 *                        |─── ripples spread ─────|    |
 *                                  |── hand lifts ──|  |
 *                                                    |─ rest ─|
 * </pre>
 *
 * The hand starts above the glass, comes down, presses it (a small extra dip, so the
 * contact reads as contact rather than as arriving), the sheet ripples outward from the
 * fingertip, and the hand rises again. Then the loop repeats - which is the whole point:
 * there is no end state, only the next tap.
 *
 * <p>Everything is a function of {@code elapsedMs} alone, so the view only needs a frame
 * clock; there is no accumulated state to get out of step with the animation. That also
 * makes {@code elapsed + LOOP_MS} produce identical frames by construction, which the
 * test asserts.
 */
public final class SplashChoreography {

    /** Length of one full cycle, in milliseconds. */
    public static final long LOOP_MS = 1200L;

    /** When the fingertip meets the glass. Ripples are born from here. */
    public static final long CONTACT_MS = 300L;

    /** How long the press lasts - the fingertip settles, then relaxes. */
    public static final long PRESS_MS = 80L;

    /** When the hand starts rising again. */
    public static final long LIFT_START_MS = 760L;

    /** When the hand is fully raised and the cycle goes quiet. */
    public static final long LIFT_END_MS = 1000L;

    /** How far above its resting place the hand starts, as a fraction of the glass
     *  line's distance from the top of the view. */
    public static final float RAISED_FRACTION = 0.14f;

    /** How far the hand pushes past first contact, as a fraction of the same distance. */
    public static final float PRESS_FRACTION = 0.012f;

    /** Ripples are born at contact and every {@code RIPPLE_STAGGER_MS} after it. */
    public static final int RIPPLE_COUNT = 3;
    public static final long RIPPLE_STAGGER_MS = 110L;

    /** How long one ripple takes to expand and fade away. */
    public static final long RIPPLE_LIFE_MS = 650L;

    /** A ripple's widest radius, as a fraction of the view's width. */
    public static final float RIPPLE_MAX_WIDTH_FRACTION = 0.50f;

    /**
     * How flat a ripple is: its vertical radius over its horizontal one. The ripples run
     * along the surface of the glass, and the surface is nearly edge-on, so they are
     * ellipses - this is the "perspective" that makes them lie on the line instead of
     * floating on the screen.
     */
    public static final float RIPPLE_FLATTEN = 0.13f;

    /** Peak brightness of a ripple the moment it is born. */
    public static final float RIPPLE_ALPHA = 0.75f;

    /** Alpha of the reflected hand when the finger is down. */
    public static final float REFLECTION_ALPHA = 0.30f;

    /** Height of the glass flexing under the fingertip, as a fraction of the width. */
    public static final float WAVE_AMPLITUDE_FRACTION = 0.012f;

    private SplashChoreography() {
        // static utility
    }

    /** Where we are in the current cycle, always in {@code [0, LOOP_MS)}. */
    public static long phase(long elapsedMs) {
        long t = elapsedMs % LOOP_MS;
        return t < 0 ? t + LOOP_MS : t;
    }

    /**
     * How far above its pressed position the hand is, in the same units the view uses
     * for the finger-to-glass distance: 1.0 at the top of the loop, 0.0 while the finger
     * is on the glass, and slightly negative during the press.
     */
    public static float handOffset(long elapsedMs) {
        long t = phase(elapsedMs);

        if (t < CONTACT_MS) {
            // Descending. Ease-in-cubic: slow at the top of the arc, quick at the glass,
            // which is how a hand actually arrives instead of moving at a constant speed.
            float p = t / (float) CONTACT_MS;
            float eased = p * p * p;
            return RAISED_FRACTION * (1.0f - eased);
        }
        if (t < CONTACT_MS + PRESS_MS) {
            // Pressing: the fingertip compresses very slightly against the surface.
            float p = (t - CONTACT_MS) / (float) PRESS_MS;
            return -PRESS_FRACTION * wave(p);
        }
        if (t < LIFT_START_MS) {
            return 0.0f;   // resting on the glass while the ripples spread
        }
        if (t < LIFT_END_MS) {
            float p = (t - LIFT_START_MS) / (float) (LIFT_END_MS - LIFT_START_MS);
            return RAISED_FRACTION * (p * p);   // ease-out as it lifts away
        }
        return RAISED_FRACTION;   // fully raised, waiting to tap again
    }

    /** {@code sin(pi * x)} for x in [0,1] - one smooth 0 -> 1 -> 0 hump. */
    private static float wave(float x) {
        return (float) Math.sin(Math.PI * Math.min(1.0f, Math.max(0.0f, x)));
    }

    /**
     * Progress of ripple {@code index} in the current cycle: 0.0 the instant it is born,
     * 1.0 when it has fully expanded and faded, and negative before it exists.
     */
    public static float rippleProgress(long elapsedMs, int index) {
        if (index < 0 || index >= RIPPLE_COUNT) {
            return -1.0f;
        }
        long life = loopRippleLife(index);
        float p = (phase(elapsedMs) - spawnMs(index)) / (float) life;
        return p < 0f ? -1f : Math.min(1f, p);
    }

    /**
     * A ripple's life, stretched when the loop would otherwise cut it off.
     *
     * <p>With the constants above the last ripple would still be growing when the hand
     * came back up. Rather than let it be clipped mid-flight - which looks like a bug -
     * every ripple's life is the shorter of {@link #RIPPLE_LIFE_MS} and the time actually
     * left before the cycle ends. Small, but it is the difference between a loop that
     * restarts cleanly and one that stutters.
     */
    private static long loopRippleLife(int index) {
        // Capped by the lift, not by the end of the cycle: the ripples belong to the tap,
        // so they should be over by the time the hand is up and the screen is quiet again.
        long left = LIFT_END_MS - spawnMs(index);
        return Math.max(1L, Math.min(RIPPLE_LIFE_MS, left));
    }

    private static long spawnMs(int index) {
        return CONTACT_MS + index * RIPPLE_STAGGER_MS;
    }

    /** When ripple {@code index} is born, in the current cycle. */
    public static long rippleSpawnMs(int index) {
        return spawnMs(index);
    }

    /** How bright ripple {@code index} is right now: 0 when it does not exist. */
    public static float rippleAlpha(long elapsedMs, int index) {
        float p = rippleProgress(elapsedMs, index);
        if (p < 0f) {
            return 0f;
        }
        // Fade away as it grows, so the newest ripple is always the brightest.
        return RIPPLE_ALPHA * (1.0f - p * p);
    }

    /**
     * How wide ripple {@code index} is right now, as a fraction of the view's width.
     *
     * <p>Uses a square root rather than a straight line: a ripple on water slows as it
     * spreads, so most of the growth happens early. A linear radius looks mechanical.
     */
    public static float rippleWidthFraction(long elapsedMs, int index) {
        float p = rippleProgress(elapsedMs, index);
        if (p < 0f) {
            return 0f;
        }
        return RIPPLE_MAX_WIDTH_FRACTION * (float) Math.sqrt(p);
    }

    /** How visible the reflection under the glass is: it fades in with the hand. */
    public static float reflectionAlpha(long elapsedMs) {
        float offset = handOffset(elapsedMs);
        // 1.0 when the hand is down, ~0.0 when it is fully raised.
        float presence = 1.0f - Math.min(1.0f, Math.max(0.0f, offset / RAISED_FRACTION));
        return REFLECTION_ALPHA * presence;
    }

    /**
     * A travelling wave along the glass, for the frame that follows a tap: {@code sin} of
     * distance minus time, decaying with distance.
     *
     * @param distanceFraction distance from the fingertip, as a fraction of the width
     * @param elapsedMs        the animation clock
     * @param phaseOffset      shifts one ripple's wave relative to the next
     * @return a value in {@code [-1, 1]} to bend the glass line by
     */
    public static float travellingWave(float distanceFraction, long elapsedMs,
                                       float phaseOffset) {
        long t = phase(elapsedMs);
        if (t < CONTACT_MS) {
            return 0f;
        }
        float since = (t - CONTACT_MS) / 1000.0f;          // seconds since the tap
        float phaseArg = distanceFraction * 12.0f - since * 9.0f + phaseOffset;
        // Decay with distance and with time, so the glass settles instead of ringing.
        float decay = (float) Math.exp(-distanceFraction * 4.5f) * (float) Math.exp(-since * 1.6f);
        return (float) Math.sin(phaseArg) * decay;
    }

    /** True while the fingertip is touching the glass. */
    public static boolean isTouching(long elapsedMs) {
        long t = phase(elapsedMs);
        return t >= CONTACT_MS && t < LIFT_START_MS;
    }
}
