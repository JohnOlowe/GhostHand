package damjay.control.ghosthand.host;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import damjay.control.ghosthand.HostActivity;
import damjay.control.ghosthand.R;
import damjay.control.ghosthand.net.Frame;
import damjay.control.ghosthand.net.GhostProtocol;
import damjay.control.ghosthand.net.Record;
import damjay.control.ghosthand.util.ApiLevels;

import java.io.IOException;

/**
 * The host's engine room. One foreground service owns the whole capture session:
 *
 * <pre>
 *   HostActivity
 *      |  ACTION_START (+ resultCode/data from the MediaProjection consent dialog)
 *      v
 *   ScreenCaptureService  (foreground, type = mediaProjection)
 *      |-- MediaProjection        the permission token the user just granted
 *      |-- VirtualDisplay         renders the screen into the encoder's Surface
 *      |-- ScreenEncoder          Surface -> H.264 access units
 *      |-- ClientHub              TCP server -> every connected guest
 *      '-- HostController         mDNS advert + "what is my IP?" helper
 * </pre>
 *
 * <p><b>Why a service and not the activity?</b> Because Android only lets an app
 * hold a MediaProjection inside a foreground service (Android 10+), and because the
 * stream should survive the host putting the app away or rotating the device.
 *
 * <p><b>Android 14 (API 34) ordering rule.</b> {@code startForeground()} with
 * {@code FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION} must happen <em>before</em>
 * {@code getMediaProjection()}, otherwise you get a SecurityException. That is why
 * {@link #onStartCommand} promotes the service first and only then touches the
 * projection - see {@link #beginStreaming}.
 *
 * <p><b>State visibility.</b> The activity is not bound to this service; it reads
 * the three static fields ({@link #state}, {@link #lastError}, {@link #live}) and
 * listens to the {@link #ACTION_STATE} / {@link #ACTION_STATS} broadcasts. That is
 * deliberately blunt and easy to follow - a bound AIDL service would be more
 * "correct" but far harder to read for a first milestone.
 */
