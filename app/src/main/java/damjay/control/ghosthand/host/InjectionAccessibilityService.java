package damjay.control.ghosthand.host;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Path;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * The piece that makes the guest's finger move on the host.
 *
 * <h2>Why this needs an AccessibilityService at all</h2>
 *
 * An ordinary app cannot write into another app's input stream. Android hands that
 * power to exactly three things: the system itself, a rooted shell ({@code input
 * tap}), and an {@link AccessibilityService} the user has explicitly enabled in
 * Settings. This app has no root requirement, so it is the third option:
 *
 * <pre>
 *   guest finger ──TOUCH frame──► TouchInjector ──► dispatchGesture ──► host UI
 *                                   (pure logic)      (this class)
 * </pre>
 *
 * <p>The user grants it once, in Settings -> Accessibility. That grant is the whole
 * security story: the OS is telling them "this app can act on your screen", and it
 * cannot be given silently by any app, including this one.
 *
 * <h2>What dispatchGesture can and cannot do</h2>
 *
 * It executes a {@code GestureDescription}: one or more strokes, each a
 * {@code Path} with a start delay and a duration, dispatched as a unit. It cannot
 * stream - there is no "keep this finger down and move it" call - which is why
 * {@link TouchInjector} buffers a drag and replays it on release. It also cannot send
 * a bare key event ({@code dispatchGesture}, not {@code performGlobalAction}).
 *
 * <p>Every gesture is dispatched on the main thread: {@code dispatchGesture} requires
 * it, and the caller (the socket reader) is not on it.
 *
 * <h2>Android 7.0 and up</h2>
 *
 * Every one of those calls arrived in API 24. On Android 4.4-6.x this class still
 * loads, installs and connects - the framework is free to instantiate it whenever the
 * user opens Settings -> Accessibility - but {@link #inject} refuses before touching
 * anything, and all API-24 code lives in the nested {@code Api24} class that is only
 * loaded once a gesture is actually dispatched. See the comment on that class.
 */
public class InjectionAccessibilityService extends AccessibilityService {

    private static final String TAG = "GhostHandInject";

    /** Set while the service is connected; null when the user has not enabled it. */
    private static volatile InjectionAccessibilityService instance;

    /** Last failure, surfaced in the host log so a silent no-op is impossible. */
    private static volatile String lastError;

    public static InjectionAccessibilityService instance() {
        return instance;
    }

    public static String lastError() {
        return lastError;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        lastError = null;
        Log.i(TAG, "accessibility service connected - injection available");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        Log.i(TAG, "accessibility service disconnected - injection unavailable");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    /** A gesture injector never needs to react to the UI; it only acts on request. */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Deliberately empty. Reading the screen is not what we are here for, and
        // doing nothing keeps this service invisible to the user's daily use.
    }

    @Override
    public void onInterrupt() {
        // Required override; nothing to interrupt because we never start work here.
    }

    // --------------------------------------------------------------------------
    // injection
    // --------------------------------------------------------------------------

    /**
     * Executes a planned gesture. Must be called on the main thread.
     *
     * @return true if the gesture was accepted for dispatch (it is still asynchronous;
     *         "accepted" is not "the app reacted")
     */
    public boolean inject(TouchInjector.Gesture gesture) {
        if (gesture == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // dispatchGesture() and GestureDescription arrived in Android 7.0. Below
            // that there is no way to synthesise input without root or adb, so this
            // is the honest answer rather than a silent no-op. Nothing API-24 is
            // touched before this point - see the Api24 class at the bottom.
            lastError = "gesture injection needs Android 7.0 (API 24)";
            return false;
        }
        return Api24.dispatch(this, gesture);
    }

    /** Called from {@link Api24}'s callback; kept here so no API-24 type leaks out. */
    private static void onGestureResult(boolean completed, String error) {
        lastError = completed ? null : error;
    }

    // --------------------------------------------------------------------------
    // display size
    // --------------------------------------------------------------------------

    /**
     * The host screen's size in pixels, in the current orientation.
     *
     * <p>This is the <em>real</em> display, not the capture size: the guest
     * normalises by what it sees, which is the whole screen even when we stream a
     * scaled-down copy of it. Using {@code getDefaultDisplay().getRealMetrics()} also
     * means the values rotate with the device, so a portrait capture followed by a
     * landscape one needs no coordinate flipping.
     */
    public int[] displaySize() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm != null && wm.getDefaultDisplay() != null) {
            Display display = wm.getDefaultDisplay();
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            display.getRealMetrics(metrics);
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return new int[] { metrics.widthPixels, metrics.heightPixels };
            }
        }
        android.util.DisplayMetrics fallback = getResources().getDisplayMetrics();
        return new int[] { fallback.widthPixels, fallback.heightPixels };
    }

    // --------------------------------------------------------------------------
    // is it enabled?
    // --------------------------------------------------------------------------

    /**
     * Checks the system's own list of enabled accessibility services.
     *
     * <p>Reading {@code Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES} is allowed
     * without a permission, and it is the only reliable answer: it reflects whether
     * <em>this</em> component is switched on, where {@link #instance()} would also be
     * null during the brief window before the service binds (and after a process
     * restart the socket may arrive before it does).
     */
    public static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null || enabled.isEmpty()) {
            return false;
        }
        ComponentName ours = new ComponentName(context, InjectionAccessibilityService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            ComponentName candidate = ComponentName.unflattenFromString(splitter.next());
            if (candidate != null && candidate.equals(ours)) {
                return true;
            }
        }
        return false;
    }

    // --------------------------------------------------------------------------
    // API 24, in its own class
    // --------------------------------------------------------------------------

    /**
     * Every reference to an Android 7.0 (API 24) type lives in here.
     *
     * <p>This is not tidiness, it is load-order: the class loader resolves a type the
     * first time a method that mentions it is <em>executed</em>, not when the file is
     * compiled or the class is verified. Putting {@code GestureDescription},
     * {@code StrokeDescription} and {@code dispatchGesture()} behind a nested class
     * means an Android 4.4 device can load, install and even connect this service
     * without ever touching a class the platform does not have - and since the
     * framework is the one that instantiates an accessibility service, that loading
     * is not something we get to control. The outer class stays clean; the keep rules
     * in {@code app/proguard.pro} cover this nested class with {@code $*}.
     */
    private static final class Api24 {

        private static final GestureResultCallback CALLBACK = new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                onGestureResult(true, null);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                onGestureResult(false, "gesture cancelled by the system");
                Log.w(TAG, "gesture cancelled");
            }
        };

        private Api24() {
        }

        static boolean dispatch(InjectionAccessibilityService service,
                                TouchInjector.Gesture gesture) {
            Path path = new Path();
            path.moveTo(gesture.startX(), gesture.startY());
            for (int i = 1; i < gesture.pointCount(); i++) {
                path.lineTo(gesture.xs[i], gesture.ys[i]);
            }

            // A tap is a path with a single point. Android accepts that as a stroke:
            // StrokeDescription only needs *a* path, and a moveTo-only path is what
            // every tap-injection sample uses. It is also why the tap case above does
            // not fabricate a 1-pixel lineTo - that would inject a 1 px drag instead.
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0L,
                            Math.max(1L, gesture.durationMs));

            GestureDescription description = new GestureDescription.Builder()
                    .addStroke(stroke)
                    .build();

            try {
                boolean accepted = service.dispatchGesture(description, CALLBACK, null);
                if (!accepted) {
                    // The common cause is another gesture still running: dispatch is
                    // one-at-a-time, and a rapid tap-tap is exactly that situation. It
                    // is not fatal - the next gesture goes through.
                    Log.w(TAG, "gesture rejected (another gesture in flight?)");
                }
                return accepted;
            } catch (RuntimeException e) {
                // Some devices/OEM builds cap stroke duration or point count and throw
                // instead of returning false. Log it rather than kill the reader thread.
                onGestureResult(false, e.getClass().getSimpleName() + ": " + e.getMessage());
                Log.e(TAG, "dispatchGesture failed for " + gesture, e);
                return false;
            }
        }
    }
}
