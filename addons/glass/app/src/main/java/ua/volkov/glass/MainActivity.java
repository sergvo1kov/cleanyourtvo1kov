package ua.volkov.glass;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Flick TV Glass: a tvOS-style home. The focused film fills the screen behind its title and synopsis,
 * a frosted dock of favourite apps sits at the bottom, all apps open below it, weather and the clock
 * with the alert state sit in capsules in the top corners. Films, alerts and Smart Life come from Flick
 * TV (see Flick); OK on a film opens it in Lampa.
 */
public final class MainActivity extends Activity {
    static final String LAMPA = "top.rootu.lamps";
    private static final int MAX_DOCK = 6;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private Ui ui;
    private SharedPreferences prefs;

    private FrameLayout root;
    private final ImageView[] art = new ImageView[2];
    private ImageView soft;
    private int front;
    private View dim;
    private LinearLayout info;
    private TextView title, meta, overview, badge;
    private LinearLayout dots;
    private View stageFocus;
    private FrameLayout dockBand;
    private LinearLayout dock;
    private ScrollView appsPage;
    private LinearLayout appsGrid;
    private TextView weatherCap, statusCap, hint;
    private boolean appsShown;

    private JSONArray feed = new JSONArray();
    private int idx;
    private int feedSeed;
    private long feedSeedAt = System.currentTimeMillis();
    private String shownArt = "";
    private final Map<String, JSONObject> details = new HashMap<>();
    JSONObject status = new JSONObject();

    private ControlCenter control;
    private WeatherPanel weather;
    private SettingsPanel settings;

