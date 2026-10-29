package com.phairplay.service

/**
 * Pure decisions behind "the UI is a full-screen input source" (see the state machine in
 * [PhairPlayService]). No Android types, so they are unit-testable on the JVM.
 */
object VisualSessionPolicy {

    /** Which layer of the overlay container MainActivity shows — mirrors `MainActivity.updateOverlay`. */
    enum class Overlay { NONE, PIN, NOW_PLAYING, STREAMING, PHOTO }

    /** Same precedence as `MainActivity.updateOverlay`: PIN > now-playing > video > photo > nothing. */
    fun overlayFor(pinShown: Boolean, nowPlaying: Boolean, connected: Boolean, photoShown: Boolean): Overlay = when {
        pinShown -> Overlay.PIN
        nowPlaying -> Overlay.NOW_PLAYING
        connected -> Overlay.STREAMING
        photoShown -> Overlay.PHOTO
        else -> Overlay.NONE
    }

    /** True for the screens that are a visual takeover (mirror / photo) and must not outlive the UI. */
    fun isVisual(overlay: Overlay): Boolean = overlay == Overlay.STREAMING || overlay == Overlay.PHOTO

    /**
     * How the receiver ends a visual session.
     *
     *  - [NONE]: nothing visual is running (audio-only, idle) — leave everything alone.
     *  - [END_MIRROR_STREAMS]: stop only the mirror's own streams (video 110 + its audio 96), keep the
     *    RTSP connection because an independent audio stream (buffered, type 103) rides on it.
     *  - [CLOSE_CONNECTION]: close the sender's RTSP connection. This is what makes the phone notice
     *    that the session is over; nothing independent is on it to be cut.
     */
    enum class Teardown { NONE, END_MIRROR_STREAMS, CLOSE_CONNECTION }

    /**
     * @param videoActive            a mirror / video stream is running
     * @param photoShown             a photo is on screen
     * @param independentAudioActive an audio stream that is NOT part of the mirror shares the connection
     */
    fun teardownFor(videoActive: Boolean, photoShown: Boolean, independentAudioActive: Boolean): Teardown = when {
        !videoActive && !photoShown -> Teardown.NONE
        // A photo alone cannot be ended without closing the connection; with independent audio on it
        // the UI simply drops the photo (the service clears it) and the audio carries on.
        independentAudioActive -> if (videoActive) Teardown.END_MIRROR_STREAMS else Teardown.NONE
        else -> Teardown.CLOSE_CONNECTION
    }
}
