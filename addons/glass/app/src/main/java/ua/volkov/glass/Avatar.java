package ua.volkov.glass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The profile picture: an emoji on a soft two-tone disc, the way tvOS shows a profile. The emoji is
 * drawn with the TV's own emoji font; the wolf comes first.
 */
final class Avatar extends Drawable {
    static final String[] EMOJI = {"🐺", "🦊", "🐼", "🦁", "🐨", "🐱", "😎", "👻", "👽", "🤖"};
    static final String[] NAMES = {"Волк", "Лис", "Панда", "Лев", "Коала", "Кот", "Очки", "Призрак", "Пришелец", "Робот"};
    // top and bottom of each disc
    private static final int[][] TONES = {
            {0xFF8FA6C8, 0xFF3E5577}, {0xFFFFB36B, 0xFFE0602A}, {0xFFBFE3A8, 0xFF5E9E5A},
            {0xFFFFD66B, 0xFFE09A2A}, {0xFFB9C6D6, 0xFF6B7C93}, {0xFFFFB1C8, 0xFFD8628C},
            {0xFF7FD4FF, 0xFF2A7FD8}, {0xFFC9B8FF, 0xFF7356D8}, {0xFF9CF0C8, 0xFF2AA884},
            {0xFFD6DCE4, 0xFF7E8896}};

    private final int index;
    private final int size;
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);

    Avatar(int index, int sizePx) {
        this.index = Math.max(0, Math.min(EMOJI.length - 1, index));
        this.size = sizePx;
        int[] t = TONES[this.index];
        disc.setShader(new LinearGradient(0, 0, 0, sizePx, t[0], t[1], Shader.TileMode.CLAMP));
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, sizePx / 28f));
        rim.setColor(0x59FFFFFF);
        glyph.setTextAlign(Paint.Align.CENTER);
        glyph.setTextSize(sizePx * 0.6f);
        setBounds(0, 0, sizePx, sizePx);
    }

    static int current(Context c) {
        return c.getSharedPreferences("glass", Context.MODE_PRIVATE).getInt("avatar", 0);
    }

    static void set(Context c, int i) {
        c.getSharedPreferences("glass", Context.MODE_PRIVATE).edit().putInt("avatar", i).apply();
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float r = Math.min(b.width(), b.height()) / 2f;
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        c.drawCircle(cx, cy, r, disc);
        c.drawCircle(cx, cy, r - rim.getStrokeWidth() / 2f, rim);
        Paint.FontMetrics m = glyph.getFontMetrics();
        c.drawText(EMOJI[index], cx, cy - (m.ascent + m.descent) / 2f, glyph);
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public void setAlpha(int alpha) {
        disc.setAlpha(alpha);
        glyph.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        // the avatar keeps its colours
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
