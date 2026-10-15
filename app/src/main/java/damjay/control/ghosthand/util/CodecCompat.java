package damjay.control.ghosthand.util;

import android.media.MediaCodec;
import android.os.Build;

import java.nio.ByteBuffer;

/**
 * The one place where {@code MediaCodec}'s buffer access differs between API levels.
 *
 * <p><b>Why this class exists.</b> {@code MediaCodec} has two ways to hand you a
 * buffer, and which one is legal depends on the platform version:
 *
 * <pre>
 *   API 16+   ByteBuffer[] all = codec.getInputBuffers();   // the whole array
 *             ... use all[index]
 *
 *   API 21+   ByteBuffer one = codec.getInputBuffer(index); // just the one
 * </pre>
 *
 * <p>The array form is not merely "older": it is the only form that exists on
 * Android 4.4, which is a target of this build. The per-index form was added in
 * Android 5.0 along with the async mode, and the array form was deprecated in the
 * same release.
 *
 * <p><b>Why this is a separate class and not an if-statement in the callers.</b>
 * The bytecode below contains a call to a method that does not exist on API 19. That
 * is fine - the platform resolves method references lazily, per instruction, when
 * that instruction first executes, and the {@code SDK_INT} check guarantees the
 * older devices never reach it - but *somewhere* has to own that fact so it can be
 * reviewed, tested and exempted in one place. AndroidX does exactly this with its
 * {@code Api21Impl} / {@code Api23Impl} nested classes; this is the same trick with
 * a smaller blast radius. {@code check_api.py} has a single allowlist entry for this
 * file and refuses to let any other class in the app make an unavailable call.
 *
 * <p><b>Both methods are null-tolerant.</b> {@code getInputBuffer()} is documented to
 * return null if the index is stale or the codec is in a bad state, and the array
 * form can return a shorter array on some OEM codecs, so callers get null instead of
 * an {@code ArrayIndexOutOfBoundsException} and handle it the same way.
 */
public final class CodecCompat {

    /** Android 5.0, where the per-index accessors replaced the arrays. */
    private static final int LOLLIPOP = Build.VERSION_CODES.LOLLIPOP;

    private CodecCompat() {
        // static utility
    }

    /** The codec's input buffer {@code index}, or null if there is nothing to write to. */
    public static ByteBuffer inputBuffer(MediaCodec codec, int index) {
        if (codec == null || index < 0) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= LOLLIPOP) {
            return codec.getInputBuffer(index);
        }
        ByteBuffer[] buffers = codec.getInputBuffers();
        return (buffers != null && index < buffers.length) ? buffers[index] : null;
    }

    /** The codec's output buffer {@code index}, or null if there is nothing to read. */
    public static ByteBuffer outputBuffer(MediaCodec codec, int index) {
        if (codec == null || index < 0) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= LOLLIPOP) {
            return codec.getOutputBuffer(index);
        }
        // getOutputBuffers() is the API 16..20 form. It must be called after start()
        // and re-read after a flush(), which is why it is fetched here rather than
        // cached in a field by the caller.
        ByteBuffer[] buffers = codec.getOutputBuffers();
        return (buffers != null && index < buffers.length) ? buffers[index] : null;
    }
}
