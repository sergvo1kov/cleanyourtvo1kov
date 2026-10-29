package ua.volkov.glass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Posters and backdrops: a memory cache, then a disk cache in the app's cache folder (so after the first
 * time the row draws at once, even right after a reboot), then the network with four downloads at a time.
 */
final class Images {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService IO = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long DISK_MAX = 60L * 1024 * 1024;
    private static Context app;

    private Images() {}

    /** Lets content:// pictures (TV cards) load through the app's resolver, and sets up the disk cache. */
    static void init(Context ctx) {
        app = ctx.getApplicationContext();
        IO.execute(Images::trimDisk);
    }

    static void load(String url, ImageView into) {
        into.setTag(url);
        if (url == null || url.isEmpty()) {
            into.setImageDrawable(null);
            return;
        }
        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            into.setImageBitmap(hit);
            return;
        }
        into.setImageDrawable(null);
        IO.execute(() -> {
            Bitmap b = fetch(url);
            if (b == null) return;
            CACHE.put(url, b);
            MAIN.post(() -> {
                if (url.equals(into.getTag())) {
                    into.setAlpha(0f);
                    into.setImageBitmap(b);
                    into.animate().alpha(1f).setDuration(180).start();
                }
            });
        });
    }

    /** Downloads in the background so the pictures are ready before they are shown. */
    static void prefetch(String url) {
        if (url == null || url.isEmpty() || CACHE.get(url) != null) return;
        IO.execute(() -> {
            if (CACHE.get(url) != null) return;
            Bitmap b = fetch(url);
            if (b != null) CACHE.put(url, b);
        });
    }

    interface Got {
        void got(Bitmap b);
    }

    /** The picture at url, cached, delivered on the main thread (null if it cannot be loaded). */
    static void get(String url, Got cb) {
        if (url == null || url.isEmpty()) {
            MAIN.post(() -> cb.got(null));
            return;
        }
        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            MAIN.post(() -> cb.got(hit));
            return;
        }
        IO.execute(() -> {
            Bitmap b = fetch(url);
            if (b != null) CACHE.put(url, b);
            MAIN.post(() -> cb.got(b));
        });
    }

    private static Bitmap fetch(String url) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        if (!url.startsWith("http")) {
            if (app == null) return null;
            try (InputStream in = app.getContentResolver().openInputStream(Uri.parse(url))) {
                return in == null ? null : BitmapFactory.decodeStream(in, null, o);
            } catch (Exception e) {
                return null;
            }
        }
        File f = disk(url);
        if (f != null && f.length() > 0) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b != null) {
                f.setLastModified(System.currentTimeMillis());
                return b;
            }
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8_000);
            c.setReadTimeout(12_000);
            c.setRequestProperty("User-Agent", "FlickTV/2");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[16 * 1024];
                for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
                byte[] bytes = buf.toByteArray();
                Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                if (b != null && f != null) {
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(bytes);
                    } catch (Exception ignored) {
                        // cache only
                    }
                }
                return b;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static File disk(String url) {
        if (app == null) return null;
        File dir = new File(app.getCacheDir(), "img");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        return new File(dir, Integer.toHexString(url.hashCode()) + "-" + url.length());
    }

    /** Keeps the disk cache under its limit, dropping the pictures not shown for longest. */
    private static void trimDisk() {
        if (app == null) return;
        File[] files = new File(app.getCacheDir(), "img").listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= DISK_MAX) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= DISK_MAX * 3 / 4) break;
            total -= f.length();
            try {
                Files.deleteIfExists(f.toPath());
            } catch (Exception ignored) {
                // next time
            }
        }
    }

    /** A cheap, smooth blur for the backdrop: shrink hard, then let the GPU stretch it with filtering. */
    static Bitmap backdrop(Bitmap src) {
        if (src == null) return null;
        Bitmap small = Bitmap.createScaledBitmap(src, 24, 14, true);
        return Bitmap.createScaledBitmap(small, 192, 108, true);
    }
}
