package damjay.control.ghosthand;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import damjay.control.ghosthand.guest.ControlsConfig;
import damjay.control.ghosthand.guest.GuestController;
import damjay.control.ghosthand.util.DraggableLayout;
import damjay.control.ghosthand.util.TextComposer;
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
    private LinearLayout statusPill;
    private DraggableLayout controlsBar;
    private View btnHandle;
    private TextView txtHud;
    private View panelSettings;
    private LinearLayout settingsContent;

    // Three-state control bar - the pure rules live in ControlsConfig, the
    // views live below. One SharedPreferences file holds everything the
    // settings page edits: state, per-state dock mode, per-state button sets.
    private static final String CONTROL_PREFS = "guest_controls";
    private ControlsConfig.State ctrlState = ControlsConfig.State.ONE_LINE;
    private ControlsConfig.Dock dockOneLine = ControlsConfig.Dock.FLOAT;
    private ControlsConfig.Dock dockExpanded = ControlsConfig.Dock.FLOAT;
    private final Set<String> oneLineIds = new LinkedHashSet<>();
    private final Set<String> expandedIds = new LinkedHashSet<>();
    private final LinkedHashMap<String, Integer> buttonLabels = new LinkedHashMap<>();
    private SharedPreferences controlPrefs;
    /** True while the bar is docked (PUSH): pinned, undraggable, no saved offset. */
    private boolean dockedNow;
    /** The one-tap clean view: bars away, pill away, controls folded. */
    private boolean fullscreenClean;

    // Status pill auto-hide (the pill used to sit on the video permanently).
    private final Handler pillHandler = new Handler(Looper.getMainLooper());
    private final Runnable pillHideTick = () -> hideStatusPill(false);
    private final Handler hudHandler = new Handler(Looper.getMainLooper());
    private final Runnable hudHideTick = () -> txtHud.setVisibility(View.GONE);

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
    private final TextComposer composer = new TextComposer();

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
        statusPill = findViewById(R.id.statusPill);
        controlsBar = findViewById(R.id.controlsBar);
        btnHandle = findViewById(R.id.btnHandle);
        txtHud = findViewById(R.id.txtHud);
        panelSettings = findViewById(R.id.panelSettings);
        settingsContent = findViewById(R.id.settingsContent);
        // The HUD only flashes for a moment; never let a stray tap on it
        // travel through to the host's screen underneath.
        txtHud.setClickable(true);

        setupHostList();
        controller = new GuestController(this);
        decoder = new VideoDecoder(decoderListener);

        setupSurface();
        setupControls();
        setupTouchForwarding();
        setupControlRegistry();
        loadControlPrefs();
        buildSettingsPanel();
        setupStatusPill();
        setupControlsDrag();

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
        pillHandler.removeCallbacks(pillHideTick);
        hudHandler.removeCallbacks(hudHideTick);
        controller.disconnect("activity destroyed");
        if (decoder != null) {
            decoder.stop();
            decoder = null;
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Portrait/landscape changes how many buttons fit per row; re-wrap.
        if (connected) {
            renderControls();
        }
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
        panelSettings.setVisibility(View.GONE);
        fullscreenClean = false;
        pillHandler.removeCallbacks(pillHideTick);
        statusPill.animate().cancel();
        statusPill.setAlpha(1f);
        hudHandler.removeCallbacks(hudHideTick);
        txtHud.setVisibility(View.GONE);
        dotStatus.setActivated(false);
        dotStatus.setSelected(false);
        txtStatFps.setText("");
        txtStatPing.setText("");
        renderControls(); // connected is false: folds the bar and hides the handle
        root.setPadding(0, 0, 0, 0); // no dock while idle
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
        // A docked (PUSH) control bar claims this much of the bottom; the
        // video letterboxes into what is left, which is what "push the
        // screen up" means in practice.
        int rh = root.getHeight() - root.getPaddingBottom();
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
        hideSystemBars();
    }

    /**
     * Hides the bars (and, when needed, flips the window into the unconstrained
     * mode they agree on). Split out because the Full screen button calls it
     * to undo a transient edge-swipe without re-running everything else.
     *
     * <p>Two knobs have to agree here: the window stops insetting the content
     * (setDecorFitsSystemWindows) *and* the root stops consuming insets
     * (fitsSystemWindows). Leaving the XML attribute on while the window is
     * unconstrained would keep padding the video away from the edges.
     */
    private void hideSystemBars() {
        if (!immersive) {
            immersive = true;
            panelConnect.setVisibility(View.GONE);
            txtOverlay.setVisibility(View.GONE);
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            root.setFitsSystemWindows(false);
        }
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

    // ----------------------------------------------------------controls--
    // The three-state control bar: UNEXPANDED (one handle), ONE_LINE (the
    // chosen row between x and a) and FULL (the chosen buttons wrapping,
    // plus Settings / Full screen). Pure rules in ControlsConfig, storage in
    // the guest_controls prefs, pixels here.
    // --------------------------------------------------------------------------

    /** id -> label, in canonical display order (shared with the settings page). */
    private void setupControlRegistry() {
        buttonLabels.put("back", R.string.guest_nav_back);
        buttonLabels.put("home", R.string.guest_nav_home);
        buttonLabels.put("recents", R.string.guest_nav_recents);
        buttonLabels.put("shade", R.string.guest_nav_shade);
        buttonLabels.put("rotate", R.string.guest_nav_rotate);
        buttonLabels.put("clip_to", R.string.guest_clip_to_host);
        buttonLabels.put("clip_from", R.string.guest_clip_from_host);
        buttonLabels.put("compose", R.string.guest_clip_compose);
        buttonLabels.put("vol_up", R.string.guest_nav_vol_up);
        buttonLabels.put("vol_down", R.string.guest_nav_vol_down);
        buttonLabels.put("media", R.string.guest_nav_media);
    }

    private void loadControlPrefs() {
        controlPrefs = getSharedPreferences(CONTROL_PREFS, MODE_PRIVATE);
        oneLineIds.clear();
        oneLineIds.addAll(ControlsConfig.parseIds(controlPrefs.getString(
                "one_line", joinCsv(ControlsConfig.DEFAULT_ONE_LINE))));
        expandedIds.clear();
        expandedIds.addAll(ControlsConfig.parseIds(controlPrefs.getString(
                "expanded", joinCsv(ControlsConfig.DEFAULT_EXPANDED))));
        dockOneLine = dockOf(controlPrefs.getInt("mode_one_line",
                ControlsConfig.Dock.FLOAT.ordinal()));
        dockExpanded = dockOf(controlPrefs.getInt("mode_expanded",
                ControlsConfig.Dock.FLOAT.ordinal()));
        try {
            ctrlState = ControlsConfig.State.valueOf(controlPrefs.getString(
                    "state", ControlsConfig.State.ONE_LINE.name()));
        } catch (IllegalArgumentException e) {
            ctrlState = ControlsConfig.State.ONE_LINE;
        }
    }

    private static ControlsConfig.Dock dockOf(int ordinal) {
        return ordinal == ControlsConfig.Dock.PUSH.ordinal()
                ? ControlsConfig.Dock.PUSH : ControlsConfig.Dock.FLOAT;
    }

    private static String joinCsv(String[] ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    /**
     * Every button's action, one map. Navigation goes out as a GhostHand
     * command (never a synthetic swipe); volume and play/pause are commands
     * the host answers with TYPE_HOST_TEXT, which lands in the HUD.
     */
    private void onControlButton(String id) {
        switch (id) {
            case "back":
                controller.sendGlobalAction(GhostProtocol.GLOBAL_BACK);
                break;
            case "home":
                controller.sendGlobalAction(GhostProtocol.GLOBAL_HOME);
                break;
            case "recents":
                controller.sendGlobalAction(GhostProtocol.GLOBAL_RECENTS);
                break;
            case "shade":
                controller.sendGlobalAction(GhostProtocol.GLOBAL_NOTIFICATIONS);
                break;
            case "rotate":
                // Not an AccessibilityService action - a GhostHand command (100+)
                // that makes the host flip its own screen.
                controller.sendGlobalAction(GhostProtocol.GLOBAL_ROTATE);
                appendLog("asked the host to rotate");
                break;
            case "vol_up":
                controller.sendGlobalAction(GhostProtocol.GLOBAL_VOLUME_UP);
                break;
            case "vol_down":
                controller.sendGlobalAction(GhostProtocol.GLOBAL_VOLUME_DOWN);
                break;
            case "media":
                controller.sendGlobalAction(GhostProtocol.GLOBAL_MEDIA_TOGGLE);
                appendLog("asked the host to play/pause");
                break;
            case "clip_to": {
                String mine = readClipboard();
                if (mine.isEmpty()) {
                    appendLog("clipboard is empty - nothing to send");
                    return;
                }
                controller.sendClipboard(mine, true);
                appendLog("clipboard sent to host (" + mine.length() + " chars)");
                break;
            }
            case "clip_from":
                controller.requestClipboard();
                appendLog("asked the host for its clipboard");
                break;
            case "compose":
                if (!connected) {
                    appendLog("connect to a host first - text is sent over the session");
                    return;
                }
                composer.open(this, text -> {
                    controller.sendClipboard(text, true);
                    appendLog("clipboard sent to host (" + text.length() + " chars)");
                });
                break;
            default:
                break;
        }
    }

    private View makeButton(String id) {
        TextView b = (TextView) LayoutInflater.from(this)
                .inflate(R.layout.item_control_button, controlsBar, false);
        Integer label = buttonLabels.get(id);
        b.setText(label == null ? id : getString(label));
        b.setOnClickListener(v -> onControlButton(id));
        return b;
    }

    private View makeStructural(String label, View.OnClickListener onClick) {
        TextView b = (TextView) LayoutInflater.from(this)
                .inflate(R.layout.item_control_button, controlsBar, false);
        b.setText(label);
        b.setOnClickListener(onClick);
        return b;
    }

    /** Rebuilds the bar for the current state, selections and dock modes. */
    private void renderControls() {
        controlsBar.removeAllViews();
        if (!connected) {
            controlsBar.setVisibility(View.GONE);
            btnHandle.setVisibility(View.GONE);
            updateDocking();
            return;
        }
        btnHandle.setVisibility(ctrlState == ControlsConfig.State.UNEXPANDED
                ? View.VISIBLE : View.GONE);
        controlsBar.setVisibility(ctrlState == ControlsConfig.State.UNEXPANDED
                ? View.GONE : View.VISIBLE);
        if (ctrlState == ControlsConfig.State.ONE_LINE) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(makeStructural(getString(R.string.guest_controls_close),
                    v -> setState(ControlsConfig.State.UNEXPANDED)));
            for (String id : ControlsConfig.buttonsFor(ctrlState, oneLineIds, expandedIds)) {
                row.addView(makeButton(id));
            }
            row.addView(makeStructural(getString(R.string.guest_controls_expand),
                    v -> setState(ControlsConfig.State.FULL)));
            controlsBar.addView(row);
        } else if (ctrlState == ControlsConfig.State.FULL) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            List<String> ids = ControlsConfig.buttonsFor(ctrlState, oneLineIds, expandedIds);
            boolean landscape = getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
            int perRow = landscape ? 6 : 4;
            for (int i = 0; i < ids.size(); i += perRow) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                for (int j = i; j < Math.min(i + perRow, ids.size()); j++) {
                    row.addView(makeButton(ids.get(j)));
                }
                col.addView(row);
            }
            LinearLayout struct = new LinearLayout(this);
            struct.setOrientation(LinearLayout.HORIZONTAL);
            struct.setGravity(Gravity.CENTER);
            struct.addView(makeStructural(getString(R.string.guest_controls_collapse),
                    v -> setState(ControlsConfig.State.ONE_LINE)));
            struct.addView(makeStructural(getString(R.string.guest_settings),
                    v -> panelSettings.setVisibility(View.VISIBLE)));
            struct.addView(makeStructural(
                    getString(fullscreenClean ? R.string.guest_windowed
                            : R.string.guest_fullscreen),
                    this::onFullscreenClick));
            col.addView(struct);
            controlsBar.addView(col);
        }
        updateDocking();
    }

    private void setState(ControlsConfig.State state) {
        ctrlState = state;
        if (controlPrefs != null) {
            controlPrefs.edit().putString("state", state.name()).apply();
        }
        renderControls();
        if (state == ControlsConfig.State.UNEXPANDED) {
            if (fullscreenClean) {
                hideStatusPill(true);
            }
        } else {
            // Expanding is the user asking for the controls - show the status
            // again while they decide, then let the auto-hide take over.
            showStatusPill();
        }
    }

    /**
     * The one-tap clean view the "Full screen" button gives: hide any bars an
     * edge swipe brought back, fold the controls, fade the pill. The corner
     * handle stays (a few dp) so there is always a way back.
     */
    private void onFullscreenClick(View ignored) {
        toggleFullscreen();
    }

    private void toggleFullscreen() {
        fullscreenClean = !fullscreenClean;
        if (fullscreenClean) {
            hideSystemBars();
            setState(ControlsConfig.State.UNEXPANDED);
        } else {
            showStatusPill();
            renderControls(); // back in FULL: refresh the button label
        }
    }

    /**
     * Docks or floats the bar. PUSH gives it the full width at the bottom and
     * reserves its height out of the root, so the letterbox math in
     * {@link #applyVideoAspect()} genuinely pushes the picture up; FLOAT is the
     * old draggable pill, untouched.
     */
    private void updateDocking() {
        boolean docked = ctrlState != ControlsConfig.State.UNEXPANDED
                && dockFor(ctrlState) == ControlsConfig.Dock.PUSH;
        dockedNow = docked;
        controlsBar.setDragEnabled(!docked);
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) controlsBar.getLayoutParams();
        if (lp == null) {
            return;
        }
        lp.width = docked ? ViewGroup.LayoutParams.MATCH_PARENT
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.gravity = docked ? Gravity.BOTTOM : (Gravity.BOTTOM | Gravity.END);
        controlsBar.setBackgroundResource(docked ? R.drawable.bg_dock : R.drawable.bg_pill);
        // The floating bar carries a 10dp margin from the XML; a DOCKED bar
        // must not - that margin was the visible gap between the bar and the
        // true bottom of the screen, sitting inside the reserved strip. Float
        // mode puts the 10dp back (and keeps it out of the dock's height math,
        // which uses lp.bottomMargin below).
        int edge = docked ? 0 : dp(10);
        lp.setMargins(edge, edge, edge, edge);
        if (docked) {
            controlsBar.setTranslationX(0f);
            controlsBar.setTranslationY(0f);
        }
        controlsBar.setLayoutParams(lp);
        controlsBar.post(() -> {
            int pad = docked ? controlsBar.getHeight() + lp.bottomMargin : 0;
            if (root.getPaddingBottom() != pad) {
                root.setPadding(0, 0, 0, pad);
            }
            applyVideoAspect();
        });
    }

    private ControlsConfig.Dock dockFor(ControlsConfig.State state) {
        return state == ControlsConfig.State.ONE_LINE ? dockOneLine : dockExpanded;
    }

    // ------------------------- status pill / HUD ---------------------------

    /**
     * The connected pill used to sit on the video for the whole session.
     * Now: fully visible for four seconds (and whenever the user touches it),
     * then faded out; a tap anywhere on it brings it back or sends it away.
     * alpha 0 still receives taps - which is exactly what makes the faded
     * state a reveal gesture instead of a dead zone.
     */
    private void setupStatusPill() {
        statusPill.setOnClickListener(v -> {
            if (statusPill.getAlpha() < 0.5f) {
                showStatusPill();
            } else {
                hideStatusPill(true);
            }
        });
        btnHandle.setOnClickListener(v -> setState(ControlsConfig.State.ONE_LINE));
    }

    private void showStatusPill() {
        pillHandler.removeCallbacks(pillHideTick);
        statusPill.animate().cancel();
        statusPill.setAlpha(1f);
        if (connected) {
            pillHandler.postDelayed(pillHideTick, 4_000L);
        }
    }

    private void hideStatusPill(boolean now) {
        pillHandler.removeCallbacks(pillHideTick);
        if (!connected) {
            return;
        }
        statusPill.animate().cancel();
        statusPill.animate().alpha(0f).setDuration(now ? 120L : 300L).start();
    }

    /** Transient overlay for host answers (volume %, play/pause ack). */
    private void showHud(String text) {
        txtHud.setText(text);
        txtHud.setVisibility(View.VISIBLE);
        hudHandler.removeCallbacks(hudHideTick);
        hudHandler.postDelayed(hudHideTick, 1_500L);
    }

    // --------------------------- settings page -----------------------------

    /**
     * Built at runtime from ControlsConfig.BUTTON_IDS so the checkbox lists
     * can never drift from the buttons that actually exist. Two independent
     * radio groups (one-line / expanded, each float-or-dock) plus a checkbox
     * per button per state; every change saves and re-renders immediately.
     */
    private void buildSettingsPanel() {
        settingsContent.removeAllViews();
        settingsContent.addView(header(getString(R.string.guest_settings_title), 0));

        settingsContent.addView(header(getString(R.string.guest_settings_one_line), 1));
        settingsContent.addView(dockGroup(true));

        settingsContent.addView(header(getString(R.string.guest_settings_expanded), 1));
        settingsContent.addView(dockGroup(false));

        settingsContent.addView(
                header(getString(R.string.guest_settings_buttons_one_line), 1));
        addCheckboxes(settingsContent, oneLineIds);

        settingsContent.addView(
                header(getString(R.string.guest_settings_buttons_expanded), 1));
        addCheckboxes(settingsContent, expandedIds);

        TextView done = (TextView) LayoutInflater.from(this)
                .inflate(R.layout.item_control_button, settingsContent, false);
        done.setText(R.string.guest_settings_done);
        done.setTextSize(14f);
        done.setPadding(0, dp(14), 0, dp(6));
        // item_control_button is pill-coloured for the control bar; inside the
        // light settings panel it needs the theme ink instead.
        done.setTextColor(getResources().getColor(R.color.text_primary));
        done.setOnClickListener(v -> panelSettings.setVisibility(View.GONE));
        settingsContent.addView(done);
    }

    private TextView header(String text, int first) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextSize(14f);
        h.setTypeface(null, android.graphics.Typeface.BOLD);
        // The panel sits on @color/surface (white in the light scheme), so the
        // theme's own text colour is correct in BOTH modes - forced white was
        // what made this unreadable on KitKat, which is always light.
        h.setTextColor(getResources().getColor(R.color.text_primary));
        h.setPadding(0, dp(first == 0 ? 0 : 18), 0, dp(6));
        return h;
    }

    /**
     * A checkbox/radio that reads on the panel's themed surface: AppCompat
     * widgets because they can tint the little box/circle on every API level
     * (the framework gained setButtonTintList only in 21, this app floors at 19).
     */
    private static void tintChoice(android.widget.TextView choice, int color) {
        choice.setTextColor(color);
        if (choice instanceof androidx.appcompat.widget.AppCompatCheckBox) {
            ((androidx.appcompat.widget.AppCompatCheckBox) choice)
                    .setButtonTintList(android.content.res.ColorStateList.valueOf(color));
        }
        if (choice instanceof androidx.appcompat.widget.AppCompatRadioButton) {
            ((androidx.appcompat.widget.AppCompatRadioButton) choice)
                    .setButtonTintList(android.content.res.ColorStateList.valueOf(color));
        }
    }

    private RadioGroup dockGroup(final boolean forOneLine) {
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.HORIZONTAL);
        ControlsConfig.Dock current = forOneLine ? dockOneLine : dockExpanded;
        final int ink = getResources().getColor(R.color.text_primary);
        androidx.appcompat.widget.AppCompatRadioButton floatBtn =
                new androidx.appcompat.widget.AppCompatRadioButton(this);
        floatBtn.setId(View.generateViewId());
        floatBtn.setText(R.string.guest_settings_float);
        floatBtn.setChecked(current == ControlsConfig.Dock.FLOAT);
        androidx.appcompat.widget.AppCompatRadioButton pushBtn =
                new androidx.appcompat.widget.AppCompatRadioButton(this);
        pushBtn.setId(View.generateViewId());
        pushBtn.setText(R.string.guest_settings_push);
        pushBtn.setChecked(current == ControlsConfig.Dock.PUSH);
        tintChoice(floatBtn, ink);
        tintChoice(pushBtn, ink);
        g.addView(floatBtn);
        g.addView(pushBtn);
        g.setOnCheckedChangeListener((group, checkedId) -> {
            boolean push = checkedId == pushBtn.getId();
            ControlsConfig.Dock dock = push
                    ? ControlsConfig.Dock.PUSH : ControlsConfig.Dock.FLOAT;
            if (forOneLine) {
                dockOneLine = dock;
            } else {
                dockExpanded = dock;
            }
            controlPrefs.edit()
                    .putInt(forOneLine ? "mode_one_line" : "mode_expanded", dock.ordinal())
                    .apply();
            renderControls();
        });
        return g;
    }

    private void addCheckboxes(LinearLayout c, final Set<String> selection) {
        final int ink = getResources().getColor(R.color.text_primary);
        for (final String id : ControlsConfig.BUTTON_IDS) {
            androidx.appcompat.widget.AppCompatCheckBox cb =
                    new androidx.appcompat.widget.AppCompatCheckBox(this);
            Integer label = buttonLabels.get(id);
            cb.setText(label == null ? id : getString(label));
            cb.setChecked(selection.contains(id));
            tintChoice(cb, ink);
            cb.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    selection.add(id);
                } else {
                    selection.remove(id);
                }
                controlPrefs.edit()
                        .putString(selection == oneLineIds ? "one_line" : "expanded",
                                ControlsConfig.toCsv(selection))
                        .apply();
                renderControls();
            });
            c.addView(cb);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------------

    /** Current clipboard text, or "" when empty (KitKat-safe, never throws). */
    private String readClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData data = cm == null ? null : cm.getPrimaryClip();
            if (data != null && data.getItemCount() > 0) {
                CharSequence text = data.getItemAt(0).coerceToText(this);
                if (text != null) {
                    return text.toString();
                }
            }
        } catch (RuntimeException e) {
            appendLog("clipboard read failed: " + e.getMessage());
        }
        return "";
    }

    /** Replaces this device's clipboard - the auto-copy on arrival. */
    private void writeClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("ghosthand", text));
                Toast.makeText(this,
                        getString(R.string.clipboard_updated, text.length()),
                        Toast.LENGTH_SHORT).show();
            }
        } catch (RuntimeException e) {
            appendLog("clipboard write failed: " + e.getMessage());
        }
    }

    /**
     * The controls pill can be dragged anywhere over the video (it otherwise
     * sits in the bottom-right and blocks part of the picture). The chosen spot
     * is stored as screen fractions, so it survives restarts AND rotation -
     * re-applied on every layout pass, which is also what re-clamps the bar
     * into view after the guest's own orientation changes.
     */
    private void setupControlsDrag() {
        final DraggableLayout controlsBar = findViewById(R.id.controlsBar);
        final android.content.SharedPreferences prefs =
                getSharedPreferences("guest_ui", MODE_PRIVATE);
        controlsBar.setListener(() -> {
            if (dockedNow) {
                return; // a docked bar has no free position to remember
            }
            float[] f = controlsBar.getPositionFractions();
            prefs.edit()
                    .putFloat("controls_fx", f[0])
                    .putFloat("controls_fy", f[1])
                    .apply();
            appendLog("controls moved - drag the bar again to reposition");
        });
        controlsBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            if (v.getVisibility() != View.VISIBLE || dockedNow) {
                return;
            }
            controlsBar.applyPosition(
                    prefs.getFloat("controls_fx", Float.NaN),
                    prefs.getFloat("controls_fy", Float.NaN));
        });
    }

    /**
     * Captures taps and drags on the mirrored picture and ships them to the
     * host as normalised coordinates (0..10000 of the view's width/height) -
     * normalised because the two screens almost never share a resolution and
     * letterbox offsets must not leak in. The host injects them (live via
     * Shizuku, or replays the drag with dispatchGesture at lift-off).
     *
     * <p>The control bar, handle, pill, HUD and settings panel all live OUTSIDE
     * videoContainer, so anything they cover is consumed here and never
     * reaches the host.
     */
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
        renderControls();
        showStatusPill();
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

    @Override
    public void onHostText(String text) {
        // The host answering a volume or media command: show it as a HUD for
        // a beat and keep it in the session log for the record.
        appendLog("host: " + text);
        showHud(text);
    }

    @Override
    public void onClipboardRequest() {
        // The host pressed "From guest": answer from the UI thread, where reading
        // the clipboard is allowed (including on KitKat).
        String mine = readClipboard();
        boolean ok = !mine.isEmpty();
        controller.sendClipboard(mine, ok);
        if (ok) {
            appendLog("sent clipboard to host (" + mine.length() + " chars)");
        } else {
            appendLog("clipboard is empty - told the host");
        }
    }

    @Override
    public void onPeerClipboard(String text, boolean ok) {
        // Either the answer to a pull or a push from the host. ok=false means the
        // host could not read ITS clipboard (Android 10+ blocks background reads) -
        // never overwrite ours with that.
        if (!ok || text.isEmpty()) {
            appendLog("host clipboard unavailable (Android 10+ blocks background reads)");
            return;
        }
        composer.setIncoming(text); // if the compose box is open, show what arrived
        writeClipboard(text);
        appendLog("clipboard updated from host (" + text.length() + " chars)");
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
