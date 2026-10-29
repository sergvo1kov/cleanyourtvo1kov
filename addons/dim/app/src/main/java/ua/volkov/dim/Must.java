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
                row.put("type", o.optString("type"));
                out.put(row);
            }
        }
        return out;
    }

    /**
     * "Like what you watched": similar titles to the most recent watched ones, without anything already
     * watched or in Want. [{id, title, poster, year}], at most 24.
     */
    static JSONArray similar(Context ctx, String nick) throws Exception {
        StringBuilder dbg = new StringBuilder();
        Http.Response r = Http.get(BASE + "/api/users/uri/" + nick, null, 15_000);
        dbg.append("users/uri ").append(r.code).append('\n');
        JSONObject user = new JSONObject(r.body);
        JSONObject lists = user.optJSONObject("lists");
        java.util.Set<Long> skip = new java.util.HashSet<>();
        List<Long> watched = new ArrayList<>();
        if (lists != null) {
            for (String k : new String[]{"want", "watched", "shows"}) {
                JSONArray a = lists.optJSONArray(k);
                if (a == null) continue;
                for (int i = 0; i < a.length(); i++) {
                    long id = a.optLong(i);
                    skip.add(id);
                    if ("watched".equals(k)) watched.add(id);
                }
            }
        }
        // the lists are newest first: seed from the last few things watched
        List<List<JSONObject>> perSeed = new ArrayList<>();
        for (int i = 0; i < watched.size() && perSeed.size() < 6; i++) {
            try {
                Http.Response s = Http.get(BASE + "/api/products/" + watched.get(i) + "/similars", null, 15_000);
                dbg.append("similars ").append(watched.get(i)).append(' ').append(s.code).append('\n');
                if (s.code != 200) continue;
                JSONArray arr = new JSONArray(s.body);
                List<JSONObject> rows = new ArrayList<>();
                for (int j = 0; j < arr.length(); j++) rows.add(arr.getJSONObject(j));
                perSeed.add(rows);
            } catch (Exception e) {
                dbg.append("similars error ").append(e).append('\n');
            }
        }
        // interleave the seeds so one film does not fill the row
        JSONArray out = new JSONArray();
        java.util.Set<Long> seen = new java.util.HashSet<>(skip);
        for (int round = 0; out.length() < 24 && round < 30; round++) {
            boolean any = false;
            for (List<JSONObject> rows : perSeed) {
                if (round >= rows.size() || out.length() >= 24) continue;
                any = true;
                JSONObject o = rows.get(round);
                long id = o.optLong("id");
                if (!seen.add(id) || o.optString("poster_file_path", "").isEmpty() || o.isNull("poster_file_path")) continue;
                JSONObject row = new JSONObject();
                row.put("id", id);
                row.put("title", o.optString("title"));
                row.put("poster", poster(o.optString("poster_file_path")));
                String date = o.optString("release_date");
                row.put("year", date.length() >= 4 ? date.substring(0, 4) : "");
                row.put("type", o.optString("type"));
                out.put(row);
            }
            if (!any) break;
        }
        dbg.append("result ").append(out.length()).append('\n');
        dump(ctx, dbg);
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
            try (FileOutputStream f = new FileOutputStream(new File(dir, "must-debug.txt"), true)) {
                f.write(dbg.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // debugging aid only
        }
    }
}
