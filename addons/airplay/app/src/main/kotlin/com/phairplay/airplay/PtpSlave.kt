package com.phairplay.airplay

import com.phairplay.util.Logger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Wire format of the PTPv2 (IEEE 1588 / 802.1AS style, unicast) messages AirPlay 2 uses, as observed in
 * HomePod <-> Mac captures. Only what the slave needs is decoded. All parsing is bounds-checked and returns
 * null on anything malformed (never throws).
 */
internal object PtpWire {
    const val SYNC = 0x0
    const val DELAY_REQ = 0x1
    const val FOLLOW_UP = 0x8
    const val DELAY_RESP = 0x9
    const val ANNOUNCE = 0xB
    const val SIGNALING = 0xC

    const val HEADER_LEN = 34
    const val FLAG_TWO_STEP = 0x0200
    const val FLAG_UNICAST = 0x0400
    const val FLAG_TIMESCALE = 0x0008
    private const val APPLE_OUI = 0x000D93

    class Header(
        val type: Int,
        val transportSpecific: Int,
        val length: Int,
        val domain: Int,
        val flags: Int,
        /** correctionField in nanoseconds (raw value is scaled by 2^16). */
        val correctionNanos: Long,
        val sourceClockId: Long,
        val sourcePort: Int,
        val sequenceId: Int,
        val control: Int,
        val logInterval: Int,
    ) {
        val twoStep get() = flags and FLAG_TWO_STEP != 0
    }

