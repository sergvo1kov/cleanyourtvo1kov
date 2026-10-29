package ua.volkov.glass;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small building blocks shared by the Glass screens: text, glass surfaces, plates, round buttons. */
final class Ui {
    static final int WHITE = 0xFFFFFFFF, SUB = 0xA6FFFFFF, FAINT = 0x66FFFFFF, GRAPHITE = 0xFF1C1C1E;
    static final int GLASS = 0x2EFFFFFF, GLASS_LINE = 0x40FFFFFF, PANEL = 0xE6202228, PLATE = 0x1FFFFFFF;

    private final Context c;
    private final float dp;
    /** Look & Style from the settings: Liquid Glass (clear, bright rim) or Frosted (milky). */
    final boolean liquid;
    /** Blur behind the apps and panels: 0 light, 1 medium, 2 strong. */
    final int blurLevel;
    /** How dark the picture gets behind the apps: 0 light, 1 medium, 2 dark. */
    final int scrimLevel;

    Ui(Context c) {
        this.c = c;
        this.dp = c.getResources().getDisplayMetrics().density;
        android.content.SharedPreferences p = c.getSharedPreferences("glass", Context.MODE_PRIVATE);
        liquid = !"frosted".equals(p.getString("glass_style", "liquid"));
        blurLevel = p.getInt("blur", 1);
        scrimLevel = p.getInt("scrim", 1);
    }

    /** Width the picture is shrunk to before it is blown up again: smaller is blurrier. */
    int blurWidth() {
        return blurLevel == 0 ? 96 : blurLevel == 2 ? 24 : 44;
    }

    float scrim() {
        return scrimLevel == 0 ? 0.5f : scrimLevel == 2 ? 0.88f : 0.72f;
    }

    /** The sheets of the panels: more see-through in Liquid Glass. */
    int sheet() {
        return liquid ? 0xC71C1C1E : 0xEB2A2A2E;
    }

