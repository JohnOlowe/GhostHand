package damjay.control.ghosthand.host;

/**
 * Binder transaction codes for {@code IInputManager.injectInputEvent}, per
 * Android version. The interface is classic positional AIDL, so the code is
 * simply the method's 1-based position among the interface's declarations -
 * and that position moves as methods are added and removed.
 *
 * <p>Values were counted from the AIDL sources at each version's LineageOS tag
 * (cm-11.0..cm-14.1, lineage-15.1..lineage-23.0) and cross-checked against a
 * decompiled AOSP proxy, which writes exactly the parcel layout
 * {@link ShellTouch} uses: interface token, writeInt(1) + writeToParcel(event),
 * mode int; reply is readException + int result.
 *
 * <p>Unknown future versions answer -1 and the caller must fall back to
 * {@code dispatchGesture}: guessing a code for a version whose interface we
 * have not counted would send our parcel to some other method of the input
 * service. An OEM that reorders the interface has the same effect; the
 * failure surfaces as a refused transaction and the fallback kicks in.
 */
public final class InputCodes {

    private InputCodes() {
    }

    /**
     * @param sdkInt the device's {@code Build.VERSION.SDK_INT}
     * @return the transaction code, or -1 if this version's code is unknown
     */
    public static int injectInputEventCode(int sdkInt) {
        if (sdkInt >= 21 && sdkInt <= 25) {
            return 5;  // cm-11.0, cm-12.0, cm-13.0, cm-14.1: 4 methods precede it
        }
        if (sdkInt >= 26 && sdkInt <= 32) {
            return 8;  // lineage-15.1 .. lineage-19.1 (8.0 through 12L)
        }
        if (sdkInt == 33) {
            return 9;  // lineage-20.0: isInputDeviceEnabled-era methods grew the head
        }
        if (sdkInt == 34) {
            return 12; // lineage-21.0: velocity-tracker/pointer methods inserted first
        }
        if (sdkInt == 35 || sdkInt == 36) {
            return 11; // lineage-22.1/22.2/23.0: one method left again
        }
        return -1;
    }
}
