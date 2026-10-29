package com.phairplay.airplay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Random
import kotlin.math.abs

/**
 * Kotlin PTP grandmaster over loopback: Announce/Sync/Follow_Up unicast to the slave and Delay_Resp answers.
 * Master time = local nanoTime + [offset0] + drift*(t - t0). [appleStyle] mimics the HomePod encoding
 * (Follow_Up/Delay_Resp carry a coarse base timestamp and the remainder in correctionField).
 */
class TestPtpMaster(
    private val slaveEvent: Int,
    private val slaveGeneral: Int,
    val gmId: Long,
    val offset0: Long,
    private val driftPpm: Double = 0.0,
    private val jitterNs: Long = 0,
    private val intervalMs: Long = 10,
    private val loss: Double = 0.0,
    private val appleStyle: Boolean = true,
    private val priority1: Int = 248,
) {
    private val lo = InetAddress.getLoopbackAddress()
    private val ev = DatagramSocket(0, lo)
    private val gen = DatagramSocket(0, lo)
    private val t0 = System.nanoTime()
    private val rnd = Random(42)
    @Volatile private var running = false
    @Volatile var paused = false
    @Volatile private var lastBase = 0L
    private val threads = ArrayList<Thread>()

    fun truthOffset(local: Long): Double = offset0 + (local - t0) * driftPpm * 1e-6
    private fun m(t: Long) = t + truthOffset(t).toLong()
    private fun jit() = if (jitterNs > 0) (rnd.nextDouble() * jitterNs).toLong() else 0L
    private fun lost() = loss > 0 && rnd.nextDouble() < loss
    private fun send(s: DatagramSocket, b: ByteArray, port: Int) = s.send(DatagramPacket(b, b.size, lo, port))

    fun start() {
        running = true
        threads += Thread { sendLoop() }.apply { isDaemon = true; start() }
        threads += Thread { recvLoop() }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        ev.close(); gen.close()
    }

    private fun sendLoop() {
        var seq = 0
        var next = System.nanoTime()
        var announceAt = 0L
        while (running) {
            try {
                if (!paused) {
                    val now = System.nanoTime()
                    if (now >= announceAt) { announceAt = now + 100_000_000L; send(gen, announce(seq), slaveGeneral) }
                    seq = (seq + 1) and 0xFFFF
                    if (!lost()) {
                        val tSend = System.nanoTime()
                        val origin = m(tSend) - jit()
                        val sync = PtpWire.header(PtpWire.SYNC, 44, 0x0608, seq, 0, -3, gmId, 0x8004)
                        send(ev, sync, slaveEvent)
                        val base = if (appleStyle) origin - 3_000_000L else origin
                        lastBase = base
                        if (!lost()) {
                            val fu = PtpWire.header(PtpWire.FOLLOW_UP, 64, 0x0408, seq, 2, -3, gmId, 0x8004,
                                correctionNanos = origin - base).copyOf(64)
                            PtpWire.putTs(fu, 34, base)
                            fu[44] = 0; fu[45] = 3; fu[46] = 0; fu[47] = 16
                            fu[48] = 0; fu[49] = 0x0D; fu[50] = 0x93.toByte(); fu[53] = 4
                            for (i in 0 until 8) fu[54 + i] = (gmId shr (56 - 8 * i)).toByte()
                            send(gen, fu, slaveGeneral)
                        }
                    }
                }
                next += intervalMs * 1_000_000L
                val sleep = (next - System.nanoTime()) / 1_000_000L
                if (sleep > 0) Thread.sleep(sleep)
            } catch (e: Exception) { if (running) e.printStackTrace() }
        }
    }

    private fun recvLoop() {
        val buf = ByteArray(256)
        val pkt = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                pkt.length = buf.size
                ev.receive(pkt)
                val t = System.nanoTime()
                if (paused) continue
                val msg = PtpWire.parse(buf, pkt.length) as? PtpWire.DelayReq ?: continue
                if (lost()) continue
                val rx = m(t) + jit()
                val base = lastBase
                val resp = PtpWire.header(PtpWire.DELAY_RESP, 54, 0x0608, msg.header.sequenceId, 3, -3, gmId, 0x8004,
                    correctionNanos = if (appleStyle) rx - base else 0)
                PtpWire.putTs(resp, 34, if (appleStyle) base else rx)
                for (i in 0 until 8) resp[44 + i] = (msg.header.sourceClockId shr (56 - 8 * i)).toByte()
                resp[52] = (msg.header.sourcePort shr 8).toByte(); resp[53] = msg.header.sourcePort.toByte()
                send(gen, resp, slaveGeneral)
            } catch (e: Exception) { if (running) e.printStackTrace() }
        }
    }

    private fun announce(seq: Int): ByteArray {
        val b = PtpWire.header(PtpWire.ANNOUNCE, 64, 0x0408, seq, 5, 0, gmId, 0x8004)
        b[47] = priority1.toByte(); b[48] = 248.toByte(); b[49] = 0x21; b[50] = 0x43; b[51] = 0x6A; b[52] = 245.toByte()
        for (i in 0 until 8) b[53 + i] = (gmId shr (56 - 8 * i)).toByte()
        b[63] = 0xA0.toByte()
        return b
    }
}

