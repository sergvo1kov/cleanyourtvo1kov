package ua.volkov.dim;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Updates over the air from the fork's GitHub releases: Flick TV (tags v*) and AirPlay (tags airplay-v*).
 * It reads the public release list, compares versions, downloads the APK over HTTPS and hands it to
 * Android's installer, which asks once on screen and refuses anything not signed like the installed app.
 * Nothing runs in the background: a check happens when asked, or at most every 12 hours when Home opens.
 */
final class Updater {
    private static final String RELEASES = "https://api.github.com/repos/sergvo1kov/cleanyourtvo1kov/releases?per_page=100";
    private static final String ACTION = "ua.volkov.dim.UPDATE_STATUS";
    private static final String CHECKED_AT = "update_checked_at";
    private static final long EVERY_MS = 12 * 3600_000L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** One app this updater looks after. */
    static final class Target {
        final String name, pkg, tagPrefix, assetPrefix;
        String installed, latest, url;

        Target(String name, String pkg, String tagPrefix, String assetPrefix) {
            this.name = name;
            this.pkg = pkg;
            this.tagPrefix = tagPrefix;
            this.assetPrefix = assetPrefix;
        }

        boolean newer() {
            return installed != null && latest != null && url != null && compare(latest, installed) > 0;
        }
    }

    private Updater() {}

    private static List<Target> targets() {
        List<Target> t = new ArrayList<>();
        t.add(new Target("Flick TV", "ua.volkov.dim", "v", "flick-tv-"));
        t.add(new Target("AirPlay", "ua.volkov.airplay", "airplay-v", "flick-airplay-"));
        return t;
    }

    /** "3.10" > "3.9": compares the dotted numbers one by one. */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Blocking: fills installed and latest versions. Call off the main thread. */
    private static List<Target> look(Context ctx) throws Exception {
        List<Target> out = targets();
        PackageManager pm = ctx.getPackageManager();
        for (Target t : out) {
            try {
                t.installed = pm.getPackageInfo(t.pkg, 0).versionName;
            } catch (PackageManager.NameNotFoundException e) {
                t.installed = null;
            }
        }
        java.util.Map<String, String> h = new java.util.HashMap<>();
        h.put("Accept", "application/vnd.github+json");
        Http.Response r = Http.get(RELEASES, h, 15_000);
        if (r.code != 200) throw new Exception("GitHub ответил " + r.code);
        JSONArray rel = new JSONArray(r.body);
        for (Target t : out) {
            for (int i = 0; i < rel.length(); i++) {
                JSONObject o = rel.getJSONObject(i);
                if (o.optBoolean("draft") || o.optBoolean("prerelease")) continue;
                String tag = o.optString("tag_name");
                if (!tag.startsWith(t.tagPrefix)) continue;
                String ver = tag.substring(t.tagPrefix.length());
                if (ver.isEmpty() || !Character.isDigit(ver.charAt(0))) continue;   // "v" must be followed by a number
                String apk = null;
                JSONArray as = o.optJSONArray("assets");
                for (int k = 0; as != null && k < as.length(); k++) {
                    String n = as.getJSONObject(k).optString("name");
                    if (n.startsWith(t.assetPrefix) && n.endsWith(".apk")) apk = as.getJSONObject(k).optString("browser_download_url");
                }
                if (apk == null) continue;
                if (t.latest == null || compare(ver, t.latest) > 0) {
                    t.latest = ver;
                    t.url = apk;
                }
            }
        }
        Store.prefs(ctx).edit().putLong(CHECKED_AT, System.currentTimeMillis()).apply();
        return out;
    }

    /** When Home opens: at most every 12 hours, a quiet check; a toast only when something is newer. */
    static void autoCheck(Activity a) {
        long at = Store.prefs(a).getLong(CHECKED_AT, 0);
        if (System.currentTimeMillis() - at < EVERY_MS) return;
        Context app = a.getApplicationContext();
        IO.execute(() -> {
            try {
                List<Target> ts = look(app);
                StringBuilder s = new StringBuilder();
                for (Target t : ts) if (t.newer()) s.append(s.length() == 0 ? "" : ", ").append(t.name).append(' ').append(t.latest);
                if (s.length() > 0) {
                    MAIN.post(() -> Toast.makeText(app, "Доступно обновление: " + s + ".  Настройки → Flick TV → Обновления",
                            Toast.LENGTH_LONG).show());
                }
            } catch (Exception ignored) {
                // no network now: the next Home tries again after 12 hours
            }
        });
    }

