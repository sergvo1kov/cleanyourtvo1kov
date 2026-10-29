package ua.volkov.glass;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * The weather, Apple-style, on the left: the city, the temperature now, the next six hours, and five
 * days with range bars on one shared scale. From Open-Meteo, no key.
 */
final class WeatherPanel extends Panel {
    private static final double LAT = 46.4825, LON = 30.7233;
    private LinearLayout body;
    private TextView updated;

    WeatherPanel(MainActivity a, FrameLayout root, Ui ui) {
        super(a, root, ui);
    }

    @Override
    int side() {
        return -1;
    }

    @Override
    FrameLayout.LayoutParams params() {
        return ui.flp(360, -2, Gravity.TOP | Gravity.START, 40, 24, 0, 0);
    }

    @Override
    View build() {
        LinearLayout s = new LinearLayout(a);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setPadding(ui.px(20), ui.px(18), ui.px(20), ui.px(14));
        s.setBackground(ui.round(0xE61C1C1E, 28));
        s.setFocusable(true);   // the sheet itself holds the focus: there is nothing to press here
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(ui.text("Загружаю прогноз…", 12, Ui.SUB, false));
        s.addView(body);
        updated = ui.text("", 9, Ui.FAINT, false);
        updated.setGravity(Gravity.CENTER);
        s.addView(updated, ui.lp(-1, -2, 0, 10, 0, 0));
        load();
        return s;
    }

    @Override
    void opened() {
        a.post(() -> sheet.requestFocus());
    }

    private void load() {
        a.background(() -> {
            try {
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
                        + "&current=temperature_2m,weather_code,is_day,relative_humidity_2m,wind_speed_10m"
                        + "&hourly=temperature_2m,weather_code,precipitation_probability,is_day"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                        + "&forecast_days=6&timezone=Europe%2FKyiv";
                Http.Response r = Http.get(url, null, 12_000);
                if (r.code != 200) throw new Exception("Open-Meteo " + r.code);
                JSONObject o = new JSONObject(r.body);
                a.post(() -> {
                    if (isOpen()) render(o);
                });
            } catch (Exception e) {
                a.post(() -> {
                    if (!isOpen()) return;
                    body.removeAllViews();
                    body.addView(ui.text("Прогноз не загрузился: " + e.getMessage(), 11, Ui.SUB, false));
                });
            }
        });
    }

