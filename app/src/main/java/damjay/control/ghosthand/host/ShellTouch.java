package damjay.control.ghosthand.host;

import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;

import rikka.shizuku.SystemServiceHelper;
import rikka.shizuku.ShizukuBinderWrapper;

/**
 * Streams raw {@link MotionEvent}s into the input system as the guest's finger
 * moves - scrolling that follows the finger instead of replaying when it lifts.
 *
 * <p>Why this exists: {@code AccessibilityService.dispatchGesture} only accepts a
 * <em>complete</em> stroke (see {@link TouchInjector}), which is why the
 * no-grant path must buffer a drag and inject it at ACTION_UP. The shell user
 * holds {@code android.permission.INJECT_EVENTS}, so with Shizuku granted we can
 * instead call {@code IInputManager.injectInputEvent} for every event the moment
 * it arrives - exactly how a real finger behaves.
 *
 * <p>Two fingers are injected the same way: wire actions 4/5/6 arrive with both
 * pointers and become ACTION_POINTER_DOWN / ACTION_MOVE / ACTION_POINTER_UP +
 * ACTION_UP events with a two-slot {@link MotionEvent}, which is what lets a
 * pinch on the guest zoom the host's photo app in real time.
 *
 * <p>Everything is guarded: the transaction code comes from {@link InputCodes}
 * (per-SDK candidate list - stock and LineageOS can differ for the same SDK,
 * which is how live touch was silently dead on a stock API-34 host while the
 * table only carried the Lineage count), the parcel layout matches the
 * platform's generated proxy byte-for-byte, and any failure - no candidate
 * left, refused injection, dead binder, OEM that reordered the interface - sets
 * a sticky {@link #isBroken()} flag and the caller falls back to dispatchGesture
 * for good. Worst case is today's behaviour, never a crash.
 *
 * <p>Called only from the guest's socket reader thread (one caller by
 * construction), which is also what serialises events in finger order. Each
 * binder call is sub-millisecond, so touching the read loop is harmless.
 */
public final class ShellTouch {

    private static final String TAG = "GhostHand";
    private static final String DESCRIPTOR = "android.hardware.input.IInputManager";
    /** INJECT_INPUT_EVENT_MODE_ASYNC: submit and go, no round-trip wait. */
    private static final int MODE_ASYNC = 0;

    private static volatile boolean broken;
    private static String brokenReason = "";
    private static boolean haveDown;
    private static IBinder service;

    /**
     * The transaction code that last worked, or -1 before the first success.
     * Tried candidates rotate through {@link InputCodes#injectInputEventCodes}.
     */
    private static int code = -1;
    private static boolean lineageRom;

    /** Guest-DOWN epoch, mapped onto this device's uptime so pacing survives. */
    private static long hostEpochMs;
    private static long guestEpochMs;
    private static long lastWhenMs;

    private ShellTouch() {
    }

    /** Sticky: once true, live injection is abandoned and never retried. */
    public static boolean isBroken() {
        return broken;
    }

    /** True when the build fingerprint identifies LineageOS (orders the AIDL differently). */
    public static synchronized boolean isLineageRom() {
        if (!lineageRom) {
            String fp = (Build.FINGERPRINT + " " + Build.DISPLAY + " " + Build.MANUFACTURER)
                    .toLowerCase(java.util.Locale.US);
            lineageRom = fp.contains("lineage") || fp.contains("/omni/") || fp.contains("crdroid");
        }
        return lineageRom;
    }

    /** One line for the host dashboard: which code streams, or why nothing does. */
    public static synchronized String statusLine() {
        if (broken) {
            return "live touch broken: " + brokenReason;
        }
        if (code > 0) {
            return "live touch on (inject code " + code + ")";
        }
        return "live touch not started";
    }

    /**
     * Injects one touch event. Synchronous; ordered by the reader thread.
     * Single-pointer form; see the other overload for two fingers.
     *
     * @return true if the event (or a redundant one) is on its way in
     */
    public static boolean onTouch(int action, int xPx, int yPx, long guestTimeMs) {
        return onTouch(action, xPx, yPx, -1, -1, guestTimeMs);
    }

