package damjay.control.ghosthand.guest;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import damjay.control.ghosthand.util.CodecCompat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Guest side: H.264 bytes -> pixels on a {@link Surface}.
 *
 * <pre>
 *   socket reader thread                     decoder thread
 *   -------------------                      --------------
 *   FrameCodec.readFully()      offer()      take()
 *   VIDEO_CONFIG / VIDEO  ---------------->  queueInputBuffer()
 *                                             dequeueOutputBuffer()
 *                                             releaseOutputBuffer(render=true)
 *                                                     |
 *                                                     v
 *                                              SurfaceView's Surface
 * </pre>
 *
 * <p>Two threads rather than one, because the socket read must never be blocked by
 * the decoder: if reading stalls, TCP's receive window closes, the host's writer
 * queue fills, and the host starts dropping frames. Decoupling them with a bounded
 * queue means a slow decoder degrades to dropped frames on the guest instead of
 * back-pressuring the whole session.
 *
 * <p><b>Frame pacing.</b> {@code releaseOutputBuffer(index, true)} renders as soon
 * as the compositor can take it, which would play a burst of buffered frames at
 * 200 fps and then starve. So we sleep until each frame's presentation time is due,
 * using our own clock anchored to the first frame we see. That is the "poor man's
 * video sync" - no AudioTrack to master against, since there is no audio (yet).
 */
public class VideoDecoder {

    private static final String TAG = "GhostHand/Decoder";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;

    /** Frames buffered between the socket thread and the decode thread. */
    private static final int MAX_PENDING = 90;

    /** If we fall this far behind, stop sleeping and catch up. */
    private static final long MAX_CATCHUP_NS = 250_000_000L; // 250 ms

    public interface Listener {
        /** Called when the decoder reports its real output size. Any thread. */
        void onVideoSizeChanged(int width, int height);

        /** One frame hit the surface. Used for the guest's fps readout. */
        void onFrameRendered(long ptsUs);

        void onDecoderError(String message);
    }

    /** What the socket thread hands us. */
    private static final class Item {
        final byte[] data;
        final long ptsUs;
        final boolean key;

