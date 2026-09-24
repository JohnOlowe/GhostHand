package damjay.control.ghosthand.host;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import damjay.control.ghosthand.net.GhostProtocol;

/**
 * Host side discovery: advertises "there is a GhostHand stream here" over mDNS
 * (Android calls the API NSD - Network Service Discovery) and answers the much
 * more mundane question "what IP address should I type in?".
 *
 * <p><b>Why both?</b> mDNS is lovely when it works - the guest just shows a list of
 * hosts with no typing. But it is famously unreliable on some routers and on phone
 * hotspots that do client isolation, so the manual IP path is not a fallback for
 * debugging, it is a first-class feature. Both are always available.
 *
 * <p>NSD registration is asynchronous: {@link NsdManager#registerService} returns
 * immediately and the result arrives in {@link NsdRegistrationListener}. A failure
 * there must never kill the stream, so it is only logged.
 */
public class HostController {

    private static final String TAG = "GhostHand/HostCtl";

    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private String registeredName;
    private volatile boolean registered;

    /** Starts advertising. Safe to call once per session. */
    public void start(Context context, String serviceName) {
        if (nsdManager == null) {
            Object service = context.getSystemService(Context.NSD_SERVICE);
            nsdManager = (service instanceof NsdManager) ? (NsdManager) service : null;
        }
        if (nsdManager == null) {
            Log.w(TAG, "NSD unavailable on this device; manual IP entry still works");
            return;
        }

        NsdServiceInfo info = new NsdServiceInfo();
        // The "_xxx._tcp." shape is mandatory: NSD rejects anything else.
        info.setServiceType(GhostProtocol.NSD_SERVICE_TYPE);
        info.setServiceName(serviceName);
        info.setPort(GhostProtocol.DEFAULT_PORT);

        registrationListener = new NsdRegistrationListener();
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        } catch (RuntimeException e) {
            // Happens when a previous registration was never torn down.
            Log.w(TAG, "registerService failed: " + e.getMessage());
            registrationListener = null;
        }
    }

    /** Stops advertising. Must be called from the same session that started it. */
    public void stop(Context context) {
        if (nsdManager != null && registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener);
            } catch (RuntimeException e) {
                Log.w(TAG, "unregisterService failed: " + e.getMessage());
            }
        }
        registrationListener = null;
        registered = false;
        registeredName = null;
    }

    public boolean isRegistered() {
        return registered;
    }

    public String getRegisteredName() {
        return registeredName;
    }

    /**
     * Every IPv4 address this device currently has, formatted as "ip:port".
     *
     * <p>We enumerate {@link NetworkInterface}s rather than using
     * {@code WifiManager.getConnectionInfo().getIpAddress()} because the latter is
     * deprecated, returns 0 on Android 12+, and would miss hotspot/tethered links.
     *
     * <p>Order matters for the UI: site-local addresses (192.168.x.x, 10.x.x.x,
     * 172.16-31.x.x) come first because those are the ones a peer phone can reach.
     */
    public List<String> listAddresses(int port) {
        List<String> preferred = new ArrayList<>();
        List<String> others = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.isLoopbackAddress() || !(addr instanceof Inet4Address)) {
                        continue; // IPv4 only: simpler for a human to type
                    }
                    String entry = addr.getHostAddress() + ":" + port;
                    if (addr.isSiteLocalAddress()) {
                        preferred.add(entry);
                    } else {
                        others.add(entry);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "listAddresses failed: " + e.getMessage());
        }
        preferred.addAll(others);
        return preferred;
    }

    /** First site-local IPv4 address, or null. Used for the "share address" button. */
    public String primaryAddress(int port) {
        List<String> all = listAddresses(port);
        return all.isEmpty() ? null : all.get(0);
    }

    private class NsdRegistrationListener implements NsdManager.RegistrationListener {

        @Override
        public void onServiceRegistered(NsdServiceInfo serviceInfo) {
            registered = true;
            registeredName = serviceInfo.getServiceName();
            Log.i(TAG, "advertised as '" + registeredName + "' on "
                    + GhostProtocol.NSD_SERVICE_TYPE);
        }

        @Override
        public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            registered = false;
            Log.w(TAG, "NSD registration failed, errorCode=" + errorCode);
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            registered = false;
            Log.i(TAG, "NSD unregistered");
        }

        @Override
        public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            Log.w(TAG, "NSD unregistration failed, errorCode=" + errorCode);
        }
    }
}
