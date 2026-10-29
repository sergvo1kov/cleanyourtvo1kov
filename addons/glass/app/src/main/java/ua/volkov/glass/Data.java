package ua.volkov.glass;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Glass's own data: the film row, alerts, weather, internet, Smart Life, TorrServe. It is fetched and
 * kept here, with Glass's own keys (KeysPanel), so Glass works with Flick TV removed. All calls block:
 * call them off the main thread.
 */
final class Data {
    static final int GREEN = 0xFF32D74B, RED = 0xFFFF453A, ORANGE = 0xFFFF9F0A, GREY = 0xFF8E8E93;

    private static volatile JSONArray lampa = new JSONArray();
    private static volatile long lampaAt;

    private Data() {}

    static JSONArray feed(Context ctx, int seed) {
        long now = System.currentTimeMillis();
        if (now - lampaAt > 120_000L) {
            lampaAt = now;
            lampa = LampaCards.read(ctx);
        }
        Feed.refreshMust(ctx, false);
        Feed.refreshFresh(ctx, false);
        try {
            return Feed.build(ctx, lampa, seed);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** {overview, vote, genres, year, backdrop, tmdb, media} from TMDB, or empty. */
    static JSONObject details(Context ctx, JSONObject film) {
        try {
            JSONObject d = LampaLink.details(ctx, film);
            return d == null ? new JSONObject() : d;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** {tmdb, media} or empty. */
    static JSONObject find(Context ctx, JSONObject film) {
        try {
            String[] f = film.optString("tmdb").isEmpty()
                    ? LampaLink.find(ctx, film.optString("title"), film.optString("year"), film.optString("type"))
                    : new String[]{film.optString("tmdb"), film.optString("media")};
            return f == null ? new JSONObject() : new JSONObject().put("tmdb", f[0]).put("media", f[1]);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static boolean toggle(Context ctx, String id) {
        try {
            JSONArray a = new JSONArray(Store.prefs(ctx).getString(Store.TUYA_DEVICES, "[]"));
            JSONObject d = null;
            for (int i = 0; i < a.length(); i++) if (id != null && id.equals(a.getJSONObject(i).optString("id"))) d = a.getJSONObject(i);
            if (d == null || d.optString("switch_code").isEmpty()) return false;
            Tuya.command(Store.str(ctx, Store.TUYA_ID), Store.str(ctx, Store.TUYA_SECRET), id, d.optString("switch_code"),
                    !d.optBoolean("switch_on"));
            Store.prefs(ctx).edit().putLong(Store.TUYA_AT, 0).apply();
            Refresher.kick(ctx, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void refresh(Context ctx) {
        Refresher.kick(ctx, true);
    }

    /** Standby through Glass's own Home service, or through Flick TV's when that one is running. */
    static boolean sleep(Context ctx) {
        return HomeRedirectService.sleep() || Flick.sleep(ctx);
    }

    static JSONObject status(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            Refresher.kick(ctx, false);
            SharedPreferences p = Store.prefs(ctx);
            int state = p.getInt(Store.ALERT_STATE, Alerts.UNKNOWN);
            String headline;
            int colour;
            switch (state) {
                case Alerts.ALERT:
                    headline = "Воздушная тревога";
                    colour = RED;
                    break;
                case Alerts.PARTIAL:
                    headline = "Тревога в части района";
                    colour = ORANGE;
                    break;
                case Alerts.CALM:
                    headline = "Тревоги нет";
                    colour = GREEN;
                    break;
                default:
                    headline = "Нет данных о тревогах";
                    colour = GREY;
            }
            StringBuilder d = new StringBuilder();
            int oblasts = p.getInt(Store.ALERT_OBLASTS, 0);
            if (state != Alerts.UNKNOWN) d.append(oblasts == 0 ? "По Украине тихо" : "Тревога в " + Alerts.oblastsWord(oblasts));
            if (p.getLong(Store.NET_AT, 0) > 0) {
                if (d.length() > 0) d.append("   ·   ");
                d.append(p.getBoolean(Store.NET_OK, false) ? "Интернет ✓" : "Интернета нет");
            }
            if (Store.str(ctx, Store.ALERTS_TOKEN).isEmpty() && state == Alerts.UNKNOWN) d.setLength(0);
            o.put("alert", new JSONObject()
                    .put("state", state)
                    .put("scope", Alerts.label(p.getString(Store.ALERT_SCOPE, Alerts.RAION)))
                    .put("since", p.getLong(Store.ALERT_SINCE, 0))
                    .put("list", p.getString(Store.ALERT_LIST, ""))
                    .put("headline", headline)
                    .put("detail", d.toString())
                    .put("colour", colour));
            o.put("weather", new JSONObject()
                    .put("at", p.getLong(Store.W_AT, 0))
                    .put("temp", p.getInt(Store.W_TEMP, 0)).put("feels", p.getInt(Store.W_FEELS, 0))
                    .put("code", p.getInt(Store.W_CODE, 0)).put("day", p.getBoolean(Store.W_DAY, true))
                    .put("wind", p.getInt(Store.W_WIND, 0)).put("hum", p.getInt(Store.W_HUM, 0))
                    .put("max", p.getInt(Store.W_MAX, 0)).put("min", p.getInt(Store.W_MIN, 0))
                    .put("words", Weather.words(p.getInt(Store.W_CODE, 0))));
            o.put("net", new JSONObject()
                    .put("at", p.getLong(Store.NET_AT, 0)).put("ok", p.getBoolean(Store.NET_OK, false)));
            o.put("tuya", new JSONArray(p.getString(Store.TUYA_DEVICES, "[]")));
            String torr = TorrServe.version();
            o.put("torr", torr == null ? "" : torr);
        } catch (Exception ignored) {
            // whatever was filled in stays
        }
        return o;
    }

    /** The keys this app uses, as entered in KeysPanel. */
    static final String[] KEYS = {Store.ALERTS_TOKEN, Store.TUYA_ID, Store.TUYA_SECRET, Store.TMDB_KEY, Store.MUST_NICK,
            Store.TG_CHANNELS};

    /** Copies Flick TV's keys into Glass, once, when asked. Answers how many were copied, -1 if Flick TV did not answer. */
    static int importFromFlick(Context ctx) {
        JSONObject k = Flick.export(ctx);
        if (k == null) return -1;
        SharedPreferences.Editor e = Store.prefs(ctx).edit();
        int n = 0;
        for (String key : KEYS) {
            String v = k.optString(key, "").trim();
            if (!v.isEmpty()) {
                e.putString(key, v);
                n++;
            }
        }
        // stale results fetched without the keys go, so everything is fetched again with them
        e.putLong(Store.ALERT_AT, 0).putLong(Store.TUYA_AT, 0).putLong(Store.MUST_AT, 0).putLong(Store.FRESH_AT, 0).apply();
        Refresher.kick(ctx, true);
        return n;
    }
}
