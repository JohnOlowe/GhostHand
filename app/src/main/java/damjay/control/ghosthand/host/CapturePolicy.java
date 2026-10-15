package damjay.control.ghosthand.host;

/**
 * The Android 14 MediaProjection rule, in one place so both the service's branches
 * and the unit test agree on where the boundary is.
 *
 * <p>Android 14 (UPSIDE_DOWN_CAKE) made a consent grant single-use:
 * {@code MediaProjectionManagerService$MediaProjection.isValid()} returns false - and
 * throws {@code SecurityException("Don't take multiple captures by invoking
 * MediaProjection#createVirtualDisplay multiple times on the same instance")} - as soon
 * as {@code mVirtualDisplayId != INVALID_DISPLAY}, i.e. from the moment the first
 * virtual display is created, <em>permanently</em>: releasing that display does not
 * reset the flag, and neither does stopping it. Below 14 the same grant may create
 * displays one after another, so an in-place swap stays seamless there.
 *
 * <p>The two callers: {@link ScreenCaptureService} picks between recreating the
 * display in place (below 14) and asking the activity for a fresh consent that lets
 * {@code swapProjection()} put a new grant under the running session (14 and above).
 */
public final class CapturePolicy {

    private CapturePolicy() {
    }

    /**
     * @param sdkInt the platform the code is running on ({@code Build.VERSION.SDK_INT})
     * @return true when a capture-mode change mid-session must go through a fresh
     *         system consent instead of recreating the virtual display in place
     */
    public static boolean needsFreshConsentForModeChange(int sdkInt) {
        return sdkInt >= 34;
    }
}
