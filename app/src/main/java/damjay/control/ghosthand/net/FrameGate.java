package damjay.control.ghosthand.net;

/**
 * Per-guest video suppression after a drop - the difference between a freeze and
 * a corrupted picture.
 *
 * <p>H..264 P-frames are built on top of a reference frame. If a frame is dropped
 * on the way to one guest (its queue overflowed, or a control frame evicted it),
 * every subsequent P-frame decodes against a picture that phone never received:
 * the result is block-smearing with green blocks - exactly what the screenshots
 * in {@code screenshots/} show during rapid scrolling, when frame sizes balloon
 * and the network queue overflows.
 *
 * <p>The protocol has no sequence numbers, so the guest cannot detect the gap.
 * The sender can: once anything video was dropped for this guest, suppress all
 * further non-key video frames until the next key frame arrives. The guest then
 * shows its last good frame (a freeze of at most one key-frame interval - the
 * "glitch clock") and resyncs cleanly, instead of rendering garbage.
 *
 * <p>Control frames (config, geometry, stats, clipboard, ...) carry no picture
 * and always pass.
 */
public final class FrameGate {

    private boolean awaitingKeyframe;

    /** Called when a video frame was dropped or evicted for this guest. */
    public void onVideoDropped() {
        awaitingKeyframe = true;
    }

    /**
     * @param isVideo    the frame about to be queued is a VIDEO frame
     * @param isKeyframe it carries the full picture (reference frame)
     * @return true to send it, false to suppress it until the next key frame
     */
    public boolean allow(boolean isVideo, boolean isKeyframe) {
        if (!isVideo) {
            return true; // control frames never depend on picture state
        }
        if (!awaitingKeyframe) {
            return true;
        }
        if (isKeyframe) {
            awaitingKeyframe = false; // resync point: let it through
            return true;
        }
        return false;
    }

    /** True while frames are being held back. */
    public boolean isAwaitingKeyframe() {
        return awaitingKeyframe;
    }
}
