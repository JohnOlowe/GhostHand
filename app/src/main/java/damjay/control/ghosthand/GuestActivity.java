package damjay.control.ghosthand;

import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import damjay.control.ghosthand.guest.GuestController;
import damjay.control.ghosthand.guest.VideoDecoder;
import damjay.control.ghosthand.net.GhostProtocol;
import damjay.control.ghosthand.net.Record;

/**
 * The guest: everything the other phone is doing, drawn here.
 *
 * <p><b>Who does what</b>
 * <pre>
 *   GuestActivity    UI, surface ownership, touch capture, letterboxing
 *   GuestController  mDNS discovery, socket, frame parsing    (own thread)
 *   VideoDecoder     MediaCodec H.264 decode + frame pacing   (own thread)
 *   SurfaceView      the actual pixels                        (compositor)
 * </pre>
 *
 * <p><b>Why a SurfaceView and not an ImageView?</b> A SurfaceView owns a separate
 * window that SurfaceFlinger composites under the app's UI, and MediaCodec can render
 * into it without a single pixel passing through the Java heap. The price is that the
 * surface is created and destroyed asynchronously, so the decoder is started only from
 * {@code surfaceCreated} and stopped in {@code surfaceDestroyed} - drawing into a dead
 * surface throws.
 *
 * <p><b>Two things must both be true before decoding can start:</b> the host's SPS/PPS
 * (a VIDEO_CONFIG frame) and a live Surface. Either can arrive first, so both paths
 * funnel into {@link #startDecoderIfNeeded()}.
 *
 * <p><b>What AndroidX changed here.</b> Three things:
 * <ul>
 *   <li>the discovered-host list is an {@link RecyclerView} with a real adapter
 *       instead of inflating item views into a LinearLayout on every update;</li>
 *   <li>full-screen mode uses {@link WindowInsetsControllerCompat}, the
 *       {@code setSystemUiVisibility} replacement that also works on API 30+ where the
 *       old flags are ignored;</li>
 *   <li>the IP field is a Material {@link TextInputLayout} + {@link TextInputEditText},
 *       so errors and hints are drawn by the component, not by hand.</li>
 * </ul>
 */
public class GuestActivity extends AppCompatActivity implements GuestController.Listener {

    private static final int DEFAULT_PORT = GhostProtocol.DEFAULT_PORT;

    // views (see res/layout/activity_guest.xml)
    private FrameLayout root;
    private FrameLayout videoContainer;
    private SurfaceView surfaceVideo;
    private TextView txtOverlay;
    private TextView txtStatus;
    private TextView txtLog;
    private TextView txtStatFps;
    private TextView txtStatSize;
    private TextView txtStatPing;
    private TextView txtNoHosts;
    private View dotStatus;
    private MaterialCardView panelConnect;
    private RecyclerView listHosts;
    private TextInputLayout boxHost;
    private TextInputEditText edtHost;
    private MaterialButton btnConnect;
    private MaterialButton btnDisconnect;

    private HostAdapter hostAdapter;
    private GuestController controller;
    private VideoDecoder decoder;

    private Surface videoSurface;
    private boolean surfaceReady;

    private byte[] pendingSps;
    private byte[] pendingPps;
    private int streamWidth;
    private int streamHeight;
    private boolean decoderRunning;
    private boolean connected;
    private boolean immersive;

    // touch throttling
    private long lastTouchSentMs;
    private float lastTouchX = -1f;
    private float lastTouchY = -1f;

    // fps readout, counted here because VideoDecoder.onFrameRendered fires per frame
    private int renderedWindow;
    private long renderedWindowStartMs;

    private final SimpleDateFormat logTime = new SimpleDateFormat("HH:mm:ss", Locale.US);

    // --------------------------------------------------------------------------
    // Lifecycle
    // --------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guest);

