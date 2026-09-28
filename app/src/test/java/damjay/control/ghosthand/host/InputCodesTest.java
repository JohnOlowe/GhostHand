package damjay.control.ghosthand.host;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The version table for IInputManager.injectInputEvent. Each value was counted
 * from the AIDL source at that Android version's LineageOS tag; a wrong number
 * here does not fail loudly at build time - it sends our parcel to a DIFFERENT
 * input-service method on the device, so the table is pinned.
 */
public class InputCodesTest {

    @Test
    public void api21To25UseTheOldFiveMethodHead() {
        for (int sdk = 21; sdk <= 25; sdk++) {
            assertEquals("API " + sdk, 5, InputCodes.injectInputEventCode(sdk));
        }
    }

    @Test
    public void api26To32ShareCodeEight() {
        for (int sdk = 26; sdk <= 32; sdk++) {
            assertEquals("API " + sdk, 8, InputCodes.injectInputEventCode(sdk));
        }
    }

    @Test
    public void theThreeRecentVersionsEachHaveTheirOwnCode() {
        assertEquals(9, InputCodes.injectInputEventCode(33));
        assertEquals(12, InputCodes.injectInputEventCode(34));
        assertEquals(11, InputCodes.injectInputEventCode(35));
        assertEquals(11, InputCodes.injectInputEventCode(36));
    }

    @Test
    public void unknownVersionsRefuseInsteadOfGuessing() {
        // A future Android whose interface we have not counted must answer -1:
        // the caller then falls back to dispatchGesture instead of transacting
        // with a code that may belong to some other method.
        assertEquals(-1, InputCodes.injectInputEventCode(20));
        assertEquals(-1, InputCodes.injectInputEventCode(37));
        assertEquals(-1, InputCodes.injectInputEventCode(99));
    }
}
