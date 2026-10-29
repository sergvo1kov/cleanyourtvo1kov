package ua.volkov.glass;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Choose the profile picture: the current one large, ten to pick from below. */
final class ProfilePanel extends Panel {
    private ImageView big;
    private TextView name;
    private View current;

    ProfilePanel(MainActivity a, FrameLayout root, Ui ui) {
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
        s.setGravity(Gravity.CENTER_HORIZONTAL);
        s.setPadding(ui.px(18), ui.px(20), ui.px(18), ui.px(20));
        s.setBackground(ui.round(0xE61C1C1E, 28));
        s.setClipChildren(false);
        s.setClipToPadding(false);

        int now = Avatar.current(a);
        big = new ImageView(a);
        big.setImageDrawable(new Avatar(now, ui.px(88)));
        s.addView(big, ui.lp(88, 88, 0, 0, 0, 10));
        name = ui.text(Avatar.NAMES[now], 15, Ui.WHITE, true);
        s.addView(name);
        s.addView(ui.text("Профиль", 10, Ui.SUB, false), ui.lp(-2, -2, 0, 3, 0, 16));

        LinearLayout grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        LinearLayout row = null;
        for (int i = 0; i < Avatar.EMOJI.length; i++) {
            if (i % 5 == 0) {
                row = new LinearLayout(a);
                row.setClipChildren(false);
                grid.addView(row, ui.lp(-2, -2, 0, i == 0 ? 0 : 12, 0, 0));
            }
            row.addView(choice(i, now), ui.lp(50, 50, i % 5 == 0 ? 0 : 12, 0, 0, 0));
        }
        s.addView(grid);
        return s;
    }

    private View choice(int i, int now) {
        FrameLayout f = new FrameLayout(a);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0);
        ring.setStroke(ui.px(2.5f), i == now ? 0x99FFFFFF : 0);
        f.setForeground(ring);
        ImageView img = new ImageView(a);
        img.setImageDrawable(new Avatar(i, ui.px(50)));
        f.addView(img, new FrameLayout.LayoutParams(-1, -1));
        f.setFocusable(true);
        f.setClickable(true);
        f.setTag(ring);
        if (i == now) current = f;
        f.setOnFocusChangeListener((v, has) -> {
            ring.setStroke(ui.px(2.5f), has ? 0xF2FFFFFF : (i == Avatar.current(a) ? 0x99FFFFFF : 0));
            v.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f).setDuration(160).start();
        });
        f.setOnClickListener(v -> {
            if (current != null && current != v) ((GradientDrawable) current.getTag()).setStroke(ui.px(2.5f), 0);
            current = v;
            Avatar.set(a, i);
            big.setImageDrawable(new Avatar(i, ui.px(88)));
            name.setText(Avatar.NAMES[i]);
            a.avatarChanged();
        });
        return f;
    }

    @Override
    void opened() {
        a.post(() -> {
            if (current != null) current.requestFocus();
        });
    }
}
