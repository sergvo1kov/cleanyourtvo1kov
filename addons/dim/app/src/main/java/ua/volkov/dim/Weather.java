package ua.volkov.dim;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/** The weather in Odesa now, from Open-Meteo (no key). */
final class Weather {
    static final double LAT = 46.4825, LON = 30.7233;

    static final class Now {
        int temp, feels, code, wind, humidity, max, min;
        boolean day = true;
    }

    private Weather() {}

    static Now fetch() throws IOException, JSONException {
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
                + "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m,is_day"
                + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=Europe%2FKyiv";
        Http.Response r = Http.get(url, null, 15_000);
        if (r.code != 200) throw new IOException("Open-Meteo: HTTP " + r.code);
        JSONObject root = new JSONObject(r.body);
        JSONObject cur = root.getJSONObject("current");
        Now n = new Now();
        n.temp = (int) Math.round(cur.getDouble("temperature_2m"));
        n.feels = (int) Math.round(cur.optDouble("apparent_temperature", n.temp));
        n.code = cur.optInt("weather_code", 0);
        n.wind = (int) Math.round(cur.optDouble("wind_speed_10m", 0));
        n.humidity = cur.optInt("relative_humidity_2m", 0);
        n.day = cur.optInt("is_day", 1) == 1;
        JSONObject daily = root.optJSONObject("daily");
        if (daily != null) {
            JSONArray max = daily.optJSONArray("temperature_2m_max");
            JSONArray min = daily.optJSONArray("temperature_2m_min");
            n.max = max == null ? n.temp : (int) Math.round(max.optDouble(0, n.temp));
            n.min = min == null ? n.temp : (int) Math.round(min.optDouble(0, n.temp));
        }
        return n;
    }

    static String deg(int t) {
        return (t > 0 ? "+" : "") + t + "°";
    }

    /** WMO weather code → symbol. */
    static String icon(int code, boolean day) {
        if (code == 0) return day ? "☀" : "☾";
        if (code <= 2) return day ? "⛅" : "☁";
        if (code == 3) return "☁";
        if (code == 45 || code == 48) return "🌫";
        if (code >= 51 && code <= 67) return "🌧";
        if (code >= 71 && code <= 77) return "❄";
        if (code >= 80 && code <= 82) return "🌦";
        if (code == 85 || code == 86) return "🌨";
        if (code >= 95) return "⛈";
        return "☁";
    }

    static String words(int code) {
        if (code == 0) return "Ясно";
        if (code == 1) return "Преимущественно ясно";
        if (code == 2) return "Переменная облачность";
        if (code == 3) return "Облачно";
        if (code == 45 || code == 48) return "Туман";
        if (code >= 51 && code <= 57) return "Морось";
        if (code >= 61 && code <= 67) return "Дождь";
        if (code >= 71 && code <= 77) return "Снег";
        if (code >= 80 && code <= 82) return "Ливень";
        if (code == 85 || code == 86) return "Снегопад";
        if (code >= 95) return "Гроза";
        return "";
    }
}