    sealed class Msg { abstract val header: Header }
    class Sync(override val header: Header, val originNanos: Long) : Msg()
    class DelayReq(override val header: Header, val originNanos: Long) : Msg()
    class FollowUp(override val header: Header, val preciseOriginNanos: Long, val appleGmClockId: Long?) : Msg()
    class DelayResp(override val header: Header, val receiveNanos: Long, val reqClockId: Long, val reqPort: Int) : Msg()
    class Announce(
        override val header: Header,
        val utcOffset: Int, val priority1: Int, val clockClass: Int, val accuracy: Int, val variance: Int,
        val priority2: Int, val gmIdentity: Long, val stepsRemoved: Int, val timeSource: Int,
    ) : Msg()
    /** [tlvs] = (type, value) pairs; the value includes organizationId/subtype for type 3. */
    class Signaling(override val header: Header, val targetClockId: Long, val targetPort: Int, val tlvs: List<Pair<Int, ByteArray>>) : Msg()

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = (u8(b, o) shl 8) or u8(b, o + 1)
    private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong()
    private fun i64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or u8(b, o + i).toLong()
        return v
    }
    private fun ts(b: ByteArray, o: Int): Long {
        var sec = 0L
        for (i in 0 until 6) sec = (sec shl 8) or u8(b, o + i).toLong()
        return sec * 1_000_000_000L + u32(b, o + 6)
    }

    fun parse(b: ByteArray, len: Int): Msg? {
        if (len < HEADER_LEN || len > b.size) return null
        if (u8(b, 1) and 0x0F != 2) return null
        val type = u8(b, 0) and 0x0F
        val msgLen = u16(b, 2)
        if (msgLen < HEADER_LEN || msgLen > len) return null
        val raw = i64(b, 8)
        val h = Header(
            type = type, transportSpecific = u8(b, 0) shr 4, length = msgLen, domain = u8(b, 4), flags = u16(b, 6),
            correctionNanos = Math.round(raw / 65536.0), sourceClockId = i64(b, 20), sourcePort = u16(b, 28),
            sequenceId = u16(b, 30), control = u8(b, 32), logInterval = b[33].toInt(),
        )
        return when (type) {
            SYNC -> if (msgLen >= 44) Sync(h, ts(b, 34)) else null
            DELAY_REQ -> if (msgLen >= 44) DelayReq(h, ts(b, 34)) else null
            FOLLOW_UP -> if (msgLen >= 44) FollowUp(h, ts(b, 34), appleGm(b, 44, msgLen)) else null
            DELAY_RESP -> if (msgLen >= 54) DelayResp(h, ts(b, 34), i64(b, 44), u16(b, 52)) else null
            ANNOUNCE -> if (msgLen >= 64) Announce(
                h, u16(b, 44), u8(b, 47), u8(b, 48), u8(b, 49), u16(b, 50), u8(b, 52), i64(b, 53), u16(b, 61), u8(b, 63),
            ) else null
            SIGNALING -> if (msgLen >= 44) Signaling(h, i64(b, 34), u16(b, 42), tlvs(b, 44, msgLen)) else null
            else -> null
        }
    }

    private fun tlvs(b: ByteArray, from: Int, end: Int): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        var p = from
        while (p + 4 <= end) {
            val t = u16(b, p)
            val l = u16(b, p + 2)
            if (p + 4 + l > end) break
            out += t to b.copyOfRange(p + 4, p + 4 + l)
            p += 4 + l
        }
        return out
    }

    /** Apple organization TLV (OUI 00-0D-93, subtype 4) in Follow_Up carries the grandmaster ClockIdentity. */
    private fun appleGm(b: ByteArray, from: Int, end: Int): Long? {
        for ((t, v) in tlvs(b, from, end)) {
            if (t == 3 && v.size >= 14 && (u16(v, 0) shl 8 or u8(v, 2)) == APPLE_OUI && (u16(v, 3) shl 8 or u8(v, 5)) == 4) {
                return i64(v, 6)
            }
        }
        return null
    }

    // ---- builders ----

    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = (v shr 8).toByte(); b[o + 1] = v.toByte() }
    private fun put64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v shr (56 - 8 * i)).toByte() }
    fun putTs(b: ByteArray, o: Int, nanos: Long) {
        val sec = nanos / 1_000_000_000L
        val ns = nanos % 1_000_000_000L
        for (i in 0 until 6) b[o + i] = (sec shr (40 - 8 * i)).toByte()
        b[o + 6] = (ns shr 24).toByte(); b[o + 7] = (ns shr 16).toByte(); b[o + 8] = (ns shr 8).toByte(); b[o + 9] = ns.toByte()
    }

    fun header(
        type: Int, length: Int, flags: Int, seq: Int, control: Int, log: Int, clockId: Long, port: Int = 0x8000,
        correctionNanos: Long = 0, transportSpecific: Int = 1, domain: Int = 0,
    ): ByteArray {
        val b = ByteArray(length)
        b[0] = ((transportSpecific shl 4) or type).toByte()
        b[1] = 2
        put16(b, 2, length)
        b[4] = domain.toByte()
        put16(b, 6, flags)
        put64(b, 8, correctionNanos shl 16)
        put64(b, 20, clockId)
        put16(b, 28, port)
        put16(b, 30, seq)
        b[32] = control.toByte()
        b[33] = log.toByte()
        return b
    }

    /** Delay_Req, 44 bytes, shaped like the ones the Mac/Roku send (unicast flag, zero origin timestamp). */
    fun delayReq(seq: Int, clockId: Long) = header(DELAY_REQ, 44, FLAG_UNICAST or FLAG_TWO_STEP, seq, 1, 0, clockId)

    /** Announce that never wins BMCA (priority 255), with a path-trace TLV, 76 bytes. */
    fun announce(seq: Int, clockId: Long): ByteArray {
        val b = header(ANNOUNCE, 76, FLAG_UNICAST or FLAG_TIMESCALE, seq, 5, 0, clockId)
        put16(b, 44, 37)
        b[47] = 255.toByte(); b[48] = 255.toByte(); b[49] = 0xFE.toByte(); put16(b, 50, 0xFFFF)
        b[52] = 255.toByte()
        put64(b, 53, clockId)
        b[63] = 0xA0.toByte()
        put16(b, 64, 8); put16(b, 66, 8); put64(b, 68, clockId)
        return b
    }

    /** Signaling with the standard 802.1AS Message Interval Request TLV (Sync every 2^-3 s), 60 bytes. */
    fun signalingIntervalRequest(seq: Int, clockId: Long): ByteArray {
        val b = header(SIGNALING, 60, FLAG_UNICAST or FLAG_TIMESCALE, seq, 5, 0x7F, clockId)
        put16(b, 44, 3); put16(b, 46, 12)
        b[48] = 0x00; b[49] = 0x80.toByte(); b[50] = 0xC2.toByte()
        b[54] = 2
        b[55] = 0; b[56] = 0xFD.toByte(); b[57] = 0x80.toByte(); b[58] = 3
        return b
    }
}

