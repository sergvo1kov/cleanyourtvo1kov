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

    static JSONArray read(Context ctx) {
        JSONArray out = new JSONArray();
        StringBuilder dbg = new StringBuilder();
        for (String table : new String[]{"watch_next_program", "preview_program"}) {
            Uri uri = Uri.parse("content://android.media.tv/" + table);
            try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
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
                while (c.moveToNext() && out.length() < 24) {
                    String p = pkg >= 0 ? c.getString(pkg) : "";
                    dbg.append("  ").append(p).append(" | ").append(title >= 0 ? c.getString(title) : "").append('\n');
                    if (!PKG.equals(p) || title < 0) continue;
                    JSONObject o = new JSONObject();
                    o.put("title", c.getString(title));
                    String art = poster >= 0 ? c.getString(poster) : null;
                    if ((art == null || art.isEmpty()) && thumb >= 0) art = c.getString(thumb);
                    o.put("poster", art == null ? "" : art);
                    o.put("intent", intent >= 0 && c.getString(intent) != null ? c.getString(intent) : "");
                    o.put("src", "lampa");
                    out.put(o);
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
