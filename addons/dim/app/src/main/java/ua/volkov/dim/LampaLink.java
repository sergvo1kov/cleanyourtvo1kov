package ua.volkov.dim;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Opens one exact film in Lampa. Must gives titles and years but no TMDB id, so the title is looked up
 * on TMDB (with the person's free key) or, failing that, through Lampa's exported search provider; the
 * match then opens as a themoviedb.org link, which Lampa turns into that film's card.
 * Found ids are cached, so the second press is instant.
 */
final class LampaLink {
    static final String PKG = "top.rootu.lamps";
    private static final String CACHE = "lampa_ids";

    private LampaLink() {}

    static boolean installed(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** {tmdbId, "movie"|"tv"}, or null. Blocking: call it off the main thread. */
    static String[] find(Context ctx, String title, String year, String type) {
        if (title == null || title.trim().isEmpty()) return null;
        String key = title.trim().toLowerCase(Locale.ROOT) + "|" + (year == null ? "" : year);
        try {
            JSONObject cache = new JSONObject(Store.prefs(ctx).getString(CACHE, "{}"));
            String hit = cache.optString(key, "");
            if (hit.contains("|")) return hit.split("\\|", 2);
        } catch (Exception ignored) {
            // rebuild the cache below
        }
        // Lampa's search runs a few spoken commands on its own (one of them uninstalls Lampa):
        // never send it a title that mentions Lampa.
        String low = title.toLowerCase(Locale.ROOT);
        if (low.contains("lampa") || low.contains("ламп")) return null;
        String[] best = tmdb(ctx, title.trim(), year, type);
        if (best == null) best = provider(ctx, title, year);
        if (best != null) {
            try {
                JSONObject cache = new JSONObject(Store.prefs(ctx).getString(CACHE, "{}"));
                if (cache.length() > 300) cache = new JSONObject();
                cache.put(key, best[0] + "|" + best[1]);
                Store.prefs(ctx).edit().putString(CACHE, cache.toString()).apply();
            } catch (Exception ignored) {
                // cache only
            }
        }
        return best;
    }

    /** TMDB's own search, with the person's free API key (v3 key or v4 read token). */
    private static String[] tmdb(Context ctx, String title, String year, String type) {
        String key = Store.str(ctx, Store.TMDB_KEY);
        if (key.isEmpty()) return null;
        boolean v3 = key.matches("[0-9a-fA-F]{32}");
        java.util.Map<String, String> h = new java.util.HashMap<>();
        if (!v3) h.put("Authorization", "Bearer " + key);
        boolean show = type != null && (type.startsWith("show") || type.equals("tv"));
        String[] order = show ? new String[]{"tv", "movie"} : new String[]{"movie", "tv"};
        for (String media : order) {
            for (int pass = 0; pass < 2; pass++) {
                boolean withYear = pass == 0 && year != null && !year.isEmpty();
                if (pass == 1 && !(year != null && !year.isEmpty())) break;
                try {
                    String url = "https://api.themoviedb.org/3/search/" + media + "?language=ru-RU&query="
                            + Uri.encode(title)
                            + (withYear ? ("movie".equals(media) ? "&year=" : "&first_air_date_year=") + year : "")
                            + (v3 ? "&api_key=" + key : "");
                    Http.Response r = Http.get(url, h, 10_000);
                    if (r.code != 200) return null;   // a wrong key: no point trying further
                    org.json.JSONArray res = new JSONObject(r.body).optJSONArray("results");
                    if (res != null && res.length() > 0) return new String[]{String.valueOf(res.getJSONObject(0).optLong("id")), media};
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Lampa's exported search provider (Google TV's global search); some Lampa builds answer it. */
    private static String[] provider(Context ctx, String title, String year) {
        String auth = authority(ctx);
        if (auth == null) return null;
        Uri uri = Uri.parse("content://" + auth + "/search/search_suggest_query/" + Uri.encode(title.trim()));
        String[] best = null;
        int bestScore = -1;
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, new String[]{title.trim()}, null)) {
            if (c == null) return null;
            int cId = c.getColumnIndex("suggest_intent_data_id");
            int cMedia = c.getColumnIndex("suggest_intent_extra_data");
            int cName = c.getColumnIndex("suggest_text_1");
            int cYear = c.getColumnIndex("suggest_production_year");
            for (int row = 0; c.moveToNext() && row < 40; row++) {
                String id = cId < 0 ? "" : c.getString(cId);
                String media = cMedia < 0 ? "" : c.getString(cMedia);
                if (id == null || !id.matches("\\d+") || !("movie".equals(media) || "tv".equals(media))) continue;
                String name = cName < 0 ? "" : String.valueOf(c.getString(cName));
                String y = cYear < 0 ? "" : String.valueOf(c.getString(cYear));
                int score = 0;
                if (year != null && !year.isEmpty() && year.equals(y)) score += 2;
                if (name.trim().equalsIgnoreCase(title.trim())) score += 3;
                if (row == 0) score += 1;   // Lampa already sorts by closeness
                if (score > bestScore) {
                    bestScore = score;
                    best = new String[]{id, media};
                }
            }
        } catch (Exception e) {
            return null;
        }
        return best;
    }

    /**
     * A wide picture for the focus backdrop: the row's own one, or the film's TMDB backdrop found by its
     * id (with the TMDB key). Cached. Blocking. Null when there is none.
     */
    static String backdrop(Context ctx, JSONObject film) {
        String own = film.optString("backdrop");
        if (!own.isEmpty()) return own;
        if (Store.str(ctx, Store.TMDB_KEY).isEmpty() || "lampa".equals(film.optString("src"))) return null;   // poster instead
        String id = film.optString("tmdb"), media = film.optString("media");
        if (id.isEmpty()) {
            String[] f = find(ctx, film.optString("title"), film.optString("year"), film.optString("type"));
            if (f == null) return null;
            id = f[0];
            media = f[1];
        }
        String ckey = media + "/" + id;
        try {
            JSONObject cache = new JSONObject(Store.prefs(ctx).getString("backdrops", "{}"));
            if (cache.has(ckey)) return cache.optString(ckey);
            String key = Store.str(ctx, Store.TMDB_KEY);
            boolean v3 = key.matches("[0-9a-fA-F]{32}");
            java.util.Map<String, String> h = new java.util.HashMap<>();
            if (!v3) h.put("Authorization", "Bearer " + key);
            Http.Response r = Http.get("https://api.themoviedb.org/3/" + media + "/" + id + (v3 ? "?api_key=" + key : ""), h, 10_000);
            if (r.code != 200) return null;
            String path = new JSONObject(r.body).optString("backdrop_path", "");
            String url = path.isEmpty() || "null".equals(path) ? "" : "https://image.tmdb.org/t/p/w780" + path;
            if (cache.length() > 300) cache = new JSONObject();
            cache.put(ckey, url);
            Store.prefs(ctx).edit().putString("backdrops", cache.toString()).apply();
            return url;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * What the Glass stage shows about a film: {tmdb, media, overview, vote, genres, year, backdrop}, from
     * TMDB in Russian with the person's key. Cached. Blocking. Null without a key or a match.
     */
    static JSONObject details(Context ctx, JSONObject film) {
        String key = Store.str(ctx, Store.TMDB_KEY);
        if (key.isEmpty()) return null;
        String id = film.optString("tmdb"), media = film.optString("media");
        if (id.isEmpty()) {
            String[] f = find(ctx, film.optString("title"), film.optString("year"), film.optString("type"));
            if (f == null) return null;
            id = f[0];
            media = f[1];
        }
        String ckey = media + "/" + id;
        try {
            JSONObject cache = new JSONObject(Store.prefs(ctx).getString("details", "{}"));
            JSONObject hit = cache.optJSONObject(ckey);
            if (hit != null) return hit;
            boolean v3 = key.matches("[0-9a-fA-F]{32}");
            java.util.Map<String, String> h = new java.util.HashMap<>();
            if (!v3) h.put("Authorization", "Bearer " + key);
            Http.Response r = Http.get("https://api.themoviedb.org/3/" + media + "/" + id + "?language=ru-RU"
                    + (v3 ? "&api_key=" + key : ""), h, 10_000);
            if (r.code != 200) return null;
            JSONObject o = new JSONObject(r.body);
            StringBuilder g = new StringBuilder();
            org.json.JSONArray genres = o.optJSONArray("genres");
            for (int i = 0; genres != null && i < genres.length() && i < 2; i++) {
                if (g.length() > 0) g.append(", ");
                String name = genres.getJSONObject(i).optString("name");
                g.append(name.isEmpty() ? "" : Character.toUpperCase(name.charAt(0)) + name.substring(1));
            }
            String date = o.optString("movie".equals(media) ? "release_date" : "first_air_date");
            String back = o.optString("backdrop_path", "");
            JSONObject d = new JSONObject()
                    .put("tmdb", id).put("media", media)
                    .put("overview", o.optString("overview"))
                    .put("vote", Math.round(o.optDouble("vote_average", 0) * 10) / 10.0)
                    .put("genres", g.toString())
                    .put("year", date.length() >= 4 ? date.substring(0, 4) : "")
                    .put("backdrop", back.isEmpty() || "null".equals(back) ? "" : "https://image.tmdb.org/t/p/w1280" + back);
            if (cache.length() > 200) cache = new JSONObject();
            cache.put(ckey, d);
            Store.prefs(ctx).edit().putString("details", cache.toString()).apply();
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    /** The intent that opens that film's card in Lampa. */
    static Intent cardIntent(String[] found) {
        Intent i = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.themoviedb.org/" + found[1] + "/" + found[0]));
        i.setPackage(PKG);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static String authority(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(PKG, PackageManager.GET_PROVIDERS);
            if (pi.providers != null) {
                for (ProviderInfo p : pi.providers) {
                    if (p.authority == null) continue;
                    for (String a : p.authority.split(";")) if (a.endsWith(".atvsearch")) return a;
                }
            }
        } catch (Exception ignored) {
            // not installed
        }
        return null;
    }
}
