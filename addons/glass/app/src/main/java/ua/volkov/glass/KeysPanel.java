package ua.volkov.glass;

import android.content.SharedPreferences;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Glass's own keys and services, kept in Glass only. "Импорт из Flick TV" copies Flick TV's keys once;
 * after that the two apps never share them. Values are saved as they are typed.
 */
final class KeysPanel extends Panel {
    private View first;
    private final EditText[] fields = new EditText[Data.KEYS.length];
    private boolean changed;

    private static final String[] TITLES = {"Тревоги: токен alerts.in.ua", "Smart Life: Access ID", "Smart Life: Access Secret",
            "TMDB: API-ключ", "Must: ник профиля", "Telegram: каналы"};
    private static final String[] HINTS = {"пусто: открытый источник по Одессе", "из Tuya IoT Platform", "из Tuya IoT Platform",
            "бесплатный, themoviedb.org", "лента «Хочу посмотреть» и похожие", "через запятую, без @"};
    private static final boolean[] SECRET = {true, false, true, true, false, false};

    KeysPanel(MainActivity a, FrameLayout root, Ui ui) {
        super(a, root, ui);
    }

    @Override
    int side() {
        return 0;
    }

    @Override
    FrameLayout.LayoutParams params() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    @Override
    View build() {
        LinearLayout s = new LinearLayout(a);
        s.setBackgroundColor(0xF2101012);
        s.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER);
        TextView head = ui.text("Ключи и сервисы", 24, Ui.WHITE, true);
        head.setGravity(Gravity.CENTER);
        left.addView(head, ui.lp(-1, -2, 0, 0, 0, 10));
        TextView sub = ui.text("Хранятся только в Glass и никуда не передаются.\nFlick TV хранит свои отдельно.", 11, Ui.SUB, false);
        sub.setGravity(Gravity.CENTER);
        left.addView(sub, ui.lp(-1, -2, 24, 0, 24, 0));
        s.addView(left, new LinearLayout.LayoutParams(0, -1, 1));

        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(ui.px(28), ui.px(48), ui.px(56), ui.px(48));
        list.setClipChildren(false);
        list.setClipToPadding(false);
        scroll.addView(list);

        LinearLayout imp = button("Импорт из Flick TV", Flick.installed(a) ? "скопировать ключи один раз" : "Flick TV не установлен");
        imp.setOnClickListener(v -> a.background(() -> {
            int n = Data.importFromFlick(a);
            a.post(() -> {
                if (n < 0) {
                    Toast.makeText(a, "Flick TV не ответил: обнови его", Toast.LENGTH_LONG).show();
                    return;
                }
                fill();
                changed = true;
                Toast.makeText(a, n == 0 ? "В Flick TV ключей нет" : "Скопировано: " + n, Toast.LENGTH_SHORT).show();
            });
        }));
        list.addView(imp, ui.lp(-1, 52, 0, 0, 0, 18));
        first = imp;

        SharedPreferences p = Store.prefs(a);
        for (int i = 0; i < Data.KEYS.length; i++) {
            list.addView(ui.text(TITLES[i], 10, Ui.FAINT, false), ui.lp(-2, -2, 10, i == 0 ? 0 : 10, 0, 6));
            EditText e = field(HINTS[i], SECRET[i]);
            String key = Data.KEYS[i];
            e.setText(p.getString(key, ""));
            e.setOnFocusChangeListener((v, has) -> {
                ((android.graphics.drawable.GradientDrawable) v.getBackground()).setColor(has ? 0xF2FFFFFF : Ui.PLATE);
                e.setTextColor(has ? Ui.GRAPHITE : Ui.WHITE);
                if (!has) save(key, e);
            });
            e.setOnEditorActionListener((v, id, ev) -> {
                save(key, e);
                return false;
            });
            fields[i] = e;
            list.addView(e, ui.lp(-1, 44, 0, 0, 0, 0));
        }
        s.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1.25f));
        return s;
    }

    private void fill() {
        SharedPreferences p = Store.prefs(a);
        for (int i = 0; i < fields.length; i++) if (fields[i] != null) fields[i].setText(p.getString(Data.KEYS[i], ""));
    }

    private void save(String key, EditText e) {
        String v = e.getText().toString().trim();
        SharedPreferences p = Store.prefs(a);
        if (v.equals(p.getString(key, ""))) return;
        p.edit().putString(key, v).putLong(Store.ALERT_AT, 0).putLong(Store.TUYA_AT, 0)
                .putLong(Store.MUST_AT, 0).putLong(Store.FRESH_AT, 0).apply();
        changed = true;
    }

    private EditText field(String hint, boolean secret) {
        EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setHintTextColor(Ui.FAINT);
        e.setTextColor(Ui.WHITE);
        e.setTextSize(13);
        e.setPadding(ui.px(14), 0, ui.px(14), 0);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        e.setBackground(ui.round(Ui.PLATE, 12));
        return e;
    }

    private LinearLayout button(String title, String value) {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(ui.px(16), 0, ui.px(16), 0);
        r.addView(ui.line(title, 12.5f, Ui.WHITE, true), new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(ui.line(value, 11, Ui.SUB, false));
        ui.plate(r, 12, null);
        return r;
    }

    @Override
    void opened() {
        a.post(() -> {
            if (first != null) first.requestFocus();
        });
    }

    @Override
    void close() {
        for (int i = 0; i < fields.length; i++) if (fields[i] != null) save(Data.KEYS[i], fields[i]);
        super.close();
        if (changed) {
            changed = false;
            Data.refresh(a);
            a.post(a::keysChanged);
        }
    }
}
