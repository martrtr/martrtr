package io.openai.footdraw;

import android.content.Context;
import android.net.*;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class JieliFpvClient implements AutoCloseable {
    private static final String TAG = "FootDraw-FPV";
    interface Listener { void onFrame(byte[] jpeg, long seq, int type); }

    private final NetworkRouter router;
    private final ConnectivityManager cm;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;
    private volatile DatagramSocket video;
    private volatile Socket control;

    JieliFpvClient(Context context, NetworkRouter router, Listener listener) {
        this.router = router;
        this.cm = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        this.listener = listener;
    }

    void start() {
        if (!running.compareAndSet(false, true)) return;
        thread = new Thread(this::loop, "FootDraw-JieliFPV");
        thread.start();
    }

    @Override public void close() {
        running.set(false);
        try { if (video != null) video.close(); } catch (Exception ignored) {}
        try { if (control != null) control.close(); } catch (Exception ignored) {}
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        long backoff = 250;
        while (running.get()) {
            try {
                Network wifi = router.awaitCameraWifi(10000);
                if (wifi == null) throw new IOException("camera Wi-Fi unavailable");
                InetAddress gateway = gateway(wifi);
                if (gateway == null) gateway = InetAddress.getByName("192.168.1.1");
                Log.i(TAG, "camera network=" + wifi + " gateway=" + gateway.getHostAddress());

                DatagramSocket v = new DatagramSocket(null);
                video = v;
                v.setReuseAddress(true);
                wifi.bindSocket(v);
                v.bind(new InetSocketAddress(2224));
                v.setReceiveBufferSize(1024 * 1024);
                v.setSoTimeout(900);

                Socket ctl = new Socket();
                control = ctl;
                wifi.bindSocket(ctl);
                ctl.connect(new InetSocketAddress(gateway, 2222), 2600);
                ctl.setTcpNoDelay(true);
                ctl.setKeepAlive(true);
                ctl.setSoTimeout(150);
                OutputStream out = ctl.getOutputStream();
                InputStream in = ctl.getInputStream();

                send(out, 1, 29, "0", "69");
                sleep(100);
                send(out, 1, 50);
                sleep(100);
                send(out, 1, 50, "9", "0");
                Log.i(TAG, "stream requested");
                backoff = 250;

                JieliStreamParser parser = new JieliStreamParser((jpeg, seq, type, channel) -> {
                    if (jpeg != null && jpeg.length >= 100 && listener != null) {
                        listener.onFrame(jpeg, seq, type);
                    }
                });

                byte[] buf = new byte[65535];
                byte[] reply = new byte[2048];
                long heartbeat = System.currentTimeMillis();
                long lastPacket = heartbeat;

                while (running.get()) {
                    try {
                        int avail = in.available();
                        if (avail > 0) {
                            int n = in.read(reply, 0, Math.min(reply.length, avail));
                            if (n < 0) throw new EOFException("camera control closed");
                        }
                    } catch (SocketTimeoutException ignored) {}

                    long now = System.currentTimeMillis();
                    if (now - heartbeat > 2800) {
                        send(out, 9999, 9993);
                        heartbeat = now;
                    }

                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        v.receive(p);
                        lastPacket = now;
                        parser.accept(p.getData(), p.getOffset(), p.getLength());
                    } catch (SocketTimeoutException timeout) {
                        if (now - lastPacket > 4500) throw new SocketTimeoutException("camera stream stalled");
                    }
                }
            } catch (Exception e) {
                if (running.get()) Log.w(TAG, "camera reconnect: " + e);
            } finally {
                DatagramSocket v = video; video = null;
                Socket ctl = control; control = null;
                try { if (v != null) v.close(); } catch (Exception ignored) {}
                try { if (ctl != null) ctl.close(); } catch (Exception ignored) {}
            }
            sleep(backoff);
            backoff = Math.min(3000, backoff * 2);
        }
    }

    private InetAddress gateway(Network n) {
        try {
            LinkProperties lp = cm == null ? null : cm.getLinkProperties(n);
            if (lp == null) return null;
            for (RouteInfo r : lp.getRoutes()) {
                InetAddress g = r.getGateway();
                if (g != null && g.getAddress().length == 4 && !g.isAnyLocalAddress()) return g;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void send(OutputStream out, int id, int cmd, String... args) throws IOException {
        StringBuilder p = new StringBuilder();
        for (String a : args) {
            if (p.length() > 0) p.append(' ');
            p.append(a);
        }
        String msg = String.format(Locale.US, "CTP:%04d %04d %04d ", id, cmd, p.length()) + p;
        out.write(msg.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
