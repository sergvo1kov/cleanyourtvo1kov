package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PlayoutSchedulerTest {
    private val sr = 48000
    private val blk = 1024

    private class FakeClock(synced: Boolean = true, override var clockId: Long = 7L, var offset: Long = 5_000_000_000L) : PtpClock {
        override val synced get() = _s
        private var _s = synced
        fun setSynced(v: Boolean) { _s = v }
        override fun masterNanosForLocal(localNanos: Long) = if (_s) localNanos + offset else null
        override fun localNanosForMaster(masterNanos: Long) = if (_s) masterNanos - offset else null
        override val pathDelayNanos: Long? = 0
    }

    /** Output that plays written frames in real (sim) time at sr*(1+ppm) after [pathNs] latency; no output until first write. */
    private class FakeSink(val sr: Int, val now: () -> Long, val pathNs: Long = 80_000_000L, val ppm: Double = 0.0, val stamps: Boolean = true) : PlayoutSink {
        var written = 0L
        var t0 = Long.MIN_VALUE         // time frame 0 starts
        var flushes = 0
        override fun write(buf: ByteArray, off: Int, len: Int) {
            if (t0 == Long.MIN_VALUE) t0 = now() + pathNs
            written += len / 4
        }
        fun pos(): Long = if (t0 == Long.MIN_VALUE || now() < t0) 0 else minOf(written, ((now() - t0) * sr * (1 + ppm * 1e-6) / 1e9).toLong())
        override fun timestamp() = if (stamps && pos() > 0) FrameStamp(pos(), now()) else null
        override fun headFrames() = pos()
        override fun latencyNanos() = pathNs
        override fun flush() { written = 0; t0 = Long.MIN_VALUE; flushes++ }
        /** Local time at which frame index f becomes audible. */
        fun audibleAt(f: Long) = t0 + (f * 1e9 / (sr * (1 + ppm * 1e-6))).toLong()
    }

    private var t = 1_000_000_000_000L
    private val now = { t }
    private fun pcm(frames: Int = blk) = ByteArray(frames * 4) { 1 }

    private class Rig(val clock: FakeClock, var anchor: PlayoutAnchor?, val sch: PlayoutScheduler, val sink: FakeSink)

    private fun rig(anchor: PlayoutAnchor?, clock: FakeClock = FakeClock(), ppm: Double = 0.0, stamps: Boolean = true): Rig {
        val sink = FakeSink(sr, now, ppm = ppm, stamps = stamps)
        lateinit var r: Rig
        val sch = PlayoutScheduler(sr, now, { clock }, { r.anchor }, { })
        r = Rig(clock, anchor, sch, sink)
        return r
    }

    /** Feed blocks of the stream (rtp from [rtp0]) whenever the sink buffer drops below 100 ms, for [seconds] of sim time. */
    private fun run(r: Rig, rtp0: Long, seconds: Double): Long {
        var rtp = rtp0
        val tStart = t
        val end = t + (seconds * 1e9).toLong()
        while (t < end) {
            // sender supplies media at real-time pace plus a 1 s lead
            val supplied = rtp0 + (t - tStart) * sr / 1_000_000_000L + sr
            if (r.sink.written - r.sink.pos() < sr / 10 && rtp < supplied) {
                if (!r.sch.process(pcm(), rtp and 0xFFFFFFFFL, r.sink)) { r.sch.noteWritten(blk); r.sink.write(pcm(), 0, blk * 4) }
                rtp += blk
            }
            t += 1_000_000L
        }
        return rtp
    }

    /** Master time that frame with [rtp] should be audible at, given the anchor, as local time. */
    private fun target(r: Rig, rtp: Long) = r.clock.localNanosForMaster(r.anchor!!.masterNanos + (rtp - r.anchor!!.rtpTime) * 1_000_000_000L / sr)!!

    @Test fun anchorConversion() {
        assertEquals(12_500_000_000L, PlayoutAnchor.masterNanos(12, 1L shl 63))
        assertEquals(1_000_000_000L + 750_000_000L, PlayoutAnchor.masterNanos(1, (3L shl 62)))
    }

    @Test fun startsAtTargetTime() {
        val clock = FakeClock(offset = 5_000_000_000L)
        // frame rtp=1000 must be audible 1.5 s from now (local), expressed in master time
        val anchor = PlayoutAnchor(1f, 1000, t + 1_500_000_000L + clock.offset, 7L)
        val r = rig(anchor, clock)
        val rtp = run(r, 1000, 3.0)
        // the first written frame (index 0 = rtp 1000 unless dropped) audible time vs target
        val err = r.sink.audibleAt(r.sink.written - 1) - target(r, rtp - 1)
        assertTrue("start error ${err / 1000}us", abs(err) < 2_000_000)
    }

    @Test fun lateStartDropsFrames() {
        val clock = FakeClock()
        val start = t
        // rtp 0 should have been audible 300 ms ago -> first 300 ms must be dropped; blocks keep coming
        val anchor = PlayoutAnchor(1f, 0, start - 300_000_000L + clock.offset, 7L)
        val r = rig(anchor, clock)
        val rtp = run(r, 0, 2.0)
        // frame index i carries rtp = dropped + i (drop is contiguous at start): check last written frame
        val lastRtp = rtp - 1
        val dropped = lastRtp + 1 - r.sink.written
        val err = r.sink.audibleAt(r.sink.written - 1) - target(r, lastRtp)
        assertTrue("dropped=$dropped err=${err / 1000}us", dropped > 0.25 * sr && abs(err) < 3_000_000)
    }

    @Test fun driftCorrectionKeepsErrorBounded() {
        val clock = FakeClock()
        val anchor = PlayoutAnchor(1f, 0, t + 500_000_000L + clock.offset, 7L)
        val r = rig(anchor, clock, ppm = 30.0)
        var rtp = 0L
        var maxErr = 0L
        repeat(120) {              // 20 minutes
            rtp = run(r, rtp, 10.0)
            if (it > 2) maxErr = maxOf(maxErr, abs(r.sink.audibleAt(r.sink.written) - target(r, rtp)))
        }
        // 30 ppm over 1200 s = 36 ms of drift: must have been corrected, never beyond threshold + one block
        assertTrue("corrections=${r.sch.corrections} lastErr=${r.sch.lastErrorNanos} aligned=${r.sch.isAligned}", r.sch.corrections >= 1)
        assertTrue("maxErr=${maxErr / 1000}us", maxErr < 20_000_000)
        assertEquals(0, r.sink.flushes)
    }

    @Test fun seekRealigns() {
        val clock = FakeClock()
        val anchor = PlayoutAnchor(1f, 0, t + 500_000_000L + clock.offset, 7L)
        val r = rig(anchor, clock)
        var rtp = run(r, 0, 3.0)
        // seek: new anchor, 10 s of media later, to be audible 400 ms from now
        val newRtp = rtp + 10L * sr
        r.anchor = PlayoutAnchor(1f, newRtp, t + 400_000_000L + clock.offset, 7L)
        rtp = run(r, newRtp, 2.0)
        assertTrue(r.sink.flushes >= 1)
        val err = r.sink.audibleAt(r.sink.written - 1) - target(r, rtp - 1)
        assertTrue("err=${err / 1000}us", abs(err) < 3_000_000)
    }

    @Test fun masterChangeFallsBackThenAligns() {
        val clock = FakeClock()
        val anchor = PlayoutAnchor(1f, 0, t + 500_000_000L + clock.offset, 7L)
        val r = rig(anchor, clock)
        val rtp = run(r, 0, 1.0)
        assertTrue(r.sch.isActive())
        clock.clockId = 8L                        // new grandmaster: anchor timeline no longer matches
        assertFalse(r.sch.isActive())
        assertFalse(r.sch.process(pcm(), rtp, r.sink))
        clock.clockId = 7L
        assertTrue(r.sch.isActive())
    }

    @Test fun fallbackWhenNotSyncedOrNoClock() {
        val clock = FakeClock()
        clock.setSynced(false)
        val anchor = PlayoutAnchor(1f, 0, t + clock.offset, 7L)
        val r = rig(anchor, clock)
        assertFalse(r.sch.process(pcm(), 0, r.sink))
        assertEquals(0L, r.sink.written)
        val none = PlayoutScheduler(sr, now, { null }, { anchor }, { })
        assertFalse(none.process(pcm(), 0, r.sink))
        clock.setSynced(true)
        val noAnchor = PlayoutScheduler(sr, now, { clock }, { null }, { })
        assertFalse(noAnchor.process(pcm(), 0, r.sink))
        val paused = PlayoutScheduler(sr, now, { clock }, { anchor.copy(rate = 0f) }, { })
        assertFalse(paused.process(pcm(), 0, r.sink))
    }

    @Test fun resetRealignsAfterPause() {
        val clock = FakeClock()
        val anchor = PlayoutAnchor(1f, 0, t + 500_000_000L + clock.offset, 7L)
        val r = rig(anchor, clock)
        var rtp = run(r, 0, 2.0)
        // pause 3 s (rate 0): output flushed by owner, anchor re-sent so that rtp resumes 500 ms after resume
        r.sink.flush(); r.sch.requestReset()
        t += 3_000_000_000L
        r.anchor = PlayoutAnchor(1f, rtp, t + 500_000_000L + clock.offset, 7L)
        val f0 = r.sink.flushes
        rtp = run(r, rtp, 2.0)
        assertEquals(f0, r.sink.flushes)
        val err = r.sink.audibleAt(r.sink.written - 1) - target(r, rtp - 1)
        assertTrue("err=${err / 1000}us", abs(err) < 3_000_000)
    }
}
