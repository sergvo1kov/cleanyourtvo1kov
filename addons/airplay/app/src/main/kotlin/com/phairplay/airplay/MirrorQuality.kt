package com.phairplay.airplay

import com.phairplay.util.Logger

/**
 * MirrorQuality — the display size we advertise to mirroring senders (`/info` → `displays`), which is what
 * makes macOS/iOS choose the stream resolution.
 *
 * Starts at the highest tier and steps DOWN when a mirror session cannot sustain its frame rate (see
 * [MirrorStreamServer]'s stability check): the sender re-reads `/info` on every new connection, so a lower
 * tier takes effect the next time the sender (re)connects. There is deliberately no automatic step back up —
 * a flapping resolution is worse than a stable lower one. Restarting the app resets to the top tier.
 *
 * Tiers: the projector's panel/output is 1080p, but its decoders accept up to 4096×2304 (H.264), and a larger
 * source downscaled by the decoder still looks crisper than the sender scaling it itself.
 */
object MirrorQuality {
    private val tiers = listOf(3840 to 2160, 2560 to 1440, 1920 to 1080)

    @Volatile private var tierIndex = 0

    val width: Int get() = tiers[tierIndex].first
    val height: Int get() = tiers[tierIndex].second
    val isLowest: Boolean get() = tierIndex == tiers.lastIndex

    /** Steps one tier down (if possible). Returns true if the advertised size changed. */
    @Synchronized
    fun stepDown(reason: String): Boolean {
        if (isLowest) return false
        val from = "${width}x$height"
        tierIndex++
        Logger.w("MirrorQuality: $from → ${width}x$height ($reason) — applies from the next mirror connection")
        return true
    }
}
