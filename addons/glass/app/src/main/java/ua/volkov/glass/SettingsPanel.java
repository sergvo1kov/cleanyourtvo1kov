package ua.volkov.glass;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings, tvOS split-pane: a big gear and the app's name on the left, plates on the right. Keys and
 * services live in Flick TV's Control Center; this is the launcher and the TV.
 */
final class SettingsPanel extends Panel {
    private View first;

    SettingsPanel(MainActivity a, FrameLayout root, Ui ui) {
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
        FrameLayout gear = new FrameLayout(a);
        gear.setBackground(ui.round(0xFF3A3A3C, 30));
        ImageView gi = ui.icon(R.drawable.qp_gear, 0xFFD1D1D6);
        gi.setPadding(ui.px(22), ui.px(22), ui.px(22), ui.px(22));
        gear.addView(gi, new FrameLayout.LayoutParams(-1, -1));
        left.addView(gear, ui.lp(130, 130, 0, 0, 0, 22));
        TextView head = ui.text("Настройки", 26, Ui.WHITE, true);
        head.setGravity(Gravity.CENTER);
        left.addView(head, ui.lp(-1, -2, 0, 0, 0, 0));
        TextView by = ui.text("Flick TV Glass " + version() + "  ·  Serhii Volkov (@sergvo1kov)", 10, Ui.FAINT, false);
        by.setGravity(Gravity.CENTER);
        left.addView(by, ui.lp(-1, -2, 0, 8, 0, 0));
        s.addView(left, new LinearLayout.LayoutParams(0, -1, 1));

        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(ui.px(28), ui.px(56), ui.px(56), ui.px(56));   // room for the focused plate to grow
        list.setClipChildren(false);
        scroll.addView(list);
        SharedPreferences p = a.getSharedPreferences("glass", android.content.Context.MODE_PRIVATE);

        section(list, "Лаунчер");
        first = plate(list, R.drawable.qp_home, "Главный экран", "Glass  ›", v -> {
            close();
            a.background(() -> Flick.setHome(a, "flick"));
            try {
                a.startActivity(Flick.flickHome());
                Toast.makeText(a, "Home теперь открывает Flick TV", Toast.LENGTH_SHORT).show();
            } catch (RuntimeException e) {
                Toast.makeText(a, "Flick TV не установлен", Toast.LENGTH_SHORT).show();
            }
        });
        TextView[] auto = new TextView[1];
        View autoPlate = plate(list, R.drawable.qp_play, "Фильмы сменяются сами",
                p.getBoolean("autoplay", true) ? "Вкл" : "Выкл", null);
        auto[0] = value(autoPlate);
        autoPlate.setOnClickListener(v -> {
            boolean on = !p.getBoolean("autoplay", true);
            p.edit().putBoolean("autoplay", on).apply();
            auto[0].setText(on ? "Вкл" : "Выкл");
        });
        plate(list, R.drawable.g_grid, "Док", "Вернуть как было", v -> {
            p.edit().remove("dock").apply();
            close();
            a.recreate();
        });

        section(list, "Сервисы");
        plate(list, R.drawable.qp_gear, "Ключи и сервисы", "в Flick TV  ›", v -> {
            close();
            try {
                a.startActivity(Flick.flickKeys());
            } catch (RuntimeException e) {
                Toast.makeText(a, "Flick TV не установлен", Toast.LENGTH_SHORT).show();
            }
        });
        plate(list, R.drawable.g_refresh, "Обновить данные", "тревоги, погода, Smart Life", v -> {
            a.background(() -> Flick.refresh(a));
            Toast.makeText(a, "Обновляю", Toast.LENGTH_SHORT).show();
        });

        section(list, "Телевизор");
        plate(list, R.drawable.qp_picture, "Изображение", "›", v -> settings(new Intent().setComponent(
                new ComponentName("com.android.tv.settings", "com.android.tv.settings.partnercustomizer.picture.PictureActivity"))));
        plate(list, R.drawable.qp_volume, "Звук", "›", v -> settings(new Intent(Settings.ACTION_SOUND_SETTINGS)));
        plate(list, R.drawable.qp_wifi, "Сеть", "›", v -> settings(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        plate(list, R.drawable.qp_tv, "Все настройки", "›", v -> settings(new Intent(Settings.ACTION_SETTINGS)));
        s.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1.25f));
        return s;
    }

    @Override
    void opened() {
        a.post(() -> {
            if (first != null) first.requestFocus();
        });
    }

    private void section(LinearLayout list, String name) {
        list.addView(ui.text(name, 10, Ui.FAINT, false), ui.lp(-2, -2, 10, list.getChildCount() == 0 ? 0 : 18, 0, 8));
    }

    private View plate(LinearLayout list, int icon, String title, String value, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(ui.px(14), 0, ui.px(16), 0);
        FrameLayout ib = new FrameLayout(a);
        ib.setBackground(ui.round(0xFF3A3A3C, 7));
        ImageView i = ui.icon(icon, Ui.WHITE);
        i.setPadding(ui.px(4), ui.px(4), ui.px(4), ui.px(4));
        ib.addView(i, new FrameLayout.LayoutParams(-1, -1));
        r.addView(ib, ui.lp(26, 26, 0, 0, 12, 0));
        r.addView(ui.line(title, 12.5f, Ui.WHITE, false), new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(ui.line(value, 11, Ui.SUB, false));
        ui.plate(r, 12, click);
        list.addView(r, ui.lp(-1, 46, 0, 0, 0, 8));
        return r;
    }

    private static TextView value(View plate) {
        LinearLayout r = (LinearLayout) plate;
        return (TextView) r.getChildAt(r.getChildCount() - 1);
    }

    private void settings(Intent i) {
        close();
        try {
            a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            a.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private String version() {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}
