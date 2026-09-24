package damjay.control.ghosthand;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import damjay.control.ghosthand.host.HostController;
import damjay.control.ghosthand.host.ScreenCaptureService;
import damjay.control.ghosthand.net.GhostProtocol;

/**
 * The host's dashboard.
 *
 * <p><b>How this activity and {@link ScreenCaptureService} talk.</b> No binding, no
 * AIDL. The activity sends commands with {@code startForegroundService()}
 * (ACTION_START / ACTION_STOP / ACTION_SET_MODE) and receives state through a
 * {@link BroadcastReceiver} listening for {@code ACTION_STATE} and {@code ACTION_STATS}.
 * Both broadcasts are restricted to our own package with {@code setPackage()}, so no
 * other app can see or spoof them.
 *
 * <p>That loose coupling is deliberate: the service must outlive the activity (press
 * Home and the stream continues), and an activity that comes back later can re-read
 * the current state from the service's static {@code state}/{@code lastError} fields.
 *
 * <p><b>The permission dance</b> - screen capture needs two grants:
 * <ol>
 *   <li>POST_NOTIFICATIONS (Android 13+) so the foreground-service notification is
 *       visible. Requested with {@link #requestPermissions}; purely cosmetic - the
 *       service runs either way.</li>
 *   <li>The MediaProjection consent dialog, launched with
 *       {@link #startActivityForResult}. Its result (a resultCode plus an Intent
 *       carrying a single-use token on Android 14+) is forwarded to the service,
 *       which is why we ask again on every start.</li>
 * </ol>
 */
public class HostActivity extends Activity {

    /** requestCode for the MediaProjection consent dialog. */
    private static final int REQ_PROJECTION = 1001;
    /** requestCode for POST_NOTIFICATIONS. */
    private static final int REQ_NOTIFICATION = 1002;

    /** Longest-side presets, index-aligned with R.array.host_resolution_options. */
    private static final int[] RESOLUTION_PRESETS = { 480, 720, 1280, 1920, 4096 };
    private static final int DEFAULT_PRESET_INDEX = 2;

    // views (see res/layout/activity_host.xml)
    private View dotStatus;
    private TextView txtStatus;
    private TextView txtStatusDetail;
    private TextView txtAddress;
    private TextView txtLog;
    private TextView txtStatFps;
    private TextView txtStatBitrate;
    private TextView txtStatClients;
    private TextView txtStatDropped;
    private Button btnToggle;
    private Button btnCopy;
    private Button btnShare;
    private Button btnGuestMode;
    private Spinner spnResolution;
    private Switch swMirror;

    private HostController addressHelper;
    private String currentAddress;
    private final SimpleDateFormat logTime = new SimpleDateFormat("HH:mm:ss", Locale.US);

    // --------------------------------------------------------------------------
    // Lifecycle
    // --------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_host);

        dotStatus = findViewById(R.id.dotStatus);
        txtStatus = findViewById(R.id.txtStatus);
        txtStatusDetail = findViewById(R.id.txtStatusDetail);
        txtAddress = findViewById(R.id.txtAddress);
        txtLog = findViewById(R.id.txtLog);
        txtStatFps = findViewById(R.id.txtStatFps);
        txtStatBitrate = findViewById(R.id.txtStatBitrate);
        txtStatClients = findViewById(R.id.txtStatClients);
        txtStatDropped = findViewById(R.id.txtStatDropped);
        btnToggle = findViewById(R.id.btnToggle);
        btnCopy = findViewById(R.id.btnCopy);
        btnShare = findViewById(R.id.btnShare);
        btnGuestMode = findViewById(R.id.btnGuestMode);
        spnResolution = findViewById(R.id.spnResolution);
        swMirror = findViewById(R.id.swMirror);

        addressHelper = new HostController();

        setupControls();
        refreshAddresses();
        renderState(ScreenCaptureService.state, ScreenCaptureService.lastError);

