package ua.volkov.dim;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.Toast;

/**
 * Flick TV's look (theme, film backdrop, Google TV panel, TV settings) for the settings sheet. Keys and
 * services (Must, TMDB, alerts, Tuya, Telegram) are entered in the Control Center's Settings.
 */
final class AppSettings {
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

    /** The look of Home only: theme, the film backdrop, the Google TV panel, the TV's own settings. */
    static void visual(Activity a, Runnable onChanged) {
        boolean light = light(a);
        boolean art = backdropOn(a);
        String[] items = {
                light ? "Тема: светлая  →  тёмная" : "Тема: тёмная  →  светлая",
                art ? "Фон с постером фильма: включён  →  выключить" : "Фон с постером фильма: выключен  →  включить",
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
                        case 1:
                            Store.prefs(a).edit().putBoolean(Store.BACKDROP, !art).apply();
                            onChanged.run();
                            break;
                        case 2: googlePanel(a); break;
                        case 3:
                            a.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    static boolean backdropOn(android.content.Context c) {
        return Store.prefs(c).getBoolean(Store.BACKDROP, true);
    }

    static void googlePanel(Activity a) {
        try {
            a.startActivity(new Intent().setComponent(new ComponentName(HomeRedirectService.STOCK,
                    HomeRedirectService.STOCK + ".dashboard.DashboardHandler")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(a, "Удерживай кнопку Home на пульте", Toast.LENGTH_LONG).show();
        }
    }
}
