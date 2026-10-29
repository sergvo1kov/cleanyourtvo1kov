package ua.volkov.glass;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Flick TV's data, read through its DataProvider: the film row, alerts, weather, Smart Life, TorrServe.
 * Keys and tokens stay in Flick TV; Glass only gets the results. All calls block: call them off the main
 * thread. When Flick TV is missing or older, every call answers empty.
 */
final class Flick {
    static final String PKG = "ua.volkov.dim";
    private static final Uri URI = Uri.parse("content://ua.volkov.dim.data");

    private Flick() {}

    static boolean installed(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String call(Context ctx, String method, String arg, Bundle extras) {
        try {
            Bundle b = ctx.getContentResolver().call(URI, method, arg, extras);
            return b == null ? null : b.getString("json");
        } catch (Exception e) {
            return null;
        }
    }

    static JSONObject status(Context ctx) {
        try {
            String s = call(ctx, "status", null, null);
            return s == null ? new JSONObject() : new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static JSONArray feed(Context ctx, int seed) {
        try {
            String s = call(ctx, "feed", String.valueOf(seed), null);
            return s == null ? new JSONArray() : new JSONArray(s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static JSONObject withFilm(Context ctx, String method, JSONObject film) {
        Bundle e = new Bundle();
        e.putString("film", film.toString());
        try {
            String s = call(ctx, method, null, e);
            return s == null ? new JSONObject() : new JSONObject(s);
        } catch (Exception ex) {
            return new JSONObject();
        }
    }

    /** {overview, vote, genres, year, backdrop, tmdb, media} from TMDB, or empty. */
    static JSONObject details(Context ctx, JSONObject film) {
        return withFilm(ctx, "details", film);
    }

    /** {tmdb, media} or empty. */
    static JSONObject find(Context ctx, JSONObject film) {
        return withFilm(ctx, "find", film);
    }

    static boolean toggle(Context ctx, String deviceId) {
        return call(ctx, "tuya", deviceId, null) != null;
    }

    static void refresh(Context ctx) {
        call(ctx, "refresh", null, null);
    }

    static boolean sleep(Context ctx) {
        String s = call(ctx, "sleep", null, null);
        return s != null && s.contains("true");
    }

    /** "flick" or "glass": which home screen the Home button opens (Flick TV's button service decides). */
    static void setHome(Context ctx, String target) {
        call(ctx, "home", target, null);
    }

    static void setHomeAsync(Context ctx, String target) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> setHome(app, target)).start();
    }

    static Intent flickHome() {
        return new Intent().setClassName(PKG, PKG + ".HomeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Flick TV's Control Center: where every key and service is entered. */
    static Intent flickKeys() {
        return new Intent().setClassName(PKG, PKG + ".DimActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
