package damjay.control.ghosthand.host;

import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * A thin, optional bridge to Shizuku: shell-level commands as the adb shell
 * user, available ONLY when the Shizuku app is installed, running, and the
 * user has granted this package permission in Shizuku's own dialog.
 *
 * <p>Why Shizuku: an ordinary app cannot rotate the phone while another app is
 * in front, and cannot grant itself WRITE_SECURE_SETTINGS - but `settings put
 * system user_rotation ...` from the shell user can. That turns the guest's
 * Rotate button into a system-wide rotation that works even when GhostHand is
 * backgrounded, instead of a window-level flip that only works while its own
 * activity is in front.
 *
 * <p>Why it is optional: Shizuku needs Android 7+ and (without root) a working
 * adb - USB or the Android 11+ wireless-debugging flow - to even start. A
 * KitKat guest phone has none of that, and does not need it: every call site
 * here falls back to the plain app-level path when {@link State} is not
 * {@link State#GRANTED}, so nothing about the core app depends on this class.
 *
 * <p>Threading: every method that talks binder must be called OFF the main
 * thread except probe()/requestPermission(); {@link #run} always calls back on
 * a fresh worker thread. Everything is wrapped in catch Throwable: a dead
 * binder, a pre-v11 server, a denied permission and a missing class all
 * degrade to State.UNAVAILABLE instead of crashing the capture session.
 */
public final class ElevatedShell {

    private static final String TAG = "GhostHand";

    public enum State {
        /** Shizuku cannot be used: not installed, not running, or pre-v11. */
        UNAVAILABLE,
        /** Server is alive but this package has no permission yet. */
        DENIED,
        /** Permission granted - shell commands are allowed. */
        GRANTED
    }

    /** Result of a shell command: exit code + captured stdout. */
    public interface Result {
        void onResult(int exitCode, String stdout);
    }

    private ElevatedShell() {
    }

    /** True when the Shizuku app itself is installed (needs the <queries> tag). */
    public static boolean isInstalled(android.content.Context context) {
        try {
            context.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Cheap, main-thread-safe probe. Never throws. */
    public static State probe() {
        try {
            if (Shizuku.isPreV11()) {
                return State.UNAVAILABLE;
            }
            if (!Shizuku.pingBinder()) {
                return State.UNAVAILABLE;
            }
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    ? State.GRANTED : State.DENIED;
        } catch (Throwable t) {
            // Missing Shizuku app, dead binder, hidden-API refusal - all the same
            // answer: not available, never a crash.
            Log.i(TAG, "shizuku probe failed: " + t);
            return State.UNAVAILABLE;
        }
    }

    /**
     * Asks Shizuku to show its own permission dialog for this package.
     * Returns whether the request was actually dispatched - the caller shows
     * that answer in the UI, because "pressed Grant and nothing happened" is
     * precisely the silence we must never produce. No-op (false) when the
     * server is not running: nothing could show the dialog anyway.
     */
    public static boolean requestPermission() {
        try {
            if (!Shizuku.isPreV11() && Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(0x5A12);
                return true;
            }
        } catch (Throwable t) {
            Log.i(TAG, "shizuku request failed: " + t);
        }
        return false;
    }

    /**
     * Runs {@code command} through the Shizuku server as the shell user.
     * Typed proxy over the official AIDL: IShizukuService.newProcess has the
     * pinned transaction code 7, so this survives server upgrades; the method
     * is simply forbidden by the server when the permission is missing, which
     * surfaces as a caught exception and {@code exitCode = -1}.
     */
    public static void run(String command, Result callback) {
        Thread t = new Thread(() -> {
            int exit = -1;
            String out = "";
            try {
                IBinder binder = Shizuku.getBinder();
                if (binder == null) {
                    throw new IllegalStateException("no shizuku binder");
                }
                IShizukuService svc = IShizukuService.Stub.asInterface(binder);
                moe.shizuku.server.IRemoteProcess proc =
                        svc.newProcess(new String[] { "sh", "-c", command }, null, null);
                try {
                    // The AIDL surface hands out a ParcelFileDescriptor; the public
                    // ShizukuRemoteProcess wrapper would convert it, but newProcess
                    // itself is deprecated-private, so we wrap it ourselves.
                    java.io.InputStream in = new android.os.ParcelFileDescriptor
                            .AutoCloseInputStream(proc.getInputStream());
                    StringBuilder sb = new StringBuilder();
                    byte[] buf = new byte[256];
                    int n;
                    while ((n = in.read(buf)) > 0 && sb.length() < 4096) {
                        sb.append(new String(buf, 0, n, "UTF-8"));
                    }
                    out = sb.toString().trim();
                    exit = proc.waitFor();
                } finally {
                    try {
                        proc.destroy();
                    } catch (Throwable ignored) {
                        // already dead
                    }
                }
            } catch (Throwable e) {
                Log.i(TAG, "shizuku command failed: " + e);
            }
            callback.onResult(exit, out);
        }, "ghosthand-shell");
        t.setDaemon(true);
        t.start();
    }
}
