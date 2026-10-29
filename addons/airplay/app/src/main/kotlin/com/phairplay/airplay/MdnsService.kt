package com.phairplay.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import com.phairplay.util.NetworkUtils

/**
 * MdnsService — Advertises PhairPlay as an AirPlay 2 receiver on the local network.
 *
 * WHY: For macOS/iOS to show PhairPlay in the AirPlay menu, the device must announce
 * itself using mDNS (Multicast DNS, the same protocol as Apple's Bonjour).
 * Without this advertisement, no sender would know PhairPlay exists.
 *
 * HOW: Registers two mDNS services using Android's [NsdManager]:
 * - `_airplay._tcp` — main AirPlay service with feature flags and device info
 * - `_raop._tcp`    — audio streaming service (required even for screen mirroring)
 *
 * Both services use port [AIRPLAY_PORT] (7000), which is where [RtspHandler] listens.
 *
 * The service name shown in AirPlay pickers is determined by [displayNameOverride]:
 * - If set: uses the user-configured name from Settings
 * - If blank/null: falls back to [NetworkUtils.getDeviceName]
 *
 * State changes are reported via [onStateChange] callback.
 *
 * Example:
 *   val mdns = MdnsService(context, onStateChange = { state -> /* update UI */ })
 *   mdns.start(displayNameOverride = "Living Room TV")
 *   mdns.stop()
 *   mdns.restart(displayNameOverride = "Living Room TV")
 */
