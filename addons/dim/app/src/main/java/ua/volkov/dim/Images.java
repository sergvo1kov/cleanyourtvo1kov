package ua.volkov.dim;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Posters from the web, kept in a small memory cache. */
final class Images {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Images() {}

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
                if (url.equals(into.getTag())) into.setImageBitmap(b);
            });
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
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("User-Agent", "Dim-TV/1.0");
            try (InputStream in = c.getInputStream()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeStream(in, null, o);
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** A cheap, smooth blur for the backdrop: shrink hard, then let the GPU stretch it with filtering. */
    static Bitmap backdrop(Bitmap src) {
        if (src == null) return null;
        Bitmap small = Bitmap.createScaledBitmap(src, 24, 14, true);
        return Bitmap.createScaledBitmap(small, 192, 108, true);
    }
}
