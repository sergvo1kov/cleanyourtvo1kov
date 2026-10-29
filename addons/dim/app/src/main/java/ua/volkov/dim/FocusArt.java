package ua.volkov.dim;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.json.JSONObject;

/**
 * Behind the For you row, as in Lampa: the focused film's backdrop (or its poster, enlarged) slides in
 * from the right and fades into the page through a gradient on its left and bottom edges.
 */
final class FocusArt {
    private final Context ctx;
    private final FrameLayout box;
    private final ImageView[] img = new ImageView[2];
    private int front;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float dp;
    private String showing = "";
    private String wanted = "";
    private Runnable pending;

    FocusArt(Context ctx, FrameLayout root, boolean light) {
        this.ctx = ctx;
        dp = ctx.getResources().getDisplayMetrics().density;
        int w = ctx.getResources().getDisplayMetrics().widthPixels;
        box = new FrameLayout(ctx);
        box.setAlpha(0f);
        for (int i = 0; i < 2; i++) {
            img[i] = new ImageView(ctx);
            img[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            img[i].setAlpha(0f);
            box.addView(img[i], new FrameLayout.LayoutParams(-1, -1));
        }
        int bg = light ? 0xFFE9EEF6 : 0xFF0A0D18;
        int clear = bg & 0x00FFFFFF;
        View left = new View(ctx);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{bg, (bg & 0x00FFFFFF) | 0xB0000000, clear, clear}));
        box.addView(left, new FrameLayout.LayoutParams(-1, -1));
        View bottom = new View(ctx);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{bg, (bg & 0x00FFFFFF) | 0xC0000000, clear}));
        box.addView(bottom, new FrameLayout.LayoutParams(-1, -1));
        View top = new View(ctx);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(bg & 0x00FFFFFF) | 0x99000000, clear, clear}));
        box.addView(top, new FrameLayout.LayoutParams(-1, -1));
        // the right two thirds of the screen, the upper part where the row is
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(w * 0.68f), -1, Gravity.END | Gravity.TOP);
        root.addView(box, lp);
        box.post(() -> fitHeight(root));
    }

    private void fitHeight(FrameLayout root) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) box.getLayoutParams();
        lp.height = Math.round(root.getHeight() * 0.74f);
        box.setLayoutParams(lp);
    }

    /** Waits a moment, so scrolling fast along the row does not flicker through every film. */
    void show(JSONObject film) {
        final String key = film.optString("title") + "|" + film.optString("year");
        wanted = key;
        if (pending != null) main.removeCallbacks(pending);
        pending = () -> {
            Context app = ctx.getApplicationContext();
            new Thread(() -> {
                String url = LampaLink.backdrop(app, film);
                if (url == null || url.isEmpty()) url = film.optString("poster");
                final String u = url;
                Images.get(u, b -> {
                    if (key.equals(wanted)) present(key, b);
                });
            }).start();
        };
        main.postDelayed(pending, 280);
    }

    void hide() {
        wanted = "";
        showing = "";
        if (pending != null) main.removeCallbacks(pending);
        box.animate().cancel();
        box.animate().alpha(0f).setDuration(260).start();
    }

    private void present(String key, Bitmap b) {
        if (b == null || key.equals(showing)) return;
        showing = key;
        ImageView out = img[front];
        front = 1 - front;
        ImageView in = img[front];
        in.setImageBitmap(b);
        in.animate().cancel();
        in.setAlpha(0f);
        in.setTranslationX(48 * dp);
        in.setScaleX(1.06f);
        in.setScaleY(1.06f);
        in.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f).setDuration(650)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        out.animate().cancel();
        out.animate().alpha(0f).translationX(-24 * dp).setDuration(450).start();
        box.animate().cancel();
        box.animate().alpha(1f).setDuration(300).start();
    }
}
