package ua.volkov.dim;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "Want" list of a public Must profile (mustapp.com/@nick), read only. Must has no official API;
 * this uses the same public endpoints its website uses. Raw answers go to must-debug.txt in the app's
 * external files folder, so a format change can be seen over ADB.
 */
final class Must {
    static final String BASE = "https://mustapp.com";
    private static final Pattern PRODUCT_LINK = Pattern.compile("/p/(\\d+)");

    private Must() {}

    /** [{id, title, poster, year}] newest first, at most 20. */
    static JSONArray want(Context ctx, String nick) throws Exception {
        StringBuilder dbg = new StringBuilder();
        List<Long> ids = new ArrayList<>();
        // 1. the profile as JSON
        try {
            Http.Response r = Http.get(BASE + "/api/users/uri/" + nick, null, 15_000);
            dbg.append("users/uri ").append(r.code).append('\n').append(cut(r.body)).append("\n\n");
            if (r.code == 200) ids.addAll(wantIds(new JSONObject(r.body)));
        } catch (Exception e) {
            dbg.append("users/uri error ").append(e).append("\n\n");
        }
        // 2. fallback: the profile page's product links
        if (ids.isEmpty()) {
            Http.Response r = Http.get(BASE + "/@" + nick, null, 15_000);
            dbg.append("page ").append(r.code).append('\n').append(cut(r.body)).append("\n\n");
            LinkedHashSet<Long> seen = new LinkedHashSet<>();
            Matcher m = PRODUCT_LINK.matcher(r.body);
            while (m.find()) seen.add(Long.parseLong(m.group(1)));
            ids.addAll(seen);
        }
        if (ids.size() > 20) ids = ids.subList(0, 20);
        JSONArray out = new JSONArray();
        if (ids.isEmpty()) {
            dump(ctx, dbg);
            return out;
        }
        JSONArray idArr = new JSONArray();
        for (Long id : ids) idArr.put(id);
        Http.Response p = Http.post(BASE + "/api/products", null, new JSONObject().put("ids", idArr).toString(), 15_000);
        dbg.append("products ").append(p.code).append('\n').append(cut(p.body)).append('\n');
        dump(ctx, dbg);
        JSONArray arr = new JSONArray(p.body);
        for (Long id : ids) {   // keep the list's order
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.optLong("id") != id) continue;
                JSONObject row = new JSONObject();
                row.put("id", id);
                row.put("title", o.optString("title"));
                row.put("poster", poster(o.optString("poster_file_path")));
                String date = o.optString("release_date");
                row.put("year", date.length() >= 4 ? date.substring(0, 4) : "");
                out.put(row);
            }
        }
        return out;
    }

    /** Looks for the "want" id list wherever the profile JSON keeps it. */
    static List<Long> wantIds(JSONObject user) {
        List<Long> ids = new ArrayList<>();
        JSONArray a = user.optJSONArray("want");
        if (a == null && user.optJSONObject("lists") != null) a = user.optJSONObject("lists").optJSONArray("want");
        if (a == null && user.optJSONObject("products") != null) a = user.optJSONObject("products").optJSONArray("want");
        if (a == null) return ids;
        for (int i = 0; i < a.length(); i++) {
            Object v = a.opt(i);
            if (v instanceof Number) ids.add(((Number) v).longValue());
            else if (v instanceof JSONObject) {
                JSONObject o = (JSONObject) v;
                long id = o.optLong("product_id", o.optLong("id", 0));
                if (id > 0) ids.add(id);
            }
        }
        return ids;
    }

    static String poster(String path) {
        if (path == null || path.isEmpty() || "null".equals(path)) return "";
        if (path.startsWith("http")) return path;
        return "https://media.mustapp.me/must/posters/w300" + (path.startsWith("/") ? path : "/" + path);
    }

    private static String cut(String s) {
        return s == null ? "" : s.length() > 4000 ? s.substring(0, 4000) + "…" : s;
    }

    private static void dump(Context ctx, StringBuilder dbg) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            try (FileOutputStream f = new FileOutputStream(new File(dir, "must-debug.txt"))) {
                f.write(dbg.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // debugging aid only
        }
    }
}
