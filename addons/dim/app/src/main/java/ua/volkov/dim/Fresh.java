package ua.volkov.dim;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fresh films and shows from TMDB, with the person's own free key: films released in the last half
 * year that people actually watch, and the shows trending this week. Each row carries its TMDB id, so a
 * press opens its card in Lampa straight away. [{tmdb, media, title, poster, year, src:"fresh", label}]
 */
final class Fresh {
    private static final String API = "https://api.themoviedb.org/3/";
    private static final String IMG = "https://image.tmdb.org/t/p/w342";

    private Fresh() {}

    static JSONArray load(Context ctx, Set<String> skipTitles) throws Exception {
        String key = Store.str(ctx, Store.TMDB_KEY);
        JSONArray out = new JSONArray();
        if (key.isEmpty()) return out;
        boolean v3 = key.matches("[0-9a-fA-F]{32}");
        Map<String, String> h = new HashMap<>();
        if (!v3) h.put("Authorization", "Bearer " + key);
        String since = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                .format(new Date(System.currentTimeMillis() - 180L * 24 * 3600_000L));
        String auth = v3 ? "&api_key=" + key : "";
        JSONArray movies = results(API + "discover/movie?language=ru-RU&sort_by=popularity.desc&include_adult=false"
                + "&vote_count.gte=80&primary_release_date.gte=" + since + auth, h);
        JSONArray shows = results(API + "trending/tv/week?language=ru-RU" + auth, h);
        Set<Long> seen = new HashSet<>();
        // two films, then a show, so both kinds are in the row
        int m = 0, s = 0;
        while (out.length() < 24 && (m < movies.length() || s < shows.length())) {
            for (int k = 0; k < 2 && m < movies.length(); k++) add(out, movies.optJSONObject(m++), "movie", seen, skipTitles);
            if (s < shows.length()) add(out, shows.optJSONObject(s++), "tv", seen, skipTitles);
        }
        return out;
    }

    private static JSONArray results(String url, Map<String, String> h) throws Exception {
        Http.Response r = Http.get(url, h, 12_000);
        if (r.code != 200) throw new Exception("TMDB " + r.code);
        JSONArray a = new JSONObject(r.body).optJSONArray("results");
        return a == null ? new JSONArray() : a;
    }

    private static void add(JSONArray out, JSONObject o, String media, Set<Long> seen, Set<String> skip) throws Exception {
        if (o == null || o.optBoolean("adult")) return;
        long id = o.optLong("id");
        String poster = o.optString("poster_path", "");
        if (id == 0 || poster.isEmpty() || "null".equals(poster) || !seen.add(id)) return;
        String title = o.optString("movie".equals(media) ? "title" : "name");
        String orig = o.optString("movie".equals(media) ? "original_title" : "original_name");
        if (skip.contains(norm(title)) || skip.contains(norm(orig))) return;   // already in Must
        String date = o.optString("movie".equals(media) ? "release_date" : "first_air_date");
        JSONObject row = new JSONObject();
        row.put("tmdb", String.valueOf(id));
        row.put("media", media);
        row.put("title", title);
        row.put("poster", IMG + poster);
        String back = o.optString("backdrop_path", "");
        if (!back.isEmpty() && !"null".equals(back)) row.put("backdrop", "https://image.tmdb.org/t/p/w780" + back);
        row.put("year", date.length() >= 4 ? date.substring(0, 4) : "");
        row.put("src", "fresh");
        row.put("label", "movie".equals(media) ? "Новинка" : "Сериал");
        out.put(row);
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The For you row: Lampa's Continue watching first, then the Want list, Must's picks, fresh titles
     * and Lampa's recommendations in turn.
     */
    static JSONArray mix(JSONArray lampa, JSONArray want, JSONArray must, JSONArray fresh) {
        JSONArray out = new JSONArray();
        java.util.List<Object> recs = new java.util.ArrayList<>();
        for (int i = 0; i < lampa.length(); i++) {
            JSONObject o = lampa.optJSONObject(i);
            if (o == null) continue;
            if ("Продолжить".equals(o.optString("label")) && out.length() < 4) out.put(o);   // continue watching first
            else recs.add(o);
        }
        // then one from the Want list, two Must picks, a fresh title, one of Lampa's own, and again
        int w = 0, m = 0, f = 0, r = 0;
        while (out.length() < 44 && (w < want.length() || m < must.length() || f < fresh.length() || r < recs.size())) {
            if (w < want.length()) out.put(want.opt(w++));
            for (int k = 0; k < 2 && m < must.length(); k++) out.put(must.opt(m++));
            if (f < fresh.length()) out.put(fresh.opt(f++));
            if (r < recs.size()) out.put(recs.get(r++));
        }
        return out;
    }
}
