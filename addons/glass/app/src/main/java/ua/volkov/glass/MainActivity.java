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
    private final View[] shades = new View[3];
    private int front;
    private View dim;
    private LinearLayout info;
    private TextView title, meta, overview, badge;
    private LinearLayout dots;
    private View stageFocus;
    private FrameLayout dockBand;
    private LinearLayout dock;
    private ScrollView appsPage;
    private FrameLayout appsWrap;
    private android.graphics.Bitmap currentArt;
    private LinearLayout appsGrid;
    private String gridSig;
    private android.graphics.Bitmap softFor, softBlur;
    private FrameLayout weatherCap;
    private LinearLayout statusCap;
    private TextView wGlyph, wTemp, sTime, hint;
    private View alertDot;
    private ImageView sAvatar;
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
    private ProfilePanel profile;
    private KeysPanel keys;
    private volatile NowPlaying.Track track;
    private LinearLayout npCap;
    private ImageView npArt;
    private TextView npText;
    private int npTicks;
    private boolean npPolling;
    private String statusKey;

    private final SimpleDateFormat clock = new SimpleDateFormat("H:mm", Locale.getDefault());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            renderCapsules();
            pollTrack();
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
                Home.fallback(app);
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
        profile = new ProfilePanel(this, root, ui);
        keys = new KeysPanel(this, root, ui);
        renderDock();
        if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
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
                || (settings != null && settings.isOpen()) || (profile != null && profile.isOpen())
                || (keys != null && keys.isOpen());
    }

    void closePanels() {
        if (control != null) control.close();
        if (weather != null) weather.close();
        if (settings != null) settings.close();
        if (profile != null) profile.close();
        if (keys != null) keys.close();
    }

    NowPlaying.Track track() {
        return track;
    }

    /** From the top capsules Down goes to the film, or to the dock when the film cannot take focus. */
    private boolean focusDown() {
        if (stageFocus.isFocusable() && stageFocus.getVisibility() == View.VISIBLE && stageFocus.requestFocus()) return true;
        return dock.getChildCount() > 0 && dock.getChildAt(0).requestFocus();
    }

    /** Now Playing opens the app that plays: Flick AirPlay for the phone, or the music app itself. */
    void openNowPlaying() {
        NowPlaying.Track t = track;
        launch(t == null ? "ua.volkov.airplay" : t.pkg);
    }

    void postDelayed(Runnable r, long ms) {
        main.postDelayed(r, ms);
    }

    /** What plays, every two seconds (every second while Now Playing is open), off the main thread. */
    private void pollTrack() {
        if (npPolling || ++npTicks % 2 != 0) return;
        npPolling = true;
        Context app = getApplicationContext();
        io.execute(() -> {
            NowPlaying.Track t = NowPlaying.current(app);
            main.post(() -> {
                npPolling = false;
                track = t;
                renderNowPlaying();
                if (control.isOpen()) control.renderTrack();
            });
        });
    }

    private android.graphics.Bitmap npShownArt;
    private String npShownText;

    /** The Now Playing capsule at the top, as tvOS shows what plays: cover, track and artist. */
    private void renderNowPlaying() {
        NowPlaying.Track t = track;
        boolean show = t != null;
        if (show != (npCap.getVisibility() == View.VISIBLE)) {
            // no alpha animation here: the focus zoom shares the view's animator and used to cancel the
            // fade halfway, leaving an invisible capsule that held the focus
            boolean had = npCap.hasFocus();
            npCap.setAlpha(1f);
            npCap.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show && had && dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
        }
        if (!show) return;
        String txt = (t.playing ? "" : "❚❚  ") + t.title + (t.artist.isEmpty() ? "" : "  ·  " + t.artist);
        if (!txt.equals(npShownText)) {
            npShownText = txt;
            npText.setText(txt);
        }
        if (t.art != npShownArt) {
            npShownArt = t.art;
            if (t.art != null) npArt.setImageBitmap(t.art);
            else npArt.setImageResource(R.drawable.g_airplay);
        }
    }

    void openKeys() {
        keys.open();
    }

    /** New keys: fetch everything again with them. */
    void keysChanged() {
        feedSeed++;
        loadFeed();
        loadStatus();
    }

    void openProfile() {
        profile.open();
    }

    /** A new profile picture: the capsule and the Control Center follow. */
    void avatarChanged() {
        statusKey = null;
        renderCapsules();
        if (control.isOpen()) control.render();
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
        shades[0] = left;
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xD9000000, 0x80000000, 0x10000000, 0x00000000}));
        root.addView(left, new FrameLayout.LayoutParams(-1, -1));
        View bottom = new View(this);
        shades[1] = bottom;
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x66000000, 0x00000000, 0x00000000}));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -1));
        View top = new View(this);
        shades[2] = top;
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
        hint = ui.line("◀ ▶  другие фильмы     OK  смотреть в Lampa", 10, Ui.FAINT, false);
        hint.setAlpha(0f);
        info.addView(hint, ui.lp(-2, -2, 0, 10, 0, 0));
        root.addView(info, ui.flp(560, -2, Gravity.START | Gravity.BOTTOM, 64, 0, 0, 176));
        // carousel dots, centred just above the dock as in tvOS
        root.addView(dots, ui.flp(-2, 8, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 150));

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

        // capsules as in tvOS: a small round weather badge on the left (with a tiny alert dot), the clock
        // and the profile picture on the right
        weatherCap = new FrameLayout(this);
        LinearLayout wcol = new LinearLayout(this);
        wcol.setOrientation(LinearLayout.VERTICAL);
        wcol.setGravity(Gravity.CENTER);
        wGlyph = ui.text("☁", 13, Ui.WHITE, false);
        wTemp = ui.text("", 9.5f, Ui.WHITE, true);
        // full width and centred text: an emoji's measured box is narrower than what it draws
        wGlyph.setGravity(Gravity.CENTER);
        wGlyph.setIncludeFontPadding(true);
        wTemp.setGravity(Gravity.CENTER);
        wcol.addView(wGlyph, ui.lp(-1, -2, 0, 0, 0, 0));
        wcol.addView(wTemp, ui.lp(-1, -2, 0, 0, 0, 0));
        weatherCap.addView(wcol, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        alertDot = new View(this);
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(0x66FFFFFF);
        alertDot.setBackground(dg);
        weatherCap.addView(alertDot, ui.flp(5, 5, Gravity.TOP | Gravity.END, 0, 9, 9, 0));
        capsule(weatherCap, 25, v -> weather.open());
        root.addView(weatherCap, ui.flp(50, 50, Gravity.START | Gravity.TOP, 52, 28, 0, 0));

        statusCap = new LinearLayout(this);
        statusCap.setGravity(Gravity.CENTER_VERTICAL);
        statusCap.setPadding(ui.px(14), 0, ui.px(4), 0);
        sTime = ui.text("", 12, Ui.WHITE, false);
        statusCap.addView(sTime, ui.lp(-2, -2, 0, 0, 9, 0));
        sAvatar = new ImageView(this);
        statusCap.addView(sAvatar, ui.lp(28, 28, 0, 0, 0, 0));
        capsule(statusCap, 18, v -> control.open());
        statusCap.setOnLongClickListener(v -> {
            profile.open();
            return true;
        });
        root.addView(statusCap, ui.flp(-2, 36, Gravity.END | Gravity.TOP, 0, 35, 52, 0));

        // Now Playing: shown only while something plays
        npCap = new LinearLayout(this);
        npCap.setGravity(Gravity.CENTER_VERTICAL);
        npCap.setPadding(ui.px(4), 0, ui.px(16), 0);
        npArt = new ImageView(this);
        npArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        npArt.setBackground(ui.round(0xFF3A3A3C, 14));
        npArt.setClipToOutline(true);
        npCap.addView(npArt, ui.lp(28, 28, 0, 0, 10, 0));
        npText = ui.line("", 12, Ui.WHITE, false);
        npText.setMaxWidth(ui.px(380));
        npCap.addView(npText);
        capsule(npCap, 18, v -> openNowPlaying());
        View.OnKeyListener capDown = (v, code, e) -> e.getAction() == KeyEvent.ACTION_DOWN
                && code == KeyEvent.KEYCODE_DPAD_DOWN && focusDown();
        weatherCap.setOnKeyListener(capDown);
        statusCap.setOnKeyListener(capDown);
        npCap.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) return focusDown();
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) return weatherCap.requestFocus();
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return statusCap.requestFocus();
            return false;
        });
        npCap.setVisibility(View.GONE);
        root.addView(npCap, ui.flp(-2, 36, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 35, 0, 0));

        // all apps, under the dock when it moves up
        appsWrap = new FrameLayout(this);
        appsWrap.setAlpha(0f);
        appsWrap.setVisibility(View.GONE);
        appsPage = new ScrollView(this) {
            // the page scrolls itself, row by row and eased (gridFollow); the stock jump looks jerky
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
                return 0;
            }
        };
        appsPage.setVerticalScrollBarEnabled(false);
        appsPage.setClipToPadding(false);
        appsGrid = new LinearLayout(this);
        appsGrid.setOrientation(LinearLayout.VERTICAL);
        appsGrid.setClipChildren(false);
        // a focused tile grows past its cell: the padding must not clip it (LinearLayout clips to its padding by default)
        appsGrid.setClipToPadding(false);
        appsGrid.setPadding(ui.px(56), ui.px(20), ui.px(56), ui.px(48));
        appsPage.addView(appsGrid);
        appsWrap.addView(appsPage, new FrameLayout.LayoutParams(-1, -1));
        // apps that scroll up towards the dock fade out row by row instead of being cut off
        appsPage.setOnScrollChangeListener((v, x, y, ox, oy) -> fadeRows());
        root.addView(appsWrap, ui.flp(-1, -1, Gravity.TOP, 0, 170, 0, 0));

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

    /** A smoky glass capsule; on focus it turns white with graphite text, as in tvOS. */
    private void capsule(ViewGroup c, float radiusDp, View.OnClickListener click) {
        GradientDrawable g = ui.capsule(radiusDp);
        c.setBackground(g);
        c.setFocusable(true);
        c.setClickable(true);
        c.setOnClickListener(click);
        c.setOnFocusChangeListener((v, has) -> {
            ui.capsuleFocus(g, has);
            tintTexts(c, has ? Ui.GRAPHITE : Ui.WHITE);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator()).start();
        });
    }

    private static void tintTexts(ViewGroup g, int colour) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof TextView) ((TextView) v).setTextColor(colour);
            else if (v instanceof ViewGroup) tintTexts((ViewGroup) v, colour);
        }
    }

    // ------------------------------------------------------------------ the film stage

    private void loadFeed() {
        Context app = getApplicationContext();
        int seed = feedSeed;
        io.execute(() -> {
            JSONArray f = Data.feed(app, seed);
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
        title.setText("Для тебя");
        meta.setText("Фильмы появятся, когда загрузятся Must, Lampa и новинки. Ключи: Настройки → Ключи и сервисы");
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
                JSONObject got = Data.details(app, film);
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
        android.text.SpannableString sp = new android.text.SpannableString(m.toString());
        int star = m.indexOf("★");
        if (star >= 0) sp.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFD60A), star, star + 1, 0);
        meta.setText(sp);
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
    private String pendingArt;

    private void setArt(String url) {
        if (url == null || url.isEmpty() || url.equals(shownArt)) return;
        // behind the apps the frosted picture stays put: a new film there flashed the whole screen
        if (appsShown) {
            pendingArt = url;
            return;
        }
        shownArt = url;
        // the blurred copy is made off the main thread: on the TV it costs a frame or two
        Images.get(url, b0 -> io.execute(() -> {
            android.graphics.Bitmap sb = b0 == null ? null : Ui.blur(b0, 40);
            android.graphics.Bitmap apps = b0 == null ? null : Ui.blur(b0, ui.blurWidth());
            main.post(() -> {
                if (b0 != null) {
                    softFor = b0;
                    softBlur = apps;
                }
                artReady(url, b0, sb);
            });
        }));
    }

    private void artReady(String url, android.graphics.Bitmap b, android.graphics.Bitmap blurred) {
        {
            if (!url.equals(shownArt)) return;
            if (b == null) {   // the picture did not load: never leave the blur over the screen
                soft.animate().cancel();
                soft.animate().alpha(0f).setDuration(400).start();
                return;
            }
            ImageView out = art[front];
            front = 1 - front;
            ImageView in = art[front];
            boolean first = out.getAlpha() < 0.05f;
            soft.animate().cancel();
            in.animate().cancel();
            soft.setImageBitmap(blurred);
            Runnable reveal = () -> {
                if (!url.equals(shownArt)) return;   // a newer picture is on its way
                in.setImageBitmap(b);
                currentArt = b;
                in.setAlpha(1f);
                in.setScaleX(1.03f);
                in.setScaleY(1.03f);
                out.setAlpha(0f);
                // a short settle, not a long zoom: a full-screen picture moving for seconds keeps the TV's GPU busy
                in.animate().scaleX(1f).scaleY(1f).setDuration(1400).setInterpolator(new DecelerateInterpolator()).start();
                soft.animate().alpha(0f).setDuration(600).setInterpolator(new DecelerateInterpolator()).start();
            };
            if (first) {
                soft.setAlpha(1f);
                reveal.run();
            } else {
                soft.animate().alpha(1f).setDuration(260).withEndAction(reveal).start();
            }
        }
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
                JSONObject f = Data.find(app, film);
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
            JSONObject s = Data.status(app);
            main.post(() -> {
                status = s;
                renderCapsules();
                if (control.isOpen()) control.render();
            });
        });
    }

    private void renderCapsules() {
        JSONObject w = status.optJSONObject("weather");
        boolean has = w != null && w.optLong("at") != 0;
        String g = has ? Ui.glyph(w.optInt("code"), w.optBoolean("day", true)) : "☁";
        String t = has ? Math.round(w.optDouble("temp")) + "°" : "";
        if (!TextUtils.equals(wGlyph.getText(), g)) wGlyph.setText(g);
        if (!TextUtils.equals(wTemp.getText(), t)) wTemp.setText(t);
        wTemp.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
        // the alert state is a tiny dot on the weather badge: there, but quiet
        JSONObject a = status.optJSONObject("alert");
        int state = a == null ? -1 : a.optInt("state", -1);
        int dot = state == 1 ? 0xFFFF453A : state == 2 ? 0xFFFF9F0A : state == 0 ? 0xFF30D158 : 0x66FFFFFF;
        ((GradientDrawable) alertDot.getBackground()).setColor(dot);
        // the clock, then the profile picture
        String time = clock.format(new Date());
        int av = Avatar.current(this);
        String skey = time + "|" + av;
        if (!skey.equals(statusKey)) {
            if (statusKey == null || !statusKey.endsWith("|" + av)) sAvatar.setImageDrawable(new Avatar(av, ui.px(28)));
            statusKey = skey;
            sTime.setText(time);
        }
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
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && !appsShown) {
                    showApps(true);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_UP && appsShown) {   // up from the apps: the home screen first
                    showApps(false);
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
        gear.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && !appsShown) {
                showApps(true);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP && appsShown) {
                showApps(false);
                return true;
            }
            return false;
        });
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
        return appTile(pkg, w, h, null);
    }

    private View appTile(String pkg, int w, int h, TextView label) {
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
        // a soft gloss across the top, like the reference's glass icons
        View gloss = new View(this);
        gloss.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x33FFFFFF, 0x0DFFFFFF, 0x00FFFFFF}));
        f.addView(gloss, new FrameLayout.LayoutParams(-1, h / 2, Gravity.TOP));
        f.setClipToOutline(true);
        focusable(f, null, label);
        return f;
    }

    /** Focus as in tvOS: the tile lifts, grows and gets a bright rim. */
    private void focusable(View v, View.OnClickListener click) {
        focusable(v, click, null);
    }

    private void focusable(View v, View.OnClickListener click, TextView label) {
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
            view.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
            if (label != null) label.animate().alpha(has ? 1f : 0f).setDuration(180).start();
            if (has && label != null) gridFollow(view);
        });
    }

    private void showApps(boolean apps) {
        if (apps == appsShown) return;
        appsShown = apps;
        DecelerateInterpolator ease = new DecelerateInterpolator(2f);
        if (apps) {
            renderGrid();
            float up = -(dockBand.getTop() - ui.px(78));
            dockBand.animate().translationY(up).setDuration(380).setInterpolator(ease).withLayer().start();
            info.animate().alpha(0f).setDuration(200).start();
            dots.animate().alpha(0f).setDuration(200).start();
            // the stage turns to frosted glass behind the apps
            if (currentArt != null) {
                soft.animate().cancel();
                if (softFor != currentArt) {
                    softFor = currentArt;
                    softBlur = Ui.blur(currentArt, ui.blurWidth());
                }
                soft.setImageBitmap(softBlur);
                // once the frosted copy covers the screen, the layers under it stop being drawn: on a TV GPU
                // every full-screen layer costs frames
                soft.animate().alpha(1f).setDuration(380).withEndAction(() -> stageLayers(!appsShown)).start();
            }
            dim.animate().alpha(ui.scrim()).setDuration(300).start();
            FrameLayout.LayoutParams wl = (FrameLayout.LayoutParams) appsWrap.getLayoutParams();
            wl.topMargin = ui.px(78) + dockBand.getHeight() + ui.px(4);
            appsWrap.setLayoutParams(wl);
            appsWrap.setVisibility(View.VISIBLE);
            appsWrap.setTranslationY(ui.px(40));
            appsWrap.animate().alpha(1f).translationY(0f).setDuration(380).setInterpolator(ease).withLayer().start();
            appsPage.scrollTo(0, 0);
            appsPage.post(this::fadeRows);
            main.postDelayed(() -> {
                if (appsGrid.getChildCount() > 0) ((ViewGroup) appsGrid.getChildAt(0)).getChildAt(0).requestFocus();
            }, 120);
        } else {
            stageLayers(true);
            if (pendingArt != null) {
                String u = pendingArt;
                pendingArt = null;
                main.postDelayed(() -> setArt(u), 400);
            }
            dockBand.animate().translationY(0f).setDuration(320).setInterpolator(ease).withLayer().start();
            info.animate().alpha(1f).setDuration(300).start();
            dots.animate().alpha(1f).setDuration(300).start();
            soft.animate().alpha(0f).setDuration(400).start();
            dim.animate().alpha(0f).setDuration(300).start();
            appsWrap.animate().alpha(0f).translationY(ui.px(40)).setDuration(220).withLayer()
                    .withEndAction(() -> appsWrap.setVisibility(View.GONE)).start();
            if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
        }
    }

    private android.animation.ObjectAnimator gridScroll;

    /** Keeps the row above the focused one in view, gliding there instead of jumping. */
    private void gridFollow(View tile) {
        View row = (View) tile.getParent().getParent();
        int i = appsGrid.indexOfChild(row);
        if (i < 0) return;
        int max = Math.max(0, appsGrid.getHeight() - appsPage.getHeight());
        int target = i == 0 ? 0 : Math.min(max, appsGrid.getChildAt(i - 1).getTop() - ui.px(20));
        if (target == appsPage.getScrollY()) return;
        if (gridScroll != null) gridScroll.cancel();
        gridScroll = android.animation.ObjectAnimator.ofInt(appsPage, "scrollY", target);
        gridScroll.setDuration(340);
        gridScroll.setInterpolator(new DecelerateInterpolator(1.6f));
        gridScroll.start();
    }

    private void stageLayers(boolean on) {
        int v = on ? View.VISIBLE : View.INVISIBLE;
        for (ImageView i : art) if (i != null && (on || i.getVisibility() == View.VISIBLE)) i.setVisibility(v);
        for (View s : shades) if (s != null) s.setVisibility(v);
    }

    /** Rows near the top of the apps page fade as they scroll towards the dock. */
    private void fadeRows() {
        int sy = appsPage.getScrollY();
        int zone = ui.px(70);
        for (int i = 0; i < appsGrid.getChildCount(); i++) {
            View row = appsGrid.getChildAt(i);
            float top = row.getTop() - sy;   // the row's top against the page's top edge
            float a = top >= 0 ? 1f : Math.max(0f, 1f + top / zone);
            if (row.getAlpha() != a) row.setAlpha(a);
        }
    }

    private void renderGrid() {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<String> pkgs = new ArrayList<>();
        for (ResolveInfo r : getPackageManager().queryIntentActivities(q, 0)) {
            String p = r.activityInfo.packageName;
            if (!pkgs.contains(p) && !p.equals(getPackageName()) && !p.equals("com.gathin.tvhome")) pkgs.add(p);
        }
        Collections.sort(pkgs, (a, b) -> label(a).compareToIgnoreCase(label(b)));
        // the banners are slow to load: build the grid again only when the apps changed
        String sig = pkgs.toString();
        if (sig.equals(gridSig) && appsGrid.getChildCount() > 0) return;
        gridSig = sig;
        appsGrid.removeAllViews();
        int page = getResources().getDisplayMetrics().widthPixels - ui.px(112);
        int gap = ui.px(16);
        int w = (page - 5 * gap) / 6, h = Math.round(w * 9f / 16f);
        LinearLayout row = null;
        for (int i = 0; i < pkgs.size(); i++) {
            if (i % 6 == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                appsGrid.addView(row, ui.lp(-1, -2, 0, 0, 0, 10));
            }
            final String pkg = pkgs.get(i);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setClipChildren(false);
            TextView t = ui.line(label(pkg), 10, Ui.WHITE, true);
            t.setMaxWidth(w);
            t.setGravity(Gravity.CENTER);
            t.setAlpha(0f);   // tvOS names only the focused app
            View tile = appTile(pkg, w, h, t);
            if (i < 6) {
                tile.setOnKeyListener((v, code, e) -> {
                    if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_UP) {
                        showApps(false);
                        return true;
                    }
                    return false;
                });
            }
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
            cell.addView(t, new LinearLayout.LayoutParams(w, -2));
            ((LinearLayout.LayoutParams) t.getLayoutParams()).topMargin = ui.px(7);
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
        npCap.setFocusable(true);
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
