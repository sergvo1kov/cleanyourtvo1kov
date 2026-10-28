package ua.volkov.dim;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * One row for the launcher's panel under the clock (fork launcher, HomeStatus.java):
 * kicker, headline, detail, colour, alert, since_ms. Reading it refreshes stale data in the background
 * and notifies the URI when new data lands.
 */
public final class StatusProvider extends ContentProvider {
    static final Uri URI = Uri.parse("content://ua.volkov.dim.status/now");
    private static final String[] COLUMNS = {"kicker", "headline", "detail", "colour", "alert", "since_ms"};

    static final int GREEN = 0xFF32D74B, RED = 0xFFFF453A, ORANGE = 0xFFFF9F0A, GREY = 0xFF8E8E93;

    private final java.util.List<android.media.session.MediaController> watched = new java.util.ArrayList<>();
    private final android.media.session.MediaController.Callback trackChanged =
            new android.media.session.MediaController.Callback() {
                @Override
                public void onMetadataChanged(android.media.MediaMetadata metadata) {
                    if (getContext() != null) notifyChanged(getContext());
                }

                @Override
                public void onPlaybackStateChanged(android.media.session.PlaybackState state) {
                    if (getContext() != null) notifyChanged(getContext());
                }
            };

    @Override
    public boolean onCreate() {
        // Home's "now playing" line follows the music without polling: watch the media sessions.
        Context ctx = getContext();
        if (ctx == null) return true;
        try {
            android.media.session.MediaSessionManager m =
                    (android.media.session.MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            android.media.session.MediaSessionManager.OnActiveSessionsChangedListener l = controllers -> {
                watch(controllers);
                notifyChanged(ctx);
            };
            m.addOnActiveSessionsChangedListener(l, NowPlaying.listener(ctx),
                    new android.os.Handler(android.os.Looper.getMainLooper()));
            watch(m.getActiveSessions(NowPlaying.listener(ctx)));
        } catch (RuntimeException e) {
            // no notification access yet: Home simply shows no music line
        }
        return true;
    }

    private void watch(java.util.List<android.media.session.MediaController> controllers) {
        for (android.media.session.MediaController c : watched) c.unregisterCallback(trackChanged);
        watched.clear();
        if (controllers == null) return;
        for (android.media.session.MediaController c : controllers) {
            c.registerCallback(trackChanged, new android.os.Handler(android.os.Looper.getMainLooper()));
            watched.add(c);
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        Context ctx = getContext();
        if (ctx == null) return null;
        Refresher.kick(ctx, false);
        Line l = line(ctx);
        MatrixCursor c = new MatrixCursor(COLUMNS, 1);
        c.addRow(new Object[]{l.kicker, l.headline, l.detail, l.colour, l.alert ? 1 : 0, l.sinceMs});
        return c;
    }

    static void notifyChanged(Context ctx) {
        ctx.getContentResolver().notifyChange(URI, null);
    }

    static final class Line {
        String kicker, headline, detail;
        int colour;
        boolean alert;
        long sinceMs;
    }

    /** The same words the add-on's own screen uses, so Home and the app agree. */
    static Line line(Context ctx) {
        SharedPreferences p = Store.prefs(ctx);
        Line l = new Line();
        int state = p.getInt(Store.ALERT_STATE, Alerts.UNKNOWN);
        String scope = Alerts.label(p.getString(Store.ALERT_SCOPE, Alerts.RAION));
        String weather = p.getLong(Store.W_AT, 0) == 0 ? ""
                : "  ·  " + Weather.icon(p.getInt(Store.W_CODE, 0), p.getBoolean(Store.W_DAY, true))
                + " " + Weather.deg(p.getInt(Store.W_TEMP, 0));
        l.kicker = scope + weather;
        switch (state) {
            case Alerts.ALERT:
                l.headline = "Воздушная тревога";
                l.colour = RED;
                l.alert = true;
                l.sinceMs = p.getLong(Store.ALERT_SINCE, 0);
                break;
            case Alerts.PARTIAL:
                l.headline = "Тревога в части района";
                l.colour = ORANGE;
                break;
            case Alerts.CALM:
                l.headline = "Тревоги нет";
                l.colour = GREEN;
                break;
            default:
                l.headline = "Нет данных о тревогах";
                l.colour = GREY;
        }
        StringBuilder d = new StringBuilder();
        int oblasts = p.getInt(Store.ALERT_OBLASTS, 0);
        if (state != Alerts.UNKNOWN) d.append(oblasts == 0 ? "По Украине тихо" : "Тревога в " + Alerts.oblastsWord(oblasts));
        if (p.getLong(Store.NET_AT, 0) > 0) {
            if (d.length() > 0) d.append("   ·   ");
            d.append(p.getBoolean(Store.NET_OK, false) ? "Интернет ✓" : "Интернета нет");
            if (p.getBoolean(Store.NET_OK, false) && p.getLong(Store.SPEED_AT, 0) > 0) {
                d.append(" ").append(Math.round(p.getFloat(Store.SPEED_DOWN, 0))).append(" Мбит/с");
            }
        }
        // Music playing (and no alert): the detail line shows the track.
        if (state != Alerts.ALERT) {
            NowPlaying.Track t = NowPlaying.current(ctx);
            if (t != null && t.playing) {
                d.setLength(0);
                d.append("♪  ").append(t.title);
                if (!t.artist.isEmpty()) d.append("  —  ").append(t.artist);
            }
        }
        // A fresh urgent post about Odesa takes the detail line: that is what matters right now.
        try {
            org.json.JSONArray feed = new org.json.JSONArray(p.getString(Store.TG_FEED, "[]"));
            for (int i = 0; i < feed.length(); i++) {
                org.json.JSONObject o = feed.getJSONObject(i);
                long age = System.currentTimeMillis() - o.optLong("ms");
                if (o.optBoolean("odesa") && age >= 0 && age < 20 * 60_000L) {
                    String first = o.optString("text").split("\n")[0].trim();
                    if (first.length() > 80) first = first.substring(0, 79) + "…";
                    d.setLength(0);
                    d.append("⚠ ").append(first);
                    break;
                }
            }
        } catch (Exception ignored) {
            // no feed yet
        }
        l.detail = d.toString();
        return l;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.ua.volkov.dim.status";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
