package ua.volkov.dim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * A small four-bar equalizer by "Now playing": the bars rise and fall while music plays and settle low
 * when it pauses. Drawn by hand (no images), redrawn only while it moves.
 */
final class Equalizer extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private boolean playing;
    private final float[] level = {0.3f, 0.3f, 0.3f, 0.3f};
    private static final float[] SPEED = {2.1f, 3.3f, 2.7f, 3.9f};
    private static final float[] PHASE = {0f, 1.3f, 2.6f, 0.7f};

    Equalizer(Context c) {
        super(c);
    }

    void setColour(int colour) {
        paint.setColor(colour);
        invalidate();
    }

    void setPlaying(boolean on) {
        if (on == playing) return;
        playing = on;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float gap = w * 0.12f, bw = (w - gap * 3) / 4f;
        double t = SystemClock.uptimeMillis() / 1000.0;
        boolean moving = false;
        for (int i = 0; i < 4; i++) {
            float target = playing
                    ? (float) (0.35 + 0.65 * Math.abs(Math.sin(t * SPEED[i] + PHASE[i]) * Math.cos(t * SPEED[i] * 0.37 + PHASE[i])))
                    : 0.22f;
            level[i] += (target - level[i]) * (playing ? 0.35f : 0.18f);
            if (Math.abs(target - level[i]) > 0.01f) moving = true;
            float bh = Math.max(h * 0.15f, h * level[i]);
            float x = i * (bw + gap);
            bar.set(x, h - bh, x + bw, h);
            c.drawRoundRect(bar, bw / 2f, bw / 2f, paint);
        }
        if (playing || moving) postInvalidateOnAnimation();
    }
}
