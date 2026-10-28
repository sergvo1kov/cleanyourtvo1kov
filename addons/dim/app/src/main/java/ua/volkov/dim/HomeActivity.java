package ua.volkov.dim;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The home screen: header with tabs, a large "For you" card, favourite app banners and quick tiles. */
public final class HomeActivity extends Activity {
    private static final String[] DEFAULT_FAVS = {"com.netflix.ninja", "com.google.android.youtube.tv",
            "com.apple.atve.androidtv.appletv", "com.amazon.amazonvideo.livingroom",
            "com.google.android.youtube.tvmusic", "com.apple.android.music", "org.videolan.vlc"};
    private static final String TORRSERVE = "ru.yourok.torrserve";
    private static final int MAX_FAVS = 12;
    /** Kept installed but not listed: the previous launcher (a fallback home) and the stock Google TV home. */
    private static final java.util.Set<String> HIDDEN = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.gathin.tvhome", "com.google.android.apps.tv.launcherx"));

    // palette (set from the theme)
    private int txt, sub, glass, glassFocus, line;
    private boolean light;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private float dp;
    private ContentObserver observer;

    private ImageView backdrop;
    private View scrim;
    private LinearLayout homePage;
    private ScrollView appsPage;
    private LinearLayout appsGrid;
    private TextView caption, dateLine, alertPill, titleBig;
    private View statusDot;
    private GradientDrawable dotShape;
    private boolean pulsing;
    private TextView tabHome, tabApps;
    private FrameLayout hero;
    private String heroMode = "";
    private LinearLayout favRow;
    private LinearLayout quickRow;
    private TextView hint;
    private View hintBar;
    private final Runnable dwell = () -> {
        String h = hintFor(getCurrentFocus());
        if (h != null) showHint(h, false);
    };

    // hero (music)
    private ImageView npArt;
    private TextView npKicker, npTitle, npArtist, npPos, npDur, npPlay, npOpen;
    private View npFill;
    private NowPlaying.Track track;

    // quick tiles
    private volatile String torrVersion;

