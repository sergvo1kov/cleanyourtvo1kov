package com.phairplay.airplay

import com.phairplay.util.Logger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Temporary hook for the multi-room playout scheduler. The ptp-sync branch will introduce
 * `object MultiroomPtp { @Volatile var clock: PtpClock? }`; when merging, either point the scheduler at this
 * object or make [MultiroomClockSource.clock] delegate to `MultiroomPtp.clock`. The NTP adapter publishes itself here.
 */
object MultiroomClockSource {
    @Volatile var clock: PtpClock? = null
}

/** `adb shell setprop debug.phairplay.nobit41 1` (read once, at first use = service start; app restart needed to change). */
internal val noBit41: Boolean by lazy {
    val p = runCatching {
        Runtime.getRuntime().exec(arrayOf("getprop", "debug.phairplay.nobit41")).inputStream.bufferedReader().readText().trim()
    }.getOrDefault("")
    (p == "1").also { Logger.i("MR: NTP mode switch debug.phairplay.nobit41=$p -> $it") }
}

/**
 * Offset estimator for the Apple NTP-like exchange (all times in nanoseconds; t1/t4 local [System.nanoTime],
 * t2/t3 sender clock). offset = master - local. Samples are filtered by minimum RTT in a sliding window
 * and a line (offset vs local time = drift) is fitted through the good ones.
 */
internal class NtpOffsetEstimator(
    private val windowSize: Int = 64,
    private val minGoodSamples: Int = 4,
) {
    private class S(val local: Long, val offset: Long, val rtt: Long)
    private val samples = ArrayDeque<S>()
    @Volatile private var base = 0L        // local reference time
    @Volatile private var offset0 = 0.0    // offset at base (ns)
    @Volatile private var slope = 0.0      // ns of offset per ns of local time
    @Volatile var count = 0; private set
    @Volatile var lastRttNanos = -1L; private set
    @Volatile var synced = false; private set

    @Synchronized
    fun add(t1: Long, t2: Long, t3: Long, t4: Long) {
        val rtt = (t4 - t1) - (t3 - t2)
        if (rtt < 0 || rtt > 2_000_000_000L) return
        val off = ((t2 - t1) + (t3 - t4)) / 2
        samples.addLast(S((t1 + t4) / 2, off, rtt)); if (samples.size > windowSize) samples.removeFirst()
        count++; lastRttNanos = rtt
        val minRtt = samples.minOf { it.rtt }
        val good = samples.filter { it.rtt <= minRtt * 3 / 2 + 500_000 }
        if (good.size < minGoodSamples) return
        val best = good.minByOrNull { it.rtt }!!
        base = best.local
        var o = best.offset.toDouble(); var b = 0.0
        val span = good.last().local - good.first().local
        if (good.size >= 8 && span >= 10_000_000_000L) {
            // least squares of offset over (local - base), clamped to a plausible crystal drift
            val xs = good.map { (it.local - base).toDouble() }; val ys = good.map { it.offset.toDouble() }
            val mx = xs.average(); val my = ys.average()
            val sxx = xs.sumOf { (it - mx) * (it - mx) }
            if (sxx > 0) {
                val fit = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
                if (kotlin.math.abs(fit) < 200e-6) { b = fit; o = my + b * (0 - mx) }
            }
        }
        offset0 = o; slope = b; synced = true
    }

    fun offsetAt(local: Long): Long = (offset0 + slope * (local - base)).toLong()
    val driftPpm: Double get() = slope * 1e6
}

/**
 * [PtpClock] backed by AirPlay's NTP-like timing exchange: used when the receiver does not advertise PTP
 * (features bit 41) and the sender negotiates `timingProtocol=NTP`. We send 32-byte requests
 * (`80 D2 seq.. origin@24`) to the sender's `timingPort` from [localPort] (this is the timingPort we return in SETUP #1)
 * and get `80 D3` replies (`origin@8, receive@16, transmit@24`, NTP 32.32 timestamps).
 * Master time = sender's timing clock as nanoseconds of its 32.32 timestamp (same scale as networkTimeSecs/Frac
 * of SETRATEANCHORTIME, see [anchorMasterNanos]).
 */
