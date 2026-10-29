package ua.volkov.glass;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ping, download and upload against Cloudflare's speed test endpoints (speed.cloudflare.com).
 * Four parallel streams for about 8 seconds each way. Blocking: run off the main thread.
 */
final class SpeedTest {
    private static final String BASE = "https://speed.cloudflare.com/";
    private static final int STREAMS = 4;
    private static final long PHASE_MS = 8_000L;

    interface Progress {
        /** phase: "ping", "down", "up"; value in ms or Mbit/s so far. */
        void update(String phase, double value);
    }

    static final class Result {
        double pingMs, downMbps, upMbps;
    }

    private SpeedTest() {}

    static Result run(Progress p) throws IOException {
        Result r = new Result();
        r.pingMs = ping();
        p.update("ping", r.pingMs);
        r.downMbps = transfer(false, p);
        r.upMbps = transfer(true, p);
        return r;
    }

    static boolean online() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://www.gstatic.com/generate_204").openConnection();
            c.setConnectTimeout(5_000);
            c.setReadTimeout(5_000);
            int code = c.getResponseCode();
            c.disconnect();
            return code == 204 || code == 200;
        } catch (IOException e) {
            return false;
        }
    }

    private static double ping() throws IOException {
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            long t0 = System.nanoTime();
            HttpURLConnection c = (HttpURLConnection) new URL(BASE + "__down?bytes=0").openConnection();
            c.setConnectTimeout(5_000);
            c.setReadTimeout(5_000);
            c.getResponseCode();
            Http.read(c.getInputStream());
            long t = System.nanoTime() - t0;
            if (i > 0) times.add(t);   // the first one pays for DNS and TLS
        }
        Collections.sort(times);
        return times.get(times.size() / 2) / 1e6;
    }

    private static double transfer(boolean upload, Progress p) throws IOException {
        AtomicLong bytes = new AtomicLong();
        AtomicBoolean stop = new AtomicBoolean();
        List<Thread> threads = new ArrayList<>();
        final IOException[] failure = new IOException[1];
        long start = System.nanoTime();
        for (int i = 0; i < STREAMS; i++) {
            Thread t = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        if (upload) uploadOnce(bytes, stop);
                        else downloadOnce(bytes, stop);
                    }
                } catch (IOException e) {
                    synchronized (failure) {
                        if (failure[0] == null) failure[0] = e;
                    }
                }
            });
            t.start();
            threads.add(t);
        }
        long end = System.currentTimeMillis() + PHASE_MS;
        while (System.currentTimeMillis() < end) {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            p.update(upload ? "up" : "down", mbps(bytes.get(), System.nanoTime() - start));
        }
        stop.set(true);
        long elapsed = System.nanoTime() - start;
        for (Thread t : threads) {
            try {
                t.join(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (bytes.get() == 0 && failure[0] != null) throw failure[0];
        return mbps(bytes.get(), elapsed);
    }

    private static double mbps(long bytes, long nanos) {
        return nanos <= 0 ? 0 : bytes * 8.0 / (nanos / 1e9) / 1e6;
    }

    private static void downloadOnce(AtomicLong bytes, AtomicBoolean stop) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + "__down?bytes=25000000").openConnection();
        c.setConnectTimeout(8_000);
        c.setReadTimeout(8_000);
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while (!stop.get() && (n = in.read(buf)) > 0) bytes.addAndGet(n);
        } finally {
            c.disconnect();
        }
    }

    private static void uploadOnce(AtomicLong bytes, AtomicBoolean stop) throws IOException {
        int size = 4_000_000;
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + "__up").openConnection();
        c.setConnectTimeout(8_000);
        c.setReadTimeout(8_000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.setFixedLengthStreamingMode(size);
        byte[] buf = new byte[64 * 1024];
        try {
            try (OutputStream out = c.getOutputStream()) {
                int left = size;
                while (left > 0 && !stop.get()) {
                    int n = Math.min(buf.length, left);
                    out.write(buf, 0, n);
                    bytes.addAndGet(n);
                    left -= n;
                }
                if (left > 0) return;   // stopped mid-body: drop the connection
            }
            c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }
}