class MdnsService(
    private val context: Context,
    private val videoTransport: AirPlayVideoTransport = AirPlayVideoTransport.LEGACY,
    private val onStateChange: (ProtocolState) -> Unit = {},
    /**
     * Called with the actual mDNS service name after registration completes.
     *
     * Android's NsdManager resolves name collisions automatically: if another device
     * on the network is already registered as "PhairPlay", Android will register us as
     * "PhairPlay (2)" instead. The [onActualNameRegistered] callback delivers the name
     * that was actually registered (which may differ from the requested name).
     *
     * The caller can use this to update the UI (e.g., show "Registered as: PhairPlay (2)")
     * or log the divergence for debugging.
     *
     * Only the `_airplay._tcp` service name is reported (not the `_raop._tcp` name,
     * which has a MAC address prefix and is not shown to users).
     */
    private val onActualNameRegistered: (String) -> Unit = {}
) {

    // Android's built-in mDNS manager — handles multicast registration
    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /**
     * Wi-Fi multicast lock, held for as long as we advertise.
     *
     * Wi-Fi hardware drops multicast and broadcast frames that are not addressed to this
     * device whenever it is allowed to power-save, and mDNS is entirely multicast. Without
     * this lock a sender's query can simply never reach us, which looks exactly like "the TV
     * is not discoverable" even though the registration is live. The manifest has asked for
     * CHANGE_WIFI_MULTICAST_STATE since the beginning; nothing used it.
     *
     * Costs battery on a phone — irrelevant here, this is a mains-powered TV receiver whose
     * entire job is to be findable.
     */
    private val multicastLock: WifiManager.MulticastLock? = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.createMulticastLock("PhairPlay-mdns").apply { setReferenceCounted(false) }
    }.getOrElse {
        Logger.w("Could not create Wi-Fi multicast lock: ${it.message}")
        null
    }

    // Listeners track registration state; held to enable unregistration later.
    // Guarded by [lock].
    private var airPlayListener: NsdManager.RegistrationListener? = null
    private var raopListener: NsdManager.RegistrationListener? = null

    /**
     * Serializes start / stop / restart.
     *
     * These are reached from several threads at once: the receiver's coroutine scope
     * ([AirPlayReceiver.onStreamingStopped], [AirPlayReceiver.readvertise]) and the service's
     * teardown ([AirPlayReceiver.stop]). Unserialized, two threads unregistered the same
     * [NsdManager] listeners (`IllegalArgumentException: listener not registered`) and a
     * restart could re-register the services after the shutdown had already begun, leaving an
     * advertisement with nothing listening behind it.
     *
     * NsdManager's register/unregister calls are asynchronous (they post to its handler and
     * return), so holding this lock across them cannot deadlock with the listener callbacks.
     */
    private val lock = Any()

    // Count of how many services have confirmed registration.
    // Only when both reach 2 do we emit ProtocolState.ADVERTISING.
    // Guarded by [lock]; callbacks go through [incrementAndCheckBothRegistered].
    private var registeredCount = 0

    // Guard against double-start
    @Volatile
    private var isStarted = false

    /**
     * True once [stop] was called and [start] has not been called since. While set, [restart]
     * is a no-op: a late "streaming ended" or "network came back" callback that races with
     * shutdown must not re-advertise a receiver that is going away.
     * Guarded by [lock].
     */
    private var stopped = false

    /**
     * Bumped on every registration cycle. Each listener remembers the generation it was created
     * for and ignores its callbacks once superseded, so a late `onServiceRegistered` from a
     * registration that [restart] already replaced cannot be counted towards the new one.
     * Written under [lock]; volatile because listener callbacks read it without taking it.
     */
    @Volatile
    private var generation = 0

    // The name we requested to register — compared against the actual registered name
    // in onServiceRegistered to detect mDNS collision auto-renaming.
    @Volatile
    private var requestedName: String = ""

    /**
     * Starts mDNS advertising.
     *
     * Registers both the `_airplay._tcp` and `_raop._tcp` services.
     * The device will appear in the macOS/iOS AirPlay menu within ~1-3 seconds.
     *
     * Idempotent: calling it twice without [stop] in between is a no-op.
     *
     * @param displayNameOverride User-configured display name from Settings.
     *   Pass `null` or blank to use the Android system device name.
     */
    fun start(displayNameOverride: String? = null) {
        synchronized(lock) {
            stopped = false
            startLocked(displayNameOverride)
        }
    }

    private fun startLocked(displayNameOverride: String?) {
        if (isStarted) {
            Logger.w("MdnsService.start() called but already registered — ignoring")
            return
        }
        isStarted = true
        registeredCount = 0
        generation++
        acquireMulticastLock()

        val effectiveName = resolveDisplayName(displayNameOverride)
        Logger.i("Starting mDNS advertising as '$effectiveName'")
        requestedName = effectiveName

        try {
            registerAirPlayService(effectiveName, generation)
            registerRaopService(effectiveName, generation)
        } catch (e: Exception) {
            // Don't stay "started" with nothing (or only half) registered: undo whatever did get
            // registered so a later start()/restart() begins from a clean slate.
            unregisterLocked()
            throw e
        }
    }

    /**
     * Stops mDNS advertising.
     *
     * Unregisters both mDNS services. The device disappears from sender pickers
     * within ~5-10 seconds (mDNS goodbye packet sent immediately, but senders cache briefly).
     *
     * Safe to call even if [start] was never called, and safe to call more than once or
     * concurrently. After it returns, [restart] does nothing until [start] is called again.
     */
    fun stop() {
        synchronized(lock) {
            stopped = true
            Logger.i("Stopping mDNS advertising")
            unregisterLocked()
            releaseMulticastLock()
        }
        onStateChange(ProtocolState.DISABLED)
    }

    /**
     * Restarts mDNS advertising.
     *
     * Used after a streaming session ends and when the network returns, to re-advertise the
     * device in sender pickers.
     *
     * A no-op after [stop] (until [start] is called again), so a callback that loses the race
     * against shutdown cannot resurrect the advertisement. The multicast lock stays held
     * across the restart — releasing it for the gap would let the radio drop the very queries
     * the new registration is waiting for.
     *
     * @param displayNameOverride Updated display name, if changed in Settings.
     */
    fun restart(displayNameOverride: String? = null) {
        synchronized(lock) {
            if (stopped) {
                Logger.d("MdnsService.restart() ignored — advertising was stopped")
                return
            }
            Logger.d("Restarting mDNS advertising")
            unregisterLocked()
            startLocked(displayNameOverride)
        }
        // Reported outside the lock so a state observer can never deadlock against us.
        // (Kept from the previous stop()+start() implementation: the restart is visible as a
        // DISABLED blip until both services confirm.)
        onStateChange(ProtocolState.DISABLED)
    }

    /**
     * Unregisters whichever listeners are currently registered. Each one is handled on its
     * own: an error unregistering one (already gone with its interface, or never accepted by
     * the daemon) must not leave the other registered, and is non-fatal — the service simply
     * expires via mDNS TTL. Always leaves the object in the "not registered" state.
     * Caller must hold [lock].
     */
    private fun unregisterLocked() {
        unregisterListener(airPlayListener, "_airplay._tcp")
        unregisterListener(raopListener, "_raop._tcp")
        airPlayListener = null
        raopListener = null
        registeredCount = 0
        isStarted = false
        generation++   // orphan any callbacks still in flight from the old registrations
    }

    private fun unregisterListener(listener: NsdManager.RegistrationListener?, label: String) {
        if (listener == null) return
        try {
            nsdManager.unregisterService(listener)
        } catch (e: Exception) {
            Logger.e("Error unregistering mDNS $label (non-fatal)", e)
        }
    }

    private fun acquireMulticastLock() {
        val lock = multicastLock ?: return
        if (!lock.isHeld) {
            runCatching { lock.acquire() }
                .onSuccess { Logger.d("Wi-Fi multicast lock acquired") }
                .onFailure { Logger.w("Could not acquire multicast lock: ${it.message}") }
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }
                .onSuccess { Logger.d("Wi-Fi multicast lock released") }
                .onFailure { Logger.w("Could not release multicast lock: ${it.message}") }
        }
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * Determines the effective name to advertise.
     * Uses [override] if non-blank; otherwise reads from the Android system.
     */
    private fun resolveDisplayName(override: String?): String {
        val trimmed = override?.trim() ?: ""
        return if (trimmed.isNotEmpty()) trimmed else NetworkUtils.getDeviceName(context)
    }

    /**
     * Registers the `_airplay._tcp` mDNS service.
     *
     * TXT records tell senders what features PhairPlay supports.
     * See TECHNICAL_SPEC.md §8 for bit-level breakdown of the `features` value.
     *
     * @param displayName The name shown in sender AirPlay pickers.
     */
    private fun registerAirPlayService(displayName: String, gen: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = displayName
            serviceType = SERVICE_TYPE_AIRPLAY
            port = AIRPLAY_PORT

            // Core identity TXT records
            setAttribute("deviceid", NetworkUtils.getMacAddress())
            setAttribute("features", videoTransport.featuresTxt)
            setAttribute("model", videoTransport.model)
            setAttribute("srcvers", videoTransport.sourceVersion)
            // Keep the legacy TXT record byte-for-byte compatible with the proven profile.
            // Protocol 1.1 is only announced when the experimental transport is selected.
            if (videoTransport.isModern) setAttribute("protovers", videoTransport.protocolVersion)
            setAttribute("vv", "2")                             // AirPlay protocol version 2
            setAttribute("pi", NetworkUtils.getPersistentUuid(context))

            // For MULTIROOM, advertise additional fields for multi-room audio capability
            if (videoTransport == com.phairplay.airplay.AirPlayVideoTransport.MULTIROOM) {
                setAttribute("flags", "0x204")                  // Screen-mirroring + multi-room receiver
                setAttribute("gid", NetworkUtils.getPersistentUuid(context))  // Group ID (same as device ID)
                setAttribute("psi", NetworkUtils.getPersistentUuid(context))  // Permanent source ID
                setAttribute("igl", "0")                        // Input group leader
                setAttribute("gcgl", "0")                       // Group contains group leader
                // pk and at are set from PairingKeys after context is available
                val pairingPublicKey = com.phairplay.airplay.handshake.PairingKeys.get(context).edPublic
                setAttribute("pk", pairingPublicKey.joinToString("") { "%02x".format(it) })
                setAttribute("at", "4")                         // Authentication type
                setAttribute("acl", "0")
                run {
                    val f = videoTransport.features
                    val b = ByteArray(8) { i -> ((f ushr (8 * i)) and 0xFF).toByte() }
                    setAttribute("fex", android.util.Base64.encodeToString(b, android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
                }
            } else {
                setAttribute("flags", "0x4")                    // Screen-mirroring receiver (legacy)
            }
        }

        airPlayListener = createRegistrationListener(
            serviceLabel = "_airplay._tcp",
            gen = gen,
            onRegisteredName = { actualName ->
                // Detect collision auto-renaming: NsdManager appended " (2)", " (3)", etc.
                if (actualName != requestedName) {
                    Logger.w("mDNS name collision detected: requested='$requestedName' " +
                             "actual='$actualName' — NsdManager resolved automatically")
                }
                onActualNameRegistered(actualName)
            },
            onSuccess = { incrementAndCheckBothRegistered(gen) },
            onFailure = { onStateChange(ProtocolState.ERROR) }
        )
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, airPlayListener!!)
    }

    /**
     * Registers the `_raop._tcp` mDNS service.
     *
     * RAOP (Remote Audio Output Protocol) is the audio component of AirPlay.
     * macOS and iOS require it even for screen mirroring — not only for audio-only streams.
     *
     * RAOP service name format required by the AirPlay protocol:
     *   `"<MACADDRESS_NOCOLONS>@<DeviceName>"`
     *   e.g., `"AABBCCDDEEFF@Living Room TV"`
     *
     * @param displayName The device name portion of the RAOP service name.
     */
    private fun registerRaopService(displayName: String, gen: Int) {
        val macHex = NetworkUtils.getMacAddress().replace(":", "").uppercase()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$macHex@$displayName"  // required RAOP format
            serviceType = SERVICE_TYPE_RAOP
            port = AIRPLAY_PORT

            setAttribute("cn", "0,1,2,3")        // Cipher numbers (encryption types)
            setAttribute("da", "true")             // Digest authentication capable
            setAttribute("et", "0,3,5")            // Encryption types supported
            setAttribute("md", "0,1,2")            // Metadata types supported
            setAttribute("sv", "false")            // Software volume control
            setAttribute("tp", "UDP")              // Transport for audio RTP
            setAttribute("vn", "65537")            // Version number (required)
            setAttribute("vs", if (videoTransport == com.phairplay.airplay.AirPlayVideoTransport.MULTIROOM) videoTransport.sourceVersion else AIRPLAY_SERVER_VERSION)
            setAttribute("am", if (videoTransport == com.phairplay.airplay.AirPlayVideoTransport.MULTIROOM) videoTransport.model else AirPlayFeatures.MODEL)

            // For MULTIROOM, add feature flags and public key
            if (videoTransport == com.phairplay.airplay.AirPlayVideoTransport.MULTIROOM) {
                setAttribute("ft", videoTransport.featuresTxt)  // Feature bits as comma-separated hex
                setAttribute("sf", "0x204")                    // Status flags
                val pairingPublicKey = com.phairplay.airplay.handshake.PairingKeys.get(context).edPublic
                setAttribute("pk", pairingPublicKey.joinToString("") { "%02x".format(it) })
                setAttribute("vv", "0")                        // Additional version info
            }
        }

        raopListener = createRegistrationListener(
            serviceLabel = "_raop._tcp",
            gen = gen,
            onRegisteredName = null,  // RAOP name has MAC prefix — not shown to users
            onSuccess = { incrementAndCheckBothRegistered(gen) },
            onFailure = { onStateChange(ProtocolState.ERROR) }
        )
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, raopListener!!)
    }

    /**
     * Emits [ProtocolState.ADVERTISING] only after both services have confirmed registration.
     * This prevents a brief "advertising" state where only one of the two required services
     * is live.
     */
    private fun incrementAndCheckBothRegistered(gen: Int) {
        val bothUp = synchronized(lock) {
            if (gen != generation) return   // superseded by restart()/stop() — not ours any more
            registeredCount++
            registeredCount >= 2
        }
        if (bothUp) onStateChange(ProtocolState.ADVERTISING)
    }

    /**
     * Creates an [NsdManager.RegistrationListener] with logging and callbacks.
     *
     * @param serviceLabel     Human-readable service type for log messages.
     * @param gen              Registration generation this listener belongs to; its callbacks
     *   are ignored once [generation] has moved on.
     * @param onRegisteredName Called with the actual registered service name (may differ from
     *   requested due to collision resolution). Pass null if the name is not user-visible.
     * @param onSuccess        Called on [onServiceRegistered].
     * @param onFailure        Called on [onRegistrationFailed].
     */
    private fun createRegistrationListener(
        serviceLabel: String,
        gen: Int,
        onRegisteredName: ((String) -> Unit)?,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ): NsdManager.RegistrationListener {
        return object : NsdManager.RegistrationListener {

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                // NsdManager may append " (2)" to resolve name conflicts.
                // Log the actual name so we can debug picker-visibility issues.
                if (gen != generation) {
                    Logger.d("mDNS $serviceLabel registration callback from a superseded cycle — ignoring")
                    return
                }
                Logger.i("mDNS registered: $serviceLabel as '${serviceInfo.serviceName}'")
                onRegisteredName?.invoke(serviceInfo.serviceName)
                onSuccess()
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (gen != generation) return   // a superseded cycle failing must not disturb the live one
                // Error codes from NsdManager:
                //   FAILURE_ALREADY_ACTIVE (3) — already registered; treat as success
                //   FAILURE_MAX_LIMIT (4)      — too many services (should not happen)
                //   FAILURE_INTERNAL_ERROR (0) — system mDNS daemon issue
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    Logger.w("mDNS $serviceLabel already active — treating as success")
                    onSuccess()
                } else {
                    Logger.e("mDNS registration FAILED for $serviceLabel, errorCode=$errorCode")
                    isStarted = false
                    onFailure()
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Logger.d("mDNS unregistered: $serviceLabel")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Non-fatal: the service will expire via mDNS TTL (~4500ms by default)
                Logger.w("mDNS unregistration failed for $serviceLabel, errorCode=$errorCode (non-fatal)")
            }
        }
    }

    companion object {
        /** Standard mDNS service type for AirPlay receivers. */
        private const val SERVICE_TYPE_AIRPLAY = "_airplay._tcp"

        /** Standard mDNS service type for RAOP (audio). Required alongside AirPlay. */
        private const val SERVICE_TYPE_RAOP = "_raop._tcp"

        /** AirPlay RTSP port — [RtspHandler] must listen on this port. */
        const val AIRPLAY_PORT = 7000

        // Feature bitmask and model come from [AirPlayFeatures]; the same values are used by
        // InfoResponder and RtspHandler.handleServerInfo.

        /** AirPlay server version — matches a real Apple TV for maximum compatibility. */
        private const val AIRPLAY_SERVER_VERSION = "220.68"
    }
}
