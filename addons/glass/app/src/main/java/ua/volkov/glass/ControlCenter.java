package ua.volkov.glass;

import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The tvOS Control Center, top right: StandBy (the screensaver), network, Bluetooth, inputs, settings,
 * the alert state, Smart Life lights as round toggles, sleep, TorrServe, and the switch back to Flick TV.
 */
final class ControlCenter extends Panel {
    private TextView time, alertHead, alertSub, netSub, btSub, torrSub;
    private LinearLayout lights;
    private View first;

    ControlCenter(MainActivity a, FrameLayout root, Ui ui) {
        super(a, root, ui);
    }

    @Override
    FrameLayout.LayoutParams params() {
        return ui.flp(360, -2, Gravity.TOP | Gravity.END, 0, 24, 40, 0);
    }

    @Override
    View build() {
        LinearLayout s = new LinearLayout(a);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setPadding(ui.px(16), ui.px(14), ui.px(16), ui.px(16));
        s.setBackground(ui.round(0xE61C1C1E, 28));
        s.setClipChildren(false);
        s.setClipToPadding(false);

        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        time = ui.text("", 14, Ui.WHITE, true);
        head.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(ui.text("Пункт управления", 11, Ui.SUB, false));
        s.addView(head, ui.lp(-1, -2, 4, 0, 4, 12));

        // StandBy big tile + network and Bluetooth
        LinearLayout r1 = row();
        LinearLayout standby = bigTile(R.drawable.g_clock, "Заставка", v -> {
            close();
            try {
                a.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName("com.android.systemui",
                        "com.android.systemui.Somnambulator")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                settings(new Intent(Settings.ACTION_DREAM_SETTINGS));
            }
        });
        first = standby;
        r1.addView(standby, ui.lp(150, 132, 0, 0, 10, 0));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        LinearLayout net = tile(R.drawable.qp_wifi, "Сеть", v -> settings(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        netSub = sub(net);
        col.addView(net, ui.lp(-1, 61, 0, 0, 0, 10));
        LinearLayout bt = tile(R.drawable.qp_bt, "Bluetooth", v -> {
            Intent i = new Intent("com.android.tv.settings.CONNECTED_DEVICES");
            if (i.resolveActivity(a.getPackageManager()) == null) i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            settings(i);
        });
        btSub = sub(bt);
        col.addView(bt, ui.lp(-1, 61, 0, 0, 0, 0));
        r1.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        s.addView(r1, ui.lp(-1, -2, 0, 0, 0, 10));

        LinearLayout r2 = row();
        LinearLayout inputs = tile(R.drawable.g_input, "Входы", v -> chooseInput());
        sub(inputs).setText("HDMI, ТВ");
        r2.addView(inputs, new LinearLayout.LayoutParams(0, ui.px(56), 1));
        LinearLayout set = tile(R.drawable.qp_gear, "Настройки", v -> {
            close();
            a.post(() -> a.openSettings());
        });
        sub(set).setText("Glass и телевизор");
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, ui.px(56), 1);
        sl.setMargins(ui.px(10), 0, 0, 0);
        r2.addView(set, sl);
        s.addView(r2, ui.lp(-1, -2, 0, 0, 0, 10));

        // the alert state; OK fetches it again now
        LinearLayout alert = new LinearLayout(a);
        alert.setOrientation(LinearLayout.VERTICAL);
        alert.setPadding(ui.px(14), ui.px(10), ui.px(14), ui.px(10));
        alertHead = ui.line("", 13, Ui.WHITE, true);
        alert.addView(alertHead);
        alertSub = ui.text("", 10, Ui.SUB, false);
        alertSub.setMaxLines(2);
        alert.addView(alertSub, ui.lp(-2, -2, 0, 4, 0, 0));
        ui.plate(alert, 16, v -> {
            alertSub.setText("Обновляю данные о тревогах…");
            a.background(() -> {
                Flick.refresh(a);
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException ignored) {
                    // fine
                }
                JSONObject st = Flick.status(a);
                a.post(() -> {
                    a.status = st;
                    render();
                });
            });
        });
        s.addView(alert, ui.lp(-1, -2, 0, 0, 0, 12));

        // round buttons: sleep, TorrServe, refresh, back to Flick TV
        LinearLayout r3 = row();
        r3.setGravity(Gravity.CENTER_VERTICAL);
        r3.addView(round(R.drawable.g_moon, "Сон", v -> {
            close();
            a.background(() -> {
                boolean ok = Flick.sleep(a);
                if (!ok) a.post(() -> Toast.makeText(a, "Сон доступен со службой «Flick TV: кнопка Home»", Toast.LENGTH_LONG).show());
            });
        }));
        LinearLayout torr = round(R.drawable.g_down, "TorrServe", v -> {
            close();
            a.launch("ru.yourok.torrserve");
        });
        torrSub = (TextView) torr.getChildAt(1);
        r3.addView(torr);
        r3.addView(round(R.drawable.g_refresh, "Обновить", v -> {
            a.background(() -> Flick.refresh(a));
            Toast.makeText(a, "Обновляю тревоги, погоду и Smart Life", Toast.LENGTH_SHORT).show();
        }));
        r3.addView(round(R.drawable.g_swap, "Flick TV", v -> {
            close();
            a.background(() -> Flick.setHome(a, "flick"));
            try {
                a.startActivity(Flick.flickHome());
            } catch (RuntimeException e) {
                Toast.makeText(a, "Flick TV не установлен", Toast.LENGTH_SHORT).show();
            }
        }));
        s.addView(r3, ui.lp(-1, -2, 0, 0, 0, 10));

        s.addView(ui.text("SMART LIFE", 9, Ui.FAINT, true), ui.lp(-2, -2, 4, 4, 0, 8));
        lights = row();
        lights.setGravity(Gravity.CENTER_VERTICAL);
        s.addView(lights, ui.lp(-1, -2, 0, 0, 0, 0));
        render();
        return s;
    }

