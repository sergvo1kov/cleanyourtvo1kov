package com.phairplay.airplay

import com.phairplay.airplay.handshake.*
import com.phairplay.util.Logger
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** Resources for one authenticated, keyless URL session; never supplies fake mirror AES keys. */
internal class HapUrlSession(
    private val peer: InetAddress,
    timingPort: Int,
    private val pairing: HapSecureSession,
    private val info: ByteArray,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listener = ServerSocket(0)
    private val timing = AirPlayNtpClient(peer, timingPort)
    private val lock = Any()
    private var closed = false
    private var socket: Socket? = null
    val eventPort: Int = listener.localPort
    val localTimingPort: Int = timing.localPort

    fun start() {
        timing.start(scope)
        scope.launch {
            try {
                var accepted: Socket
                while (true) {
                    accepted = listener.accept()
                    if (accepted.inetAddress == peer) break
                    accepted.close()
                }
                synchronized(lock) {
                    if (closed) { accepted.close(); return@launch }
                    socket = accepted
                    listener.close() // Keys/counters belong to exactly one event connection.
                }
                accepted.tcpNoDelay = true
                accepted.soTimeout = 15000
                val input = HapInputStream(accepted.getInputStream(), pairing.key("Events-Salt", "Events-Read-Encryption-Key"))
                val output = HapOutputStream(accepted.getOutputStream(), pairing.key("Events-Salt", "Events-Write-Encryption-Key"))
                val body = PlistCodec.encode(mapOf("type" to "updateInfo", "value" to PlistCodec.decode(info)))
                output.write(("POST /command HTTP/1.1\r\nCSeq: 1\r\n" +
                    "Content-Type: application/x-apple-binary-plist\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray() + body)
                output.flush()
                val reader = RtspRequestReader(65536, 65536, 65536)
                val reply = reader.read(input) ?: error("Event acknowledgement missing")
                require(reply.method == "HTTP/1.1" && reply.uri == "200" && reply.headers["CSeq"] == "1")
                Logger.i("HAP event updateInfo acknowledged")
                accepted.soTimeout = 0 // An idle event channel is not a disconnected sender.
                while (reader.read(input) != null) { /* bounded, authenticated response parsing */ }
            } catch (e: Exception) {
                synchronized(lock) { if (!closed) Logger.w("HAP event channel ended: ${e.message}") }
            } finally {
                close()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { listener.close() }
            runCatching { socket?.close() }
            timing.stop()
            scope.cancel()
        }
    }
}