    /** The Updates dialog: each app with its version, OK on one with an update installs it. */
    static void open(Activity a, int theme) {
        Toast.makeText(a, "Проверяю обновления…", Toast.LENGTH_SHORT).show();
        IO.execute(() -> {
            try {
                List<Target> ts = look(a.getApplicationContext());
                MAIN.post(() -> show(a, theme, ts));
            } catch (Exception e) {
                MAIN.post(() -> Toast.makeText(a, "Не удалось проверить: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private static void show(Activity a, int theme, List<Target> ts) {
        if (a.isFinishing()) return;
        String[] items = new String[ts.size()];
        for (int i = 0; i < ts.size(); i++) {
            Target t = ts.get(i);
            if (t.installed == null) items[i] = t.name + ":  не установлен" + (t.latest != null ? "  →  установить " + t.latest : "");
            else if (t.newer()) items[i] = t.name + " " + t.installed + "  →  обновить до " + t.latest;
            else items[i] = t.name + " " + t.installed + "  ·  последняя версия";
        }
        new AlertDialog.Builder(a, theme)
                .setTitle("Обновления")
                .setItems(items, (d, w) -> {
                    Target t = ts.get(w);
                    if ((t.installed == null && t.url != null) || t.newer()) install(a, t);
                })
                .show();
    }

    private static void install(Activity a, Target t) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, "Разреши Flick TV устанавливать приложения, затем нажми «Обновить» ещё раз",
                    Toast.LENGTH_LONG).show();
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                Toast.makeText(a, "Настройки → Приложения → Специальный доступ → Установка неизвестных приложений → Flick TV",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        Toast.makeText(a, "Скачиваю " + t.name + " " + t.latest + "…", Toast.LENGTH_SHORT).show();
        Context app = a.getApplicationContext();
        IO.execute(() -> {
            try {
                PackageInstaller pi = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(t.pkg);
                int id = pi.createSession(sp);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    HttpURLConnection c = open(t.url);
                    long len = c.getContentLengthLong();
                    try (InputStream in = c.getInputStream(); OutputStream out = s.openWrite("apk", 0, len > 0 ? len : -1)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        s.fsync(out);
                    } finally {
                        c.disconnect();
                    }
                    MAIN.post(() -> listen(app));
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent pend = PendingIntent.getBroadcast(app, id, new Intent(ACTION).setPackage(app.getPackageName()), flags);
                    s.commit(pend.getIntentSender());
                }
            } catch (Exception e) {
                MAIN.post(() -> Toast.makeText(app, "Обновление не удалось: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** GitHub's download link redirects to its file storage; follow it by hand, HTTPS only. */
    private static HttpURLConnection open(String url) throws Exception {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            c.setRequestProperty("User-Agent", "FlickTV-Updater");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String next = c.getHeaderField("Location");
                c.disconnect();
                if (next == null || !next.startsWith("https://")) throw new Exception("плохая ссылка");
                url = next;
                continue;
            }
            if (code != 200) throw new Exception("загрузка " + code);
            return c;
        }
        throw new Exception("слишком много переходов");
    }

    private static boolean listening;

    /** The installer's answer: ask the person to confirm, or say how it went. */
    private static void listen(Context app) {
        if (listening) return;
        listening = true;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } else if (st == PackageInstaller.STATUS_SUCCESS) {
                    Toast.makeText(c, "Обновлено", Toast.LENGTH_SHORT).show();
                } else {
                    String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    Toast.makeText(c, "Установка не удалась" + (msg == null ? "" : ": " + msg), Toast.LENGTH_LONG).show();
                }
            }
        };
        IntentFilter f = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(r, f);
    }
}
