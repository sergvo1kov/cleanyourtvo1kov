package com.phairplay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.phairplay.MainActivity
import com.phairplay.R
import android.view.Surface
import com.phairplay.airplay.AirPlayReceiver
import com.phairplay.cast.CastReceiver
import com.phairplay.miracast.MiracastReceiver
import com.phairplay.settings.AppSettings
import com.phairplay.settings.SettingsRepository
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.media3.common.text.CueGroup

/**
 * PhairPlayService — Android ForegroundService that hosts all receiver protocols.
 *
 * WHY: The AirPlay/Miracast/Cast receivers need to run continuously in the background.
 * Android may kill background processes. A ForegroundService with a persistent
 * notification keeps the app alive and shows the user that PhairPlay is active.
 *
 * HOW: Bind to this service from [MainActivity] to receive state updates.
 * Use [ServiceController] to send start/stop/restart commands.
 *
 * Service lifecycle:
 *   startForegroundService() → onCreate() → onStartCommand() → [running in background]
 *   stopSelf() / stopService() → onDestroy() → all receivers stopped
 *
 * Commands via Intent actions (sent by [ServiceController]):
 *   ACTION_START   — starts all enabled receivers
 *   ACTION_STOP    — stops all receivers and stops the service
 *   ACTION_RESTART — stops then starts all receivers (service keeps running)
 *
 * UI vs. session state machine (the UI is a full-screen "input source" for VISUAL sessions only):
 *
 *   session            UI leaves foreground (Back / Home / onStop)   session starts, UI not resumed
 *   ─────────────────  ────────────────────────────────────────────  ─────────────────────────────
 *   audio-only         nothing: keeps playing headless                nothing: UI is NOT popped
 *   mirror / video     MainActivity -> [endVisualSession] -> receiver  receiver.onVisualSessionStarted
 *                      closes the sender's RTSP connection (or stops   -> [bringUiToFront] starts
 *                      just the mirror streams if independent audio    MainActivity
 *                      rides on it): the phone sees mirroring stop
 *   photo              same; the photo frame is also cleared           not auto-foregrounded
 *
 * Who ends what: only the sender (TEARDOWN / disconnect) or the UI leaving ([endVisualSession]) ends
 * a visual session; neither ever stops this service, the receivers or the mDNS advertisement (the end
 * of a session re-advertises via AirPlayReceiver.onStreamingStopped, as before). Who starts the UI:
 * only the session-START callback, never a state change, so ending a session cannot re-foreground it.
 * Foregrounding from the background needs SYSTEM_ALERT_WINDOW on Android 10+; without it the mirror
 * simply runs as before (see [bringUiToFront]).
 */
class PhairPlayService : Service() {

    // Binder for Activity binding (returns this service directly)
    private val binder = LocalBinder()

    // Coroutine scope — cancelled in onDestroy() to clean up all coroutines
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Observable state — Activities and Fragments observe this via the binder
    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val _airPlayState = MutableStateFlow(ProtocolState.DISABLED)
    val airPlayState: StateFlow<ProtocolState> = _airPlayState.asStateFlow()

    private val _miracastState = MutableStateFlow(ProtocolState.DISABLED)
    val miracastState: StateFlow<ProtocolState> = _miracastState.asStateFlow()

    private val _castState = MutableStateFlow(ProtocolState.DISABLED)
    val castState: StateFlow<ProtocolState> = _castState.asStateFlow()

    private val _activeConnection = MutableStateFlow<ActiveConnection?>(null)
    val activeConnection: StateFlow<ActiveConnection?> = _activeConnection.asStateFlow()

    private val _photoFrame = MutableStateFlow<PhotoFrame?>(null)
    val photoFrame: StateFlow<PhotoFrame?> = _photoFrame.asStateFlow()

    // Non-null while AirPlay audio is playing WITHOUT video — drives the now-playing overlay.
    private val _nowPlaying = MutableStateFlow<com.phairplay.airplay.NowPlayingInfo?>(null)
    val nowPlaying: StateFlow<com.phairplay.airplay.NowPlayingInfo?> = _nowPlaying.asStateFlow()

