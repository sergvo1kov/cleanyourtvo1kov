package ua.volkov.dim;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The quick settings sheet that slides in from the right: network, Bluetooth, picture mode, standby,
 * volume, the screensaver, a way back to the stock Google TV home, and all settings.
 */
final class QuickPanel {
    interface Host {
        void onPanelClosed();

        /** The app's own settings: theme, Must, TMDB, keys. */
        void openAppSettings();
    }

    private final Activity a;
    private final FrameLayout root;
    private final ViewGroup behind;
    private final boolean light;
    private final Host host;
    private final float dp;
    private final int txt, sub, tile, tileFocus;

    private FrameLayout overlay;
    private LinearLayout sheet;
    private TextView netSub, btSub, picSub, volPct, saverSub, timeoutSub;
    private View volFill;
    private LinearLayout volBar;

    private static final int[] TIMEOUTS_MIN = {5, 10, 15, 30, 60};

    QuickPanel(Activity a, FrameLayout root, ViewGroup behind, boolean light, Host host) {
        this.a = a;
        this.root = root;
        this.behind = behind;
        this.light = light;
        this.host = host;
        this.dp = a.getResources().getDisplayMetrics().density;
        txt = light ? 0xFF0D1220 : 0xFFFFFFFF;
        sub = light ? 0x990D1220 : 0x99FFFFFF;
        tile = light ? 0x14000000 : 0x14FFFFFF;
        tileFocus = light ? 0xFFFFFFFF : 0x3DFFFFFF;
    }

    boolean isOpen() {
        return overlay != null;
    }