/**
 * PTP slave for AirPlay 2 multi-room: estimates the offset of [System.nanoTime] to the grandmaster over
 * unicast E2E (Sync / Follow_Up / Delay_Req / Delay_Resp) and exposes it as a [PtpClock].
 *
 * Model: `master = local + a + slope * (local - ref)`. Each Sync/Delay_Req exchange gives one sample
 * `m = ((t1-t2)+(t4-t3))/2` (master minus local; the textbook slave-minus-master offset is `-m`) and
 * `rtt = (t4-t3)-(t1-t2)`. Samples within `minRtt + 2 ms` of the best round trip in a 60 s window are fed to a
 * least-squares line (slope only once the span reaches 10 s, clamped to +-300 ppm; before that, median).
 *
 * Apple encoding (verified against multiroom_mac.pcap): Follow_Up.correctionField is added to preciseOrigin
 * (t1), and Delay_Resp carries the *Sync's* origin as receiveTimestamp with the elapsed time in correctionField,
 * so t4 = receiveTimestamp + correctionField (additive, unlike the 1588 text which subtracts it).
 *
 * One dual-stack UDP socket per port (IPv4 peers show up as IPv4 addresses). The master is learned from incoming
 * Announce/Sync (or [setMasterHint]); replies go to the sender's source ports.
 *
 * @param eventPort UDP port for Sync/Delay_Req (0 = ephemeral; read [boundEventPort] after [start])
 * @param generalPort UDP port for Announce/Follow_Up/Delay_Resp/Signaling (0 = ephemeral)
 * @param bindAny bind to the wildcard address; false = loopback only
 */
