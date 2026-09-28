package damjay.control.ghosthand.host;

/**
 * Binder transaction codes for {@code IInputManager.injectInputEvent}, per
 * Android version. The interface is classic positional AIDL, so the code is
 * simply the method's 1-based position among the interface's declarations - and
 * that position moves as methods are added and removed.
 *
 * <p>One SDK can have MORE THAN ONE correct code: the position differs between
 * stock AOSP and LineageOS for the same release, because a ROM may insert its
 * own methods ahead of it. The vc13 field report showed the cost of conflating
 * them - the table carried a LineageOS-21 count of 12 for API 34, while stock
 * Android 14 has {@code injectInputEvent} at position 10, so on a stock host
 * the transact landed on {@code verifyInputEvent}: the call "succeeded" as a
 * verification and no touch ever reached the screen, silently disabling live
 * swipe. Every SDK therefore returns an ordered candidate list (stock first,
 * then the ROM variant), and {@link ShellTouch} remembers the first code that
 * actually works.
 *
 * <p>Stock values were counted from the AOSP tags' {@code
 * android/hardware/input/IInputManager.aidl} (android-13.0.0_r1,
 * android-14.0.0_r1, android-15.0.0_r1, android-16.0.0_r1); the older ranges
 * and the LineageOS variant were counted from the LineageOS tags at those
 * versions (cm-11.0..cm-14.1, lineage-15.1..lineage-23.0) and cross-checked
 * against a decompiled AOSP proxy, which writes exactly the parcel layout
 * {@link ShellTouch} uses: interface token, writeInt(1) + writeToParcel(event),
 * mode int; reply is readException + int result.
 *
 * <p>An unknown future version answers an empty list and the caller must fall
 * back to {@code dispatchGesture}: guessing a code for a version whose
 * interface we have not counted would send our parcel to some other method of
 * the input service. An OEM that reorders the interface has the same effect;
 * a refused or excepted transaction makes {@link ShellTouch} try the next
 * candidate and, if all fail, go sticky and fall back.
 */
public final class InputCodes {

    private InputCodes() {
    }

    /**
     * Candidate transaction codes for {@code injectInputEvent}, in the order
     * they should be tried (the first one that works is remembered).
     *
     * @param sdkInt     the device's {@code Build.VERSION.SDK_INT}
     * @param lineageRom true when the build identifies as LineageOS (or a
     *                   close derivative), which orders its interface differently
     *                   from stock on API 34
     * @return the candidates, empty when this version's codes are unknown
     */
    public static int[] injectInputEventCodes(int sdkInt, boolean lineageRom) {
        if (sdkInt >= 21 && sdkInt <= 25) {
            return one(5);   // cm-11.0, cm-12.0, cm-13.0, cm-14.1: 4 methods precede it
        }
        if (sdkInt >= 26 && sdkInt <= 32) {
            return one(8);   // lineage-15.1 .. lineage-19.1 (8.0 through 12L)
        }
        if (sdkInt == 33) {
            return one(9);   // AOSP android-13 and lineage-20.0 agree: stock == 9
        }
        if (sdkInt == 34) {
            // Stock android-14: getVelocityTrackerStrategy + isInputDeviceEnabled
            // era -> 10 (counted from android-14.0.0_r1). Lineage-21 inserts two
            // methods ahead of it -> 12. Order by ROM; the other is kept as the
            // fallback so either host ends up with the right code.
            return lineageRom ? new int[] { 12, 10 } : new int[] { 10, 12 };
        }
        if (sdkInt == 35 || sdkInt == 36) {
            return one(11);  // AOSP android-15/16 and lineage-22/23 agree: 11
        }
        return new int[0];
    }

    private static int[] one(int code) {
        return new int[] { code };
    }
}
