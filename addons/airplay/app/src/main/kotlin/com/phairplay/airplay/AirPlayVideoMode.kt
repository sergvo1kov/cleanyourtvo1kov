package com.phairplay.airplay

/**
 * The AirPlay `features` bitmask and model we advertise (mDNS TXT, `/info`, `/server-info`).
 *
 * Bits (see openairplay spec): 0 Video, 1 Photo, 2 VideoFairPlay, 4 HTTP live streams, 5 slideshow, 7 screen
 * mirroring, 9 audio, 14 FairPlay auth, 15-17 metadata, 18-21 audio formats, 27 legacy pairing, 30 RAOP.
 *
 * Match UxPlay with its `-hls` option enabled: the default mask is 0x5A7FFEE6, then bits 0 (video) and 4
 * (HLS) are enabled. Bit 27 (legacy pairing) stays enabled. Its HTTP `/server-info` deliberately advertises
 * the smaller legacy video mask separately.
 */
object AirPlayFeatures {
    const val LOW = 0x5A7FFEF7L
    const val HIGH = 0x0L

    /** mDNS TXT `features` value: "0x<low>,0x<high>". */
    val TXT: String = "0x%X,0x%X".format(LOW, HIGH)

    /** The same bitmask as one 64-bit value (for the binary-plist `features` in `/info`). */
    const val LONG: Long = (HIGH shl 32) or LOW

    const val MODEL = "AppleTV3,2"
}
