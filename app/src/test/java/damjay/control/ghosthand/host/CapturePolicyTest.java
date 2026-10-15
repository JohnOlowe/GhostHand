package damjay.control.ghosthand.host;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The boundary the crash lived on: recreating a virtual display on the same
 * MediaProjection grant is seamless below Android 14 and a SecurityException on 14+.
 * The service branches on {@link CapturePolicy}; this pins the branch to the OS
 * version that introduced {@code MediaProjectionManagerService$MediaProjection.isValid()}.
 */
public class CapturePolicyTest {

    @Test
    public void hostFloorMayRecreateInPlace() {
        // 21 is the host role's floor (MIN_HOST_API) - the original seamless path.
        assertFalse(CapturePolicy.needsFreshConsentForModeChange(21));
    }

    @Test
    public void android13StillAllowsSequentialDisplays() {
        // lineage-20.0 (Android 13) has no isValid() one-shot check at all.
        assertFalse(CapturePolicy.needsFreshConsentForModeChange(33));
    }

    @Test
    public void android14IsTheFirstToEnforce() {
        // lineage-21.0 (Android 14): isValid() = !hasTimedOut && mCountStarts <= 1
        // && mVirtualDisplayId == INVALID_DISPLAY, SecurityException otherwise.
        assertTrue(CapturePolicy.needsFreshConsentForModeChange(34));
    }

    @Test
    public void everyNewerReleaseKeepsTheRule() {
        assertTrue(CapturePolicy.needsFreshConsentForModeChange(35));
        assertTrue(CapturePolicy.needsFreshConsentForModeChange(36));
    }
}