class PtpSlaveTest {
    private var slave: PtpSlave? = null
    private val masters = ArrayList<TestPtpMaster>()

    @After fun tearDown() {
        masters.forEach { it.stop() }
        slave?.stop()
    }

    private fun newSlave(): PtpSlave = PtpSlave(0, 0, bindAny = false).also { slave = it; it.start() }

    private fun newMaster(s: PtpSlave, gm: Long, offset: Long, drift: Double = 0.0, jitter: Long = 0, loss: Double = 0.0,
                          apple: Boolean = true, prio1: Int = 248, interval: Long = 10) =
        TestPtpMaster(s.boundEventPort, s.boundGeneralPort, gm, offset, drift, jitter, interval, loss, apple, prio1)
            .also { masters += it; it.start() }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    /** Estimated minus true master-local offset in microseconds at the present moment. */
    private fun errUs(s: PtpSlave, m: TestPtpMaster): Double {
        val l = System.nanoTime()
        val est = s.masterNanosForLocal(l)!! - l
        return (est - m.truthOffset(l)) / 1e3
    }

    private val base = 100_000_000_000_000L // keeps master time positive, ~27 h
    private val offset = base + 123_456_000L // +123.456 ms

    @Test fun notSyncedBeforeSamples() {
        val s = newSlave()
        assertFalse(s.synced)
        assertNull(s.masterNanosForLocal(System.nanoTime()))
        assertNull(s.localNanosForMaster(System.nanoTime()))
        assertNull(s.pathDelayNanos)
        assertEquals(0L, s.clockId)
    }

    @Test fun knownOffsetAndDrift_appleEncoding() {
        val s = newSlave()
        val m = newMaster(s, 0x0499b9b3154e0008L, offset, drift = 20.0, jitter = 50_000)
        assertTrue("synced", waitFor(5000) { s.synced })
        assertEquals(m.gmId, s.clockId)
        Thread.sleep(12_000)
        val e = errUs(s, m)
        println("PTP accuracy (apple, +123.456 ms, 20 ppm, 12 s): err=${"%.1f".format(e)} us drift=${s.driftPpm} ppm delay=${s.pathDelayNanos} ns")
        assertTrue("err $e us", abs(e) < 200)
        assertTrue("drift ${s.driftPpm}", abs(s.driftPpm!! - 20.0) < 5.0)
        // forward/inverse round trip
        val l = System.nanoTime()
        assertEquals(l.toDouble(), s.localNanosForMaster(s.masterNanosForLocal(l)!!)!!.toDouble(), 100.0)
    }

    @Test fun standardEncodingWithoutCorrections() {
        val s = newSlave()
        val m = newMaster(s, 0x1122334455667788L, base - 50_000_000L, drift = -15.0, jitter = 30_000, apple = false)
        assertTrue(waitFor(5000) { s.synced })
        Thread.sleep(3000)
        val e = errUs(s, m)
        println("PTP accuracy (standard, -50 ms, -15 ppm, 3 s): err=${"%.1f".format(e)} us")
        assertTrue("err $e us", abs(e) < 200)
    }

    @Test fun survivesPacketLoss() {
        val s = newSlave()
        val m = newMaster(s, 0x0499b9b3154e0008L, offset, drift = 20.0, jitter = 50_000, loss = 0.3)
        assertTrue(waitFor(8000) { s.synced })
        Thread.sleep(6000)
        assertTrue(s.synced)
        val e = errUs(s, m)
        println("PTP accuracy (30% loss, 6 s): err=${"%.1f".format(e)} us")
        assertTrue("err $e us", abs(e) < 300)
    }

