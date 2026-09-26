package damjay.control.ghosthand.host;

import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import damjay.control.ghosthand.util.CodecCompat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Host side: screen pixels -> H.264 bytes.
 *
 * <p>The pipeline is:
 *
 * <pre>
 *   MediaProjection
 *        |  createVirtualDisplay(..., encoderInputSurface)
 *        v
 *   VirtualDisplay  --composites the screen into-->  Surface
 *        |                                            |
 *        |                              MediaCodec encoder (input surface mode)
 *        v                                            v
 *   (nothing to draw; the system does it)      encoded access units
 *                                                     |
 *                                                     v
 *                                          ClientHub.broadcast(...)
 * </pre>
 *
 * <p>Key idea: we never touch a single pixel in Java. {@code MediaCodec} hands us a
 * {@link Surface} ({@link #getInputSurface()}) that the encoder reads directly in
 * the GPU/codec hardware, and {@code VirtualDisplay} renders the whole screen into
 * that surface. That is what makes 30 fps possible on a phone - a Bitmap/JPEG
 * approach would die on the CPU and the WiFi link.
 *
 * <p>Threading: {@link #start()} configures and starts the codec on the calling
 * thread, then spawns one private "encoder" thread that loops in
 * {@link #drainLoop()}. Every {@link Listener} callback therefore happens on that
 * thread, not the main thread - the service marshals UI updates back itself.
 */
public class ScreenEncoder {

    private static final String TAG = "GhostHand/Encoder";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC; // H.264/AVC

    /** Receives encoded output. Called on the encoder thread. */
    public interface Listener {
        /**
         * The encoder's one-time config blob: SPS + PPS NAL units, still carrying
         * their 00 00 00 01 start codes. Split them with {@link #splitStartCodes}.
         */
        void onCodecConfig(byte[] config);

        /**
         * One encoded access unit (one frame's worth of NAL data).
         *
         * @param data   valid bytes are {@code data[0 .. length)}
         * @param key    true when this is an IDR frame a decoder can start from
         * @param ptsUs  presentation timestamp in microseconds
         */
        void onEncodedFrame(byte[] data, int length, boolean key, long ptsUs);

        void onEncoderError(String message);
    }

    private final int width;
    private final int height;
    private final int bitrate;
    private final int fps;
    private final Listener listener;

    private MediaCodec codec;
    private Surface inputSurface;
    private Thread worker;
    private volatile boolean running;

    /**
     * Scratch buffer reused for every output frame. It only grows, and is written
     * exclusively on the encoder thread, so no locking is needed.
     */
    private byte[] scratch = new byte[256 * 1024];

    public ScreenEncoder(int width, int height, int bitrate, int fps, Listener listener) {
        this.width = width;
        this.height = height;
        this.bitrate = bitrate;
        this.fps = fps;
        this.listener = listener;
    }

    public Surface getInputSurface() {
        return inputSurface;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * Configures and starts the encoder.
     *
     * @throws IOException if no H.264 encoder is available (essentially never on a
     *                     real phone, but emulators without GPU codecs can fail).
     */
    public void start() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);

        // Surface input mode: the codec allocates a Surface for us instead of
        // expecting COLOR_FormatYUV420* byte buffers we would have to fill by hand.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        // One key frame every 2 seconds. A late-joining guest waits at most that
        // long for a clean picture; shorter intervals would waste bandwidth.
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        // Lowest latency: the encoder must not sit on frames to build B-frame
        // groups or look-ahead statistics. Ignored by codecs that do not know the key.
        format.setInteger(MediaFormat.KEY_LATENCY, 1);

        codec = MediaCodec.createEncoderByType(MIME);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = codec.createInputSurface();
        codec.start();

        running = true;
        worker = new Thread(this::drainLoop, "ghosthand-encoder");
        worker.setPriority(Thread.NORM_PRIORITY + 1);
        worker.start();

        Log.i(TAG, "encoder started " + width + "x" + height + " @" + fps + "fps "
                + (bitrate / 1000) + " kbps CBR");
    }

    /**
     * Stops the encoder and releases the codec. Safe to call twice and from any
     * thread. The worker thread is interrupted so a blocking dequeue returns.
     */
    public void stop() {
        running = false;
        Thread t = worker;
        worker = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
                // already in an error state
            }
            try {
                codec.release();
            } catch (RuntimeException ignored) {
                // nothing useful to do while shutting down
            }
            codec = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        Log.i(TAG, "encoder stopped");
    }

    /**
     * The whole job of this class: ask the codec for finished buffers, copy them out
     * and hand them to the listener.
     *
     * <p>{@code dequeueOutputBuffer(timeoutUs)} returns:
     * <ul>
     *   <li>a {@code >= 0} index: a real encoded buffer,</li>
     *   <li>{@code INFO_OUTPUT_FORMAT_CHANGED}: the codec's output MediaFormat, which
     *       is where the csd-0/csd-1 (SPS/PPS) live,</li>
     *   <li>{@code INFO_TRY_AGAIN_LATER}: nothing ready yet - sleep a moment.</li>
     * </ul>
     */
    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            try {
                int index = codec.dequeueOutputBuffer(info, 10_000 /* 10 ms */);

                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = codec.getOutputFormat();
                    byte[] csd0 = bytesOf(format, "csd-0");
                    byte[] csd1 = bytesOf(format, "csd-1");
                    // Some encoders hand us one blob containing both NAL units.
                    byte[] config = concat(csd0, csd1);
                    if (config.length == 0) {
                        listener.onEncoderError("encoder produced no SPS/PPS");
                        running = false;
                        break;
                    }
                    listener.onCodecConfig(config);
                    continue;
                }

                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // 10 ms of nothing; a tiny sleep keeps this thread off the CPU
                    // while the screen is static (the encoder emits no frames then).
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }

                if (index < 0) {
                    continue; // other informational codes: ignore
                }

                ByteBuffer buffer = null;
                try {
                    // API 19 has no getOutputBuffer(index) - see CodecCompat.
                    buffer = CodecCompat.outputBuffer(codec, index);
                    if (buffer == null || info.size <= 0) {
                        continue;
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // SPS/PPS arriving as a real buffer instead of via the format.
                        byte[] config = new byte[info.size];
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        buffer.get(config);
                        listener.onCodecConfig(config);
                        continue;
                    }
                    if (scratch.length < info.size) {
                        scratch = new byte[info.size];
                    }
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    buffer.get(scratch, 0, info.size);

                    boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    listener.onEncodedFrame(scratch, info.size, key, info.presentationTimeUs);
                } finally {
                    // Always give the buffer back, even if the listener threw, or the
                    // codec runs out of output buffers and stalls forever.
                    try {
                        codec.releaseOutputBuffer(index, false);
                    } catch (RuntimeException ignored) {
                        // codec died underneath us; the outer catch will stop the loop
                    }
                }
            } catch (IllegalStateException e) {
                if (running) {
                    Log.e(TAG, "encoder failed", e);
                    listener.onEncoderError("encoder: " + e.getMessage());
                }
                running = false;
                break;
            } catch (RuntimeException e) {
                if (running) {
                    Log.e(TAG, "encoder error", e);
                    listener.onEncoderError("encoder: " + e.getMessage());
                }
                running = false;
                break;
            }
        }
        Log.i(TAG, "encoder drain loop finished");
    }

    /**
     * Encoders cannot change their output size after {@code configure()} - the only
     * public dynamic parameters are {@code PARAMETER_KEY_REQUEST_SYNC_FRAME},
     * {@code PARAMETER_KEY_VIDEO_BITRATE}, {@code PARAMETER_KEY_SUSPEND},
     * {@code PARAMETER_KEY_LOW_LATENCY} and {@code PARAMETER_KEY_OFFSET_TIME}.
     * So a rotation means a new encoder: the service calls {@link #stop()}, builds a
     * fresh ScreenEncoder at the new size and re-attaches the VirtualDisplay. The
     * MediaProjection, the TCP server and every guest socket survive that, so the
     * viewer sees a short pause instead of a disconnect.
     */
    public void updateBitrate(int newBitrate) {
        MediaCodec c = codec;
        if (c == null || !running) {
            return;
        }
        try {
            android.os.Bundle params = new android.os.Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate);
            c.setParameters(params);
            Log.i(TAG, "bitrate retuned to " + (newBitrate / 1000) + " kbps");
        } catch (RuntimeException e) {
            Log.w(TAG, "live bitrate change unsupported: " + e.getMessage());
        }
    }

    /**
     * Requests an immediate IDR frame. Call this when a guest connects so it does
     * not have to wait up to {@code KEY_I_FRAME_INTERVAL} seconds for a decodable
     * picture. No-op on codecs that reject the call.
     */
    public void requestKeyFrame() {
        MediaCodec c = codec;
        if (c == null || !running) {
            return;
        }
        try {
            android.os.Bundle params = new android.os.Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            c.setParameters(params);
        } catch (RuntimeException e) {
            Log.w(TAG, "requestKeyFrame unsupported: " + e.getMessage());
        }
    }

    // ------------------------------- helpers --------------------------------

    private static byte[] bytesOf(MediaFormat format, String key) {
        try {
            ByteBuffer bb = format.getByteBuffer(key);
            if (bb == null) {
                return new byte[0];
            }
            byte[] out = new byte[bb.remaining()];
            bb.get(out);
            return out;
        } catch (RuntimeException e) {
            return new byte[0];
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        if (a.length == 0) {
            return b;
        }
        if (b.length == 0) {
            return a;
        }
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /**
     * Splits an Annex-B blob (NAL units separated by {@code 00 00 00 01} or
     * {@code 00 00 01} start codes) into its individual NAL units, <em>without</em>
     * the start codes.
     *
     * <p>Why: the wire carries bare NAL units (see
     * {@link damjay.control.ghosthand.net.GhostProtocol#TYPE_VIDEO_CONFIG}). Start
     * codes are a *framing* concern of a byte stream, not of a message - and the
     * guest re-adds them when it turns the bytes into {@code MediaFormat} csd, which
     * is where Annex-B framing is required again. KitKat's MediaCodec feeds csd-0/1
     * verbatim into codec-config input buffers with no fixup, so a start-code-less
     * SPS parses as nothing and the guest screen stays grey or turns green.
     */
    public static List<byte[]> splitStartCodes(byte[] data) {
        List<byte[]> out = new ArrayList<>();
        if (data == null || data.length == 0) {
            return out;
        }
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    starts.add(Integer.valueOf(i + 3));
                    i += 2;
                } else if (i + 3 < data.length && data[i + 3] == 1) {
                    starts.add(Integer.valueOf(i + 4));
                    i += 3;
                }
            }
        }
        if (starts.isEmpty()) {
            // No start codes at all: treat the whole blob as one NAL unit.
            out.add(data.clone());
            return out;
        }
        for (int k = 0; k < starts.size(); k++) {
            int from = starts.get(k).intValue();
            int to = (k + 1 < starts.size()) ? starts.get(k + 1).intValue() : data.length;
            // trim the trailing start-code prefix (00 00 00 / 00 00) of the next NAL
            while (to > from && data[to - 1] == 0) {
                to--;
            }
            if (to > from) {
                byte[] nal = new byte[to - from];
                System.arraycopy(data, from, nal, 0, to - from);
                out.add(nal);
            }
        }
        return out;
    }
}
