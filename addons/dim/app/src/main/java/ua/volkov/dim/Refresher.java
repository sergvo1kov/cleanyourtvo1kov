package ua.volkov.dim;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches what is stale, only when someone reads (the launcher's panel or this app's screen):
 * nothing runs in the background while nobody looks. Alerts and the internet check every 30 s,
 * Smart Life every 60 s, the weather every 30 min.
 */
final class Refresher {
    static final long ALERTS_MS = 30_000L, TUYA_MS = 60_000L, TG_MS = 120_000L, WEATHER_MS = 30 * 60_000L, RETRY_MS = 5 * 60_000L;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile boolean busy;
    private static long weatherTried;

    private Refresher() {}

    static void kick(Context ctx, boolean force) {
        if (busy) return;
        busy = true;
        Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            try {
                if (refresh(app, force)) StatusProvider.notifyChanged(app);
            } finally {
                busy = false;
            }
        });
    }

    private static boolean refresh(Context ctx, boolean force) {
        SharedPreferences p = Store.prefs(ctx);
        long now = System.currentTimeMillis();
        boolean changed = false;
        SharedPreferences.Editor e = p.edit();

        if (force || now - p.getLong(Store.ALERT_AT, 0) > ALERTS_MS) {
            boolean online = SpeedTest.online();
            e.putBoolean(Store.NET_OK, online).putLong(Store.NET_AT, now);
            try {
                Alerts.Result a = Alerts.fetch(Store.str(ctx, Store.ALERTS_TOKEN));
                e.putInt(Store.ALERT_STATE, a.state)
                        .putLong(Store.ALERT_SINCE, a.sinceMs)
                        .putString(Store.ALERT_SCOPE, a.scope)
                        .putInt(Store.ALERT_OBLASTS, a.oblasts.size())
                        .putString(Store.ALERT_LIST, Alerts.join(a.oblasts))
                        .putString(Store.ALERT_SOURCE, a.source);
            } catch (Exception ex) {
                e.putInt(Store.ALERT_STATE, Alerts.UNKNOWN).putString(Store.ALERT_SOURCE, String.valueOf(ex.getMessage()));
            }
            e.putLong(Store.ALERT_AT, now);
            changed = true;
        }

        boolean weatherDue = now - p.getLong(Store.W_AT, 0) > WEATHER_MS && now - weatherTried > RETRY_MS;
        if (force || weatherDue) {
            weatherTried = now;
            try {
                Weather.Now w = Weather.fetch();
                e.putInt(Store.W_TEMP, w.temp).putInt(Store.W_FEELS, w.feels).putInt(Store.W_CODE, w.code)
                        .putBoolean(Store.W_DAY, w.day).putInt(Store.W_WIND, w.wind).putInt(Store.W_HUM, w.humidity)
                        .putInt(Store.W_MAX, w.max).putInt(Store.W_MIN, w.min).putLong(Store.W_AT, now);
                changed = true;
            } catch (Exception ignored) {
                // keep the last reading
            }
        }

        String id = Store.str(ctx, Store.TUYA_ID), secret = Store.str(ctx, Store.TUYA_SECRET);
        if (!id.isEmpty() && !secret.isEmpty() && (force || now - p.getLong(Store.TUYA_AT, 0) > TUYA_MS)) {
            try {
                JSONArray d = Tuya.devices(id, secret);
                e.putString(Store.TUYA_DEVICES, d.toString()).putString(Store.TUYA_ERROR, "");
            } catch (Exception ex) {
                e.putString(Store.TUYA_ERROR, String.valueOf(ex.getMessage()));
            }
            e.putLong(Store.TUYA_AT, now);
            changed = true;
        }

        if (force || now - p.getLong(Store.TG_AT, 0) > TG_MS) {
            try {
                JSONArray feed = Telegram.feed(Telegram.channels(Store.str(ctx, Store.TG_CHANNELS)));
                e.putString(Store.TG_FEED, feed.toString()).putString(Store.TG_ERROR, "");
            } catch (Exception ex) {
                e.putString(Store.TG_ERROR, String.valueOf(ex.getMessage()));
            }
            e.putLong(Store.TG_AT, now);
            changed = true;
        }

        e.apply();
        return changed;
    }
}
