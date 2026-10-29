package ua.volkov.dim;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Flick TV's data for Flick TV Glass, the second home screen: the film row, alerts, weather, internet,
 * Smart Life, TorrServe, and a few actions. Only apps signed with the same key may call it (permission
 * ua.volkov.dim.permission.DATA, protectionLevel signature), so keys entered here are never shared.
 * Every method answers a Bundle with "json".
 */
public final class DataProvider extends ContentProvider {
    static final String GLASS = "ua.volkov.glass";
    static final String GLASS_HOME = GLASS + ".MainActivity";

    private static volatile JSONArray lampa = new JSONArray();
    private static volatile long lampaAt;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        Bundle out = new Bundle();
        if (ctx == null) return out;
        try {
            switch (method) {
                case "status": out.putString("json", status(ctx).toString()); break;
                case "feed": out.putString("json", feed(ctx, arg).toString()); break;
                case "details": {
                    JSONObject film = new JSONObject(extras == null ? "{}" : extras.getString("film", "{}"));
                    JSONObject d = LampaLink.details(ctx, film);
                    out.putString("json", d == null ? "{}" : d.toString());
                    break;
                }
                case "find": {
                    JSONObject film = new JSONObject(extras == null ? "{}" : extras.getString("film", "{}"));
                    String[] f = film.optString("tmdb").isEmpty()
                            ? LampaLink.find(ctx, film.optString("title"), film.optString("year"), film.optString("type"))
                            : new String[]{film.optString("tmdb"), film.optString("media")};
                    out.putString("json", f == null ? "{}" : new JSONObject().put("tmdb", f[0]).put("media", f[1]).toString());
                    break;
                }
                case "tuya": {
                    JSONObject d = findDevice(ctx, arg);
                    if (d == null || d.optString("switch_code").isEmpty()) throw new Exception("нет такого выключателя");
                    boolean on = !d.optBoolean("switch_on");
                    Tuya.command(Store.str(ctx, Store.TUYA_ID), Store.str(ctx, Store.TUYA_SECRET), arg, d.optString("switch_code"), on);
                    Store.prefs(ctx).edit().putLong(Store.TUYA_AT, 0).apply();
                    Refresher.kick(ctx, false);
                    out.putString("json", new JSONObject().put("on", on).toString());
                    break;
                }
                case "refresh":
                    Refresher.kick(ctx, true);
                    out.putString("json", "{}");
                    break;
                case "sleep":
                    out.putString("json", new JSONObject().put("ok", HomeRedirectService.sleep()).toString());
                    break;
                case "home":
                    // which home screen the Home button opens: "glass" or "flick"
                    Store.prefs(ctx).edit().putString(Store.HOME_TARGET, "glass".equals(arg) ? "glass" : "flick").apply();
                    out.putString("json", "{}");
                    break;
                default:
                    out.putString("json", "{}");
            }
        } catch (Exception e) {
            out.putString("error", String.valueOf(e.getMessage()));
        }
        return out;
    }

    private static JSONObject findDevice(Context ctx, String id) throws Exception {
        JSONArray a = new JSONArray(Store.prefs(ctx).getString(Store.TUYA_DEVICES, "[]"));
        for (int i = 0; i < a.length(); i++) if (id != null && id.equals(a.getJSONObject(i).optString("id"))) return a.getJSONObject(i);
        return null;
    }

    private static JSONArray feed(Context ctx, String seed) {
        long now = System.currentTimeMillis();
        if (now - lampaAt > 120_000L) {
            lampaAt = now;
            lampa = LampaCards.read(ctx);
        }
        Feed.refreshMust(ctx, false);
        Feed.refreshFresh(ctx, false);
        int s = 0;
        try {
            s = Integer.parseInt(seed);
        } catch (Exception ignored) {
            // no shuffle
        }
        return Feed.build(ctx, lampa, s);
    }

    private static JSONObject status(Context ctx) throws Exception {
        Refresher.kick(ctx, false);
        SharedPreferences p = Store.prefs(ctx);
        JSONObject o = new JSONObject();
        StatusProvider.Line l = StatusProvider.line(ctx);
        o.put("alert", new JSONObject()
                .put("state", p.getInt(Store.ALERT_STATE, Alerts.UNKNOWN))
                .put("scope", Alerts.label(p.getString(Store.ALERT_SCOPE, Alerts.RAION)))
                .put("since", p.getLong(Store.ALERT_SINCE, 0))
                .put("list", p.getString(Store.ALERT_LIST, ""))
                .put("headline", l.headline)
                .put("detail", l.detail)
                .put("colour", l.colour));
        o.put("weather", new JSONObject()
                .put("at", p.getLong(Store.W_AT, 0))
                .put("temp", p.getInt(Store.W_TEMP, 0)).put("feels", p.getInt(Store.W_FEELS, 0))
                .put("code", p.getInt(Store.W_CODE, 0)).put("day", p.getBoolean(Store.W_DAY, true))
                .put("wind", p.getInt(Store.W_WIND, 0)).put("hum", p.getInt(Store.W_HUM, 0))
                .put("max", p.getInt(Store.W_MAX, 0)).put("min", p.getInt(Store.W_MIN, 0))
                .put("words", Weather.words(p.getInt(Store.W_CODE, 0)))
                .put("icon", Weather.icon(p.getInt(Store.W_CODE, 0), p.getBoolean(Store.W_DAY, true))));
        o.put("net", new JSONObject()
                .put("at", p.getLong(Store.NET_AT, 0)).put("ok", p.getBoolean(Store.NET_OK, false))
                .put("down", p.getFloat(Store.SPEED_DOWN, 0)).put("up", p.getFloat(Store.SPEED_UP, 0))
                .put("ping", p.getFloat(Store.SPEED_PING, 0)).put("speedAt", p.getLong(Store.SPEED_AT, 0)));
        o.put("tuya", new JSONArray(p.getString(Store.TUYA_DEVICES, "[]")));
        o.put("tg", new JSONArray(p.getString(Store.TG_FEED, "[]")));
        String torr = TorrServe.version();
        o.put("torr", torr == null ? "" : torr);
        o.put("theme", p.getString(Store.THEME, "dark"));
        return o;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
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
