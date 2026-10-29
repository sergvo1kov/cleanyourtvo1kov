package com.phairplay.airplay

/**
 * NowPlayingInfo — what the audio-only "now playing" screen shows while audio (and no video) is
 * streaming. [senderName] is always present; the track metadata and [artwork] are filled in only
 * when the sender pushes them (Apple Music / Podcasts do; raw system audio from Chrome does not, so
 * the screen falls back to a generic "Audio from <sender>" card).
 */
data class NowPlayingInfo(
    val senderName: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artwork: ByteArray? = null,
    /** Track position/length when the sender reports it (SET_PARAMETER "progress"), else null. */
    val progress: PlaybackProgress? = null,
    /** True while the sender has paused playback (SETRATEANCHORTIME rate 0). */
    val paused: Boolean = false,
    /** True for an AirPlay 2 multi-room (PTP) session: Back hides the card, audio delay is adjustable. */
    val multiRoom: Boolean = false,
) {
    /** True when the sender supplied at least a track title (vs. a bare "audio is playing" state). */
    val hasMetadata: Boolean get() = !title.isNullOrBlank()

    // ByteArray needs hand-written equals/hashCode for value semantics in a StateFlow.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NowPlayingInfo) return false
        return senderName == other.senderName &&
            title == other.title &&
            artist == other.artist &&
            album == other.album &&
            artwork.contentEquals(other.artwork) &&
            progress == other.progress &&
            paused == other.paused
    }

    override fun hashCode(): Int {
        var result = senderName.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (artwork?.contentHashCode() ?: 0)
        result = 31 * result + (progress?.hashCode() ?: 0)
        result = 31 * result + paused.hashCode()
        return result
    }
}

/**
 * Track position [positionMs] as of [anchorElapsedMs] (SystemClock.elapsedRealtime), plus the track
 * length. While playing, the position advances in real time from the anchor; while paused it holds.
 */
data class PlaybackProgress(
    val positionMs: Long,
    val durationMs: Long,
    val anchorElapsedMs: Long,
) {
    fun positionAt(nowElapsedMs: Long, paused: Boolean): Long {
        val pos = if (paused) positionMs else positionMs + (nowElapsedMs - anchorElapsedMs)
        return pos.coerceIn(0L, durationMs)
    }
}

/**
 * DmapParser — minimal reader for the DMAP/DAAP "now playing" metadata macOS sends in a binary
 * `SET_PARAMETER` body (`Content-Type: application/x-dmap-tagged`).
 *
 * DMAP is a flat TLV stream: a 4-byte ASCII tag, a 4-byte big-endian length, then that many payload
 * bytes. Container tags (e.g. `mlit`, a listing item) hold nested tags. We only need three string
 * fields — track ([minm]), artist ([asar]), album ([asal]) — so we walk the tree and pick them out
 * wherever they appear, ignoring everything else.
 *
 * Reference: DAAP/DMAP content codes (minm/asar/asal) used by AirPlay now-playing.
 */
object DmapParser {

    private val CONTAINER_TAGS = setOf("mlit", "mlcl", "mshl", "adbs")
    private const val TRACK_TAG = "minm"
    private const val ARTIST_TAG = "asar"
    private const val ALBUM_TAG = "asal"

    /** [state] from the `caps` tag, when sent: an iPhone sends 1 while playing and 2 when paused. */
    data class Metadata(val title: String?, val artist: String?, val album: String?, val state: Int? = null,
                        val tags: String = "") {
        val isEmpty: Boolean get() = title == null && artist == null && album == null
    }

    fun parseNowPlaying(body: ByteArray): Metadata {
        val acc = MutableMetadata()
        runCatching { walk(body, 0, body.size, acc, depth = 0) }
        return Metadata(acc.title, acc.artist, acc.album, acc.state, acc.tags.toString())
    }

    private class MutableMetadata {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var state: Int? = null
        val tags = StringBuilder()
    }

    private fun walk(buf: ByteArray, start: Int, end: Int, acc: MutableMetadata, depth: Int) {
        if (depth > MAX_DEPTH) return
        var i = start
        while (i + 8 <= end) {
            val tag = String(buf, i, 4, Charsets.US_ASCII)
            val len = readBE32(buf, i + 4)
            val payloadStart = i + 8
            // Malformed/overrun length → stop scanning this level rather than reading out of bounds.
            if (len < 0 || payloadStart + len > end) break
            if (acc.tags.length < 400) acc.tags.append(tag).append(if (len in 1..4) "=" + readSmall(buf, payloadStart, len) else "").append(' ')
            when (tag) {
                "caps" -> if (len >= 1) acc.state = readSmall(buf, payloadStart, len)
                TRACK_TAG  -> if (acc.title == null)  acc.title  = utf8(buf, payloadStart, len)
                ARTIST_TAG -> if (acc.artist == null) acc.artist = utf8(buf, payloadStart, len)
                ALBUM_TAG  -> if (acc.album == null)  acc.album  = utf8(buf, payloadStart, len)
                in CONTAINER_TAGS -> walk(buf, payloadStart, payloadStart + len, acc, depth + 1)
                else -> { /* unknown tag — skip its payload */ }
            }
            i = payloadStart + len
        }
    }

    private fun readSmall(buf: ByteArray, start: Int, len: Int): Int {
        var v = 0
        for (k in 0 until minOf(len, 4)) v = (v shl 8) or (buf[start + k].toInt() and 0xFF)
        return v
    }

    private fun utf8(buf: ByteArray, start: Int, len: Int): String? =
        String(buf, start, len, Charsets.UTF_8).trim().ifEmpty { null }

    private fun readBE32(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
        ((buf[off + 1].toInt() and 0xFF) shl 16) or
        ((buf[off + 2].toInt() and 0xFF) shl 8) or
        (buf[off + 3].toInt() and 0xFF)

    private const val MAX_DEPTH = 8
}
