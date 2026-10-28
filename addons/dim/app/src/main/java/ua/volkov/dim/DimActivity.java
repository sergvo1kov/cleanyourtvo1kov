package ua.volkov.dim;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Full screen: alerts, weather, internet with a speed test, Smart Life devices, settings. */
public final class DimActivity extends Activity {
    private static final int CARD = 0x1AFFFFFF, CARD_FOCUS = 0x33FFFFFF;
    private static final int WHITE = 0xFFFFFFFF, DIM = 0x99FFFFFF, FAINT = 0x66FFFFFF;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Refresher.kick(DimActivity.this, false);
            render();
            main.postDelayed(this, 30_000L);
        }
    };
    private ContentObserver observer;
    private float dp;

    private TextView updated, alertDot, alertHead, alertSub, alertList;
    private TextView wIcon, wTemp, wWords, wMore;
    private TextView netHead, netSpeed, netHint;
    private LinearLayout devices;
    private boolean testing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        setContentView(build());
        observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                render();
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        getContentResolver().registerContentObserver(StatusProvider.URI, false, observer);
        Refresher.kick(this, true);
        render();
        main.postDelayed(tick, 30_000L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
        getContentResolver().unregisterContentObserver(observer);
    }

    // ---------------------------------------------------------------- layout

    private View build() {
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF141A2A, 0xFF07090F, 0xFF000000});
        root.setBackground(bg);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(64), px(36), px(64), px(48));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        scroll.addView(col);
        root.addView(scroll);

        TextView title = text("Дом", 34, WHITE, true);
        col.addView(title);
        updated = text("Одесса", 14, FAINT, false);
        col.addView(updated, margins(0, 2, 0, 22));

        // Row 1: alert (wide) + weather
        LinearLayout row1 = row();
        LinearLayout alert = card(v -> Refresher.kick(this, true));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        alertDot = text("●", 22, StatusProvider.GREY, false);
        head.addView(alertDot, margins(0, 0, 10, 0));
        alertHead = text("…", 28, WHITE, true);
        head.addView(alertHead);
        alert.addView(head);
        alertSub = text("", 16, DIM, false);
        alert.addView(alertSub, margins(0, 6, 0, 0));
        alertList = text("", 14, FAINT, false);
        alertList.setMaxLines(3);
        alertList.setEllipsize(TextUtils.TruncateAt.END);
        alert.addView(alertList, margins(0, 14, 0, 0));
        row1.addView(alert, weight(2, 0, 0, 20, 0));

        LinearLayout weather = card(v -> Refresher.kick(this, true));
        LinearLayout wl = new LinearLayout(this);
        wl.setGravity(Gravity.CENTER_VERTICAL);
        wIcon = text("", 38, WHITE, false);
        wl.addView(wIcon, margins(0, 0, 12, 0));
        wTemp = text("", 44, WHITE, false);
        wTemp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        wl.addView(wTemp);
        weather.addView(wl);
        wWords = text("", 16, DIM, false);
        weather.addView(wWords, margins(0, 4, 0, 0));
        wMore = text("", 14, FAINT, false);
        weather.addView(wMore, margins(0, 10, 0, 0));
        row1.addView(weather, weight(1, 0, 0, 0, 0));
        col.addView(row1, margins(0, 0, 0, 20));

        // Row 2: internet + speed test, settings
        LinearLayout row2 = row();
        LinearLayout net = card(v -> runSpeedTest());
        netHead = text("Интернет", 22, WHITE, true);
        net.addView(netHead);
        netSpeed = text("", 18, DIM, false);
        net.addView(netSpeed, margins(0, 8, 0, 0));
        netHint = text("OK — проверить скорость", 13, FAINT, false);
        net.addView(netHint, margins(0, 10, 0, 0));
        row2.addView(net, weight(2, 0, 0, 20, 0));

        LinearLayout settings = card(v -> openSettings());
        settings.addView(text("Настройки", 22, WHITE, true));
        settings.addView(text("Токен тревог, Smart Life", 14, FAINT, false), margins(0, 8, 0, 0));
        row2.addView(settings, weight(1, 0, 0, 0, 0));
        col.addView(row2, margins(0, 0, 0, 30));

        col.addView(text("Smart Life", 20, WHITE, true), margins(0, 0, 0, 12));
        devices = new LinearLayout(this);
        devices.setOrientation(LinearLayout.VERTICAL);
        devices.setClipChildren(false);
        col.addView(devices);

        alert.requestFocus();
        return root;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        return r;
    }

    private LinearLayout card(View.OnClickListener onClick) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(px(26), px(22), px(26), px(22));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(22));
        g.setColor(CARD);
        c.setBackground(g);
        c.setFocusable(true);
        c.setClickable(true);
        c.setOnClickListener(onClick);
        c.setOnFocusChangeListener((v, has) -> {
            g.setColor(has ? CARD_FOCUS : CARD);
            v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).translationZ(has ? px(12) : 0)
                    .setDuration(160).start();
        });
        return c;
    }

    private TextView text(String s, float sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(px(l), px(t), px(r), px(b));
        return lp;
    }

    private LinearLayout.LayoutParams weight(float w, int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w);
        lp.setMargins(px(l), px(t), px(r), px(b));
        return lp;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    // ---------------------------------------------------------------- data

    private void render() {
        SharedPreferences p = Store.prefs(this);
        StatusProvider.Line line = StatusProvider.line(this);
        long at = Math.max(p.getLong(Store.ALERT_AT, 0), p.getLong(Store.W_AT, 0));
        updated.setText(at == 0 ? "Одесса" : "Одесса  ·  обновлено " + hhmm(at));

        alertDot.setTextColor(line.colour);
        alertHead.setText(line.headline);
        String scope = Alerts.label(p.getString(Store.ALERT_SCOPE, Alerts.RAION));
        String sub = scope;
        if (line.alert && line.sinceMs > 0) sub += "  ·  с " + hhmm(line.sinceMs) + " (" + elapsed(line.sinceMs) + ")";
        String source = p.getString(Store.ALERT_SOURCE, "");
        if (p.getInt(Store.ALERT_STATE, Alerts.UNKNOWN) == Alerts.UNKNOWN && !source.isEmpty()) sub += "  ·  " + source;
        alertSub.setText(sub);
        String list = p.getString(Store.ALERT_LIST, "");
        alertList.setText(list.isEmpty() ? "По Украине сейчас тихо" : "Тревога сейчас: " + list);

        if (p.getLong(Store.W_AT, 0) > 0) {
            wIcon.setText(Weather.icon(p.getInt(Store.W_CODE, 0), p.getBoolean(Store.W_DAY, true)));
            wTemp.setText(Weather.deg(p.getInt(Store.W_TEMP, 0)));
            wWords.setText(Weather.words(p.getInt(Store.W_CODE, 0)) + "  ·  " + Weather.deg(p.getInt(Store.W_MAX, 0))
                    + " / " + Weather.deg(p.getInt(Store.W_MIN, 0)));
            wMore.setText("Ощущается " + Weather.deg(p.getInt(Store.W_FEELS, 0)) + "\nВетер "
                    + p.getInt(Store.W_WIND, 0) + " км/ч  ·  Влажность " + p.getInt(Store.W_HUM, 0) + "%");
        } else {
            wWords.setText("Погода загружается…");
        }

        if (!testing) {
            boolean ok = p.getBoolean(Store.NET_OK, false);
            netHead.setText(p.getLong(Store.NET_AT, 0) == 0 ? "Интернет" : ok ? "Интернет ✓" : "Интернета нет");
            long sAt = p.getLong(Store.SPEED_AT, 0);
            netSpeed.setText(sAt == 0 ? "Скорость ещё не проверяли"
                    : String.format(Locale.ROOT, "↓ %.0f   ↑ %.0f Мбит/с   ·   пинг %.0f мс",
                    p.getFloat(Store.SPEED_DOWN, 0), p.getFloat(Store.SPEED_UP, 0), p.getFloat(Store.SPEED_PING, 0)));
            netHint.setText(sAt == 0 ? "OK — проверить скорость" : "Проверено в " + hhmm(sAt) + "  ·  OK — ещё раз");
        }

        renderDevices(p);
    }

    private void renderDevices(SharedPreferences p) {
        devices.removeAllViews();
        if (Store.str(this, Store.TUYA_ID).isEmpty()) {
            devices.addView(text("Добавьте Tuya Access ID и Access Secret в Настройках", 15, FAINT, false));
            return;
        }
        String err = p.getString(Store.TUYA_ERROR, "");
        JSONArray arr;
        try {
            arr = new JSONArray(p.getString(Store.TUYA_DEVICES, "[]"));
        } catch (Exception e) {
            arr = new JSONArray();
        }
        if (!err.isEmpty()) devices.addView(text("Ошибка: " + err, 14, 0xFFFF9F0A, false), margins(0, 0, 0, 10));
        if (arr.length() == 0) {
            if (err.isEmpty()) devices.addView(text("Загрузка устройств…", 15, FAINT, false));
            return;
        }
        LinearLayout r = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject d = arr.optJSONObject(i);
            if (d == null) continue;
            if (i % 4 == 0) {
                r = row();
                devices.addView(r, margins(0, 0, 0, 16));
            }
            LinearLayout c = card(v -> Refresher.kick(this, true));
            c.setMinimumHeight(px(96));
            TextView name = text(d.optString("name"), 17, WHITE, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            c.addView(name);
            boolean online = d.optBoolean("online", true);
            String sum = online ? d.optString("summary") : "не в сети";
            TextView s = text(sum.isEmpty() ? "—" : sum, 14, online ? DIM : 0xFFFF9F0A, false);
            s.setMaxLines(2);
            c.addView(s, margins(0, 6, 0, 0));
            if (!online) c.setAlpha(0.6f);
            r.addView(c, weight(1, 0, 0, i % 4 == 3 ? 0 : 16, 0));
        }
        // keep the last row's tiles the same width as full rows
        if (r != null) {
            for (int k = r.getChildCount(); k < 4; k++) {
                View pad = new View(this);
                r.addView(pad, weight(1, 0, 0, k == 3 ? 0 : 16, 0));
            }
        }
    }

    // ---------------------------------------------------------------- actions

    private void runSpeedTest() {
        if (testing) return;
        testing = true;
        netHead.setText("Проверка скорости…");
        netSpeed.setText("пинг…");
        netHint.setText("Около 20 секунд");
        final float[] shown = new float[3];
        new Thread(() -> {
            try {
                SpeedTest.Result r = SpeedTest.run((phase, value) -> main.post(() -> {
                    if ("ping".equals(phase)) shown[2] = (float) value;
                    else if ("down".equals(phase)) shown[0] = (float) value;
                    else shown[1] = (float) value;
                    netSpeed.setText(String.format(Locale.ROOT, "↓ %.0f   ↑ %.0f Мбит/с   ·   пинг %.0f мс",
                            shown[0], shown[1], shown[2]));
                    netHint.setText("down".equals(phase) ? "Загрузка…" : "up".equals(phase) ? "Отдача…" : "Пинг");
                }));
                Store.prefs(this).edit()
                        .putFloat(Store.SPEED_DOWN, (float) r.downMbps)
                        .putFloat(Store.SPEED_UP, (float) r.upMbps)
                        .putFloat(Store.SPEED_PING, (float) r.pingMs)
                        .putLong(Store.SPEED_AT, System.currentTimeMillis())
                        .putBoolean(Store.NET_OK, true)
                        .apply();
                StatusProvider.notifyChanged(this);
                main.post(() -> {
                    testing = false;
                    render();
                });
            } catch (Exception e) {
                main.post(() -> {
                    testing = false;
                    render();
                    netHint.setText("Не удалось: " + e.getMessage());
                });
            }
        }).start();
    }

    private void openSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(24), px(8), px(24), px(0));
        EditText token = field(box, "Токен alerts.in.ua (пусто — данные по области)", Store.ALERTS_TOKEN, false);
        EditText id = field(box, "Tuya Access ID / Client ID", Store.TUYA_ID, false);
        EditText secret = field(box, "Tuya Access Secret / Client Secret", Store.TUYA_SECRET, true);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Настройки")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    Store.prefs(this).edit()
                            .putString(Store.ALERTS_TOKEN, token.getText().toString().trim())
                            .putString(Store.TUYA_ID, id.getText().toString().trim())
                            .putString(Store.TUYA_SECRET, secret.getText().toString().trim())
                            .putLong(Store.ALERT_AT, 0)
                            .putLong(Store.TUYA_AT, 0)
                            .putString(Store.TUYA_DEVICES, "[]")
                            .apply();
                    Refresher.kick(this, true);
                    render();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private EditText field(LinearLayout box, String label, String key, boolean secret) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(DIM);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(l, margins(0, 12, 0, 2));
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextColor(Color.WHITE);
        e.setInputType(InputType.TYPE_CLASS_TEXT | (secret
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        e.setText(Store.str(this, key));
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private static String hhmm(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(ms));
    }

    private static String elapsed(long since) {
        long min = Math.max(0, (System.currentTimeMillis() - since) / 60_000L);
        return min < 60 ? min + " мин" : (min / 60) + " ч " + (min % 60) + " мин";
    }
}