    @Test fun masterChangeAndTimeout() {
        val s = newSlave()
        s.announceTimeoutMs = 600
        val a = newMaster(s, 0xAAAAAAAAAAAAAAAAuL.toLong(), offset, prio1 = 250)
        assertTrue(waitFor(5000) { s.synced })
        assertEquals(a.gmId, s.clockId)
        // better master appears (lower priority1) with a very different clock
        val b = newMaster(s, 0xBBBBBBBBBBBBBBBBuL.toLong(), base - 77_000_000L, prio1 = 240)
        assertTrue("switch to B", waitFor(5000) { s.clockId == b.gmId && s.synced })
        Thread.sleep(500)
        val eb = errUs(s, b)
        println("PTP master change: err vs B=${"%.1f".format(eb)} us")
        assertTrue("err $eb us", abs(eb) < 300)
        // B disappears; A (worse rank, still alive) takes over after the announce timeout
        b.paused = true
        assertTrue("fall back to A", waitFor(8000) { s.clockId == a.gmId && s.synced })
        Thread.sleep(500)
        val ea = errUs(s, a)
        assertTrue("err $ea us", abs(ea) < 300)
    }

    @Test fun masterHintFiltersOtherAddresses() {
        val s = newSlave()
        s.setMasterHint("127.0.0.2")
        newMaster(s, 0x0499b9b3154e0008L, offset)
        Thread.sleep(1500)
        assertFalse(s.synced)
        s.setMasterHint("127.0.0.1")
        assertTrue(waitFor(5000) { s.synced })
        s.setMasterHint(null)
    }

    // ---- real wire samples (captures/ptp_samples.txt) ----

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun parse(s: String) = hex(s).let { PtpWire.parse(it, it.size) }

    private val MAC = 0x3c063079e6580008L
    private val HOMEPOD = 0x0499b9b3154e0008L.toLong()

    @Test fun parsesAnnounceFromMacAndHomePod() {
        val mac = parse("1b02004c000004080000000000000000000000003c063079e65800088000000105fe00000000000000000000002500faf821436af03c063079e65800080000a0000800083c063079e6580008") as PtpWire.Announce
        assertEquals(MAC, mac.header.sourceClockId); assertEquals(MAC, mac.gmIdentity)
        assertEquals(250, mac.priority1); assertEquals(240, mac.priority2); assertEquals(248, mac.clockClass)
        assertEquals(0, mac.stepsRemoved); assertEquals(1, mac.header.sequenceId); assertEquals(-2, mac.header.logInterval)
        assertEquals(1, mac.header.transportSpecific)
        val hp = parse("1b02004c000004080000000000000000000000000499b9b3154e00088004000105fe00000000000000000000002500f8f821436af50499b9b3154e00080000a0000800080499b9b3154e0008") as PtpWire.Announce
        assertEquals(HOMEPOD, hp.gmIdentity); assertEquals(245, hp.priority2); assertEquals(248, hp.priority1)
        assertEquals(0x8004, hp.header.sourcePort)
        // Mac relays the HomePod as grandmaster one hop away
        val relay = parse("1b020054000004080000000000000000000000003c063079e65800088001000205fe00000000000000000000002500f8f821436af50499b9b3154e00080001a0000800100499b9b3154e00083c063079e6580008") as PtpWire.Announce
        assertEquals(HOMEPOD, relay.gmIdentity); assertEquals(1, relay.stepsRemoved); assertEquals(MAC, relay.header.sourceClockId)
    }

