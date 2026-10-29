package ua.volkov.dim;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The film row's sources and their refresh for Flick TV's Home: Must (similar + Want), fresh TMDB
 * titles, and the mix with Lampa's own cards.
 */
final class Feed {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile long mustTried, freshTried;

    private Feed() {}

    /** Fetches Must again when it is older than 30 minutes (or when forced). Runs in the background. */
    static void refreshMust(Context ctx, boolean force) {
        Context app = ctx.getApplicationContext();
        String nick = Store.str(app, Store.MUST_NICK);
        if (nick.isEmpty()) return;
        SharedPreferences p = Store.prefs(app);
        long now = System.currentTimeMillis();
        if (!p.contains(Store.MUST_WANT) || p.getLong(Store.MUST_AT, 0) == 0) force = true;
        if (!force && (now - p.getLong(Store.MUST_AT, 0) < 30 * 60_000L || now - mustTried < 5 * 60_000L)) return;
        if (now - mustTried < 15_000L) return;   // one fetch at a time
        mustTried = now;
        IO.execute(() -> {
            try {
                JSONArray list = Must.similar(app, nick);
                JSONArray want = new JSONArray();
                try {
                    want = Must.want(app, nick);
                    for (int i = 0; i < want.length(); i++) want.getJSONObject(i).put("label", "Хочу посмотреть");
                } catch (Exception ignored) {
                    // the recommendations still show
                }
                if (list.length() > 0 || want.length() > 0) {
                    p.edit().putString(Store.MUST_LIST, list.toString())
                            .putString(Store.MUST_WANT, want.toString()).putLong(Store.MUST_AT, now).apply();
                    for (JSONArray a : new JSONArray[]{want, list}) {
                        for (int i = 0; i < Math.min(12, a.length()); i++) Images.prefetch(a.optJSONObject(i).optString("poster"));
                    }
                }
            } catch (Exception ignored) {
                // must-debug.txt has the details
            }
        });
    }

    /** Fresh TMDB titles, every three hours, only with a TMDB key. Runs in the background. */
    static void refreshFresh(Context ctx, boolean force) {
        Context app = ctx.getApplicationContext();
        if (Store.str(app, Store.TMDB_KEY).isEmpty()) return;
        SharedPreferences p = Store.prefs(app);
        long now = System.currentTimeMillis();
        if (p.getLong(Store.FRESH_AT, 0) == 0) force = true;
        if (!force && (now - p.getLong(Store.FRESH_AT, 0) < 3 * 3600_000L || now - freshTried < 10 * 60_000L)) return;
        if (now - freshTried < 15_000L) return;
        freshTried = now;
        IO.execute(() -> {
            try {
                Set<String> skip = new HashSet<>();
                for (String k : new String[]{Store.MUST_LIST, Store.MUST_WANT}) {
                    JSONArray must = new JSONArray(p.getString(k, "[]"));
                    for (int i = 0; i < must.length(); i++) skip.add(Fresh.norm(must.optJSONObject(i).optString("title")));
                }
                JSONArray list = Fresh.load(app, skip);
                if (list.length() > 0) p.edit().putString(Store.FRESH_LIST, list.toString()).putLong(Store.FRESH_AT, now).apply();
            } catch (Exception ignored) {
                // a wrong key or no network: the row keeps Must only
            }
        });
    }

    /** The row: Lampa's Continue watching, then Want, Must picks, fresh titles and Lampa's recommendations. */
    static JSONArray build(Context ctx, JSONArray lampa, int seed) {
        SharedPreferences p = Store.prefs(ctx);
        JSONArray must = parse(p.getString(Store.MUST_LIST, "[]"));
        JSONArray want = parse(p.getString(Store.MUST_WANT, "[]"));
        JSONArray fresh = Store.str(ctx, Store.TMDB_KEY).isEmpty() ? new JSONArray() : parse(p.getString(Store.FRESH_LIST, "[]"));
        return Fresh.mix(lampa, rotate(want, seed), rotate(must, seed * 3), rotate(fresh, seed * 2));
    }

    private static JSONArray parse(String s) {
        try {
            return new JSONArray(s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static JSONArray rotate(JSONArray a, int by) {
        int n = a.length();
        if (n < 2 || by % n == 0) return a;
        JSONArray out = new JSONArray();
        for (int i = 0; i < n; i++) out.put(a.opt((i + by) % n));
        return out;
    }
}
