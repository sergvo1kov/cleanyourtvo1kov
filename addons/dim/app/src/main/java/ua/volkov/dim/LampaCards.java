package ua.volkov.dim;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The cards Lampa publishes to Android TV (Watch Next first, then its preview channels):
 * [{title, poster, intent, src:"lampa"}]. Also writes what it saw to lampa-debug.txt.
 */
final class LampaCards {
    static final String PKG = "top.rootu.lamps";

    private LampaCards() {}

    /**
     * Lampa builds its Android TV channels (recommendations, history) when a home screen sends it
     * INITIALIZE_PROGRAMS, as the stock launcher does after an install or update. Sent once per version.
     */
    static void initialize(Context ctx) {
        try {
            long updated = ctx.getPackageManager().getPackageInfo(PKG, 0).lastUpdateTime;
            android.content.SharedPreferences p = Store.prefs(ctx);
            if (p.getLong("lampa_init", 0) == updated) return;
            ctx.sendBroadcast(new android.content.Intent(android.media.tv.TvContract.ACTION_INITIALIZE_PROGRAMS).setPackage(PKG));
            p.edit().putLong("lampa_init", updated).apply();
        } catch (Exception ignored) {
            // Lampa not installed
        }
    }

    static JSONArray read(Context ctx) {
        JSONArray out = new JSONArray();
        StringBuilder dbg = new StringBuilder();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String table : new String[]{"watch_next_program", "preview_program"}) {
            boolean next = table.startsWith("watch");
            Uri uri = Uri.parse("content://android.media.tv/" + table);
            try (Cursor c = query(ctx, uri)) {
                if (c == null) {
                    dbg.append(table).append(": null\n");
                    continue;
                }
                dbg.append(table).append(": ").append(c.getCount()).append(" rows\n");
                int pkg = c.getColumnIndex("package_name");
                int title = c.getColumnIndex("title");
                int poster = c.getColumnIndex("poster_art_uri");
                int thumb = c.getColumnIndex("thumbnail_uri");
                int intent = c.getColumnIndex("intent_uri");
                int browsable = c.getColumnIndex("browsable");
                int taken = 0;
                while (c.moveToNext() && out.length() < 40) {
                    String p = pkg >= 0 ? c.getString(pkg) : "";
                    if (!PKG.equals(p) || title < 0) continue;
                    if (browsable >= 0 && c.getInt(browsable) == 0) continue;
                    String t = c.getString(title);
                    if (t == null || t.isEmpty() || !seen.add(t.trim().toLowerCase(java.util.Locale.ROOT))) continue;
                    if (next && taken >= 6) continue;   // a few to continue, the rest is Lampa's recommendations
                    taken++;
                    JSONObject o = new JSONObject();
                    o.put("title", t);
                    String art = poster >= 0 ? c.getString(poster) : null;
                    String wide = thumb >= 0 ? c.getString(thumb) : null;
                    if (art == null || art.isEmpty()) art = wide;
                    o.put("poster", direct(art, "w342"));
                    if (wide != null && !wide.isEmpty()) o.put("backdrop", direct(wide, "w780"));
                    o.put("intent", intent >= 0 && c.getString(intent) != null ? c.getString(intent) : "");
                    o.put("src", "lampa");
                    o.put("label", next ? "Продолжить" : "Lampa");
                    out.put(o);
                    dbg.append("  ").append(next ? "next " : "rec  ").append(t).append(" | ").append(art).append('\n');
                }
            } catch (Exception e) {
                dbg.append(table).append(": ").append(e).append('\n');
            }
        }
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir != null) {
                try (FileOutputStream f = new FileOutputStream(new File(dir, "lampa-debug.txt"))) {
                    f.write(dbg.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Exception ignored) {
            // debugging aid only
        }
        return out;
    }

    private static final String[] COLUMNS = {"package_name", "title", "poster_art_uri", "thumbnail_uri", "intent_uri", "browsable"};

    /** Only the six columns the row uses (the table has 50 and hundreds of rows from other apps). */
    private static Cursor query(Context ctx, Uri uri) {
        try {
            return ctx.getContentResolver().query(uri, COLUMNS, null, null, null);
        } catch (RuntimeException e) {
            return ctx.getContentResolver().query(uri, null, null, null, null);
        }
    }

    /**
     * Lampa takes pictures through image proxies; a TMDB path goes straight to TMDB's own server, over
     * HTTPS (this app allows no cleartext traffic except to the TV itself).
     */
    static String direct(String url, String size) {
        if (url == null) return "";
        int i = url.indexOf("/t/p/");
        if (i > 0 && url.startsWith("http")) {
            // /t/p/<size>/<file>: ask TMDB for the size the row needs, not the original
            String rest = url.substring(i + 5);
            int slash = rest.indexOf('/');
            return "https://image.tmdb.org/t/p/" + size + (slash >= 0 ? rest.substring(slash) : "/" + rest);
        }
        if (url.startsWith("http://")) return "https://" + url.substring(7);
        return url;
    }

    /** Lampa first, then Must, one by one, so both sources share the row. */
    static JSONArray mix(JSONArray lampa, JSONArray must) {
        JSONArray out = new JSONArray();
        int n = Math.max(lampa.length(), must.length());
        for (int i = 0; i < n && out.length() < 30; i++) {
            if (i < lampa.length()) out.put(lampa.opt(i));
            if (i < must.length()) out.put(must.opt(i));
        }
        return out;
    }
}
