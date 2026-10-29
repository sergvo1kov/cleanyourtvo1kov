package com.phairplay.airplay

/**
 * StreamStats — live counters for the optional on-screen debug overlay (Settings → "Debug overlay").
 *
 * The mirror video server ([com.phairplay.airplay.handshake.MirrorStreamServer]) and audio server
 * ([com.phairplay.airplay.handshake.AudioStreamServer]) write these volatile fields as they run;
 * [com.phairplay.ui.StreamingScreen] polls [summary] a few times a second to render the HUD. Plain
 * volatile ints keep the hot path allocation-free — no flows or locks on the per-frame path.
 *
 * [overlayEnabled] is set from [com.phairplay.settings.AppSettings.showDebugOverlay] when the AirPlay
 * receiver starts, so the HUD appears for the next mirroring session after the toggle is flipped.
 */
object StreamStats {
    /** Master switch, mirrored from the user setting; the overlay only draws when true. */
    @Volatile var overlayEnabled = false

    // ─── Video (MirrorStreamServer) ──────────────────────────────────────────
    @Volatile var videoRes = ""        // e.g. "1920x1080"
    @Volatile var videoFps = 0         // frames/sec over the last sample window; -1 = URL player (not exposed)
    @Volatile var videoQueue = 0       // current decode-queue depth; -1 = URL player (not applicable)
    @Volatile var videoDropPct = 0     // cumulative % of frames dropped under load; -1 = URL player

    // Actual decoded video dimensions (from the SPS, so portrait phone streams are portrait here).
    // StreamingScreen reads these to aspect-fit the Surface instead of stretching to 16:9.
    @Volatile var videoWidth = 0
    /** True once video (mirror frames or a URL video) is actually on screen this session. */
    @Volatile var videoShowing = false
    @Volatile var videoHeight = 0

    // ─── Audio (AudioStreamServer) ───────────────────────────────────────────
    @Volatile var audioActive = false  // true while an audio stream is running
    @Volatile var audioQueue = 0       // current playback-queue depth; -1 = URL player
    @Volatile var audioDupPct = 0      // % of RTP packets that were redundant duplicates; -1 = URL player

    /** Clears per-stream counters (call when a mirror session ends). Keeps [overlayEnabled]. */
    fun resetStreams() {
        videoRes = ""; videoFps = 0; videoQueue = 0; videoDropPct = 0
        videoWidth = 0; videoHeight = 0
        audioActive = false; audioQueue = 0; audioDupPct = 0
    }

    /** Human-readable multi-line HUD text. */
    fun summary(): String =
        "PhairPlay · debug\n" +
        "VIDEO  ${videoRes.ifEmpty { "—" }}   ${if (videoFps >= 0) "$videoFps fps" else "n/a fps"}   " +
            "q ${if (videoQueue >= 0) videoQueue else "n/a"}   " +
            "drop ${if (videoDropPct >= 0) "$videoDropPct%" else "n/a"}\n" +
        "AUDIO  " + (if (audioActive) "on   q ${if (audioQueue >= 0) audioQueue else "n/a"}   " +
            "dup ${if (audioDupPct >= 0) "$audioDupPct%" else "n/a"}" else "off")
}
