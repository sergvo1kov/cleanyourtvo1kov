package ua.volkov.dim;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Flick TV and Flick TV Glass each have their own Home service; the chosen app's one is switched on, so
 * either works with the other removed. Swapping needs WRITE_SECURE_SETTINGS, granted once over ADB:
 * adb shell pm grant ua.volkov.dim android.permission.WRITE_SECURE_SETTINGS
 * Without it Flick TV's service keeps sending Home to whichever screen HOME_TARGET names.
 */
final class HomeSwitch {
    static final String FLICK = "ua.volkov.dim/.HomeRedirectService";
    static final String GLASS = "ua.volkov.glass/.HomeRedirectService";

    private HomeSwitch() {}

    static void choose(Context ctx, String target) {
        boolean glass = "glass".equals(target) && glassInstalled(ctx);
        Store.prefs(ctx).edit().putString(Store.HOME_TARGET, glass ? "glass" : "flick").apply();
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return;
        String s = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        List<String> next = new ArrayList<>();
        if (s != null) {
            for (String p : s.split(":")) {
                p = p.trim();
                if (p.isEmpty() || p.startsWith("ua.volkov.glass/") || p.equals(FLICK)
                        || p.equals("ua.volkov.dim/ua.volkov.dim.HomeRedirectService")) continue;
                next.add(p);
            }
        }
        next.add(glass ? GLASS : FLICK);
        try {
            Settings.Secure.putString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, TextUtils.join(":", next));
            Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (RuntimeException ignored) {
            // the grant was taken back: HOME_TARGET above still points Flick TV's service the right way
        }
    }

    private static boolean glassInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(DataProvider.GLASS, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