    /** The top capsules: smoky glass with a thin light rim. */
    GradientDrawable capsule(float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, capsuleColours(false));
        g.setCornerRadius(px(radiusDp));
        g.setStroke(Math.max(1, px(0.75f)), liquid ? 0x59FFFFFF : 0x33FFFFFF);
        return g;
    }

    void capsuleFocus(GradientDrawable g, boolean has) {
        g.setColors(capsuleColours(has));
    }

    private int[] capsuleColours(boolean focused) {
        if (focused) return new int[]{0xF7FFFFFF, 0xEBFFFFFF};
        return liquid ? new int[]{0x4D2C2C2E, 0x331C1C1E} : new int[]{0x998E8E93, 0x80636366};
    }

    int px(float v) {
        return Math.round(v * dp);
    }

    TextView text(String s, float sp, int colour, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    TextView line(String s, float sp, int colour, boolean bold) {
        TextView t = text(s, sp, colour, bold);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    GradientDrawable round(int colour, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(radiusDp));
        g.setColor(colour);
        return g;
    }

    /** Frosted glass: a light veil with a thin bright edge, like the reference's dock and capsules. */
    GradientDrawable glass(float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                liquid ? new int[]{0x38FFFFFF, 0x14FFFFFF} : new int[]{0x66A8A8AD, 0x4D78787E});
        g.setCornerRadius(px(radiusDp));
        g.setStroke(Math.max(1, px(0.75f)), liquid ? 0x59FFFFFF : GLASS_LINE);
        return g;
    }

    ImageView icon(int res, int tint) {
        ImageView i = new ImageView(c);
        i.setImageResource(res);
        i.setImageTintList(ColorStateList.valueOf(tint));
        i.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return i;
    }

    /**
     * A tvOS plate: dark translucent at rest; on focus it turns white with graphite text and grows a
     * little. Children that are TextViews or ImageViews follow the colour.
     */
    void plate(View v, float radiusDp, View.OnClickListener click) {
        GradientDrawable g = round(PLATE, radiusDp);
        v.setBackground(g);
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(click);
        v.setOnFocusChangeListener((view, has) -> {
            g.setColor(has ? 0xF2FFFFFF : PLATE);
            tint(view, has ? GRAPHITE : WHITE);
            view.animate().scaleX(has ? 1.025f : 1f).scaleY(has ? 1.025f : 1f).setDuration(160)
                    .setInterpolator(new DecelerateInterpolator()).start();
        });
    }

    private static void tint(View v, int colour) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            int old = t.getCurrentTextColor();
            // keep secondary lines secondary
            int alpha = Color.alpha(old) < 0xF0 ? 0xA6 : 0xFF;
            t.setTextColor((colour & 0x00FFFFFF) | (alpha << 24));
        } else if (v instanceof ImageView && ((ImageView) v).getImageTintList() != null) {
            ((ImageView) v).setImageTintList(ColorStateList.valueOf(colour));
        } else if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) tint(g.getChildAt(i), colour);
        }
    }

    /** A round glass button with an icon, as in the Control Center's bottom row. */
    FrameLayout roundButton(int icon, float sizeDp, View.OnClickListener click) {
        FrameLayout f = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(PLATE);
        g.setStroke(Math.max(1, px(0.75f)), GLASS_LINE);
        f.setBackground(g);
        f.setFocusable(true);
        f.setClickable(true);
        f.setOnClickListener(click);
        ImageView i = icon(icon, WHITE);
        int pad = px(sizeDp * 0.28f);
        f.addView(i, new FrameLayout.LayoutParams(-1, -1));
        i.setPadding(pad, pad, pad, pad);
        f.setOnFocusChangeListener((view, has) -> {
            g.setColor(has ? 0xF2FFFFFF : (Boolean.TRUE.equals(view.getTag()) ? 0xCCFFFFFF : PLATE));
            boolean light = has || Boolean.TRUE.equals(view.getTag());
            i.setImageTintList(ColorStateList.valueOf(light ? GRAPHITE : WHITE));
            view.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(160).start();
        });
        return f;
    }

    /** Lights a round button as "on" (white), the way tvOS shows an active toggle. */
    void setOn(FrameLayout button, boolean on) {
        button.setTag(on);
        GradientDrawable g = (GradientDrawable) button.getBackground();
        boolean focused = button.hasFocus() || button.isFocused();
        g.setColor(focused ? 0xF2FFFFFF : on ? 0xCCFFFFFF : PLATE);
        ((ImageView) button.getChildAt(0)).setImageTintList(ColorStateList.valueOf(focused || on ? GRAPHITE : WHITE));
    }

    LinearLayout.LayoutParams lp(int wDp, int hDp, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(wDp < 0 ? wDp : px(wDp), hDp < 0 ? hDp : px(hDp));
        p.setMargins(px(l), px(t), px(r), px(b));
        return p;
    }

    FrameLayout.LayoutParams flp(int wDp, int hDp, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(wDp < 0 ? wDp : px(wDp), hDp < 0 ? hDp : px(hDp), gravity);
        p.setMargins(px(l), px(t), px(r), px(b));
        return p;
    }

    static int center() {
        return Gravity.CENTER;
    }

    /**
     * A smooth blur without pixels: halve the picture step by step (each step averages 2×2 pixels), then
     * double it back with filtering. Cheap enough for a 1 GB TV.
     */
    static Bitmap blur(Bitmap b, int minWidth) {
        if (b == null) return null;
        Bitmap cur = b;
        int steps = 0;
        while (cur.getWidth() > minWidth) {
            cur = Bitmap.createScaledBitmap(cur, Math.max(1, cur.getWidth() / 2), Math.max(1, cur.getHeight() / 2), true);
            steps++;
        }
        for (int i = 0; i < Math.min(steps, 4); i++) {
            cur = Bitmap.createScaledBitmap(cur, cur.getWidth() * 2, cur.getHeight() * 2, true);
        }
        return cur;
    }

    /** Weather words and a glyph for an Open-Meteo WMO code. */
    static String glyph(int code, boolean day) {
        if (code == 0) return day ? "☀" : "☾";
        if (code <= 2) return day ? "🌤" : "☁";
        if (code == 3) return "☁";
        if (code == 45 || code == 48) return "🌫\uFE0F";   // without the variation selector the TV draws an empty box
        if (code >= 51 && code <= 67) return "🌧";
        if (code >= 71 && code <= 77) return "❄";
        if (code >= 80 && code <= 82) return "🌦";
        if (code >= 85 && code <= 86) return "🌨";
        if (code >= 95) return "⛈";
        return "☁";
    }

    static String words(int code) {
        if (code == 0) return "Ясно";
        if (code <= 2) return "Малооблачно";
        if (code == 3) return "Облачно";
        if (code == 45 || code == 48) return "Туман";
        if (code >= 51 && code <= 57) return "Морось";
        if (code >= 61 && code <= 67) return "Дождь";
        if (code >= 71 && code <= 77) return "Снег";
        if (code >= 80 && code <= 82) return "Ливень";
        if (code >= 85 && code <= 86) return "Снегопад";
        if (code >= 95) return "Гроза";
        return "";
    }

    static String deg(int t) {
        return (t > 0 ? "+" : "") + t + "°";
    }
}