    @Override
    void opened() {
        a.post(() -> {
            if (first != null) first.requestFocus();
        });
    }

    void render() {
        if (time == null) return;
        time.setText(new SimpleDateFormat("H:mm", Locale.getDefault()).format(new Date()));
        JSONObject st = a.status;
        JSONObject al = st.optJSONObject("alert");
        if (al != null) {
            String dot = al.optInt("state") == 1 ? "🔴  " : al.optInt("state") == 2 ? "🟠  " : al.optInt("state") == 0 ? "🟢  " : "⚪  ";
            alertHead.setText(dot + al.optString("headline", "Нет данных о тревогах"));
            String detail = al.optString("detail");
            alertSub.setText(al.optString("scope") + (detail.isEmpty() ? "" : "  ·  " + detail));
        } else {
            alertHead.setText("⚪  Нет данных от Flick TV");
            alertSub.setText("Тревоги, Smart Life и фильмы приходят из Flick TV");
        }
        netSub.setText(network());
        String bt;
        try {
            BluetoothAdapter b = BluetoothAdapter.getDefaultAdapter();
            bt = b == null ? "нет" : b.isEnabled() ? "Включён" : "Выключен";
        } catch (RuntimeException e) {
            bt = "Пульт и наушники";   // no permission on this Android version
        }
        btSub.setText(bt);
        String torr = st.optString("torr");
        torrSub.setText(torr.isEmpty() ? "TorrServe" : "Работает");
        renderLights(st.optJSONArray("tuya"));
    }

