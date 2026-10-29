package com.phairplay.airplay

import android.content.Context
import com.phairplay.airplay.handshake.PlistCodec
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class VideoHttpResponseFramingTest {
    @Test fun `empty video control responses declare zero length on the wire`() {
        val handler = RtspHandler(mockk<Context>(relaxed = true), videoSurfaceProvider = { null },
            onStreamingStarted = {}, onStreamingStopped = {})
        val route = RtspHandler::class.java.getDeclaredMethod("routeAirPlayHttp", RtspRequest::class.java)
            .apply { isAccessible = true }
        val send = RtspHandler::class.java.getDeclaredMethod("sendResponse", OutputStream::class.java,
            RtspResponse::class.java).apply { isAccessible = true }
        for ((path, body) in listOf(
            "/action" to PlistCodec.encode(mapOf("type" to "playlistRemove", "params" to emptyMap<String, Any>())),
            "/stop" to ByteArray(0),
            "/rate?value=0.000000" to ByteArray(0),
        )) {
            val request = RtspRequest("POST", path, emptyMap(), "", bodyBytes = body, protocol = "HTTP/1.1")
            val response = route.invoke(handler, request) as RtspResponse
            assertEquals(200, response.statusCode)
            val bytes = ByteArrayOutputStream()
            send.invoke(handler, bytes, response)
            val wire = bytes.toString(Charsets.US_ASCII.name())
            assertTrue(path, wire.startsWith("HTTP/1.1 200 OK\r\n"))
            assertTrue(path, wire.contains("\r\nContent-Length: 0\r\n"))
            assertTrue(path, wire.endsWith("\r\n\r\n"))
        }
    }
}