public class ScreenCaptureService extends Service
        implements ClientHub.Listener, ScreenEncoder.Listener {

    private static final String TAG = "GhostHand/Service";

    // ------------------------------ intents ---------------------------------
    public static final String ACTION_START = "damjay.control.ghosthand.action.START_CAPTURE";
    public static final String ACTION_STOP = "damjay.control.ghosthand.action.STOP_CAPTURE";
    public static final String ACTION_SET_MODE = "damjay.control.ghosthand.action.SET_CAPTURE_MODE";

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_MAX_SIDE = "maxSide";
    /** true = mirror the built-in display; false = behave like a second display. */
    public static final String EXTRA_MIRROR_FLAG = "mirrorFlag";
    /**
     * Broadcast extra, set only by the service: Android 14+ allows exactly one capture
     * per consent grant, so changing the capture mode needs the system confirmation
     * dialog again. The activity reacts by re-running its consent launcher; until the
     * answer arrives the session keeps streaming on the mode it has now.
     */
    public static final String EXTRA_RECONSENT = "reconsent";

    /** Broadcast: something about the session changed (state, client count, log). */
    public static final String ACTION_STATE = "damjay.control.ghosthand.broadcast.STATE";
    /** Broadcast: once-per-second telemetry. */
    public static final String ACTION_STATS = "damjay.control.ghosthand.broadcast.STATS";
    public static final String EXTRA_MESSAGE = "message";

    // ------------------------------- states ---------------------------------
    public static final int STATE_IDLE = 0;
    public static final int STATE_RUNNING = 1;
    public static final int STATE_ERROR = 2;

    /** Mirrors the live service state so an activity can read it without binding. */
    public static volatile int state = STATE_IDLE;
    public static volatile String lastError = null;
    public static volatile ScreenCaptureService live = null;

    private static final String CHANNEL_ID = "ghosthand_capture";
    private static final int NOTIFICATION_ID = 4711;

    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private ScreenEncoder encoder;
    private ClientHub hub;
    private HostController controller;
    private PowerManager.WakeLock wakeLock;

    /** Target frame rate for the whole session. */
    private static final int FPS = 30;

    /**
     * Buffers guest touches into gestures. One instance for the whole service: a
     * DOWN/MOVE/UP sequence is one gesture, so the state has to live across events.
     */
    private final TouchInjector touchInjector = new TouchInjector();

    private int videoWidth;
    private int videoHeight;
    private int rotation;
    private int densityDpi;
    private int currentBitrate;
    private int maxSide = 1280;
    private boolean useMirrorFlag = true;

    /** Last SPS/PPS so a re-created encoder can be pushed to existing guests. */
    private byte[] lastCodecConfig;

    // telemetry, written on the encoder thread, read on the main thread
    private final Object statsLock = new Object();
    private long statWindowStartMs;
    private int statFrames;
    private long statBytes;
    private int statKeyframes;
    private long statDropped;
    private int statFps;
    private int statKbps;
    private long startedAtMs;

    // --------------------------- service plumbing ----------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        live = this;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Hosting is API 21+: MediaProjection, VirtualDisplay-into-a-MediaProjection and
        // the foreground-service mediaProjection type all arrived in Android 5.0. The
        // activity already refuses to get here, so this is the last line of defence -
        // a service can also be restarted by the system from an old intent.
        if (!ApiLevels.canHost(Build.VERSION.SDK_INT)) {
            Log.w(TAG, "screen capture requested on API " + Build.VERSION.SDK_INT
                    + "; the host role needs API " + ApiLevels.MIN_HOST_API);
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "onStartCommand " + action);

        if (ACTION_STOP.equals(action)) {
            stopEverything("stopped by user");
            return START_NOT_STICKY;
        }

        if (ACTION_SET_MODE.equals(action)) {
            boolean mirror = intent.getBooleanExtra(EXTRA_MIRROR_FLAG, true);
            if (mirror != useMirrorFlag && projection != null) {
                if (CapturePolicy.needsFreshConsentForModeChange(Build.VERSION.SDK_INT)) {
                    // Android 14+: the system marks a grant "used" the moment its first
                    // virtual display exists (MediaProjectionManagerService.isValid():
                    // mVirtualDisplayId != INVALID_DISPLAY) and every later
                    // createVirtualDisplay throws SecurityException - releasing the old
                    // display does not reset it. The only way to a fresh instance is a
                    // fresh consent dialog, so ask the activity for one. This broadcast
                    // reports the LIVE mode, not the requested one: the switch shows what
                    // is actually capturing until the system says yes, and a "no" leaves
                    // the session untouched (nothing is pending here).
                    Log.i(TAG, "capture mode change needs a fresh consent (Android 14 one-capture rule)");
                    broadcastState("capture mode change: confirm screen capture again in the system dialog");
                } else {
                    // Below 14 one grant may create displays one after another: swap
                    // AUTO_MIRROR <-> PUBLIC in place, encoder and guests untouched.
                    useMirrorFlag = mirror;
                    recreateVirtualDisplay();
                    log("capture mode: " + (mirror ? "AUTO_MIRROR" : "PUBLIC (second display)"));
                }
            }
            return START_NOT_STICKY;
        }

        if (!ACTION_START.equals(action)) {
            // Started without an action (e.g. by the system): nothing to do.
            if (state == STATE_IDLE) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        // ---- PROMOTE TO FOREGROUND FIRST (Android 14 ordering rule) ----
        Notification notification = buildNotification(getString(R.string.notif_starting));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        android.content.Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        maxSide = intent.getIntExtra(EXTRA_MAX_SIDE, 1280);
        boolean mirror = intent.getBooleanExtra(EXTRA_MIRROR_FLAG, true);

        if (data == null) {
            fail("no MediaProjection permission data in the start intent");
            return START_NOT_STICKY;
        }
        if (projection != null && hub != null && hub.isRunning()) {
            // A consent result while a session is already live is the answer to
            // EXTRA_RECONSENT: a brand-new grant for a mode change. Swap it in without
            // touching encoder, TCP server or guests (Android 14+ path only - below 14
            // SET_MODE recreates the display in place and never re-prompts).
            if (CapturePolicy.needsFreshConsentForModeChange(Build.VERSION.SDK_INT)) {
                swapProjection(resultCode, data, mirror);
            } else {
                log("already streaming");
            }
            return START_NOT_STICKY;
        }
        useMirrorFlag = mirror;
        beginStreaming(resultCode, data);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // started service, not a bound one
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The device rotated. Neither the encoder nor the VirtualDisplay can change
        // size while running, so the capture half of the pipeline is rebuilt. The
        // projection, the TCP server and every guest socket stay up.
        if (projection == null || encoder == null) {
            return;
        }
        int oldW = videoWidth;
        int oldH = videoHeight;
        computeCaptureSize();
        if (videoWidth == oldW && videoHeight == oldH) {
            return;
        }
        Log.i(TAG, "configuration changed " + oldW + "x" + oldH
                + " -> " + videoWidth + "x" + videoHeight);
        reconfigureCapture("screen rotated");
    }

    /**
     * Rebuilds encoder + virtual display at the size {@link #computeCaptureSize()}
     * just derived. Guests keep their sockets; they simply receive a new
     * VIDEO_CONFIG followed by a fresh key frame, and their decoder restarts.
     */
    private void reconfigureCapture(String reason) {
        // The VirtualDisplay must outlive the encoder: Android 14 forbids a second
        // createVirtualDisplay on the same grant, and releasing + rebuilding was how a
        // rotation on an Android 14 host crashed with the system's one-capture
        // SecurityException. Detach the surface that is about to die, rebuild the
        // encoder, then point the SAME display at the new surface at the new size -
        // resize(), not recreate(). Guests keep their sockets throughout.
        if (virtualDisplay != null) {
            try {
                virtualDisplay.setSurface(null);
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
        if (encoder != null) {
            encoder.stop();
            encoder = null;
        }
        try {
            createEncoder();
            if (virtualDisplay != null) {
                virtualDisplay.resize(videoWidth, videoHeight,
                        densityDpi > 0 ? densityDpi : 320);
                virtualDisplay.setSurface(encoder.getInputSurface());
            } else {
                createVirtualDisplay();
            }
            if (hub != null) {
                hub.setGeometry(videoWidth, videoHeight, rotation);
            }
            broadcastState();
            log("capture restarted at " + videoWidth + "x" + videoHeight + " (" + reason + ")");
        } catch (IOException e) {
            fail("could not restart capture: " + e.getMessage());
        } catch (RuntimeException e) {
            fail("could not restart capture: " + e);
        }
    }

    /** Reads the real panel size and derives the 16-aligned capture size. */
    private void computeCaptureSize() {
        DisplayMetrics metrics = realMetrics();
        int[] size = GhostProtocol.fitCaptureSize(
                metrics.widthPixels, metrics.heightPixels, maxSide);
        videoWidth = size[0];
        videoHeight = size[1];
        rotation = rotationFromMetrics(metrics);
        densityDpi = metrics.densityDpi;
        currentBitrate = GhostProtocol.suggestedBitrate(videoWidth, videoHeight, FPS);
    }

    /** Creates and starts the H.264 encoder for the current capture size. */
    private void createEncoder() throws IOException {
        encoder = new ScreenEncoder(videoWidth, videoHeight, currentBitrate, FPS, this);
        encoder.start();
    }

    @Override
    public void onDestroy() {
        stopEverything("service destroyed");
        live = null;
        super.onDestroy();
    }

    // ---------------------------- session startup ----------------------------

    private void beginStreaming(int resultCode, Intent data) {
        if (hub != null && hub.isRunning()) {
            log("already streaming");
            return;
        }
        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, data);
            if (projection == null) {
                fail("system refused the screen-capture permission");
                return;
            }

            registerProjectionCallback(projection);

            computeCaptureSize();

            // 1) TCP server first: guests may already be trying to connect.
            hub = new ClientHub(GhostProtocol.DEFAULT_PORT, this);
            hub.start();

            // 2) mDNS advert + IP listing so the guest can find us.
            controller = new HostController();
            controller.start(this, getString(R.string.nsd_service_name));
            for (String addr : controller.listAddresses(GhostProtocol.DEFAULT_PORT)) {
                log("reachable at " + addr);
            }

            // 3) encoder -> its input Surface is what the VirtualDisplay draws into.
            // 4) the virtual display itself.
            createEncoder();
            createVirtualDisplay();
            hub.setGeometry(videoWidth, videoHeight, rotation);

            acquireWakeLock();
            startedAtMs = SystemClock.elapsedRealtime();
            statWindowStartMs = startedAtMs;
            state = STATE_RUNNING;
            lastError = null;
            updateNotification(getString(R.string.notif_running));
            broadcastState();
            log("capturing " + videoWidth + "x" + videoHeight + " @" + FPS + "fps, "
                    + (currentBitrate / 1000) + " kbps");
            main.postDelayed(statsTick, 1000);
        } catch (IOException e) {
            fail("could not start: " + e.getMessage());
        } catch (SecurityException e) {
            fail("permission problem: " + e.getMessage());
        } catch (RuntimeException e) {
            Log.e(TAG, "start failed", e);
            fail("start failed: " + e);
        }
    }

    /**
     * Android 14 also refuses the first createVirtualDisplay unless a callback is
     * registered on the grant ("No MediaProjection callback registered"), and the
     * callback is how we notice the user tapped "Stop sharing" in the system shade -
     * without it the guest would just see a frozen frame. One callback per live grant;
     * {@link #projectionCallback} always tracks the current one so teardown and
     * {@link #swapProjection} can unregister it before the grant is retired.
     */
    private void registerProjectionCallback(MediaProjection target) {
        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.i(TAG, "MediaProjection.onStop()");
                main.post(() -> stopEverything("screen capture permission revoked"));
            }
        };
        target.registerCallback(projectionCallback, main);
    }

    private void createVirtualDisplay() {
        Surface input = encoder.getInputSurface();
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
        if (!useMirrorFlag) {
            // PUBLIC makes the virtual display a real second display: apps render
            // into it instead of the built-in screen, so you see a fresh home screen
            // rather than a copy. Useful for "control a second session" later.
            flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
        }
        virtualDisplay = projection.createVirtualDisplay(
                "GhostHand",
                videoWidth, videoHeight, densityDpi > 0 ? densityDpi : 320,
                flags,
                input,
                // VirtualDisplay.Callback is the interface DisplayManager
                // .DisplayCallback implements; createVirtualDisplay() accepts both.
                // We use the interface because it has been public since API 21 and
                // is the one every android.jar stub exposes.
                new VirtualDisplay.Callback() {
                    @Override
                    public void onPaused() {
                        Log.i(TAG, "virtual display paused");
                    }

                    @Override
                    public void onResumed() {
                        Log.i(TAG, "virtual display resumed");
                    }

                    @Override
                    public void onStopped() {
                        Log.i(TAG, "virtual display stopped");
                    }
                },
                main);
        if (virtualDisplay == null) {
            throw new IllegalStateException("createVirtualDisplay returned null");
        }
    }

    /**
     * Swap AUTO_MIRROR <-> PUBLIC without asking the user for permission again.
     * Only the VirtualDisplay is rebuilt: the flag is a property of the display,
     * not of the encoder, so the stream continues uninterrupted.
     *
     * <p>Legal only below Android 14, where one grant may create displays one after
     * another. On 14+ the first createVirtualDisplay permanently marks the grant used
     * and this would throw SecurityException from the system's own isValid() check -
     * the ACTION_SET_MODE branch routes that case through a fresh consent instead
     * (see {@link #swapProjection}).
     */
    private void recreateVirtualDisplay() {
        if (projection == null || encoder == null) {
            return;
        }
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (RuntimeException ignored) {
                // best effort
            }
            virtualDisplay = null;
        }
        createVirtualDisplay();
    }

    /**
     * Android 14 mode change: put a freshly consented grant under the running session.
     *
     * <p>The encoder keeps encoding and the guests keep their sockets; only the
     * permission token and its virtual display are replaced. Ordering matters at every
     * step: the new grant is fetched <em>before</em> anything live is touched (if the
     * system refuses, the session is simply unchanged), the old callback is
     * unregistered before the old grant is stopped (or its onStop would tear the new
     * session down), and the new display is the <em>first</em> one ever created on the
     * new grant - which is exactly what Android 14 requires.
     */
    private void swapProjection(int resultCode, Intent data, boolean mirror) {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MediaProjection next;
        try {
            next = manager.getMediaProjection(resultCode, data);
        } catch (RuntimeException e) {
            Log.e(TAG, "mode change: getMediaProjection failed", e);
            broadcastState(); // live mode unchanged; the switch snaps back
            return;
        }
        if (next == null) {
            log("system refused the screen-capture permission");
            broadcastState();
            return;
        }

        MediaProjection oldProjection = projection;
        MediaProjection.Callback oldCallback = projectionCallback;
        if (oldCallback != null) {
            try {
                oldProjection.unregisterCallback(oldCallback);
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (RuntimeException ignored) {
                // best effort
            }
            virtualDisplay = null;
        }
        try {
            oldProjection.stop();
        } catch (RuntimeException ignored) {
            // best effort
        }

        projection = next;
        registerProjectionCallback(next);
        useMirrorFlag = mirror;
        try {
            createVirtualDisplay(); // the first (and only) display on the fresh grant
        } catch (RuntimeException e) {
            // Android 14 will never create another display for either grant now, so the
            // session cannot continue - and fail() retires `next`, which is live.
            fail("could not apply capture mode: " + e);
            return;
        }
        log("capture mode: " + (mirror ? "AUTO_MIRROR" : "PUBLIC (second display)"));
        broadcastState();
    }

    // ------------------------------ teardown ---------------------------------

    private void stopEverything(String reason) {
        stopEverything(reason, false);
    }

    /**
     * Full teardown in dependency order: hub (sockets) -> controller (mDNS) ->
     * virtual display -> encoder -> projection -> wake lock.
     *
     * @param keepErrorState when true the caller ({@link #fail}) owns the state
     *                       fields, so we must not reset them to IDLE.
     */
    private void stopEverything(String reason, boolean keepErrorState) {
        Log.i(TAG, "stopEverything: " + reason);
        main.removeCallbacks(statsTick);
        // Drop a half-finished drag: injecting it later would tap some random app.
        touchInjector.reset();

        if (hub != null) {
            hub.stop();
            hub = null;
        }
        if (controller != null) {
            controller.stop(this);
            controller = null;
        }
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (RuntimeException ignored) {
                // already gone
            }
            virtualDisplay = null;
        }
        if (encoder != null) {
            encoder.stop();
            encoder = null;
        }
        if (projection != null) {
            if (projectionCallback != null) {
                try {
                    projection.unregisterCallback(projectionCallback);
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
            try {
                projection.stop();
            } catch (RuntimeException ignored) {
                // best effort
            }
            projection = null;
            projectionCallback = null;
        }
        releaseWakeLock();

        boolean wasRunning = (state == STATE_RUNNING);
        if (!keepErrorState) {
            state = STATE_IDLE;
            if (wasRunning) {
                lastError = null;
            }
        }
        if (wasRunning) {
            broadcastState();
            log("stopped: " + reason);
        }
        // We are a foreground service; nothing should resurrect us.
        stopForeground(true);
        stopSelf();
    }

    /**
     * Sets the error state <em>first</em>, then tears down. The order matters: the
     * activity reads {@link #state}/{@link #lastError} when it receives the
     * broadcast, so by the time the broadcast lands the error must already be there.
     */
    private void fail(String message) {
        Log.e(TAG, "fail: " + message);
        state = STATE_ERROR;
        lastError = message;
        broadcastState();
        stopEverything(message, true);
    }

    // ------------------------- ScreenEncoder.Listener -------------------------
    // All three run on the encoder thread.

    @Override
    public void onCodecConfig(byte[] config) {
        lastCodecConfig = config;
        ClientHub h = hub;
        if (h != null) {
            h.setVideoConfig(config);
        }
        log("sent decoder config (" + config.length + " bytes SPS+PPS)");
    }

    @Override
    public void onEncodedFrame(byte[] data, int length, boolean key, long ptsUs) {
        ClientHub h = hub;
        if (h == null) {
            return;
        }
        byte[] payload = new byte[length];
        System.arraycopy(data, 0, payload, 0, length);
        byte flags = key ? GhostProtocol.FLAG_KEYFRAME : (byte) 0;
        h.broadcast(new Frame(GhostProtocol.TYPE_VIDEO, flags, ptsUs, payload));

        synchronized (statsLock) {
            statFrames++;
            statBytes += length;
            if (key) {
                statKeyframes++;
            }
        }
    }

    @Override
    public void onEncoderError(String message) {
        main.post(() -> fail(message));
    }

    // --------------------------- ClientHub.Listener ---------------------------
    // Both are already marshalled to the main thread by the hub.

    @Override
    public void onClientCountChanged(int clients) {
        // A new guest wants a key frame right now instead of waiting up to 2 s.
        if (clients > 0 && encoder != null) {
            encoder.requestKeyFrame();
        }
        updateNotification(getString(R.string.notif_running) + " - " + clients + " guest(s)");
        broadcastState();
    }

    @Override
    public void onLog(String message) {
        log(message);
    }

    /**
     * Guest touch -> host gesture. Runs on the reader thread; the injector's decision
     * logic is thread-safe by construction (one instance, only this method touches it)
     * and only the dispatch itself hops to the main thread.
     */
    @Override
    public void onGuestTouch(String clientName, int action, int xNormalized,
                             int yNormalized, long timeMs) {
        if (!ApiLevels.canInject(Build.VERSION.SDK_INT)) {
            // API 21-23: the service can be enabled in Settings but dispatchGesture()
            // does not exist, so never even look for it. (This is also why the old
            // "enable touch control" hint below is version-aware.)
            return;
        }
        InjectionAccessibilityService injector = InjectionAccessibilityService.instance();
        if (injector == null) {
            // No accessibility grant: still acknowledge the gesture so the guest's
            // user learns why nothing happens, but do not spam a log line per event.
            if (action == TouchInjector.ACTION_DOWN) {
                log(ApiLevels.canInject(Build.VERSION.SDK_INT)
                        ? "touch from " + clientName + " ignored - enable touch control "
                          + "(accessibility) on this phone"
                        : "touch from " + clientName + " ignored - gesture injection "
                          + "needs Android 7.0, this phone is API "
                          + Build.VERSION.SDK_INT);
            }
            return;
        }

        int[] size = injector.displaySize();
        int x = TouchInjector.toPixels(xNormalized, size[0]);
        int y = TouchInjector.toPixels(yNormalized, size[1]);

        TouchInjector.Gesture gesture = touchInjector.onTouch(action, x, y, timeMs);
        if (gesture == null) {
            return; // still buffering a drag, or a cancelled gesture
        }
        // dispatchGesture must be called on the main thread.
        final TouchInjector.Gesture ready = gesture;
        main.post(() -> {
            InjectionAccessibilityService svc = InjectionAccessibilityService.instance();
            if (svc != null && svc.inject(ready)) {
                log("injected " + ready + " from " + clientName);
            }
        });
    }

    // ------------------------------- telemetry --------------------------------

    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            if (state != STATE_RUNNING) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            int frames;
            long bytes;
            synchronized (statsLock) {
                frames = statFrames;
                bytes = statBytes;
                statFrames = 0;
                statBytes = 0;
            }
            long windowMs = Math.max(1, now - statWindowStartMs);
            statWindowStartMs = now;
            statFps = (int) (frames * 1000L / windowMs);
            statKbps = (int) (bytes * 8L / windowMs);

            int clients = (hub != null) ? hub.getClientCount() : 0;
            statDropped = (hub != null) ? hub.getTotalDropped() : 0;

            Record stats = Record.create()
                    .putInt("fps", statFps)
                    .putInt("kbps", statKbps)
                    .putInt("keyframes", statKeyframes)
                    .putInt("dropped", statDropped)
                    .putInt("clients", clients)
                    .putInt("uptimeMs", now - startedAtMs)
                    .putInt("w", videoWidth)
                    .putInt("h", videoHeight);
            if (hub != null) {
                // Guests render this in their status bar, so they can tell a
                // slow WiFi link from a stalled encoder.
                hub.broadcastStats(stats);
            }

            Intent broadcast = new Intent(ACTION_STATS);
            broadcast.setPackage(getPackageName());
            broadcast.putExtra("fps", statFps);
            broadcast.putExtra("kbps", statKbps);
            broadcast.putExtra("clients", clients);
            broadcast.putExtra("dropped", (int) statDropped);
            broadcast.putExtra("uptimeMs", now - startedAtMs);
            sendBroadcast(broadcast);

            main.postDelayed(this, 1000);
        }
    };

    // ------------------------------- notification -----------------------------

    private void createNotificationChannel() {
        // NotificationChannel itself is API 26. Until Android 8.0 there are no channels
        // and NotificationCompat ignores the channel id we hand it, so there is nothing
        // to create - but the constructor call still has to not happen, or a host on
        // 5.0-7.1 dies in onCreate with NoClassDefFoundError. (This is exactly what
        // happened when minSdk dropped below 26 and the host role became reachable on
        // API 21: every other version-gated call in this file was already guarded, and
        // check_api.py pointed at the one that was not.)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW); // no sound, no heads-up
        channel.setDescription(getString(R.string.notif_channel_desc));
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, HostActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, ScreenCaptureService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // NotificationCompat, not android.app.Notification.Builder: the compat builder
        // applies the small-icon masking that Android 5+ requires (a full-colour icon
        // is rendered as a white blob without it) and keeps the action/trampoline
        // behaviour identical across API levels we support.
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_mirror)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setContentIntent(open)
                .addAction(0, getString(R.string.notif_stop), stop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    private void updateNotification(String text) {
        // NotificationManagerCompat.notify() is the same call on API 26+, but it is
        // where the compat layer would drop the notification if the POST_NOTIFICATIONS
        // grant is missing on Android 13+ instead of throwing SecurityException.
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text));
    }

    // --------------------------------- helpers --------------------------------

    private void broadcastState() {
        broadcastState(null);
    }

    /**
     * @param reconsentMessage non-null asks the activity to re-run the MediaProjection
     *        consent dialog (Android 14 one-capture-per-grant rule); the text is
     *        appended to its log. "mirror" still carries the LIVE mode - the requested
     *        one is not active until ACTION_START brings the fresh grant.
     */
    private void broadcastState(String reconsentMessage) {
        Intent intent = new Intent(ACTION_STATE);
        intent.setPackage(getPackageName());
        intent.putExtra("state", state);
        intent.putExtra("error", lastError == null ? "" : lastError);
        intent.putExtra("clients", hub != null ? hub.getClientCount() : 0);
        intent.putExtra("width", videoWidth);
        intent.putExtra("height", videoHeight);
        intent.putExtra("mirror", useMirrorFlag);
        if (reconsentMessage != null) {
            intent.putExtra(EXTRA_RECONSENT, true);
            intent.putExtra("message", reconsentMessage);
        }
        sendBroadcast(intent);
    }

    private void log(String message) {
        Log.i(TAG, message);
        Intent intent = new Intent(ACTION_STATE);
        intent.setPackage(getPackageName());
        intent.putExtra("state", state);
        intent.putExtra("message", message);
        intent.putExtra("clients", hub != null ? hub.getClientCount() : 0);
        intent.putExtra("width", videoWidth);
        intent.putExtra("height", videoHeight);
        // Every broadcast the activity's receiver reads must carry the live mode: the
        // receiver defaults a missing "mirror" to true, and a fabricated true on a
        // PUBLIC session used to send a contradictory ACTION_SET_MODE back here.
        intent.putExtra("mirror", useMirrorFlag);
        sendBroadcast(intent);
    }

    private DisplayMetrics realMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm != null && wm.getDefaultDisplay() != null) {
            // getRealMetrics includes the navigation bar / cutouts: the true panel size.
            wm.getDefaultDisplay().getRealMetrics(metrics);
        }
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            metrics.widthPixels = 1080;
            metrics.heightPixels = 1920;
            metrics.densityDpi = 420;
        }
        return metrics;
    }

    /** 0/1/2/3 == Surface.ROTATION_0/90/180/270, as an int we can put in a frame. */
    private int rotationFromMetrics(DisplayMetrics metrics) {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm != null && wm.getDefaultDisplay() != null) {
            return wm.getDefaultDisplay().getRotation();
        }
        return metrics.widthPixels > metrics.heightPixels ? 1 : 0;
    }

    @SuppressWarnings("deprecation")
    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || wakeLock != null) {
            return;
        }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GhostHand::capture");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(4 * 60 * 60 * 1000L /* hard cap: 4 h */);
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
            wakeLock = null;
        }
    }

    /** Convenience for activities: is a session currently live? */
    public static boolean isStreaming() {
        return state == STATE_RUNNING && live != null;
    }
}