    private final SimpleDateFormat clock = new SimpleDateFormat("H:mm", Locale.getDefault());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            renderCapsules();
            main.postDelayed(this, 1000);
        }
    };
    private final Runnable slow = new Runnable() {
        @Override
        public void run() {
            loadStatus();
            main.postDelayed(this, 60_000);
        }
    };
    private final Runnable feedTick = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() - feedSeedAt > 40 * 60_000L) {
                feedSeed++;
                feedSeedAt = System.currentTimeMillis();
            }
            loadFeed();
            main.postDelayed(this, 5 * 60_000);
        }
    };
    /** The stage moves on by itself every nine seconds while nobody is choosing a film. */
    private final Runnable advance = new Runnable() {
        @Override
        public void run() {
            if (!appsShown && !panelOpen() && feed.length() > 1 && !stageFocus.isFocused()
                    && prefs.getBoolean("autoplay", true)) {
                show((idx + 1) % feed.length());
            }
            main.postDelayed(this, 9000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // a crash must never leave the Home button pointing at a broken home screen
        Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Context app = getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                Flick.setHome(app, "flick");
            } catch (Throwable ignored) {
                // nothing more to do
            }
            if (old != null) old.uncaughtException(t, e);
        });
        ui = new Ui(this);
        prefs = getSharedPreferences("glass", MODE_PRIVATE);
        Images.init(this);
        setContentView(build());
        control = new ControlCenter(this, root, ui);
        weather = new WeatherPanel(this, root, ui);
        settings = new SettingsPanel(this, root, ui);
        renderDock();
        if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Flick.setHomeAsync(this, "glass");
        main.post(tick);
        main.post(slow);
        main.post(feedTick);
        main.postDelayed(advance, 9000);
        renderDock();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
        main.removeCallbacks(slow);
        main.removeCallbacks(feedTick);
        main.removeCallbacks(advance);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed on Home: back to the first screen and the first app in the dock
        closePanels();
        showApps(false);
        if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (panelOpen()) closePanels();
        else if (appsShown) showApps(false);
        // a home screen has nowhere to go back to
    }

    boolean panelOpen() {
        return (control != null && control.isOpen()) || (weather != null && weather.isOpen())
                || (settings != null && settings.isOpen());
    }

    void closePanels() {
        if (control != null) control.close();
        if (weather != null) weather.close();
        if (settings != null) settings.close();
    }

    // ------------------------------------------------------------------ layout

    private View build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        for (int i = 0; i < 2; i++) {
            art[i] = new ImageView(this);
            art[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            art[i].setAlpha(0f);
            root.addView(art[i], new FrameLayout.LayoutParams(-1, -1));
        }
        soft = new ImageView(this);
        soft.setScaleType(ImageView.ScaleType.CENTER_CROP);
        soft.setAlpha(0f);
        root.addView(soft, new FrameLayout.LayoutParams(-1, -1));
        // shades: the text side and the dock side stay readable over any picture
        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xD9000000, 0x80000000, 0x10000000, 0x00000000}));
        root.addView(left, new FrameLayout.LayoutParams(-1, -1));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x66000000, 0x00000000, 0x00000000}));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -1));
        View top = new View(this);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x00000000, 0x00000000, 0x00000000}));
        root.addView(top, new FrameLayout.LayoutParams(-1, -1));
        dim = new View(this);
        dim.setBackgroundColor(0xB3000000);
        dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        // the film: title, a line of facts, the synopsis, carousel dots
        info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        badge = ui.text("", 9.5f, 0xFFFFFFFF, true);
        badge.setPadding(ui.px(7), ui.px(3), ui.px(7), ui.px(3));
        badge.setVisibility(View.GONE);
        info.addView(badge, ui.lp(-2, -2, 0, 0, 0, 8));
        title = ui.text("", 32, Ui.WHITE, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title);
        meta = ui.line("", 12, 0xD9FFFFFF, false);
        info.addView(meta, ui.lp(-2, -2, 0, 8, 0, 0));
        overview = ui.text("", 11.5f, 0xBFFFFFFF, false);
        overview.setMaxLines(3);
        overview.setEllipsize(TextUtils.TruncateAt.END);
        overview.setLineSpacing(0, 1.15f);
        info.addView(overview, ui.lp(-1, -2, 0, 10, 0, 0));
        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        info.addView(dots, ui.lp(-2, -2, 0, 16, 0, 0));
        hint = ui.line("◀ ▶  другие фильмы     OK  смотреть в Lampa", 10, Ui.FAINT, false);
        hint.setAlpha(0f);
        info.addView(hint, ui.lp(-2, -2, 0, 10, 0, 0));
        root.addView(info, ui.flp(560, -2, Gravity.START | Gravity.BOTTOM, 64, 0, 0, 168));

        // an invisible stop for the remote over the film: Up from the dock lands here
        stageFocus = new View(this);
        stageFocus.setFocusable(true);
        stageFocus.setOnFocusChangeListener((v, has) -> {
            hint.animate().alpha(has ? 1f : 0f).setDuration(200).start();
            title.animate().scaleX(has ? 1.02f : 1f).scaleY(has ? 1.02f : 1f).setDuration(200).start();
            title.setPivotX(0);
        });
        stageFocus.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN || feed.length() == 0) return false;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                show((idx + 1) % feed.length());
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                show((idx - 1 + feed.length()) % feed.length());
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                openFilm(feed.optJSONObject(idx));
                return true;
            }
            return false;
        });
        root.addView(stageFocus, ui.flp(560, 200, Gravity.START | Gravity.BOTTOM, 64, 0, 0, 168));

        // capsules: weather on the left, the clock with the alert state on the right
        weatherCap = ui.text("", 12, Ui.WHITE, true);
        weatherCap.setGravity(Gravity.CENTER);
        capsule(weatherCap, v -> weather.open());
        root.addView(weatherCap, ui.flp(-2, 40, Gravity.START | Gravity.TOP, 48, 32, 0, 0));
        weatherCap.setMinWidth(ui.px(40));
        statusCap = ui.text("", 12, Ui.WHITE, true);
        statusCap.setGravity(Gravity.CENTER);
        capsule(statusCap, v -> control.open());
        root.addView(statusCap, ui.flp(-2, 40, Gravity.END | Gravity.TOP, 0, 32, 48, 0));

        // all apps, under the dock when it moves up
        appsPage = new ScrollView(this);
        appsPage.setVerticalScrollBarEnabled(false);
        appsPage.setClipToPadding(false);
        appsPage.setAlpha(0f);
        appsPage.setVisibility(View.GONE);
        appsGrid = new LinearLayout(this);
        appsGrid.setOrientation(LinearLayout.VERTICAL);
        appsGrid.setClipChildren(false);
        appsGrid.setPadding(ui.px(8), ui.px(8), ui.px(8), ui.px(40));
        appsPage.addView(appsGrid);
        root.addView(appsPage, ui.flp(-1, -1, Gravity.TOP, 56, 150, 56, 0));

        // the frosted dock
        dockBand = new FrameLayout(this);
        dockBand.setBackground(ui.glass(26));
        dockBand.setPadding(ui.px(12), ui.px(10), ui.px(12), ui.px(10));
        dockBand.setClipChildren(false);
        dockBand.setClipToPadding(false);
        dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dockBand.addView(dock, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        root.addView(dockBand, ui.flp(-1, -2, Gravity.BOTTOM, 56, 0, 56, 36));
        root.setClipChildren(false);
        return root;
    }

    private void capsule(TextView t, View.OnClickListener click) {
        GradientDrawable g = ui.glass(20);
        t.setBackground(g);
        t.setPadding(ui.px(14), 0, ui.px(14), 0);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(click);
        t.setOnFocusChangeListener((v, has) -> {
            g.setColors(has ? new int[]{0xF2FFFFFF, 0xF2FFFFFF} : new int[]{0x38FFFFFF, 0x1AFFFFFF});
            ((TextView) v).setTextColor(has ? Ui.GRAPHITE : Ui.WHITE);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(160).start();
        });
    }

    // ------------------------------------------------------------------ the film stage

    private void loadFeed() {
        Context app = getApplicationContext();
        int seed = feedSeed;
        io.execute(() -> {
            JSONArray f = Flick.feed(app, seed);
            main.post(() -> {
                if (f.length() == 0) {
                    if (feed.length() == 0) showEmpty();
                    return;
                }
                boolean first = feed.length() == 0 || !f.toString().equals(feed.toString());
                feed = f;
                if (first) show(Math.min(idx, f.length() - 1));
                for (int i = 0; i < Math.min(6, f.length()); i++) Images.prefetch(f.optJSONObject(i).optString("backdrop"));
            });
        });
    }

    private void showEmpty() {
        title.setText(Flick.installed(this) ? "Для тебя" : "Нужен Flick TV");
        meta.setText(Flick.installed(this) ? "Фильмы появятся, когда Flick TV загрузит Must, Lampa и новинки"
                : "Glass берёт фильмы, тревоги и Smart Life из Flick TV");
        overview.setText("");
    }

    void show(int i) {
        if (feed.length() == 0) return;
        idx = i;
        JSONObject film = feed.optJSONObject(i);
        if (film == null) return;
        title.setText(film.optString("title"));
        String[] cat = category(film);
        badge.setText(cat[0]);
        badge.setBackground(ui.round((int) Long.parseLong(cat[1], 16), 5));
        badge.setVisibility(View.VISIBLE);
        renderDots();
        JSONObject d = details.get(key(film));
        renderFacts(film, d);
        String back = d != null && !d.optString("backdrop").isEmpty() ? d.optString("backdrop")
                : !film.optString("backdrop").isEmpty() ? film.optString("backdrop") : film.optString("poster");
        setArt(back);
        if (d == null) {
            Context app = getApplicationContext();
            io.execute(() -> {
                JSONObject got = Flick.details(app, film);
                main.post(() -> {
                    details.put(key(film), got);
                    if (feed.optJSONObject(idx) == film) {
                        renderFacts(film, got);
                        if (!got.optString("backdrop").isEmpty()) setArt(got.optString("backdrop"));
                    }
                });
            });
        }
    }

    private static String key(JSONObject film) {
        return film.optString("title") + "|" + film.optString("year");
    }

    private void renderFacts(JSONObject film, JSONObject d) {
        StringBuilder m = new StringBuilder();
        String year = d != null && !d.optString("year").isEmpty() ? d.optString("year") : film.optString("year");
        if (!year.isEmpty()) m.append(year);
        if (d != null && !d.optString("genres").isEmpty()) m.append(m.length() > 0 ? "  ·  " : "").append(d.optString("genres"));
        if (d != null && d.optDouble("vote", 0) > 0) {
            m.append(m.length() > 0 ? "  ·  " : "").append("★ ").append(String.format(Locale.ROOT, "%.1f", d.optDouble("vote"))).append(" TMDB");
        }
        meta.setText(m.toString());
        overview.setText(d == null ? "" : d.optString("overview"));
    }

    private void renderDots() {
        dots.removeAllViews();
        int n = Math.min(feed.length(), 14);
        int first = Math.max(0, Math.min(idx - n / 2, feed.length() - n));
        for (int k = 0; k < n; k++) {
            View dot = new View(this);
            boolean on = first + k == idx;
            dot.setBackground(ui.round(on ? 0xF2FFFFFF : 0x59FFFFFF, 3));
            dots.addView(dot, ui.lp(on ? 16 : 6, 6, 0, 0, 6, 0));
        }
    }

    /** The picture behind everything changes through a soft blur: sharp, soft, sharp again. */
    private void setArt(String url) {
        if (url == null || url.isEmpty() || url.equals(shownArt)) return;
        shownArt = url;
        Images.get(url, b -> {
            if (b == null || !url.equals(shownArt)) return;
            ImageView out = art[front];
            front = 1 - front;
            ImageView in = art[front];
            boolean first = out.getAlpha() < 0.05f;
            soft.animate().cancel();
            in.animate().cancel();
            soft.setImageBitmap(Ui.blur(b, 40));
            Runnable reveal = () -> {
                in.setImageBitmap(b);
                in.setAlpha(1f);
                in.setScaleX(1.05f);
                in.setScaleY(1.05f);
                out.setAlpha(0f);
                in.animate().scaleX(1f).scaleY(1f).setDuration(9000).setInterpolator(new DecelerateInterpolator()).start();
                soft.animate().alpha(0f).setDuration(600).setInterpolator(new DecelerateInterpolator()).start();
            };
            if (first) {
                soft.setAlpha(1f);
                reveal.run();
            } else {
                soft.animate().alpha(1f).setDuration(260).withEndAction(reveal).start();
            }
        });
    }

    /** {badge, colour (hex ARGB)} — the same five reasons as on Flick TV's row. */
    static String[] category(JSONObject o) {
        String src = o.optString("src");
        if ("lampa".equals(src) && "Продолжить".equals(o.optString("label"))) return new String[]{"Продолжить", "E62E7DFF"};
        if ("lampa".equals(src)) return new String[]{"От Lampa", "E68E5CF7"};
        if ("fresh".equals(src)) return new String[]{"Новинка", "E6E5484D"};
        if ("Хочу посмотреть".equals(o.optString("label"))) return new String[]{"Хочу посмотреть", "E628A55F"};
        return new String[]{"Похожее", "E6C77D12"};
    }

    /** One press: the film's own Lampa link, else its TMDB card in Lampa, else Lampa's search. */
    private void openFilm(JSONObject film) {
        if (film == null) return;
        String intent = film.optString("intent");
        if (!intent.isEmpty()) {
            try {
                startActivity(Intent.parseUri(intent, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {
                // try the TMDB card
            }
        }
        Toast.makeText(this, "Открываю в Lampa: " + film.optString("title"), Toast.LENGTH_SHORT).show();
        Context app = getApplicationContext();
        io.execute(() -> {
            JSONObject d = details.get(key(film));
            String id = d != null ? d.optString("tmdb") : film.optString("tmdb");
            String media = d != null ? d.optString("media") : film.optString("media");
            if (id.isEmpty()) {
                JSONObject f = Flick.find(app, film);
                id = f.optString("tmdb");
                media = f.optString("media");
            }
            final String tid = id, tm = media;
            main.post(() -> {
                try {
                    if (!tid.isEmpty()) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org/" + tm + "/" + tid))
                                .setPackage(LAMPA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } else {
                        startActivity(new Intent(Intent.ACTION_SEARCH).setPackage(LAMPA)
                                .putExtra(SearchManager.QUERY, film.optString("title")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Lampa не установлена", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // ------------------------------------------------------------------ capsules and status

    private void loadStatus() {
        Context app = getApplicationContext();
        io.execute(() -> {
            JSONObject s = Flick.status(app);
            main.post(() -> {
                status = s;
                renderCapsules();
                if (control.isOpen()) control.render();
            });
        });
    }

    private void renderCapsules() {
        JSONObject w = status.optJSONObject("weather");
        String wt = w == null || w.optLong("at") == 0 ? "☁" : Ui.glyph(w.optInt("code"), w.optBoolean("day", true)) + "  " + Ui.deg(w.optInt("temp"));
        if (!TextUtils.equals(weatherCap.getText(), wt)) weatherCap.setText(wt);
        JSONObject a = status.optJSONObject("alert");
        int state = a == null ? -1 : a.optInt("state", -1);
        String dot = state == 1 ? "🔴" : state == 2 ? "🟠" : state == 0 ? "🟢" : "⚪";
        String st = clock.format(new Date()) + "   " + dot;
        if (!TextUtils.equals(statusCap.getText(), st)) statusCap.setText(st);
    }

    // ------------------------------------------------------------------ dock and apps

    private List<String> dockApps() {
        String saved = prefs.getString("dock", null);
        List<String> out = new ArrayList<>();
        if (saved != null) {
            for (String p : saved.split(",")) if (!p.isEmpty() && launchIntent(p) != null) out.add(p);
            return out;
        }
        String[] first = {LAMPA, "com.google.android.youtube.tv", "com.google.android.youtube.tvmusic",
                "com.apple.atve.androidtv.appletv", "ru.yourok.torrserve", "com.netflix.ninja"};
        for (String p : first) if (launchIntent(p) != null && out.size() < MAX_DOCK) out.add(p);
        return out;
    }

    private void saveDock(List<String> apps) {
        prefs.edit().putString("dock", TextUtils.join(",", apps)).apply();
    }

    private void renderDock() {
        View had = dock.findFocus();
        int at = had == null ? -1 : dock.indexOfChild(had);
        dock.removeAllViews();
        List<String> apps = dockApps();
        int page = getResources().getDisplayMetrics().widthPixels - ui.px(112) - ui.px(24);
        int gap = ui.px(16);
        int slots = MAX_DOCK + 1;
        int w = (page - (slots - 1) * gap) / slots, h = Math.round(w * 9f / 16f);
        for (int i = 0; i < apps.size(); i++) {
            final String pkg = apps.get(i);
            View tile = appTile(pkg, w, h);
            tile.setOnClickListener(v -> launch(pkg));
            tile.setOnLongClickListener(v -> {
                dockMenu(pkg);
                return true;
            });
            tile.setOnKeyListener((v, code, e) -> {
                if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN && !appsShown) {
                    showApps(true);
                    return true;
                }
                return false;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.setMargins(0, 0, gap, 0);
            dock.addView(tile, lp);
        }
        // the last tile: settings
        FrameLayout gear = new FrameLayout(this);
        gear.setBackground(ui.round(0xFF3A3A3C, 14));
        ImageView gi = ui.icon(R.drawable.qp_gear, 0xFFD1D1D6);
        int pad = Math.round(h * 0.22f);
        gi.setPadding(pad, pad, pad, pad);
        gear.addView(gi, new FrameLayout.LayoutParams(-1, -1));
        focusable(gear, v -> settings.open());
        dock.addView(gear, new LinearLayout.LayoutParams(w, h));
        if (at >= 0 && at < dock.getChildCount()) dock.getChildAt(at).requestFocus();
    }

    private void dockMenu(String pkg) {
        List<String> apps = dockApps();
        int i = apps.indexOf(pkg);
        String[] items = {"Сдвинуть влево", "Сдвинуть вправо", "Убрать из дока"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(label(pkg))
                .setItems(items, (d, which) -> {
                    if (which == 0 && i > 0) Collections.swap(apps, i, i - 1);
                    else if (which == 1 && i < apps.size() - 1) Collections.swap(apps, i, i + 1);
                    else if (which == 2) apps.remove(pkg);
                    saveDock(apps);
                    renderDock();
                })
                .show();
    }

    private View appTile(String pkg, int w, int h) {
        FrameLayout f = new FrameLayout(this);
        Drawable banner = banner(pkg);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (banner != null) {
            img.setImageDrawable(banner);
            f.setBackground(ui.round(0xFF2C2C2E, 14));
            f.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            f.setBackground(ui.round(0xFF2C2C2E, 14));
            ImageView ic = new ImageView(this);
            try {
                ic.setImageDrawable(getPackageManager().getApplicationIcon(pkg));
            } catch (Exception ignored) {
                // no icon
            }
            f.addView(ic, new FrameLayout.LayoutParams(h * 3 / 5, h * 3 / 5, Gravity.CENTER));
        }
        f.setClipToOutline(true);
        focusable(f, null);
        return f;
    }

    /** Focus as in tvOS: the tile lifts, grows and gets a bright rim. */
    private void focusable(View v, View.OnClickListener click) {
        v.setFocusable(true);
        v.setClickable(true);
        if (click != null) v.setOnClickListener(click);
        GradientDrawable rim = new GradientDrawable();
        rim.setCornerRadius(ui.px(14));
        rim.setColor(0);
        rim.setStroke(ui.px(2), 0);
        if (v instanceof FrameLayout) ((FrameLayout) v).setForeground(rim);
        v.setOnFocusChangeListener((view, has) -> {
            rim.setStroke(ui.px(2), has ? 0xE6FFFFFF : 0);
            view.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).translationZ(has ? ui.px(10) : 0)
                    .setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
        });
    }

    private void showApps(boolean apps) {
        if (apps == appsShown) return;
        appsShown = apps;
        DecelerateInterpolator ease = new DecelerateInterpolator(2f);
        if (apps) {
            renderGrid();
            float up = -(dockBand.getTop() - ui.px(78));
            dockBand.animate().translationY(up).setDuration(380).setInterpolator(ease).start();
            info.animate().alpha(0f).setDuration(200).start();
            dim.animate().alpha(1f).setDuration(300).start();
            appsPage.setVisibility(View.VISIBLE);
            appsPage.setTranslationY(ui.px(40));
            appsPage.animate().alpha(1f).translationY(0f).setDuration(380).setInterpolator(ease).start();
            appsPage.scrollTo(0, 0);
            main.postDelayed(() -> {
                if (appsGrid.getChildCount() > 0) ((ViewGroup) appsGrid.getChildAt(0)).getChildAt(0).requestFocus();
            }, 120);
        } else {
            dockBand.animate().translationY(0f).setDuration(320).setInterpolator(ease).start();
            info.animate().alpha(1f).setDuration(300).start();
            dim.animate().alpha(0f).setDuration(300).start();
            appsPage.animate().alpha(0f).translationY(ui.px(40)).setDuration(220)
                    .withEndAction(() -> appsPage.setVisibility(View.GONE)).start();
            if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
        }
    }

    private void renderGrid() {
        appsGrid.removeAllViews();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<String> pkgs = new ArrayList<>();
        for (ResolveInfo r : getPackageManager().queryIntentActivities(q, 0)) {
            String p = r.activityInfo.packageName;
            if (!pkgs.contains(p) && !p.equals(getPackageName()) && !p.equals("com.gathin.tvhome")) pkgs.add(p);
        }
        Collections.sort(pkgs, (a, b) -> label(a).compareToIgnoreCase(label(b)));
        int page = getResources().getDisplayMetrics().widthPixels - ui.px(112) - ui.px(16);
        int gap = ui.px(16);
        int w = (page - 5 * gap) / 6, h = Math.round(w * 9f / 16f);
        LinearLayout row = null;
        List<String> inDock = dockApps();
        for (int i = 0; i < pkgs.size(); i++) {
            if (i % 6 == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                appsGrid.addView(row, ui.lp(-1, -2, 0, 0, 0, 26));
            }
            final String pkg = pkgs.get(i);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setClipChildren(false);
            View tile = appTile(pkg, w, h);
            tile.setOnClickListener(v -> launch(pkg));
            tile.setOnLongClickListener(v -> {
                List<String> d = dockApps();
                boolean has = d.contains(pkg);
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle(label(pkg))
                        .setItems(new String[]{has ? "Убрать из дока" : "Добавить в док"}, (dd, which) -> {
                            if (has) d.remove(pkg);
                            else if (d.size() < MAX_DOCK) d.add(pkg);
                            else Toast.makeText(this, "В доке уже " + MAX_DOCK + ". Убери одно.", Toast.LENGTH_SHORT).show();
                            saveDock(d);
                            renderDock();
                        })
                        .show();
                return true;
            });
            cell.addView(tile, new LinearLayout.LayoutParams(w, h));
            TextView t = ui.line(label(pkg), 10, inDock.contains(pkg) ? Ui.WHITE : Ui.SUB, false);
            t.setMaxWidth(w);
            cell.addView(t, ui.lp(-2, -2, 2, 8, 0, 0));
            row.addView(cell, ui.lp(-2, -2, 0, 0, i % 6 == 5 ? 0 : 16, 0));
        }
    }

    Intent launchIntent(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }

    void launch(String pkg) {
        Intent i = launchIntent(pkg);
        if (i == null) {
            Toast.makeText(this, "Приложение не установлено", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private final Map<String, String> labels = new HashMap<>();

    String label(String pkg) {
        String l = labels.get(pkg);
        if (l != null) return l;
        try {
            PackageManager pm = getPackageManager();
            l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            l = pkg;
        }
        labels.put(pkg, l);
        return l;
    }

    private Drawable banner(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i != null && i.getComponent() != null) {
                Drawable d = pm.getActivityBanner(i.getComponent());
                if (d != null) return d;
            }
            return pm.getApplicationBanner(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    void openSettings() {
        settings.open();
    }

    /** After a panel closes: the screen's own stops for the remote take focus again. */
    void restoreFocusables() {
        stageFocus.setFocusable(true);
        weatherCap.setFocusable(true);
        statusCap.setFocusable(true);
        if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
    }

    /** For the panels: run off the main thread, answer on it. */
    void background(Runnable r) {
        io.execute(r);
    }

    void post(Runnable r) {
        main.post(r);
    }
}
