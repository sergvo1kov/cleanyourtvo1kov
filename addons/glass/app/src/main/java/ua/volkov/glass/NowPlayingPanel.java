package ua.volkov.glass;

import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Now Playing, as on Apple TV: the cover large on the left over a blurred copy of itself, the track,
 * the artist, a progress line and the transport buttons. Works for any app with a media session; for
 * Flick AirPlay the buttons reach the phone.
 */
final class NowPlayingPanel extends Panel {
    private ImageView back, cover;
    private TextView app, title, artist, elapsed, left;
    private View fill;
    private FrameLayout playBtn;
    private ImageView playIcon;
    private Bitmap shownArt;
    private View first;

    NowPlayingPanel(MainActivity a, FrameLayout root, Ui ui) {
        super(a, root, ui);
    }

    @Override
    int side() {
        return 0;
    }

    @Override
    FrameLayout.LayoutParams params() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    @Override
    View build() {
        FrameLayout s = new FrameLayout(a);
        s.setBackgroundColor(0xFF101012);
        back = new ImageView(a);
        back.setScaleType(ImageView.ScaleType.CENTER_CROP);
        s.addView(back, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(a);
        shade.setBackgroundColor(0x8C000000);
        s.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.px(96), 0, ui.px(96), 0);
        row.setClipChildren(false);
        row.setClipToPadding(false);

        cover = new ImageView(a);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(ui.round(0xFF2C2C2E, 16));
        cover.setClipToOutline(true);
        row.addView(cover, ui.lp(300, 300, 0, 0, 48, 0));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        app = ui.text("", 11, Ui.SUB, true);
        col.addView(app, ui.lp(-2, -2, 0, 0, 0, 10));
        title = ui.text("", 30, Ui.WHITE, true);
        title.setMaxLines(2);
        col.addView(title, ui.lp(-1, -2, 0, 0, 0, 6));
        artist = ui.text("", 17, Ui.SUB, false);
        artist.setMaxLines(1);
        col.addView(artist, ui.lp(-1, -2, 0, 0, 0, 26));

        // the progress line with the elapsed and remaining time under it
        FrameLayout track = new FrameLayout(a);
        track.setBackground(ui.round(0x40FFFFFF, 3));
        fill = new View(a);
        fill.setBackground(ui.round(0xE6FFFFFF, 3));
        track.addView(fill, new FrameLayout.LayoutParams(0, -1));
        col.addView(track, ui.lp(-1, 5, 0, 0, 0, 6));
        LinearLayout times = new LinearLayout(a);
        elapsed = ui.text("", 10, Ui.SUB, false);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1));
        left = ui.text("", 10, Ui.SUB, false);
        times.addView(left);
        col.addView(times, ui.lp(-1, -2, 0, 0, 0, 28));

        LinearLayout buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setClipChildren(false);
        FrameLayout prev = ui.roundButton(R.drawable.g_prev, 54, v -> transport(-1));
        buttons.addView(prev, ui.lp(54, 54, 0, 0, 22, 0));
        playBtn = ui.roundButton(R.drawable.g_pause, 66, v -> transport(0));
        playIcon = (ImageView) playBtn.getChildAt(0);
        buttons.addView(playBtn, ui.lp(66, 66, 0, 0, 22, 0));
        FrameLayout next = ui.roundButton(R.drawable.g_next, 54, v -> transport(1));
        buttons.addView(next, ui.lp(54, 54, 0, 0, 0, 0));
        col.addView(buttons);
        first = playBtn;

        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        s.addView(row, new FrameLayout.LayoutParams(-1, -1));
        render();
        return s;
    }

    @Override
    void opened() {
        a.post(() -> {
            if (first != null) first.requestFocus();
        });
    }

    private void transport(int which) {
        NowPlaying.Track t = a.track();
        if (t == null || t.controller == null) return;
        MediaController.TransportControls c = t.controller.getTransportControls();
        try {
            if (which < 0) c.skipToPrevious();
            else if (which > 0) c.skipToNext();
            else if (t.playing) c.pause();
            else c.play();
        } catch (RuntimeException ignored) {
            // the player went away
        }
        a.postDelayed(this::render, 400);
    }

    /** Called by MainActivity every second while open. */
    void render() {
        if (!isOpen() || title == null) return;
        NowPlaying.Track t = a.track();
        if (t == null) {
            title.setText("Ничего не играет");
            artist.setText("Включи музыку на телефоне через AirPlay или в любом приложении");
            app.setText("");
            elapsed.setText("");
            left.setText("");
            return;
        }
        app.setText(t.app.toUpperCase(Locale.ROOT));
        if (!title.getText().toString().equals(t.title)) title.setText(t.title);
        if (!artist.getText().toString().equals(t.artist)) artist.setText(t.artist);
        playIcon.setImageResource(t.playing ? R.drawable.g_pause : R.drawable.g_play);
        if (t.art != shownArt) {
            shownArt = t.art;
            cover.setImageBitmap(t.art);
            if (t.art != null) {
                Bitmap art = t.art;
                a.background(() -> {
                    Bitmap b = Ui.blur(art, 32);
                    a.post(() -> {
                        if (shownArt == art) back.setImageBitmap(b);
                    });
                });
            } else {
                back.setImageDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF2C3E50, 0xFF101012}));
            }
        }
        // position: the state's position moved on by the time since it was set
        long pos = -1, dur = -1;
        try {
            PlaybackState st = t.controller.getPlaybackState();
            if (st != null && st.getPosition() >= 0) {
                pos = st.getPosition();
                if (st.getState() == PlaybackState.STATE_PLAYING) {
                    pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
                }
            }
            if (t.controller.getMetadata() != null) dur = t.controller.getMetadata().getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
        } catch (RuntimeException ignored) {
            // no position
        }
        View track = (View) fill.getParent();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fill.getLayoutParams();
        if (pos >= 0 && dur > 0) {
            pos = Math.min(pos, dur);
            lp.width = Math.round(track.getWidth() * (pos / (float) dur));
            elapsed.setText(time(pos));
            left.setText("−" + time(dur - pos));
        } else {
            lp.width = 0;
            elapsed.setText("");
            left.setText("");
        }
        fill.setLayoutParams(lp);
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
