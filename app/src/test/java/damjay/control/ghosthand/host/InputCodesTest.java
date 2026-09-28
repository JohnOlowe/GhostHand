package damjay.control.ghosthand.host;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The version table for IInputManager.injectInputEvent. Each value was counted
 * from the AIDL source at that Android version's tag - stock AOSP where stock
 * and LineageOS differ; a wrong number here does not fail loudly at build
 * time - it sends our parcel to a DIFFERENT input-service method on the device,
 * so the table is pinned.
 *
 * <p>The API-34 pair is the one that shipped wrong: only the LineageOS-21
 * count (12) was carried, and on a stock host code 12 lands on
 * verifyInputEvent - live touch appeared to "work" while no event reached the
 * screen. Stock first, ROM second, both kept.
 */
public class InputCodesTest {

    @Test
    public void api21To25UseTheOldFiveMethodHead() {
        for (int sdk = 21; sdk <= 25; sdk++) {
            assertArrayEquals("API " + sdk, new int[] { 5 },
                    InputCodes.injectInputEventCodes(sdk, false));
        }
    }

    @Test
    public void api26To32ShareCodeEight() {
        for (int sdk = 26; sdk <= 32; sdk++) {
            assertArrayEquals("API " + sdk, new int[] { 8 },
                    InputCodes.injectInputEventCodes(sdk, false));
        }
    }

    @Test
    public void recentStockVersionsMatchTheAospAidl() {
        // Counted from android-13/15/16.0.0_r1 IInputManager.aidl.
        assertArrayEquals(new int[] { 9 }, InputCodes.injectInputEventCodes(33, false));
        assertArrayEquals(new int[] { 11 }, InputCodes.injectInputEventCodes(35, false));
        assertArrayEquals(new int[] { 11 }, InputCodes.injectInputEventCodes(36, false));
    }

    @Test
    public void api34TriesStockFirstAndLineageSecond() {
        // Stock android-14.0.0_r1: injectInputEvent is the 10th declaration.
        assertArrayEquals(new int[] { 10, 12 },
                InputCodes.injectInputEventCodes(34, false));
        // Lineage-21 inserts two methods ahead of it -> 12 first, stock fallback.
        assertArrayEquals(new int[] { 12, 10 },
                InputCodes.injectInputEventCodes(34, true));
    }

    @Test
    public void unknownVersionsRefuseInsteadOfGuessing() {
        // A future Android whose interface we have not counted must answer no
        // candidates at all: the caller then falls back to dispatchGesture
        // instead of transacting with a code that may belong to some other
        // method.
        assertEquals(0, InputCodes.injectInputEventCodes(20, false).length);
        assertEquals(0, InputCodes.injectInputEventCodes(37, false).length);
        assertEquals(0, InputCodes.injectInputEventCodes(99, true).length);
    }

    @Test
    public void theFirstCandidateIsAlwaysARealPositiveCode() {
        for (int sdk = 21; sdk <= 36; sdk++) {
            int[] codes = InputCodes.injectInputEventCodes(sdk, sdk % 2 == 0);
            assertTrue("API " + sdk, codes.length > 0 && codes[0] > 0);
        }
    }
}
