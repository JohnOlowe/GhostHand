package damjay.control.ghosthand.host;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a stream of guest touch events into a <em>gesture plan</em> the host can
 * execute. Pure logic: no {@code android.*} anywhere, which is why it is the part of
 * touch injection that has unit tests (see {@code TouchInjectorTest}).
 *
 * <h2>Why a plan and not a live finger</h2>
 *
 * The guest sends a stream: DOWN, MOVE, MOVE, …, UP. Android's public injection API
 * ({@code AccessibilityService.dispatchGesture}) does not accept a stream - a
 * {@code GestureDescription.StrokeDescription} is a <em>complete</em> stroke: a path,
 * a start delay and a duration, dispatched as one unit. There is no "extend the
 * gesture that is currently running" call.
 *
 * <p>So a touch becomes one of two things:
 * <ul>
 *   <li><b>a tap</b> - one point, held for as long as the guest's finger was down;</li>
 *   <li><b>a drag</b> - the sampled polyline, replayed with the original timing.</li>
 * </ul>
 *
 * The consequence, stated plainly because it is the honest limitation of a no-root
 * injector: a drag is dispatched when the finger <em>lifts</em>, not while it moves,
 * so the host screen reacts a moment after the finger. Taps - the common case for
 * menus and buttons - are immediate. A rooted {@code input} shell helper could stream
 * a touch properly; that is the price of needing no root.
 *
 * <h2>Coordinates</h2>
 *
 * The guest normalises by its view size, which shows the host's <em>screen</em> - not
 * the scaled-down capture. So the host multiplies by the real display size (see
 * {@link #toPixels}), not by the video size, and rotation needs no special case
 * because the real display metrics already follow the current orientation.
 */
public final class TouchInjector {

    // Wire actions, matching GhostProtocol's TOUCH record (and MotionEvent's order).
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;
    /** Two pointers went down together (both coordinates are in the record). */
    public static final int ACTION_DOWN2 = 4;
    /** Both pointers moved. */
    public static final int ACTION_MOVE2 = 5;
    /** The multi segment ended - the host lifts BOTH pointers on this event. */
    public static final int ACTION_UP2 = 6;

    /** Tunables. The defaults are chosen to feel like a finger, not a stylus. */
    public static final class Policy {
        /** Movement under this many host pixels still counts as "never moved". */
        public final float tapSlopPx;
        /** Points closer than this to the previous one are dropped as noise. */
        public final float minSegmentPx;
        /** Upper bound on points per stroke: a long drag must not send thousands. */
        public final int maxPoints;
        /** Stroke duration clamp: too short is not delivered, too long feels stuck. */
        public final long minDurationMs;
        public final long maxDurationMs;

        public Policy(float tapSlopPx, float minSegmentPx, int maxPoints,
                      long minDurationMs, long maxDurationMs) {
            this.tapSlopPx = tapSlopPx;
            this.minSegmentPx = minSegmentPx;
            this.maxPoints = maxPoints;
            this.minDurationMs = minDurationMs;
            this.maxDurationMs = maxDurationMs;
        }

        public static Policy defaults() {
            // 12 px of slop: a finger on a 1080p panel wobbles a few pixels while
            // tapping, and a jittery tap that registers as a 20 px drag becomes a
            // scroll rather than a click.
            return new Policy(12f, 6f, 64, 40L, 30_000L);
        }
    }

    /** A finished gesture, ready to be handed to {@code dispatchGesture}. */
    public static final class Gesture {
        /** Point coordinates in host pixels. */
        public final int[] xs;
        public final int[] ys;
        /** Per-point offset from the start of the stroke, in milliseconds. */
        public final long[] offsetsMs;
        /** Total stroke duration; always within the policy's clamp. */
        public final long durationMs;
        /** Second finger (pinch); null for the ordinary one-finger gesture. */
        public final int[] xs2;
        public final int[] ys2;
        public final long[] offsets2Ms;

        Gesture(int[] xs, int[] ys, long[] offsetsMs, long durationMs) {
            this(xs, ys, offsetsMs, durationMs, null, null, null);
        }

        Gesture(int[] xs, int[] ys, long[] offsetsMs, long durationMs,
                int[] xs2, int[] ys2, long[] offsets2Ms) {
            this.xs = xs;
            this.ys = ys;
            this.offsetsMs = offsetsMs;
            this.durationMs = durationMs;
            this.xs2 = xs2;
            this.ys2 = ys2;
            this.offsets2Ms = offsets2Ms;
        }

        /** True when a second finger's path rides along (pinch-to-zoom). */
        public boolean hasSecondPointer() {
            return xs2 != null && xs2.length > 0;
        }

        public int pointCount() {
            return xs.length;
        }

        /** A single-point stroke: the guest tapped (or long-pressed). */
        public boolean isTap() {
            return xs.length == 1;
        }

        public int startX() {
            return xs[0];
        }

        public int startY() {
            return ys[0];
        }

        public int endX() {
            return xs[xs.length - 1];
        }

        public int endY() {
            return ys[ys.length - 1];
        }

        /** Total distance the finger covered, in pixels. */
        public float pathLengthPx() {
            float total = 0f;
            for (int i = 1; i < xs.length; i++) {
                float dx = xs[i] - xs[i - 1];
                float dy = ys[i] - ys[i - 1];
                total += (float) Math.sqrt(dx * dx + dy * dy);
            }
            return total;
        }

        @Override
        public String toString() {
            return (isTap() ? "tap" : "drag " + xs.length + "pt")
                    + " (" + startX() + "," + startY() + ")"
                    + (isTap() ? "" : " -> (" + endX() + "," + endY() + ")")
                    + (hasSecondPointer() ? " +2nd finger" : "")
                    + " " + durationMs + "ms";
        }
    }

    private final Policy policy;

    /** Accumulated points, each {x, y, timestampMs}. */
    private final List<float[]> trail = new ArrayList<>();
    private boolean tracking;

    /**
     * Where (and when) the finger went down.
     *
     * <p>Kept separately from the trail because the trail is lossy on purpose:
     * sub-{@link Policy#minSegmentPx} movements are folded into the previous point,
     * which would otherwise let a tap's recorded position creep across the screen a
     * few pixels at a time - and, worse, would make a press-and-hold look
     * instantaneous, because its only point ends up carrying the UP timestamp. A tap
     * must inject exactly where the finger landed, for exactly as long as it stayed.
     */
    private int anchorX;
    private int anchorY;
    private long anchorTimeMs;

    /** Second finger: same shape as the first, alive only between 4 and 6. */
    private final List<float[]> trail2 = new ArrayList<>();
    private boolean tracking2;
    private int anchor2X;
    private int anchor2Y;
    private long anchor2TimeMs;

    public TouchInjector() {
        this(Policy.defaults());
    }

    public TouchInjector(Policy policy) {
        this.policy = policy;
    }

    public Policy policy() {
        return policy;
    }

    public boolean isTracking() {
        return tracking;
    }

    /**
     * Feeds one guest touch event in.
     *
     * @param action {@link #ACTION_DOWN}, {@link #ACTION_UP}, {@link #ACTION_MOVE} or
     *               {@link #ACTION_CANCEL}
     * @param xPx    host-screen X in pixels (see {@link #toPixels})
     * @param yPx    host-screen Y in pixels
     * @param timeMs a monotonic timestamp from the guest
     * @return the finished {@link Gesture} when the touch is complete, otherwise null
     */
    public Gesture onTouch(int action, int xPx, int yPx, long timeMs) {
        return onTouch(action, xPx, yPx, -1, -1, timeMs);
    }

    /**
     * Feeds one guest touch event in, one or two pointers ({@code x2Px < 0} =
     * single). The two-pointer set (4/5/6) keeps a parallel trail for the
     * second finger; the finished {@link Gesture} then carries both paths and
     * the accessibility side dispatches them as concurrent strokes - one
     * gesture, two fingers, which is how a pinch-to-zoom replays on the
     * no-grant path.
     */
    public Gesture onTouch(int action, int xPx, int yPx, int x2Px, int y2Px, long timeMs) {
        switch (action) {
            case ACTION_DOWN:
                startTrail(trail, xPx, yPx, timeMs);
                anchorX = xPx;
                anchorY = yPx;
                anchorTimeMs = timeMs;
                tracking = true;
                dropSecond(); // a fresh single stream supersedes any stale multi
                return null;

            case ACTION_MOVE:
                if (!tracking) {
                    // A MOVE with no DOWN means we joined mid-gesture (the guest
                    // reconnected, or a DOWN frame was lost). Start tracking here so
                    // the rest of the drag is not silently thrown away.
                    startTrail(trail, xPx, yPx, timeMs);
                    anchorX = xPx;
                    anchorY = yPx;
                    anchorTimeMs = timeMs;
                    tracking = true;
                    return null;
                }
                appendIfSignificant(trail, xPx, yPx, timeMs);
                return null;

            case ACTION_DOWN2:
                startTrail(trail, xPx, yPx, timeMs);
                anchorX = xPx;
                anchorY = yPx;
                anchorTimeMs = timeMs;
                tracking = true;
                startTrail(trail2, x2Px, y2Px, timeMs);
                anchor2X = x2Px;
                anchor2Y = y2Px;
                anchor2TimeMs = timeMs;
                tracking2 = true;
                return null;

            case ACTION_MOVE2:
                if (!tracking2) {
                    // Joined the pinch mid-gesture: start both trails here.
                    startTrail(trail, xPx, yPx, timeMs);
                    anchorX = xPx;
                    anchorY = yPx;
                    anchorTimeMs = timeMs;
                    tracking = true;
                    startTrail(trail2, x2Px, y2Px, timeMs);
                    anchor2X = x2Px;
                    anchor2Y = y2Px;
                    anchor2TimeMs = timeMs;
                    tracking2 = true;
                    return null;
                }
                appendIfSignificant(trail, xPx, yPx, timeMs);
                appendIfSignificant(trail2, x2Px, y2Px, timeMs);
                return null;

            case ACTION_UP2:
                if (!tracking2) {
                    return null;
                }
                appendIfSignificant(trail, xPx, yPx, timeMs);
                appendIfSignificant(trail2, x2Px, y2Px, timeMs);
                tracking = false;
                tracking2 = false;
                Gesture both = buildTwo(timeMs);
                trail.clear();
                trail2.clear();
                return both;

            case ACTION_CANCEL:
                trail.clear();
                tracking = false;
                dropSecond();
                return null;

            case ACTION_UP:
                if (!tracking) {
                    dropSecond();
                    return null;
                }
                appendIfSignificant(trail, xPx, yPx, timeMs);
                tracking = false;
                dropSecond(); // a single UP never carries a second finger
                Gesture gesture = build(timeMs);
                trail.clear();
                return gesture;

            default:
                return null;
        }
    }

    /** Drops a partial gesture (the guest disconnected mid-drag). */
    public void reset() {
        trail.clear();
        tracking = false;
        dropSecond();
    }

    private void dropSecond() {
        trail2.clear();
        tracking2 = false;
    }

    private static void startTrail(List<float[]> list, int x, int y, long timeMs) {
        list.clear();
        list.add(new float[] { x, y, timeMs });
    }

    // --------------------------------------------------------------------------
    // internals
    // --------------------------------------------------------------------------

    /**
     * Keeps the polyline honest: a finger resting still still generates 60 MOVE
     * events a second, and a stroke with 400 identical points is both a waste and a
     * shape Android's gesture engine mangles.
     */
    private void appendIfSignificant(List<float[]> list, int xPx, int yPx, long timeMs) {
        if (list.isEmpty()) {
            list.add(new float[] { xPx, yPx, timeMs });
            return;
        }
        float[] last = list.get(list.size() - 1);
        float dx = xPx - last[0];
        float dy = yPx - last[1];
        if (Math.sqrt(dx * dx + dy * dy) >= policy.minSegmentPx) {
            list.add(new float[] { xPx, yPx, timeMs });
            return;
        }
        // Too small to be its own point, so move the previous one to the finger's
        // latest position *and* time. Dragging the timestamp along keeps the pair
        // consistent: this point now means "the finger was here, this recently".
        last[0] = xPx;
        last[1] = yPx;
        last[2] = timeMs;
    }

    /**
     * The tap/drag decision, and it is deliberately just one question: did the finger
     * ever leave the slop radius? A 400 ms press that never moved is a long-press, so
     * it stays a single-point stroke - just a long one. A 15 px flick is a drag, even
     * though it was quick.
     */
    private Gesture build(long upTimeMs) {
        if (trail.isEmpty()) {
            return null;
        }
        // Timing is measured anchor-to-UP, never from the trail: the trail's first
        // point may have been folded forward by a wobble.
        long elapsed = clamp(Math.max(0L, upTimeMs - anchorTimeMs),
                policy.minDurationMs, policy.maxDurationMs);
        int[] xs;
        int[] ys;
        long[] offsets;
        if (allPointsWithinSlop(trail)) {
            // A tap injects at the landing point, the same way Android dispatches a
            // click at the ACTION_DOWN position rather than wherever the finger
            // happened to stop.
            xs = new int[] { anchorX };
            ys = new int[] { anchorY };
            offsets = new long[] { 0L };
        } else {
            List<float[]> points = reduceToCap(trail);
            xs = new int[points.size()];
            ys = new int[points.size()];
            offsets = new long[points.size()];
            for (int i = 0; i < points.size(); i++) {
                xs[i] = Math.round(points.get(i)[0]);
                ys[i] = Math.round(points.get(i)[1]);
                offsets[i] = Math.max(0L, (long) points.get(i)[2] - anchorTimeMs);
            }
        }
        return new Gesture(xs, ys, offsets, elapsed);
    }

    /** Two-finger finish: both trails become one concurrent two-stroke gesture. */
    private Gesture buildTwo(long upTimeMs) {
        Gesture first = build(upTimeMs);
        if (first == null) {
            return null;
        }
        if (trail2.isEmpty()) {
            return first;
        }
        int[] xs2;
        int[] ys2;
        long[] off2;
        if (allPointsWithinSlop(trail2)) {
            xs2 = new int[] { anchor2X };
            ys2 = new int[] { anchor2Y };
            off2 = new long[] { 0L };
        } else {
            List<float[]> points = reduceToCap(trail2);
            xs2 = new int[points.size()];
            ys2 = new int[points.size()];
            off2 = new long[points.size()];
            for (int i = 0; i < points.size(); i++) {
                xs2[i] = Math.round(points.get(i)[0]);
                ys2[i] = Math.round(points.get(i)[1]);
                off2[i] = Math.max(0L, (long) points.get(i)[2] - anchor2TimeMs);
            }
        }
        // One gesture, one duration: the strokes must cover the same window so
        // dispatchGesture runs them together. The longer of the two wins.
        long elapsed = first.durationMs;
        if (off2.length > 0 && off2[off2.length - 1] > elapsed) {
            elapsed = off2[off2.length - 1];
        }
        if (first.offsetsMs.length > 0
                && first.offsetsMs[first.offsetsMs.length - 1] > elapsed) {
            elapsed = first.offsetsMs[first.offsetsMs.length - 1];
        }
        return new Gesture(first.xs, first.ys, first.offsetsMs, elapsed,
                xs2, ys2, off2);
    }

    private boolean allPointsWithinSlop(List<float[]> points) {
        float x0 = points.get(0)[0];
        float y0 = points.get(0)[1];
        for (float[] p : points) {
            float dx = p[0] - x0;
            float dy = p[1] - y0;
            if (Math.sqrt(dx * dx + dy * dy) > policy.tapSlopPx) {
                return false;
            }
        }
        return true;
    }

    /**
     * Subsamples a long trail down to {@link Policy#maxPoints}, keeping the first and
     * last points exactly (they decide where the touch starts and ends) and spreading
     * the rest evenly.
     */
    private List<float[]> reduceToCap(List<float[]> points) {
        if (points.size() <= policy.maxPoints) {
            return new ArrayList<>(points);
        }
        List<float[]> out = new ArrayList<>(policy.maxPoints);
        float step = (points.size() - 1) / (float) (policy.maxPoints - 1);
        for (int i = 0; i < policy.maxPoints; i++) {
            out.add(points.get(Math.min(points.size() - 1, Math.round(i * step))));
        }
        return out;
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Normalised guest coordinate (0..10000) to a host pixel, clamped so a guest whose
     * aspect ratio differs cannot inject off-screen.
     *
     * @param spanPx the host display's width or height in pixels
     */
    public static int toPixels(long normalized, int spanPx) {
        if (spanPx <= 0) {
            return 0;
        }
        long scaled = normalized * spanPx / 10000L;
        if (scaled < 0L) {
            return 0;
        }
        if (scaled > spanPx - 1L) {
            return spanPx - 1;
        }
        return (int) scaled;
    }
}