        Item(byte[] data, long ptsUs, boolean key) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.key = key;
        }
    }

    private final Listener listener;
    private final BlockingQueue<Item> pending = new ArrayBlockingQueue<>(MAX_PENDING);

    private MediaCodec codec;
    private Surface surface;
    private Thread worker;
    private volatile boolean running;
    private volatile boolean configuredOnce;

    /** True while we have no SPS/PPS yet and must wait for a key frame. */
    private volatile boolean primed;

    private byte[] csd0 = new byte[0];
    private byte[] csd1 = new byte[0];
    private int lastWidth;
    private int lastHeight;

    // pacing
    private boolean clockAnchored;
    private long anchorSystemNs;
    private long anchorPtsUs;

    // stats
    private final Object statsLock = new Object();
    private int droppedInputs;
    private int renderedFrames;

    public VideoDecoder(Listener listener) {
        this.listener = listener;
    }

    // --------------------------- called by the reader -------------------------

    /**
     * Feeds the SPS/PPS the host sent in a VIDEO_CONFIG frame.
     *
     * @param sps raw SPS NAL bytes, <b>without</b> the 00 00 00 01 start code
     * @param pps raw PPS NAL bytes, <b>without</b> the start code
     */
    public void setCodecConfig(byte[] sps, byte[] pps, int width, int height) {
        this.csd0 = (sps != null) ? sps : new byte[0];
        this.csd1 = (pps != null) ? pps : new byte[0];
        if (width > 0) {
            this.lastWidth = width;
        }
        if (height > 0) {
            this.lastHeight = height;
        }
        this.primed = false;
        pending.clear();
        Log.i(TAG, "codec config: sps=" + csd0.length + "B pps=" + csd1.length + "B "
                + lastWidth + "x" + lastHeight);
    }

    /**
     * Queues one encoded access unit. Never blocks: if the queue is full we drop
     * non-key frames and, if even that does not help, the oldest queued frame.
     */
    public void offerVideo(byte[] data, int length, boolean key, long ptsUs) {
        if (!running) {
            return;
        }
        if (!primed) {
            if (!key) {
                // A stream cannot be decoded from a P frame. Wait for the next IDR.
                return;
            }
            if (csd0.length == 0) {
                // No SPS yet: the host sends VIDEO_CONFIG on connect, so this is
                // either a race or a broken peer. Drop and keep waiting.
                return;
            }
            primed = true;
        }
        byte[] copy = new byte[length];
        System.arraycopy(data, 0, copy, 0, length);
        Item item = new Item(copy, ptsUs, key);
        if (pending.offer(item)) {
            return;
        }
        // Behind: shed load. Key frames are precious, so drop a non-key frame first.
        if (!key) {
            bumpDropped();
            return;
        }
        Item evicted = pending.poll();
        if (evicted != null) {
            bumpDropped();
        }
        if (!pending.offer(item)) {
            bumpDropped();
        }
    }

    // ------------------------------- lifecycle --------------------------------

    /**
     * Starts decoding into {@code surface}. Call after the surface exists (i.e. from
     * {@code SurfaceHolder.Callback#surfaceCreated}), and again if it is recreated.
     */
    public void start(Surface surface) throws IOException {
        stop();
        this.surface = surface;

        MediaFormat format = MediaFormat.createVideoFormat(MIME,
                lastWidth > 0 ? lastWidth : 1280,
                lastHeight > 0 ? lastHeight : 720);
        if (csd0.length > 0) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));
        }
        if (csd1.length > 0) {
            format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1));
        }

        codec = MediaCodec.createDecoderByType(MIME);
        // The 3rd argument (Surface) is what makes the decoder render straight to
        // the display hardware; passing null would give us YUV byte buffers that we
        // would have to blit ourselves.
        codec.configure(format, surface, null, 0);
        codec.start();

        running = true;
        clockAnchored = false;
        worker = new Thread(this::decodeLoop, "ghosthand-decoder");
        worker.setPriority(Thread.NORM_PRIORITY + 1);
        worker.start();
        Log.i(TAG, "decoder started");
    }

    /** Stops and releases everything. Safe to call repeatedly. */
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
                // already broken
            }
            try {
                codec.release();
            } catch (RuntimeException ignored) {
                // nothing to do
            }
            codec = null;
        }
        pending.clear();
        primed = false;
        surface = null;
    }

    public boolean isRunning() {
        return running;
    }

    public int getDroppedInputs() {
        synchronized (statsLock) {
            return droppedInputs;
        }
    }

    public int getRenderedFrames() {
        synchronized (statsLock) {
            return renderedFrames;
        }
    }

    // ------------------------------ decode loop -------------------------------

    private void decodeLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            try {
                // 1) Push one encoded frame into the decoder's input buffer.
                Item item = pending.poll(20, TimeUnit.MILLISECONDS);
                if (item != null) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        // API 19 has no getInputBuffer(index) - see CodecCompat.
                        ByteBuffer in = CodecCompat.inputBuffer(codec, inIndex);
                        if (in != null) {
                            in.clear();
                            in.put(item.data);
                            codec.queueInputBuffer(inIndex, 0, item.data.length, item.ptsUs, 0);
                        } else {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, 0);
                        }
                    } else {
                        // No input buffer free: decoder is busy, requeue the frame.
                        requeue(item);
                    }
                }

                // 2) Pull everything the decoder has finished.
                drainOutput(info);
            } catch (IllegalStateException e) {
                if (running) {
                    Log.e(TAG, "decoder failed", e);
                    notifyError("decoder: " + e.getMessage());
                }
                running = false;
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                if (running) {
                    Log.e(TAG, "decoder error", e);
                    notifyError("decoder: " + e.getMessage());
                }
                running = false;
                break;
            }
        }
        Log.i(TAG, "decode loop finished");
    }

    private void requeue(Item item) throws InterruptedException {
        // Put it back at the head if we can; otherwise drop it and count the loss.
        if (!pending.offer(item, 30, TimeUnit.MILLISECONDS)) {
            bumpDropped();
        }
    }

    private void drainOutput(MediaCodec.BufferInfo info) {
        while (running) {
            int outIndex = codec.dequeueOutputBuffer(info, 5_000);

            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat format = codec.getOutputFormat();
                int w = format.containsKey(MediaFormat.KEY_WIDTH)
                        ? format.getInteger(MediaFormat.KEY_WIDTH) : lastWidth;
                int h = format.containsKey(MediaFormat.KEY_HEIGHT)
                        ? format.getInteger(MediaFormat.KEY_HEIGHT) : lastHeight;
                // Decoders pad to macroblock boundaries; crop tells us the real pixels.
                if (format.containsKey("crop-left") && format.containsKey("crop-right")) {
                    w = format.getInteger("crop-right") - format.getInteger("crop-left") + 1;
                }
                if (format.containsKey("crop-top") && format.containsKey("crop-bottom")) {
                    h = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1;
                }
                if (w > 0 && h > 0 && (w != lastWidth || h != lastHeight)) {
                    lastWidth = w;
                    lastHeight = h;
                    Log.i(TAG, "output format changed -> " + w + "x" + h);
                    if (listener != null) {
                        listener.onVideoSizeChanged(w, h);
                    }
                }
                continue;
            }

            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return; // nothing ready; go read more input
            }
            if (outIndex < 0) {
                continue;
            }

            boolean render = info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
            try {
                if (render) {
                    pace(info.presentationTimeUs);
                    // true = present this buffer on the Surface we configured with.
                    codec.releaseOutputBuffer(outIndex, true);
                    synchronized (statsLock) {
                        renderedFrames++;
                    }
                    if (listener != null) {
                        listener.onFrameRendered(info.presentationTimeUs);
                    }
                } else {
                    codec.releaseOutputBuffer(outIndex, false);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "releaseOutputBuffer failed: " + e.getMessage());
                return;
            }

            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                return;
            }
        }
    }

    /**
     * Sleeps until this frame's presentation time is due.
     *
     * <p>The encoder's {@code presentationTimeUs} values come from the host's
     * {@code System.nanoTime()}-ish clock, so their <em>absolute</em> value means
     * nothing to us - only the deltas do. We anchor on the first frame: from then
     * on, frame N should appear at {@code anchorSystem + (ptsN - anchorPts)}.
     */
    private void pace(long ptsUs) {
        long nowNs = System.nanoTime();
        if (!clockAnchored) {
            anchorSystemNs = nowNs;
            anchorPtsUs = ptsUs;
            clockAnchored = true;
            return;
        }
        long dueNs = anchorSystemNs + (ptsUs - anchorPtsUs) * 1000L;
        long waitNs = dueNs - nowNs;
        if (waitNs > MAX_CATCHUP_NS) {
            // Host clock jumped, or we were paused for ages: re-anchor.
            anchorSystemNs = nowNs;
            anchorPtsUs = ptsUs;
            return;
        }
        while (waitNs > 1_500_000L) { // more than 1.5 ms to wait: sleep properly
            try {
                Thread.sleep(waitNs / 1_000_000L, (int) (waitNs % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            waitNs = dueNs - System.nanoTime();
            if (waitNs <= 0) {
                return;
            }
        }
        if (waitNs > 0) {
            // Under 1.5 ms: busy-wait, because sleeping costs more than it saves.
            while (System.nanoTime() < dueNs) {
                Thread.yield();
            }
        }
    }

    /**
     * Counts a dropped input frame <em>and</em> re-arms the key-frame gate.
     *
     * <p>H.264 frames reference each other, so once we lose one, every following
     * P frame decodes against garbage until the next IDR. Setting {@code primed =
     * false} makes {@link #offerVideo} discard everything up to that next key frame,
     * which turns "smearing green blocks for seconds" into "one short freeze".
     */
    private void bumpDropped() {
        synchronized (statsLock) {
            droppedInputs++;
        }
        primed = false;
    }

    private void notifyError(final String message) {
        if (listener != null) {
            listener.onDecoderError(message);
        }
    }
}
