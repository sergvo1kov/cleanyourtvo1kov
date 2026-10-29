package com.phairplay.airplay

import android.media.AudioTimestamp
import android.media.AudioTrack
import com.phairplay.util.Logger
import kotlin.math.abs

/**
 * Static wiring point for multi-room playout. The integrating code sets [clock] (PTP slave) and calls
 * [setAnchor] on every SETRATEANCHORTIME *before* forwarding the rate to the audio classes.
 */
object MultiroomPtp {
    @Volatile var clock: PtpClock? = null
    @Volatile var anchor: PlayoutAnchor? = null

    fun setAnchor(rate: Float, rtpTime: Long, networkTimeSecs: Long, networkTimeFrac: Long, timelineId: Long, flags: Long = 0) {
        anchor = PlayoutAnchor(rate, rtpTime and 0xFFFFFFFFL, PlayoutAnchor.masterNanos(networkTimeSecs, networkTimeFrac), timelineId, flags)
    }

    fun clearAnchor() { anchor = null }
}

/** SETRATEANCHORTIME: at master (PTP) time [masterNanos] on timeline [timelineId], the frame with RTP timestamp [rtpTime] is audible. */
data class PlayoutAnchor(val rate: Float, val rtpTime: Long, val masterNanos: Long, val timelineId: Long, val flags: Long = 0) {
    companion object {
        /** secs + 64-bit binary fraction (treated as unsigned) -> nanoseconds. */
        fun masterNanos(secs: Long, frac: Long): Long = secs * 1_000_000_000L + (((frac ushr 32) * 1_000_000_000L) ushr 32)
    }
}

/** A presentation timestamp from the output: frame index [frame] was/will be audible at local [nanos] (System.nanoTime). */
class FrameStamp(val frame: Long, val nanos: Long)

/** What the scheduler needs from an audio output (AudioTrack on device, a fake in tests). Frames are counted from the last flush/creation. */
interface PlayoutSink {
    fun write(buf: ByteArray, off: Int, len: Int)
    fun timestamp(): FrameStamp?
    fun headFrames(): Long
    /** Output-path latency beyond the queued frames, ns; used only until real timestamps are available. */
    fun latencyNanos(): Long
    /** Drop everything queued and restart frame counting at 0. */
    fun flush()
}

/**
 * Aligns PCM written to an output with the PTP timeline. Pure logic (no Android): for each block
 * it predicts when the first frame would become audible and compares it with the anchor-derived target;
 * the difference is fixed by writing silence (too early) or dropping frames (too late).
 * [process] returns false when PTP is not usable (caller then runs its legacy start-delay logic and must
 * report its writes through [noteWritten]).
 */