class NtpClockAdapter(
    private val remote: InetAddress,
    private val remotePort: Int,
    private val fastIntervalMs: Long = 125,
    private val slowIntervalMs: Long = 1000,
    private val fastCount: Int = 16,
) : PtpClock, AutoCloseable {
    private val socket = DatagramSocket().also { it.soTimeout = 100 }
    private val est = NtpOffsetEstimator()
    private val pending = HashMap<Long, Long>()   // raw origin timestamp -> t1 (nanoTime)
    @Volatile private var closed = false
    @Volatile override var clockId: Long = 0L
    val localPort: Int get() = socket.localPort
    val sampleCount: Int get() = est.count
    val driftPpm: Double get() = est.driftPpm

    override val synced: Boolean get() = est.synced
    override val pathDelayNanos: Long? get() = est.lastRttNanos.takeIf { it >= 0 }?.div(2)
    override fun masterNanosForLocal(localNanos: Long): Long? =
        if (est.synced) localNanos + est.offsetAt(localNanos) else null
    override fun localNanosForMaster(masterNanos: Long): Long? {
        if (!est.synced) return null
        var l = masterNanos - est.offsetAt(masterNanos)   // offset varies negligibly (ppm) over the offset itself
        l = masterNanos - est.offsetAt(l)
        return l
    }

    private val thread = Thread(::loop, "ntp-clock").also { it.isDaemon = true }
    fun start() { thread.start(); Logger.i("MR: NTP client -> ${remote.hostAddress}:$remotePort local=$localPort") }

    override fun close() {
        closed = true
        runCatching { socket.close() }
        if (MultiroomClockSource.clock === this) MultiroomClockSource.clock = null
    }

    private fun loop() {
        var seq = 0
        val rx = ByteArray(128)
        while (!closed) {
            try {
                val t1 = System.nanoTime()
                val req = buildRequest(seq++, t1)
                synchronized(pending) {
                    pending[readLong(req, 24)] = t1
                    if (pending.size > 64) pending.clear()
                }
                socket.send(DatagramPacket(req, req.size, remote, remotePort))
                val interval = if (seq <= fastCount) fastIntervalMs else slowIntervalMs
                val deadline = System.nanoTime() + interval * 1_000_000
                while (!closed && System.nanoTime() < deadline) {
                    try {
                        val p = DatagramPacket(rx, rx.size)
                        socket.receive(p)
                        onReply(rx, p.length, System.nanoTime())
                    } catch (_: SocketTimeoutException) { }
                }
            } catch (e: Exception) {
                if (!closed) Logger.w("MR: NTP client error: ${e.message}")
                if (!closed) try { Thread.sleep(200) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun onReply(d: ByteArray, len: Int, t4: Long) {
        val r = parseReply(d, len) ?: return
        val t1 = synchronized(pending) { pending.remove(r.origin) } ?: return
        val before = est.count
        est.add(t1, r.receiveNanos, r.transmitNanos, t4)
        if (est.count != before && (est.count <= 8 || est.count % 10 == 0)) {
            Logger.i("MR: NTP n=${est.count} synced=${est.synced} rtt=${(t4 - t1 - (r.transmitNanos - r.receiveNanos)) / 1000}us " +
                "offset=${est.offsetAt(t4) / 1_000_000.0}ms drift=${"%.1f".format(est.driftPpm)}ppm")
        }
    }

    internal class Reply(val origin: Long, val receiveNanos: Long, val transmitNanos: Long)

    companion object {
        fun buildRequest(seq: Int, localNanos: Long): ByteArray {
            val b = ByteArray(32)
            b[0] = 0x80.toByte(); b[1] = 0xD2.toByte(); b[2] = (seq ushr 8).toByte(); b[3] = seq.toByte()
            val raw = nanosToRaw(localNanos)
            for (i in 0 until 8) b[24 + i] = (raw ushr (56 - 8 * i)).toByte()
            return b
        }

        internal fun parseReply(d: ByteArray, len: Int): Reply? {
            if (len < 32 || (d[1].toInt() and 0x7F) != 0x53) return null   // 0xD3 with marker bit masked
            return Reply(readLong(d, 8), rawToNanos(readLong(d, 16)), rawToNanos(readLong(d, 24)))
        }

        /** 32.32 NTP-format timestamp (seconds.fraction) -> nanoseconds. */
        fun rawToNanos(raw: Long): Long {
            val sec = raw ushr 32
            val frac = raw and 0xFFFFFFFFL
            return sec * 1_000_000_000L + ((frac * 1_000_000_000L) ushr 32)
        }

        fun nanosToRaw(nanos: Long): Long {
            val sec = nanos / 1_000_000_000L
            val ns = nanos % 1_000_000_000L
            return (sec shl 32) or ((ns shl 32) / 1_000_000_000L)
        }

        /**
         * SETRATEANCHORTIME `networkTimeSecs` + `networkTimeFrac` (uint64 fraction of a second, 2^-64) -> master nanos
         * on the same scale as [rawToNanos]. ASSUMPTION: in NTP mode the anchor uses the sender's timing clock.
         */
        fun anchorMasterNanos(secs: Long, frac64: Long): Long =
            secs * 1_000_000_000L + (((frac64 ushr 32) * 1_000_000_000L) ushr 32)

        private fun readLong(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }
    }
}