    /**
     * Injects one touch event with one or two pointers ({@code x2Px < 0} =
     * single). Wire actions 4/5/6 are the two-pointer set: both fingers down,
     * both moving, multi ended (the host lifts both - a fresh single DOWN
     * follows if the guest kept one finger on the glass).
     *
     * @return true if the event (or a redundant one) is on its way in
     */
    public static synchronized boolean onTouch(int action, int xPx, int yPx,
                                               int x2Px, int y2Px, long guestTimeMs) {
        if (broken) {
            return false;
        }
        try {
            switch (action) {
                case 0: // DOWN
                    begin(xPx, yPx, guestTimeMs);
                    return inject(MotionEvent.ACTION_DOWN, xPx, yPx, hostEpochMs);
                case 2: { // MOVE
                    if (!haveDown) {
                        begin(xPx, yPx, guestTimeMs); // joined mid-gesture
                    }
                    long when = eventTime(guestTimeMs);
                    return inject(MotionEvent.ACTION_MOVE, xPx, yPx, when);
                }
                case 1: // UP
                    if (!haveDown) {
                        return true; // nothing down: nothing to lift
                    }
                    long whenUp = eventTime(guestTimeMs);
                    haveDown = false;
                    return inject(MotionEvent.ACTION_UP, xPx, yPx, whenUp);
                case 3: // CANCEL
                    return cancel();
                case 4: { // both fingers down (second pointer landed)
                    if (!haveDown) {
                        begin(xPx, yPx, guestTimeMs);
                    } else {
                        // The single finger was already down; keep its epoch and
                        // only add the second pointer now.
                        lastWhenMs = Math.max(lastWhenMs + 1, lastWhenMs);
                    }
                    if (x2Px < 0) {
                        return false; // malformed: multi without a second point
                    }
                    long when2 = eventTime(guestTimeMs);
                    int down2 = MotionEvent.ACTION_POINTER_DOWN
                            | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
                    return injectMulti(down2, xPx, yPx, x2Px, y2Px, when2);
                }
                case 5: { // both fingers moving
                    if (x2Px < 0) {
                        return false;
                    }
                    if (!haveDown) {
                        begin(xPx, yPx, guestTimeMs); // joined mid-gesture
                    }
                    long whenMove2 = eventTime(guestTimeMs);
                    return injectMulti(MotionEvent.ACTION_MOVE, xPx, yPx, x2Px, y2Px,
                            whenMove2);
                }
                case 6: { // multi ended: lift both pointers
                    if (!haveDown) {
                        return true;
                    }
                    if (x2Px < 0) {
                        return cancel();
                    }
                    long whenEnd = eventTime(guestTimeMs);
                    int pointerUp = MotionEvent.ACTION_POINTER_UP
                            | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
                    boolean ok2 = injectMulti(pointerUp, xPx, yPx, x2Px, y2Px, whenEnd);
                    haveDown = false;
                    long whenUp2 = whenEnd + 1;
                    if (whenUp2 <= lastWhenMs) {
                        whenUp2 = lastWhenMs + 1;
                    }
                    lastWhenMs = whenUp2;
                    boolean ok0 = inject(MotionEvent.ACTION_UP, xPx, yPx, whenUp2);
                    return ok2 && ok0;
                }
                default:
                    return false;
            }
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    /** Best-effort close of a half-injected gesture; never throws. */
    public static synchronized boolean cancel() {
        if (broken || !haveDown) {
            haveDown = false;
            return broken ? false : true;
        }
        try {
            lastWhenMs += 1;
            return inject(MotionEvent.ACTION_CANCEL, -1, -1, lastWhenMs);
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    // ------------------------------ internals -------------------------------

    private static void begin(int x, int y, long guestTimeMs) {
        hostEpochMs = SystemClock.uptimeMillis();
        guestEpochMs = guestTimeMs;
        lastWhenMs = hostEpochMs;
        haveDown = true;
    }

    /**
     * Guest timeline mapped onto this device's clock: preserves inter-event
     * pacing (a VelocityTracker reads fling speed from timestamps) while staying
     * strictly monotonic, which the input dispatcher requires.
     */
    private static long eventTime(long guestTimeMs) {
        long delta = guestTimeMs - guestEpochMs;
        if (delta < 0) {
            delta = 0;
        }
        if (delta > 30_000L) {
            delta = 30_000L; // matches the planner's max stroke duration
        }
        long when = hostEpochMs + delta;
        if (when <= lastWhenMs) {
            when = lastWhenMs + 1;
        }
        lastWhenMs = when;
        return when;
    }

    private static boolean inject(int action, float x, float y, long when) throws Exception {
        IBinder binder = service();
        if (binder == null) {
            throw new IllegalStateException("no IInputManager binder");
        }
        // Same event shape `adb shell input` builds: finger, screen coords.
        MotionEvent event = MotionEvent.obtain(hostEpochMs, when, action, x, y,
                1f, 1f, 0, 1f, 1f, 0, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            return transact(binder, event, when);
        } finally {
            event.recycle();
        }
    }

    /** Two-finger event: the multi-pointer {@link MotionEvent} overload. */
    private static boolean injectMulti(int action, float x0, float y0, float x1, float y1,
                                       long when) throws Exception {
        IBinder binder = service();
        if (binder == null) {
            throw new IllegalStateException("no IInputManager binder");
        }
        MotionEvent.PointerProperties p0 = new MotionEvent.PointerProperties();
        p0.id = 0;
        p0.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerProperties p1 = new MotionEvent.PointerProperties();
        p1.id = 1;
        p1.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords c0 = new MotionEvent.PointerCoords();
        c0.x = x0;
        c0.y = y0;
        c0.pressure = 1f;
        c0.size = 1f;
        MotionEvent.PointerCoords c1 = new MotionEvent.PointerCoords();
        c1.x = x1;
        c1.y = y1;
        c1.pressure = 1f;
        c1.size = 1f;
        // API 34's multi-pointer obtain: metaState/buttonState first, then
        // x/y precision, then deviceId/edgeFlags/source/flags. Pressure and
        // size live in each PointerCoords, not in the obtain call.
        MotionEvent event = MotionEvent.obtain(hostEpochMs, when, action, 2,
                new MotionEvent.PointerProperties[] { p0, p1 },
                new MotionEvent.PointerCoords[] { c0, c1 },
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            return transact(binder, event, when);
        } finally {
            event.recycle();
        }
    }

    /**
     * One injectInputEvent transaction with the remembered code, or the ordered
     * candidates from {@link InputCodes} until one accepts. A candidate that
     * throws or refuses moves to the next; only an empty list (or all of them
     * failing) breaks the path.
     */
    private static boolean transact(IBinder binder, MotionEvent event, long when)
            throws Exception {
        int[] candidates = code > 0
                ? new int[] { code }
                : InputCodes.injectInputEventCodes(Build.VERSION.SDK_INT, isLineageRom());
        if (candidates.length == 0) {
            throw new IllegalStateException("no inject code for API " + Build.VERSION.SDK_INT);
        }
        Exception last = null;
        for (int candidate : candidates) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeInt(1);
                event.writeToParcel(data, 0);
                data.writeInt(MODE_ASYNC);
                if (!binder.transact(candidate, data, reply, 0)) {
                    last = new IllegalStateException(
                            "code " + candidate + ": unknown transaction");
                    continue;
                }
                reply.readException();
                if (reply.readInt() == 0) {
                    last = new IllegalStateException("code " + candidate + ": refused");
                    continue;
                }
                if (code != candidate) {
                    Log.i(TAG, "live touch using inject code " + candidate
                            + " (API " + Build.VERSION.SDK_INT
                            + (isLineageRom() ? ", lineage" : ", stock") + ")");
                    code = candidate;
                }
                return true;
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
        throw last != null ? last : new IllegalStateException("no inject code worked");
    }

    private static IBinder service() {
        if (service == null) {
            IBinder raw = SystemServiceHelper.getSystemService("input");
            // Through the Shizuku wrapper every transact() runs as the shell user,
            // which is where INJECT_EVENTS lives.
            service = raw == null ? null : new ShizukuBinderWrapper(raw);
        }
        return service;
    }

    private static void fail(Throwable t) {
        broken = true;
        brokenReason = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        haveDown = false;
        Log.w(TAG, "live touch disabled, falling back to dispatchGesture: " + t);
    }
}
