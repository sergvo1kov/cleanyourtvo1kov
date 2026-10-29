package com.phairplay.airplay

import com.phairplay.airplay.handshake.FairPlay
import com.phairplay.airplay.handshake.InfoResponder
import com.phairplay.airplay.handshake.PairingKeys
import com.phairplay.airplay.handshake.PairingSession
import com.phairplay.airplay.handshake.PlistCodec
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * RtspHandler — Manages the RTSP session with the AirPlay sender (macOS).
 *
 * AirPlay uses RTSP to negotiate codecs, ports, and encryption before media flows.
 * The handler accepts one sender at a time, parses ANNOUNCE SDP, acknowledges SETUP
 * and RECORD, then hands binary interleaved RTP frames to [RtpInterleaved].
 */
open class RtspHandler(
    private val context: android.content.Context,
    private val displayWidth: Int = 1920,
    private val displayHeight: Int = 1080,
    private val audioEnabled: Boolean = false,
    private val videoSurfaceProvider: () -> android.view.Surface?,
    private val onStreamingStarted: (session: SessionDescription) -> Unit,
    private val onStreamingStopped: () -> Unit,
    private val onPhotoReceived: (bytes: ByteArray, imageType: PhotoImageType) -> Unit = { _, _ -> },
    private val onPhotoCleared: () -> Unit = {},
    /**
     * AirPlay 2 mirror SETUP msg 1: supply decrypted AES key + pairing secret + the sender's
     * address and timing port (so the receiver can start NTP). Returns (eventPort, timingPort).
     */
    private val onMirrorSetupKeys: (
        aesKey: ByteArray, ecdhSecret: ByteArray, aesIv: ByteArray,
        remoteAddress: java.net.InetAddress, senderTimingPort: Int
    ) -> Pair<Int, Int> = { _, _, _, _, _ -> 0 to 0 },
    /** AirPlay 2 mirror SETUP: start the video data server (type 110); returns its data port. */
    private val onMirrorStreamStart: (streamConnectionId: Long) -> Int = { 0 },
    /** AirPlay 2 SETUP: start the audio server (type 96; ct 8 AAC-ELD mirror / 4 AAC-LC / 2 ALAC). spf = samples/frame. */
    private val onMirrorAudioStart: (sampleRate: Int, channels: Int, codecType: Int, framesPerPacket: Int) -> Pair<Int, Int> = { _, _, _, _ -> 0 to 0 },
    /** AirPlay 2 mirror TEARDOWN of just the audio stream (type 96) — stop audio, keep video. */
    private val onMirrorAudioStop: () -> Unit = {},
    /** AirPlay 2 mirror TEARDOWN of just the video stream (type 110) — stop video, keep audio. */
    private val onMirrorVideoStop: () -> Unit = {},
    /** AirPlay 2 buffered audio-only SETUP (type 103, Apple Music → TV); returns the TCP data port. */
    private val onBufferedAudioStart: () -> Int = { 0 },
    /** Stops the buffered audio-only stream (type 103 TEARDOWN). */
    private val onBufferedAudioStop: () -> Unit = {},
    /** Sender volume change (AirPlay dB: −30…0, or ≤ −144 = mute) via SET_PARAMETER. */
    private val onVolume: (Float) -> Unit = {},
    /** Now-playing track metadata (DMAP) from SET_PARAMETER — any field may be null. */
    private val onNowPlayingMetadata: (title: String?, artist: String?, album: String?) -> Unit = { _, _, _ -> },
    /** Album artwork (JPEG/PNG bytes) from SET_PARAMETER; empty bytes = artwork cleared. */
    private val onArtwork: (ByteArray) -> Unit = {},
    /** Track progress as RTP timestamps (start / now playing / end) from SET_PARAMETER "progress". */
    private val onProgress: (start: Long, current: Long, end: Long) -> Unit = { _, _, _ -> },
    /** Buffered-audio playback rate from SETRATEANCHORTIME (0 = paused, 1 = playing). */
    private val onPlaybackRate: (rate: Float) -> Unit = {},
    /** Multi-room (PTP) buffered-audio stream set up: bring the now-playing card up. */
    private val onMultiroomAudioStart: () -> Unit = {},
    /** Multi-room audio sample rate (RTP clock Hz), known once the first audio packet arrives. */
    private val onMultiroomSampleRate: (Int) -> Unit = {},
    /** Multi-room audio delay trim in ms (settings), read at every (re)start of playback. */
    private val multiroomDelayMs: () -> Int = { 0 },
    /** AirPlay video URL mode: POST /play with a media URL + start position (fraction 0..1 and/or seconds). */
    private val onVideoPlay: (url: String, startFraction: Double, startSeconds: Double) -> Unit = { _, _, _ -> },
    /** AirPlay video transport: POST /rate (≤0 pause, >0 resume). */
    private val onVideoRate: (rate: Float) -> Unit = {},
    /** AirPlay video transport: POST /scrub — seek to position (seconds). */
    private val onVideoScrub: (positionSec: Double) -> Unit = {},
    /** AirPlay video transport: POST /stop — stop URL playback. */
    private val onVideoStop: () -> Unit = {},
    /** AirPlay video transport: PUT /setProperty?selectedMediaArray — the sender's real subtitle/audio choice. */
    private val onVideoSubtitlesEnabled: (enabled: Boolean) -> Unit = {},
    /** Playlist replacement (for example a site quality switch); a new /play may follow immediately. */
    private val onVideoPlaylistRemove: () -> Unit = {},
    /** Current URL-video playback snapshot for GET /playback-info and GET /scrub. */
    private val onPlaybackInfo: () -> com.phairplay.airplay.PlaybackInfo? = { null },
    /** True while URL-video (/play) is actively playing — a mirror-audio handoff TEARDOWN must not end it. */
    private val onIsUrlVideoActive: () -> Boolean = { false },
    /** Sender's DACP reverse-control identity from RTSP headers (DACP-ID + Active-Remote token). */
    private val onRemoteControlInfo: (dacpId: String?, activeRemote: String?) -> Unit = { _, _ -> },
    /** Called when the sender closes the long-lived reverse HTTP event channel. */
    private val onReverseChannelClosed: () -> Unit = {},
    /** When true, require HomeKit-style SRP PIN pairing before streaming (gated by AppSettings). */
    private val pinAuthEnabled: Boolean = false,
    /** Persistent store of paired controllers' Ed25519 keys (for pair-verify). */
    private val pairingStore: com.phairplay.airplay.handshake.PairingStore? = null,
    /** Shows ([pin]) or hides (null) the on-screen pairing PIN during SRP pair-setup. */
    private val onShowPin: (pin: String?) -> Unit = {},
    private val videoTransport: AirPlayVideoTransport = AirPlayVideoTransport.LEGACY,
) {

    // ─── Legacy AirPlay SRP PIN pairing (only used when pinAuthEnabled) ───────
    @Volatile private var legacyPin: com.phairplay.airplay.handshake.LegacyPairSetupPin? = null
    // True once a controller has completed SRP PIN pairing. Until then, with PIN auth on, we reject
    // pair-verify — which is what makes macOS fall back to the /pair-pin-start + /pair-setup-pin PIN
    // flow (an accepted pair-verify means "already trusted, no PIN needed").
    @Volatile private var pinPaired = false

    /** Last volume the sender set (AirPlay dB); returned to GET_PARAMETER volume queries. */
    // Fixed volume: always report full level (0 dB) to the sender's GET_PARAMETER volume.
    @Volatile private var currentVolume: Float = 0f

    private var serverSocket: ServerSocket? = null

    @Volatile
    private var activeClient: Socket? = null

    @Volatile
    private var running = false

    private var currentCSeq: Int = 0

    @Volatile
    private var currentSession: SessionDescription? = null

    // Per-connection state. Every connection is served on its own thread (the control connection and each AirPlay
    // video connection concurrently), so these are thread-local: a video connection's own pair-verify/fp-setup must
    // not overwrite — or find null — the control connection's state.
    private val pairingSessionLocal = ThreadLocal<PairingSession?>()
    private val fairPlayLocal = ThreadLocal<FairPlay?>()
    private val hapPairingLocal = ThreadLocal<com.phairplay.airplay.handshake.HapSecureSession?>()
    private val hapTransientLocal = ThreadLocal<com.phairplay.airplay.handshake.HapTransientPairing?>()
    private val hapPinSetupLocal = ThreadLocal<com.phairplay.airplay.handshake.HapPinSetup?>()
    private val hapVerifyLocal = ThreadLocal<com.phairplay.airplay.handshake.HapPairVerify?>()
    /** Multi-room PIN (shown on screen); lives across TCP connections until paired / expired. */
    @Volatile private var mrPin: String? = null
    private val mrStore by lazy { pairingStore ?: com.phairplay.airplay.handshake.PairingStore(context) }
    private val hapUrlSessionLocal = ThreadLocal<HapUrlSession?>()
    private val hapUrlSessions = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<HapUrlSession, Boolean>())
    private val remoteAddressLocal = ThreadLocal<java.net.InetAddress?>()
    private val multiroomLocal = ThreadLocal<MultiroomSession?>()
    private val multiroomSessions = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<MultiroomSession, Boolean>())

    /** Per-connection AirPlay pairing state (pair-setup / pair-verify). */
    private var pairingSession: PairingSession?
        get() = pairingSessionLocal.get()
        set(value) = pairingSessionLocal.set(value)

    /** Per-connection FairPlay state (fp-setup handshake + stream-key decrypt). */
    private var fairPlay: FairPlay?
        get() = fairPlayLocal.get()
        set(value) = fairPlayLocal.set(value)

    /** Remote (sender) address of the active control connection — needed for AirPlay 2 NTP. */
    @Volatile
    private var currentRemoteAddress: java.net.InetAddress? = null

    /** True once an AirPlay 2 mirroring SETUP has run on this connection (no ANNOUNCE/SDP). */
    @Volatile
    private var isMirrorSession = false

    /** Mirror stream types currently active (96 = audio, 110 = video). Drives TEARDOWN routing.
     *  `protected` so tests can seed it without driving the full FairPlay SETUP handshake. */
    protected val activeStreamTypes: MutableSet<Int> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())

    /** When each audio stream (96 / 103) was set up (System.nanoTime) — tells a pause from a handoff. */
    protected val audioStreamStartedAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    private var setupCount = 0

    private val requestReader = RtspRequestReader(
        maxMessageBytes = MAX_MESSAGE_BYTES,
        maxPhotoBytes = PhotoHandler.MAX_PHOTO_BYTES,
        maxArtworkBytes = MAX_ARTWORK_BYTES
    )

    /**
     * Callback for decoded H.264 NAL units from the RTP stream.
     * Set by [AirPlayReceiver] after RECORD — wires to [VideoDecoder.decodeNalUnit].
     * Null for audio-only streams.
     */
    @Volatile
    var onVideoNalUnit: ((nalUnit: ByteArray, ptsUs: Long) -> Unit)? = null

    /** Starts the RTSP server. */
    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) {
            runServer(this)
        }
    }

    /** Stops the RTSP server. */
    fun stop() {
        running = false
        hapUrlSessions.toList().forEach { it.close() }
        hapUrlSessions.clear()
        multiroomSessions.toList().forEach { it.close() }
        multiroomSessions.clear()
        try {
            activeClient?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Logger.e("Error closing RTSP sockets (non-fatal)", e)
        }
        activeClient = null
        serverSocket = null
        endVideoChannel()
        Logger.i("RTSP handler stopped")
    }

    // ─── AirPlay video connections (HTTP, X-Apple-Session-ID, no CSeq) ───────────────────────────
    // A sender playing video opens SEVERAL TCP connections next to the RTSP-style control one: a reverse
    // (PTTH) channel we send events on, and plain HTTP connections carrying /play, /playback-info, /action ...
    // They run concurrently with the control connection, each on its own thread.

    private val connectionPool = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "airplay-conn").apply { isDaemon = true }
    }
    /** Serializes the RTSP-style control connections: one at a time, exactly as before. */
    private val controlLock = java.util.concurrent.locks.ReentrantLock()
    @Volatile private var reverseChannel: ReverseChannel? = null
    @Volatile private var videoSessionId: String = ""
    private val videoConnections = java.util.concurrent.atomic.AtomicInteger(0)
    private val videoSockets: MutableSet<Socket> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())
    private val hlsRelay = HlsRelay(
        sendFcup = { url, id ->
            val ch = reverseChannel
            if (ch == null) {
                Logger.w("HLS relay: no reverse channel to send the FCUP request on")
                false
            } else {
                Logger.d("FCUP request #$id → $url")
                ch.sendEvent(videoSessionId, HlsRelay.fcupRequestXml(url, id, videoSessionId))
            }
        },
        onReady = { localUrl, startSec -> onVideoPlay(localUrl, 0.0, startSec) },
    )

    /**
     * Sends a video playback-state `POST /event` (loading/playing/paused/stopped) on the reverse
     * channel, per the AirPlay video spec. Best-effort: a no-op if no reverse channel is currently up.
     */
    fun sendVideoStateEvent(sessionId: Long, state: String) {
        val ch = reverseChannel ?: return
        if (!ch.sendEvent(videoSessionId, HlsRelay.videoStateEventXml(sessionId, state))) {
            Logger.d("Video state event ($state) failed to send — reverse channel likely closed")
        }
    }

    /** Ends the reverse channel and any HLS relay (server stop, or a `POST /stop`). */
    private fun endVideoChannel() {
        hlsRelay.stop()
        reverseChannel?.close()
        reverseChannel = null
    }

    /**
     * Ends the mirror's own streams (video 110 + its audio 96) from OUTSIDE the connection thread —
     * the UI left the foreground — while leaving the RTSP connection and any other stream (buffered
     * audio 103) alone. Removing the types here keeps [handleTeardownInternal] honest: a later
     * TEARDOWN of the remaining streams sees "last stream removed" and does the full teardown.
     * [activeStreamTypes] is a concurrent set because this runs off the connection thread.
     */
    fun endMirrorStreams() {
        if (activeStreamTypes.remove(110)) onMirrorVideoStop()
        if (activeStreamTypes.remove(96)) onMirrorAudioStop()
    }

    /**
     * Closes just the current sender connection, keeping the server socket listening (unlike [stop]).
     * The connection thread wakes with an exception, and its `finally` runs the normal end-of-session
     * path (`onStreamingStopped`) — the same one a phone-initiated disconnect takes.
     */
    fun closeClient() {
        try {
            activeClient?.close()
        } catch (e: Exception) {
            Logger.w("Error closing RTSP client (non-fatal): ${e.message}")
        }
    }

    /**
     * Drops the sender's URL-video HTTP/PTTH session while keeping port 7000 and mDNS alive.
     * Safari can otherwise retain a removed playlist UUID and never send /play for the next item.
     */
    fun reconnectVideoSession() {
        hlsRelay.stop()
        reverseChannel = null
        videoSessionId = ""
        val sockets = videoSockets.toList()
        Logger.i("Resetting AirPlay URL-video control session (${sockets.size} sockets)")
        sockets.forEach { socket -> runCatching { socket.close() } }
    }

    /**
     * Binds the RTSP port with SO_REUSEADDR, retrying briefly if a just-stopped instance hasn't
     * released it yet. A quick service stop→start (the activity being destroyed and relaunched)
     * could otherwise fail with EADDRINUSE, leaving PhairPlay advertising over mDNS while port 7000
     * was dead — macOS would discover it and try to mirror but nothing could connect ("casting but
     * nothing shows"). SO_REUSEADDR handles TIME_WAIT; the retry covers the close/rebind race.
     */
    private fun bindRtspSocket(): ServerSocket {
        var lastError: java.io.IOException? = null
        repeat(BIND_MAX_ATTEMPTS) { attempt ->
            if (!running) throw java.io.IOException("RTSP server stopped before bind")
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    // Bind explicitly to the IPv4 wildcard: InetSocketAddress(port) resolves to
                    // an IPv6 wildcard on Android, which some devices' network stacks don't
                    // dual-stack onto the LAN's IPv4 interface — IPv4 senders (most phones on a
                    // typical home LAN) then get their SYN silently dropped instead of accepted.
                    bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("0.0.0.0"), RTSP_PORT))
                }
            } catch (e: java.io.IOException) {
                lastError = e
                Logger.w("RTSP port $RTSP_PORT busy (attempt ${attempt + 1}/$BIND_MAX_ATTEMPTS) — retrying in ${BIND_RETRY_MS}ms")
                try { Thread.sleep(BIND_RETRY_MS) } catch (_: InterruptedException) { throw e }
            }
        }
        throw lastError ?: java.io.IOException("RTSP bind to $RTSP_PORT failed")
    }

    private fun runServer(scope: CoroutineScope) {
        try {
            serverSocket = bindRtspSocket()
            Logger.i("RTSP server listening on port $RTSP_PORT")

            while (running && scope.isActive) {
                val clientSocket = serverSocket!!.accept()
                Logger.i("New client connected: ${clientSocket.inetAddress.hostAddress}")
                connectionPool.execute { serveConnection(clientSocket) }
            }
        } catch (e: Exception) {
            if (running) {
                Logger.e("RTSP server error (unexpected)", e)
            } else {
                Logger.d("RTSP server socket closed (expected during shutdown)")
            }
        }
    }

    /**
     * Classifies a new connection by its first request: an AirPlay video connection carries an
     * `X-Apple-Session-ID` and no `CSeq` (plain HTTP); everything else is the RTSP-style control connection,
     * served one at a time (later ones wait for the earlier one to finish, as when the accept loop was serial).
     */
    private fun serveConnection(socket: Socket) {
        remoteAddressLocal.set(socket.inetAddress)
        try {
            runCatching { socket.tcpNoDelay = true }
            val first = requestReader.read(socket.getInputStream())
            if (first == null) { socket.close(); return }
            val isVideoChannel = first.headers["CSeq"] == null && first.headers["X-Apple-Session-ID"] != null
            if (isVideoChannel) {
                serveAirPlayHttp(socket, first)
            } else {
                controlLock.lock()
                try {
                    if (!running || socket.isClosed) { socket.close(); return }
                    activeClient = socket
                    handleClient(socket, first)
                } finally {
                    controlLock.unlock()
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Connection error", e)
            runCatching { socket.close() }
        } finally {
            closeHapUrlSession()
            closeMultiroomSession()
            remoteAddressLocal.remove()
            hapPairingLocal.remove()
            hapTransientLocal.remove(); hapPinSetupLocal.remove(); hapVerifyLocal.remove()
            pairingSessionLocal.remove()
            fairPlayLocal.remove()
        }
    }

    private fun handleClient(socket: Socket, firstRequest: RtspRequest? = null) {
        var inputStream = socket.getInputStream()
        var outputStream = socket.getOutputStream()
        var encrypted = false
        var pendingRequest: RtspRequest? = firstRequest

        // Fresh pairing + FairPlay state for each control connection.
        pairingSession = PairingSession(PairingKeys.get(context))
        fairPlay = FairPlay()
        // NOTE: legacyPin and pinPaired are deliberately NOT reset here. macOS runs the PIN handshake
        // across SEPARATE TCP connections (/pair-pin-start on one, /pair-setup-pin on the next), so the
        // PIN/verifier and the "paired" flag must survive a reconnect. They live for the receiver's
        // lifetime — replaced by the next /pair-pin-start, set on a successful pairing.
        currentRemoteAddress = socket.inetAddress
        // Small request/response control channel: don't let Nagle coalesce/delay our replies.
        runCatching { socket.tcpNoDelay = true }

        try {
            while (running && !socket.isClosed) {
                val request = pendingRequest ?: requestReader.read(inputStream) ?: break
                pendingRequest = null
                currentCSeq = request.headers["CSeq"]?.toIntOrNull() ?: 0
                val response = routeRequest(request)
                sendResponse(outputStream, response)
                val hap = hapPairingLocal.get()
                if (!encrypted && hap?.complete == true) {
                    inputStream = com.phairplay.airplay.handshake.HapInputStream(inputStream,
                        hap.key("Control-Salt", "Control-Write-Encryption-Key"))
                    outputStream = com.phairplay.airplay.handshake.HapOutputStream(outputStream,
                        hap.key("Control-Salt", "Control-Read-Encryption-Key"))
                    encrypted = true
                    Logger.i("HAP control encryption enabled")
                }

                // After RECORD on a legacy SDP session: a session WITH video switches to interleaved
                // RTP (video arrives $-framed over this TCP socket). An audio-only session (e.g. Apple
                // Music) keeps the RTSP control loop — audio arrives on the UDP port, and macOS sends
                // now-playing metadata / volume / FLUSH / TEARDOWN as RTSP requests here that we must
                // keep handling (switching to interleaved mode would skip them → no metadata).
                if (request.method == "RECORD" && response.statusCode == 200 && !isMirrorSession &&
                    currentSession?.hasVideo == true) {
                    Logger.d("RTSP handshake complete — switching to interleaved RTP (video)")
                    break
                }
            }

            val session = currentSession
            if (session != null && session.hasVideo && running) {
                RtpInterleaved.readLoop(
                    inputStream = inputStream,
                    onVideoNalUnit = { nalUnit, ptsUs ->
                        onVideoNalUnit?.invoke(nalUnit, ptsUs)
                    },
                    onStreamEnded = {
                        Logger.i("RTP stream ended")
                    }
                )
            }
        } catch (e: Exception) {
            if (running) Logger.e("Error handling RTSP client", e)
        } finally {
            Logger.i("Client disconnected")
            // A control connection that never started a stream (pair-verify churn, /info probes) must not tear
            // down an AirPlay video session that is running on its own HTTP connections.
            val hadSession = currentSession != null || isMirrorSession || setupCount > 0
            socket.close()
            activeClient = null
            currentSession = null
            pairingSession = null
            fairPlay = null
            isMirrorSession = false
            activeStreamTypes.clear()
            setupCount = 0
            if (hadSession || videoConnections.get() == 0) onStreamingStopped()
        }
    }

    // ─── AirPlay video HTTP connection ───────────────────────────────────────────────────────────

    /**
     * Serves one AirPlay video connection: plain HTTP requests (`/play`, `/playback-info`, `/rate`, `/scrub`,
     * `/stop`, `/action`, `/server-info` ...). `POST /reverse` upgrades the connection to the reversed (PTTH)
     * channel, after which we only send events on it and drain the sender's answers.
     */
    private fun serveAirPlayHttp(socket: Socket, first: RtspRequest) {
        videoSockets.add(socket)
        videoConnections.incrementAndGet()
        var input = socket.getInputStream()
        var output = socket.getOutputStream()
        var encrypted = false
        var pending: RtspRequest? = first
        // This connection does its own pair-verify + fp-setup.
        pairingSession = PairingSession(PairingKeys.get(context))
        fairPlay = FairPlay()
        try {
            while (running && !socket.isClosed) {
                val request = pending ?: requestReader.read(input) ?: run {
                    dumpUnreadable(socket, input)
                    null
                } ?: break
                pending = null
                request.headers["X-Apple-Session-ID"]?.let { videoSessionId = it }
                Logger.d("AirPlay-HTTP ${request.method} ${request.uri}")
                val response = routeAirPlayHttp(request)
                sendResponse(output, response)
                val hap = hapPairingLocal.get()
                if (!encrypted && hap?.complete == true) {
                    input = com.phairplay.airplay.handshake.HapInputStream(input,
                        hap.key("Control-Salt", "Control-Write-Encryption-Key"))
                    output = com.phairplay.airplay.handshake.HapOutputStream(output,
                        hap.key("Control-Salt", "Control-Read-Encryption-Key"))
                    encrypted = true
                    Logger.i("HAP HTTP encryption enabled")
                }
                if (response.statusCode == 101) {
                    serveReverseChannel(socket, input, output)
                    break
                }
            }
        } catch (e: Exception) {
            if (running) Logger.d("AirPlay video connection ended: ${e.message}")
        } finally {
            runCatching { socket.close() }
            videoSockets.remove(socket)
            videoConnections.decrementAndGet()
        }
    }

    /**
     * Diagnostics: the sender sent something that is not HTTP (after `/fp-setup2`). Log the next bytes in hex to
     * identify the framing (the first line was already logged by the request reader).
     */
    private fun dumpUnreadable(socket: Socket, input: java.io.InputStream) {
        val buf = ByteArray(300)
        var n = 0
        try {
            socket.soTimeout = 700
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r <= 0) break
                n += r
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.soTimeout = 0 }
        }
        Logger.w("Unreadable stream continues ($n bytes read): " + buf.take(n).joinToString(" ") { "%02x".format(it) })
    }

    /** After the 101: our side of the socket is the HTTP *client* now. Keep it registered and drain replies. */
    private fun serveReverseChannel(socket: Socket, input: java.io.InputStream, output: OutputStream) {
        val channel = ReverseChannel(socket, output)
        reverseChannel = channel
        Logger.i("Reverse (PTTH) channel established for session $videoSessionId")
        try {
            val buf = ByteArray(4096)
            while (running && input.read(buf) != -1) { /* replies to our /event requests: nothing to act on */ }
        } catch (_: Exception) {
        } finally {
            // A page reload can leave the old reverse socket behind while a new
            // one is already active. Only the current channel may tear down the
            // URL session; stale sockets must be ignored.
            val wasCurrent = reverseChannel === channel
            if (wasCurrent) reverseChannel = null
            Logger.i("Reverse (PTTH) channel closed")
            if (wasCurrent) onReverseChannelClosed()
        }
    }

    private fun routeAirPlayHttp(request: RtspRequest): RtspResponse {
        val path = request.uri.substringBefore("?")
        return when {
            request.method in listOf("SETUP", "RECORD", "TEARDOWN", "OPTIONS") -> routeRequest(request)
            request.method == "POST" && path == "/reverse" -> handleReverse(request)
            request.method == "POST" && path == "/action" -> handleAction(request)
            (request.method == "POST" || request.method == "GET") && path == "/getProperty" ->
                RtspResponse(200, "OK", protocol = request.responseProtocol())
            request.method == "PUT" && path == "/setProperty" ->
                handleSetProperty(request)
            request.method == "GET" -> routeGet(request)
            request.method == "POST" -> routePost(request)
            else -> handleUnknownInternal(request)
        }
    }

    private fun handleSetProperty(request: RtspRequest): RtspResponse {
        val name = request.uri.substringAfter("?", "")
        Logger.d("SET property $name accepted")
        if (name == "selectedMediaArray") applySelectedMediaArray(request.bodyBytes)
        // UxPlay acknowledges the known media properties with errorCode=0 but does not
        // echo sender-owned selection state back through getProperty.
        val body = PlistCodec.encodeXml(mapOf("errorCode" to 0L))
        return RtspResponse(200, "OK", bodyBytes = body,
            contentType = "text/x-apple-plist+xml", protocol = request.responseProtocol())
    }

    /**
     * `selectedMediaArray`'s exact schema is undocumented (private AirPlay/AVFoundation media-selection
     * plist), so this is a best-effort reading, not a verified parser: scan the decoded structure for any
     * string that names a subtitle/legible track (by substring — real key names are unconfirmed) paired
     * with an "Enabled"-looking boolean nearby, and use the last one found. If nothing recognizable turns
     * up, subtitles stay off — same default as when this property is never sent at all.
     */
    private fun applySelectedMediaArray(body: ByteArray) {
        val root = runCatching { PlistCodec.decodeAny(body) }.getOrNull()
        Logger.d("selectedMediaArray: $root")
        val entries = (root as? List<*>) ?: ((root as? Map<*, *>)?.values?.firstOrNull() as? List<*>) ?: return
        var enabled: Boolean? = null
        for (entry in entries) {
            val map = entry as? Map<*, *> ?: continue
            val looksLikeSubtitles = map.entries.any { (k, v) ->
                listOf(k?.toString(), v?.toString()).any { s ->
                    s?.contains("subt", ignoreCase = true) == true || s?.contains("legible", ignoreCase = true) == true
                }
            }
            if (!looksLikeSubtitles) continue
            val flag = map.entries.firstOrNull { (k, _) -> k?.toString()?.contains("enabled", ignoreCase = true) == true }
                ?.value as? Boolean
            if (flag != null) enabled = flag
        }
        if (enabled != null) {
            Logger.i("selectedMediaArray: subtitles ${if (enabled) "enabled" else "disabled"} by sender")
            onVideoSubtitlesEnabled(enabled)
        }
    }

    /** POST /reverse — upgrade to PTTH/1.0 (RFC-draft "Reverse HTTP"): reply 101, then the socket is reversed. */
    private fun handleReverse(request: RtspRequest): RtspResponse {
        Logger.i("Reverse channel requested: Upgrade=${request.headers["Upgrade"]} purpose=${request.headers["X-Apple-Purpose"]}")
        if (reverseChannel != null) {
            // Replace a stale sender socket during Safari reload/reconnect.
            // Its finally block will see that it is no longer current and must
            // not tear down the replacement session.
            Logger.i("Replacing existing reverse channel")
            reverseChannel?.close()
            reverseChannel = null
        }
        return RtspResponse(
            101, "Switching Protocols",
            headers = mapOf("Connection" to "Upgrade", "Upgrade" to "PTTH/1.0"),
            protocol = "HTTP/1.1"
        )
    }


    /** POST /action — binary plist from the sender: FCUP answers (`unhandledURLResponse`), playlist add/remove. */
    private fun handleAction(request: RtspRequest): RtspResponse {
        val bad = RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        val root = runCatching { PlistCodec.decode(request.bodyBytes) }.getOrNull() ?: return bad
        val type = root["type"] as? String ?: return bad
        @Suppress("UNCHECKED_CAST")
        val params = root["params"] as? Map<String, Any?> ?: return bad
        when (type) {
            "unhandledURLResponse" -> {
                val url = params["FCUP_Response_URL"] as? String ?: return bad
                val data = params["FCUP_Response_Data"] as? ByteArray ?: return bad
                Logger.d("FCUP response for $url (${data.size}B, status=${params["FCUP_Response_StatusCode"]})")
                hlsRelay.onFcupResponse(url, data)
            }
            "playlistRemove" -> {
                Logger.i("action playlistRemove — waiting for replacement video")
                hlsRelay.stop()
                onVideoPlaylistRemove()
            }
            else -> Logger.i("action type=$type (unhandled)")
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    private fun routeRequest(request: RtspRequest): RtspResponse {
        val isMultiRoom = videoTransport == AirPlayVideoTransport.MULTIROOM
        if (isMultiRoom) {
            Logger.d("MR: RTSP ${request.method} ${request.uri}")
            // Log headers for MULTIROOM
            val headerLines = request.headers.entries.map { (k, v) -> "$k: $v" }
            if (headerLines.isNotEmpty()) Logger.d("MR:  Headers: ${headerLines.joinToString(", ")}")
            // Log body if present
            if (request.bodyBytes.isNotEmpty()) {
                Logger.d("MR:  Body: ${request.bodyBytes.size}B ${mrBodyPreview(request.bodyBytes)}")
            }
        } else {
            // Flick AirPlay: control requests at info level, to see when a sender pauses, skips or flushes
            if (request.method != "GET_PARAMETER" && request.method != "OPTIONS") Logger.i("RTSP ${request.method} ${request.uri}")
            else Logger.d("RTSP ${request.method} ${request.uri}")
        }
        // Senders attach their DACP reverse-control identity to most requests — capture it so the TV
        // remote can drive playback (DacpClient dedups, so this is cheap to call repeatedly).
        request.headers["Active-Remote"]?.let { onRemoteControlInfo(request.headers["DACP-ID"], it) }
        return when (request.method) {
            "OPTIONS"       -> handleOptionsInternal(request)
            "ANNOUNCE"      -> handleAnnounceInternal(request)
            // AirPlay 2 mirroring SETUP carries a binary plist; legacy audio SETUP carries SDP-ish text.
            "SETUP"         -> if (request.isPlistBody()) handleMirrorSetup(request) else handleSetupInternal(request)
            "RECORD"        -> handleRecordInternal(request).also { if (videoTransport != AirPlayVideoTransport.MULTIROOM) onPlaybackRate(1f) }
            "TEARDOWN"      -> handleTeardownInternal(request)
            "GET_PARAMETER" -> handleGetParameter(request)
            "SET_PARAMETER" -> handleSetParameter(request)
            "FLUSH"         -> handleFlush(request)
            "PAUSE"         -> handlePauseInternal(request)
            // AirPlay 2 buffered-audio control verbs. Acknowledge them (a 501 would abort audio-only
            // playback) and log their bodies so the anchor/rate/peer formats can be implemented.
            "SETRATEANCHORTIME", "SETRATEANCHORTIM" -> handleBufferedControl(request, "SETRATEANCHORTIME")
            "SETPEERS", "SETPEERSX"                 -> handleBufferedControl(request, "SETPEERS")
            "FLUSHBUFFERED"                         -> handleBufferedControl(request, "FLUSHBUFFERED")
            "PUT"           -> if (request.uri.substringBefore("?") == "/setProperty") handleSetProperty(request) else handlePhotoPutInternal(request)
            "DELETE"        -> handlePhotoDeleteInternal(request)
            // AirPlay 2 handshake is HTTP-style (GET/POST with bodies) over the RTSP socket.
            "GET"           -> routeGet(request)
            "POST"          -> routePost(request)
            else            -> handleUnknownInternal(request)
        }
    }

    /** Routes AirPlay 2 GET requests by URI path. */
    private fun routeGet(request: RtspRequest): RtspResponse = when (request.uri.substringBefore("?")) {
        "/info"          -> handleInfo(request)
        "/playback-info" -> handlePlaybackInfo(request)
        "/scrub"         -> handleScrubGet(request)
        "/server-info"   -> handleServerInfo(request)
        else             -> handleUnknownInternal(request)
    }

    /** Routes AirPlay 2 POST requests by URI path. */
    private fun routePost(request: RtspRequest): RtspResponse = when (request.uri.substringBefore("?")) {
        "/pair-setup"  -> handlePairSetup(request)
        "/pair-setup-pin" -> handleLegacyPairSetupPin(request)   // legacy AirPlay PIN SRP (plist)
        "/pair-pin-start" -> handlePairPinStart(request)
        "/pair-verify" -> handlePairVerify(request)
        "/fp-setup"    -> handleFpSetup(request)
        "/fp-setup2"   -> handleFpSetup2(request)
        "/feedback"    -> handleFeedback(request)
        "/audioMode"   -> RtspResponse(200, "OK", protocol = request.responseProtocol())
        // Sender MediaRemote capability update (multi-room); informational, just acknowledge.
        "/command"     -> RtspResponse(200, "OK", protocol = request.responseProtocol())
        // AirPlay video URL mode (non-mirroring): play a URL + drive transport.
        "/action"      -> handleAction(request)
        "/play"        -> handleVideoPlay(request)
        "/rate"        -> handleVideoRate(request)
        "/scrub"       -> handleVideoScrubPost(request)
        "/stop"        -> handleVideoStop(request)
        else           -> handleUnknownInternal(request)
    }

    // ─── AirPlay video URL mode (POST /play, /rate, /scrub, /stop; GET /playback-info, /scrub) ──

    /** POST /play — a media URL to play (binary/XML plist or legacy text body). */
    private fun handleVideoPlay(request: RtspRequest): RtspResponse {
        val play = parsePlayBody(request)
        val url = play.url
        if (url.isNullOrBlank()) {
            Logger.w("POST /play with no Content-Location")
            return RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
        Logger.i("POST /play url=$url start=${play.startFraction} / ${play.startSeconds}s")
        when {
            // Direct media (Safari's AirPlay button, older senders): the player fetches it itself.
            url.startsWith("http://") || url.startsWith("https://") -> {
                hlsRelay.stop()
                onVideoPlay(url, play.startFraction, play.startSeconds)
            }
            // HLS relay (YouTube app etc.): playlists come through the sender via FCUP on the reverse channel.
            url.contains("/master.m3u8") -> {
                if (!hlsRelay.start(url, play.startSeconds)) {
                    return RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
                }
            }
            else -> {
                Logger.w("POST /play: unsupported Content-Location form: $url")
                return RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
            }
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    private class PlayRequest(val url: String?, val startFraction: Double, val startSeconds: Double)

    /** Extracts the media URL + start position from a /play body (plist `Content-Location` or text). */
    private fun parsePlayBody(request: RtspRequest): PlayRequest {
        if (request.isPlistBody()) {
            val p = runCatching { PlistCodec.decode(request.bodyBytes) }.getOrNull() ?: return PlayRequest(null, 0.0, 0.0)
            return PlayRequest(
                url = p["Content-Location"] as? String,
                startFraction = (p["Start-Position"] as? Number)?.toDouble() ?: 0.0,
                startSeconds = (p["Start-Position-Seconds"] as? Number)?.toDouble() ?: 0.0,
            )
        }
        // Legacy text body: "Content-Location: <url>\r\nStart-Position: <float>\r\n"
        var url: String? = null
        var start = 0.0
        request.body.lineSequence().forEach { line ->
            when {
                line.startsWith("Content-Location:", true) -> url = line.substringAfter(":").trim()
                line.startsWith("Start-Position:", true) -> start = line.substringAfter(":").trim().toDoubleOrNull() ?: 0.0
            }
        }
        return PlayRequest(url, start, 0.0)
    }

    /** POST /rate?value=X — X=0 pause, X≥1 resume. */
    private fun handleVideoRate(request: RtspRequest): RtspResponse {
        val rate = queryParam(request.uri, "value")?.toFloatOrNull() ?: 1f
        Logger.d("POST /rate value=$rate")
        onVideoRate(rate)
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** POST /scrub?position=N — seek to N seconds. */
    private fun handleVideoScrubPost(request: RtspRequest): RtspResponse {
        queryParam(request.uri, "position")?.toDoubleOrNull()?.let {
            Logger.d("POST /scrub position=$it")
            onVideoScrub(it)
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** GET /scrub — current position + duration as text/parameters. */
    private fun handleScrubGet(request: RtspRequest): RtspResponse {
        val info = onPlaybackInfo()
        val body = "duration: %.6f\r\nposition: %.6f\r\n".format(info?.durationSec ?: 0.0, info?.positionSec ?: 0.0)
        return RtspResponse(200, "OK", body = body, contentType = "text/parameters", protocol = request.responseProtocol())
    }

    /** POST /stop — stop URL playback. */
    private fun handleVideoStop(request: RtspRequest): RtspResponse {
        // Safari can send the old session's /stop while a page reload has already
        // started a replacement /play.  Releasing the player synchronously here
        // races that new play and makes the sender fall back to local playback.
        // Route it through the same delayed lifecycle as playlistRemove instead.
        Logger.i("POST /stop (video URL) — waiting for replacement video")
        onVideoPlaylistRemove()
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** GET /playback-info — XML plist describing current position/duration/rate/ready state. */
    private fun handlePlaybackInfo(request: RtspRequest): RtspResponse {
        val info = onPlaybackInfo()
        val plist: Map<String, Any?> = if (info == null || !info.readyToPlay) {
            mapOf("readyToPlay" to false)
        } else {
            // Legacy AirPlay receivers report the logical playable VOD range, rather than
            // ExoPlayer's short rolling HLS buffer. Safari uses this range for remote playback
            // lifetime and seek controls.
            val maxLoadedDuration = (info.durationSec - info.positionSec).coerceAtLeast(0.0)
            val loadedRanges = listOf(
                mapOf("start" to info.positionSec, "duration" to maxLoadedDuration)
            )
            val seekableRanges = listOf(mapOf("start" to 0.0, "duration" to info.durationSec))
            mapOf(
                "duration" to info.durationSec,
                "position" to info.positionSec,
                "rate" to info.rate,
                "readyToPlay" to true,
                // These flags describe the logical VOD state, not the few HLS segments currently
                // resident in ExoPlayer. Reporting a transient empty buffer makes Safari abort
                // the item as "Media failed" and removes its controls while playback is healthy.
                "playbackBufferEmpty" to false,
                "playbackBufferFull" to false,
                "playbackLikelyToKeepUp" to true,
                "loadedTimeRanges" to loadedRanges,
                "seekableTimeRanges" to seekableRanges,
            )
        }
        return RtspResponse(
            200, "OK",
            bodyBytes = PlistCodec.encodeXml(plist),
            contentType = "text/x-apple-plist+xml",
            protocol = request.responseProtocol()
        )
    }

    /** GET /server-info — legacy XML plist of receiver identity for AirPlay video senders. */
    private fun handleServerInfo(request: RtspRequest): RtspResponse {
        val modern = videoTransport.isModern
        val info = mapOf(
            "deviceid" to com.phairplay.util.NetworkUtils.getMacAddress(),
            "features" to if (modern) videoTransport.features else 0x27FL,
            "model" to videoTransport.model,
            "osBuildVersion" to "12B435",
            "protovers" to videoTransport.protocolVersion,
            "srcvers" to videoTransport.sourceVersion,
            "vv" to 2L,
        )
        return RtspResponse(
            200, "OK",
            bodyBytes = PlistCodec.encodeXml(info),
            contentType = "text/x-apple-plist+xml",
            protocol = request.responseProtocol()
        )
    }

    /** Extracts a query-string parameter (`?k=v&...`) from a request URI. */
    private fun queryParam(uri: String, key: String): String? =
        uri.substringAfter('?', "").split('&')
            .firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")

    /** POST /feedback — macOS health-checks the session every ~2 s; acknowledge with 200 OK. */
    private fun handleFeedback(request: RtspRequest): RtspResponse {
        val n = request.bodyBytes.size
        if (n > 0) {
            runCatching {
                val p = PlistCodec.decode(request.bodyBytes)
                Logger.d("/feedback body ($n B): " + p.entries.joinToString { (k, v) ->
                    "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                })
            }.onFailure { Logger.d("/feedback body ($n B, non-plist)") }
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /**
     * Acknowledges an AirPlay 2 buffered-audio control verb (SETRATEANCHORTIME / SETPEERS /
     * FLUSHBUFFERED). Returning 200 keeps an audio-only session alive (a 501 would make macOS abort).
     */
    /** Diagnostics: where the sender's anchor lies in local time according to the NTP clock (tag `MR: NTP`). */
    private fun logNtpAnchor(p: Map<String, Any?>) {
        val clock = MultiroomClockSource.clock as? NtpClockAdapter ?: return
        val secs = (p["networkTimeSecs"] as? Number)?.toLong() ?: return
        val frac = (p["networkTimeFrac"] as? Number)?.toLong() ?: 0L
        val master = NtpClockAdapter.anchorMasterNanos(secs, frac)
        val local = clock.localNanosForMaster(master)
        Logger.i("MR: NTP anchor secs=$secs frac=$frac timeline=${p["networkTimeTimelineID"]} synced=${clock.synced} " +
            "anchorInLocalMs=${local?.let { (it - System.nanoTime()) / 1_000_000 }}")
    }

    private fun handleBufferedControl(request: RtspRequest, label: String): RtspResponse {
        val isMultiRoom = videoTransport == AirPlayVideoTransport.MULTIROOM
        if (isMultiRoom) {
            Logger.d("MR: $label (MULTIROOM stub response)")
        }
        val n = request.bodyBytes.size
        if (n == 0 && label == "FLUSHBUFFERED") multiroomLocal.get()?.flush(null, null)
        if (n > 0) {
            runCatching {
                val p = PlistCodec.decode(request.bodyBytes)
                if (label == "SETRATEANCHORTIME") (p["rate"] as? Number)?.let { onPlaybackRate(it.toFloat()) }
                if (isMultiRoom && label == "SETRATEANCHORTIME") {
                    val rate = (p["rate"] as? Number)?.toFloat() ?: 0f
                    val rtp = (p["rtpTime"] as? Number)?.toLong()
                    val secs = (p["networkTimeSecs"] as? Number)?.toLong()
                    if (rtp != null && secs != null) MultiroomPtp.setAnchor(rate, rtp, secs, (p["networkTimeFrac"] as? Number)?.toLong() ?: 0L,
                        (p["networkTimeTimelineID"] as? Number)?.toLong() ?: 0L)
                }
                multiroomLocal.get()?.let { mr ->
                    when (label) {
                        "SETRATEANCHORTIME" -> mr.setRate((p["rate"] as? Number)?.toFloat() ?: 0f, (p["rtpTime"] as? Number)?.toLong())
                        "FLUSHBUFFERED" -> mr.flush((p["flushFromSeq"] as? Number)?.toLong(), (p["flushUntilSeq"] as? Number)?.toLong())
                    }
                }
                if (isMultiRoom && label == "SETRATEANCHORTIME") logNtpAnchor(p)
                if (isMultiRoom) {
                    Logger.d("MR: $label body ($n B): " + p.entries.joinToString { (k, v) ->
                        "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                    })
                } else {
                    Logger.d("$label body ($n B): " + p.entries.joinToString { (k, v) ->
                        "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                    })
                }
            }.onFailure {
                if (isMultiRoom) Logger.d("MR: $label body ($n B, non-plist)")
                else Logger.d("$label body ($n B, non-plist)")
            }
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** GET /info — advertises receiver identity + capabilities (binary plist). */
    private fun handleInfo(request: RtspRequest): RtspResponse {
        val isMultiRoom = videoTransport == AirPlayVideoTransport.MULTIROOM
        if (isMultiRoom) {
            Logger.d("GET /info (MULTIROOM): building multi-room capability response")
        }
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            bodyBytes = InfoResponder.build(
                context,
                MirrorQuality.width,
                MirrorQuality.height,
                pinRequired = pinAuthEnabled,
                model = if (videoTransport.isModern) videoTransport.model else AirPlayFeatures.MODEL,
                sourceVersion = if (videoTransport.isModern) videoTransport.sourceVersion else "220.68",
                protocolVersion = if (videoTransport.isModern) videoTransport.protocolVersion else "1.1",
                features = videoTransport.features,
                isMultiRoom = isMultiRoom,
                ptpInfo = isMultiRoom && ptpAdvertisesInfo(ptpWireVariant()).also { if (it) Logger.i("MR: PTP wire variant=${ptpWireVariant()} /info PTPInfo added") },
            ),
            contentType = "application/x-apple-binary-plist",
            protocol = request.responseProtocol()
        )
    }

    /**
     * POST /pair-setup. With PIN auth off (default) this is the anonymous Ed25519 exchange. With PIN
     * auth on, it runs the HomeKit-style SRP pair-setup (TLV8) — showing a PIN on the TV that the
     * user types on the Mac — so only someone with screen access can pair.
     */
    private fun handlePairSetup(request: RtspRequest): RtspResponse {
        val mrTlvSetup = videoTransport == AirPlayVideoTransport.MULTIROOM && request.bodyBytes.size != 32
        if (mrTlvSetup) {
            val fields = runCatching { com.phairplay.airplay.handshake.HapTlv.decode(request.bodyBytes) }.getOrNull()
            val transient = fields?.get(com.phairplay.airplay.handshake.HapTlv.FLAGS)?.let { it.isNotEmpty() && (it[0].toInt() and 0x10) != 0 } == true
            if (fields != null && !transient) return handleHapPinSetup(request, fields)
        }
        if (mrTlvSetup) Logger.i("MR: pair-setup via HAP transient path (HKP=${request.headers["X-Apple-HKP"]}, ${request.bodyBytes.size}B)")
        if (request.headers["X-Apple-HKP"] == "4" || mrTlvSetup) {
            if (pinAuthEnabled) return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
            return try {
                val hap = hapTransientLocal.get() ?: com.phairplay.airplay.handshake.HapTransientPairing().also {
                    hapTransientLocal.set(it); hapPairingLocal.set(it)
                }
                RtspResponse(200, "OK", bodyBytes = hap.handle(request.bodyBytes), contentType = OCTET_STREAM,
                    protocol = request.responseProtocol())
            } catch (e: Exception) {
                Logger.w("HAP transient pairing rejected")
                RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
            }
        }
        // /pair-setup is the anonymous key exchange; PIN access control runs on /pair-setup-pin.
        return try {
            val body = pairingSession!!.pairSetup(request.bodyBytes)
            Logger.i("pair-setup OK (returned ${body.size}-byte public key)")
            RtspResponse(200, "OK", bodyBytes = body, contentType = OCTET_STREAM, protocol = request.responseProtocol())
        } catch (e: Exception) {
            Logger.e("pair-setup failed", e)
            RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
    }

    private fun accessoryPairingId(): String = com.phairplay.util.NetworkUtils.getMacAddress().uppercase()

    private fun newMrPin(): String {
        val pin = "%04d".format(java.security.SecureRandom().nextInt(10_000))
        mrPin = pin
        onShowPin(pin)
        Logger.d("MR: PIN=$pin")
        // Hide a stale code if the user never enters it.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (mrPin == pin) { mrPin = null; onShowPin(null) }
        }, 180_000)
        return pin
    }

    private fun hapReply(request: RtspRequest, body: ByteArray) = RtspResponse(
        200, "OK", bodyBytes = body, contentType = OCTET_STREAM, protocol = request.responseProtocol()
    )

    /** POST /pair-setup (HomeKit TLV8, user PIN): M1→M6, stores the controller's long-term key. */
    private fun handleHapPinSetup(request: RtspRequest, fields: Map<Int, ByteArray>): RtspResponse {
        val state = com.phairplay.airplay.handshake.HapTlv.state(fields) ?: return RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        if (mrStore.failedAttempts() >= MAX_PAIR_ATTEMPTS) {
            Logger.w("MR: pair-setup blocked — locked after $MAX_PAIR_ATTEMPTS failed attempts")
            return hapReply(request, com.phairplay.airplay.handshake.HapTlv.encode(
                com.phairplay.airplay.handshake.HapTlv.STATE to byteArrayOf((state + 1).toByte()),
                com.phairplay.airplay.handshake.HapTlv.ERROR to byteArrayOf(5)))
        }
        var setup = hapPinSetupLocal.get()
        if (state == 1) {
            val pin = mrPin ?: newMrPin()
            setup = com.phairplay.airplay.handshake.HapPinSetup(pin, PairingKeys.get(context), accessoryPairingId(),
                { id, ltpk -> mrStore.add(id, ltpk); Logger.i("MR: controller paired: $id") })
            hapPinSetupLocal.set(setup)
        }
        if (setup == null) return hapReply(request, com.phairplay.airplay.handshake.HapTlv.error(state + 1))
        return try {
            val out = setup.handle(request.bodyBytes)
            Logger.d("MR: pair-setup M$state -> M${state + 1}")
            if (setup.finished) {
                mrStore.resetFailedAttempts(); mrPin = null; onShowPin(null); hapPinSetupLocal.remove()
                Logger.i("MR: PIN pair-setup complete")
            }
            hapReply(request, out)
        } catch (e: com.phairplay.airplay.handshake.HapAuthException) {
            val n = mrStore.recordFailedAttempt()
            Logger.w("MR: pair-setup M$state failed: ${e.message} ($n/$MAX_PAIR_ATTEMPTS)")
            hapPinSetupLocal.remove()
            hapReply(request, com.phairplay.airplay.handshake.HapTlv.error(state + 1))
        } catch (e: Exception) {
            Logger.w("MR: pair-setup M$state error: ${e.message}")
            hapPinSetupLocal.remove()
            hapReply(request, com.phairplay.airplay.handshake.HapTlv.error(state + 1))
        }
    }

    /** POST /pair-verify (HomeKit TLV8): M1→M4; on success the control channel switches to HAP encryption. */
    private fun handleHapVerify(request: RtspRequest): RtspResponse {
        val fields = runCatching { com.phairplay.airplay.handshake.HapTlv.decode(request.bodyBytes) }.getOrNull()
        val state = fields?.let { com.phairplay.airplay.handshake.HapTlv.state(it) }
            ?: return hapReply(request, com.phairplay.airplay.handshake.HapTlv.error(2))
        var verify = hapVerifyLocal.get()
        if (state == 1 || verify == null) {
            verify = com.phairplay.airplay.handshake.HapPairVerify(PairingKeys.get(context), accessoryPairingId(), { mrStore.get(it) })
            hapVerifyLocal.set(verify)
        }
        return try {
            val out = verify.handle(request.bodyBytes)
            if (verify.complete) {
                hapPairingLocal.set(verify)
                Logger.i("MR: pair-verify complete for ${verify.controllerId}; encrypting control channel")
            }
            hapReply(request, out)
        } catch (e: Exception) {
            Logger.w("MR: pair-verify M$state rejected: ${e.message}")
            hapVerifyLocal.remove()
            hapReply(request, com.phairplay.airplay.handshake.HapTlv.error(state + 1))
        }
    }

    /**
     * POST /pair-verify — the anonymous ECDH handshake. AirPlay uses this same raw exchange even with
     * PIN access control on (the PIN is a SEPARATE /pair-pin-start + /pair-setup-pin layer), so this
     * is never the HomeKit TLV8 variant.
     */
    private fun handlePairVerify(request: RtspRequest): RtspResponse {
        // PIN access control: refuse pair-verify until the controller has PIN-paired this connection.
        // macOS responds to the rejection by starting the PIN flow (/pair-pin-start → /pair-setup-pin).
        if (pinAuthEnabled && !pinPaired) {
            Logger.i("pair-verify rejected — PIN pairing required first (triggers /pair-pin-start)")
            return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        }
        val tlv = request.bodyBytes
        // HAP TLV8 may carry fields in any order (iPhone puts EncryptedData before State in M3), so look
        // for the State tag instead of assuming it comes first.
        if (videoTransport == AirPlayVideoTransport.MULTIROOM && tlv.size >= 3 &&
            runCatching { com.phairplay.airplay.handshake.HapTlv.decode(tlv).containsKey(com.phairplay.airplay.handshake.HapTlv.STATE) }.getOrDefault(false)) {
            return handleHapVerify(request)
        }
        return try {
            val body = pairingSession!!.pairVerify(request.bodyBytes)
            Logger.i("pair-verify ${if (request.bodyBytes.firstOrNull()?.toInt() == 1) "M1" else "M2"} OK (returned ${body.size} bytes)")
            RtspResponse(200, "OK", bodyBytes = body, contentType = OCTET_STREAM, protocol = request.responseProtocol())
        } catch (e: Exception) {
            Logger.e("pair-verify failed", e)
            RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        }
    }

    /**
     * POST /pair-pin-start — macOS asks the receiver to begin PIN pairing and display the code. We
     * generate + show the PIN and prime the SRP session, then reply 200; the SRP exchange follows on
     * /pair-setup-pin. (This precedes pair-setup in the AirPlay PIN flow — see the logs.)
     */
    private fun handlePairPinStart(request: RtspRequest): RtspResponse {
        if (!pinAuthEnabled && videoTransport == AirPlayVideoTransport.MULTIROOM && request.headers["X-Apple-HKP"] != "4") {
            newMrPin()
            Logger.i("MR: pair-pin-start accepted (HKP=${request.headers["X-Apple-HKP"]}) — PIN shown")
            return RtspResponse(200, "OK", protocol = request.responseProtocol())
        }
        if (!pinAuthEnabled && request.headers["X-Apple-HKP"] == "4") {
            return RtspResponse(200, "OK", protocol = request.responseProtocol())
        }
        if (!pinAuthEnabled) return handleUnknownInternal(request)
        if ((pairingStore?.failedAttempts() ?: 0) >= MAX_PAIR_ATTEMPTS) {
            Logger.w("pair-pin-start blocked — PIN auth locked ($MAX_PAIR_ATTEMPTS failed attempts)")
            return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        }
        newSrpSession()
        Logger.i("pair-pin-start — PIN shown, SRP session primed")
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** Generates a fresh 4-digit PIN, shows it on the TV, and primes the legacy SRP session. */
    private fun newSrpSession() {
        val pin = "%0${PIN_DIGITS}d".format(java.security.SecureRandom().nextInt(PIN_SPACE))
        onShowPin(pin)
        legacyPin = com.phairplay.airplay.handshake.LegacyPairSetupPin(pin, PairingKeys.get(context).edPublic)
    }

    /**
     * POST /pair-setup-pin — the legacy AirPlay plist SRP exchange. Step 1 ({method,user}) returns
     * {pk,salt}; step 2 ({pk,proof}) verifies the PIN and returns {proof}. On success the controller
     * is allowed past pair-verify (→ streaming). Bounded by the failed-attempt lockout.
     */
    private fun handleLegacyPairSetupPin(request: RtspRequest): RtspResponse {
        if (!pinAuthEnabled) return handleUnknownInternal(request)
        if ((pairingStore?.failedAttempts() ?: 0) >= MAX_PAIR_ATTEMPTS) {
            Logger.w("pair-setup-pin blocked — PIN auth locked ($MAX_PAIR_ATTEMPTS failed attempts)")
            onShowPin(null)
            return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        }
        return try {
            val plist = PlistCodec.decode(request.bodyBytes)
            if (legacyPin == null) newSrpSession()   // step 1 may arrive without a prior /pair-pin-start
            val result = legacyPin!!.handle(plist)
            if (result.failed) {
                val n = pairingStore?.recordFailedAttempt() ?: 0
                Logger.w("pair-setup-pin attempt failed ($n/$MAX_PAIR_ATTEMPTS)")
                onShowPin(null); legacyPin = null
                return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
            }
            if (result.complete) {
                pairingStore?.resetFailedAttempts()   // legitimate pairing clears the lockout counter
                pinPaired = true                       // now allow pair-verify → streaming proceeds
                onShowPin(null); legacyPin = null
                Logger.i("PIN pairing complete — pair-verify now permitted")
            }
            RtspResponse(
                200, "OK",
                bodyBytes = PlistCodec.encode(result.reply!!),
                contentType = "application/x-apple-binary-plist",
                protocol = request.responseProtocol()
            )
        } catch (e: Exception) {
            Logger.e("pair-setup-pin failed", e)
            onShowPin(null); legacyPin = null
            RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
    }

    /** POST /fp-setup — FairPlay: 16-byte phase 1 → 142-byte reply; 164-byte phase 2 → 32-byte reply. */
    private fun handleFpSetup(request: RtspRequest): RtspResponse = try {
        val fp = fairPlay!!
        val b = request.bodyBytes
        // Diagnostics: byte 4 is the FairPlay version (0x03 mirroring/Safari, 0x02 Apple Music audio);
        // for phase 1, byte 14 is the mode (0..3). Confirms which path a given sender uses.
        val verMode = if (b.size >= 16) " v=0x%02x mode=%d".format(b[4].toInt() and 0xFF, b[14].toInt() and 0xFF)
                      else if (b.size >= 5) " v=0x%02x".format(b[4].toInt() and 0xFF) else ""
        val body = when (b.size) {
            16 -> fp.setup(b)
            164 -> fp.handshake(b)
            else -> throw IllegalArgumentException("unexpected fp-setup size ${b.size}")
        }
        Logger.i("fp-setup phase (${b.size}B in → ${body.size}B out)$verMode OK")
        RtspResponse(200, "OK", bodyBytes = body, contentType = OCTET_STREAM, protocol = request.responseProtocol())
    } catch (e: Exception) {
        Logger.e("fp-setup failed", e)
        RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
    }

    /**
     * AirPlay 2 mirroring SETUP (binary plist). Two messages arrive on one connection:
     *  - msg 1 carries `ekey`+`eiv`+`timingPort` → FairPlay-decrypt the AES key, hand it
     *    (with the pairing secret) to the receiver, reply with event/timing ports.
     *  - msg 2 carries `streams`[type 110] → start the mirror data server, reply with its port.
     */
    private fun handleMirrorSetup(request: RtspRequest): RtspResponse { return try {
        val req = PlistCodec.decode(request.bodyBytes)
        if (videoTransport == AirPlayVideoTransport.MULTIROOM && hapPairingLocal.get()?.complete == true && req["ekey"] == null) {
            handleMultiroomSetup(request, req)?.let { return it }
        }
        if (hapPairingLocal.get()?.complete == true && req["ekey"] == null) {
            return handleHapUrlSetup(request, req)
        }
        Logger.i("mirror SETUP plist: " + req.entries.joinToString { (k, v) ->
            "$k=" + when (v) {
                is ByteArray -> "${v.size}B"
                is List<*> -> "list[${v.size}]"
                else -> v.toString()
            }
        })
        val response = mutableMapOf<String, Any?>()

        isMirrorSession = true
        val ekey = req["ekey"] as? ByteArray
        if (ekey != null) {
            val aesKey = fairPlay!!.decrypt(ekey)
            // With the legacy feature set (no AP2 pairing) the sender skips pair-verify: no ECDH secret, and the
            // FairPlay-decrypted key is used as is (see MirrorCrypto).
            val ecdhSecret = pairingSession?.sharedSecret ?: ByteArray(0)
            val aesIv = (req["eiv"] as? ByteArray) ?: ByteArray(16)
            val senderTimingPort = (req["timingPort"] as? Long)?.toInt() ?: 0
            val remoteAddr = currentRemoteAddress ?: error("mirror SETUP without remote address")
            val (eventPort, timingPort) = onMirrorSetupKeys(aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort)
            response["eventPort"] = eventPort.toLong()
            response["timingPort"] = timingPort.toLong()
            Logger.i("mirror SETUP keys OK — eventPort=$eventPort timingPort=$timingPort (sender timing $senderTimingPort)")
        }

        val streams = req["streams"] as? List<*>
        if (streams != null) {
            val resStreams = streams.mapNotNull { s ->
                val stream = s as? Map<*, *> ?: return@mapNotNull null
                when ((stream["type"] as? Long)?.toInt()) {
                    110 -> {
                        val scid = (stream["streamConnectionID"] as? Long) ?: 0L
                        val dataPort = onMirrorStreamStart(scid)
                        activeStreamTypes.add(110)
                        Logger.i("mirror stream type=110 streamConnectionID=$scid dataPort=$dataPort")
                        mapOf("type" to 110L, "dataPort" to dataPort.toLong())
                    }
                    96 -> {
                        // Realtime-audio stream fields (codec type ct, samples-per-frame spf, latencies, …).
                        Logger.d("mirror stream type=96 dict: " + stream.entries.joinToString { (k, v) ->
                            "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                        })
                        if (!audioEnabled) {
                            Logger.i("mirror stream type=96 ignored (audio disabled in settings)")
                            return@mapNotNull null
                        }
                        val sr = (stream["sr"] as? Long)?.toInt() ?: 44100
                        val ch = (stream["channels"] as? Long)?.toInt() ?: 2
                        val ct = (stream["ct"] as? Long)?.toInt() ?: 8   // 8 = AAC-ELD (mirror), 4 = AAC-LC, 2 = ALAC
                        val spf = (stream["spf"] as? Long)?.toInt() ?: 352   // ALAC frameLength (samples/frame)
                        val (dataPort, controlPort) = onMirrorAudioStart(sr, ch, ct, spf)
                        activeStreamTypes.add(96)
                        audioStreamStartedAt[96] = System.nanoTime()
                        Logger.i("audio stream type=96 (ct=$ct ${sr}Hz x$ch spf=$spf) dataPort=$dataPort controlPort=$controlPort")
                        mapOf("type" to 96L, "dataPort" to dataPort.toLong(), "controlPort" to controlPort.toLong())
                    }
                    103 -> {
                        // Buffered (audio-only) AirPlay 2 — accepted + instrumented, but the macOS
                        // Music stream stays FairPlay-encrypted (undecryptable), so playback is not
                        // wired. Stream fields (codec ct, audioFormat, shk/shiv, latencies) logged for ref.
                        Logger.d("buffered audio stream type=103 dict: " + stream.entries.joinToString { (k, v) ->
                            "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                        })
                        if (!audioEnabled) {
                            Logger.i("buffered audio (type=103) ignored (audio disabled in settings)")
                            return@mapNotNull null
                        }
                        val dataPort = onBufferedAudioStart()
                        activeStreamTypes.add(103)
                        audioStreamStartedAt[103] = System.nanoTime()
                        Logger.i("buffered audio stream type=103 dataPort=$dataPort")
                        mapOf("type" to 103L, "dataPort" to dataPort.toLong())
                    }
                    else -> {
                        Logger.i("mirror SETUP stream dict: " + stream.entries.joinToString { (k, v) ->
                            "$k=" + when (v) { is ByteArray -> "${v.size}B"; else -> v.toString() }
                        })
                        null
                    }
                }
            }
            response["streams"] = resStreams
        }

        RtspResponse(
            200, "OK",
            bodyBytes = PlistCodec.encode(response),
            contentType = "application/x-apple-binary-plist",
            protocol = request.responseProtocol()
        )
    } catch (e: Exception) {
        Logger.e("mirror SETUP failed", e)
        RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
    }
    }

    /** AirPlay 2 multi-room SETUP (PTP control phase and buffered-audio stream); null = not a multi-room request. */
    private fun handleMultiroomSetup(request: RtspRequest, req: Map<String, Any?>): RtspResponse? {
        val streams = req["streams"] as? List<*>
        val isPtp = req["timingProtocol"] == "PTP" || req["timingProtocol"] == "NTP"
        if (!isPtp && streams == null) return null
        val session = multiroomLocal.get() ?: MultiroomSession(
            requireNotNull(remoteAddressLocal.get()), requireNotNull(hapPairingLocal.get()),
            multiroomDelayMs, onMultiroomSampleRate
        ).also { multiroomLocal.set(it); multiroomSessions.add(it); it.start() }
        val response = mutableMapOf<String, Any?>()
        if (isPtp) response.putAll(session.controlSetupResponse(req))
        if (streams != null) {
            response["streams"] = streams.mapNotNull { st ->
                val stream = st as? Map<*, *> ?: return@mapNotNull null
                when ((stream["type"] as? Number)?.toInt()) {
                    103 -> try {
                        activeStreamTypes.add(103)
                        audioStreamStartedAt[103] = System.nanoTime()
                        session.setupAudioStream(stream).also { onMultiroomAudioStart() }
                    } catch (e: Exception) { Logger.e("MR: audio stream SETUP failed", e); null }
                    96 -> try {
                        activeStreamTypes.add(96)
                        audioStreamStartedAt[96] = System.nanoTime()
                        session.setupRealtimeStream(stream).also { onMultiroomAudioStart() }
                    } catch (e: Exception) { Logger.e("MR: realtime audio stream SETUP failed", e); activeStreamTypes.remove(96); null }
                    else -> { Logger.w("MR: unsupported stream in multi-room SETUP: ${stream.keys}"); null }
                }
            }
        }
        Logger.i("MR: multi-room SETUP handled (ptp=$isPtp streams=${streams?.size ?: 0}) -> ${response.keys}")
        return RtspResponse(200, "OK", bodyBytes = PlistCodec.encode(response),
            contentType = "application/x-apple-binary-plist", protocol = request.responseProtocol())
    }


    private fun closeMultiroomSession() {
        multiroomLocal.get()?.let { it.close(); multiroomSessions.remove(it) }
        multiroomLocal.remove()
    }

    private fun handleHapUrlSetup(request: RtspRequest, fields: Map<String, Any?>): RtspResponse {
        require(fields["streams"] == null) { "HAP media streams are not implemented" }
        require(fields["timingProtocol"] == "NTP") { "Only NTP URL sessions are supported" }
        val senderPort = (fields["timingPort"] as? Number)?.toInt() ?: error("Missing timingPort")
        require(senderPort in 1..65535)
        val existing = hapUrlSessionLocal.get()
        val session = existing ?: HapUrlSession(
            requireNotNull(remoteAddressLocal.get()), senderPort, requireNotNull(hapPairingLocal.get()),
            handleInfo(request).wireBody()
        ).also {
            hapUrlSessionLocal.set(it)
            hapUrlSessions.add(it)
            it.start()
        }
        Logger.i("HAP URL SETUP eventPort=${session.eventPort} timingPort=${session.localTimingPort}")
        return RtspResponse(200, "OK", bodyBytes = PlistCodec.encode(mapOf(
            "eventPort" to session.eventPort.toLong(), "timingPort" to session.localTimingPort.toLong()
        )), contentType = "application/x-apple-binary-plist", protocol = request.responseProtocol())
    }

    private fun closeHapUrlSession() {
        hapUrlSessionLocal.get()?.let { it.close(); hapUrlSessions.remove(it) }
        hapUrlSessionLocal.remove()
    }

    /** Handles OPTIONS — macOS asks what RTSP methods are supported. */
    open fun handleOptionsInternal(request: RtspRequest): RtspResponse {
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf(
                "Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
            )
        )
    }

    /** Handles ANNOUNCE — macOS/iOS sends SDP describing codecs, ports, and encryption. */
    open fun handleAnnounceInternal(request: RtspRequest): RtspResponse {
        Logger.d("ANNOUNCE body (${request.body.length} bytes)")
        val parsed = SdpParser.parse(request.body)

        if (parsed == null) {
            Logger.e("ANNOUNCE: SDP parsing returned no usable session — rejecting")
            return RtspResponse(statusCode = 400, statusMessage = "Bad Request")
        }

        currentSession = parsed.copy(senderName = extractSenderName(request.headers["User-Agent"]))
        val s = currentSession!!
        Logger.i("Session: hasVideo=${s.hasVideo} hasAudio=${s.hasAudio} " +
                 "codec=${s.audioCodec} encrypted=${s.isAudioEncrypted} sender='${s.senderName}'")

        setupCount = 0
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    private fun extractSenderName(userAgent: String?): String {
        if (userAgent.isNullOrBlank()) return DEFAULT_SENDER_NAME
        val name = userAgent.substringBefore("/").trim()
        return name.ifEmpty { DEFAULT_SENDER_NAME }
    }

    /** Handles SETUP — allocates a media channel. */
    open fun handleSetupInternal(request: RtspRequest): RtspResponse {
        setupCount++
        val session = currentSession

        val isVideoSetup = setupCount == 1 && session?.hasVideo == true

        val transport = if (isVideoSetup) {
            "RTP/AVP/TCP;unicast;interleaved=0-1"
        } else {
            "RTP/AVP/UDP;unicast;" +
            "client_port=$AUDIO_RTP_PORT-${AUDIO_RTP_PORT + 1};" +
            "server_port=$AUDIO_RTP_PORT-${AUDIO_RTP_PORT + 1};" +
            "timing-port=${TimingHandler.TIMING_PORT}"
        }

        Logger.d("SETUP #$setupCount — transport: $transport")
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf("Session" to SESSION_ID, "Transport" to transport)
        )
    }

    /** Handles RECORD — macOS/iOS says start sending media now. */
    open fun handleRecordInternal(request: RtspRequest): RtspResponse {
        if (hapUrlSessionLocal.get() != null) return RtspResponse(200, "OK", protocol = request.responseProtocol())
        // AirPlay 2 multi-room: RECORD follows the control SETUP and just acknowledges (stream SETUP / anchor come later).
        if (multiroomLocal.get() != null) {
            Logger.i("MR: RECORD (multi-room session) — OK")
            return RtspResponse(200, "OK", headers = mapOf("Audio-Latency" to "0"), protocol = request.responseProtocol())
        }
        // AirPlay 2 mirroring has no ANNOUNCE/SDP — RECORD just acknowledges the session.
        if (isMirrorSession) {
            Logger.i("RECORD (mirror session) — OK")
            return RtspResponse(
                statusCode = 200, statusMessage = "OK",
                headers = mapOf("Audio-Latency" to "0"),
                protocol = request.responseProtocol()
            )
        }
        var session = currentSession
        if (session == null) {
            Logger.e("RECORD received but no session from ANNOUNCE — rejecting")
            return RtspResponse(statusCode = 455, statusMessage = "Method Not Valid in This State")
        }
        // RAOP audio (Apple Music) wraps the AES key with FairPlay (SDP `fpaeskey`). Unwrap it via the
        // fp-setup session into the real 16-byte key so the AudioPlayer can AES-CBC-decrypt the stream.
        val fpKey = session.fpAesKey
        if (fpKey != null && session.aesKey == null) {
            val realKey = runCatching { fairPlay?.decrypt(fpKey) }
                .onFailure { Logger.w("RAOP FairPlay audio-key decrypt failed (${fpKey.size}B): ${it.message}") }
                .getOrNull()
            if (realKey != null) {
                Logger.i("RAOP FairPlay (v0x%02x) audio key decrypted → ${realKey.size}B AES key, iv=${session.aesIv?.size ?: 0}B"
                    .format(fairPlay?.negotiatedVersion ?: 0))
                session = session.copy(aesKey = realKey)
                currentSession = session
            }
        }
        Logger.i("RECORD — streaming starting (audioOnly=${session.isAudioOnly}, encrypted=${session.isAudioEncrypted})")
        onStreamingStarted(session)
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /**
     * Handles TEARDOWN. A TEARDOWN may target SPECIFIC streams (AirPlay 2 dynamic stream removal —
     * e.g. macOS drops the audio stream when playback stops) or the whole session. If the body lists
     * streams and they're audio-only, we stop just the audio and KEEP the mirror running; otherwise
     * we tear the whole session down. (Previously any TEARDOWN killed the mirror, so stopping audio
     * on the Mac ended screen mirroring entirely.)
     */
    open fun handleTeardownInternal(request: RtspRequest): RtspResponse {
        // Real Safari overlaps a short mirror-audio handoff (type=96) with an already-active
        // URL-video (/play) session on this same connection. Checking hapUrlSessionLocal FIRST
        // (as this used to) would end the whole HAP URL session on that handoff's TEARDOWN before
        // the stream-type-aware logic below ever runs. Parse streamTypes first instead, so a
        // targeted stream removal only closes the HAP URL session when it's genuinely ending.
        val streamTypes = parseTeardownStreamTypes(request.bodyBytes)
        if (streamTypes != null && streamTypes.isNotEmpty()) {
            // Stream-level teardown: stop ONLY the listed streams. Keep the session (keys, NTP,
            // event channel) alive so the remaining stream keeps running and a stopped one can be
            // re-added later — e.g. audio keeps playing with video gone, or video keeps mirroring
            // with audio stopped. But if this removes the LAST active stream (e.g. macOS names both
            // 96 and 110 to end the session), fall through to a full teardown so cleanup isn't left
            // to the eventual socket close.
            if (streamTypes.contains(96)) {
                if (multiroomLocal.get()?.hasRealtime() == true) multiroomLocal.get()?.stopRealtime() else onMirrorAudioStop()
                activeStreamTypes.remove(96)
            }
            if (streamTypes.contains(110)) { onMirrorVideoStop(); activeStreamTypes.remove(110) }
            if (streamTypes.contains(103)) { onBufferedAudioStop(); multiroomLocal.get()?.stopAudio(); activeStreamTypes.remove(103) }
            // An audio-only stream removal is how iOS PAUSES (it re-adds the stream with SETUP on
            // play) — keep the session and connection, or the sender, still "connected", can
            // never resume. But Safari's video handoff looks the same: a ~2 s audio stream, removed,
            // after which Safari only sends /play once the session has ENDED. So only a stream that
            // actually played for a while counts as a pause; video/mixed removals end the session.
            // HOWEVER: if URL-video (/play) is active, this is Safari's mirror-audio handoff
            // overlapping it, not a real close — keep the session alive regardless of timing.
            val now = System.nanoTime()
            val audioPause = streamTypes.none { it == 110 } && streamTypes.all { type ->
                val started = audioStreamStartedAt[type]
                started != null && now - started >= PAUSE_MIN_STREAM_AGE_NS
            }
            val urlVideoActive = onIsUrlVideoActive()
            streamTypes.forEach { audioStreamStartedAt.remove(it) }
            if (activeStreamTypes.isNotEmpty() || audioPause || urlVideoActive) {
                Logger.i("TEARDOWN streams=$streamTypes — stopped those, session continues (active=$activeStreamTypes, urlVideo=$urlVideoActive)")
                return RtspResponse(statusCode = 200, statusMessage = "OK", protocol = request.responseProtocol())
            }
            Logger.i("TEARDOWN streams=$streamTypes — last stream removed, ending session")
        } else {
            Logger.i("TEARDOWN (session, body=${request.bodyBytes.size}B) — streaming stopping")
        }
        activeStreamTypes.clear()
        if (hapUrlSessionLocal.get() != null) {
            closeHapUrlSession()
            onVideoStop()
        } else {
            onStreamingStopped()
        }
        return RtspResponse(statusCode = 200, statusMessage = "OK", protocol = request.responseProtocol())
    }

    /** Parses the `streams` list from a TEARDOWN body, returning the stream `type`s, or null. */
    private fun parseTeardownStreamTypes(body: ByteArray): List<Int>? = runCatching {
        if (body.isEmpty()) return null
        val streams = PlistCodec.decode(body)["streams"] as? List<*> ?: return null
        streams.mapNotNull { ((it as? Map<*, *>)?.get("type") as? Long)?.toInt() }
    }.getOrNull()

    private fun handleGetParameter(request: RtspRequest): RtspResponse {
        val query = request.body.trim()
        val isMultiRoom = videoTransport == AirPlayVideoTransport.MULTIROOM
        if (isMultiRoom) Logger.i("MR: GET_PARAMETER body='$query'")
        else Logger.i("GET_PARAMETER body='$query'")
        // macOS queries "volume" during setup and aborts if it gets no value back. Report the
        // last value the sender set so its volume slider reflects the receiver.
        return if (query.startsWith("volume")) {
            RtspResponse(
                statusCode = 200, statusMessage = "OK",
                body = "volume: %.6f\r\n".format(currentVolume),
                contentType = "text/parameters",
                protocol = request.responseProtocol()
            )
        } else {
            RtspResponse(statusCode = 200, statusMessage = "OK", protocol = request.responseProtocol())
        }
    }

    private fun handleSetParameter(request: RtspRequest): RtspResponse {
        val body = request.body
        val contentType = request.headers["Content-Type"]?.lowercase() ?: ""
        val isMultiRoom = videoTransport == AirPlayVideoTransport.MULTIROOM
        // Text bodies carry "volume: <dB>"; binary bodies carry DMAP now-playing metadata or artwork.
        when {
            body.startsWith("volume") -> {
                body.substringAfter(":").trim().toFloatOrNull()?.let { v ->
                    currentVolume = v
                    onVolume(v)
                    if (isMultiRoom) Logger.d("MR: SET_PARAMETER volume=$v")
                    else Logger.d("SET_PARAMETER volume=$v")
                }
            }
            body.startsWith("progress") -> {
                // "progress: <start>/<current>/<end>" — RTP timestamps of the track start, the frame
                // now playing and the track end.
                val parts = body.substringAfter(":").trim().split("/").mapNotNull { it.trim().toLongOrNull() }
                if (parts.size == 3) onProgress(parts[0], parts[1], parts[2])
                if (isMultiRoom) Logger.d("MR: SET_PARAMETER progress=${parts.joinToString("/")}")
                else Logger.i("SET_PARAMETER progress=${parts.joinToString("/")}")
            }
            contentType.startsWith("image/") -> {
                // Album artwork (image/jpeg, image/png). A zero-length body clears it.
                onArtwork(request.bodyBytes)
                if (isMultiRoom) Logger.i("MR: SET_PARAMETER artwork (${request.bodyBytes.size}B, $contentType)")
                else Logger.i("SET_PARAMETER artwork (${request.bodyBytes.size}B, $contentType)")
            }
            contentType.contains("dmap") || looksLikeDmap(request.bodyBytes) -> {
                val meta = DmapParser.parseNowPlaying(request.bodyBytes)
                onNowPlayingMetadata(meta.title, meta.artist, meta.album)
                if (isMultiRoom) Logger.i("MR: SET_PARAMETER now-playing: title='${meta.title}' artist='${meta.artist}' album='${meta.album}'")
                else Logger.i("SET_PARAMETER now-playing: title='${meta.title}' artist='${meta.artist}' album='${meta.album}'")
                Logger.i("now-playing tags: ${meta.tags}")
                // Flick AirPlay: the sender says at once whether it plays or paused (DACP player state)
                meta.state?.let { st -> if (videoTransport != AirPlayVideoTransport.MULTIROOM) onPlaybackRate(if (st == 1 || st == 4) 1f else 0f) }
            }
            else -> {
                if (isMultiRoom) Logger.d("MR: SET_PARAMETER (${request.bodyBytes.size}B, $contentType, unhandled)")
                else Logger.d("SET_PARAMETER (${request.bodyBytes.size}B, $contentType, unhandled)")
            }
        }
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /**
     * POST /fp-setup2 — the newer FairPlay handshake iOS uses for AirPlay video (URL/HLS). Its format is not
     * publicly documented. Reject it the same way as UxPlay so compatible senders can fall back to the regular
     * plaintext /play URL flow. An empty 200 tells the sender that setup succeeded and makes it switch this
     * connection to an encrypted channel we cannot read.
     */
    private fun handleFpSetup2(request: RtspRequest): RtspResponse {
        val b = request.bodyBytes
        val hex = b.joinToString(" ") { "%02x".format(it) }
        val declaredBodyLength = if (b.size >= 12) {
            ((b[8].toInt() and 0xff) shl 24) or
                ((b[9].toInt() and 0xff) shl 16) or
                ((b[10].toInt() and 0xff) shl 8) or
                (b[11].toInt() and 0xff)
        } else {
            -1
        }
        val framing = if (b.size >= 8 && b.copyOfRange(0, 4).contentEquals("FPLY".toByteArray())) {
            "FPLY version=${b[4].toInt() and 0xff}.${b[5].toInt() and 0xff} " +
                "phase=${b[6].toInt() and 0xff} flags=${b[7].toInt() and 0xff} declaredBody=$declaredBodyLength"
        } else {
            "unknown framing"
        }
        Logger.w("fp-setup2 (${b.size}B, $framing) body=$hex — rejecting with 421 to request /play fallback")
        return RtspResponse(
            statusCode = 421,
            statusMessage = "Misdirected Request",
            contentType = "application/x-apple-binary-plist",
            protocol = request.responseProtocol()
        )
    }

    /** Heuristic: a DMAP body starts with the `mlit` listing-item container tag. */
    private fun looksLikeDmap(body: ByteArray): Boolean =
        body.size >= 8 && String(body, 0, 4, Charsets.US_ASCII) == "mlit"

    /** Handles any unrecognized RTSP method. */
    open fun handleUnknownInternal(request: RtspRequest): RtspResponse {
        Logger.w("Unknown/unhandled RTSP: ${request.method} ${request.uri} (${request.bodyBytes.size}B body)")
        return RtspResponse(statusCode = 501, statusMessage = "Not Implemented", protocol = request.responseProtocol())
    }

    /** Handles FLUSH — macOS requests we discard buffered media data (seek/pause). */
    private fun handleFlush(@Suppress("UNUSED_PARAMETER") request: RtspRequest): RtspResponse {
        multiroomLocal.get()?.flushRealtime()
        // Flick AirPlay: a classic AirPlay sender pauses with FLUSH (there is no rate message), so the
        // track's clock stops here; the next "progress" or RECORD starts it again.
        if (videoTransport != AirPlayVideoTransport.MULTIROOM) onPlaybackRate(0f)
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /** Handles PAUSE — suspends media delivery. Responds 200 OK; resume arrives as RECORD. */
    open fun handlePauseInternal(request: RtspRequest): RtspResponse {
        Logger.d("PAUSE received")
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /** Handles AirPlay photo sharing: HTTP `PUT /photo` with a JPEG/PNG body. */
    open fun handlePhotoPutInternal(request: RtspRequest): RtspResponse {
        if (!request.isPhotoRequest()) {
            return handleUnknownInternal(request)
        }

        return when (val validation = PhotoHandler.validatePhoto(
            request.bodyBytes,
            request.headers["Content-Type"]
        )) {
            is PhotoValidation.Valid -> {
                onPhotoReceived(request.bodyBytes, validation.imageType)
                Logger.i("Photo received (${validation.imageType.mimeType}, ${request.bodyBytes.size} bytes)")
                RtspResponse(
                    statusCode = 200,
                    statusMessage = "OK",
                    protocol = request.responseProtocol()
                )
            }
            is PhotoValidation.Invalid -> {
                Logger.w("Photo rejected: ${validation.reason}")
                RtspResponse(
                    statusCode = 400,
                    statusMessage = "Bad Request",
                    protocol = request.responseProtocol()
                )
            }
        }
    }

    /** Handles AirPlay photo clearing: HTTP `DELETE /photo`. */
    open fun handlePhotoDeleteInternal(request: RtspRequest): RtspResponse {
        if (!request.isPhotoRequest()) {
            return handleUnknownInternal(request)
        }

        onPhotoCleared()
        Logger.i("Photo cleared")
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            protocol = request.responseProtocol()
        )
    }

    /** Human-readable preview of a request/response body for the multi-room protocol log. */
    private fun mrBodyPreview(b: ByteArray): String {
        if (b.size >= 8 && String(b, 0, 8, Charsets.ISO_8859_1) == "bplist00") {
            runCatching { return "bplist " + PlistCodec.decodeAny(b).toString().take(4000) }
        }
        val printable = b.take(200).all { it in 32..126 || it == 10.toByte() || it == 13.toByte() }
        if (printable) return "'" + String(b, Charsets.ISO_8859_1).take(1500) + "'"
        return b.take(512).joinToString("") { "%02x".format(it) } + if (b.size > 512) "…" else ""
    }

    private fun sendResponse(outputStream: OutputStream, response: RtspResponse) {
        if (videoTransport == AirPlayVideoTransport.MULTIROOM) {
            val w = response.wireBody()
            Logger.d("MR: -> ${response.statusCode} ${response.statusMessage} ct=${response.contentType} ${w.size}B" +
                if (w.isNotEmpty()) " ${mrBodyPreview(w)}" else "")
        }
        // Binary-safe: build the header block as ASCII, then write the raw body bytes.
        // Content-Length must be the BYTE length (not String.length) so binary plists,
        // FairPlay payloads, and encrypted bodies are framed correctly.
        val wire = response.wireBody()
        val head = StringBuilder()
        head.append("${response.protocol} ${response.statusCode} ${response.statusMessage}\r\n")
        if (response.protocol.startsWith("RTSP")) {
            head.append("CSeq: $currentCSeq\r\n")
        }
        head.append("Server: AirTunes/220.68\r\n")
        response.contentType?.let { head.append("Content-Type: $it\r\n") }
        response.headers.forEach { (key, value) ->
            head.append("$key: $value\r\n")
        }
        if (wire.isNotEmpty() || response.protocol.startsWith("HTTP")) {
            head.append("Content-Length: ${wire.size}\r\n")
        }
        head.append("\r\n")
        // ONE write for header + body. Two back-to-back write()s on a raw socket stream are two TCP
        // sends; with Nagle on, the body segment waits for the peer's ACK of the header segment, and
        // the peer's delayed ACK can hold that up for tens to a couple of hundred ms — per RTSP
        // response, and the handshake is ~10 request/response pairs.
        val headBytes = head.toString().toByteArray(Charsets.US_ASCII)
        val out = if (wire.isEmpty()) headBytes else headBytes.copyOf(headBytes.size + wire.size)
            .also { System.arraycopy(wire, 0, it, headBytes.size, wire.size) }
        outputStream.write(out)
        outputStream.flush()
    }

    private fun sendServiceUnavailable(socket: Socket) {
        try {
            val response = "RTSP/1.0 503 Service Unavailable\r\nCSeq: 0\r\n\r\n"
            socket.outputStream.write(response.toByteArray())
            socket.outputStream.flush()
        } catch (e: Exception) {
            Logger.e("Error sending 503 response", e)
        }
    }

    companion object {
        /** An audio stream must have played this long for its TEARDOWN to count as a pause. */
        private const val PAUSE_MIN_STREAM_AGE_NS = 8_000_000_000L
        private const val RTSP_PORT = 7000

        // SRP PIN access control. macOS's AirPlay code-entry field is exactly 4 digits, so the PIN
        // must be 4 digits to be enterable. A 4-digit space is low-entropy, so the load-bearing
        // defense is the MAX_PAIR_ATTEMPTS lockout below (uniform random + hard attempt cap, NOT
        // length). The PIN is still uniformly random — no biased truncation.
        private const val PIN_DIGITS = 4
        private const val PIN_SPACE = 10_000        // 10^PIN_DIGITS
        private const val MAX_PAIR_ATTEMPTS = 10
        private const val BIND_MAX_ATTEMPTS = 12      // ~3s total — covers a quick stop→start restart
        private const val BIND_RETRY_MS = 250L
        // iOS/iPadOS mirror-stream SETUP requests carry a large binary plist
        // (observed ~77 KB from iPhone17,1 on iOS 26) that exceeded the previous
        // 64 KB cap, so the reader rejected the video stream SETUP and the sender
        // tore the session down right after audio started. 1 MB gives ample head-
        // room for future senders while staying trivially safe on a LAN.
        private const val MAX_MESSAGE_BYTES = 1024 * 1024
        private const val OCTET_STREAM = "application/octet-stream"
        private const val TIMING_PORT = 6002   // matches TimingHandler's UDP NTP port
        private const val SESSION_ID = "PhairPlaySession"
        private const val AUDIO_RTP_PORT = 6001
        private const val DEFAULT_SENDER_NAME = "AirPlay Sender"
        /** Now-playing cover art via SET_PARAMETER (image/jpeg|png); several hundred KB is typical. */
        private const val MAX_ARTWORK_BYTES = 4 * 1024 * 1024
    }
}

private fun RtspRequest.isPhotoRequest(): Boolean =
    uri.substringBefore("?") == PhotoHandler.PHOTO_PATH

private fun RtspRequest.responseProtocol(): String =
    if (protocol.startsWith("HTTP/")) protocol else "RTSP/1.0"

/** True if the body is an Apple binary plist (AirPlay 2 mirroring SETUP), vs legacy SDP. */
private fun RtspRequest.isPlistBody(): Boolean =
    bodyBytes.size >= 8 && String(bodyBytes, 0, 8, Charsets.US_ASCII) == "bplist00"