    void open() {
        if (overlay != null) return;
        overlay = new FrameLayout(a);
        overlay.setBackgroundColor(light ? 0x33000000 : 0x66000000);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> close());
        sheet = build();
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(px(330), -2, Gravity.END | Gravity.CENTER_VERTICAL);
        sl.setMargins(0, px(24), px(28), px(24));
        overlay.addView(sheet, sl);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        behind.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        refresh();
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(180).start();
        sheet.setTranslationX(px(60));
        sheet.animate().translationX(0f).setDuration(300).setInterpolator(new DecelerateInterpolator(2f)).start();
        sheet.getChildAt(1).requestFocus();
    }

    void close() {
        if (overlay == null) return;
        FrameLayout o = overlay;
        overlay = null;
        sheet.animate().translationX(px(60)).setDuration(180).start();
        o.animate().alpha(0f).setDuration(180).withEndAction(() -> root.removeView(o)).start();
        behind.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        host.onPanelClosed();
    }

    // ------------------------------------------------------------------ layout

    private LinearLayout build() {
        LinearLayout s = new LinearLayout(a);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setPadding(px(18), px(16), px(18), px(16));
        s.setClickable(true);   // a press on the sheet itself does not close it
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(24));
        bg.setColor(light ? 0xF2F4F6FA : 0xF01B2030);
        bg.setStroke(1, light ? 0x1F000000 : 0x26FFFFFF);
        s.setBackground(bg);
        s.setElevation(px(24));
        s.setClipChildren(false);

        s.addView(text("Настройки", 17, txt, true), margins(4, 0, 0, 10));

        LinearLayout r1 = row();
        LinearLayout net = tile("⌁", "Сеть", v -> settings(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        netSub = sub(net);
        r1.addView(net, half(true));
        LinearLayout bt = tile("ᛒ", "Bluetooth", v -> openBluetooth());
        btSub = sub(bt);
        r1.addView(bt, half(false));
        s.addView(r1);

        LinearLayout r2 = row();
        LinearLayout pic = tile("◐", "Режим картинки", v -> openPicture());
        picSub = sub(pic);
        r2.addView(pic, half(true));
        LinearLayout off = tile("⏻", "Выключить", v -> standby());
        sub(off).setText("Режим ожидания");
        r2.addView(off, half(false));
        s.addView(r2, margins(0, 8, 0, 0));

        s.addView(volume(), margins(0, 8, 0, 0));

        s.addView(text("ЗАСТАВКА", 10, sub, true), margins(4, 14, 0, 6));
        LinearLayout r3 = row();
        LinearLayout saver = tile("✦", "Запустить", v -> startSaver());
        saverSub = sub(saver);
        r3.addView(saver, half(true));
        LinearLayout timeout = tile("◷", "Включать через", v -> nextTimeout());
        timeoutSub = sub(timeout);
        r3.addView(timeout, half(false));
        s.addView(r3);

        LinearLayout stock = tile("⌂", "Вернуться на основной экран", v -> stockHome());
        sub(stock).setText("Стандартный лаунчер Google TV");
        s.addView(stock, margins(0, 8, 0, 0));
        stock.getLayoutParams().width = -1;

        LinearLayout r4 = row();
        LinearLayout app = tile("✎", "Flick TV", v -> {
            close();
            host.openAppSettings();
        });
        sub(app).setText("Тема, Must, Lampa, ключи");
        r4.addView(app, half(true));
        LinearLayout all = tile("⚙", "Все настройки ›", v -> settings(new Intent(Settings.ACTION_SETTINGS)));
        sub(all).setText("Телевизор");
        r4.addView(all, half(false));
        s.addView(r4, margins(0, 8, 0, 0));
        return s;
    }

    private LinearLayout volume() {
        LinearLayout v = new LinearLayout(a);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(px(12), px(10), px(12), px(10));
        GradientDrawable g = round(tile, 14);
        v.setBackground(g);
        v.setFocusable(true);
        v.setClickable(true);
        v.addView(text("🔈", 13, txt, false), margins(0, 0, 10, 0));
        volBar = new LinearLayout(a);
        volBar.setBackground(round(light ? 0x1F000000 : 0x26FFFFFF, 3));
        volFill = new View(a);
        volFill.setBackground(round(txt, 3));
        volBar.addView(volFill, new LinearLayout.LayoutParams(0, px(6)));
        v.addView(volBar, new LinearLayout.LayoutParams(0, px(6), 1));
        volPct = text("", 11, sub, false);
        volPct.setMinWidth(px(38));
        volPct.setGravity(Gravity.END);
        v.addView(volPct);
        v.setOnFocusChangeListener((view, has) -> {
            g.setColor(has ? tileFocus : tile);
            view.animate().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f).setDuration(140).start();
        });
        v.setOnKeyListener((view, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                audio().adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        code == KeyEvent.KEYCODE_DPAD_RIGHT ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
                renderVolume();
                return true;
            }
            return false;
        });
        v.setOnClickListener(view -> {
            audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
            renderVolume();
        });
        return v;
    }

    private LinearLayout tile(String icon, String title, View.OnClickListener onClick) {
        LinearLayout t = new LinearLayout(a);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(px(12), px(9), px(10), px(9));
        GradientDrawable g = round(tile, 14);
        t.setBackground(g);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(onClick);
        t.setOnFocusChangeListener((view, has) -> {
            g.setColor(has ? tileFocus : tile);
            view.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).translationZ(has ? px(6) : 0)
                    .setDuration(140).start();
        });
        TextView i = text(icon, 14, txt, false);
        i.setGravity(Gravity.CENTER);
        i.setBackground(round(light ? 0x14000000 : 0x1FFFFFFF, 13));
        t.addView(i, new LinearLayout.LayoutParams(px(26), px(26)));
        LinearLayout words = new LinearLayout(a);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(title, 11.5f, txt, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(name);
        TextView s = text("", 9.5f, sub, false);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(s);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, -2, 1);
        wl.setMargins(px(9), 0, 0, 0);
        t.addView(words, wl);
        return t;
    }

    private static TextView sub(LinearLayout tile) {
        return (TextView) ((LinearLayout) tile.getChildAt(1)).getChildAt(1);
    }

    // ------------------------------------------------------------------ state

    private void refresh() {
        netSub.setText(network());
        BluetoothAdapter b = BluetoothAdapter.getDefaultAdapter();
        btSub.setText(b == null ? "нет" : b.isEnabled() ? "Включён" : "Выключен");
        picSub.setText(pictureMode(a));
        saverSub.setText(saverName());
        renderTimeout();
        renderVolume();
    }

    private String network() {
        try {
            ConnectivityManager cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            if (c == null) return "Нет подключения";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet · подключено";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi‑Fi · подключено";
            return "Подключено";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The picture modes as this TV's settings name them (TvSettingsPlus, picture_mode_ref_entries). */
    static String pictureMode(Context ctx) {
        int m = Settings.Global.getInt(ctx.getContentResolver(), "picture_mode", -1);
        switch (m) {
            case 0: return "Пользовательский";
            case 1: return "Кино";
            case 2: return "Спорт";
            case 3: return "Насыщенный";
            case 4: return "Яркий";
            case 5: return "Dolby Vision Bright";
            case 6: return "Dolby Vision Dark";
            case 7: return "Стандарт";
            case 8: return "Обычный";
            case 9: return "Фильм";
            case 10: return "Игра";
            case 11: return "Энергосбережение";
            case 12: return "AI PQ";
            case 13: return "Dolby Vision Vivid";
            case 14: return "Dolby Vision Game";
            default: return "Открыть";
        }
    }

    private String saverName() {
        String comp = Settings.Secure.getString(a.getContentResolver(), "screensaver_components");
        if (comp == null || comp.isEmpty()) return "Заставка";
        ComponentName cn = ComponentName.unflattenFromString(comp.split(",")[0]);
        if (cn == null) return "Заставка";
        try {
            PackageManager pm = a.getPackageManager();
            CharSequence l = pm.getServiceInfo(cn, 0).loadLabel(pm);
            return l == null ? "Заставка" : l.toString();
        } catch (Exception e) {
            return "Заставка";
        }
    }

    private void renderTimeout() {
        int ms = Settings.System.getInt(a.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 0);
        timeoutSub.setText(ms <= 0 ? "—" : ms >= 3_600_000 ? (ms / 3_600_000) + " ч" : (ms / 60_000) + " мин");
    }

    private void renderVolume() {
        AudioManager am = audio();
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean muted = am.isStreamMute(AudioManager.STREAM_MUSIC);
        int pct = Math.round(cur * 100f / max);
        volPct.setText(muted ? "без звука" : pct + "%");
        volBar.post(() -> {
            ViewGroup.LayoutParams lp = volFill.getLayoutParams();
            lp.width = muted ? 0 : Math.round(volBar.getWidth() * cur / (float) max);
            volFill.setLayoutParams(lp);
        });
    }

    private AudioManager audio() {
        return (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
    }

    // ------------------------------------------------------------------ actions

    private void settings(Intent i) {
        close();
        try {
            a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            a.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private void openBluetooth() {
        // Android TV keeps Bluetooth under "Remotes & accessories"
        Intent i = new Intent("com.android.tv.settings.CONNECTED_DEVICES");
        if (i.resolveActivity(a.getPackageManager()) == null) i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        if (i.resolveActivity(a.getPackageManager()) == null) i = new Intent(Settings.ACTION_SETTINGS);
        settings(i);
    }

    private void openPicture() {
        Intent i = new Intent().setComponent(new ComponentName("com.android.tv.settings",
                "com.android.tv.settings.partnercustomizer.picture.PictureActivity"));
        if (i.resolveActivity(a.getPackageManager()) == null) i = new Intent(Settings.ACTION_DISPLAY_SETTINGS);
        settings(i);
    }

    private void standby() {
        if (!HomeRedirectService.sleep()) {
            Toast.makeText(a, "Нужна служба «Flick TV: кнопка Home» в Спец. возможностях", Toast.LENGTH_LONG).show();
            return;
        }
        close();
    }

    private void startSaver() {
        close();
        try {
            a.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(
                    new ComponentName("com.android.systemui", "com.android.systemui.Somnambulator"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            settings(new Intent(Settings.ACTION_DREAM_SETTINGS));
        }
    }

    private void nextTimeout() {
        if (!Settings.System.canWrite(a)) {
            settings(new Intent(Settings.ACTION_DREAM_SETTINGS));
            return;
        }
        int cur = Settings.System.getInt(a.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 0) / 60_000;
        int next = TIMEOUTS_MIN[0];
        for (int m : TIMEOUTS_MIN) {
            if (m > cur) {
                next = m;
                break;
            }
        }
        Settings.System.putInt(a.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, next * 60_000);
        renderTimeout();
    }

    private void stockHome() {
        HomeRedirectService.allowStock = true;   // until our Home shows again
        close();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(HomeRedirectService.STOCK);
        try {
            a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            HomeRedirectService.allowStock = false;
            Toast.makeText(a, "Стандартный лаунчер недоступен", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ helpers

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(a);
        r.setClipChildren(false);
        return r;
    }

    private LinearLayout.LayoutParams half(boolean left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(0, 0, left ? px(8) : 0, 0);
        return lp;
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(px(l), px(t), px(r), px(b));
        return lp;
    }

    private GradientDrawable round(int colour, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(radiusDp));
        g.setColor(colour);
        return g;
    }

    private TextView text(String s, float sp, int colour, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
