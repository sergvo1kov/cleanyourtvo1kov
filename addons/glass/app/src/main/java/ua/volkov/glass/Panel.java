package ua.volkov.glass;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * An overlay over the home screen: a dimmed backdrop and one sheet. While it is open the screen behind
 * takes no focus, so the remote stays inside; Back closes it.
 */
abstract class Panel {
    final MainActivity a;
    final FrameLayout root;
    final Ui ui;
    private FrameLayout overlay;
    View sheet;

    Panel(MainActivity a, FrameLayout root, Ui ui) {
        this.a = a;
        this.root = root;
        this.ui = ui;
    }

    boolean isOpen() {
        return overlay != null;
    }

    /** The sheet, its layout params inside the overlay, and from which side it slides in (-1 left, 1 right, 0 fade). */
    abstract View build();

    abstract FrameLayout.LayoutParams params();

    int side() {
        return 1;
    }

    void opened() {
    }

    void open() {
        if (overlay != null) return;
        a.closePanels();
        overlay = new FrameLayout(a);
        overlay.setBackgroundColor(0x99000000);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> close());
        sheet = build();
        sheet.setClickable(true);
        overlay.addView(sheet, params());
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (c instanceof ViewGroup) ((ViewGroup) c).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            c.setFocusable(false);
        }
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(200).start();
        int s = side();
        if (s != 0) {
            sheet.setTranslationX(ui.px(48) * s);
            sheet.animate().translationX(0f).setDuration(320).setInterpolator(new DecelerateInterpolator(2f)).start();
        } else {
            sheet.setScaleX(0.96f);
            sheet.setScaleY(0.96f);
            sheet.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(new DecelerateInterpolator(2f)).start();
        }
        opened();
        sheet.post(() -> {
            View f = sheet.focusSearch(View.FOCUS_DOWN);
            if (sheet instanceof ViewGroup && !sheet.hasFocus()) ((ViewGroup) sheet).requestFocus(View.FOCUS_DOWN);
            if (!sheet.hasFocus() && f != null) f.requestFocus();
        });
    }

    void close() {
        if (overlay == null) return;
        FrameLayout o = overlay;
        overlay = null;
        o.animate().alpha(0f).setDuration(180).withEndAction(() -> root.removeView(o)).start();
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (c == o) continue;
            if (c instanceof ViewGroup) ((ViewGroup) c).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
        a.restoreFocusables();
    }

    static int topEnd() {
        return Gravity.TOP | Gravity.END;
    }
}