    // Non-null while a PIN should be shown on screen for SRP pair-setup (PIN access control).
    private val _pairingPin = MutableStateFlow<String?>(null)
    val pairingPin: StateFlow<String?> = _pairingPin.asStateFlow()
    private val _videoCues = MutableStateFlow<CueGroup?>(null)
    val videoCues: StateFlow<CueGroup?> = _videoCues.asStateFlow()

    // Surface provider — supplied by MainActivity after binding (Sprint 5).
    // The lambda captures this field so it always uses the latest provider even if
    // setVideoSurfaceProvider() is called after startAirPlay().
    @Volatile private var videoSurfaceProvider: (() -> Surface?)? = null

    // Receiver instances — null when not running
    // @Volatile: also read, unlocked, from the network callback thread.
    @Volatile private var airPlayReceiver: AirPlayReceiver? = null

    // Watches for the network coming back so mDNS can be re-registered — see
    // [registerNetworkCallback]. Null when never registered or already unregistered.
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var miracastReceiver: MiracastReceiver? = null
    private var castReceiver: CastReceiver? = null

    // Settings — read once when starting, re-read on restart
    private lateinit var settingsRepository: SettingsRepository

    /**
     * Guards the receiver fields above and [destroyed].
     *
     * Commands arrive on [serviceScope] (IO pool, so a START and a STOP can run in parallel) and
     * teardown also comes from the main thread ([onTaskRemoved], [onDestroy]). All of them read and
     * replace [airPlayReceiver] & co., so starting and stopping them is serialized under this lock.
     * The suspend parts (reading settings, the delay in a restart) stay outside it.
     */
    private val receiversLock = Any()

    /** Set in [onDestroy]; a start command that was still in flight must not build receivers after it. */
    private var destroyed = false

