package ua.volkov.dim;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
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
    private final ImageView soft;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String showing = "";
    private String wanted = "";
    private Runnable pending;

    FocusArt(Context ctx, FrameLayout root, boolean light) {
        this.ctx = ctx;
        box = new FrameLayout(ctx);
        box.setAlpha(0f);
        for (int i = 0; i < 2; i++) {
            img[i] = new ImageView(ctx);
            img[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            img[i].setAlpha(0f);
            box.addView(img[i], new FrameLayout.LayoutParams(-1, -1));
        }
        // a blurred copy of the next picture, laid over the change: sharp, soft, sharp again
        soft = new ImageView(ctx);
        soft.setScaleType(ImageView.ScaleType.CENTER_CROP);
        soft.setAlpha(0f);
        box.addView(soft, new FrameLayout.LayoutParams(-1, -1));
        // Lampa's look: the picture fills the screen, darkened towards the left and the bottom where text is
        int bg = light ? 0xEEF2F8 : 0x070A14;
        View left = new View(ctx);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{bg | 0xF0000000, bg | 0xB4000000, bg | 0x50000000, bg | 0x10000000}));
        box.addView(left, new FrameLayout.LayoutParams(-1, -1));
        View bottom = new View(ctx);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{bg | 0xF5000000, bg | 0xC8000000, bg | 0x30000000, bg}));
        box.addView(bottom, new FrameLayout.LayoutParams(-1, -1));
        View top = new View(ctx);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{bg | 0x8C000000, bg, bg, bg}));
        box.addView(top, new FrameLayout.LayoutParams(-1, -1));
        root.addView(box, new FrameLayout.LayoutParams(-1, -1));
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
        final ImageView out = img[front];
        front = 1 - front;
        final ImageView in = img[front];
        boolean first = box.getAlpha() < 0.05f;
        soft.animate().cancel();
        in.animate().cancel();
        out.animate().cancel();
        soft.setImageBitmap(blur(b));
        Runnable reveal = () -> {
            // under the blur: the new picture is already there; the blur lifts off it
            in.setImageBitmap(b);
            in.setAlpha(1f);
            in.setScaleX(1.04f);
            in.setScaleY(1.04f);
            out.setAlpha(0f);
            in.animate().scaleX(1f).scaleY(1f).setDuration(1400).setInterpolator(new DecelerateInterpolator()).start();
            soft.animate().alpha(0f).setDuration(560).setInterpolator(new DecelerateInterpolator()).start();
        };
        if (first) {
            soft.setAlpha(1f);
            reveal.run();
        } else {
            // the old picture dissolves into the blurred new one
            soft.animate().alpha(1f).setDuration(240).withEndAction(reveal).start();
        }
        box.animate().cancel();
        box.animate().alpha(1f).setDuration(320).start();
    }

    /**
     * A smooth blur without pixels: halve the picture step by step (each step averages 2×2 pixels, so
     * nothing aliases), then double it back up step by step with filtering.
     */
    private static Bitmap blur(Bitmap b) {
        Bitmap cur = b;
        while (cur.getWidth() > 40) {
            cur = Bitmap.createScaledBitmap(cur, Math.max(1, cur.getWidth() / 2), Math.max(1, cur.getHeight() / 2), true);
        }
        for (int i = 0; i < 4; i++) {
            cur = Bitmap.createScaledBitmap(cur, cur.getWidth() * 2, cur.getHeight() * 2, true);
        }
        return cur;
    }
}
