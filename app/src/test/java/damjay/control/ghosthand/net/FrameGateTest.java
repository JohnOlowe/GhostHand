package damjay.control.ghosthand.net;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The freeze-vs-corruption switch. A regression here returns to the exact bug
 * the screenshots in {@code screenshots/} captured: green block corruption on
 * the guest whenever the host's network queue dropped a frame during scrolling.
 */
public class FrameGateTest {

    @Test
    public void freshGatePassesEverythingVideo() {
        FrameGate gate = new FrameGate();
        assertTrue(gate.allow(true, false));
        assertTrue(gate.allow(true, true));
        assertFalse(gate.isAwaitingKeyframe());
    }

    @Test
    public void controlFramesAlwaysPass() {
        FrameGate gate = new FrameGate();
        gate.onVideoDropped();
        assertTrue(gate.allow(false, false)); // stats/config/clipboard etc.
    }

    @Test
    public void afterADropOnlyTheNextKeyFrameGetsThrough() {
        FrameGate gate = new FrameGate();
        gate.onVideoDropped();
        // P-frames that would reference the missing picture: suppressed.
        assertFalse(gate.allow(true, false));
        assertFalse(gate.allow(true, false));
        assertTrue(gate.isAwaitingKeyframe());
        // The key frame resyncs and is sent...
        assertTrue(gate.allow(true, true));
        assertFalse(gate.isAwaitingKeyframe());
        // ...and the stream flows again.
        assertTrue(gate.allow(true, false));
    }

    @Test
    public void repeatedDropsDoNotLeakThrough() {
        FrameGate gate = new FrameGate();
        gate.onVideoDropped();
        assertFalse(gate.allow(true, false));
        gate.onVideoDropped(); // another drop while still waiting
        assertFalse(gate.allow(true, false));
        assertTrue(gate.allow(true, true));
    }

    @Test
    public void aKeyFrameThatIsDroppedKeepsTheGateClosed() {
        // Dropping the key frame itself must not "open" on a later P-frame:
        // the gate only opens by SENDING a key frame, not by one going missing.
        FrameGate gate = new FrameGate();
        gate.onVideoDropped();
        assertFalse(gate.allow(true, false));
        gate.onVideoDropped(); // say the key frame we waited for was dropped too
        assertFalse(gate.allow(true, false));
        assertTrue(gate.allow(true, true));
    }
}