    private void render(JSONObject o) {
        body.removeAllViews();
        JSONObject cur = o.optJSONObject("current");
        JSONObject daily = o.optJSONObject("daily");
        JSONObject hourly = o.optJSONObject("hourly");
        if (cur == null || daily == null || hourly == null) return;
        int t = (int) Math.round(cur.optDouble("temperature_2m"));
        int code = cur.optInt("weather_code");
        JSONArray max = daily.optJSONArray("temperature_2m_max"), min = daily.optJSONArray("temperature_2m_min");

        TextView city = ui.text("Одесса", 14, Ui.WHITE, true);
        city.setGravity(Gravity.CENTER);
        body.addView(city, ui.lp(-1, -2, 0, 0, 0, 2));
        TextView big = ui.text(Ui.deg(t), 46, Ui.WHITE, false);
        big.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        big.setGravity(Gravity.CENTER);
        body.addView(big, ui.lp(-1, -2, 0, 0, 0, 2));
        TextView w = ui.text(Ui.words(code), 12, Ui.WHITE, false);
        w.setGravity(Gravity.CENTER);
        body.addView(w);
        TextView hl = ui.text("Макс. " + Ui.deg(round(max, 0)) + "   Мин. " + Ui.deg(round(min, 0)), 11, Ui.SUB, false);
        hl.setGravity(Gravity.CENTER);
        body.addView(hl, ui.lp(-1, -2, 0, 4, 0, 12));

        LinearLayout hHead = new LinearLayout(a);
        hHead.addView(ui.text("ПОЧАСОВОЙ ПРОГНОЗ", 9, Ui.FAINT, true), new LinearLayout.LayoutParams(0, -2, 1));
        hHead.addView(ui.text("Влажность " + cur.optInt("relative_humidity_2m") + "%  ·  Ветер "
                + Math.round(cur.optDouble("wind_speed_10m")) + " км/ч", 9, Ui.FAINT, false));
        body.addView(hHead);
        body.addView(rule(), ui.lp(-1, 1, 0, 6, 0, 8));

        // the next six hours, starting now
        JSONArray times = hourly.optJSONArray("time"), ht = hourly.optJSONArray("temperature_2m"),
                hc = hourly.optJSONArray("weather_code"), hd = hourly.optJSONArray("is_day");
        int start = 0;
        String now = String.format(Locale.ROOT, "%tFT%<tH:00", Calendar.getInstance());
        for (int i = 0; times != null && i < times.length(); i++) if (now.equals(times.optString(i))) start = i;
        LinearLayout hours = new LinearLayout(a);
        for (int k = 0; k < 6 && ht != null && start + k < ht.length(); k++) {
            int i = start + k;
            LinearLayout c = new LinearLayout(a);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            String hh = k == 0 ? "Сейчас" : times.optString(i).substring(11, 13);
            c.addView(ui.text(hh, 9.5f, Ui.SUB, false));
            c.addView(ui.text(Ui.glyph(hc.optInt(i), hd == null || hd.optInt(i, 1) == 1), 15, Ui.WHITE, false), ui.lp(-2, -2, 0, 6, 0, 6));
            c.addView(ui.text(Ui.deg((int) Math.round(ht.optDouble(i))), 11, Ui.WHITE, true));
            hours.addView(c, new LinearLayout.LayoutParams(0, -2, 1));
        }
        body.addView(hours, ui.lp(-1, -2, 0, 0, 0, 12));

        body.addView(ui.text("ПРОГНОЗ НА 5 ДНЕЙ", 9, Ui.FAINT, true));
        body.addView(rule(), ui.lp(-1, 1, 0, 6, 0, 4));
        JSONArray dc = daily.optJSONArray("weather_code"), dp = daily.optJSONArray("precipitation_probability_max");
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int i = 0; i < 5 && max != null && i < max.length(); i++) {
            lo = Math.min(lo, round(min, i));
            hi = Math.max(hi, round(max, i));
        }
        String[] names = {"Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб"};
        Calendar cal = Calendar.getInstance();
        for (int i = 0; i < 5 && max != null && i < max.length(); i++) {
            LinearLayout r = new LinearLayout(a);
            r.setGravity(Gravity.CENTER_VERTICAL);
            String day = i == 0 ? "Сегодня" : names[(cal.get(Calendar.DAY_OF_WEEK) - 1 + i) % 7];
            r.addView(ui.text(day, 11, Ui.WHITE, true), ui.lp(64, -2, 0, 0, 0, 0));
            LinearLayout ic = new LinearLayout(a);
            ic.setOrientation(LinearLayout.VERTICAL);
            ic.setGravity(Gravity.CENTER_HORIZONTAL);
            ic.addView(ui.text(Ui.glyph(dc == null ? 0 : dc.optInt(i), true), 13, Ui.WHITE, false));
            int pr = dp == null ? 0 : dp.optInt(i);
            if (pr >= 20) ic.addView(ui.text(pr + "%", 8, 0xFF64D2FF, true));
            r.addView(ic, ui.lp(34, -2, 0, 0, 6, 0));
            TextView tmin = ui.text(Ui.deg(round(min, i)), 11, Ui.SUB, false);
            tmin.setGravity(Gravity.END);
            r.addView(tmin, ui.lp(36, -2, 0, 0, 8, 0));
            r.addView(bar(round(min, i), round(max, i), lo, hi), new LinearLayout.LayoutParams(0, ui.px(5), 1));
            TextView tmax = ui.text(Ui.deg(round(max, i)), 11, Ui.WHITE, false);
            tmax.setGravity(Gravity.END);
            r.addView(tmax, ui.lp(36, -2, 8, 0, 0, 0));
            body.addView(r, ui.lp(-1, 30, 0, 0, 0, 0));
        }
        updated.setText("Open-Meteo  ·  обновлено " + String.format(Locale.ROOT, "%tH:%<tM", Calendar.getInstance()));
    }

    /** A day's range on the five-day scale: a cool-to-warm gradient pill inside a dim track. */
    private View bar(int lo, int hi, int all0, int all1) {
        FrameLayout track = new FrameLayout(a);
        track.setBackground(ui.round(0x33FFFFFF, 3));
        View fill = new View(a);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF64D2FF, 0xFFFFD60A, 0xFFFF9F0A});
        g.setCornerRadius(ui.px(3));
        fill.setBackground(g);
        track.addView(fill, new FrameLayout.LayoutParams(0, -1));
        track.post(() -> {
            float span = Math.max(1, all1 - all0);
            int w = track.getWidth();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fill.getLayoutParams();
            lp.leftMargin = Math.round(w * (lo - all0) / span);
            lp.width = Math.max(ui.px(6), Math.round(w * (hi - lo) / span));
            fill.setLayoutParams(lp);
        });
        return track;
    }

    private View rule() {
        View v = new View(a);
        v.setBackgroundColor(0x26FFFFFF);
        return v;
    }

    private static int round(JSONArray a, int i) {
        return a == null ? 0 : (int) Math.round(a.optDouble(i, 0));
    }
}