        if (ScreenCaptureService.isStreaming()) {
            appendLog("re-attached to a running session");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ScreenCaptureService.ACTION_STATE);
        filter.addAction(ScreenCaptureService.ACTION_STATS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ makes you state explicitly that a receiver is private.
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(serviceReceiver, filter);
        }
        refreshAddresses();
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(serviceReceiver);
        } catch (IllegalArgumentException ignored) {
            // already unregistered
        }
    }

    // --------------------------------------------------------------------------
    // UI wiring
    // --------------------------------------------------------------------------

    private void setupControls() {
        spnResolution.setSelection(DEFAULT_PRESET_INDEX);

        btnToggle.setOnClickListener(v -> onToggleClicked());

        btnCopy.setOnClickListener(v -> {
            if (currentAddress == null) {
                Toast.makeText(this, R.string.host_no_address, Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("ghosthand-host", currentAddress));
                Toast.makeText(this, R.string.host_copied, Toast.LENGTH_SHORT).show();
            }
        });

        btnShare.setOnClickListener(v -> {
            if (currentAddress == null) {
                Toast.makeText(this, R.string.host_no_address, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.host_share_subject));
            share.putExtra(Intent.EXTRA_TEXT, currentAddress);
            startActivity(Intent.createChooser(share, getString(R.string.host_share_subject)));
        });

        swMirror.setOnCheckedChangeListener(this::onMirrorToggled);

        btnGuestMode.setOnClickListener(v ->
                startActivity(new Intent(this, GuestActivity.class)));
    }

    private void onToggleClicked() {
        if (ScreenCaptureService.isStreaming()) {
            Intent intent = new Intent(this, ScreenCaptureService.class);
            intent.setAction(ScreenCaptureService.ACTION_STOP);
            startServiceCompat(intent);
            appendLog("stop requested");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS },
                    REQ_NOTIFICATION);
            return;
        }
        requestProjection();
    }

    /**
     * {@code Context.startForegroundService} is the API 26+ way to say "this service
     * will promote itself to the foreground within five seconds". Below 26 that call
     * does not exist and plain startService is correct - hence the branch.
     */
    private void startServiceCompat(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    /** Shows the system "Start casting your screen?" dialog. */
    private void requestProjection() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            appendLog("this device has no MediaProjection support");
            return;
        }
        appendLog("asking the user to confirm screen capture…");
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) {
            return;
        }
        if (resultCode == RESULT_OK && data != null) {
            startCapture(resultCode, data);
        } else {
            appendLog(getString(R.string.host_permission_denied));
            Toast.makeText(this, R.string.host_permission_denied, Toast.LENGTH_SHORT).show();
            renderState(ScreenCaptureService.STATE_IDLE, null);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIFICATION) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            appendLog(getString(R.string.host_notification_denied));
        }
        // Ask for screen capture either way: the notification is not required for
        // the foreground service to run, only for it to be visible.
        requestProjection();
    }

    private void startCapture(int resultCode, Intent data) {
        Intent intent = new Intent(this, ScreenCaptureService.class);
        intent.setAction(ScreenCaptureService.ACTION_START);
        intent.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
        // The permission token is a Parcelable Intent; the service needs it verbatim.
        intent.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
        intent.putExtra(ScreenCaptureService.EXTRA_MAX_SIDE, selectedMaxSide());
        intent.putExtra(ScreenCaptureService.EXTRA_MIRROR_FLAG, swMirror.isChecked());

        startServiceCompat(intent);

        // Keep the screen awake so the capture keeps producing frames.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        appendLog("capture service starting (" + selectedMaxSide() + " px longest side)");
        renderState(ScreenCaptureService.STATE_RUNNING, null);
    }

    private int selectedMaxSide() {
        int position = spnResolution.getSelectedItemPosition();
        if (position < 0 || position >= RESOLUTION_PRESETS.length) {
            position = DEFAULT_PRESET_INDEX;
        }
        return RESOLUTION_PRESETS[position];
    }

    // --------------------------------------------------------------------------
    // Service -> activity
    // --------------------------------------------------------------------------

    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            if (ScreenCaptureService.ACTION_STATS.equals(intent.getAction())) {
                renderStats(intent);
                return;
            }

            String message = intent.getStringExtra("message");
            if (message != null && !message.isEmpty()) {
                appendLog(message);
            }
            int state = intent.getIntExtra("state", ScreenCaptureService.state);
            String error = intent.getStringExtra("error");
            renderState(state, (error == null || error.isEmpty()) ? null : error);

            int clients = intent.getIntExtra("clients", -1);
            int width = intent.getIntExtra("width", 0);
            int height = intent.getIntExtra("height", 0);
            if (clients >= 0 && width > 0) {
                txtStatClients.setText(String.valueOf(clients));
                txtStatusDetail.setText(width + "x" + height + " · " + clients + " guest(s)");
            }

            boolean mirror = intent.getBooleanExtra("mirror", true);
            if (swMirror.isChecked() != mirror) {
                // Detach the listener first: setChecked() would otherwise fire it and
                // send a redundant ACTION_SET_MODE back to the service.
                swMirror.setOnCheckedChangeListener(null);
                swMirror.setChecked(mirror);
                swMirror.setOnCheckedChangeListener(HostActivity.this::onMirrorToggled);
                onMirrorToggled(swMirror, mirror);
            }
        }
    };

    private void renderState(int state, String error) {
        boolean running = (state == ScreenCaptureService.STATE_RUNNING);
        switch (state) {
            case ScreenCaptureService.STATE_RUNNING:
                txtStatus.setText(R.string.host_status_running);
                // dot_status.xml is a StateListDrawable: activated -> green,
                // selected -> red, neither -> grey.
                dotStatus.setActivated(true);
                dotStatus.setSelected(false);
                btnToggle.setText(R.string.host_stop);
                break;
            case ScreenCaptureService.STATE_ERROR:
                txtStatus.setText(R.string.host_status_error);
                dotStatus.setActivated(false);
                dotStatus.setSelected(true);
                btnToggle.setText(R.string.host_start);
                break;
            default:
                txtStatus.setText(R.string.host_status_idle);
                dotStatus.setActivated(false);
                dotStatus.setSelected(false);
                btnToggle.setText(R.string.host_start);
                break;
        }
        // The spinner only matters before a session starts; changing capture size
        // mid-stream would need a brand new encoder.
        spnResolution.setEnabled(!running);
        if (!running) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            txtStatFps.setText("0");
            txtStatBitrate.setText("0");
        }
        if (error != null) {
            txtStatusDetail.setText(error);
            appendLog("error: " + error);
        } else if (!running) {
            txtStatusDetail.setText("");
        }
    }

    private void renderStats(Intent intent) {
        int fps = intent.getIntExtra("fps", 0);
        int kbps = intent.getIntExtra("kbps", 0);
        int clients = intent.getIntExtra("clients", 0);
        int dropped = intent.getIntExtra("dropped", 0);
        long uptime = intent.getLongExtra("uptimeMs", 0);

        txtStatFps.setText(String.valueOf(fps));
        txtStatBitrate.setText(String.valueOf(kbps));
        txtStatClients.setText(String.valueOf(clients));
        txtStatDropped.setText(String.valueOf(dropped));
        txtStatusDetail.setText(getString(R.string.host_status_running) + " · "
                + clients + " guest(s) · up " + formatUptime(uptime));
    }

    /**
     * AUTO_MIRROR vs PUBLIC. The label doubles as the explanation, and the change is
     * pushed to the running service so it can rebuild its VirtualDisplay without
     * asking the user for capture permission a second time.
     */
    private void onMirrorToggled(CompoundButton button, boolean checked) {
        button.setText(checked ? R.string.host_mode_mirror : R.string.host_mode_public);
        if (ScreenCaptureService.isStreaming()) {
            Intent intent = new Intent(this, ScreenCaptureService.class);
            intent.setAction(ScreenCaptureService.ACTION_SET_MODE);
            intent.putExtra(ScreenCaptureService.EXTRA_MIRROR_FLAG, checked);
            startServiceCompat(intent);
        }
    }

    // --------------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------------

    /**
     * Asks {@link HostController} for this device's IPv4 addresses and prints them.
     * This is the manual-connection path for when mDNS is blocked by the router.
     */
    private void refreshAddresses() {
        if (addressHelper == null) {
            return;
        }
        List<String> addresses = addressHelper.listAddresses(GhostProtocol.DEFAULT_PORT);
        if (addresses.isEmpty()) {
            currentAddress = null;
            txtAddress.setText(R.string.host_address_unknown);
            return;
        }
        currentAddress = addresses.get(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < addresses.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(addresses.get(i));
        }
        txtAddress.setText(sb.toString());
    }

    private void appendLog(String message) {
        String line = logTime.format(new Date()) + "  " + message + "\n";
        String updated = txtLog.getText().toString() + line;
        // Keep the log bounded: a long session would otherwise grow this TextView
        // until the UI thread chokes on layout.
        if (updated.length() > 8000) {
            updated = updated.substring(updated.length() - 6000);
        }
        txtLog.setText(updated);
    }

    private static String formatUptime(long millis) {
        long seconds = millis / 1000L;
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
}
