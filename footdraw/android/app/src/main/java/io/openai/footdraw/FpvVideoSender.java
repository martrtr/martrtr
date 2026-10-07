package io.openai.footdraw;

import android.net.Network;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

final class FpvVideoSender implements AutoCloseable {
    private final String host;
    private final int port;
    private final String token;
    private final String clientId;
    private final NetworkRouter router;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object lock = new Object();
    private volatile byte[] latest;
    private long generation;
    private Thread thread;
    private volatile Socket socket;

    FpvVideoSender(String host, int port, String token, String clientId, NetworkRouter router) {
        this.host = host;
        this.port = port;
        this.token = token;
        this.clientId = clientId;
        this.router = router;
    }

    void start() {
        if (!running.compareAndSet(false, true)) return;
        thread = new Thread(this::loop, "FootDraw-FPV-Tx");
        thread.start();
    }

    void offer(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 100 || !running.get()) return;
        synchronized (lock) {
            latest = jpeg;
            generation++;
            lock.notifyAll();
        }
    }

    @Override public void close() {
        running.set(false);
        synchronized (lock) { lock.notifyAll(); }
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        long backoff = 250;
        while (running.get()) {
            try {
                Network cell = router.awaitCellular(10000);
                if (cell == null) throw new IOException("cellular unavailable");
                try (Socket s = cell.getSocketFactory().createSocket()) {
                    socket = s;
                    s.connect(new InetSocketAddress(host, port), 4000);
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);
                    s.setSendBufferSize(512 * 1024);
                    BufferedOutputStream raw = new BufferedOutputStream(s.getOutputStream(), 256 * 1024);
                    raw.write(("VIDEO PHONE " + token + " " + clientId + " JPEG/1\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    raw.flush();
                    DataOutputStream out = new DataOutputStream(raw);
                    backoff = 250;
                    long sentGeneration = -1;

                    while (running.get() && !s.isClosed()) {
                        byte[] frame;
                        long g;
                        synchronized (lock) {
                            while (running.get() && generation == sentGeneration) lock.wait(250);
                            if (!running.get()) break;
                            g = generation;
                            frame = latest;
                        }
                        if (frame == null) continue;
                        out.writeInt(frame.length);
                        out.write(frame);
                        out.flush();
                        sentGeneration = g;
                    }
                }
            } catch (Exception ignored) {
                try { Thread.sleep(backoff); } catch (InterruptedException ignored2) {}
                backoff = Math.min(3000, backoff * 2);
            } finally {
                socket = null;
            }
        }
    }
}
