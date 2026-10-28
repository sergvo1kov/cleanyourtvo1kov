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
    private TextView caption, dateLine, alertPill;
    private View statusDot;
    private GradientDrawable dotShape;
    private boolean pulsing;
    private TextView tabHome, tabApps;
    private FrameLayout hero;
    private String heroMode = "";
    private LinearLayout favRow;
    private LinearLayout quickRow;
    private TextView hint;

    // hero (music)
    private ImageView npArt;
    private TextView npKicker, npTitle, npArtist, npPos, npDur, npPlay, npOpen;
    private View npFill;
    private NowPlaying.Track track;

    // quick tiles
    private TextView qHdmiName, qHdmiSub, qLightName, qLightSub, qSensorName, qSensorSub, qNetSub, qTorrSub;
    private String hdmiInput, lightId, lightCode, lightName;
    private boolean lightOn;
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
        io.execute(() -> LampaCards.probe(getApplicationContext()));
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
        header.addView(logo, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        LinearLayout tabs = new LinearLayout(this);
        tabHome = tab("Главная", v -> showApps(false));
        tabApps = tab("Приложения", v -> showApps(true));
        TextView tabLife = tab("Smart Life", v -> startActivity(new Intent(this, DimActivity.class)));
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
        left.addView(text("Для тебя", 30, txt, true));
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
        hint = text("◉  Открыть        ◎  Удерживай OK — переставить или убрать", 10, sub, false);
        hint.setPadding(0, px(6), 0, 0);
        hintBar.addView(hint);
        root.addView(hintBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

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
        if (hint != null) hint.setText(apps ? "◉  Открыть        ◎  Удерживай OK — добавить в избранное или убрать"
                : "◉  Открыть        ◎  Удерживай OK — переставить или убрать");
        homePage.setVisibility(apps ? View.GONE : View.VISIBLE);
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
        // the dot by the wordmark: green calm, red (pulsing) alert, orange partial, grey unknown
        dotShape.setColor(c);
        boolean pulse = state == Alerts.ALERT;
        if (pulse != pulsing) {
            pulsing = pulse;
            statusDot.animate().cancel();
            if (pulse) pulseDot(); else statusDot.setAlpha(1f);
        }
        alertPill.setTextColor(light ? darker(c) : c);
        alertPill.setBackground(round((c & 0x00FFFFFF) | 0x29000000, 12, 0));
    }

    private void pulseDot() {
        if (!pulsing) return;
        float to = statusDot.getAlpha() > 0.6f ? 0.25f : 1f;
        statusDot.animate().alpha(to).setDuration(700).withEndAction(this::pulseDot).start();
    }

    // ------------------------------------------------------------------ hero

    private void renderHero() {
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
            case "must": caption.setText("Must  ·  Похоже на то, что ты смотрел  ·  OK — открыть в Lampa"); break;
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
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            FrameLayout frame = new FrameLayout(this);
            frame.setFocusable(true);
            frame.setClickable(true);
            frame.setOnClickListener(v -> openInLampa(title));
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

    private JSONArray mustList() {
        try {
            return new JSONArray(Store.prefs(this).getString(Store.MUST_LIST, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
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
        hint.setText("◀ ▶  двигать        OK или Назад — готово");
        View v = favRow.getChildAt(idx);
        v.requestFocus();
        v.animate().translationY(-px(6)).setDuration(150).start();
    }

    private void endMove() {
        moving = -1;
        saveFavs();
        hint.setText("◉  Открыть        ◎  Удерживай OK — переставить или убрать");
        for (int i = 0; i < favRow.getChildCount(); i++) favRow.getChildAt(i).setTranslationY(0);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
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
            if (!pkgs.contains(p) && !p.equals(getPackageName())) pkgs.add(p);
        }
        Collections.sort(pkgs, (a, b) -> label(a).compareToIgnoreCase(label(b)));
        pkgs.add(0, getPackageName());   // Дом itself (Smart Life, speed test, settings)
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
            TextView t = text(pkg.equals(getPackageName()) ? "Дом" : label(pkg), 10, sub, false);
            t.setSingleLine(true);
            t.setMaxWidth(px(128));
            t.setEllipsize(TextUtils.TruncateAt.END);
            cell.addView(t, margins(2, 5, 0, 0));
            row.addView(cell, margins(0, 0, i % 6 == 5 ? 0 : 13, 0));
        }
    }

    // ------------------------------------------------------------------ quick tiles

    private void buildQuick() {
        quickRow.removeAllViews();
        TextView[] h = quick("▭", v -> openInput(), true);
        qHdmiName = h[0];
        qHdmiSub = h[1];
        TextView[] l = quick("💡", v -> toggleLight(), true);
        qLightName = l[0];
        qLightSub = l[1];
        TextView[] s = quick("🌡", v -> startActivity(new Intent(this, DimActivity.class)), true);
        qSensorName = s[0];
        qSensorSub = s[1];
        TextView[] n = quick("⇅", v -> startActivity(new Intent(this, DimActivity.class)), true);
        n[0].setText("Интернет");
        qNetSub = n[1];
        TextView[] t = quick("◎", v -> launch(TORRSERVE), true);
        t[0].setText("TorrServe");
        qTorrSub = t[1];
        TextView[] st = quick("⚙", v -> openSettings(), false);
        st[0].setText("Настройки");
        st[1].setText("Вид, Must, ключи");
        findHdmi();
        renderQuick();
    }

    private TextView[] quick(String icon, View.OnClickListener onClick, boolean gap) {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(px(12), 0, px(10), 0);
        c.setFocusable(true);
        c.setClickable(true);
        c.setOnClickListener(onClick);
        GradientDrawable g = round(glass, 11, line);
        c.setBackground(g);
        c.setOnFocusChangeListener((v, has) -> {
            g.setColor(has ? glassFocus : glass);
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).translationZ(has ? px(8) : 0).setDuration(150).start();
        });
        TextView ic = text(icon, 16, txt, false);
        ic.setGravity(Gravity.CENTER);
        c.addView(ic, new LinearLayout.LayoutParams(px(22), -2));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView name = text("", 12, txt, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView subT = text("", 9, sub, false);
        subT.setSingleLine(true);
        subT.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(name);
        box.addView(subT);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -2, 1);
        bl.setMargins(px(8), 0, 0, 0);
        c.addView(box, bl);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -1, 1);
        if (gap) cl.setMargins(0, 0, px(10), 0);
        quickRow.addView(c, cl);
        return new TextView[]{name, subT};
    }

    private void findHdmi() {
        qHdmiName.setText("Источники");
        qHdmiSub.setText("HDMI и ТВ");
        try {
            TvInputManager tm = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            for (TvInputInfo in : tm.getTvInputList()) {
                if (in.getType() == TvInputInfo.TYPE_HDMI && in.getParentId() == null) {
                    hdmiInput = in.getId();
                    CharSequence name = in.loadLabel(this);
                    qHdmiName.setText(name == null ? "HDMI" : name.toString());
                    qHdmiSub.setText("Переключить вход");
                    break;
                }
            }
        } catch (RuntimeException ignored) {
            // no TV input service: the tile opens the inputs settings
        }
    }

    private void openInput() {
        if (hdmiInput != null) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(hdmiInput))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException ignored) {
                // fall through to the settings page
            }
        }
        try {
            startActivity(new Intent("android.settings.INPUT_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
        }
    }

    private void renderQuick() {
        SharedPreferences p = Store.prefs(this);
        // Smart Life: first switch and first sensor
        lightId = null;
        qLightName.setText("Свет");
        qLightSub.setText(Store.str(this, Store.TUYA_ID).isEmpty() ? "Smart Life не подключён" : "загрузка…");
        qSensorName.setText("Датчик");
        qSensorSub.setText("—");
        try {
            JSONArray d = new JSONArray(p.getString(Store.TUYA_DEVICES, "[]"));
            boolean gotSensor = false;
            for (int i = 0; i < d.length(); i++) {
                JSONObject o = d.getJSONObject(i);
                if (lightId == null && !o.optString("switch_code").isEmpty()) {
                    lightId = o.optString("id");
                    lightCode = o.optString("switch_code");
                    lightName = o.optString("name");
                    lightOn = o.optBoolean("switch_on");
                    qLightName.setText(lightName);
                    qLightSub.setText(!o.optBoolean("online", true) ? "не в сети" : lightOn ? "Включён  ·  OK" : "Выключен  ·  OK");
                } else if (!gotSensor && o.optString("switch_code").isEmpty() && o.optString("summary").contains("°")) {
                    gotSensor = true;
                    String[] parts = o.optString("summary").split("  ·  ");
                    qSensorName.setText(parts.length > 1 ? parts[0] + "  ·  " + parts[1] : parts[0]);
                    qSensorSub.setText(o.optString("name"));
                }
            }
        } catch (Exception ignored) {
            // no devices yet
        }
        boolean ok = p.getBoolean(Store.NET_OK, false);
        long sAt = p.getLong(Store.SPEED_AT, 0);
        qNetSub.setText(p.getLong(Store.NET_AT, 0) == 0 ? "проверка…" : !ok ? "нет подключения"
                : sAt > 0 ? "Есть  ·  " + Math.round(p.getFloat(Store.SPEED_DOWN, 0)) + " Мбит/с" : "Есть  ·  OK — тест");
        String tv = torrVersion;
        if (launchIntent(TORRSERVE) == null) qTorrSub.setText("не установлен");
        else qTorrSub.setText(tv == null ? "не отвечает  ·  OK — открыть" : "Работает  ·  " + tv);
    }

    private void checkTorrServe() {
        io.execute(() -> {
            torrVersion = TorrServe.version();
            main.post(this::renderQuick);
        });
    }

    private void toggleLight() {
        if (lightId == null) {
            startActivity(new Intent(this, DimActivity.class));
            return;
        }
        String id = Store.str(this, Store.TUYA_ID), secret = Store.str(this, Store.TUYA_SECRET);
        final String dev = lightId, code = lightCode, name = lightName;
        final boolean on = lightOn;
        qLightSub.setText(on ? "Выключаю…" : "Включаю…");
        io.execute(() -> {
            try {
                Tuya.command(id, secret, dev, code, !on);
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