        root = findViewById(R.id.root);
        videoContainer = findViewById(R.id.videoContainer);
        surfaceVideo = findViewById(R.id.surfaceVideo);
        txtOverlay = findViewById(R.id.txtOverlay);
        txtStatus = findViewById(R.id.txtStatus);
        txtLog = findViewById(R.id.txtLog);
        txtStatFps = findViewById(R.id.txtStatFps);
        txtStatSize = findViewById(R.id.txtStatSize);
        txtStatPing = findViewById(R.id.txtStatPing);
        txtNoHosts = findViewById(R.id.txtNoHosts);
        dotStatus = findViewById(R.id.dotStatus);
        panelConnect = findViewById(R.id.panelConnect);
        listHosts = findViewById(R.id.listHosts);
        boxHost = findViewById(R.id.boxHost);
        edtHost = findViewById(R.id.edtHost);
        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);

        setupHostList();
        controller = new GuestController(this);
        decoder = new VideoDecoder(decoderListener);

        setupSurface();
        setupControls();
        setupTouchForwarding();
        setupSystemControls();

        // The root lays out asynchronously and letterboxing needs real pixel sizes,
        // so re-run the calculation whenever the layout changes.
        root.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or2, ob) -> applyVideoAspect());

        controller.startDiscovery(this);
        appendLog("searching for hosts on " + GhostProtocol.NSD_SERVICE_TYPE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!controller.isConnected()) {
            controller.startDiscovery(this);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Discovery is a radio operation; do not keep it running while hidden.
        controller.stopDiscovery();
    }

    @Override
    protected void onDestroy() {
        controller.disconnect("activity destroyed");
        if (decoder != null) {
            decoder.stop();
            decoder = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (connected) {
            // First back press leaves the video, second one leaves the activity.
            disconnect("user pressed back");
            return;
        }
        super.onBackPressed();
    }

    // --------------------------------------------------------------------------
    // Surface handling
    // --------------------------------------------------------------------------

    private void setupSurface() {
        surfaceVideo.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                videoSurface = holder.getSurface();
                surfaceReady = true;
                appendLog("surface created");
                startDecoderIfNeeded();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                // The container resized (rotation, letterbox change). MediaCodec keeps
                // rendering into the same Surface object, so nothing to redo here.
                applyVideoAspect();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                // The surface is invalid the moment this returns, so the decoder has
                // to be stopped before we let the reference go.
                surfaceReady = false;
                videoSurface = null;
                stopDecoder("surface destroyed");
            }
        });
    }

    /** Starts decoding only once both prerequisites are ready. */
    private void startDecoderIfNeeded() {
        if (decoderRunning || !surfaceReady || pendingSps == null || pendingSps.length == 0) {
            return;
        }
        if (decoder == null) {
            return;
        }
        decoder.setCodecConfig(pendingSps, pendingPps, streamWidth, streamHeight);
        try {
            decoder.start(videoSurface);
            decoderRunning = true;
            renderedWindow = 0;
            renderedWindowStartMs = SystemClock.elapsedRealtime();
            appendLog("decoder started (" + pendingSps.length + "B SPS, "
                    + (pendingPps == null ? 0 : pendingPps.length) + "B PPS)");
        } catch (IOException e) {
            appendLog("decoder could not start: " + e.getMessage());
            showOverlay("No H.264 decoder on this device");
        }
    }

    private void stopDecoder(String reason) {
        if (decoder != null && decoderRunning) {
            decoder.stop();
            appendLog("decoder stopped (" + reason + ")");
        }
        decoderRunning = false;
    }

    // --------------------------------------------------------------------------
    // Controls
    // --------------------------------------------------------------------------

    /** RecyclerView setup: one adapter instance, reused for every discovery update. */
    private void setupHostList() {
        hostAdapter = new HostAdapter();
        listHosts.setLayoutManager(new LinearLayoutManager(this));
        listHosts.setAdapter(hostAdapter);
    }

    private void setupControls() {
        btnConnect.setOnClickListener(v -> connectFromInput());
        btnDisconnect.setOnClickListener(v -> disconnect("user tapped disconnect"));

        edtHost.setOnEditorActionListener((view, actionId, event) -> {
            connectFromInput();
            return true;
        });

        // Prefill from an intent extra so a shared address can deep-link here.
        String preset = getIntent().getStringExtra("host");
        if (preset != null && !preset.isEmpty()) {
            edtHost.setText(preset);
        }
    }

    private void connectFromInput() {
        String raw = edtHost.getText() == null ? "" : edtHost.getText().toString().trim();
        if (raw.isEmpty()) {
            boxHost.setError(getString(R.string.guest_bad_ip));
            appendLog("no address entered - pick a discovered host or type an IP");
            return;
        }
        String host = raw;
        int port = DEFAULT_PORT;
        int colon = raw.indexOf(':');
        if (colon > 0) {
            host = raw.substring(0, colon);
            try {
                port = Integer.parseInt(raw.substring(colon + 1));
            } catch (NumberFormatException e) {
                boxHost.setError("bad port");
                appendLog("bad port in '" + raw + "'");
                return;
            }
        }
        if (host.isEmpty() || host.indexOf('.') < 0) {
            boxHost.setError(getString(R.string.guest_bad_ip));
            appendLog(getString(R.string.guest_bad_ip) + ": " + raw);
            return;
        }
        boxHost.setError(null);
        connectTo(host, port, "manual entry");
    }

    private void connectTo(String host, int port, String how) {
        appendLog("connecting to " + host + ":" + port + " (" + how + ")");
        txtStatus.setText(R.string.guest_status_connecting);
        showOverlay(getString(R.string.guest_status_connecting));
        dotStatus.setActivated(false);
        btnConnect.setEnabled(false);
        controller.connect(host, port, Build.MODEL);
    }

    private void disconnect(String reason) {
        controller.disconnect(reason);
        stopDecoder(reason);
        connected = false;
        pendingSps = null;
        pendingPps = null;
        streamWidth = 0;
        streamHeight = 0;
        appendLog("disconnected (" + reason + ")");
        renderIdle();
    }

    private void renderIdle() {
        btnConnect.setEnabled(true);
        btnDisconnect.setEnabled(false);
        txtStatus.setText(R.string.guest_status_idle);
        showOverlay(getString(R.string.guest_status_idle));
        panelConnect.setVisibility(View.VISIBLE);
        findViewById(R.id.controlsBar).setVisibility(View.GONE);
        dotStatus.setActivated(false);
        dotStatus.setSelected(false);
        txtStatFps.setText("");
        txtStatPing.setText("");
        exitImmersive();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    private void showOverlay(String text) {
        txtOverlay.setText(text);
        txtOverlay.setVisibility(View.VISIBLE);
    }

    // --------------------------------------------------------------------------
    // Aspect ratio / immersive chrome
    // --------------------------------------------------------------------------

    /**
     * Sizes {@code videoContainer} so the stream keeps its aspect ratio inside the
     * window (letterbox / pillarbox). MediaCodec stretches to whatever the Surface is,
     * so without this a landscape stream would be squashed into a portrait view.
     */
    private void applyVideoAspect() {
        if (streamWidth <= 0 || streamHeight <= 0) {
            return;
        }
        int rw = root.getWidth();
        int rh = root.getHeight();
        if (rw <= 0 || rh <= 0) {
            return;
        }
        float viewAspect = (float) rw / (float) rh;
        float videoAspect = (float) streamWidth / (float) streamHeight;

        int targetW;
        int targetH;
        if (videoAspect > viewAspect) {
            // Video is wider than the window: fill the width, shrink the height.
            targetW = rw;
            targetH = Math.round(rw / videoAspect);
        } else {
            targetH = rh;
            targetW = Math.round(rh * videoAspect);
        }

        ViewGroup.LayoutParams lp = videoContainer.getLayoutParams();
        if (lp == null || (lp.width == targetW && lp.height == targetH)) {
            return;
        }
        lp.width = targetW;
        lp.height = targetH;
        if (lp instanceof FrameLayout.LayoutParams) {
            ((FrameLayout.LayoutParams) lp).gravity = Gravity.CENTER;
        }
        videoContainer.setLayoutParams(lp);
    }

    /**
     * Matches this device's orientation to the stream's, so mirroring a portrait phone
     * onto a phone held in portrait needs no letterboxing at all.
     */
    private void matchOrientationToStream() {
        if (streamWidth <= 0 || streamHeight <= 0) {
            return;
        }
        int requested = (streamWidth > streamHeight)
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
        if (getRequestedOrientation() != requested) {
            setRequestedOrientation(requested);
        }
    }

    /**
     * Hides the status and navigation bars with {@link WindowInsetsControllerCompat}.
     *
     * <p>On API 30+ the old {@code View.SYSTEM_UI_FLAG_*} bits are ignored by the
     * platform, so a framework-only build would silently stay windowed on a modern
     * phone. The compat controller picks the right mechanism per API level:
     * {@code WindowInsetsController} where it exists, the legacy flags below 30.
     */
    private void enterImmersive() {
        if (immersive) {
            return;
        }
        immersive = true;
        panelConnect.setVisibility(View.GONE);
        txtOverlay.setVisibility(View.GONE);
        // Two knobs have to agree here: the window stops insetting the content
        // (setDecorFitsSystemWindows) *and* the root stops consuming insets
        // (fitsSystemWindows). Leaving the XML attribute on while the window is
        // unconstrained would keep padding the video away from the edges.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        root.setFitsSystemWindows(false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), root);
        controller.hide(WindowInsetsCompat.Type.systemBars());
        // Swipe from an edge shows the bars temporarily instead of resizing the video.
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private void exitImmersive() {
        if (!immersive) {
            return;
        }
        immersive = false;
        panelConnect.setVisibility(View.VISIBLE);
        txtOverlay.setVisibility(View.VISIBLE);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), root);
        controller.show(WindowInsetsCompat.Type.systemBars());
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        root.setFitsSystemWindows(true);
    }

    // --------------------------------------------------------------------------
    // Touch forwarding (the control channel - injection is milestone 2)
    // --------------------------------------------------------------------------

    /**
     * Captures taps and drags on the mirrored picture and ships them to the host as
     * normalised coordinates (0..10000 of the view's width/height).
     *
     * <p>Normalised rather than pixels because the guest's screen and the host's
     * capture almost never share a resolution, and letterbox offsets must not leak into
     * the coordinates. The host multiplies by its own display size on injection.
     *
     * <p>Injection itself is the next milestone: an ordinary app cannot write into
     * another app's input stream, so the host will need an AccessibilityService
     * ({@code dispatchGesture}) or a shell-level helper. The transport already works end
     * to end - watch the host's log to see every touch arrive.
     */
    /**
     * The four host-navigation buttons (back / home / recents / notification shade).
     * They live in the layout, overlay the video corner, and talk to the HOST phone:
     * a letterboxed stream cannot express an edge swipe - the finger would have to
     * start on the host screen's true edge, and the video rarely reaches it - so the
     * gesture becomes a command instead.
     */
    private void setupSystemControls() {
        findViewById(R.id.btnNavBack).setOnClickListener(v ->
                controller.sendGlobalAction(GhostProtocol.GLOBAL_BACK));
        findViewById(R.id.btnNavHome).setOnClickListener(v ->
                controller.sendGlobalAction(GhostProtocol.GLOBAL_HOME));
        findViewById(R.id.btnNavRecents).setOnClickListener(v ->
                controller.sendGlobalAction(GhostProtocol.GLOBAL_RECENTS));
        findViewById(R.id.btnNavShade).setOnClickListener(v ->
                controller.sendGlobalAction(GhostProtocol.GLOBAL_NOTIFICATIONS));
    }

    private void setupTouchForwarding() {
        videoContainer.setOnTouchListener((view, event) -> {
            if (!connected || streamWidth <= 0 || streamHeight <= 0) {
                return false;
            }
            int action = event.getActionMasked();
            int protocolAction;
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    protocolAction = 0;
                    break;
                case MotionEvent.ACTION_MOVE:
                    protocolAction = 2;
                    break;
                case MotionEvent.ACTION_UP:
                    protocolAction = 1;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    protocolAction = 3;
                    break;
                default:
                    return false; // multi-touch is a later milestone
            }

            float nx = event.getX() / (float) Math.max(1, view.getWidth());
            float ny = event.getY() / (float) Math.max(1, view.getHeight());
            nx = Math.max(0f, Math.min(1f, nx));
            ny = Math.max(0f, Math.min(1f, ny));

            long now = SystemClock.uptimeMillis();
            if (protocolAction == 2) {
                // Throttle drag streams: 60 Hz of MOVE events would flood a link that
                // is already carrying video.
                boolean movedEnough = Math.abs(nx - lastTouchX) > 0.004f
                        || Math.abs(ny - lastTouchY) > 0.004f;
                if (now - lastTouchSentMs < 16 || !movedEnough) {
                    return true;
                }
            }
            lastTouchSentMs = now;
            lastTouchX = nx;
            lastTouchY = ny;
            controller.sendTouch(protocolAction, nx, ny, now);
            return true;
        });
    }

    // --------------------------------------------------------------------------
    // GuestController.Listener - all on the main thread except onVideoData
    // --------------------------------------------------------------------------

    @Override
    public void onHostsChanged(List<GuestController.DiscoveredHost> hosts) {
        appendLog("discovery: " + hosts.size() + " host(s) visible");
        hostAdapter.submit(hosts);
        txtNoHosts.setVisibility(hosts.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onDiscoveryError(String message) {
        appendLog("discovery: " + message);
    }

    @Override
    public void onConnected(String hostName, int width, int height) {
        connected = true;
        if (width > 0 && height > 0) {
            streamWidth = width;
            streamHeight = height;
        }
        appendLog("connected to '" + hostName + "' " + streamWidth + "x" + streamHeight);
        findViewById(R.id.controlsBar).setVisibility(View.VISIBLE);
        btnConnect.setEnabled(false);
        btnDisconnect.setEnabled(true);
        txtStatus.setText(getString(R.string.guest_status_connected) + " · " + hostName);
        showOverlay(getString(R.string.guest_waiting_video));
        dotStatus.setActivated(true);
        txtStatSize.setText(streamWidth + "x" + streamHeight);
        applyVideoAspect();
        matchOrientationToStream();
    }

    @Override
    public void onVideoConfig(byte[] sps, byte[] pps, int width, int height) {
        pendingSps = sps;
        pendingPps = pps;
        if (width > 0 && height > 0) {
            streamWidth = width;
            streamHeight = height;
        }
        appendLog("got decoder config for " + streamWidth + "x" + streamHeight);
        // A config change mid-session (the host rotated) invalidates the running
        // decoder, so tear it down and let startDecoderIfNeeded() build a new one.
        stopDecoder("new video config");
        applyVideoAspect();
        matchOrientationToStream();
        startDecoderIfNeeded();
    }

    /**
     * <b>Reader thread.</b> No view access here - hand the bytes straight to the
     * decoder. Hopping to the main thread 30 times a second would add latency.
     */
    @Override
    public void onVideoData(byte[] data, int length, boolean key, long ptsUs) {
        VideoDecoder d = decoder;
        if (d == null || !decoderRunning) {
            return;
        }
        d.offerVideo(data, length, key, ptsUs);
    }

    @Override
    public void onGeometry(int width, int height, int rotation) {
        streamWidth = width;
        streamHeight = height;
        appendLog("host geometry " + width + "x" + height + " rotation=" + rotation);
        applyVideoAspect();
        matchOrientationToStream();
    }

    @Override
    public void onStats(Record stats) {
        txtStatSize.setText(stats.getInt("w", 0) + "x" + stats.getInt("h", 0)
                + " · " + stats.getInt("clients", 0) + " guest(s) · host "
                + stats.getInt("kbps", 0) + " kbps");
    }

    @Override
    public void onLatency(long millis) {
        txtStatPing.setText("rtt " + millis + " ms");
    }

    @Override
    public void onDisconnected(String reason) {
        appendLog("disconnected: " + reason);
        connected = false;
        stopDecoder(reason);
        renderIdle();
    }

    // --------------------------------------------------------------------------
    // VideoDecoder.Listener - decoder thread, so hop to the UI thread for views
    // --------------------------------------------------------------------------

    private final VideoDecoder.Listener decoderListener = new VideoDecoder.Listener() {
        @Override
        public void onVideoSizeChanged(final int width, final int height) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    streamWidth = width;
                    streamHeight = height;
                    appendLog("decoder reports " + width + "x" + height);
                    applyVideoAspect();
                    matchOrientationToStream();
                    txtStatSize.setText(width + "x" + height);
                }
            });
        }

        @Override
        public void onFrameRendered(long ptsUs) {
            renderedWindow++;
            long now = SystemClock.elapsedRealtime();
            final boolean first = !immersive;
            if (now - renderedWindowStartMs >= 1000 || first) {
                final int fps =
                        (int) (renderedWindow * 1000L / Math.max(1, now - renderedWindowStartMs));
                renderedWindow = 0;
                renderedWindowStartMs = now;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (first) {
                            enterImmersive();
                            txtStatus.setText(getString(R.string.guest_status_connected) + " · live");
                        }
                        txtStatFps.setText(fps + " fps · dropped "
                                + (decoder != null ? decoder.getDroppedInputs() : 0));
                    }
                });
            }
        }

        @Override
        public void onDecoderError(final String message) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    appendLog("decoder error: " + message);
                    showOverlay("Decoder error - reconnect to recover");
                    dotStatus.setSelected(true);
                    Snackbar.make(root, "Decoder error: " + message, Snackbar.LENGTH_LONG).show();
                }
            });
        }
    };

    // --------------------------------------------------------------------------
    // RecyclerView adapter for discovered hosts
    // --------------------------------------------------------------------------

    /**
     * RecyclerView 1.1.0's adapter contract is the classic one: create a holder by
     * inflating item_host.xml, then bind a host into it. Rows are recycled as the list
     * scrolls, which is why binding must always set every field - a recycled row still
     * holds the previous host's text.
     */
    private final class HostAdapter extends RecyclerView.Adapter<HostAdapter.HostHolder> {

        private final List<GuestController.DiscoveredHost> hosts = new ArrayList<>();

        void submit(List<GuestController.DiscoveredHost> updated) {
            hosts.clear();
            hosts.addAll(updated);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public HostHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View row = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_host, parent, false);
            return new HostHolder(row);
        }

        @Override
        public void onBindViewHolder(@NonNull HostHolder holder, int position) {
            final GuestController.DiscoveredHost host = hosts.get(position);
            holder.name.setText(host.name);
            holder.addr.setText(host.host + ":" + host.port);
            holder.itemView.setOnClickListener(v -> connectTo(host.host, host.port, "mDNS"));
        }

        @Override
        public int getItemCount() {
            return hosts.size();
        }

        final class HostHolder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView addr;

            HostHolder(View row) {
                super(row);
                name = row.findViewById(R.id.txtHostName);
                addr = row.findViewById(R.id.txtHostAddr);
            }
        }
    }

    // --------------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------------

    private void appendLog(String message) {
        String line = logTime.format(new Date()) + "  " + message + "\n";
        String updated = txtLog.getText().toString() + line;
        if (updated.length() > 8000) {
            updated = updated.substring(updated.length() - 6000);
        }
        txtLog.setText(updated);
    }
}
