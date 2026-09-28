package damjay.control.ghosthand.host;

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
 * <p>Everything is guarded: the transaction code comes from
 * {@link InputCodes} (version table, -1 = unknown), the parcel layout matches
 * the platform's generated proxy byte-for-byte, and any failure - unknown code,
 * refused injection, dead binder, OEM that reordered the interface - sets a
 * sticky {@link #isBroken()} flag and the caller falls back to dispatchGesture
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
    private static boolean haveDown;
    private static IBinder service;

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

    /**
     * Injects one touch event. Synchronous; ordered by the reader thread.
     *
     * @return true if the event (or a redundant one) is on its way in
     */
    public static synchronized boolean onTouch(int action, int xPx, int yPx, long guestTimeMs) {
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
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(1);
            event.writeToParcel(data, 0);
            data.writeInt(MODE_ASYNC);
            if (!binder.transact(InputCodes.injectInputEventCode(
                    android.os.Build.VERSION.SDK_INT), data, reply, 0)) {
                throw new IllegalStateException("unknown transaction (wrong code?)");
            }
            reply.readException();
            if (reply.readInt() == 0) {
                throw new IllegalStateException("injection refused");
            }
            return true;
        } finally {
            event.recycle();
            data.recycle();
            reply.recycle();
        }
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
        haveDown = false;
        Log.w(TAG, "live touch disabled, falling back to dispatchGesture: " + t);
    }
}
