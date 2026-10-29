package com.phairplay.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Random
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class NtpClockTest {
    private val MS = 1_000_000L

    /** Sender-side emulator: clock = local*(1+driftPpm*1e-6) + offset; random loss and return-path jitter. */
    private class Emulator(val offsetNs: Long, val driftPpm: Double, val lossPct: Int, val jitterMs: Int) : AutoCloseable {
        val sock = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val port = sock.localPort
        private val rnd = Random(1)
        @Volatile var run = true
        private val t0 = System.nanoTime()
        fun clock(): Long = 1_000_000_000_000L + offsetNs + ((System.nanoTime() - t0) * (1 + driftPpm * 1e-6)).toLong() + t0
        init {
            Thread {
                val b = ByteArray(64)
                while (run) try {
                    val p = DatagramPacket(b, b.size); sock.receive(p)
                    val t2 = clock()
                    if (rnd.nextInt(100) < lossPct) continue
                    if (jitterMs > 0) Thread.sleep(rnd.nextInt(jitterMs + 1).toLong())
                    val r = ByteArray(32); r[0] = 0x80.toByte(); r[1] = 0xD3.toByte(); r[2] = b[2]; r[3] = b[3]
                    System.arraycopy(b, 24, r, 8, 8)
                    put(r, 16, NtpClockAdapter.nanosToRaw(t2)); put(r, 24, NtpClockAdapter.nanosToRaw(clock()))
                    sock.send(DatagramPacket(r, 32, p.address, p.port))
                } catch (_: Exception) { }
            }.also { it.isDaemon = true }.start()
        }
        private fun put(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v ushr (56 - 8 * i)).toByte() }
        override fun close() { run = false; sock.close() }
    }

    private fun runAdapter(emu: Emulator, millis: Long): Pair<Long, NtpClockAdapter> {
        val c = NtpClockAdapter(InetAddress.getLoopbackAddress(), emu.port, fastIntervalMs = 20, slowIntervalMs = 20)
        c.start()
        Thread.sleep(millis)
        val now = System.nanoTime()
        val est = c.masterNanosForLocal(now)!!
        val truth = emu.clock()
        return (est - truth) to c
    }

    @Test fun `loopback offset 250ms with jitter and loss is estimated within 2ms`() {
        Emulator(250 * MS, 0.0, 20, 3).use { emu ->
            val (err, c) = runAdapter(emu, 4000)
            c.use {
                assertTrue(c.synced)
                assertEquals("err=${err / 1000}us", 0.0, err / 1e6, 2.0)
            }
        }
    }

    @Test fun `loopback with 30ppm drift stays within 2ms`() {
        Emulator(-250 * MS, 30.0, 10, 2).use { emu ->
            val (err, c) = runAdapter(emu, 5000)
            c.use { assertEquals("err=${err / 1000}us", 0.0, err / 1e6, 2.0) }
        }
    }

    @Test fun `not synced before samples and conversions round trip`() {
        Emulator(250 * MS, 0.0, 0, 0).use { emu ->
            val c = NtpClockAdapter(InetAddress.getLoopbackAddress(), emu.port, 20, 20)
            assertFalse(c.synced); assertNull(c.masterNanosForLocal(0)); assertNull(c.localNanosForMaster(0))
            c.start(); Thread.sleep(1000)
            c.use {
                assertTrue(c.synced)
                val l = System.nanoTime()
                assertEquals(l.toDouble(), c.localNanosForMaster(c.masterNanosForLocal(l)!!)!!.toDouble(), 1000.0)
            }
        }
    }

    @Test fun `estimator recovers offset and 30ppm drift under asymmetric jitter over 5 minutes`() {
        val est = NtpOffsetEstimator()
        val rnd = Random(7)
        val offset0 = 250 * MS
        fun master(local: Long) = local + offset0 + (local * 30e-6).toLong()
        var local = 10_000_000_000L
        repeat(300) {
            val out = 200_000L + (if (rnd.nextInt(5) == 0) rnd.nextInt(8_000_000) else rnd.nextInt(100_000))
            val back = 200_000L + (if (rnd.nextInt(5) == 0) rnd.nextInt(8_000_000) else rnd.nextInt(100_000))
            val t1 = local; val t2 = master(t1 + out); val t3 = t2 + 50_000; val t4 = t1 + out + 50_000 + back
            if (rnd.nextInt(10) != 0) est.add(t1, t2, t3, t4)
            local += 1_000_000_000L
        }
        assertTrue(est.synced)
        val err = est.offsetAt(local) - (offset0 + (local * 30e-6).toLong())
        assertTrue("err=${err}ns", abs(err) < 500_000)
        assertEquals(30.0, est.driftPpm, 5.0)
    }

    @Test fun `packet format request and reply`() {
        val raw = NtpClockAdapter.nanosToRaw(123_456_789_012L)
        val req = NtpClockAdapter.buildRequest(0x0102, 123_456_789_012L)
        assertEquals(32, req.size); assertEquals(0x80, req[0].toInt() and 0xFF); assertEquals(0xD2, req[1].toInt() and 0xFF)
        assertEquals(1, req[2].toInt()); assertEquals(2, req[3].toInt())
        assertEquals(raw ushr 56, (req[24].toLong() and 0xFF))
        val reply = ByteArray(32); reply[0] = 0x80.toByte(); reply[1] = 0xD3.toByte()
        System.arraycopy(req, 24, reply, 8, 8)
        val r = NtpClockAdapter.parseReply(reply, 32)!!
        assertEquals(raw, r.origin)
        assertNull(NtpClockAdapter.parseReply(req, 32))   // a request is not a reply
        assertNull(NtpClockAdapter.parseReply(reply, 31))
        assertEquals(123_456_789_012L, NtpClockAdapter.rawToNanos(raw), 1.0)
    }

    @Test fun `anchor conversion uses 64-bit fraction`() {
        // 0.5 s = 0x8000_0000_0000_0000 (negative as signed long)
        assertEquals(10_500_000_000L, NtpClockAdapter.anchorMasterNanos(10, Long.MIN_VALUE))
    }

    @Test fun `bit41 mask gives 0x10100`() {
        assertEquals(0x10100L, 0x10300L and (1L shl 9).inv())
    }

    private fun assertEquals(expected: Long, actual: Long, delta: Double) = assertEquals(expected.toDouble(), actual.toDouble(), delta)
}