    // ─── Service Lifecycle ───────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Logger.i("PhairPlayService created")
        settingsRepository = SettingsRepository(applicationContext)
        createNotificationChannel()
        registerNetworkCallback()
        // Flick AirPlay: the track as a system media session, for the launchers' Now Playing
        airPlaySession = AirPlaySession(applicationContext) { cmd -> sendAirPlayRemoteCommand(cmd) }
        serviceScope.launch {
            nowPlaying.collect { info -> airPlaySession?.update(info) }
        }
    }

    @Volatile private var airPlaySession: AirPlaySession? = null

    /** The phone's AirPlay volume slider moves the TV's own volume. */
    fun onSenderVolume(db: Float) {
        airPlaySession?.setVolume(db)
    }

    /**
     * Re-registers the mDNS advertisement whenever a network becomes available again.
     *
     * The TV going into standby takes its Wi-Fi interface down with it. An NsdManager
     * registration does not survive that, and nothing in the app noticed: on wake the
     * receiver was still listening on port 7000, still reported itself as running, and was
     * invisible to every Apple device until the app was restarted by hand.
     *
     * [ConnectivityManager.NetworkCallback.onAvailable] fires for the first network too, not
     * only for a returning one, so [AirPlayReceiver.readvertise] is a no-op before the receiver
     * exists and an idempotent restart after; either way the advertisement matches reality.
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: run {
            Logger.w("No ConnectivityManager — mDNS will not recover automatically from a network drop")
            return
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (_serviceState.value != ServiceState.Running) return
                Logger.i("Network available — re-advertising over mDNS")
                airPlayReceiver?.readvertise()
            }

            override fun onLost(network: Network) {
                // Nothing to do: the registration is already gone with the interface. Logged
                // because it is the other half of the story when reading a diagnostic dump.
                Logger.i("Network lost — mDNS advertisement is gone until it returns")
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess {
                networkCallback = callback
                Logger.d("Network callback registered for mDNS recovery")
            }
            .onFailure { Logger.e("Could not register network callback", it) }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { cb ->
            runCatching { cm?.unregisterNetworkCallback(cb) }
                .onFailure { Logger.w("Could not unregister network callback: ${it.message}") }
        }
        networkCallback = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground immediately with a persistent notification
        startForeground(NOTIFICATION_ID, buildNotification(isRunning = false))

        when (intent?.action) {
            ACTION_START   -> serviceScope.launch { startReceivers() }
            ACTION_STOP    -> serviceScope.launch { stopReceivers(); stopSelf() }
            ACTION_RESTART -> serviceScope.launch { restartReceivers() }
            else           -> serviceScope.launch { startReceivers() } // default: start
        }

        // START_STICKY: if the system kills the service, restart it with a null intent
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * The task was removed from recents. The receiver is deliberately independent from the task:
     * AirPlay audio/video and mDNS must keep working while the launcher is in front, and
     * [START_STICKY] must be allowed to recover the process after a memory kill. Android calls this
     * callback for task removal, so stopping the service here would make "closed app" look like a
     * protocol failure. The explicit notification Stop action remains the way to shut it down.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.i("App task removed — keeping background receivers running")
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Called by [MainActivity] after it binds, to supply the [Surface] for video rendering.
     *
     * The lambda is invoked lazily — only when a stream is actually being started — so it
     * is safe to call this before or after [startAirPlay]. The lambda should return null
     * if the Activity's StreamingScreen is not yet available (e.g., surface not yet created).
     *
     * Call with `{ null }` (or simply don't call) during Activity destruction so we stop
     * holding a reference to the Activity's Surface after the window is gone.
     *
     * @param provider Lambda that returns the current [Surface], or null if unavailable.
     */
    fun setVideoSurfaceProvider(provider: () -> Surface?) {
        videoSurfaceProvider = provider
        // The receiver may have kept an AirPlay URL session alive while the
        // Activity was backgrounded. Reattach its decoder to the new SurfaceView.
        airPlayReceiver?.attachUrlVideoSurface()
    }

    fun attachCurrentVideoSurface() {
        airPlayReceiver?.attachUrlVideoSurface()
    }

    /**
     * Sends a DACP transport command (TV remote → AirPlay sender), e.g. play/pause or skip what the
     * Mac/iPhone is streaming. Bound Activities call this from media-key events. No-op if no AirPlay
     * sender has advertised a DACP identity.
     */
    fun sendAirPlayRemoteCommand(command: String) {
        airPlayReceiver?.sendRemoteCommand(command)
    }

    /** Live multi-room audio delay change (ms) from the Now Playing remote keys. */

    fun urlPlaybackInfo(): com.phairplay.airplay.PlaybackInfo? = airPlayReceiver?.urlPlaybackInfo()
    fun toggleUrlPlayback() = airPlayReceiver?.toggleUrlPlayback()
    fun seekUrlPlayback(positionSec: Double) = airPlayReceiver?.seekUrlVideo(positionSec)

    /**
     * Ends ONLY the visual session (mirror / video / photo) — called by [MainActivity] when it leaves
     * the foreground. The service, receivers, mDNS and audio-only sessions keep running; a no-op if
     * nothing visual is running. Callable from the main thread: the socket work is moved to IO.
     */
    fun endVisualSession() {
        val photoShown = _photoFrame.value != null
        _photoFrame.value = null
        val receiver = airPlayReceiver ?: return
        serviceScope.launch {
            try {
                receiver.endVisualSession(photoShown)
            } catch (e: Exception) {
                Logger.e("endVisualSession failed", e)
            }
        }
    }

    /**
     * A mirror/video session just started. If the UI is not in front, start [MainActivity] so the
     * mirror is visible (as Apple TV does). Called from receiver threads, hence the launch.
     *
     * Android 10+ blocks activity starts from the background unless the app holds SYSTEM_ALERT_WINDOW.
     * If that is missing (or the start is refused) log one warning and carry on: the mirror then just
     * runs on the black surface as before. No overlay window and no permission screen (Android TV has none).
     */
    private fun bringUiToFront() {
        wakeDisplay()
        if (UiForeground.resumed) return
        serviceScope.launch {
            if (UiForeground.resumed) return@launch
            val ctx = applicationContext
            if (!android.provider.Settings.canDrawOverlays(ctx)) {
                Logger.w(noOverlayWarning())
                return@launch
            }
            try {
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
                Logger.i("Mirror started with UI in background — bringing MainActivity to front")
            } catch (e: Exception) {
                Logger.w("${noOverlayWarning()} (startActivity failed: ${e.message})")
            }
        }
    }

    /**
     * Wakes the display (and, on this projector, the lamp) when a real AirPlay session starts while the
     * device is in network standby: confirmed live that the OS/this service stay fully reachable and
     * answer real AirPlay requests in that state — only the screen/lamp stays off, and nothing in the
     * platform turns it back on for us. `ACQUIRE_CAUSES_WAKEUP` forces that; `ON_AFTER_RELEASE` then keeps
     * it on per the system's normal timeout, same as a physical wake, so releasing right away is enough.
     */
    @Suppress("DEPRECATION")
    private fun wakeDisplay() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        // A running TV screensaver is still "interactive". Skipping the wake request there
        // leaves DreamActivity above the streaming Activity and its Surface unavailable.
        try {
            val wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "PhairPlay:sessionWake"
            )
            wakeLock.acquire(3000)
            Logger.i("Woke the display for an incoming AirPlay session")
        } catch (e: Exception) {
            Logger.w("Could not wake the display: ${e.message}")
        }
    }

    private fun noOverlayWarning() =
        "Cannot bring the UI to front for a mirror session started in the background; " +
        "grant it once with: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"

    override fun onDestroy() {
        Logger.i("PhairPlayService destroying")
        unregisterNetworkCallback()
        synchronized(receiversLock) { destroyed = true }
        stopAllReceiversInternal()
        airPlaySession?.release()
        airPlaySession = null
        serviceJob.cancel()
        super.onDestroy()
    }

    // ─── Service Control ─────────────────────────────────────────────────────

    /**
     * Starts all receivers that are enabled in Settings.
     *
     * Reads current settings, then starts AirPlay, Miracast, and/or Cast
     * receivers according to the enabled flags.
     */
    private suspend fun startReceivers() {
        val settings = settingsRepository.settingsFlow.first()
        com.phairplay.util.NetworkUtils.multiRoomIdentity = settings.multiRoomEnabled
        com.phairplay.util.NetworkUtils.getPersistentUuid(applicationContext) // warm the UUID cache used for the derived deviceid
        synchronized(receiversLock) {
            if (destroyed) {
                // A START (or the start half of a RESTART) that lost the race against onDestroy():
                // starting now would leave a receiver bound to port 7000 with nobody to stop it.
                Logger.i("Service already destroyed — not starting receivers")
                return
            }
            Logger.i("Starting receivers: AirPlay=${settings.airPlayEnabled}, Miracast=${settings.miracastEnabled}, Cast=${settings.castEnabled}")

            _serviceState.value = ServiceState.Running
            updateNotification(isRunning = true)

            if (settings.airPlayEnabled)   startAirPlay(settings)
            if (settings.miracastEnabled)  startMiracast()
            if (settings.castEnabled)      startCast()
        }
    }

    /**
     * Stops all active receivers and updates the service state to Stopped.
     * Does NOT call stopSelf() — use [ACTION_STOP] for that.
     */
    private fun stopReceivers() {
        Logger.i("Stopping all receivers")
        synchronized(receiversLock) {
            stopAllReceiversInternal()
            _serviceState.value = ServiceState.Stopped
            _activeConnection.value = null
            updateNotification(isRunning = false)
        }
    }

    /**
     * Restarts all receivers: stops them, waits briefly, then starts them again.
     * Used for applying settings changes or recovering from errors.
     */
    private suspend fun restartReceivers() {
        Logger.i("Restarting all receivers")
        _serviceState.value = ServiceState.Restarting
        updateNotification(isRunning = false)
        stopAllReceiversInternal()
        kotlinx.coroutines.delay(500) // brief pause to ensure ports are released
        startReceivers()
    }

    // ─── Individual Protocol Starters ────────────────────────────────────────

    /**
     * Creates and starts the [AirPlayReceiver].
     *
     * The display name comes from settings — blank means use the Android device name,
     * which [MdnsService] resolves at runtime.
     *
     * Surface is not available here (it lives in the Activity/Fragment).
     * The surface provider is wired up from [MainActivity] in Sprint 5.
     * Until then, video frames are silently discarded and only audio plays.
     *
     * @param settings Current app settings; read once per start/restart cycle.
     */
    private fun startAirPlay(settings: AppSettings) {
        // Mirror the debug-overlay setting into the shared stats bus that StreamingScreen reads.
        com.phairplay.airplay.StreamStats.overlayEnabled = settings.showDebugOverlay

        // Idempotent: a redundant ACTION_START (e.g. the activity being recreated while the
        // foreground service is still alive) must NOT spin up a second AirPlayReceiver competing
        // for port 7000. The existing receiver keeps running and picks up the new Surface via the
        // surfaceProvider. A genuine restart goes through ACTION_RESTART (stop → delay → start).
        if (airPlayReceiver != null) {
            Logger.i("AirPlay receiver already running — skipping duplicate start")
            return
        }
        // Captures the sender name reported by AirPlayReceiver before CONNECTED fires.
        // onSenderNameChanged is called synchronously before emitState(CONNECTED), so
        // this assignment happens-before the Main-thread read in onStateChanged.
        var pendingSenderName = "AirPlay Sender"

        airPlayReceiver = AirPlayReceiver(
            context = applicationContext,
            displayName = settings.effectiveDisplayName,
            mirrorWidth = settings.mirrorWidth,
            mirrorHeight = settings.mirrorHeight,
            audioEnabled = settings.mirrorAudioEnabled,
            pinAuthEnabled = settings.airPlayPinAuthEnabled,
            // Select video transport: MULTIROOM for multi-room audio, LEGACY by default.
            videoTransport = if (settings.multiRoomEnabled)
                com.phairplay.airplay.AirPlayVideoTransport.MULTIROOM
            else
                com.phairplay.airplay.AirPlayVideoTransport.LEGACY,
            // Delegate to the current provider at call time — captures the field, not a fixed value.
            // When MainActivity calls setVideoSurfaceProvider(), future surface requests use it.
            videoSurfaceProvider = { videoSurfaceProvider?.invoke() },
            onSenderNameChanged = { name ->
                pendingSenderName = name.ifEmpty { "AirPlay Sender" }
            },
            onPhotoReceived = { bytes, imageType ->
                _photoFrame.value = PhotoFrame(
                    bytes = bytes.copyOf(),
                    mimeType = imageType.mimeType
                )
                updateNotification(isRunning = true)
            },
            onPhotoCleared = {
                _photoFrame.value = null
            },
            onNowPlayingChanged = { info ->
                _nowPlaying.value = info
            },
            onSenderVolume = { db -> onSenderVolume(db) },
            onPinChanged = { pin ->
                _pairingPin.value = pin
            },
            onVideoCues = { cues -> _videoCues.value = cues },
            onVisualSessionStarted = { bringUiToFront() },
            onStateChanged = { state ->
                _airPlayState.value = state
                when (state) {
                    ProtocolState.CONNECTED   -> {
                        _photoFrame.value = null
                        _activeConnection.value =
                            ActiveConnection(pendingSenderName, Protocol.AIRPLAY)
                        updateNotification(isRunning = true, streamingSenderName = pendingSenderName)
                    }
                    ProtocolState.ADVERTISING,
                    ProtocolState.DISABLED,
                    ProtocolState.ERROR       -> {
                        _activeConnection.value = null
                        updateNotification(isRunning = state != ProtocolState.DISABLED &&
                                                       state != ProtocolState.ERROR)
                    }
                }
            }
        ).also { it.start() }
        Logger.d("AirPlay receiver started (displayName='${settings.effectiveDisplayName}')")
    }

    private fun startMiracast() {
        // Idempotent for the same reason as startAirPlay(): the UI can be relaunched (and send a fresh
        // ACTION_START) while this service — deliberately — is still running.
        if (miracastReceiver != null) return
        _miracastState.value = ProtocolState.ADVERTISING
        miracastReceiver = MiracastReceiver(
            context = applicationContext,
            onStateChanged = { state -> _miracastState.value = state }
        ).also { it.start() }
        Logger.d("Miracast receiver started")
    }

    private fun startCast() {
        if (castReceiver != null) return
        _castState.value = ProtocolState.ADVERTISING
        castReceiver = CastReceiver(
            context = applicationContext,
            onStateChanged = { state -> _castState.value = state }
        ).also { it.start() }
        Logger.d("Cast receiver started")
    }

    /**
     * Stops every receiver and resets the published state.
     *
     * Runs entirely under [receiversLock] (re-entrant, so [stopReceivers] may already hold it):
     * the fields are detached first, so the same receiver can never be stopped twice, and a
     * concurrent [startReceivers] waits until the old receiver has released port 7000 instead of
     * racing it for the socket.
     */
    private fun stopAllReceiversInternal() = synchronized(receiversLock) {
        val airPlay = airPlayReceiver
        val miracast = miracastReceiver
        val cast = castReceiver
        airPlayReceiver = null
        miracastReceiver = null
        castReceiver = null
        try { airPlay?.stop() } catch (e: Exception) { Logger.e("AirPlay stop error", e) }
        try { miracast?.stop() } catch (e: Exception) { Logger.e("Miracast stop error", e) }
        try { cast?.stop() } catch (e: Exception) { Logger.e("Cast stop error", e) }
        _airPlayState.value = ProtocolState.DISABLED
        _miracastState.value = ProtocolState.DISABLED
        _castState.value = ProtocolState.DISABLED
        _photoFrame.value = null
        _nowPlaying.value = null
        _pairingPin.value = null
        _videoCues.value = null
    }

    // ─── Notification ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW  // LOW: no sound, minimal visual interruption
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds the persistent notification for the ForegroundService.
     *
     * The notification shows the service status and provides quick actions
     * so users can Stop or Restart without opening the app.
     *
     * @param isRunning            True if receivers are active; false if stopped/restarting.
     * @param notificationContentText Override for the notification body text.
     *   When null, the default running/stopped status string is used.
     *   Pass the sender name here (e.g. "Streaming from MacBook Pro") when connected.
     */
    private fun buildNotification(
        isRunning: Boolean,
        notificationContentText: String? = null
    ): Notification {
        // Tapping the notification opens the app
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Stop" action — sends ACTION_STOP to this service
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PhairPlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Restart" action — sends ACTION_RESTART to this service
        val restartIntent = PendingIntent.getService(
            this, 2,
            Intent(this, PhairPlayService::class.java).apply { action = ACTION_RESTART },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isRunning) R.string.notification_status_running
                         else           R.string.notification_status_stopped

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(notificationContentText ?: getString(statusText))
            .setContentIntent(openAppIntent)
            .setOngoing(true)                   // Prevents user from swiping away
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_stop,    getString(R.string.action_stop),    stopIntent)
            .addAction(R.drawable.ic_restart, getString(R.string.action_restart), restartIntent)
            .build()
    }

    private fun updateNotification(isRunning: Boolean, streamingSenderName: String? = null) {
        val contentText = streamingSenderName?.let {
            getString(R.string.notification_status_streaming, it)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(isRunning, contentText))
    }

    // ─── Binder ─────────────────────────────────────────────────────────────

    /**
     * LocalBinder — Provides direct access to [PhairPlayService] for bound Activities.
     *
     * WHY: Binding (rather than just starting) the service gives the Activity a
     * direct reference, so it can observe the service's StateFlows without
     * using broadcasts or a shared ViewModel.
     */
    inner class LocalBinder : Binder() {
        fun getService(): PhairPlayService = this@PhairPlayService
    }

    companion object {
        const val CHANNEL_ID      = "phairplay_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START    = "com.phairplay.action.START"
        const val ACTION_STOP     = "com.phairplay.action.STOP"
        const val ACTION_RESTART  = "com.phairplay.action.RESTART"
    }
}

/**
 * PhotoFrame — latest still image received via AirPlay `/photo`.
 *
 * The bytes are kept in memory only and cleared on DELETE `/photo`, streaming
 * start, receiver stop, or service destruction.
 */
data class PhotoFrame(
    val bytes: ByteArray,
    val mimeType: String,
    val receivedAtMillis: Long = System.currentTimeMillis()
)