class PlayoutScheduler(
    private val sampleRate: Int,
    private val nowNanos: () -> Long = System::nanoTime,
    private val clockProvider: () -> PtpClock? = { MultiroomPtp.clock ?: MultiroomClockSource.clock },
    private val anchorProvider: () -> PlayoutAnchor? = { MultiroomPtp.anchor },
    private val log: (String) -> Unit = { Logger.i(it) },
) {
    @Volatile private var resetRequested = false
    private var written = 0L            // frames handed to the sink since its last flush
    private var aligned = false
    private var fadeInNext = false
    private val offsets = ArrayDeque<Long>()   // nanos at which frame 0 is audible, from recent stamps
    private var lastStampAt = -1L
    var corrections = 0; private set
    var lastErrorNanos = 0L; private set
    val isAligned get() = aligned

    private val bytesPerFrame = 4
    private val fadeFrames = sampleRate * 4 / 1000

    /** Start alignment over (rate 0 -> 1, new playback). Thread-safe. */
    fun requestReset() { resetRequested = true }

    /** The sink was flushed by someone else. */
    fun onFlushed() { written = 0; aligned = false; offsets.clear(); lastStampAt = -1L }

    /** Account frames written outside [process] (legacy path, loss-concealment silence). */
    fun noteWritten(frames: Int) { written += frames }

    fun isActive(): Boolean = activeClock() != null

    private fun activeClock(): PtpClock? {
        val c = clockProvider() ?: return null
        val a = anchorProvider() ?: return null
        return if (c.synced && a.rate > 0f && (c.clockId == 0L || c.clockId == a.timelineId)) c else null
    }

    private fun audibleNanos(frame: Long, sink: PlayoutSink): Long =
        if (offsets.size >= 3) median() + frame * 1_000_000_000L / sampleRate
        else nowNanos() + (frame - sink.headFrames()) * 1_000_000_000L / sampleRate + sink.latencyNanos()

    private fun median(): Long = offsets.sorted()[offsets.size / 2]

    private fun sampleStamp(sink: PlayoutSink) {
        val now = nowNanos()
        if (lastStampAt >= 0 && now - lastStampAt < 200_000_000L) return
        lastStampAt = now
        val s = sink.timestamp() ?: return
        if (s.frame <= 0 || s.frame > written) return
        offsets.addLast(s.nanos - s.frame * 1_000_000_000L / sampleRate)
        if (offsets.size > 9) offsets.removeFirst()
    }

    fun process(pcm: ByteArray, rtp: Long, sink: PlayoutSink): Boolean {
        if (resetRequested) { resetRequested = false; onFlushed() }
        val clock = activeClock()
        val anchor = anchorProvider()
        if (clock == null || anchor == null) { aligned = false; return false }
        val frames = pcm.size / bytesPerFrame
        val dRtp = (rtp - anchor.rtpTime).toInt().toLong()           // 32-bit wrap
        val master = anchor.masterNanos + dRtp * 1_000_000_000L / sampleRate
        val target = clock.localNanosForMaster(master) ?: run { aligned = false; return false }

        sampleStamp(sink)
        var err = audibleNanos(written, sink) - target
        if (aligned && abs(err) > REALIGN_NANOS) {
            log("PS: error ${err / 1_000_000}ms > 200ms, realigning")
            sink.flush(); onFlushed()
            err = audibleNanos(written, sink) - target
        }
        val threshold = if (!aligned) START_NANOS else if (offsets.size >= 3) DRIFT_NANOS else Long.MAX_VALUE
        var silence = 0
        var drop = 0
        if (abs(err) > threshold) {
            val ef = (err * sampleRate / 1_000_000_000L)
            if (ef > 0) drop = minOf(ef, frames.toLong()).toInt() else silence = minOf(-ef, MAX_SILENCE_FRAMES.toLong()).toInt()
            if (aligned) { corrections++; log("PS: drift correction err=${err / 1000}us ${if (drop > 0) "drop=$drop" else "silence=$silence"} frames") }
            else log("PS: start alignment err=${err / 1000}us ${if (drop > 0) "drop=$drop" else "silence=$silence"} frames")
        }
        lastErrorNanos = err
        aligned = drop < frames       // a block dropped entirely: the late start is not corrected yet
        if (silence > 0) {
            val s = ByteArray(silence * bytesPerFrame)
            sink.write(s, 0, s.size); written += silence
            fadeInNext = true
        }
        if (drop > 0) fadeInNext = true
        val keep = frames - drop
        if (keep <= 0) return true
        val out = pcm.copyOfRange(drop * bytesPerFrame, frames * bytesPerFrame)
        if (fadeInNext) { fadeIn(out, minOf(fadeFrames, keep)); fadeInNext = false }
        sink.write(out, 0, out.size); written += keep
        return true
    }

    private fun fadeIn(buf: ByteArray, n: Int) {
        for (k in 0 until n) {
            val g = k.toFloat() / n
            for (ch in 0..1) {
                val p = k * 4 + ch * 2
                val v = ((buf[p].toInt() and 0xFF) or (buf[p + 1].toInt() shl 8)).toShort().toInt()
                val o = (v * g).toInt()
                buf[p] = o.toByte(); buf[p + 1] = (o shr 8).toByte()
            }
        }
    }

    companion object {
        const val START_NANOS = 1_000_000L
        const val DRIFT_NANOS = 15_000_000L
        const val REALIGN_NANOS = 200_000_000L
        const val MAX_SILENCE_FRAMES = 10 * 48000
    }
}

/** [PlayoutSink] over an AudioTrack (frames counted since creation / last flush). */
internal class AudioTrackSink(private val track: AudioTrack, private val sampleRate: Int) : PlayoutSink {
    private val ts = AudioTimestamp()
    private val latencyNs: Long by lazy {
        // AudioTrack.getLatency() is @hide: it includes the buffer, which the head-position math already counts.
        runCatching {
            val ms = AudioTrack::class.java.getMethod("getLatency").invoke(track) as Int
            maxOf(0L, ms - track.bufferSizeInFrames * 1000L / sampleRate) * 1_000_000L
        }.getOrDefault(0L)
    }

    override fun write(buf: ByteArray, off: Int, len: Int) { track.write(buf, off, len, AudioTrack.WRITE_BLOCKING) }
    override fun timestamp(): FrameStamp? = if (track.getTimestamp(ts)) FrameStamp(ts.framePosition, ts.nanoTime) else null
    override fun headFrames(): Long = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
    override fun latencyNanos(): Long = latencyNs
    override fun flush() { runCatching { track.pause(); track.flush(); track.play() } }
}
