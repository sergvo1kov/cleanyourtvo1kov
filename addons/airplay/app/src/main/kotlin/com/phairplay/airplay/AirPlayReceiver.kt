package com.phairplay.airplay

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.text.CueGroup
import com.phairplay.airplay.handshake.AirPlayNtpClient
import com.phairplay.airplay.handshake.AudioStreamServer
import com.phairplay.airplay.handshake.BufferedAudioServer
import com.phairplay.airplay.handshake.MirrorStreamServer
import com.phairplay.service.ProtocolState
import com.phairplay.service.VisualSessionPolicy
import com.phairplay.service.VisualSessionPolicy.Teardown
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket

/**
 * AirPlayReceiver — Top-level orchestrator for the AirPlay 2 receiver pipeline.
 *
 * WHY: Coordinates all AirPlay components into a single lifecycle:
 * - [MdnsService]: mDNS advertising (makes device visible in sender pickers)
 * - [RtspHandler]: RTSP handshake (OPTIONS → ANNOUNCE → SETUP → RECORD)
 * - [VideoDecoder]: H.264 hardware decode via MediaCodec → SurfaceView
 * - [AudioPlayer]: AES-128-CTR decrypt + AAC/ALAC decode → AudioTrack
 *
 * HOW: [PhairPlayService] creates this receiver and calls [start]/[stop].
 * The pipeline activates lazily — VideoDecoder and AudioPlayer are created
 * only after RECORD is received, when [SessionDescription] is available.
 *
 * For audio-only streams (music, podcasts), only [AudioPlayer] is started —
 * no [VideoDecoder] and no fullscreen streaming surface is needed.
 *
 * State changes are reported via [onStateChanged] to [PhairPlayService].
 *
 * Example:
 *   val receiver = AirPlayReceiver(
 *       context = context,
 *       displayName = settings.effectiveDisplayName,
 *       videoSurfaceProvider = { streamingScreen.getSurface() },
 *       onStateChanged = { state -> /* update UI */ }
 *   )
 *   receiver.start()
 *   receiver.stop()
 */