    private final List<String> favs = new ArrayList<>();
    private int moving = -1;
    private long mustTried;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            renderHero();
            renderTitleRow();
            main.postDelayed(this, 1000);
        }
    };
    private final Runnable slowTick = new Runnable() {
        @Override
        public void run() {
            Refresher.kick(HomeActivity.this, false);
            checkTorrServe();
            refreshMust(false);
            io.execute(() -> lampa = LampaCards.read(getApplicationContext()));
            renderQuick();
            main.postDelayed(this, 30_000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        light = "light".equals(Store.prefs(this).getString(Store.THEME, "dark"));
        txt = light ? 0xFF0D1220 : 0xFFFFFFFF;
        sub = light ? 0x990D1220 : 0x9EFFFFFF;
        glass = light ? 0x8CFFFFFF : 0x14FFFFFF;
        glassFocus = light ? 0xFFFFFFFF : 0x30FFFFFF;
        line = light ? 0x1F0D1220 : 0x1FFFFFFF;
        // setup over ADB: am start -n ua.volkov.dim/.HomeActivity --es must_nick <nick>
        String nick = getIntent() == null ? null : getIntent().getStringExtra("must_nick");
        if (nick != null) Store.prefs(this).edit().putString(Store.MUST_NICK, nick.trim()).putLong(Store.MUST_AT, 0).apply();
        loadFavs();
        setContentView(build());
        observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                renderQuick();
                renderTitleRow();
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        getContentResolver().registerContentObserver(StatusProvider.URI, false, observer);
        Refresher.kick(this, false);
        main.post(tick);
        main.post(slowTick);
        renderFavs();
        io.execute(() -> lampa = LampaCards.read(getApplicationContext()));
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
        main.removeCallbacks(slowTick);
        getContentResolver().unregisterContentObserver(observer);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while home: back to the first page and the first favourite
        showApps(false);
        if (favRow != null && favRow.getChildCount() > 0) favRow.getChildAt(0).requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (moving >= 0) {
            endMove();
        } else if (qMoving >= 0) {
            endQuickMove();
        } else if (appsPage.getVisibility() == View.VISIBLE) {
            showApps(false);
        }
        // a home screen has nowhere to go back to
    }

    // ------------------------------------------------------------------ layout

    private View build() {
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = light
                ? new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFEEF2F8, 0xFFD3DCEA})
                : new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0B1020, 0xFF161233, 0xFF1D1544});
        root.setBackground(bg);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0f);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                light ? new int[]{0x99EEF2F8, 0xCCD3DCEA} : new int[]{0x99080B14, 0xDD080B14}));
        scrim.setAlpha(0f);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(52), px(22), px(52), px(40));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // header: wordmark, tabs, settings
        FrameLayout header = new FrameLayout(this);
        LinearLayout logo = new LinearLayout(this);
        logo.setGravity(Gravity.CENTER_VERTICAL);
        logo.addView(text("Дом", 17, txt, true));
        statusDot = new View(this);
        dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dotShape.setColor(0xFF8E8E93);
        statusDot.setBackground(dotShape);
        View dot = statusDot;
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(px(6), px(6));
        dl.setMargins(px(4), px(2), 0, 0);
        logo.addView(dot, dl);
        // (no wordmark: the header is just the tabs and Settings)
        LinearLayout tabs = new LinearLayout(this);
        tabHome = tab("Главная", v -> showApps(false));
        tabApps = tab("Приложения", v -> showApps(true));
        TextView tabLife = tab("Центр управления", v -> startActivity(new Intent(this, DimActivity.class)));
        tabs.addView(tabHome);
        tabs.addView(tabApps, margins(8, 0, 8, 0));
        tabs.addView(tabLife);
        header.addView(tabs, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        TextView settings = tab("Настройки", v -> openSettings());
        header.addView(settings, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        col.addView(header, new LinearLayout.LayoutParams(-1, px(34)));
        selectTab(false);

        // title row: "For you" + clock, date, weather, alert
        FrameLayout titleRow = new FrameLayout(this);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        titleBig = text("Для тебя", 30, txt, true);
        left.addView(titleBig);
        caption = text("", 10, sub, false);
        left.addView(caption);
        titleRow.addView(left, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.BOTTOM));
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        clock.setTextColor(txt);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        right.addView(clock, endAligned(0));
        dateLine = text("", 11, sub, false);
        right.addView(dateLine, endAligned(0));
        alertPill = text("", 10, 0xFF32D74B, true);
        alertPill.setPadding(px(8), px(2), px(8), px(2));
        right.addView(alertPill, endAligned(5));
        titleRow.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM));
        col.addView(titleRow, lp(-1, -2, 0, 6, 0, 0));

        // the page that switches between Home and Apps
        FrameLayout pages = new FrameLayout(this);
        pages.setClipChildren(false);
        col.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));

        homePage = new LinearLayout(this);
        homePage.setOrientation(LinearLayout.VERTICAL);
        homePage.setClipChildren(false);
        homePage.setClipToPadding(false);
        pages.addView(homePage, new FrameLayout.LayoutParams(-1, -1));

        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        homePage.addView(hero, lp(-1, 160, 0, 10, 0, 0));

        // favourite apps, in a glass band
        LinearLayout band = new LinearLayout(this);
        band.setBackground(round(glass, 15, line));
        band.setPadding(px(9), px(9), px(9), px(9));
        band.setClipChildren(false);
        band.setClipToPadding(false);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        favRow = new LinearLayout(this);
        favRow.setClipChildren(false);
        favRow.setClipToPadding(false);
        favRow.setPadding(px(2), px(2), px(2), px(2));
        hs.addView(favRow);
        band.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        homePage.addView(band, lp(-1, -2, 0, 12, 0, 0));

        quickRow = new LinearLayout(this);
        quickRow.setClipChildren(false);
        homePage.addView(quickRow, lp(-1, 46, 0, 12, 0, 0));
        buildQuick();

        // hint bar: pinned to the bottom edge, never pushed off screen by the blocks above
        LinearLayout hintBar = new LinearLayout(this);
        hintBar.setOrientation(LinearLayout.VERTICAL);
        // a fade under the hints, so nothing scrolling below can show through them
        hintBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                light ? new int[]{0x00D3DCEA, 0xF2D3DCEA, 0xFFD3DCEA} : new int[]{0x00121029, 0xF2121029, 0xFF121029}));
        hintBar.setPadding(px(52), px(16), px(52), px(10));
        View rule = new View(this);
        rule.setBackgroundColor(line);
        hintBar.addView(rule, new LinearLayout.LayoutParams(-1, 1));
        hint = text("", 10, sub, false);
        hint.setPadding(0, px(6), 0, 0);
        hintBar.addView(hint);
        hintBar.setAlpha(0f);
        this.hintBar = hintBar;
        root.addView(hintBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        // hints appear only after the remote rests on something for a moment
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            if (moving >= 0 || qMoving >= 0) return;
            hideHint();
            main.removeCallbacks(dwell);
            main.postDelayed(dwell, 1500);
        });

        // all apps page
        appsPage = new ScrollView(this);
        appsPage.setVisibility(View.GONE);
        appsPage.setClipChildren(true);
        appsPage.setClipToPadding(true);
        appsPage.setVerticalScrollBarEnabled(false);
        appsGrid = new LinearLayout(this);
        appsGrid.setOrientation(LinearLayout.VERTICAL);
        appsGrid.setPadding(px(8), px(14), px(8), px(36));
        appsGrid.setClipToPadding(false);
        appsGrid.setClipChildren(false);
        appsPage.addView(appsGrid);
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(-1, -1);
        al.setMargins(-px(8), px(10), -px(8), 0);
        pages.addView(appsPage, al);

        return root;
    }

    private TextView tab(String label, View.OnClickListener onClick) {
        TextView t = text(label, 13, sub, false);
        t.setPadding(px(17), px(6), px(17), px(6));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(onClick);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(20));
        g.setColor(Color.TRANSPARENT);
        t.setBackground(g);
        t.setOnFocusChangeListener((v, has) -> {
            g.setColor(has ? glassFocus : (v.isSelected() ? glass : Color.TRANSPARENT));
            ((TextView) v).setTextColor(has || v.isSelected() ? txt : sub);
        });
        return t;
    }

    private void selectTab(boolean apps) {
        for (TextView t : new TextView[]{tabHome, tabApps}) {
            boolean sel = (t == tabApps) == apps;
            t.setSelected(sel);
            ((GradientDrawable) t.getBackground()).setColor(sel ? glass : Color.TRANSPARENT);
            t.setTextColor(sel ? txt : sub);
            t.setTypeface(Typeface.create(sel ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        }
    }

    private void showApps(boolean apps) {
        selectTab(apps);
        hideHint();
        homePage.setVisibility(apps ? View.GONE : View.VISIBLE);
        titleBig.setText(apps ? "Все приложения" : "Для тебя");
        if (apps) caption.setText("Удерживай OK, чтобы добавить приложение в избранное на Главной");
        else heroMode = "";   // redraw the For you card and its caption
        appsPage.setVisibility(apps ? View.VISIBLE : View.GONE);
        if (apps) {
            renderAppsGrid();
            if (appsGrid.getChildCount() > 0) {
                ViewGroup r = (ViewGroup) appsGrid.getChildAt(0);
                if (r.getChildCount() > 0) r.getChildAt(0).requestFocus();
            }
        }
    }

    // ------------------------------------------------------------------ title row

    private void renderTitleRow() {
        SharedPreferences p = Store.prefs(this);
        String date = new SimpleDateFormat("EEE, d MMMM", new Locale("ru")).format(new Date());
        String w = p.getLong(Store.W_AT, 0) == 0 ? "" : "  ·  Одесса " + Weather.deg(p.getInt(Store.W_TEMP, 0))
                + " " + Weather.words(p.getInt(Store.W_CODE, 0));
        dateLine.setText(date + w);
        int state = p.getInt(Store.ALERT_STATE, Alerts.UNKNOWN);
        String scope = Alerts.label(p.getString(Store.ALERT_SCOPE, Alerts.RAION));
        int c;
        String s;
        if (state == Alerts.ALERT) {
            c = 0xFFFF453A;
            s = "●  Тревога  ·  " + scope;
        } else if (state == Alerts.PARTIAL) {
            c = 0xFFFF9F0A;
            s = "●  Тревога в части района";
        } else if (state == Alerts.CALM) {
            c = 0xFF32D74B;
            s = "●  Тревоги нет  ·  " + scope;
        } else {
            c = 0xFF8E8E93;
            s = "●  Нет данных о тревогах";
        }
        alertPill.setText(s);

        alertPill.setTextColor(light ? darker(c) : c);
        alertPill.setBackground(round((c & 0x00FFFFFF) | 0x29000000, 12, 0));
    }

    private void showHint(String text, boolean sticky) {
        hint.setText(text);
        hintBar.animate().cancel();
        hintBar.animate().alpha(1f).setDuration(220).start();
    }

    private void hideHint() {
        if (hintBar == null) return;
        hintBar.animate().cancel();
        hintBar.animate().alpha(0f).setDuration(150).start();
    }

    /** What holding OK does on the focused element, or null when nothing useful to say. */
    private String hintFor(View f) {
        if (f == null) return null;
        if (isInside(f, favRow)) return "◉  Открыть        ◎  Удерживай OK — переставить или убрать из избранного";
        if (isInside(f, quickRow)) return "◉  Открыть        ◎  Удерживай OK — заменить, переставить, убрать или добавить плитку";
        if (isInside(f, appsGrid)) return "◉  Открыть        ◎  Удерживай OK — добавить в избранное или убрать";
        if (isInside(f, hero) && "must".equals(heroMode)) return "◉  Открыть в Lampa";
        return null;
    }

    private static boolean isInside(View v, View parent) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == parent) return true;
        return v == parent;
    }

    private void pulseDot() {
        if (!pulsing) return;
        float to = statusDot.getAlpha() > 0.6f ? 0.25f : 1f;
        statusDot.animate().alpha(to).setDuration(700).withEndAction(this::pulseDot).start();
    }

    // ------------------------------------------------------------------ hero

    private void renderHero() {
        if (homePage.getVisibility() != View.VISIBLE) return;
        SharedPreferences p = Store.prefs(this);
        boolean alert = p.getInt(Store.ALERT_STATE, Alerts.UNKNOWN) == Alerts.ALERT;
        track = NowPlaying.current(this);
        JSONArray must = mustList();
        String mode = alert ? "alert" : track != null ? "music" : must.length() > 0 ? "must" : "weather";
        if (!mode.equals(heroMode)) {
            boolean hadFocus = hero.findFocus() != null;
            hero.removeAllViews();
            heroMode = mode;
            switch (mode) {
                case "alert": buildAlertHero(); break;
                case "music": buildMusicHero(); break;
                case "must": buildMustHero(must); break;
                default: buildWeatherHero();
            }
            if (hadFocus) hero.requestFocus(View.FOCUS_DOWN);
            setBackdrop(null);
        }
        switch (mode) {
            case "alert": caption.setText("Воздушная тревога  ·  " + Alerts.label(p.getString(Store.ALERT_SCOPE, Alerts.RAION))); updateAlertHero(); break;
            case "music": caption.setText(track.app + "  ·  Сейчас играет"); updateMusicHero(); break;
            case "must": caption.setText(lampaCount > 0 ? "Lampa  ·  Must" : "Must"); break;
            default: caption.setText("Одесса  ·  сейчас");
        }
    }

    private FrameLayout heroCard(int[] colours) {
        FrameLayout c = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colours);
        g.setCornerRadius(px(15));
        g.setStroke(1, 0x14FFFFFF);
        c.setBackground(g);
        c.setElevation(px(8));
        hero.addView(c, new FrameLayout.LayoutParams(-1, -1));
        return c;
    }

    // music
    private void buildMusicHero() {
        FrameLayout c = heroCard(new int[]{0xFF0A1410, 0xFF13261E, 0xFF0C1813});
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(17), px(17), px(22), px(17));
        npArt = new ImageView(this);
        npArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        npArt.setBackground(round(0x22FFFFFF, 9, 0));
        clip(npArt, 9);
        row.addView(npArt, new LinearLayout.LayoutParams(px(134), px(134)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, -2, 1);
        il.setMargins(px(22), 0, 0, 0);
        row.addView(info, il);
        npKicker = text("", 10, 0xFFFF4E45, true);
        npKicker.setLetterSpacing(0.06f);
        info.addView(npKicker);
        npTitle = text("", 24, Color.WHITE, true);
        npTitle.setSingleLine(true);
        npTitle.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(npTitle, margins(0, 4, 0, 0));
        npArtist = text("", 14, 0xB3FFFFFF, false);
        npArtist.setSingleLine(true);
        info.addView(npArtist);
        FrameLayout bar = new FrameLayout(this);
        View track = new View(this);
        track.setBackground(round(0x2EFFFFFF, 2, 0));
        bar.addView(track, new FrameLayout.LayoutParams(-1, -1));
        npFill = new View(this);
        npFill.setBackground(round(0xFFFF4E45, 2, 0));
        npFill.setPivotX(0);
        bar.addView(npFill, new FrameLayout.LayoutParams(-1, -1));
        info.addView(bar, lp(-1, 3, 0, 12, 0, 0));
        FrameLayout times = new FrameLayout(this);
        npPos = text("", 9, 0x8CFFFFFF, false);
        npDur = text("", 9, 0x8CFFFFFF, false);
        times.addView(npPos, new FrameLayout.LayoutParams(-2, -2, Gravity.START));
        times.addView(npDur, new FrameLayout.LayoutParams(-2, -2, Gravity.END));
        info.addView(times, lp(-1, -2, 0, 4, 0, 0));
        LinearLayout ctl = new LinearLayout(this);
        ctl.setGravity(Gravity.CENTER_VERTICAL);
        ctl.addView(circle("⏮", 29, 0x1FFFFFFF, v -> media(-1)));
        View play = circle("⏸", 35, 0xFFFF4E45, v -> media(0));
        npPlay = (TextView) ((FrameLayout) play).getChildAt(0);
        ctl.addView(play, lp(35, 35, 11, 0, 11, 0));
        ctl.addView(circle("⏭", 29, 0x1FFFFFFF, v -> media(1)));
        View spacer = new View(this);
        ctl.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        npOpen = text("", 11, Color.WHITE, false);
        npOpen.setPadding(px(17), px(8), px(17), px(8));
        pill(npOpen, 0x1FFFFFFF, 0x47FFFFFF);
        npOpen.setOnClickListener(v -> {
            if (this.track != null) launch(this.track.pkg);
        });
        ctl.addView(npOpen);
        info.addView(ctl, lp(-1, -2, 0, 6, 0, 0));
        c.addView(row, new FrameLayout.LayoutParams(-1, -1));
    }

    private void updateMusicHero() {
        NowPlaying.Track t = track;
        if (t == null) return;
        npKicker.setText(t.app.toUpperCase(new Locale("ru")) + (t.playing ? "     ●  СЕЙЧАС ИГРАЕТ" : "     НА ПАУЗЕ"));
        npTitle.setText(t.title);
        npArtist.setText(t.artist);
        npPlay.setText(t.playing ? "⏸" : "▶");
        npOpen.setText("Открыть " + t.app);
        String key = t.title + "|" + t.artist;
        if (!key.equals(npArt.getTag())) {
            npArt.setTag(key);
            npArt.setImageBitmap(t.art);
            setBackdrop(t.art);
        }
        long dur = 0;
        MediaMetadata md = t.controller.getMetadata();
        if (md != null) dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        PlaybackState st = t.controller.getPlaybackState();
        long pos = 0;
        if (st != null) {
            pos = st.getPosition();
            if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
                pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
            }
        }
        if (dur > 0) {
            pos = Math.max(0, Math.min(pos, dur));
            npFill.setScaleX(pos / (float) dur);
            npPos.setText(mmss(pos));
            npDur.setText(mmss(dur));
        } else {
            npFill.setScaleX(0);
            npPos.setText("");
            npDur.setText("");
        }
    }

    private void media(int what) {
        NowPlaying.Track t = track;
        if (t == null) return;
        android.media.session.MediaController.TransportControls c = t.controller.getTransportControls();
        if (what < 0) c.skipToPrevious();
        else if (what > 0) c.skipToNext();
        else if (t.playing) c.pause();
        else c.play();
    }

    // alert
    private TextView alAgo, alPosts;

    private void buildAlertHero() {
        FrameLayout c = heroCard(new int[]{0xFF3A0A0C, 0xFF5C1014, 0xFF2A080A});
        c.setFocusable(true);
        c.setOnClickListener(v -> startActivity(new Intent(this, DimActivity.class)));
        focusRing(c, 15);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(22), px(17), px(22), px(17));
        TextView k = text("ВОЗДУШНАЯ ТРЕВОГА", 11, 0xFFFF6961, true);
        k.setLetterSpacing(0.08f);
        box.addView(k);
        alAgo = text("", 26, Color.WHITE, true);
        box.addView(alAgo, margins(0, 4, 0, 0));
        alPosts = text("", 13, 0xD9FFFFFF, false);
        alPosts.setMaxLines(4);
        alPosts.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(alPosts, margins(0, 8, 0, 0));
        c.addView(box);
    }

    private void updateAlertHero() {
        SharedPreferences p = Store.prefs(this);
        long since = p.getLong(Store.ALERT_SINCE, 0);
        String took = HomeStatusTime.elapsed(since);
        alAgo.setText(Alerts.label(p.getString(Store.ALERT_SCOPE, Alerts.RAION)) + (took.isEmpty() ? "" : "  ·  " + took));
        StringBuilder b = new StringBuilder();
        try {
            JSONArray feed = new JSONArray(p.getString(Store.TG_FEED, "[]"));
            for (int i = 0; i < feed.length() && i < 3; i++) {
                JSONObject o = feed.getJSONObject(i);
                String first = o.optString("text").split("\n")[0];
                if (b.length() > 0) b.append('\n');
                b.append(hhmm(o.optLong("ms"))).append("  ").append(first);
            }
        } catch (Exception ignored) {
            // no feed
        }
        alPosts.setText(b.length() == 0 ? "В укрытие. Экстренных сообщений Monitor пока нет." : b);
    }

    // must
    private void buildMustHero(JSONArray list) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(px(8), px(8), px(8), 0);
        hs.addView(row);
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            final String title = o.optString("title");
            final String lampaIntent = o.optString("intent");
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            FrameLayout frame = new FrameLayout(this);
            frame.setFocusable(true);
            frame.setClickable(true);
            frame.setOnClickListener(v -> {
                if (!lampaIntent.isEmpty()) openIntentUri(lampaIntent, title);
                else openInLampa(title);
            });
            // the title shows until the poster arrives and covers it
            TextView fallback = text(title, 11, txt, true);
            fallback.setGravity(Gravity.CENTER);
            fallback.setPadding(px(6), 0, px(6), 0);
            fallback.setBackground(round(glass, 9, line));
            frame.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            clip(img, 9);
            frame.addView(img, new FrameLayout.LayoutParams(-1, -1));
            Images.load(o.optString("poster"), img);
            focusRing(frame, 9);
            card.addView(frame, new LinearLayout.LayoutParams(px(84), px(124)));
            TextView t = text(title, 9, txt, false);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setMaxWidth(px(84));
            card.addView(t, margins(0, 4, 0, 0));
            row.addView(card, margins(0, 0, 14, 0));
        }
        // bleed the row into the page margin so the first poster lines up with the cards below
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-1, -1);
        hl.setMargins(-px(8), -px(8), -px(8), 0);
        hero.addView(hs, hl);
    }

    private volatile JSONArray lampa = new JSONArray();
    private int lampaCount;

    /** The For you row: Lampa's own cards mixed with Must's picks. */
    private JSONArray mustList() {
        JSONArray must;
        try {
            must = new JSONArray(Store.prefs(this).getString(Store.MUST_LIST, "[]"));
        } catch (Exception e) {
            must = new JSONArray();
        }
        JSONArray l = lampa;
        if (l.length() != lampaCount) {
            lampaCount = l.length();
            heroMode = "";
        }
        return LampaCards.mix(l, must);
    }

    private void refreshMust(boolean force) {
        String nick = Store.str(this, Store.MUST_NICK);
        if (nick.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (!force && (now - Store.prefs(this).getLong(Store.MUST_AT, 0) < 30 * 60_000L || now - mustTried < 5 * 60_000L)) return;
        mustTried = now;
        Context app = getApplicationContext();
        io.execute(() -> {
            try {
                JSONArray list = Must.similar(app, nick);
                if (list.length() == 0) list = Must.want(app, nick);
                if (list.length() > 0) {
                    Store.prefs(app).edit().putString(Store.MUST_LIST, list.toString()).putLong(Store.MUST_AT, now).apply();
                    main.post(() -> heroMode = "");   // rebuild with the new posters
                }
            } catch (Exception ignored) {
                // must-debug.txt has the details
            }
        });
    }

    /** A Lampa card carries its own intent: it opens that exact film in Lampa. */
    private void openIntentUri(String uri, String title) {
        try {
            Intent i = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            openInLampa(title);
        }
    }

    /** Lampa takes a plain search intent and opens its own search with the title filled in. */
    private void openInLampa(String title) {
        Intent i = new Intent(Intent.ACTION_SEARCH);
        i.setPackage("top.rootu.lamps");
        i.putExtra(SearchManager.QUERY, title);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            search(title);
        }
    }

    private void search(String title) {
        Intent i = new Intent("android.search.action.GLOBAL_SEARCH");
        i.putExtra(SearchManager.QUERY, title);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Intent yt = new Intent(Intent.ACTION_SEARCH);
            yt.setPackage("com.google.android.youtube.tv");
            yt.putExtra(SearchManager.QUERY, title);
            try {
                startActivity(yt);
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, title, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // weather
    private void buildWeatherHero() {
        FrameLayout c = heroCard(light ? new int[]{0xFF1B2A44, 0xFF243A5E, 0xFF1B2A44} : new int[]{0xFF141B2E, 0xFF1C2541, 0xFF141B2E});
        SharedPreferences p = Store.prefs(this);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(28), 0, px(28), 0);
        row.addView(text(Weather.icon(p.getInt(Store.W_CODE, 0), p.getBoolean(Store.W_DAY, true)), 54, Color.WHITE, false));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(Weather.deg(p.getInt(Store.W_TEMP, 0)), 44, Color.WHITE, false);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        info.addView(t);
        info.addView(text(Weather.words(p.getInt(Store.W_CODE, 0)) + "  ·  ощущается " + Weather.deg(p.getInt(Store.W_FEELS, 0))
                + "  ·  ветер " + p.getInt(Store.W_WIND, 0) + " км/ч", 14, 0xB3FFFFFF, false));
        row.addView(info, margins(24, 0, 0, 0));
        c.addView(row, new FrameLayout.LayoutParams(-1, -1));
    }

    private void setBackdrop(Bitmap art) {
        Bitmap b = Images.backdrop(art);
        if (b == null) {
            backdrop.animate().alpha(0f).setDuration(400).start();
            scrim.animate().alpha(0f).setDuration(400).start();
            return;
        }
        backdrop.setImageBitmap(b);
        backdrop.animate().alpha(1f).setDuration(600).start();
        scrim.animate().alpha(1f).setDuration(600).start();
    }

    // ------------------------------------------------------------------ favourites

    private void loadFavs() {
        favs.clear();
        String saved = Store.prefs(this).getString(Store.FAVS, "");
        if (saved.isEmpty()) {
            for (String f : DEFAULT_FAVS) if (launchIntent(f) != null) favs.add(f);
        } else {
            for (String f : saved.split(",")) if (!f.isEmpty() && launchIntent(f) != null) favs.add(f);
        }
    }

    private void saveFavs() {
        Store.prefs(this).edit().putString(Store.FAVS, TextUtils.join(",", favs)).apply();
    }

    private void renderFavs() {
        favRow.removeAllViews();
        for (int i = 0; i < favs.size(); i++) {
            final int idx = i;
            final String pkg = favs.get(i);
            View tile = appTile(pkg, 128, 72);
            tile.setOnClickListener(v -> {
                if (moving >= 0) endMove();
                else launch(pkg);
            });
            tile.setOnLongClickListener(v -> {
                favMenu(idx);
                return true;
            });
            favRow.addView(tile, lp(128, 72, 0, 0, i == favs.size() - 1 ? 0 : 11, 0));
        }
        if (favs.isEmpty()) {
            TextView t = text("Добавь приложения: вкладка «Приложения» → удерживай OK", 13, sub, false);
            t.setPadding(px(10), px(26), px(10), px(26));
            favRow.addView(t);
        }
    }

    private void favMenu(int idx) {
        String name = label(favs.get(idx));
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle(name)
                .setItems(new String[]{"Переместить", "Убрать из избранного"}, (d, which) -> {
                    if (which == 0) startMove(idx);
                    else {
                        favs.remove(idx);
                        saveFavs();
                        renderFavs();
                    }
                })
                .show();
    }

    private void startMove(int idx) {
        moving = idx;
        showHint("◀ ▶  двигать        OK или Назад — готово", true);
        View v = favRow.getChildAt(idx);
        v.requestFocus();
        v.animate().translationY(-px(6)).setDuration(150).start();
    }

    private void endMove() {
        moving = -1;
        saveFavs();
        hideHint();
        for (int i = 0; i < favRow.getChildCount(); i++) favRow.getChildAt(i).setTranslationY(0);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (qMoving >= 0 && e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int to = qMoving + (k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                if (to >= 0 && to < tiles.size()) {
                    Collections.swap(tiles, qMoving, to);
                    qMoving = to;
                    buildQuick();
                    View v = tiles.get(to).view;
                    v.requestFocus();
                    v.setTranslationY(-px(5));
                }
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BACK) {
                endQuickMove();
                return true;
            }
        }
        if (moving >= 0 && e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int to = moving + (k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                if (to >= 0 && to < favs.size()) {
                    Collections.swap(favs, moving, to);
                    moving = to;
                    renderFavs();
                    View v = favRow.getChildAt(to);
                    v.requestFocus();
                    v.setTranslationY(-px(6));
                }
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BACK) {
                endMove();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** A banner tile for an app: its own TV banner, or its icon and name. */
    private View appTile(String pkg, int wDp, int hDp) {
        FrameLayout f = new FrameLayout(this);
        f.setFocusable(true);
        f.setClickable(true);
        f.setLayoutParams(new LinearLayout.LayoutParams(px(wDp), px(hDp)));
        Drawable banner = banner(pkg);
        if (banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setImageDrawable(banner);
            clip(iv, 11);
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            f.setBackground(round(light ? 0xFF1C2238 : 0xFF232838, 11, 0));
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            ImageView ic = new ImageView(this);
            ic.setImageDrawable(icon(pkg));
            box.addView(ic, new LinearLayout.LayoutParams(px(30), px(30)));
            TextView t = text(label(pkg), 10, Color.WHITE, true);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            box.addView(t, margins(0, 4, 0, 0));
            f.addView(box, new FrameLayout.LayoutParams(-1, -1));
        }
        focusRing(f, 11);
        return f;
    }

    private void renderAppsGrid() {
        appsGrid.removeAllViews();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> all = getPackageManager().queryIntentActivities(q, 0);
        List<String> pkgs = new ArrayList<>();
        for (ResolveInfo r : all) {
            String p = r.activityInfo.packageName;
            if (!pkgs.contains(p) && !p.equals(getPackageName()) && !HIDDEN.contains(p)) pkgs.add(p);
        }
        Collections.sort(pkgs, (a, b) -> label(a).compareToIgnoreCase(label(b)));
        pkgs.add(0, getPackageName());   // the Control centre itself (Smart Life, speed test, settings)
        LinearLayout row = null;
        for (int i = 0; i < pkgs.size(); i++) {
            final String pkg = pkgs.get(i);
            if (i % 6 == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                appsGrid.addView(row, margins(0, 0, 0, 22));
            }
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setClipChildren(false);
            View tile = appTile(pkg, 128, 72);
            tile.setOnClickListener(v -> {
                if (pkg.equals(getPackageName())) startActivity(new Intent(this, DimActivity.class));
                else launch(pkg);
            });
            tile.setOnLongClickListener(v -> {
                boolean has = favs.contains(pkg);
                new AlertDialog.Builder(this, dialogTheme())
                        .setTitle(label(pkg))
                        .setItems(new String[]{has ? "Убрать из избранного" : "Добавить в избранное"}, (d, w) -> {
                            if (has) favs.remove(pkg);
                            else if (favs.size() < MAX_FAVS) favs.add(pkg);
                            else Toast.makeText(this, "В избранном уже " + MAX_FAVS + ". Убери одно.", Toast.LENGTH_SHORT).show();
                            saveFavs();
                            renderFavs();
                        })
                        .show();
                return true;
            });
            cell.addView(tile);
            TextView t = text(pkg.equals(getPackageName()) ? "Центр управления" : label(pkg), 10, sub, false);
            t.setSingleLine(true);
            t.setMaxWidth(px(128));
            t.setEllipsize(TextUtils.TruncateAt.END);
            cell.addView(t, margins(2, 5, 0, 0));
            row.addView(cell, margins(0, 0, i % 6 == 5 ? 0 : 13, 0));
        }
    }

    // ------------------------------------------------------------------ quick tiles

    /** One slot of the bottom row: what it shows is set by its spec (see candidates()). */
    private final class Tile {
        final String spec;
        View view;
        TextView icon, name, subT;

        Tile(String spec) {
            this.spec = spec;
        }
    }

    private final List<Tile> tiles = new ArrayList<>();
    private int qMoving = -1;
    private static final int MAX_TILES = 6;

    private List<String> loadQuick() {
        List<String> out = new ArrayList<>();
        String saved = Store.prefs(this).getString(Store.QUICK, "");
        if (!saved.isEmpty()) {
            for (String sp : saved.split(",")) if (!sp.isEmpty()) out.add(sp);
            return out;
        }
        // first run: first HDMI, first switch, first sensor, internet, TorrServe, settings
        List<String[]> c = candidates();
        String firstInput = null, firstSwitch = null, firstSensor = null;
        for (String[] k : c) {
            if (firstInput == null && k[0].startsWith("input:")) firstInput = k[0];
            if (firstSwitch == null && k[0].startsWith("tuya:") && "switch".equals(k[2])) firstSwitch = k[0];
            if (firstSensor == null && k[0].startsWith("tuya:") && "sensor".equals(k[2])) firstSensor = k[0];
        }
        out.add(firstInput != null ? firstInput : "sources");
        if (firstSwitch != null) out.add(firstSwitch);
        if (firstSensor != null) out.add(firstSensor);
        out.add("net");
        out.add("torr");
        out.add("settings");
        return out;
    }

    private void saveQuick() {
        List<String> specs = new ArrayList<>();
        for (Tile t : tiles) specs.add(t.spec);
        Store.prefs(this).edit().putString(Store.QUICK, TextUtils.join(",", specs)).apply();
    }

    /** Everything a slot can show: {spec, label, kind}. */
    private List<String[]> candidates() {
        List<String[]> out = new ArrayList<>();
        try {
            TvInputManager tm = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            List<String[]> inputs = new ArrayList<>();
            for (TvInputInfo in : tm.getTvInputList()) {
                if (in.getParentId() != null || in.isPassthroughInput() == false && in.getType() != TvInputInfo.TYPE_TUNER) continue;
                CharSequence l = in.loadLabel(this);
                inputs.add(new String[]{"input:" + in.getId(), l == null ? "Вход" : l.toString(), "input"});
            }
            Collections.sort(inputs, (a, b) -> a[1].compareToIgnoreCase(b[1]));
            out.addAll(inputs);
        } catch (RuntimeException ignored) {
            // no TV inputs on this device
        }
        try {
            JSONArray d = new JSONArray(Store.prefs(this).getString(Store.TUYA_DEVICES, "[]"));
            for (int i = 0; i < d.length(); i++) {
                JSONObject o = d.getJSONObject(i);
                boolean sw = !o.optString("switch_code").isEmpty();
                out.add(new String[]{"tuya:" + o.optString("id"), o.optString("name"), sw ? "switch" : "sensor"});
            }
        } catch (Exception ignored) {
            // Smart Life not connected yet
        }
        out.add(new String[]{"sources", "Источники (выбор входа)", "fixed"});
        out.add(new String[]{"net", "Интернет и тест скорости", "fixed"});
        out.add(new String[]{"torr", "TorrServe", "fixed"});
        out.add(new String[]{"panel", "Панель Google TV (Google Home)", "fixed"});
        out.add(new String[]{"control", "Центр управления", "fixed"});
        out.add(new String[]{"settings", "Настройки", "fixed"});
        return out;
    }

    private void buildQuick() {
        boolean hadFocus = quickRow.findFocus() != null;
        int focusAt = -1;
        if (hadFocus) for (int i = 0; i < tiles.size(); i++) if (tiles.get(i).view.hasFocus()) focusAt = i;
        quickRow.removeAllViews();
        List<String> specs = tiles.isEmpty() ? loadQuick() : null;
        if (specs != null) for (String sp : specs) tiles.add(new Tile(sp));
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            final int idx = i;
            makeTile(t, i < tiles.size() - 1);
            t.view.setOnClickListener(v -> {
                if (qMoving >= 0) endQuickMove();
                else tap(t);
            });
            t.view.setOnLongClickListener(v -> {
                tileMenu(idx);
                return true;
            });
        }
        if (focusAt >= 0 && focusAt < tiles.size()) tiles.get(focusAt).view.requestFocus();
        renderQuick();
    }

    private void makeTile(Tile t, boolean gap) {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(px(12), 0, px(10), 0);
        c.setFocusable(true);
        c.setClickable(true);
        GradientDrawable g = round(glass, 11, line);
        c.setBackground(g);
        c.setOnFocusChangeListener((v, has) -> {
            g.setColor(has ? glassFocus : glass);
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).translationZ(has ? px(8) : 0).setDuration(150).start();
        });
        t.icon = text("", 16, txt, false);
        t.icon.setGravity(Gravity.CENTER);
        c.addView(t.icon, new LinearLayout.LayoutParams(px(22), -2));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        t.name = text("", 12, txt, true);
        t.name.setSingleLine(true);
        t.name.setEllipsize(TextUtils.TruncateAt.END);
        t.subT = text("", 9, sub, false);
        t.subT.setSingleLine(true);
        t.subT.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(t.name);
        box.addView(t.subT);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -2, 1);
        bl.setMargins(px(8), 0, 0, 0);
        c.addView(box, bl);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -1, 1);
        if (gap) cl.setMargins(0, 0, px(10), 0);
        quickRow.addView(c, cl);
        t.view = c;
    }

    private JSONObject tuyaDevice(String id) {
        try {
            JSONArray d = new JSONArray(Store.prefs(this).getString(Store.TUYA_DEVICES, "[]"));
            for (int i = 0; i < d.length(); i++) if (id.equals(d.getJSONObject(i).optString("id"))) return d.getJSONObject(i);
        } catch (Exception ignored) {
            // not loaded yet
        }
        return null;
    }

    private String inputLabel(String id) {
        try {
            TvInputManager tm = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            TvInputInfo in = tm.getTvInputInfo(id);
            CharSequence l = in == null ? null : in.loadLabel(this);
            return l == null ? "Вход" : l.toString();
        } catch (RuntimeException e) {
            return "Вход";
        }
    }

    private void renderQuick() {
        SharedPreferences p = Store.prefs(this);
        for (Tile t : tiles) {
            String sp = t.spec;
            if (sp.startsWith("input:")) {
                t.icon.setText("▭");
                t.name.setText(inputLabel(sp.substring(6)));
                t.subT.setText("Переключить вход");
            } else if (sp.startsWith("tuya:")) {
                JSONObject o = tuyaDevice(sp.substring(5));
                if (o == null) {
                    t.icon.setText("💡");
                    t.name.setText("Smart Life");
                    t.subT.setText(Store.str(this, Store.TUYA_ID).isEmpty() ? "не подключён" : "загрузка…");
                    continue;
                }
                String name = o.optString("name");
                boolean sw = !o.optString("switch_code").isEmpty();
                boolean online = o.optBoolean("online", true);
                if (sw) {
                    String low = name.toLowerCase(new Locale("ru"));
                    t.icon.setText(low.contains("розет") || low.contains("plug") ? "🔌" : "💡");
                    t.name.setText(name);
                    boolean on = o.optBoolean("switch_on");
                    t.subT.setText(!online ? "не в сети" : on ? "Включён  ·  OK" : "Выключен  ·  OK");
                } else {
                    String[] parts = o.optString("summary").split("  ·  ");
                    t.icon.setText(o.optString("summary").contains("°") ? "🌡" : "◉");
                    t.name.setText(parts.length > 1 ? parts[0] + "  ·  " + parts[1] : parts[0].isEmpty() ? name : parts[0]);
                    t.subT.setText(online ? name : "не в сети");
                }
            } else if ("net".equals(sp)) {
                t.icon.setText("⇅");
                t.name.setText("Интернет");
                boolean ok = p.getBoolean(Store.NET_OK, false);
                long sAt = p.getLong(Store.SPEED_AT, 0);
                t.subT.setText(p.getLong(Store.NET_AT, 0) == 0 ? "проверка…" : !ok ? "нет подключения"
                        : sAt > 0 ? "Есть  ·  " + Math.round(p.getFloat(Store.SPEED_DOWN, 0)) + " Мбит/с" : "Есть  ·  OK — тест");
            } else if ("torr".equals(sp)) {
                t.icon.setText("◎");
                t.name.setText("TorrServe");
                String tv = torrVersion;
                t.subT.setText(launchIntent(TORRSERVE) == null ? "не установлен"
                        : tv == null ? "не отвечает  ·  OK — открыть" : "Работает  ·  " + tv);
            } else if ("panel".equals(sp)) {
                t.icon.setText("⌂");
                t.name.setText("Google Home");
                t.subT.setText("Панель Google TV");
            } else if ("sources".equals(sp)) {
                t.icon.setText("⇄");
                t.name.setText("Источники");
                t.subT.setText("HDMI и ТВ");
            } else if ("control".equals(sp)) {
                t.icon.setText("◫");
                t.name.setText("Центр управления");
                t.subT.setText("Тревоги, свет, сеть");
            } else {
                t.icon.setText("⚙");
                t.name.setText("Настройки");
                t.subT.setText("Вид, Must, ключи");
            }
        }
    }

    private void tap(Tile t) {
        String sp = t.spec;
        if (sp.startsWith("input:")) openInput(sp.substring(6));
        else if (sp.startsWith("tuya:")) toggleTuya(t, sp.substring(5));
        else if ("net".equals(sp) || "control".equals(sp)) startActivity(new Intent(this, DimActivity.class));
        else if ("torr".equals(sp)) launch(TORRSERVE);
        else if ("panel".equals(sp)) openGoogleTvPanel();
        else if ("sources".equals(sp)) openInput(null);
        else openSettings();
    }

    private void tileMenu(int idx) {
        List<String> items = new ArrayList<>();
        items.add("Заменить на…");
        items.add("Переместить");
        if (tiles.size() > 1) items.add("Убрать");
        if (tiles.size() < MAX_TILES) items.add("Добавить плитку рядом");
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle(tiles.get(idx).name.getText())
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    String what = items.get(w);
                    if (what.startsWith("Заменить")) pickTile(idx, true);
                    else if (what.startsWith("Переместить")) startQuickMove(idx);
                    else if (what.startsWith("Убрать")) {
                        tiles.remove(idx);
                        saveQuick();
                        buildQuick();
                    } else pickTile(idx, false);
                })
                .show();
    }

    /** Choose what a slot shows: replace slot idx, or add a new one after it. */
    private void pickTile(int idx, boolean replace) {
        List<String[]> all = candidates();
        List<String[]> free = new ArrayList<>();
        for (String[] c : all) {
            boolean used = false;
            for (Tile t : tiles) if (t.spec.equals(c[0])) used = true;
            if (!used) free.add(c);
        }
        if (free.isEmpty()) {
            Toast.makeText(this, "Всё уже на панели", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[free.size()];
        for (int i = 0; i < free.size(); i++) {
            String[] c = free.get(i);
            String kind = "input".equals(c[2]) ? "Вход:  " : "switch".equals(c[2]) ? "Свет / розетка:  " : "sensor".equals(c[2]) ? "Датчик:  " : "";
            labels[i] = kind + c[1];
        }
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle(replace ? "Показывать на этой плитке" : "Новая плитка")
                .setItems(labels, (d, w) -> {
                    Tile t = new Tile(free.get(w)[0]);
                    if (replace) tiles.set(idx, t);
                    else tiles.add(idx + 1, t);
                    saveQuick();
                    buildQuick();
                    tiles.get(replace ? idx : idx + 1).view.requestFocus();
                })
                .show();
    }

    private void startQuickMove(int idx) {
        qMoving = idx;
        showHint("◀ ▶  двигать        OK или Назад — готово", true);
        View v = tiles.get(idx).view;
        v.requestFocus();
        v.animate().translationY(-px(5)).setDuration(150).start();
    }

    private void endQuickMove() {
        qMoving = -1;
        saveQuick();
        for (Tile t : tiles) t.view.setTranslationY(0);
        hideHint();
    }

    private void openInput(String inputId) {
        try {
            TvInputManager tm = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            TvInputInfo in = inputId == null ? null : tm.getTvInputInfo(inputId);
            if (in != null && in.getType() == TvInputInfo.TYPE_TUNER) {
                Intent live = launchIntent("com.mediatek.wwtv.tvcenter");
                if (live == null) live = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(inputId));
                startActivity(live.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
        } catch (RuntimeException ignored) {
            // treat it as a passthrough input
        }
        if (inputId != null) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException ignored) {
                // fall through to the inputs page
            }
        }
        try {
            startActivity(new Intent("android.settings.INPUT_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private void checkTorrServe() {
        io.execute(() -> {
            torrVersion = TorrServe.version();
            main.post(this::renderQuick);
        });
    }

    private void toggleTuya(Tile t, String devId) {
        JSONObject o = tuyaDevice(devId);
        if (o == null || o.optString("switch_code").isEmpty()) {
            startActivity(new Intent(this, DimActivity.class));   // a sensor: show the full screen
            return;
        }
        String id = Store.str(this, Store.TUYA_ID), secret = Store.str(this, Store.TUYA_SECRET);
        final String code = o.optString("switch_code"), name = o.optString("name");
        final boolean on = o.optBoolean("switch_on");
        t.subT.setText(on ? "Выключаю…" : "Включаю…");
        io.execute(() -> {
            try {
                Tuya.command(id, secret, devId, code, !on);
                Thread.sleep(1200);
                Store.prefs(this).edit().putLong(Store.TUYA_AT, 0).apply();
                Refresher.kick(this, true);
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, name + ": " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    // ------------------------------------------------------------------ settings

    private void openSettings() {
        String[] items = {
                light ? "Тема: светлая  →  тёмная" : "Тема: тёмная  →  светлая",
                "Must: " + (Store.str(this, Store.MUST_NICK).isEmpty() ? "указать ник" : "@" + Store.str(this, Store.MUST_NICK)),
                "Тревоги, Smart Life, Telegram",
                "Панель Google TV (Google Home)",
                "Настройки телевизора"};
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("Настройки")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            Store.prefs(this).edit().putString(Store.THEME, light ? "dark" : "light").apply();
                            recreate();
                            break;
                        case 1: askMust(); break;
                        case 2: startActivity(new Intent(this, DimActivity.class)); break;
                        case 3: openGoogleTvPanel(); break;
                        default:
                            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                })
                .show();
    }

    private void askMust() {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setText(Store.str(this, Store.MUST_NICK));
        e.setHint("ник, например sergvo1kov");
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("Must: ник профиля")
                .setMessage("Профиль должен быть открытым. Показываем «Хочу посмотреть».")
                .setView(e)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String nick = e.getText().toString().trim().replace("@", "").replace("https://mustapp.com/", "");
                    Store.prefs(this).edit().putString(Store.MUST_NICK, nick).putLong(Store.MUST_AT, 0).apply();
                    mustTried = 0;
                    refreshMust(true);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void openGoogleTvPanel() {
        try {
            startActivity(new Intent().setComponent(new ComponentName(HomeRedirectService.STOCK,
                    HomeRedirectService.STOCK + ".dashboard.DashboardHandler")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Удерживай кнопку Home на пульте", Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ helpers

    private Intent launchIntent(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }

    private void launch(String pkg) {
        Intent i = launchIntent(pkg);
        if (i == null) {
            Toast.makeText(this, "Приложение не установлено", Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private Drawable banner(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i != null) {
                Drawable d = pm.getActivityBanner(i);
                if (d != null) return d;
            }
            return pm.getApplicationBanner(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private Drawable icon(String pkg) {
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return new ColorDrawable(Color.GRAY);
        }
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private void focusRing(View v, int radiusDp) {
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(px(radiusDp));
        ring.setStroke(px(2.5f), Color.TRANSPARENT);
        ring.setColor(Color.TRANSPARENT);
        clip(v, radiusDp);   // the tile, its picture and the ring share one rounded outline
        if (v instanceof FrameLayout) ((FrameLayout) v).setForeground(ring);
        v.setOnFocusChangeListener((view, has) -> {
            ring.setStroke(px(2.5f), has ? (light ? 0xFF0D1220 : Color.WHITE) : Color.TRANSPARENT);
            view.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f)
                    .translationZ(has ? px(14) : 0).setDuration(160).start();
        });
    }

    private View circle(String label, int sizeDp, int colour, View.OnClickListener onClick) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(colour);
        f.setBackground(g);
        f.setFocusable(true);
        f.setClickable(true);
        f.setOnClickListener(onClick);
        TextView t = text(label, sizeDp > 30 ? 15 : 12, Color.WHITE, false);
        t.setGravity(Gravity.CENTER);
        f.addView(t, new FrameLayout.LayoutParams(-1, -1));
        f.setLayoutParams(new LinearLayout.LayoutParams(px(sizeDp), px(sizeDp)));
        f.setOnFocusChangeListener((v, has) -> {
            g.setStroke(has ? px(2) : 0, Color.WHITE);
            v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(140).start();
        });
        return f;
    }

    private void pill(TextView t, int colour, int focusColour) {
        GradientDrawable g = round(colour, 20, 0);
        t.setBackground(g);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnFocusChangeListener((v, has) -> {
            g.setColor(has ? focusColour : colour);
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(140).start();
        });
    }

    private void clip(View v, int radiusDp) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px(radiusDp));
            }
        });
        v.setClipToOutline(true);
    }

    private GradientDrawable round(int colour, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(radiusDp));
        g.setColor(colour);
        if (stroke != 0) g.setStroke(1, stroke);
        return g;
    }

    private TextView text(String s, float sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    private LinearLayout.LayoutParams endAligned(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.END;
        lp.topMargin = px(topDp);
        return lp;
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(px(l), px(t), px(r), px(b));
        return lp;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w < 0 ? w : px(w), h < 0 ? h : px(h));
        lp.setMargins(px(l), px(t), px(r), px(b));
        return lp;
    }

    private int dialogTheme() {
        return light ? android.R.style.Theme_Material_Light_Dialog_Alert : android.R.style.Theme_Material_Dialog_Alert;
    }

    private static int darker(int c) {
        return Color.rgb(Color.red(c) * 6 / 10, Color.green(c) * 6 / 10, Color.blue(c) * 6 / 10);
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private static String mmss(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    private static String hhmm(long ms) {
        return ms <= 0 ? "" : new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(ms));
    }

    /** How long the alert has been on: "23 мин", "1 ч 05 мин". */
    static final class HomeStatusTime {
        private HomeStatusTime() {}

        static String elapsed(long since) {
            if (since <= 0) return "";
            long min = Math.max(0, (System.currentTimeMillis() - since) / 60_000L);
            return min < 60 ? min + " мин" : (min / 60) + " ч " + String.format(Locale.ROOT, "%02d", min % 60) + " мин";
        }
    }
}