    @Test fun parsesSyncFollowUpDelayReqDelayResp() {
        val sync = parse("1002002c000006080000000000000000000000000499b9b3154e00088004000100fd00000000000000000000") as PtpWire.Sync
        assertTrue(sync.header.twoStep); assertEquals(1, sync.header.sequenceId); assertEquals(-3, sync.header.logInterval)
        assertEquals(0L, sync.header.correctionNanos); assertEquals(HOMEPOD, sync.header.sourceClockId)

        val fu = parse("180200600000040800000020bb650000000000000499b9b3154e00088004000102fd000000052d4f2f75a60f0003001c0080c20000010000000000000000000000000000000000000000000000030010000d930000040499b9b3154e00080000") as PtpWire.FollowUp
        assertEquals(339279L * 1_000_000_000L + 796_239_375L, fu.preciseOriginNanos)
        assertEquals(2_145_125L, fu.header.correctionNanos)
        assertEquals(HOMEPOD, fu.appleGmClockId)
        val macFu = parse("1802006000000408000000015e4f0000000000003c063079e65800088000000102fd000000003dbd177cedbc0003001c0080c20000010000000000000000000000000000000000000000000000030010000d930000043c063079e65800080000") as PtpWire.FollowUp
        assertEquals(0x3dbdL * 1_000_000_000L + 0x177cedbcL, macFu.preciseOriginNanos)
        assertEquals(MAC, macFu.appleGmClockId)

        val dreq = parse("1102002c000004080000000000000000000000003c063079e65800088000000100fd00000000000000000000") as PtpWire.DelayReq
        assertEquals(MAC, dreq.header.sourceClockId); assertEquals(1, dreq.header.sequenceId); assertEquals(0x8000, dreq.header.sourcePort)

        val resp = parse("1902003600000608000000a62a930000000000000499b9b3154e00088004000100fd000000052d4f2f75a60f3c063079e65800088000") as PtpWire.DelayResp
        assertEquals(339279L * 1_000_000_000L + 796_239_375L, resp.receiveNanos)
        assertEquals(10_889_875L, resp.header.correctionNanos)
        assertEquals(MAC, resp.reqClockId); assertEquals(0x8000, resp.reqPort)
    }

    @Test fun parsesSignaling() {
        val mac = parse("1c02006a000004080000000000000000000000003c063079e65800088000000005800000000000000000000000030016000d9300000100070000000027100000271000247a3c00030020000d93000005001f000000000000271000002710000027100000271000247a3c") as PtpWire.Signaling
        assertEquals(2, mac.tlvs.size); assertEquals(3, mac.tlvs[0].first); assertEquals(0x16, mac.tlvs[0].second.size)
        assertEquals(0x20, mac.tlvs[1].second.size)
        val roku = parse("1c02003c0000060000000000000000000000000020efbdfffe4d7b0880000000057f000000000000000000000003000c0080c200000200fd80030000") as PtpWire.Signaling
        assertEquals(1, roku.tlvs.size); assertEquals(12, roku.tlvs[0].second.size)
        assertEquals(0x20efbdfffe4d7b08L, roku.header.sourceClockId)
        val rokuReq = parse("1102002c0000060000000000000000000000000020efbdfffe4d7b08800007dc0100000000052d5b1e09fd1b") as PtpWire.DelayReq
        assertEquals(2012, rokuReq.header.sequenceId)
    }

    @Test fun malformedInputNeverThrows() {
        val samples = listOf(
            "1b02004c000004080000000000000000000000003c063079e65800088000000105fe00000000000000000000002500faf821436af03c063079e65800080000a0000800083c063079e6580008",
            "180200600000040800000020bb650000000000000499b9b3154e00088004000102fd000000052d4f2f75a60f0003001c0080c20000010000000000000000000000000000000000000000000000030010000d930000040499b9b3154e00080000",
            "1c02006a000004080000000000000000000000003c063079e65800088000000005800000000000000000000000030016000d9300000100070000000027100000271000247a3c00030020000d93000005001f000000000000271000002710000027100000271000247a3c",
        )
        for (s in samples) {
            val b = hex(s)
            for (n in 0..b.size) PtpWire.parse(b, n) // truncated: null or message, never an exception
            val bad = b.copyOf(); bad[1] = 1; assertNull(PtpWire.parse(bad, bad.size))
        }
        assertNull(PtpWire.parse(ByteArray(0), 0))
        assertNotNull(PtpWire.delayReq(1, 5L).let { PtpWire.parse(it, it.size) })
        // our own builders round-trip
        val ann = PtpWire.announce(7, 0x1234L).let { PtpWire.parse(it, it.size) } as PtpWire.Announce
        assertEquals(255, ann.priority1); assertEquals(7, ann.header.sequenceId)
        val sig = PtpWire.signalingIntervalRequest(3, 0x1234L).let { PtpWire.parse(it, it.size) } as PtpWire.Signaling
        assertEquals(1, sig.tlvs.size)
    }
}
