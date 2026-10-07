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
        try { cm.requestNetwork(cellReq, cellularCb); } catch (Exception e) { Log.w(TAG, "cell request", e); }

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
        try { cm.registerNetworkCallback(wifiReq, wifiCb); } catch (Exception e) { Log.w(TAG, "wifi callback", e); }

        installSuggestion();
        requestCameraNetwork();

        keeper = new Thread(this::keepCameraConnected, "FootDraw-CameraWiFi");
        keeper.start();
    }

    private void updateCellular(Network n) {
        synchronized (lock) {
            cellular = n;
            Log.i(TAG, "cellular=" + n);
            lock.notifyAll();
        }
    }

    private void considerWifi(Network n) {
        if (!isCameraNetwork(n)) return;
        synchronized (lock) {
            cameraWifi = n;
            Log.i(TAG, "camera wifi=" + n + " ssid=" + networkSsid(n));
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
            if (foundCell != null) cellular = foundCell;
            if (foundCam != null) cameraWifi = foundCam;
            lock.notifyAll();
        }
    }

    private boolean isCameraNetwork(Network n) {
        String ssid = networkSsid(n);
        if (CAMERA_SSID.equals(ssid)) return true;
        try {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp != null) {
                for (RouteInfo r : lp.getRoutes()) {
                    java.net.InetAddress g = r.getGateway();
                    if (g instanceof Inet4Address && "192.168.1.1".equals(g.getHostAddress())) return true;
                }
            }
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
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length()-1) == '"') return s.substring(1, s.length()-1);
        return s;
    }

    private void installSuggestion() {
        if (wm == null || Build.VERSION.SDK_INT < 29) return;
        try {
            WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                    .setSsid(CAMERA_SSID)
                    .setIsAppInteractionRequired(false)
                    .build();
            int result = wm.addNetworkSuggestions(Collections.singletonList(suggestion));
            Log.i(TAG, "camera suggestion result=" + result);
        } catch (Throwable e) {
            Log.w(TAG, "camera suggestion unavailable", e);
        }
    }

    private void requestCameraNetwork() {
        if (cm == null || Build.VERSION.SDK_INT < 29 || cameraRequestCb != null) return;
        try {
            WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder().setSsid(CAMERA_SSID).build();
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(spec)
                    .build();
            cameraRequestCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { considerWifi(n); }
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { considerWifi(n); }
                @Override public void onLost(Network n) {
                    synchronized (lock) {
                        if (n.equals(cameraWifi)) cameraWifi = null;
                        lock.notifyAll();
                    }
                }
                @Override public void onUnavailable() {
                    // A NetworkSpecifier request is released after onUnavailable().
                    // Clear our handle so the keeper can issue a fresh request later.
                    cameraRequestCb = null;
                    synchronized (lock) { lock.notifyAll(); }
                }
            };
            cm.requestNetwork(req, cameraRequestCb);
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
            // Different Android/Lineage builds expose slightly different cmd-wifi
            // parsers. Try the modern persistent-free form first, then the
            // simpler form. Both target the exact open camera SSID.
            String cmd =
                    "cmd wifi set-wifi-enabled enabled >/dev/null 2>&1 || true; "
                    + "svc wifi enable >/dev/null 2>&1 || true; "
                    + "cmd wifi connect-network '" + CAMERA_SSID
                    + "' open -r none >/dev/null 2>&1 "
                    + "|| cmd wifi connect-network '" + CAMERA_SSID
                    + "' open >/dev/null 2>&1 || true";
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            if (!p.waitFor(2200, TimeUnit.MILLISECONDS)) {
                try { p.destroyForcibly(); } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            Log.w(TAG, "root camera connect", e);
        }
    }

    private void keepCameraConnected() {
        long lastRootAttempt = 0;
        while (running) {
            refreshExisting();
            if (cameraWifi == null) {
                long now = System.currentTimeMillis();
                if (now - lastRootAttempt > 2200) {
                    rootConnectCamera();
                    lastRootAttempt = now;
                }
                requestCameraNetwork();
            }
            try { Thread.sleep(cameraWifi == null ? 900 : 2500); }
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