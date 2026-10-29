package com.phairplay.airplay.handshake

import android.content.Context
import com.phairplay.util.NetworkUtils

/**
 * InfoResponder — builds the binary-plist body for `GET /info`, the first request a macOS
 * AirPlay sender makes. It advertises the receiver's identity and capability bits so the
 * sender knows to continue with pairing → FairPlay → mirroring.
 *
 * Values are kept consistent with what [com.phairplay.airplay.MdnsService] advertises so the
 * sender sees one coherent device.
 *
 * NOTE: the Ed25519 public key (`pk`) is added in the pairing phase once a persistent
 * identity exists; macOS still proceeds to pair-setup without it.
 */
object InfoResponder {

    /**
     * Output latency advertised to multi-room senders, µs. Rough estimate of the AudioTrack path measured on the
     * projector (~125-144 ms buffer) — it does NOT include HDMI-ARC / soundbar processing, which is unknown.
     */
    const val MULTIROOM_OUTPUT_LATENCY_MICROS = 150_000L

    fun build(
        context: Context,
        width: Int = 1920,
        height: Int = 1080,
        pinRequired: Boolean = false,
        model: String = com.phairplay.airplay.AirPlayFeatures.MODEL,
        sourceVersion: String = SOURCE_VERSION,
        protocolVersion: String = "1.1",
        features: Long = com.phairplay.airplay.AirPlayFeatures.LONG,
        isMultiRoom: Boolean = false,
        ptpInfo: Boolean = false,
    ): ByteArray {
        val mac = NetworkUtils.getMacAddress()
        // When PIN access control is on, set the "pairing/PIN required" status bit so the sender runs
        // the SRP pair-setup flow. NOTE: exact flag semantics are sender-version-dependent — verify
        // against macOS and adjust if pairing doesn't trigger.
        // For MULTIROOM, statusFlags should be 516 (0x204) for multi-room receivers.
        val statusFlags = when {
            isMultiRoom -> 516L  // Multi-room receiver status flags
            pinRequired -> STATUS_FLAGS or STATUS_FLAG_PIN_REQUIRED
            else -> STATUS_FLAGS
        }

        val baseInfo = mapOf(
            "deviceID" to mac,
            "macAddress" to mac,
            "features" to features,
            "statusFlags" to statusFlags,
            "model" to model,
            "name" to NetworkUtils.getDeviceName(context),
            "sourceVersion" to sourceVersion,
            "pi" to NetworkUtils.getPersistentUuid(context),
            "pk" to PairingKeys.get(context).edPublic,
            "vv" to 2L,
            // Fixed volume (Roku-style, volumeControlType=1) in every profile: the sender's volume is
            // ignored and the remote/soundbar owns the level.
            "volumeControlType" to if (isMultiRoom) MULTIROOM_VOLUME_CONTROL_TYPE else 1L,
            "protovers" to protocolVersion,
            "keepAliveLowPower" to true,
            "keepAliveSendStatsAsBody" to true,
            // NOTE: macOS IGNORES this for system-audio AirPlay — it sends ALAC (ct=2) regardless of
            // what we advertise (verified: advertising AAC-only still got ALAC). So we keep the broad
            // set (mirroring negotiates AAC-ELD from it, which works). Audio-only would need a
            // software ALAC decoder since this TV has no hardware ALAC codec.
            "audioFormats" to listOf(
                mapOf("type" to 100L, "audioInputFormats" to 67108860L, "audioOutputFormats" to 67108860L),
                mapOf("type" to 101L, "audioInputFormats" to 67108860L, "audioOutputFormats" to 67108860L)
            ),
            "audioLatencies" to listOf(
                mapOf("type" to 100L, "audioType" to "default", "inputLatencyMicros" to 0L, "outputLatencyMicros" to 0L),
                mapOf("type" to 101L, "audioType" to "default", "inputLatencyMicros" to 0L, "outputLatencyMicros" to 0L)
            ),
            // Screen the sender can mirror to — without this, macOS aborts after key setup.
            "displays" to listOf(
                mapOf(
                    "uuid" to "e0ff8a27-6738-3d56-8a16-cc53aacee925",
                    "widthPhysical" to 0L,
                    "heightPhysical" to 0L,
                    "width" to width.toLong(),
                    "height" to height.toLong(),
                    "widthPixels" to width.toLong(),
                    "heightPixels" to height.toLong(),
                    "rotation" to false,
                    "refreshRate" to (1.0 / 60.0),
                    "overscanned" to false,   // false = macOS uses the full advertised resolution
                    "features" to 14L
                )
            )
        )

        // For MULTIROOM, add multi-room specific fields
        val info = if (isMultiRoom) {
            baseInfo + (if (ptpInfo) mapOf("PTPInfo" to mapOf("SupportsClockPortMatchingOverride" to true)) else emptyMap()) + mapOf(
                // Multi-room only: report the projector's own output latency (instead of 0) so the sender can
                // schedule us accordingly. Approximate; excludes HDMI-ARC/soundbar.
                "audioLatencies" to listOf(
                    mapOf("type" to 100L, "audioType" to "default", "inputLatencyMicros" to 0L, "outputLatencyMicros" to MULTIROOM_OUTPUT_LATENCY_MICROS),
                    mapOf("type" to 101L, "audioType" to "default", "inputLatencyMicros" to 0L, "outputLatencyMicros" to MULTIROOM_OUTPUT_LATENCY_MICROS)
                ),
                "supportedFormats" to mapOf(
                    "bufferStream" to 12582912L,
                    "audioStream" to 21235712L,
                    "screenStream" to 21235712L,
                    "lowLatencyAudioStream" to 0L
                ),
                "supportedAudioFormatsExtended" to mapOf(
                    "bufferStream" to listOf(22L, 23L)  // Supported audio format bits
                ),
                "playbackCapabilities" to mapOf(
                    "supportsAirPlayVideoWithSharePlay" to false,
                    "supportsOfflineHLS" to false,
                    "supportsAVMetrics" to false,
                    "supportsV2ArtworkMetadata" to false,
                    "supportsUIForAudioOnlyContent" to true,
                    "supportsStopAtEndOfQueue" to false,
                    "supportsCoordinatedMultiViewPlayback" to false,
                    "supportsFPSSecureStop" to false
                ),
                "psi" to NetworkUtils.getPersistentUuid(context)  // Permanent source ID (same as pi for now)
            )
        } else {
            baseInfo
        }

        return PlistCodec.encode(info)
    }

    /** Matches RPiPlay's /info statusFlags (0x44). */
    private const val STATUS_FLAGS = 68L

    /** Status bit advertising that the receiver requires PIN pairing (0x8 — verify vs macOS). */
    private const val STATUS_FLAG_PIN_REQUIRED = 0x8L

    private const val SOURCE_VERSION = "220.68"
}

/** Experiment: multi-room advertises "no volume control" (0). Set to 1 to roll back to Roku-style fixed volume. */
private const val MULTIROOM_VOLUME_CONTROL_TYPE = 0L
