package com.phairplay.service

import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import com.phairplay.airplay.DacpClient
import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.util.Logger

/**
 * Flick AirPlay: what the phone is playing, shown to the rest of the TV as an ordinary media session
 * (title, artist, album, cover, playing or paused). Launchers that read media sessions (Flick TV,
 * Flick TV Glass, Google TV) show it as Now Playing; their play/pause/next/previous go back to the
 * phone through DACP. Also turns the phone's AirPlay volume into the TV's own volume.
 */
class AirPlaySession(private val context: Context, private val remote: (String) -> Unit) {
    private val session = MediaSession(context, "FlickAirPlay").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = remote(DacpClient.CMD_PLAY_PAUSE)
            override fun onPause() = remote(DacpClient.CMD_PLAY_PAUSE)
            override fun onSkipToNext() = remote(DacpClient.CMD_NEXT)
            override fun onSkipToPrevious() = remote(DacpClient.CMD_PREV)
        })
    }
    private var lastArt: ByteArray? = null
    private var lastKey: String? = null

    fun update(info: NowPlayingInfo?) {
        try {
            if (info == null) {
                if (session.isActive) {
                    session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0f).build())
                    session.isActive = false
                }
                lastKey = null
                lastArt = null
                return
            }
            val key = listOf(info.title, info.artist, info.album, info.senderName).joinToString("\u0001")
            val artChanged = !(info.artwork contentEquals lastArt)
            if (key != lastKey || artChanged) {
                lastKey = key
                lastArt = info.artwork
                val md = MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, info.title ?: "AirPlay")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, info.artist ?: info.senderName)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, info.album ?: "")
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, info.senderName)
                info.progress?.let { p -> md.putLong(MediaMetadata.METADATA_KEY_DURATION, p.durationMs) }
                info.artwork?.let { a ->
                    BitmapFactory.decodeByteArray(a, 0, a.size)?.let { b -> md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, b) }
                }
                session.setMetadata(md.build())
            }
            val pos = info.progress?.positionAt(android.os.SystemClock.elapsedRealtime(), info.paused) ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(if (info.paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, pos, if (info.paused) 0f else 1f)
                    .build()
            )
            if (!session.isActive) session.isActive = true
        } catch (e: Exception) {
            Logger.w("Media session update failed: ${e.message}")
        }
    }

    /** The phone's AirPlay volume (dB, −30…0; ≤ −144 is mute) as the TV's media volume. */
    fun setVolume(db: Float) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val index = if (db <= -144f) 0 else Math.round(max * ((db.coerceIn(-30f, 0f) + 30f) / 30f))
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != index) am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        } catch (e: Exception) {
            Logger.w("Volume change failed: ${e.message}")
        }
    }

    fun release() {
        runCatching { session.isActive = false }
        runCatching { session.release() }
    }
}
