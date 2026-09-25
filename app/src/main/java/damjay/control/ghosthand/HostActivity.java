package damjay.control.ghosthand;

import android.Manifest;
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
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import android.widget.TextView;

import damjay.control.ghosthand.util.ApiLevels;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import damjay.control.ghosthand.host.HostController;
import damjay.control.ghosthand.host.InjectionAccessibilityService;
import damjay.control.ghosthand.host.ScreenCaptureService;
import damjay.control.ghosthand.net.GhostProtocol;

/**
 * The host's dashboard.
 *
 * <p><b>How this activity and {@link ScreenCaptureService} talk.</b> No binding, no
 * AIDL. The activity sends commands with {@code ContextCompat.startForegroundService()}
 * (ACTION_START / ACTION_STOP / ACTION_SET_MODE) and receives state through a
 * {@link BroadcastReceiver} listening for {@code ACTION_STATE} and {@code ACTION_STATS}.
 * Both broadcasts are restricted to our own package with {@code setPackage()}, so no
 * other app can see or spoof them.
 *
 * <p>That loose coupling is deliberate: the service must outlive the activity (press
 * Home and the stream continues), and an activity that comes back later re-reads the
 * current state from the service's static {@code state} field.
 *
 * <p><b>Two grants are needed before capture can start</b>, and AndroidX is what makes
 * the dance readable:
 * <ul>
 *   <li>{@link ActivityResultContracts.RequestPermission} for POST_NOTIFICATIONS on
 *       Android 13+ (the foreground-service notification).</li>
 *   <li>{@link ActivityResultContracts.StartActivityForResult} for the MediaProjection
 *       consent dialog. Its result - a resultCode plus a single-use Intent token - is
 *       forwarded to the service, which is why we ask again on every start.</li>
 * </ul>
 * {@code registerForActivityResult} replaces {@code startActivityForResult} +
 * {@code onActivityResult}: the callback is bound to a launcher created before the
 * activity starts, so it survives the process-recreation races that used to make
 * request codes fragile.
 */
public class HostActivity extends AppCompatActivity {

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
    private MaterialButton btnToggle;
    private MaterialAutoCompleteTextView spnResolution;
    private TextInputLayout boxResolution;
    private MaterialSwitch swMirror;

    /**
     * Android 14 capture-mode change: the service answered with EXTRA_RECONSENT and
     * the system dialog is (or was) up again. desiredMirror is the mode the user
     * asked for - the switch shows the LIVE mode while we wait, because that is what
     * is still capturing, so the answer must not be read back from it.
     */
    private boolean awaitingReconsent;
    private boolean desiredMirror;
    private View dotTouch;
    private TextView txtTouchStatus;
    private MaterialButton btnTouchSettings;

    private HostController addressHelper;
    private String currentAddress;
    private final SimpleDateFormat logTime = new SimpleDateFormat("HH:mm:ss", Locale.US);

