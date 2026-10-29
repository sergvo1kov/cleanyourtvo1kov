package com.phairplay.airplay

import com.phairplay.airplay.handshake.*
import io.mockk.every
import io.mockk.mockk
import java.net.InetAddress
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class HapUrlSessionTest {
    private val address = InetAddress.getByName("127.0.0.1")
    private val readKey = ByteArray(32) { 1 }
    private val writeKey = ByteArray(32) { 2 }

    private fun session(): HapUrlSession {
        val pairing = mockk<HapTransientPairing>()
        every { pairing.key("Events-Salt", "Events-Read-Encryption-Key") } returns readKey
        every { pairing.key("Events-Salt", "Events-Write-Encryption-Key") } returns writeKey
        return HapUrlSession(address, 9, pairing, PlistCodec.encode(mapOf("model" to "test")))
    }

    @Test fun encryptedEventUsesIndependentDirectionalKeysAndClosesWithSession() {
        val session = session()
        session.start()
        try {
            Socket(address, session.eventPort).use { socket ->
                socket.soTimeout = 3000
                val input = HapInputStream(socket.getInputStream(), writeKey)
                val request = RtspRequestReader(65536, 65536, 65536).read(input)!!
                assertEquals("/command", request.uri)
                assertEquals("updateInfo", PlistCodec.decode(request.bodyBytes)["type"])
                val output = HapOutputStream(socket.getOutputStream(), readKey)
                output.write("HTTP/1.1 200 OK\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n".toByteArray())
                output.flush()
                session.close()
                assertEquals(-1, input.read())
            }
        } finally { session.close() }
    }

    @Test fun closesListenerEvenIfSenderNeverConnects() {
        val session = session()
        val port = session.eventPort
        session.start()
        session.close()
        assertThrows(java.io.IOException::class.java) { Socket(address, port).close() }
        session.close()
    }
}
