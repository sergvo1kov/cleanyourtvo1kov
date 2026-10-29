package com.phairplay.airplay

/** Receiver identity used for URL-video negotiation. Legacy remains the safe default. */
enum class AirPlayVideoTransport(
    val model: String,
    val sourceVersion: String,
    val protocolVersion: String,
    val featuresLow: Long,
    private val baseFeaturesHigh: Long,
) {
    LEGACY("AppleTV3,2", "220.68", "1.0", AirPlayFeatures.LOW, AirPlayFeatures.HIGH),
    // Keep the experimental identity separate; do not claim Roku's unsupported feature mask.
    AIRPLAY_2("AppleTV14,1", "377.40.00", "1.1", AirPlayFeatures.LOW, AirPlayFeatures.HIGH),
    // Multi-room audio (AirPlay 2 audio-only): same model/version as LEGACY,
    // but featuresHigh includes multi-room capability bits (40,41,48 = 0x10300; 38/43/46 not claimed: pairing not implemented).
    MULTIROOM("AppleTV14,1", "377.40.00", "1.1", AirPlayFeatures.LOW, 0x10300L);

    /** MULTIROOM drops bit 41 (PTP) when `debug.phairplay.nobit41=1` so the sender negotiates NTP timing. */
    val featuresHigh: Long get() = if (this == MULTIROOM && noBit41) baseFeaturesHigh and (1L shl 9).inv() else baseFeaturesHigh

    val isModern: Boolean get() = this in setOf(AIRPLAY_2, MULTIROOM)
    val features: Long get() = (featuresHigh shl 32) or featuresLow
    val featuresTxt: String get() = "0x%X,0x%X".format(featuresLow, featuresHigh)
}