class AirPlayReceiver(
    private val context: Context,
    /** User-configured display name from Settings (blank = use system device name). */
    private val displayName: String = "",
    /** Advertised mirroring resolution (from the "high resolution" setting). */
    private val mirrorWidth: Int = 1920,
    private val mirrorHeight: Int = 1080,
    /** Whether to accept the mirroring audio stream (experimental — see AppSettings.mirrorAudioEnabled). */
    private val audioEnabled: Boolean = false,
    /** Require HomeKit-style SRP PIN pairing before streaming (AppSettings.airPlayPinAuthEnabled). */
    private val pinAuthEnabled: Boolean = false,
    /** URL-video identity. LEGACY is retained as the default and fallback. */
    private val videoTransport: AirPlayVideoTransport = AirPlayVideoTransport.LEGACY,
    /** Lazy Surface provider — called only for video streams when RECORD arrives. */
    private val videoSurfaceProvider: () -> Surface?,
    private val onStateChanged: (ProtocolState) -> Unit,
    /**
     * Called with the sender name when a streaming session starts (RECORD received).
     *
     * The name is extracted from the RTSP `User-Agent` header. The caller
     * ([PhairPlayService]) uses this to update the [ActiveConnection] and notification
     * text with the real sender identifier instead of the generic "AirPlay Sender".
     *
     * Guaranteed to be called BEFORE [onStateChanged] is called with [ProtocolState.CONNECTED].
     */
    private val onSenderNameChanged: (String) -> Unit = {},
    /** Called when iOS/macOS sends a JPEG/PNG to the `/photo` endpoint. */
    private val onPhotoReceived: (bytes: ByteArray, imageType: PhotoImageType) -> Unit = { _, _ -> },
    /** Called when iOS/macOS clears the currently displayed `/photo`. */
    private val onPhotoCleared: () -> Unit = {},
    /**
     * Called with the actual mDNS-registered name after [start].
     *
     * The name may differ from [displayName] if another device on the network already uses
     * the same name — NsdManager resolves the collision by appending " (2)", " (3)", etc.
     * The UI can use this callback to show the user the real registered name.
     */
    private val onActualNameRegistered: (String) -> Unit = {},
    /**
     * Audio-only "now playing" state. Emits a [NowPlayingInfo] when audio is streaming WITHOUT video
     * (system audio, Apple Music, podcasts) so the UI can show a now-playing card instead of a black
     * surface; emits null when video is mirroring (the video screen takes over) or audio stops.
     */
    private val onNowPlayingChanged: (NowPlayingInfo?) -> Unit = {},
    /** Pairing PIN to show ([pin]) or hide (null) on the TV during SRP pair-setup. */
    private val onPinChanged: (pin: String?) -> Unit = {},
    /**
     * A VIDEO session (screen mirroring, URL video) just began. Never called for audio-only starts.
     * Fired only from the session-start paths — never from [onStreamingStopped], so ending a
     * session cannot re-trigger it. May be called on any thread; must not block.
     */
    private val onVisualSessionStarted: () -> Unit = {},
    /** Timed text cues from URL/HLS playback, forwarded to the TV subtitle overlay. */
    private val onVideoCues: (CueGroup) -> Unit = {}
) {

    // Persistent store of paired controllers (for PIN access control / pair-verify).
    private val pairingStore = com.phairplay.airplay.handshake.PairingStore(context)

    // SupervisorJob: child coroutine failures don't propagate to siblings.
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    /**
     * Set as the very first thing [stop] does. Callbacks that race with teardown — the RTSP
     * connection thread reporting "streaming stopped" because [stop] just closed its socket, a
     * late network-available notification — check it so they don't do post-shutdown work:
     * re-advertise over mDNS (a ghost picker entry with nothing listening), or push a state
     * that overwrites the DISABLED the service has just published.
     */
    @Volatile private var stopping = false

    // Child components
    private var mdnsService: MdnsService? = null
    private var rtspHandler: RtspHandler? = null
    private var timingHandler: TimingHandler? = null
    private var videoDecoder: VideoDecoder? = null
    private var audioPlayer: AudioPlayer? = null

    // UDP socket for receiving audio RTP packets — opened after RECORD, closed on TEARDOWN
    @Volatile private var audioSocket: DatagramSocket? = null

    // AirPlay 2 mirroring: data stream server + event channel + keys (set during SETUP).
    @Volatile private var mirrorServer: MirrorStreamServer? = null
    @Volatile private var audioServer: AudioStreamServer? = null
    @Volatile private var bufferedAudioServer: BufferedAudioServer? = null
    @Volatile private var urlVideoPlayer: AirPlayVideoPlayer? = null
    @Volatile private var pendingPlaylistStop: Job? = null
    private val urlVideoGeneration = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var sessionActive = false

    // Reverse remote control (TV → sender). Created lazily once a sender advertises DACP-ID.
    private val dacpClient = DacpClient(context)

    // Held while any stream is active so the Wi-Fi radio can't power-save between packets.
    private val streamWifiLock = StreamWifiLock(context)
    @Volatile private var ntpClient: AirPlayNtpClient? = null
    @Volatile private var eventSocket: ServerSocket? = null
    @Volatile private var eventClientSocket: java.net.Socket? = null
    @Volatile private var mirrorAesKey: ByteArray? = null
    @Volatile private var mirrorEcdhSecret: ByteArray? = null
    @Volatile private var mirrorAesIv: ByteArray? = null

    // ─── Now-playing (audio-only) state ──────────────────────────────────────
    // The now-playing card shows only when audio plays WITHOUT video. We track both stream kinds
    // plus the latest DMAP metadata/artwork and recompute on every change (see [emitNowPlaying]).
    @Volatile private var audioPlaying = false
    @Volatile private var videoPlaying = false
    @Volatile private var npSenderName = "AirPlay"
    @Volatile private var npTitle: String? = null
    @Volatile private var npArtist: String? = null
    @Volatile private var npAlbum: String? = null
    @Volatile private var npArtwork: ByteArray? = null
    @Volatile private var npProgress: PlaybackProgress? = null
    @Volatile private var npPaused = false
    @Volatile private var npMultiroom = false
    @Volatile private var audioClockHz = 44_100L   // RTP clock of the current audio stream (multi-room may be 48 kHz)
    // Audio stream removed by the sender while the session lives on = paused (iOS pause); the
    // now-playing card stays up, paused, until the stream comes back or [PAUSED_CARD_TIMEOUT_MS].
    @Volatile private var audioSuspendJob: kotlinx.coroutines.Job? = null

    /**
     * Starts the AirPlay receiver.
     *
     * 1. Starts mDNS advertising with the configured display name.
     * 2. Opens the RTSP server socket (port 7000).
     * 3. Emits [ProtocolState.ADVERTISING] once both mDNS services are registered.
     *
     * Non-blocking — all network work runs in background coroutines.
     */
    fun start() {
        Logger.i("AirPlayReceiver starting (displayName='$displayName')")
        scope.launch {
            try {
                startTimingHandler()
                startMdnsService()
                startRtspHandler()
            } catch (e: Exception) {
                Logger.e("Failed to start AirPlayReceiver", e)
                emitState(ProtocolState.ERROR)
            }
        }
    }

    /**
     * Stops the AirPlay receiver and releases all resources.
     *
     * Stops RTSP handler, mDNS advertising, video decoder, and audio player.
     * Cancels all background coroutines.
     *
     * MUST be called when [PhairPlayService] stops or is destroyed.
     */
    fun stop() {
        Logger.i("AirPlayReceiver stopping")
        stopping = true
        try {
            rtspHandler?.stop()
            timingHandler?.stop()
            mdnsService?.stop()
            dacpClient.stop()
            releaseMediaComponents()
        } catch (e: Exception) {
            Logger.e("Error during AirPlayReceiver stop", e)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Sends a DACP transport command (see [DacpClient] constants) from the TV remote back to the
     * AirPlay sender — e.g. play/pause or skip what the Mac/iPhone is streaming. No-op if no sender
     * has advertised a DACP identity yet.
     */
    fun sendRemoteCommand(command: String) = dacpClient.sendCommand(command)

    /** True once a sender has advertised DACP reverse-control (so the TV remote can drive playback). */
    fun isRemoteControlAvailable(): Boolean = dacpClient.isAvailable

    /**
     * Re-registers the mDNS advertisement without touching the RTSP/timing servers.
     *
     * For when the network came back under us — the TV waking from standby being the case that
     * prompted this. An NsdManager registration does not survive its interface going down, and
     * nothing brings it back on its own, so the receiver keeps listening on port 7000 while
     * being invisible in every AirPlay picker. Restarting the whole receiver would do it too,
     * but that needlessly drops the sockets; only the advertisement is stale.
     *
     * No-op when the receiver was never started.
     */
    fun readvertise() {
        val mdns = mdnsService ?: return
        if (stopping) return
        Logger.i("Re-advertising AirPlay over mDNS (network returned)")
        scope.launch {
            try {
                mdns.restart(displayName.ifBlank { null })
            } catch (e: Exception) {
                Logger.e("Failed to re-advertise mDNS", e)
            }
        }
    }

    /**
     * Ends ONLY the visual session (screen mirror / video / photo) because the UI left the foreground.
     * The receiver, the mDNS advertisement and audio-only sessions are untouched. Idempotent, and a
     * no-op when nothing visual is running (so it is safe to call on every "UI left" event).
     * Safe from any thread; does socket work, so call it off the main thread.
     *
     * Mirroring shares the ONE RTSP connection with its audio (video 110 + audio 96 are SETUP on it;
     * [RtspHandler] accepts a single client). Closing the connection is what makes the sender notice,
     * and the mirror's own audio is meant to end with the mirror — so that is the default. Only if an
     * independent buffered-audio stream (103) is on the connection do we stop just the mirror streams.
     * See [VisualSessionPolicy.teardownFor]. The connection close comes back through
     * [onStreamingStopped] (the same path as a phone-initiated disconnect), which already re-advertises.
     *
     * @param photoShown whether the service is currently showing a photo (the receiver does not track it)
     */
    fun endVisualSession(photoShown: Boolean) {
        if (stopping) return
        val handler = rtspHandler ?: return
        val action = VisualSessionPolicy.teardownFor(
            videoActive = videoPlaying || urlVideoPlayer != null,
            photoShown = photoShown,
            independentAudioActive = bufferedAudioServer != null
        )
        Logger.i("End visual session: $action (video=$videoPlaying url=${urlVideoPlayer != null} photo=$photoShown)")
        when (action) {
            Teardown.NONE -> Unit
            Teardown.END_MIRROR_STREAMS -> handler.endMirrorStreams()
            Teardown.CLOSE_CONNECTION -> handler.closeClient()
        }
    }

    // ─── Private: startup ────────────────────────────────────────────────────

    private fun startTimingHandler() {
        timingHandler = TimingHandler().also { it.start(scope) }
        Logger.d("Timing handler started on UDP port ${TimingHandler.TIMING_PORT}")
    }

    private fun startMdnsService() {
        mdnsService = MdnsService(
            context = context,
            videoTransport = videoTransport,
            onStateChange = { state -> emitState(state) },
            onActualNameRegistered = { actualName -> onActualNameRegistered(actualName) }
        ).also { it.start(displayName.ifBlank { null }) }
        Logger.d("mDNS service started")
    }

    private fun startRtspHandler() {
        rtspHandler = RtspHandler(
            context = context,
            displayWidth = mirrorWidth,
            displayHeight = mirrorHeight,
            audioEnabled = audioEnabled,
            videoSurfaceProvider = videoSurfaceProvider,
            onStreamingStarted = { session -> onStreamingStarted(session) },
            onStreamingStopped = { onStreamingStopped() },
            onPhotoReceived = { bytes, imageType -> onPhotoReceived(bytes, imageType) },
            onPhotoCleared = { onPhotoCleared() },
            onMirrorSetupKeys = { aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort ->
                startMirrorKeys(aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort)
            },
            onMirrorStreamStart = { streamConnectionId -> startMirrorStream(streamConnectionId) },
            onMirrorAudioStart = { sampleRate, channels, ct, spf -> startMirrorAudio(sampleRate, channels, ct, spf) },
            onMirrorAudioStop = { stopMirrorAudio() },
            onMirrorVideoStop = { stopMirrorVideo() },
            onBufferedAudioStart = { startBufferedAudio() },
            onBufferedAudioStop = { stopBufferedAudio() },
            onMultiroomAudioStart = { startMultiroomAudio() },
            onMultiroomSampleRate = { hz -> audioClockHz = hz.toLong() },
            multiroomDelayMs = { MULTIROOM_AUDIO_DELAY_MS },
            // Fixed volume (see InfoResponder volumeControlType): sender volume is ignored and the
            // remote/soundbar owns the level, so no onVolume handler.
            onNowPlayingMetadata = { title, artist, album ->
                npTitle = title; npArtist = artist; npAlbum = album
                emitNowPlaying()
            },
            onArtwork = { bytes ->
                npArtwork = bytes.takeIf { it.isNotEmpty() }
                emitNowPlaying()
            },
            onProgress = { start, current, end -> onTrackProgress(start, current, end) },
            onPlaybackRate = { rate -> onPlaybackRate(rate) },
            onVideoPlay = { url, fraction, seconds -> startUrlVideo(url, fraction, seconds) },
            onVideoRate = { rate ->
                urlVideoPlayer?.setRate(rate)
                rtspHandler?.sendVideoStateEvent(urlVideoGeneration.get(), if (rate > 0f) "playing" else "paused")
            },
            onVideoScrub = { pos -> urlVideoPlayer?.scrub(pos) },
            onVideoSubtitlesEnabled = { enabled -> urlVideoPlayer?.setSubtitlesEnabled(enabled) },
            onVideoStop = { resetUrlVideoSession() },
            // A quality/episode change replaces the item on the existing control connection.
            onVideoPlaylistRemove = { resetUrlVideoSession(waitForReplacement = true) },
            onPlaybackInfo = { urlVideoPlayer?.info() },
            onIsUrlVideoActive = { urlVideoPlayer != null },
            // Safari closes the old reverse channel during a page reload. Give a
            // replacement /play a chance to arrive before tearing down the URL
            // player; otherwise the old channel can stop the new playback.
            onReverseChannelClosed = { scheduleUrlVideoStop() },
            onRemoteControlInfo = { dacpId, activeRemote -> dacpClient.configure(dacpId, activeRemote) },
            pinAuthEnabled = pinAuthEnabled,
            videoTransport = videoTransport,
            pairingStore = pairingStore,
            onShowPin = { pin -> onPinChanged(pin) }
        ).also { it.start(scope) }
        Logger.i("RTSP handler started on port 7000 (audioEnabled=$audioEnabled pinAuth=$pinAuthEnabled)")
    }

    // ─── Private: streaming lifecycle ────────────────────────────────────────

    /**
     * Called by [RtspHandler] when RECORD is received and [SessionDescription] is ready.
     *
     * Wires the media pipeline:
     * - video stream: creates [VideoDecoder] + wires [RtspHandler.onVideoNalUnit]
     * - audio stream: creates [AudioPlayer]
     * - audio-only:   only [AudioPlayer], app stays on HomeScreen
     */
    private fun onStreamingStarted(session: SessionDescription) {
        sessionActive = true
        Logger.i("Streaming started — video=${session.hasVideo} audio=${session.hasAudio} " +
                 "audioOnly=${session.isAudioOnly}")
        streamWifiLock.acquire()

        scope.launch {
            try {
                if (session.hasVideo) startVideoDecoder(session)
                if (session.hasAudio) startAudioPlayer(session)
                // Legacy (SDP) session: reflect its stream kinds into now-playing state so an
                // audio-only RAOP session shows the now-playing card.
                npSenderName = session.senderName.ifBlank { npSenderName }
                videoPlaying = session.hasVideo
                audioPlaying = session.hasAudio
                emitNowPlaying()
                if (session.hasVideo) onVisualSessionStarted()
                // Notify PhairPlayService of the sender name BEFORE emitting CONNECTED,
                // so the name is ready when the ActiveConnection is created.
                onSenderNameChanged(session.senderName)
                emitState(ProtocolState.CONNECTED)
            } catch (e: Exception) {
                Logger.e("Failed to start media pipeline", e)
                emitState(ProtocolState.ERROR)
            }
        }
    }

    /**
     * Called when streaming ends (TEARDOWN received or socket closed).
     *
     * Releases media components. DNS-SD stays registered throughout normal sessions, so there is
     * no need to unregister and register it again after every disconnect; doing that creates a
     * discovery gap and makes quick Safari retries race a changing service record.
     *
     * When the receiver itself is being stopped ([stopping]) this is only the echo of [stop]
     * closing the RTSP connection: release the media and nothing else.
     */
    @Synchronized private fun onStreamingStopped() {
        // Safari starts URL video on separate HTTP connections, then tears down the short-lived
        // RTSP audio handoff session. That TEARDOWN must release only the old mirror/audio transport:
        // the URL player and its HTTP control channel are still the active visual session.
        if (urlVideoPlayer != null) {
            Logger.i("RTSP transport stopped during URL video — preserving URL player")
            releaseMediaComponents(preserveUrlVideo = true)
            return
        }
        if (!sessionActive) {
            Logger.d("Duplicate streaming-stop callback ignored")
            return
        }
        sessionActive = false
        Logger.i("Streaming stopped — releasing media components")
        releaseMediaComponents()
        if (stopping) return
        emitState(ProtocolState.ADVERTISING)
    }

    // ─── Private: media pipeline ──────────────────────────────────────────────

    /**
     * Initializes [VideoDecoder] with SPS/PPS from the [SessionDescription].
     *
     * Resolution hint: AirPlay SDP does not include width/height — the actual
     * resolution is embedded in the SPS NAL unit. We pass [DEFAULT_VIDEO_WIDTH] ×
     * [DEFAULT_VIDEO_HEIGHT] as a hint; MediaCodec reads the real size from SPS.
     *
     * [RtspHandler.onVideoNalUnit] is wired here so RTP interleaved NAL units
     * flow directly into [VideoDecoder.decodeNalUnit].
     */
    private fun startVideoDecoder(session: SessionDescription) {
        val surface = videoSurfaceProvider() ?: run {
            Logger.w("VideoDecoder: no surface available — skipping video pipeline")
            return
        }
        val sps = session.spsBytes ?: run {
            Logger.w("VideoDecoder: no SPS in SDP — skipping")
            return
        }
        val pps = session.ppsBytes ?: run {
            Logger.w("VideoDecoder: no PPS in SDP — skipping")
            return
        }

        videoDecoder = VideoDecoder(surface).also { decoder ->
            decoder.initialize(sps, pps, DEFAULT_VIDEO_WIDTH, DEFAULT_VIDEO_HEIGHT)
            rtspHandler?.onVideoNalUnit = { nalUnit, ptsUs ->
                decoder.decodeNalUnit(nalUnit, ptsUs)
            }
        }
        Logger.i("VideoDecoder started (${DEFAULT_VIDEO_WIDTH}x${DEFAULT_VIDEO_HEIGHT} hint)")
    }

    /**
     * Initializes [AudioPlayer] with codec and encryption params from [SessionDescription].
     *
     * When the SDP contains no AES key/IV (unencrypted or missing keys), null is passed —
     * [AudioPlayer.initialize] skips cipher setup entirely and writes audio payload directly.
     * This prevents a zero-key cipher from producing garbage audio (S6-4 fix).
     */
    private fun startAudioPlayer(session: SessionDescription) {
        audioPlayer = AudioPlayer().also { player ->
            player.initialize(
                aesKey     = session.aesKey.takeIf { session.isAudioEncrypted },
                aesIv      = session.aesIv.takeIf  { session.isAudioEncrypted },
                sampleRate = session.sampleRate,
                channels   = session.channels,
                codec      = session.audioCodec,
                alacFramesPerPacket = session.alacFramesPerPacket
            )
        }
        Logger.i("AudioPlayer started (${session.sampleRate}Hz × ${session.channels}ch, " +
                 "codec=${session.audioCodec}, encrypted=${session.isAudioEncrypted})")

        startAudioUdpReceiver()
    }

    /**
     * Opens a UDP socket on [AUDIO_RTP_PORT] and feeds every received packet to
     * [AudioPlayer.playAudioPacket].
     *
     * WHY UDP: AirPlay audio is sent as RTP over UDP — low latency is more important
     * than guaranteed delivery. A missing packet produces a brief audio glitch,
     * which is far less disruptive than the buffering delays that TCP would introduce.
     *
     * The socket is closed in [releaseMediaComponents] when streaming ends.
     */
    private fun startAudioUdpReceiver() {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(AUDIO_RTP_PORT)
                audioSocket = socket
                Logger.i("Audio UDP receiver listening on port $AUDIO_RTP_PORT")

                val buf    = ByteArray(MAX_AUDIO_PACKET_BYTES)
                val packet = DatagramPacket(buf, buf.size)

                while (isActive) {
                    socket.receive(packet)
                    // copyOf trims to actual packet length before passing to the player
                    audioPlayer?.playAudioPacket(packet.data.copyOf(packet.length))
                }
            } catch (e: Exception) {
                // SocketException thrown when audioSocket.close() is called — expected
                if (audioSocket != null) {
                    Logger.e("Audio UDP receiver error (unexpected)", e)
                } else {
                    Logger.d("Audio socket closed (expected during shutdown)")
                }
            }
        }
    }

    // ─── Private: AirPlay 2 mirroring ─────────────────────────────────────────

    /**
     * Mirror SETUP msg 1: stash the decrypted AES key + pairing secret, open the event
     * channel (macOS connects to it), and switch the UI to the streaming surface.
     * @return the event channel's TCP port.
     */
    private fun startMirrorKeys(
        aesKey: ByteArray,
        ecdhSecret: ByteArray,
        aesIv: ByteArray,
        remoteAddress: java.net.InetAddress,
        senderTimingPort: Int,
    ): Pair<Int, Int> {
        sessionActive = true
        streamWifiLock.acquire()
        mirrorAesKey = aesKey
        mirrorEcdhSecret = ecdhSecret
        mirrorAesIv = aesIv
        // Explicit IPv4 wildcard — see RtspHandler.bindRtspSocket() for why.
        val event = ServerSocket(0, 50, java.net.InetAddress.getByName("0.0.0.0"))
        eventSocket = event
        // Accept + drain the event connection. We don't act on events yet, but macOS expects
        // the advertised event port to be connectable, so keep it open and readable.
        scope.launch(Dispatchers.IO) {
            try {
                event.accept().use { s ->
                    eventClientSocket = s
                    Logger.i("Event channel: macOS connected from ${s.inetAddress.hostAddress}")
                    val buf = ByteArray(4096)
                    val input = s.getInputStream()
                    while (isActive && input.read(buf) != -1) { /* drain */ }
                }
            } catch (e: Exception) {
                if (eventSocket != null) Logger.d("Event channel closed")
            } finally {
                eventClientSocket = null
            }
        }
        // AirPlay 2 NTP is receiver-initiated: poll the sender's timing port so macOS proceeds.
        val ntp = AirPlayNtpClient(remoteAddress, senderTimingPort).also { ntpClient = it; it.start(scope) }
        if (urlVideoPlayer == null) StreamStats.videoShowing = false
        onSenderNameChanged("AirPlay")
        emitState(ProtocolState.CONNECTED)
        Logger.i("Mirror keys set; eventPort=${event.localPort} timingPort=${ntp.localPort}")
        return event.localPort to ntp.localPort
    }

    /**
     * Mirror SETUP msg 2: start the data-stream server for the requested stream.
     * @return the data server's TCP port (macOS connects here to send H.264).
     */
    private fun startMirrorStream(streamConnectionId: Long): Int {
        val aesKey = mirrorAesKey ?: run { Logger.e("mirror stream start before keys set"); return 0 }
        val ecdhSecret = mirrorEcdhSecret ?: return 0
        mirrorServer?.stop() // a repeated SETUP for the same stream must not leak the previous server
        return MirrorStreamServer(aesKey, ecdhSecret, streamConnectionId, videoSurfaceProvider, mirrorWidth, mirrorHeight)
            .also { mirrorServer = it; it.start(scope); videoPlaying = true; emitNowPlaying(); onVisualSessionStarted() }
            .dataPort
            .also { Logger.i("Mirror data server started on port $it") }
    }

    /** Mirror SETUP audio stream (type 96): start the AAC-ELD / AAC-LC / ALAC audio server. @return (dataPort, controlPort). */
    private fun startMirrorAudio(sampleRate: Int, channels: Int, codecType: Int, framesPerPacket: Int): Pair<Int, Int> {
        val aesKey = mirrorAesKey ?: run { Logger.e("audio start before keys set"); return 0 to 0 }
        val ecdhSecret = mirrorEcdhSecret ?: return 0 to 0
        val aesIv = mirrorAesIv ?: return 0 to 0
        audioServer?.stop() // a repeated SETUP for the same stream must not leak the previous server
        val server = AudioStreamServer(aesKey, ecdhSecret, aesIv, sampleRate, channels, codecType, framesPerPacket)
            .also { audioServer = it; it.start(scope) }
        audioPlaying = true
        resumeFromSuspend()
        emitNowPlaying()
        onVisualSessionStarted()   // Now Playing is a screen takeover too — bring PhairPlay to front, like video
        Logger.i("Mirror audio server started: dataPort=${server.dataPort} controlPort=${server.controlPort}")
        return server.dataPort to server.controlPort
    }

    /** Stops ONLY the mirror audio stream (macOS dynamic-stream TEARDOWN) — video keeps running. */
    private fun stopMirrorAudio() {
        audioServer?.stop()
        audioServer = null
        if (!videoPlaying) { suspendAudio(); return }
        audioPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
        Logger.i("Mirror audio stream stopped (video mirroring continues)")
    }

    /**
     * Audio-only stream removed while the session stays open — iOS's pause. Keep the card up in
     * the paused state (as Apple TV does) instead of dropping back to the home screen, and give up
     * after [PAUSED_CARD_TIMEOUT_MS] if playback never resumes.
     */
    private fun suspendAudio() {
        onPlaybackRate(0f)
        audioSuspendJob?.cancel()
        audioSuspendJob = scope.launch {
            delay(PAUSED_CARD_TIMEOUT_MS)
            audioPlaying = false
            clearNowPlayingMetadata()
            emitNowPlaying()
            Logger.i("Paused audio card timed out")
        }
        Logger.i("Audio stream removed — paused, session kept")
    }

    private fun resumeFromSuspend() {
        audioSuspendJob?.cancel()
        audioSuspendJob = null
        onPlaybackRate(1f)
    }

    /** Stops ONLY the mirror video stream (macOS dynamic-stream TEARDOWN) — audio keeps playing. */
    private fun stopMirrorVideo() {
        mirrorServer?.stop()
        mirrorServer = null
        if (urlVideoPlayer == null) StreamStats.videoShowing = false
        videoPlaying = urlVideoPlayer != null
        emitNowPlaying()   // audio may still be playing → now-playing card can take over
        Logger.i("Mirror video stream stopped (audio playback continues)")
    }

    /**
     * AirPlay video URL mode (non-mirroring): show the streaming surface and hand the URL to
     * [AirPlayVideoPlayer], which fetches + plays it via MediaPlayer onto the same Surface.
     */
    @Synchronized private fun startUrlVideo(url: String, startFraction: Double, startSeconds: Double) {
        pendingPlaylistStop?.cancel()
        pendingPlaylistStop = null
        sessionActive = true
        // URL video often follows a short RTSP audio handoff. Mark the visual
        // session before notifying the UI, otherwise it remains on the audio-only
        // now-playing screen and hides the video controls.
        videoPlaying = true
        emitNowPlaying()
        streamWifiLock.acquire()
        onSenderNameChanged("AirPlay")
        emitState(ProtocolState.CONNECTED)   // shows StreamingScreen → Surface becomes available
        onVisualSessionStarted()
        val generation = urlVideoGeneration.incrementAndGet()
        val player = urlVideoPlayer ?: AirPlayVideoPlayer(
            context = context,
            surfaceProvider = videoSurfaceProvider,
            onEnded = { endedGeneration -> stopUrlVideo(endedGeneration) },
            onCues = onVideoCues
        ).also { urlVideoPlayer = it }
        player.play(url, startFraction, startSeconds, generation)
        rtspHandler?.sendVideoStateEvent(generation, "loading")
        Logger.i("AirPlay URL video generation=$generation started: $url (start=$startFraction / ${startSeconds}s)")
    }

    /** Stops AirPlay video URL playback (POST /stop or end-of-media) and ends the session. */
    @Synchronized private fun stopUrlVideo(expectedGeneration: Long? = null) {
        val currentGeneration = urlVideoGeneration.get()
        if (expectedGeneration != null && expectedGeneration != currentGeneration) {
            Logger.d("Ignoring stale URL-video stop generation=$expectedGeneration current=$currentGeneration")
            return
        }
        pendingPlaylistStop?.cancel()
        pendingPlaylistStop = null
        val player = urlVideoPlayer ?: run {
            Logger.d("Duplicate URL-video stop ignored")
            return
        }
        urlVideoPlayer = null
        player.release()
        rtspHandler?.sendVideoStateEvent(currentGeneration, "stopped")
        onStreamingStopped()
        Logger.i("AirPlay URL video stopped")
    }

    /** Waits for playlist replacement; explicit legacy /stop retains its reconnect behavior. */
    @Synchronized private fun resetUrlVideoSession(waitForReplacement: Boolean = false) {
        val generation = urlVideoGeneration.get()
        if (videoTransport == AirPlayVideoTransport.LEGACY && !waitForReplacement) {
            stopUrlVideo(generation)
            scope.launch {
                // Preserve the proven legacy reconnect path as an immediate fallback.
                delay(250)
                synchronized(this@AirPlayReceiver) {
                    if (urlVideoGeneration.get() == generation) rtspHandler?.reconnectVideoSession()
                    else Logger.d("Ignoring reconnect for replaced URL-video generation=$generation")
                }
            }
            return
        }
        // Both transports keep the control connection alive while the sender replaces the item.
        // Continue rendering the old item until the replacement /play arrives or the grace period expires.
        // Per the AirPlay video spec, the sender is documented to wait for this "stopped" event before
        // it knows the current item ended and it may send the next /play — send it right away instead
        // of only once the grace period times out, so a well-behaved sender can react immediately.
        rtspHandler?.sendVideoStateEvent(generation, "stopped")
        pendingPlaylistStop?.cancel()
        pendingPlaylistStop = scope.launch {
            delay(5_000)
            Logger.i("URL-video replacement timeout for generation=$generation")
            stopUrlVideo(generation)
        }
    }

    /** Gives a replacing reverse channel or /play a short window before ending the stale item. */
    @Synchronized private fun scheduleUrlVideoStop() {
        if (pendingPlaylistStop?.isActive == true) {
            // A playlistRemove (episode/quality switch) already started its own, longer grace
            // period moments earlier — Safari closing the reverse channel right after is part of
            // that same switch, not a separate signal. Don't let this shorter window cut it short.
            Logger.d("Reverse channel closed during an active playlist-replacement grace period — keeping it")
            return
        }
        val generation = urlVideoGeneration.get()
        pendingPlaylistStop = scope.launch {
            delay(2_000)
            if (urlVideoPlayer != null) {
                Logger.i("Reverse channel remained closed — releasing stale URL-video item")
                stopUrlVideo(generation)
            }
        }
    }

    fun urlPlaybackInfo(): PlaybackInfo? = urlVideoPlayer?.info()
    /** Reconnects an existing URL player to the current Activity surface after UI recreation. */
    fun attachUrlVideoSurface() { urlVideoPlayer?.attachSurface() }
    fun toggleUrlPlayback() {
        urlVideoPlayer?.let { player -> player.setRate(if ((player.info()?.rate ?: 0.0) > 0.0) 0f else 1f) }
    }
    fun seekUrlVideo(positionSec: Double) { urlVideoPlayer?.scrub(positionSec) }

    /** Starts the AirPlay 2 buffered audio-only stream (type 103, Apple Music → TV); returns its TCP port. */
    private fun startBufferedAudio(): Int {
        streamWifiLock.acquire()
        bufferedAudioServer?.stop()
        val server = BufferedAudioServer().also { bufferedAudioServer = it; it.start(scope) }
        audioPlaying = true   // buffered audio (type 103) is always audio-only
        resumeFromSuspend()
        emitNowPlaying()
        onVisualSessionStarted()   // Now Playing is a screen takeover too — bring PhairPlay to front, like video
        Logger.i("Buffered audio server started: dataPort=${server.dataPort}")
        return server.dataPort
    }

    /** Multi-room (PTP) buffered-audio stream set up: same Now Playing takeover as other audio-only sessions. */
    private fun startMultiroomAudio() {
        streamWifiLock.acquire()
        audioClockHz = AUDIO_CLOCK_HZ
        npMultiroom = true
        audioPlaying = true
        resumeFromSuspend()
        emitNowPlaying()
        onVisualSessionStarted()
        Logger.i("Multi-room audio session started")
    }

    /** Live delay change from the remote while the multi-room player is up: shift output by [deltaMs]. */

    /** Stops the buffered audio-only stream (type 103 TEARDOWN). */
    private fun stopBufferedAudio() {
        bufferedAudioServer?.stop()
        bufferedAudioServer = null
        if (!videoPlaying) { suspendAudio(); return }
        audioPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
        Logger.i("Buffered audio stream stopped")
    }

    /**
     * Clears the video NAL callback, closes the audio socket, and releases media components.
     *
     * Synchronized because it is reached from two threads at teardown: [stop] (service thread) and
     * the RTSP connection thread reporting the session end that [stop] just caused. Both walk the
     * same fields with check-then-null sequences.
     */
    @Synchronized
    private fun releaseMediaComponents(preserveUrlVideo: Boolean = false) {
        audioSuspendJob?.cancel()
        audioSuspendJob = null
        if (!preserveUrlVideo) StreamStats.videoShowing = false
        if (!preserveUrlVideo) streamWifiLock.release()
        rtspHandler?.onVideoNalUnit = null
        try { audioSocket?.close() } catch (e: Exception) { /* non-fatal */ }
        audioSocket = null
        mirrorServer?.stop()
        mirrorServer = null
        audioServer?.stop()
        audioServer = null
        bufferedAudioServer?.stop()
        bufferedAudioServer = null
        if (!preserveUrlVideo) {
            urlVideoPlayer?.release()
            urlVideoPlayer = null
        }
        ntpClient?.stop()
        ntpClient = null
        try { eventClientSocket?.close() } catch (e: Exception) { /* non-fatal */ }
        eventClientSocket = null
        try { eventSocket?.close() } catch (e: Exception) { /* non-fatal */ }
        eventSocket = null
        // Clear the FairPlay/ECDH keys on FULL teardown only. This method runs on a genuine session
        // end (last-stream / session TEARDOWN, or control-connection close) — NOT on a per-stream
        // teardown, which goes through stopMirrorAudio/stopMirrorVideo and leaves the keys intact so
        // macOS can re-add a dynamic stream on the same live session without re-sending keys (that
        // dynamic-readd path is why the keys must survive a stream stop). Clearing here prevents a
        // brand-new control connection from reusing a previous session's stale keys.
        mirrorAesKey = null
        mirrorEcdhSecret = null
        mirrorAesIv = null
        videoDecoder?.release()
        videoDecoder = null
        audioPlayer?.release()
        audioPlayer = null
        // Session fully torn down — clear now-playing so the UI leaves the audio card.
        audioPlaying = false
        videoPlaying = preserveUrlVideo && urlVideoPlayer != null
        clearNowPlayingMetadata()
        emitNowPlaying()
    }

    /** Pushes the current now-playing state out: a [NowPlayingInfo] when audio plays without video, else null. */
    private fun emitNowPlaying() {
        val show = audioPlaying && !videoPlaying
        onNowPlayingChanged(
            if (show) NowPlayingInfo(npSenderName, npTitle, npArtist, npAlbum, npArtwork, npProgress, npPaused, npMultiroom) else null
        )
    }

    /** Drops stale track metadata/artwork when an audio stream ends (so it can't bleed into the next). */
    private fun clearNowPlayingMetadata() {
        npTitle = null; npArtist = null; npAlbum = null; npArtwork = null
        npProgress = null; npPaused = false; npMultiroom = false
    }

    /** Converts the sender's RTP-timestamp progress (44.1 kHz clock, 32-bit wrap) to a position. */
    private fun onTrackProgress(start: Long, current: Long, end: Long) {
        val durationMs = rtpDelta(end, start) * 1000 / audioClockHz
        if (durationMs <= 0) return
        val positionMs = (rtpDelta(current, start) * 1000 / audioClockHz).coerceIn(0L, durationMs)
        npProgress = PlaybackProgress(positionMs, durationMs, SystemClock.elapsedRealtime())
        emitNowPlaying()
    }

    /** SETRATEANCHORTIME: rate 0 freezes the progress at its current position, rate > 0 resumes it. */
    private fun onPlaybackRate(rate: Float) {
        val paused = rate == 0f
        if (paused == npPaused) return
        val now = SystemClock.elapsedRealtime()
        npProgress = npProgress?.let { it.copy(positionMs = it.positionAt(now, npPaused), anchorElapsedMs = now) }
        npPaused = paused
        emitNowPlaying()
    }

    private fun rtpDelta(a: Long, b: Long): Long = (a - b) and 0xFFFFFFFFL

    // ─── Private: state emission ─────────────────────────────────────────────

    /** Dispatches [state] on the Main thread (Android UI rule). */
    private fun emitState(state: ProtocolState) {
        scope.launch {
            withContext(Dispatchers.Main) {
                // Re-checked on the Main thread: a state queued just before [stop] must not land
                // after the service published DISABLED and flip the UI back to "advertising".
                if (stopping && state != ProtocolState.DISABLED) return@withContext
                onStateChanged(state)
            }
        }
    }

    companion object {
        /** RTP clock of AirPlay audio (progress timestamps are in these units). */
        private const val AUDIO_CLOCK_HZ = 44_100L
        /** How long a paused (stream-removed) audio card stays up before returning home. */
        private const val PAUSED_CARD_TIMEOUT_MS = 15 * 60_000L

        // Hint dimensions for MediaCodec configuration.
        // Real resolution is encoded in the H.264 SPS NAL unit.
        private const val DEFAULT_VIDEO_WIDTH  = 1920
        private const val DEFAULT_VIDEO_HEIGHT = 1080

        /**
         * UDP port for receiving audio RTP packets.
         * Advertised in the RTSP SETUP response so the sender knows where to send audio.
         * Must not conflict with the RTSP port (7000) or timing port ([TimingHandler.TIMING_PORT]).
         */
        internal const val AUDIO_RTP_PORT = 6001

        /**
         * Maximum UDP audio packet size in bytes.
         * ALAC frames are typically ≤ 8 KB. 16 KB is a safe upper bound.
         */
        private const val MAX_AUDIO_PACKET_BYTES = 16 * 1024
    }
}