    /**
     * The MediaProjection consent dialog. Created as a field, before {@code onStart},
     * which is the contract {@code registerForActivityResult} expects.
     */
    private final ActivityResultLauncher<Intent> projectionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        Intent data = result.getData();
                        boolean reconsent = awaitingReconsent;
                        awaitingReconsent = false;
                        if (result.getResultCode() == RESULT_OK && data != null) {
                            if (reconsent) {
                                // Mid-session mode change on Android 14: the service is
                                // still running, so skip the "starting fresh" UI updates
                                // and hand it the mode the user asked for, not the switch
                                // (the switch shows the still-live old mode).
                                startCapture(result.getResultCode(), data, desiredMirror);
                            } else {
                                startCapture(result.getResultCode(), data);
                            }
                        } else if (reconsent) {
                            // Nothing was pending on the service side - it never left the
                            // live mode - so a "no" just leaves everything as it was.
                            appendLog(getString(R.string.host_mode_unchanged));
                        } else {
                            showMessage(getString(R.string.host_permission_denied));
                            appendLog(getString(R.string.host_permission_denied));
                            renderState(ScreenCaptureService.STATE_IDLE, null);
                        }
                    });

    /** POST_NOTIFICATIONS (Android 13+). Purely cosmetic; capture runs either way. */
    private final ActivityResultLauncher<String> notificationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (!granted) {
                            appendLog(getString(R.string.host_notification_denied));
                        }
                        requestProjection();
                    });

    // --------------------------------------------------------------------------
    // Lifecycle
    // --------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Defensive: MainActivity already refuses to start this screen below API 21,
        // but the activity is exported-ish (it can be launched from a launcher shortcut
        // or an old task record), and everything below this line assumes MediaProjection
        // exists. Bail out before inflating anything, which also means no API-21 class
        // is ever resolved on an older device.
        if (!ApiLevels.canHost(Build.VERSION.SDK_INT)) {
            Toast.makeText(this,
                    getString(R.string.main_host_unavailable, Build.VERSION.RELEASE),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        setContentView(R.layout.activity_host);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

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
        spnResolution = findViewById(R.id.spnResolution);
        boxResolution = findViewById(R.id.boxResolution);
        swMirror = findViewById(R.id.swMirror);
        dotTouch = findViewById(R.id.dotTouch);
        txtTouchStatus = findViewById(R.id.txtTouchStatus);
        btnTouchSettings = findViewById(R.id.btnTouchSettings);

        addressHelper = new HostController();

        setupControls();
        refreshAddresses();
        renderState(ScreenCaptureService.state, ScreenCaptureService.lastError);

        if (ScreenCaptureService.isStreaming()) {
            appendLog("re-attached to a running session");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The user may be returning from Accessibility settings, where the grant is
        // made. There is no callback for that, so re-read it whenever we come back.
        refreshTouchStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ScreenCaptureService.ACTION_STATE);
        filter.addAction(ScreenCaptureService.ACTION_STATS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requires stating explicitly that the receiver is private.
            ContextCompat.registerReceiver(this, serviceReceiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
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
        // Material 3's exposed dropdown is a MaterialAutoCompleteTextView: an
        // editable text field with a list, not a Spinner. setSimpleItems() wires the
        // string-array straight in, and the selection *index* is recovered by
        // matching the label back to the array - which keeps RESOLUTION_PRESETS
        // aligned with R.array.host_resolution_options without a parallel list of
        // (label, value) pairs to keep in sync.
        spnResolution.setSimpleItems(R.array.host_resolution_options);
        spnResolution.setText(resolutionLabels()[DEFAULT_PRESET_INDEX], false);

        btnToggle.setOnClickListener(v -> onToggleClicked());

        findViewById(R.id.btnCopy).setOnClickListener(v -> {
            if (currentAddress == null) {
                showMessage(getString(R.string.host_no_address));
                return;
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("ghosthand-host", currentAddress));
                showMessage(getString(R.string.host_copied));
            }
        });

        findViewById(R.id.btnShare).setOnClickListener(v -> {
            if (currentAddress == null) {
                showMessage(getString(R.string.host_no_address));
                return;
            }
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.host_share_subject));
            share.putExtra(Intent.EXTRA_TEXT, currentAddress);
            startActivity(Intent.createChooser(share, getString(R.string.host_share_subject)));
        });

        swMirror.setOnCheckedChangeListener(this::onMirrorToggled);

        btnTouchSettings.setOnClickListener(v -> {
            // Android deliberately gives no API to enable an accessibility service:
            // only the user, in Settings, can grant this. So all we can do is open
            // the right screen - and say so in the UI rather than pretend otherwise.
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                showMessage(getString(R.string.host_touch_enable));
            } catch (RuntimeException e) {
                appendLog("could not open accessibility settings: " + e.getMessage());
            }
        });

        findViewById(R.id.btnGuestMode).setOnClickListener(v ->
                startActivity(new Intent(this, GuestActivity.class)));
    }

    /**
     * MaterialSwitch is a {@code SwitchCompat}, so the label doubles as the
     * explanation for the two capture modes.
     */
    private void onMirrorToggled(android.widget.CompoundButton button, boolean checked) {
        button.setText(checked ? R.string.host_mode_mirror : R.string.host_mode_public);
        if (ScreenCaptureService.isStreaming()) {
            // Remembered because the service's EXTRA_RECONSENT broadcast will snap the
            // switch back to the live mode; the answer to the system dialog must apply
            // THIS value regardless of what the switch shows at that point.
            desiredMirror = checked;
            Intent intent = new Intent(this, ScreenCaptureService.class);
            intent.setAction(ScreenCaptureService.ACTION_SET_MODE);
            intent.putExtra(ScreenCaptureService.EXTRA_MIRROR_FLAG, checked);
            ContextCompat.startForegroundService(this, intent);
        }
    }

    private void onToggleClicked() {
        if (ScreenCaptureService.isStreaming()) {
            Intent intent = new Intent(this, ScreenCaptureService.class);
            intent.setAction(ScreenCaptureService.ACTION_STOP);
            ContextCompat.startForegroundService(this, intent);
            appendLog("stop requested");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        requestProjection();
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
        projectionLauncher.launch(manager.createScreenCaptureIntent());
    }

    private void startCapture(int resultCode, Intent data) {
        startCapture(resultCode, data, swMirror.isChecked());
    }

    /** @param mirror mode this capture should use; may differ from the switch while a
     *  mode-change consent was in flight (Android 14 re-consent flow). */
    private void startCapture(int resultCode, Intent data, boolean mirror) {
        Intent intent = new Intent(this, ScreenCaptureService.class);
        intent.setAction(ScreenCaptureService.ACTION_START);
        intent.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
        // The permission token is a Parcelable Intent; the service needs it verbatim.
        intent.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
        intent.putExtra(ScreenCaptureService.EXTRA_MAX_SIDE, selectedMaxSide());
        intent.putExtra(ScreenCaptureService.EXTRA_MIRROR_FLAG, mirror);

        ContextCompat.startForegroundService(this, intent);

        // Keep the screen awake so the capture keeps producing frames.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        appendLog("capture service starting (" + selectedMaxSide() + " px longest side)");
        renderState(ScreenCaptureService.STATE_RUNNING, null);
    }

    private String[] resolutionLabels() {
        return getResources().getStringArray(R.array.host_resolution_options);
    }

    /** Maps the dropdown's visible label back to a preset by index. */
    private int selectedMaxSide() {
        String[] labels = resolutionLabels();
        String chosen = spnResolution.getText() == null ? "" : spnResolution.getText().toString();
        for (int i = 0; i < labels.length && i < RESOLUTION_PRESETS.length; i++) {
            if (labels[i].equals(chosen)) {
                return RESOLUTION_PRESETS[i];
            }
        }
        return RESOLUTION_PRESETS[DEFAULT_PRESET_INDEX];
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

            // UI-only sync. Never call onMirrorToggled here: the service's own state
            // is what we are copying, so echoing it back as a command can only be
            // redundant - or, when a broadcast lacked the extra and defaulted mirror
            // to true on a PUBLIC session, contradictory (it used to tell the service
            // to recreate the virtual display out of nowhere).
            boolean mirror = intent.getBooleanExtra("mirror", true);
            syncMirrorUi(mirror);
            if (intent.getBooleanExtra(ScreenCaptureService.EXTRA_RECONSENT, false)
                    && !awaitingReconsent) {
                awaitingReconsent = true;
                // The service keeps streaming on the live mode until the user decides;
                // a refusal changes nothing because the service never left that mode.
                requestProjection();
            }
        }
    };

    /** Push the service's live mode into switch + label without firing the listener. */
    private void syncMirrorUi(boolean mirror) {
        if (swMirror.isChecked() == mirror) {
            return;
        }
        swMirror.setOnCheckedChangeListener(null);
        swMirror.setChecked(mirror);
        swMirror.setText(mirror ? R.string.host_mode_mirror : R.string.host_mode_public);
        swMirror.setOnCheckedChangeListener(HostActivity.this::onMirrorToggled);
    }

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
        // The dropdown only matters before a session starts: a different capture size
        // needs a brand new encoder, which is what stopping and starting does.
        boxResolution.setEnabled(!running);
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

    /**
     * Reflects the accessibility grant in the UI.
     *
     * <p>Checked against the system's enabled-services list rather than against
     * {@code InjectionAccessibilityService.instance()}: the service binds
     * asynchronously, and this activity can be on screen before it does.
     */
    private void refreshTouchStatus() {
        if (!ApiLevels.canInject(Build.VERSION.SDK_INT)) {
            // Android 5.0-6.x: the host can mirror, but GestureDescription does not
            // exist yet, so there is nothing to enable and no settings page that would
            // help. Say so instead of showing a switch that cannot work.
            dotTouch.setActivated(false);
            dotTouch.setSelected(false);
            txtTouchStatus.setText(getString(R.string.host_touch_too_old,
                    Build.VERSION.RELEASE));
            btnTouchSettings.setEnabled(false);
            btnTouchSettings.setAlpha(0.5f);
            return;
        }
        boolean enabled = InjectionAccessibilityService.isEnabled(this);
        dotTouch.setActivated(enabled);
        dotTouch.setSelected(false);
        txtTouchStatus.setText(enabled
                ? R.string.host_touch_enabled
                : R.string.host_touch_disabled);
        btnTouchSettings.setEnabled(true);
        btnTouchSettings.setAlpha(1f);
        btnTouchSettings.setText(enabled
                ? R.string.host_touch_open_settings
                : R.string.host_touch_enable);
    }

    private void showMessage(String message) {
        Snackbar.make(btnToggle, message, Snackbar.LENGTH_SHORT).show();
    }

    private void appendLog(String message) {
        String line = logTime.format(new Date()) + "  " + message + "\n";
        String updated = txtLog.getText().toString() + line;
        // Keep the log bounded: a long session would otherwise grow this TextView
        // until the main thread chokes laying it out.
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