    private void renderLights(JSONArray devices) {
        lights.removeAllViews();
        if (devices == null || devices.length() == 0) {
            lights.addView(ui.text("Нет устройств: ключи Tuya вводятся в Flick TV", 10, Ui.FAINT, false));
            return;
        }
        int n = 0;
        for (int i = 0; i < devices.length() && n < 4; i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d == null || d.optString("switch_code").isEmpty()) continue;
            n++;
            final String id = d.optString("id");
            String name = d.optString("name");
            LinearLayout b = round(R.drawable.g_bulb, name, null);
            FrameLayout btn = (FrameLayout) b.getChildAt(0);
            ui.setOn(btn, d.optBoolean("switch_on"));
            btn.setOnClickListener(v -> {
                boolean now = !Boolean.TRUE.equals(btn.getTag());
                ui.setOn(btn, now);
                a.background(() -> {
                    boolean ok = Flick.toggle(a, id);
                    if (!ok) a.post(() -> {
                        ui.setOn(btn, !now);
                        Toast.makeText(a, name + ": не удалось", Toast.LENGTH_SHORT).show();
                    });
                });
            });
            lights.addView(b);
        }
        if (n == 0) lights.addView(ui.text("Выключателей нет — только датчики", 10, Ui.FAINT, false));
    }

    private String network() {
        try {
            ConnectivityManager cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network nw = cm.getActiveNetwork();
            NetworkCapabilities c = nw == null ? null : cm.getNetworkCapabilities(nw);
            if (c == null) return "Нет подключения";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi‑Fi";
            return "Подключено";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The TV's HDMI and tuner inputs, as a short list. */
    private void chooseInput() {
        TvInputManager m = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> list = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (m != null) {
            for (TvInputInfo t : m.getTvInputList()) {
                if (t.isHidden(a)) continue;
                list.add(t);
                CharSequence l = t.loadCustomLabel(a);
                if (l == null) l = t.loadLabel(a);
                names.add(String.valueOf(l));
            }
        }
        if (list.isEmpty()) {
            settings(new Intent("android.settings.INPUT_SETTINGS"));
            return;
        }
        new android.app.AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Вход")
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    TvInputInfo t = list.get(w);
                    close();
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, t.isPassthroughInput()
                                ? TvContract.buildChannelUriForPassthroughInput(t.getId())
                                : TvContract.buildChannelsUriForInput(t.getId()))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        if (!t.isPassthroughInput()) i.setPackage("com.mediatek.wwtv.tvcenter");
                        a.startActivity(i);
                    } catch (RuntimeException e) {
                        a.launch("com.mediatek.wwtv.tvcenter");
                    }
                })
                .show();
    }

    private void settings(Intent i) {
        close();
        try {
            a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            a.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    // ------------------------------------------------------------------ pieces

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(a);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        return r;
    }

    private LinearLayout bigTile(int icon, String title, View.OnClickListener click) {
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        ImageView i = ui.icon(icon, Ui.WHITE);
        t.addView(i, ui.lp(40, 40, 0, 0, 0, 10));
        t.addView(ui.text(title, 12, Ui.WHITE, true));
        ui.plate(t, 22, click);
        return t;
    }

    private LinearLayout tile(int icon, String title, View.OnClickListener click) {
        LinearLayout t = new LinearLayout(a);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(ui.px(12), 0, ui.px(10), 0);
        ImageView i = ui.icon(icon, Ui.WHITE);
        t.addView(i, ui.lp(20, 20, 0, 0, 10, 0));
        LinearLayout words = new LinearLayout(a);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(ui.line(title, 11.5f, Ui.WHITE, true));
        words.addView(ui.line("", 9.5f, Ui.SUB, false), ui.lp(-2, -2, 0, 3, 0, 0));
        t.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        ui.plate(t, 18, click);
        return t;
    }

    private static TextView sub(LinearLayout tile) {
        return (TextView) ((LinearLayout) tile.getChildAt(1)).getChildAt(1);
    }

    /** A round button with its name under it. */
    private LinearLayout round(int icon, String name, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(a);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        b.setClipChildren(false);
        FrameLayout btn = ui.roundButton(icon, 46, click);
        b.addView(btn, ui.lp(46, 46, 0, 0, 0, 6));
        TextView t = ui.line(name, 9, Ui.SUB, false);
        t.setMaxWidth(ui.px(76));
        t.setGravity(Gravity.CENTER);
        b.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        b.setLayoutParams(lp);
        return b;
    }
}
