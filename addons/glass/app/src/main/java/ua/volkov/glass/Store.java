package ua.volkov.glass;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings (tokens) and the last fetched state, in the app's private preferences. */
final class Store {
    // Settings
    static final String ALERTS_TOKEN = "alerts_token";
    static final String TUYA_ID = "tuya_id";
    static final String TUYA_SECRET = "tuya_secret";

    // Alerts: state -1 unknown, 0 calm, 1 alert in our area, 2 alert in part of the raion
    static final String ALERT_STATE = "alert_state";
    static final String ALERT_SINCE = "alert_since";
    static final String ALERT_SCOPE = "alert_scope";
    static final String ALERT_OBLASTS = "alert_oblasts";
    static final String ALERT_LIST = "alert_list";
    static final String ALERT_AT = "alert_at";
    static final String ALERT_SOURCE = "alert_source";

    // Weather
    static final String W_TEMP = "w_temp";
    static final String W_FEELS = "w_feels";
    static final String W_CODE = "w_code";
    static final String W_DAY = "w_day";
    static final String W_WIND = "w_wind";
    static final String W_HUM = "w_hum";
    static final String W_MAX = "w_max";
    static final String W_MIN = "w_min";
    static final String W_AT = "w_at";

    // Internet
    static final String NET_OK = "net_ok";
    static final String NET_AT = "net_at";
    static final String SPEED_DOWN = "speed_down";
    static final String SPEED_UP = "speed_up";
    static final String SPEED_PING = "speed_ping";
    static final String SPEED_AT = "speed_at";

    // Tuya: the device list as JSON [{name, online, summary}]
    static final String TUYA_DEVICES = "tuya_devices";
    static final String TUYA_AT = "tuya_at";
    static final String TUYA_ERROR = "tuya_error";

    // Telegram: channels setting, feed JSON [{channel, title, text, ms, post}]
    static final String TG_CHANNELS = "tg_channels";
    static final String TG_FEED = "tg_feed";
    static final String TG_AT = "tg_at";
    static final String TG_ERROR = "tg_error";

    // Home screen
    static final String HOME_TARGET = "home_target";  // "flick" or "glass": which home screen Home opens
    static final String BACKDROP = "backdrop_on";     // the focused film's picture behind the row
    static final String THEME = "theme";              // "dark" or "light"
    static final String FAVS = "favs";                // package names, comma separated, in order
    static final String MUST_NICK = "must_nick";
    static final String MUST_LIST = "must_list";
    static final String MUST_AT = "must_at";
    static final String MUST_WANT = "must_want";      // the profile's own Want list, badged on the row
    static final String QUICK = "quick";              // bottom row slots, comma separated specs
    static final String TMDB_KEY = "tmdb_key";        // free TMDB API key: a poster opens its card in Lampa
    static final String FRESH_LIST = "fresh_list";    // fresh TMDB titles for the For you row
    static final String FRESH_AT = "fresh_at";

    private Store() {}

    static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences("data", Context.MODE_PRIVATE);
    }

    static String str(Context ctx, String key) {
        String v = prefs(ctx).getString(key, "");
        return v == null ? "" : v.trim();
    }
}
