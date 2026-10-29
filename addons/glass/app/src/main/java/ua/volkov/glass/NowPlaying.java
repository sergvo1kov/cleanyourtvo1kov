package ua.volkov.glass;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;

import java.util.List;

/** What a music app is playing right now, through Android's media sessions. */
final class NowPlaying {
    /** Preferred players, in order; any other app with a media session is used after these. */
    static final String[] MUSIC = {"ua.volkov.airplay", "com.google.android.youtube.tvmusic", "com.apple.android.music",
            "com.google.android.apps.youtube.music"};

    static final class Track {
        String title = "", artist = "", app = "", pkg = "";
        boolean playing;
        Bitmap art;
        MediaController controller;
    }

    private NowPlaying() {}

    static ComponentName listener(Context ctx) {
        return new ComponentName(ctx, NowPlayingListener.class);
    }

    /** Null when nothing plays (or paused), or when notification access is missing. */
    static Track current(Context ctx) {
        List<MediaController> sessions;
        try {
            MediaSessionManager m = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            sessions = m.getActiveSessions(listener(ctx));
        } catch (SecurityException | NullPointerException e) {
            return null;
        }
        MediaController pick = null;
        int bestRank = Integer.MAX_VALUE;
        for (MediaController c : sessions) {
            if (c.getMetadata() == null) continue;
            PlaybackState st = c.getPlaybackState();
            int state = st == null ? PlaybackState.STATE_NONE : st.getState();
            boolean active = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                    || state == PlaybackState.STATE_PAUSED;
            if (!active) continue;
            int rank = MUSIC.length + 1;
            for (int i = 0; i < MUSIC.length; i++) if (MUSIC[i].equals(c.getPackageName())) rank = i;
            if (state != PlaybackState.STATE_PLAYING) rank += 10;   // a playing session beats a paused one
            if (rank < bestRank) {
                bestRank = rank;
                pick = c;
            }
        }
        if (pick == null) return null;
        MediaMetadata md = pick.getMetadata();
        Track t = new Track();
        t.controller = pick;
        t.pkg = pick.getPackageName();
        t.title = str(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        t.artist = str(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        PlaybackState st = pick.getPlaybackState();
        t.playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        t.art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (t.art == null) t.art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (t.art == null) t.art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        try {
            PackageManager pm = ctx.getPackageManager();
            t.app = pm.getApplicationLabel(pm.getApplicationInfo(t.pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            t.app = t.pkg;
        }
        if (t.title.isEmpty()) return null;
        return t;
    }

    private static String str(MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return "";
    }
}
