@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.phairplay.airplay

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.text.CueGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.phairplay.util.Logger

/** Snapshot returned for `GET /playback-info` / `GET /scrub`. */
data class PlaybackInfo(
    val durationSec: Double,
    val positionSec: Double,
    val bufferedPositionSec: Double,
    val rate: Double,        // 0.0 = paused, 1.0 = playing
    val readyToPlay: Boolean,
    val isBuffering: Boolean,
)

/**
 * AirPlayVideoPlayer — plays an AirPlay "video URL" stream (the non-mirroring mode: a sender app like Safari or
 * YouTube says "AirPlay this video" and POSTs a URL via `/play`). The TV fetches and plays the media itself with
 * Media3 ExoPlayer (progressive files and HLS, including fMP4 and packed audio), rendering to the same streaming
 * [Surface] the mirror decoder uses; the sender only drives transport (`/rate`, `/scrub`, `/stop`) and polls
 * `/playback-info`.
 *
 * ExoPlayer must be used from a single thread, while RTSP/HTTP control verbs arrive on many threads: all player
 * access happens on a private [HandlerThread], and [info] reads a snapshot refreshed there.
 */
class AirPlayVideoPlayer(
    private val context: Context,
    private val surfaceProvider: () -> Surface?,
    private val onEnded: (generation: Long) -> Unit = {},
    private val onCues: (CueGroup) -> Unit = {},
) {
    private val thread = HandlerThread("airplay-video").apply { start() }
    private val handler = Handler(thread.looper)

    // Touched only on [handler]'s thread.
    private var player: ExoPlayer? = null
    private var attachedSurface: Surface? = null
    private var preparePending = false
    private var startFraction = 0.0
    private var seekedToFraction = false
    private var activeListener: Player.Listener? = null
    // Persists across play() calls within the same session (episode/content replacement keeps the
    // viewer's subtitle choice) but resets to the safe default when a brand-new session starts.
    @Volatile private var subtitlesEnabled = false

    @Volatile private var snapshot: PlaybackInfo? = null
    @Volatile private var released = false

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            attachSurfaceNow(p)
            refreshSnapshot(p)
            handler.postDelayed(this, TICK_MS)
        }
    }

    /**
     * Starts playing [url], seeking once ready to [startPositionSeconds] if given, else to [startPositionFraction]
     * (0..1 of the duration).
     */
    fun play(url: String, startPositionFraction: Double, startPositionSeconds: Double = 0.0, generation: Long = 0L) {
        Logger.i("AirPlay video: play url=$url start=$startPositionFraction / ${startPositionSeconds}s")
        post {
            releasePlayerNow()
            // URL playback does not use MirrorStreamServer's RTP counters. Mark it explicitly so
            // the HUD does not report a healthy HLS stream as "0 fps / audio off".
            StreamStats.videoRes = "URL"
            StreamStats.videoFps = -1
            StreamStats.videoQueue = -1
            StreamStats.videoDropPct = -1
            StreamStats.audioActive = false
            StreamStats.audioQueue = -1
            StreamStats.audioDupPct = -1
            snapshot = PlaybackInfo(0.0, 0.0, 0.0, 0.0, readyToPlay = false, isBuffering = true)
            startFraction = startPositionFraction.coerceIn(0.0, 1.0)
            seekedToFraction = false
            runCatching {
                val p = urlVideoPlayerBuilder(context, thread.looper).build()
                player = p
                // Default to no subtitles, matching Safari/AVPlayer's own default (captions off unless the
                // viewer turned them on): AirPlay's `selectedMediaArray` /setProperty tells us the sender's
                // real choice, applied via setSubtitlesEnabled(); until parsed, don't let ExoPlayer's track
                // selector auto-pick a stream's "default" text rendition, which showed dialogue subtitles
                // the viewer never asked for.
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
                    .build()
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ false
                )
                val listener = listenerFor(p, generation)
                activeListener = listener
                p.addListener(listener)
                // Never initialize this device's codec on ExoPlayer's dummy Surface: switching
                // that decoder to a late SurfaceView can leave READY playback without visible video.
                // Wait for a valid UI Surface, then attach once BEFORE prepare().
                val item = MediaItem.Builder().setUri(url).apply {
                    if (url.substringBefore('?').endsWith(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
                }.build()
                if (startPositionSeconds > 0.0) p.setMediaItem(item, (startPositionSeconds * 1000).toLong())
                else p.setMediaItem(item)
                p.playWhenReady = true
                preparePending = true
                attachSurfaceNow(p)
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            }.onFailure {
                Logger.e("AirPlay video: setup failed", it)
                releasePlayerNow()
                onEnded(generation)
            }
        }
    }

    private fun listenerFor(owner: ExoPlayer, generation: Long) = object : Player.Listener {
        private var readyLogged = false

        override fun onRenderedFirstFrame() {
            if (player === owner) Logger.i("AirPlay video: first frame generation=$generation")
        }

        override fun onCues(cueGroup: CueGroup) {
            if (player === owner) this@AirPlayVideoPlayer.onCues(cueGroup)
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (player !== owner) return
            val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                .flatMap { group -> (0 until group.mediaTrackGroup.length).map { i ->
                    val f = group.mediaTrackGroup.getFormat(i)
                    "${f.language ?: "?"}/${f.label ?: f.sampleMimeType ?: "text"}"
                } }
            Logger.i("AirPlay video tracks: text=${if (text.isEmpty()) "none" else text.joinToString()}")
        }
        override fun onPlaybackStateChanged(state: Int) {
            if (player !== owner) return
            val p = player ?: return
            when (state) {
                Player.STATE_READY -> {
                    if (!seekedToFraction && startFraction > 0.0 && p.duration != C.TIME_UNSET) {
                        seekedToFraction = true
                        p.seekTo((startFraction * p.duration).toLong())
                    }
                    if (!readyLogged) {
                        readyLogged = true
                        Logger.i("AirPlay video: ready dur=${p.duration}ms → playing generation=$generation")
                    }
                }
                Player.STATE_ENDED -> {
                    Logger.i("AirPlay video: completed")
                    onEnded(generation)
                }
                Player.STATE_BUFFERING -> {
                    Logger.d("AirPlay video: buffering at ${p.currentPosition}ms")
                }
                else -> Unit
            }
            refreshSnapshot(p)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (player !== owner) return
            Logger.e("AirPlay video error: ${error.errorCodeName} — ${error.message}", error)
            onEnded(generation)
        }
    }

    private fun refreshSnapshot(p: ExoPlayer) {
        val size = p.videoSize
        if (size.width > 0 && size.height > 0) StreamStats.videoRes = "${size.width}x${size.height}"
        // Media3 does not expose a decoded-frame counter on this player thread. Keep that
        // distinction visible instead of showing a misleading zero.
        StreamStats.videoFps = -1
        StreamStats.videoQueue = -1
        StreamStats.videoDropPct = -1
        StreamStats.audioActive = p.audioFormat != null && p.playbackState != Player.STATE_IDLE
        StreamStats.audioQueue = -1
        StreamStats.audioDupPct = -1
        val ready = p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING && p.duration != C.TIME_UNSET
        // Single source of truth for "is there something on screen": StreamingScreen's waiting
        // placeholder (gated on this) used a stricter STATE_READY-only check while the transport
        // bar below (readyToPlay) already counted STATE_BUFFERING-with-known-duration as ready —
        // the two disagreed for a second or two on every play(), so the placeholder and the
        // scrubber (already showing "0:00 / <duration>") were visible at the same time.
        StreamStats.videoShowing = ready
        val dur = if (p.duration == C.TIME_UNSET) 0.0 else p.duration / 1000.0
        val pos = p.currentPosition.coerceAtLeast(0) / 1000.0
        val buffered = p.bufferedPosition.coerceAtLeast(p.currentPosition).toDouble() / 1000.0
        // playWhenReady is the user's intended rate. Keep advertising 1.0 during a normal HLS
        // rebuffer so AirPlay does not interpret the short buffering interval as a failed pause.
        val playing = p.playWhenReady && p.playbackState != Player.STATE_ENDED
        snapshot = PlaybackInfo(
            durationSec = dur,
            positionSec = pos,
            bufferedPositionSec = buffered,
            rate = if (playing) 1.0 else 0.0,
            readyToPlay = ready,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
        )
    }

    /** rate ≤ 0 pauses, > 0 resumes. */
    fun setRate(rate: Float) = post {
        player?.let { p ->
            if (rate > 0f) p.play() else p.pause()
        }
    }

    /** Seeks to [positionSec] seconds. */
    fun scrub(positionSec: Double) = post { player?.seekTo((positionSec * 1000).toLong()) }

    /** Applies the sender's actual subtitle choice (AirPlay `/setProperty?selectedMediaArray`). */
    fun setSubtitlesEnabled(enabled: Boolean) = post {
        subtitlesEnabled = enabled
        player?.let { p ->
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
                .build()
        }
    }

    /** Current playback snapshot for `/playback-info`, or null if nothing is loaded. */
    fun info(): PlaybackInfo? = snapshot

    /** Re-attach the streaming surface (after the Activity recreates it on foreground). */
    fun attachSurface() = post { player?.let { attachSurfaceNow(it) } }

    private fun attachSurfaceNow(p: ExoPlayer) {
        val surface = surfaceProvider()?.takeIf { it.isValid } ?: run {
            attachedSurface = null
            return
        }
        if (attachedSurface !== surface) {
            // Surface destruction can race the validity check on the Activity thread.
            if (runCatching { p.setVideoSurface(surface) }.isFailure) {
                Logger.w("AirPlay video: Surface attach failed; waiting for the next valid Surface")
                return
            }
            attachedSurface = surface
            Logger.i("AirPlay video: attached valid Surface (preparePending=$preparePending)")
        }
        if (preparePending) {
            preparePending = false
            p.prepare()
        }
    }

    fun release() {
        if (released) return
        released = true
        snapshot = null
        handler.removeCallbacksAndMessages(null)
        handler.post {
            releasePlayerNow()
            thread.quitSafely()
        }
    }

    private fun releasePlayerNow() {
        handler.removeCallbacks(ticker)
        val previous = player
        player = null
        previous?.let { p ->
            activeListener?.let { listener -> runCatching { p.removeListener(listener) } }
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        activeListener = null
        attachedSurface = null
        preparePending = false
    }

    private inline fun post(crossinline block: () -> Unit) {
        if (!released) handler.post { block() }
    }

    private companion object {
        const val TICK_MS = 500L
    }
}

/** Build on the video worker, where waiting for the previous codec cannot block the UI. */
internal fun urlVideoPlayerBuilder(context: Context, looper: Looper): ExoPlayer.Builder =
    ExoPlayer.Builder(context).setLooper(looper)
        // Media3's 500 ms default returns while a slow codec can still own the Surface.
        // GP520 release during codec startup took ~3 s in a rapid episode-switch probe;
        // preparing the replacement then failed with "Surface already connected".
        .setReleaseTimeoutMs(10_000)
