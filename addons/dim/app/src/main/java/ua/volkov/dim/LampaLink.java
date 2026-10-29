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
 * Opens one exact film in Lampa. Must gives titles and years but no TMDB id, so the title goes through
 * Lampa's own exported search provider (the one Google TV's global search uses), which answers with TMDB
 * ids; the best match then opens as a themoviedb.org link, which Lampa turns into that film's card.
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
    static String[] find(Context ctx, String title, String year) {
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
