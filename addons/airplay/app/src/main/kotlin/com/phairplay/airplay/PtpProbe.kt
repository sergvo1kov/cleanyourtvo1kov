package com.phairplay.airplay

import com.phairplay.util.Logger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicLong

/** SETUP #1 answer variant for the PTP experiment (one per build; override at runtime via `setprop debug.phairplay.ptpwire <v>`). */
internal const val PTP_WIRE_VARIANT = "a4"   // v0 baseline | v1 ClockPorts | v2 timingPort | v3 both | a1 async /command | a2 full timingPeerInfo+PTPInfo | a3 a1+a2 | a4 a3+own ClockPorts
// b1 full+ClockPorts{senderClockID,ownClockID}->evt | b2 full+ClockPorts{sender UUID,own ID}->evt | b3 full+ClockPorts [evt,gen] list
// b4 b1+eventPort=TCP&UDP 3319, timingPort=3320 | b5 b1 IPv4-only Addresses | b6 b1+async /command | b7 b1+PtpSlave solicitation (Announce/Signaling to peer 320+319) | b8 b4+b6+b7

internal fun ptpWireVariant(): String {
    val p = runCatching {
        Runtime.getRuntime().exec(arrayOf("getprop", "debug.phairplay.ptpwire")).inputStream.bufferedReader().readText().trim()
    }.getOrDefault("")
    return if (p in setOf("v0", "v1", "v2", "v3", "a1", "a2", "a3", "a4", "b1", "b2", "b3", "b4", "b5", "b6", "b7", "b8")) p else PTP_WIRE_VARIANT
}

/**
 * Passive PTP listener on two high UDP ports (event ~319, general ~320 analogues). Logs everything received
 * with tag `MR: PTP probe`; never replies. Lives for one multi-room session.
 */
internal class PtpProbe(private val slavePeer: String? = null) : AutoCloseable {
    private var slave: PtpSlave? = null
    private val event: DatagramSocket = bind(3319)
    private val general: DatagramSocket = bind(3320, event.localPort)
    val eventPort: Int = event.localPort
    val generalPort: Int = general.localPort
    private val count = AtomicLong()
    @Volatile private var closed = false

    private fun bind(start: Int, skip: Int = -1): DatagramSocket {
        for (p in start until start + 200) {
            if (p == skip) continue
            try { return DatagramSocket(p) } catch (_: Exception) {}   // wildcard bind, dual-stack (v4+v6)
        }
        return DatagramSocket(0)
    }

    fun start() {
        if (slavePeer != null) {
            // b7/b8: hand the two ports to PtpSlave (it solicits Sync via Announce/Signaling to the sender), keep raw logging.
            event.close(); general.close()
            val sl = PtpSlave(eventPort, generalPort)
            sl.helloEventToo = true
            sl.rawLog = { name, a, port, buf, n -> logPacket(name, a.hostAddress ?: "?", port, buf, n) }
            sl.start(); sl.setMasterHint(slavePeer.substringBefore('%'))
            slave = sl
            Logger.i("MR: PTP probe(slave mode) event=$eventPort general=$generalPort hint=$slavePeer")
            Thread({
                var last = 0L
                while (!closed) {
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                    val c = count.get()
                    if (c != last) { Logger.i("MR: PTP probe count=$c (+${c - last}/s) slaveSynced=${sl.synced} gm=${sl.clockId}"); last = c }
                }
            }, "ptp-probe-count").also { it.isDaemon = true }.start()
            return
        }
        Logger.i("MR: PTP probe listening event=$eventPort general=$generalPort")
        listOf(event to "event", general to "general").forEach { (s, name) ->
            Thread({ loop(s, name) }, "ptp-probe-$name").also { it.isDaemon = true }.start()
        }
        Thread({
            var last = 0L
            while (!closed) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                val c = count.get()
                if (c != last) { Logger.i("MR: PTP probe count=$c (+${c - last}/s)"); last = c }
            }
        }, "ptp-probe-count").also { it.isDaemon = true }.start()
    }

    private fun logPacket(name: String, host: String, port: Int, buf: ByteArray, n: Int) {
        count.incrementAndGet()
        val head = (0 until minOf(64, n)).joinToString(" ") { "%02x".format(buf[it]) }
        var meta = ""
        if (n >= 34) {
            val type = buf[0].toInt() and 0x0F
            val ver = buf[1].toInt() and 0x0F
            val domain = buf[4].toInt() and 0xFF
            val clk = (20 until 28).joinToString("") { "%02x".format(buf[it]) }
            meta = " type=$type ver=$ver domain=$domain clockId=$clk"
        }
        Logger.i("MR: PTP probe $name from $host:$port len=$n$meta head=$head")
    }

    private fun loop(s: DatagramSocket, name: String) {
        val buf = ByteArray(2048)
        val dp = DatagramPacket(buf, buf.size)
        try {
            while (!closed) {
                dp.length = buf.size
                s.receive(dp)
                logPacket(name, dp.address.hostAddress ?: "?", dp.port, buf, dp.length)
            }
        } catch (e: Exception) {
            if (!closed) Logger.w("MR: PTP probe $name ended: ${e.message}")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        Logger.i("MR: PTP probe closed, total packets=${count.get()}")
        runCatching { slave?.stop() }
        runCatching { event.close() }
        runCatching { general.close() }
    }
}

/** Random int64 ClockID, stable for the app process lifetime. */
internal val PTP_CLOCK_ID: Long by lazy { java.security.SecureRandom().nextLong() }

internal fun ptpSendsAsyncCommand(v: String) = v == "a1" || v == "a3" || v == "a4" || v == "b6" || v == "b8"
internal fun ptpFullPeerInfo(v: String) = v == "a2" || v == "a3" || v == "a4" || v.startsWith("b")
internal fun ptpSlaveMode(v: String) = v == "b7" || v == "b8"
internal fun ptpSharedPorts(v: String) = v == "b4" || v == "b8"
internal fun ptpAdvertisesInfo(v: String) = ptpFullPeerInfo(v)
