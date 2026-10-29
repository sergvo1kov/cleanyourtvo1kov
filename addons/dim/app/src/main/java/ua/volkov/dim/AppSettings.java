package ua.volkov.dim;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

/**
 * Flick TV's own settings (theme, Must, TMDB key, Google TV panel, TV settings), one list shared by
 * the Home screen and the Control Center, so both show the same items.
 */
final class AppSettings {
    interface Keys {
        /** The Control Center's form: alerts token, Tuya keys, Telegram channels. */
        void openKeys();
    }

    private AppSettings() {}

    static boolean light(Activity a) {
        return "light".equals(Store.prefs(a).getString(Store.THEME, "dark"));
    }

    static int dialogTheme(Activity a) {
        return light(a) ? android.R.style.Theme_Material_Light_Dialog_Alert : android.R.style.Theme_Material_Dialog_Alert;
    }

    static String version(Activity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** onChanged runs after anything that changes what Home shows (theme, Must, TMDB). */
    static void show(Activity a, Keys keys, Runnable onChanged) {
        boolean light = light(a);
        String nick = Store.str(a, Store.MUST_NICK);
        String[] items = {
                light ? "Тема: светлая  →  тёмная" : "Тема: тёмная  →  светлая",
                "Must: " + (nick.isEmpty() ? "указать ник" : "@" + nick),
                "Тревоги, Smart Life, Telegram",
                "Lampa: " + (Store.str(a, Store.TMDB_KEY).isEmpty() ? "ключ TMDB для открытия в один клик" : "ключ TMDB указан ✓"),
                "Панель Google TV (Google Home)",
                "Настройки телевизора"};
        new AlertDialog.Builder(a, dialogTheme(a))
                .setTitle("Flick TV " + version(a) + "  ·  Serhii Volkov (@sergvo1kov)")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            Store.prefs(a).edit().putString(Store.THEME, light ? "dark" : "light").apply();
                            onChanged.run();
                            break;
                        case 1: askMust(a, onChanged); break;
                        case 2: keys.openKeys(); break;
                        case 3: askTmdb(a, onChanged); break;
                        case 4: googlePanel(a); break;
                        default:
                            a.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                })
                .show();
    }

    static void askMust(Activity a, Runnable onChanged) {
        EditText e = field(a, Store.MUST_NICK, "ник, например sergvo1kov");
        new AlertDialog.Builder(a, dialogTheme(a))
                .setTitle("Must: ник профиля")
                .setMessage("Профиль должен быть открытым. Показываем «похоже на то, что ты смотрел».")
                .setView(e)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String nick = e.getText().toString().trim().replace("@", "").replace("https://mustapp.com/", "");
                    Store.prefs(a).edit().putString(Store.MUST_NICK, nick).putLong(Store.MUST_AT, 0).apply();
                    onChanged.run();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    static void askTmdb(Activity a, Runnable onChanged) {
        EditText e = field(a, Store.TMDB_KEY, "API key или Read Access Token");
        new AlertDialog.Builder(a, dialogTheme(a))
                .setTitle("Lampa: ключ TMDB")
                .setMessage("Бесплатный ключ: themoviedb.org → Настройки → API. С ним постер открывает фильм в Lampa сразу, "
                        + "а в «Для тебя» появляются свежие фильмы.")
                .setView(e)
                .setPositiveButton("Сохранить", (d, w) -> {
                    Store.prefs(a).edit().putString(Store.TMDB_KEY, e.getText().toString().trim())
                            .remove("lampa_ids").putLong(Store.FRESH_AT, 0).apply();
                    onChanged.run();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    static void googlePanel(Activity a) {
        try {
            a.startActivity(new Intent().setComponent(new ComponentName(HomeRedirectService.STOCK,
                    HomeRedirectService.STOCK + ".dashboard.DashboardHandler")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(a, "Удерживай кнопку Home на пульте", Toast.LENGTH_LONG).show();
        }
    }

    private static EditText field(Activity a, String key, String hint) {
        EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setText(Store.str(a, key));
        e.setHint(hint);
        return e;
    }
}
