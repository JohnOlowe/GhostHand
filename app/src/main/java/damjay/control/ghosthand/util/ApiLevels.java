package damjay.control.ghosthand.util;

/**
 * Which half of GhostHand a given Android version can run.
 *
 * <p>The two roles have different floors, and that asymmetry is the whole reason this
 * app is useful on an old phone:
 *
 * <table>
 *   <tr><th>role</th><th>needs</th><th>why</th></tr>
 *   <tr><td>host (mirror)</td><td>API 21</td>
 *       <td>{@code MediaProjection} and {@code MediaCodec.createInputSurface()} into a
 *           virtual display arrived in Android 5.0. Before that, an app could not
 *           capture the screen at all without root.</td></tr>
 *   <tr><td>host (inject)</td><td>API 24</td>
 *       <td>{@code AccessibilityService.dispatchGesture()} and
 *           {@code GestureDescription} arrived in Android 7.0. A 5.0-6.x phone can
 *           mirror, but nothing can synthesise input on it.</td></tr>
 *   <tr><td>guest (view + control)</td><td>API 19</td>
 *       <td>Needs {@code MediaCodec} (16), {@code SurfaceView} (1), {@code NsdManager}
 *           (16) and sockets (1). None of it is new, so a 4.4 phone makes a perfectly
 *           good controller for a modern one.</td></tr>
 * </table>
 *
 * <p><b>Pure Java on purpose.</b> It takes the SDK level as an {@code int} instead of
 * reading {@code Build.VERSION.SDK_INT} itself, so the policy is a plain function that
 * the JVM unit tests can exercise at every boundary - 18, 19, 20, 21, 23, 24 - without
 * a device or an emulator. The two constants are written as literals rather than
 * {@code Build.VERSION_CODES} for the same reason. Callers pass the real value:
 * {@code ApiLevels.canHost(Build.VERSION.SDK_INT)}.
 */
public final class ApiLevels {

    /** Android 5.0 Lollipop - MediaProjection, so a phone can serve its screen. */
    public static final int MIN_HOST_API = 21;

    /** Android 7.0 Nougat - dispatchGesture, so a phone can be controlled. */
    public static final int MIN_TOUCH_API = 24;

    /** Android 4.4 KitKat - nothing more than this app was built against. */
    public static final int MIN_SUPPORTED_API = 19;

    private ApiLevels() {
        // static policy
    }

    /** True when a device on {@code sdkInt} can capture and serve its own screen. */
    public static boolean canHost(int sdkInt) {
        return sdkInt >= MIN_HOST_API;
    }

    /** True when a device on {@code sdkInt} can have its touches synthesised. */
    public static boolean canInject(int sdkInt) {
        return sdkInt >= MIN_TOUCH_API;
    }

    /** True when a device on {@code sdkInt} can run the guest half. */
    public static boolean canGuest(int sdkInt) {
        return sdkInt >= MIN_SUPPORTED_API;
    }
}
