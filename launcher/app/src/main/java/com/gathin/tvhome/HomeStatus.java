package com.gathin.tvhome;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Fork-only: the "Dim" add-on's status (air-raid alert, weather, internet) in the panel under the clock.
 * The launcher stays offline: it only reads one row the add-on publishes through its ContentProvider,
 * re-reading every 30 seconds while Home is on screen (the add-on refreshes its data when read).
 * Absent add-on: create() returns null and Home is exactly as before.
 */
final class HomeStatus {
    static final String AUTHORITY = "ua.volkov.dim.status";
    private static final Uri URI = Uri.parse("content://" + AUTHORITY + "/now");
    private static final long POLL_MS = 30_000L;

    /** One snapshot as the add-on wrote it. */
    static final class Snap {
        final String kicker;
        final String headline;
        final String detail;
        final int colour;
        final boolean alert;
        final long sinceMs;

        Snap(String kicker, String headline, String detail, int colour, boolean alert, long sinceMs) {
            this.kicker = kicker;
            this.headline = headline;
            this.detail = detail;
            this.colour = colour;
            this.alert = alert;
            this.sinceMs = sinceMs;
        }
    }

    private final Context ctx;
    private final Handler main;
    private final Runnable onChange;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ContentObserver observer;
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            read();
            main.postDelayed(this, POLL_MS);
        }
    };
    private volatile Snap snap;
    private boolean watching;

    private HomeStatus(Context ctx, Handler main, Runnable onChange) {
        this.ctx = ctx.getApplicationContext();
        this.main = main;
        this.onChange = onChange;
        this.observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                read();
            }
        };
    }

    /** Null when the add-on is not installed (or not visible to this app). */
    static HomeStatus create(Context ctx, Handler main, Runnable onChange) {
        try {
            if (ctx.getPackageManager().resolveContentProvider(AUTHORITY, 0) == null) return null;
        } catch (RuntimeException e) {
            return null;
        }
        return new HomeStatus(ctx, main, onChange);
    }

    Snap snap() {
        return snap;
    }

    void start() {
        if (!watching) {
            try {
                ctx.getContentResolver().registerContentObserver(URI, false, observer);
                watching = true;
            } catch (RuntimeException ignored) {
                // add-on removed since create(): nothing to watch, poll() keeps trying quietly
            }
        }
        main.removeCallbacks(poll);
        main.post(poll);
    }

    void stop() {
        main.removeCallbacks(poll);
        if (watching) {
            ctx.getContentResolver().unregisterContentObserver(observer);
            watching = false;
        }
    }

    void shutdown() {
        stop();
        io.shutdownNow();
    }

    void read() {
        try {
            io.execute(() -> {
                Snap s = query();
                snap = s;
                main.post(onChange);
            });
        } catch (RejectedExecutionException ignored) {
            // shut down
        }
    }

    private Snap query() {
        try (Cursor c = ctx.getContentResolver().query(URI, null, null, null, null)) {
            if (c == null || !c.moveToFirst()) return null;
            String kicker = str(c, "kicker");
            String headline = str(c, "headline");
            if (headline.isEmpty()) return null;
            return new Snap(kicker, headline, str(c, "detail"),
                    (int) lng(c, "colour"), lng(c, "alert") != 0, lng(c, "since_ms"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0 || c.isNull(i)) return "";
        String v = c.getString(i);
        return v == null ? "" : v;
    }

    private static long lng(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }

    /** "23 мин", "1 ч 05 мин": how long the alert has been on. */
    static String elapsed(long sinceMs, long now) {
        if (sinceMs <= 0 || now < sinceMs) return "";
        long min = (now - sinceMs) / 60_000L;
        if (min < 60) return min + " мин";
        return (min / 60) + " ч " + String.format(java.util.Locale.ROOT, "%02d", min % 60) + " мин";
    }
}
