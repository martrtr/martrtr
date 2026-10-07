package io.openai.footdraw;

import android.content.Context;
import android.net.*;
import android.net.wifi.*;
import android.os.Build;
import android.util.Log;
import java.net.Inet4Address;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class NetworkRouter implements AutoCloseable {
    static final String CAMERA_SSID = "HD-b28984";
    private static final String TAG = "FootDraw-Net";

    private final Context context;
    private final ConnectivityManager cm;
    private final WifiManager wm;
    private final Object lock = new Object();

    private volatile Network cellular;
    private volatile Network cameraWifi;
    private ConnectivityManager.NetworkCallback cellularCb;
    private ConnectivityManager.NetworkCallback wifiCb;
    private ConnectivityManager.NetworkCallback cameraRequestCb;
    private volatile boolean running;
    private Thread keeper;
    private volatile boolean rootAvailable;
    private volatile boolean rootChecked;

    NetworkRouter(Context c) {
        context = c.getApplicationContext();
        cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
    }

    void start() {
        if (running || cm == null) return;
        running = true;
        refreshExisting();

        NetworkRequest cellReq = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        cellularCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { updateCellular(n); }
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) updateCellular(n);
            }
            @Override public void onLost(Network n) {
                synchronized (lock) {
                    if (n.equals(cellular)) cellular = null;
                    lock.notifyAll();
                }
            }
        };
        try { cm.requestNetwork(cellReq, cellularCb); }
        catch (Exception e) { Log.w(TAG, "cell request", e); }

        NetworkRequest wifiReq = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        wifiCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { considerWifi(n); }
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { considerWifi(n); }
            @Override public void onLost(Network n) {
                synchronized (lock) {
                    if (n.equals(cameraWifi)) cameraWifi = null;
                    lock.notifyAll();
                }
            }
        };
        try { cm.registerNetworkCallback(wifiReq, wifiCb); }
        catch (Exception e) { Log.w(TAG, "wifi callback", e); }

        installSuggestion();

        keeper = new Thread(this::keepCameraConnected, "FootDraw-CameraWiFi");
        keeper.start();
    }

    private void updateCellular(Network n) {
        synchronized (lock) {
            if (!n.equals(cellular)) Log.i(TAG, "cellular=" + n);
            cellular = n;
            lock.notifyAll();
        }
    }

    private void considerWifi(Network n) {
        if (!isCameraNetwork(n)) return;
        synchronized (lock) {
            if (!n.equals(cameraWifi))
                Log.i(TAG, "camera wifi=" + n + " ssid=" + networkSsid(n));
            cameraWifi = n;
            lock.notifyAll();
        }
    }

    private void refreshExisting() {
        if (cm == null) return;
        Network foundCell = null, foundCam = null;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null) continue;
                if (foundCell == null
                        && c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) foundCell = n;
                if (foundCam == null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && isCameraNetwork(n)) foundCam = n;
            }
        } catch (Exception ignored) {}
        synchronized (lock) {
            cellular = foundCell;
            cameraWifi = foundCam;
            lock.notifyAll();
        }
    }

    private boolean isCameraNetwork(Network n) {
        String ssid = networkSsid(n);
        if (CAMERA_SSID.equals(ssid)) return true;
        // On some Android builds the SSID is hidden from NetworkCapabilities even
        // though the app owns the network. The camera itself is very distinctive:
        // it hands the phone 192.168.1.2/24 and uses 192.168.1.1 as gateway.
        try {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) return false;
            boolean gateway = false, cameraAddress = false;
            for (RouteInfo r : lp.getRoutes()) {
                java.net.InetAddress g = r.getGateway();
                if (g instanceof Inet4Address && "192.168.1.1".equals(g.getHostAddress()))
                    gateway = true;
            }
            for (LinkAddress a : lp.getLinkAddresses()) {
                java.net.InetAddress x = a.getAddress();
                if (x instanceof Inet4Address && "192.168.1.2".equals(x.getHostAddress()))
                    cameraAddress = true;
            }
            return gateway && cameraAddress;
        } catch (Exception ignored) {}
        return false;
    }

    private String networkSsid(Network n) {
        try {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && Build.VERSION.SDK_INT >= 29) {
                Object ti = c.getTransportInfo();
                if (ti instanceof WifiInfo) return cleanSsid(((WifiInfo) ti).getSSID());
            }
            Network active = cm.getActiveNetwork();
            if (active != null && active.equals(n) && wm != null) {
                @SuppressWarnings("deprecation") WifiInfo wi = wm.getConnectionInfo();
                if (wi != null) return cleanSsid(wi.getSSID());
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String cleanSsid(String s) {
        if (s == null) return "";
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length()-1) == '"')
            return s.substring(1, s.length()-1);
        return s;
    }

    private void installSuggestion() {
        if (wm == null || Build.VERSION.SDK_INT < 29) return;
        try {
            WifiNetworkSuggestion.Builder b = new WifiNetworkSuggestion.Builder()
                    .setSsid(CAMERA_SSID)
                    .setIsAppInteractionRequired(false);
            if (Build.VERSION.SDK_INT >= 30) b.setIsInitialAutojoinEnabled(true);
            int result = wm.addNetworkSuggestions(Collections.singletonList(b.build()));
            Log.i(TAG, "camera suggestion result=" + result);
        } catch (Throwable e) {
            Log.w(TAG, "camera suggestion unavailable", e);
        }
    }

    // Non-root fallback. Android may require one user approval the first time;
    // after approval the suggestion above is the persistent reconnect mechanism.
    private synchronized void requestCameraNetwork() {
        if (cm == null || Build.VERSION.SDK_INT < 29 || cameraRequestCb != null || !running) return;
        try {
            WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder()
                    .setSsid(CAMERA_SSID).build();
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(spec)
                    .build();
            final ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { considerWifi(n); }
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { considerWifi(n); }
                @Override public void onLost(Network n) {
                    synchronized (lock) {
                        if (n.equals(cameraWifi)) cameraWifi = null;
                        lock.notifyAll();
                    }
                }
                @Override public void onUnavailable() {
                    synchronized (NetworkRouter.this) {
                        if (cameraRequestCb == this) cameraRequestCb = null;
                    }
                    synchronized (lock) { lock.notifyAll(); }
                }
            };
            cameraRequestCb = cb;
            cm.requestNetwork(req, cb, 15000);
            Log.i(TAG, "requested camera SSID " + CAMERA_SSID);
        } catch (Throwable e) {
            Log.w(TAG, "camera network request unavailable", e);
            cameraRequestCb = null;
        }
    }

    private synchronized boolean hasRoot() {
        if (rootChecked) return rootAvailable;
        try {
            Process p = new ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start();
            boolean done = p.waitFor(3500, TimeUnit.MILLISECONDS);
            rootAvailable = done && p.exitValue() == 0;
            if (!done) try { p.destroyForcibly(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
            rootAvailable = false;
        }
        rootChecked = true;
        Log.i(TAG, "root=" + rootAvailable);
        return rootAvailable;
    }

    private void rootConnectCamera() {
        if (!hasRoot()) return;
        try {
            // Only switch networks if the camera SSID is actually visible. This
            // avoids repeatedly tearing at the user's normal Wi-Fi while the
            // camera is powered off.
            String q = "'" + CAMERA_SSID.replace("'", "'\\''") + "'";
            String cmd =
                    "cmd wifi set-wifi-enabled enabled >/dev/null 2>&1 || true; "
                    + "svc wifi enable >/dev/null 2>&1 || true; "
                    + "cmd wifi start-scan >/dev/null 2>&1 || true; "
                    + "sleep 0.15; "
                    + "if cmd wifi list-scan-results 2>/dev/null | grep -Fq " + q + "; then "
                    + "cmd wifi connect-network " + q + " open -r none >/dev/null 2>&1 "
                    + "|| cmd wifi connect-network " + q + " open >/dev/null 2>&1 "
                    + "|| true; fi";
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            if (!p.waitFor(2600, TimeUnit.MILLISECONDS)) {
                try { p.destroyForcibly(); } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            Log.w(TAG, "root camera connect", e);
        }
    }

    private void keepCameraConnected() {
        long lastRootAttempt = 0, lastSpecifierAttempt = 0;
        boolean root = hasRoot();
        while (running) {
            refreshExisting();
            if (cameraWifi == null) {
                long now = System.currentTimeMillis();
                if (root) {
                    if (now - lastRootAttempt > 1800) {
                        rootConnectCamera();
                        lastRootAttempt = now;
                    }
                } else if (now - lastSpecifierAttempt > 16000) {
                    requestCameraNetwork();
                    lastSpecifierAttempt = now;
                }
            }
            try { Thread.sleep(cameraWifi == null ? 650 : 2000); }
            catch (InterruptedException ignored) {}
        }
    }

    Network awaitCellular(long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (running) {
            refreshExisting();
            Network n = cellular;
            if (n != null) return n;
            synchronized (lock) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) return null;
                lock.wait(Math.min(left, 400));
            }
        }
        return null;
    }

    Network awaitCameraWifi(long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (running) {
            refreshExisting();
            Network n = cameraWifi;
            if (n != null) return n;
            synchronized (lock) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) return null;
                lock.wait(Math.min(left, 400));
            }
        }
        return null;
    }

    @Override public void close() {
        running = false;
        if (keeper != null) keeper.interrupt();
        if (cm != null) {
            try { if (cellularCb != null) cm.unregisterNetworkCallback(cellularCb); } catch (Exception ignored) {}
            try { if (wifiCb != null) cm.unregisterNetworkCallback(wifiCb); } catch (Exception ignored) {}
            try { if (cameraRequestCb != null) cm.unregisterNetworkCallback(cameraRequestCb); } catch (Exception ignored) {}
        }
        synchronized (lock) { cellular = null; cameraWifi = null; lock.notifyAll(); }
    }
}