class PtpSlave(
    private val eventPort: Int,
    private val generalPort: Int,
    private val bindAny: Boolean = true,
) : PtpClock {

    /** Master ports used for the initial hello to a hinted address, before any packet reveals the real ones. */
    @Volatile var defaultMasterEventPort = 319
    @Volatile var defaultMasterGeneralPort = 320
    /** Experiment: also send the hello (Announce+Signaling) to the master's event port. */
    @Volatile var helloEventToo = false
    /** Experiment: raw packet hook (socket name, source, port, bytes, length), called before parsing. */
    @Volatile var rawLog: ((String, InetAddress, Int, ByteArray, Int) -> Unit)? = null
    internal var announceTimeoutMs = 5000L
    internal var staleMs = 30_000L

    @Volatile var boundEventPort = 0; private set
    @Volatile var boundGeneralPort = 0; private set

    private val ownClockId: Long = Random.nextLong() and 0x7FFFFFFFFFFFFFFFL or 0x0000020000000000L
    private val lock = Any()

    @Volatile private var running = false
    private var eventSock: DatagramSocket? = null
    private var generalSock: DatagramSocket? = null
    private var executor: ScheduledExecutorService? = null

    // --- master tracking (guarded by lock) ---
    private var hint: String? = null
    private var masterAddr: InetAddress? = null
    private var masterSrcClock = 0L
    private var masterEventPort = 0
    private var masterGeneralPort = 0
    private var gmId = 0L
    private var gmRank: LongArray? = null // priority1, class, accuracy, variance, priority2, gmId, steps
    private var lastAnnounceNs = 0L

    // --- exchange bookkeeping (guarded by lock) ---
    private class Pending(val syncSeq: Int, val t2: Long, val syncCorr: Long, val born: Long) {
        var t3 = 0L; var t1: Long? = null; var t4: Long? = null
    }
    private val byDreq = HashMap<Int, Pending>()
    private val bySync = HashMap<Int, Pending>()
    private var dreqSeq = 0
    private var announceSeq = 0
    private var signalingSeq = 0

    // --- estimation (guarded by lock) ---
    private class Sample(val l: Long, val m: Long, val rtt: Long)
    private val samples = ArrayDeque<Sample>()
    private var totalSamples = 0L
    private var acceptedCount = 0
    private var outliers = 0
    private var badFits = 0
    private var syncedFlag = false
    private var lastSampleNs = 0L

    private class Fit(val ref: Long, val a: Double, val slope: Double)
    @Volatile private var fit: Fit? = null
    @Volatile private var delayNs: Long? = null
    @Volatile private var syncedPub = false
    @Volatile private var gmPub = 0L

    private var lastLogNs = 0L
    private var lastHelloNs = 0L

    override val synced: Boolean get() = syncedPub
    override val clockId: Long get() = gmPub
    override val pathDelayNanos: Long? get() = delayNs

    override fun masterNanosForLocal(localNanos: Long): Long? {
        val f = fit ?: return null
        if (!syncedPub) return null
        return localNanos + (f.a + f.slope * (localNanos - f.ref).toDouble()).toLong()
    }

    override fun localNanosForMaster(masterNanos: Long): Long? {
        val f = fit ?: return null
        if (!syncedPub) return null
        val delta = (masterNanos - f.ref).toDouble() - f.a
        return f.ref + (delta / (1.0 + f.slope)).toLong()
    }

    /** Estimated drift of the master relative to the local clock, ppm (diagnostics). */
    val driftPpm: Double? get() = fit?.let { it.slope * 1e6 }

    /** Restrict the master to this address (null = accept the first / best Announcing peer). Also sends a hello to it. */
    fun setMasterHint(address: String?) {
        synchronized(lock) {
            hint = address
            val m = masterAddr
            if (address != null && m != null && !sameHost(m, address)) resetMaster("hint changed to $address")
        }
        if (address != null && running) sendHintHello()
    }

    @Synchronized
    fun start() {
        if (running) return
        val bind = if (bindAny) null else InetAddress.getLoopbackAddress()
        val ev = DatagramSocket(InetSocketAddress(bind, eventPort))
        val gen = try {
            DatagramSocket(InetSocketAddress(bind, generalPort))
        } catch (e: Exception) { ev.close(); throw e }
        eventSock = ev; generalSock = gen
        boundEventPort = ev.localPort; boundGeneralPort = gen.localPort
        running = true
        Thread({ recvLoop(ev, "event") }, "ptp-event").apply { isDaemon = true }.start()
        Thread({ recvLoop(gen, "general") }, "ptp-general").apply { isDaemon = true }.start()
        executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ptp-tick").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ safely { tick() } }, 500, 1000, TimeUnit.MILLISECONDS)
        }
        Logger.i("MR: PTP slave started event=$boundEventPort general=$boundGeneralPort clockId=${hex(ownClockId)}")
        sendHintHello()
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        executor?.shutdownNow(); executor = null
        eventSock?.close(); generalSock?.close()
        eventSock = null; generalSock = null
        synchronized(lock) { resetMaster(null) }
        Logger.i("MR: PTP slave stopped")
    }

    // ------------------------------------------------------------------ receive

    private fun recvLoop(sock: DatagramSocket, name: String) {
        val buf = ByteArray(1500)
        val pkt = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                pkt.length = buf.size
                sock.receive(pkt)
                val t2 = System.nanoTime()
                rawLog?.invoke(name, pkt.address, pkt.port, buf, pkt.length)
                val msg = PtpWire.parse(buf, pkt.length) ?: continue
                handle(msg, pkt.address, pkt.port, t2)
            } catch (e: SocketException) {
                if (running) Logger.w("MR: PTP socket error: ${e.message}")
                return
            } catch (e: Exception) {
                Logger.w("MR: PTP bad packet: $e")
            }
        }
    }

    private inline fun safely(block: () -> Unit) {
        try { block() } catch (e: Exception) { Logger.w("MR: PTP error: $e") }
    }

    private fun handle(msg: PtpWire.Msg, src: InetAddress, srcPort: Int, t2: Long) {
        val h = msg.header
        if (h.domain != 0 || h.sourceClockId == ownClockId) return
        synchronized(lock) {
            val hn = hint
            if (hn != null && !sameHost(src, hn)) return
            when (msg) {
                is PtpWire.Announce -> onAnnounce(msg, src, srcPort)
                is PtpWire.Sync -> if (adoptOrCheck(h, src)) { masterEventPort = srcPort; onSync(msg, t2) }
                is PtpWire.FollowUp -> if (isMaster(h, src)) { masterGeneralPort = srcPort; onFollowUp(msg) }
                is PtpWire.DelayResp -> if (isMaster(h, src)) onDelayResp(msg)
                else -> {}
            }
        }
    }

    private fun isMaster(h: PtpWire.Header, src: InetAddress): Boolean {
        val m = masterAddr ?: return false
        return h.sourceClockId == masterSrcClock && sameHost(m, src.hostAddress)
    }

    /** Sync from an unknown peer with no master yet makes it the master (until an Announce says otherwise). */
    private fun adoptOrCheck(h: PtpWire.Header, src: InetAddress): Boolean {
        if (isMaster(h, src)) return true
        if (masterAddr != null) return false
        switchMaster(src, h.sourceClockId, h.sourceClockId, null, "first Sync")
        return true
    }

    private fun onAnnounce(a: PtpWire.Announce, src: InetAddress, srcPort: Int) {
        val h = a.header
        val now = System.nanoTime()
        val rank = longArrayOf(
            a.priority1.toLong(), a.clockClass.toLong(), a.accuracy.toLong(), a.variance.toLong(),
            a.priority2.toLong(), a.gmIdentity, a.stepsRemoved.toLong(),
        )
        if (isMaster(h, src)) {
            masterGeneralPort = srcPort
            lastAnnounceNs = now
            gmRank = rank
            if (a.gmIdentity != gmId) {
                Logger.i("MR: PTP grandmaster changed ${hex(gmId)} -> ${hex(a.gmIdentity)} (via ${src.hostAddress})")
                clearEstimate()
                gmId = a.gmIdentity; gmPub = gmId
            }
            return
        }
        val cur = gmRank
        val timedOut = masterAddr != null && now - lastAnnounceNs > announceTimeoutMs * 1_000_000L
        val better = cur == null || betterRank(rank, cur)
        if (masterAddr == null || timedOut || better) {
            switchMaster(src, h.sourceClockId, a.gmIdentity, rank, if (timedOut) "announce timeout" else "better announce")
            masterGeneralPort = srcPort
        }
    }

    /** BMCA-like: compare grandmaster data; the same grandmaster is only replaced via a lower stepsRemoved. */
    private fun betterRank(n: LongArray, c: LongArray): Boolean {
        if (n[5] == c[5]) return n[6] < c[6]
        for (i in 0 until 5) if (n[i] != c[i]) return n[i] < c[i]
        return n[5] < c[5]
    }

    private fun switchMaster(src: InetAddress, srcClock: Long, gm: Long, rank: LongArray?, why: String) {
        val old = gmId
        clearEstimate()
        masterAddr = src; masterSrcClock = srcClock
        masterEventPort = defaultMasterEventPort; masterGeneralPort = defaultMasterGeneralPort
        gmId = gm; gmPub = gm; gmRank = rank
        lastAnnounceNs = System.nanoTime()
        Logger.i("MR: PTP master ${hex(old)} -> ${hex(gm)} @${src.hostAddress} ($why)")
        sendHello()
    }

    private fun resetMaster(why: String?) {
        clearEstimate()
        masterAddr = null; masterSrcClock = 0; gmId = 0; gmPub = 0; gmRank = null
        if (why != null) Logger.i("MR: PTP master reset: $why")
    }

    private fun clearEstimate() {
        samples.clear(); byDreq.clear(); bySync.clear()
        acceptedCount = 0; outliers = 0; badFits = 0
        syncedFlag = false; syncedPub = false; fit = null; delayNs = null
    }

    // ------------------------------------------------------------------ exchanges

    private fun onSync(s: PtpWire.Sync, t2: Long) {
        val h = s.header
        if (bySync.containsKey(h.sequenceId)) return
        val now = t2
        byDreq.values.removeAll { now - it.born > 3_000_000_000L }
        bySync.values.removeAll { now - it.born > 3_000_000_000L }
        val p = Pending(h.sequenceId, t2, h.correctionNanos, now)
        if (!h.twoStep) p.t1 = s.originNanos + h.correctionNanos
        dreqSeq = (dreqSeq + 1) and 0xFFFF
        bySync[h.sequenceId] = p
        byDreq[dreqSeq] = p
        val addr = masterAddr ?: return
        val pkt = PtpWire.delayReq(dreqSeq, ownClockId)
        p.t3 = System.nanoTime()
        send(eventSock, pkt, addr, masterEventPort)
    }

    private fun onFollowUp(f: PtpWire.FollowUp) {
        val p = bySync[f.header.sequenceId] ?: return
        p.t1 = f.preciseOriginNanos + p.syncCorr + f.header.correctionNanos
        f.appleGmClockId?.let { if (gmId == masterSrcClock && it != gmId) { gmId = it; gmPub = it } }
        complete(p)
    }

    private fun onDelayResp(r: PtpWire.DelayResp) {
        if (r.reqClockId != ownClockId || r.reqPort != 0x8000) return
        val p = byDreq[r.header.sequenceId] ?: return
        p.t4 = r.receiveNanos + r.header.correctionNanos
        complete(p)
    }

    private fun complete(p: Pending) {
        val t1 = p.t1 ?: return
        val t4 = p.t4 ?: return
        byDreq.values.remove(p); bySync.values.remove(p)
        val a = t1 - p.t2
        val b = t4 - p.t3
        addSample(Sample(p.t3, (a + b) / 2, b - a), p.t2)
    }

    // ------------------------------------------------------------------ estimation

    private fun addSample(s: Sample, now: Long) {
        if (s.rtt < -1_000_000L || s.rtt > 1_000_000_000L) return // garbage
        totalSamples++
        lastSampleNs = now
        // Step detection: a synced model that keeps missing by > 5 ms means the master clock jumped.
        val f = fit
        if (syncedFlag && f != null) {
            val pred = f.a + f.slope * (s.l - f.ref).toDouble()
            if (Math.abs(s.m - pred) > 5_000_000.0 && s.rtt < 10_000_000L) {
                if (++outliers >= 5) {
                    Logger.i("MR: PTP clock step detected, restarting estimate")
                    clearEstimate()
                    samples.addLast(s)
                    return
                }
            } else outliers = 0
        }
        samples.addLast(s)
        val horizon = s.l - WINDOW_NS
        while (samples.isNotEmpty() && samples.first().l < horizon) samples.removeFirst()
        while (samples.size > 3000) samples.removeFirst()
        refit()
    }

    private fun refit() {
        var minRtt = Long.MAX_VALUE
        for (s in samples) if (s.rtt < minRtt) minRtt = s.rtt
        val thr = minRtt + RTT_SLACK_NS
        val acc = samples.filter { it.rtt <= thr }
        acceptedCount = acc.size
        if (acc.isEmpty()) return
        val ref = acc.last().l
        val n = acc.size
        val ys = DoubleArray(n) { acc[it].m.toDouble() }
        val xs = DoubleArray(n) { (acc[it].l - ref).toDouble() }
        val span = acc.last().l - acc.first().l
        val a: Double
        val slope: Double
        if (span >= SLOPE_MIN_SPAN_NS && n >= 16) {
            val xm = xs.average(); val ym = ys.average()
            var sxy = 0.0; var sxx = 0.0
            for (i in 0 until n) { sxy += (xs[i] - xm) * (ys[i] - ym); sxx += (xs[i] - xm) * (xs[i] - xm) }
            slope = (if (sxx > 0) sxy / sxx else 0.0).coerceIn(-MAX_SLOPE, MAX_SLOPE)
            a = ym - slope * xm
        } else {
            slope = 0.0
            a = ys.sorted()[n / 2]
        }
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (i in 0 until n) { val r = ys[i] - (a + slope * xs[i]); if (r < lo) lo = r; if (r > hi) hi = r }
        val ok = n >= MIN_SAMPLES && hi - lo < MAX_SPREAD_NS
        fit = Fit(ref, a, slope)
        delayNs = acc.map { it.rtt }.sorted()[n / 2] / 2
        if (ok) {
            badFits = 0
            if (!syncedFlag) {
                syncedFlag = true
                Logger.i("MR: PTP synced: offset=${fmtMs(a)} ms n=$n spread=${fmtMs(hi - lo)} ms")
            }
        } else if (syncedFlag && ++badFits >= 3) {
            syncedFlag = false
            Logger.i("MR: PTP lost sync (n=$n spread=${fmtMs(hi - lo)} ms)")
        }
        syncedPub = syncedFlag
    }

    // ------------------------------------------------------------------ housekeeping / sending

    private fun tick() {
        val now = System.nanoTime()
        synchronized(lock) {
            if (syncedFlag && now - lastSampleNs > staleMs * 1_000_000L) {
                syncedFlag = false; syncedPub = false
                Logger.i("MR: PTP lost sync (no samples for ${staleMs / 1000} s)")
            }
            masterAddr?.let { addr ->
                send(generalSock, PtpWire.announce(nextAnnounce(), ownClockId), addr, masterGeneralPort)
                if (now - lastHelloNs > 10_000_000_000L) sendHello()
            }
            if (now - lastLogNs >= 1_000_000_000L && (masterAddr != null || totalSamples > 0)) {
                lastLogNs = now
                val f = fit
                Logger.i(
                    "MR: PTP gm=${hex(gmId)} src=${masterAddr?.hostAddress} synced=$syncedFlag " +
                        "off=${f?.let { fmtMs(it.a) }} ms drift=${f?.let { "%.1f".format(it.slope * 1e6) }} ppm " +
                        "delay=${delayNs?.let { fmtMs(it.toDouble()) }} ms n=$acceptedCount/${samples.size} total=$totalSamples",
                )
            }
            if (masterAddr == null) sendHintHello()
        }
    }

    private fun nextAnnounce() = (announceSeq++) and 0xFFFF

    private fun sendHello() {
        val addr = masterAddr ?: return
        lastHelloNs = System.nanoTime()
        send(generalSock, PtpWire.announce(nextAnnounce(), ownClockId), addr, masterGeneralPort)
        send(generalSock, PtpWire.signalingIntervalRequest((signalingSeq++) and 0xFFFF, ownClockId), addr, masterGeneralPort)
    }

    private fun sendHintHello() {
        val hn = synchronized(lock) { if (masterAddr == null) hint else null } ?: return
        try {
            val addr = InetAddress.getByName(hn)
            send(generalSock, PtpWire.announce(nextAnnounce(), ownClockId), addr, defaultMasterGeneralPort)
            send(generalSock, PtpWire.signalingIntervalRequest((signalingSeq++) and 0xFFFF, ownClockId), addr, defaultMasterGeneralPort)
            if (helloEventToo) {
                send(eventSock, PtpWire.announce(nextAnnounce(), ownClockId), addr, defaultMasterEventPort)
                send(eventSock, PtpWire.signalingIntervalRequest((signalingSeq++) and 0xFFFF, ownClockId), addr, defaultMasterEventPort)
            }
        } catch (e: Exception) {
            Logger.w("MR: PTP hint hello to $hn failed: $e")
        }
    }

    private fun send(sock: DatagramSocket?, data: ByteArray, to: InetAddress, port: Int) {
        if (sock == null || port == 0) return
        try {
            sock.send(DatagramPacket(data, data.size, to, port))
        } catch (e: Exception) {
            if (running) Logger.w("MR: PTP send to ${to.hostAddress}:$port failed: $e")
        }
    }

    private companion object {
        const val WINDOW_NS = 60_000_000_000L
        const val RTT_SLACK_NS = 2_000_000L
        const val SLOPE_MIN_SPAN_NS = 10_000_000_000L
        const val MAX_SLOPE = 300e-6
        const val MIN_SAMPLES = 8
        const val MAX_SPREAD_NS = 2_000_000.0

        fun hex(v: Long) = "%016x".format(v)
        fun fmtMs(ns: Double) = "%.3f".format(ns / 1e6)
        fun sameHost(a: InetAddress, b: String) = a.hostAddress.substringBefore('%') == b.substringBefore('%')
    }
}
