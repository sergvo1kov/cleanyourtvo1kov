package ua.volkov.glass;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Which home screen the Home button opens. Each app has its own Home service; the chosen app's service
 * is the one switched on, so either works with the other removed. Swapping the services needs
 * WRITE_SECURE_SETTINGS, granted once over ADB:
 * adb shell pm grant ua.volkov.glass android.permission.WRITE_SECURE_SETTINGS
 * Without it Glass falls back to asking Flick TV's service to send Home here.
 */
final class Home {
    static final String GLASS = "ua.volkov.glass/.HomeRedirectService";
    static final String FLICK = "ua.volkov.dim/.HomeRedirectService";

    private Home() {}

    static boolean canSwap(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    private static List<String> services(Context ctx) {
        String s = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        List<String> out = new ArrayList<>();
        if (s != null) for (String p : s.split(":")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    /** True when Glass's own Home service is the one switched on. */
    static boolean ours(Context ctx) {
        for (String s : services(ctx)) if (s.equals(GLASS) || s.equals("ua.volkov.glass/ua.volkov.glass.HomeRedirectService")) return true;
        return false;
    }

    /** "glass" or "flick". Off the main thread: Flick TV is asked through its provider. */
    static void choose(Context ctx, String target) {
        boolean glass = !"flick".equals(target) || !Flick.installed(ctx);
        if (canSwap(ctx)) {
            List<String> list = services(ctx);
            List<String> next = new ArrayList<>();
            for (String s : list) if (!s.startsWith("ua.volkov.glass/") && !s.startsWith("ua.volkov.dim/.Home")
                    && !s.equals("ua.volkov.dim/ua.volkov.dim.HomeRedirectService")) next.add(s);
            next.add(glass ? GLASS : FLICK);
            if (!next.equals(list)) {
                try {
                    Settings.Secure.putString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                            TextUtils.join(":", next));
                    Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                } catch (RuntimeException ignored) {
                    // the grant was taken back: the fallback below still points Flick TV the right way
                }
            }
        }
        // Flick TV's own service (when it is the one on) follows the choice too
        if (Flick.installed(ctx)) Flick.setHome(ctx, glass ? "glass" : "flick");
    }

    static void chooseAsync(Context ctx, String target) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> choose(app, target)).start();
    }

    /** After a crash: Home must not keep opening a broken screen. Flick TV if it is there, else Google TV. */
    static void fallback(Context ctx) {
        if (Flick.installed(ctx)) {
            choose(ctx, "flick");
        } else if (canSwap(ctx)) {
            List<String> next = new ArrayList<>();
            for (String s : services(ctx)) if (!s.startsWith("ua.volkov.glass/")) next.add(s);
            Settings.Secure.putString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, TextUtils.join(":", next));
        }
    }
}
