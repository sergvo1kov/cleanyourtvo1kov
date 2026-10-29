package com.phairplay.airplay.handshake

import java.io.*
import org.junit.Assert.*
import org.junit.Test

class HapStreamsTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test fun fragmentedFramesRemainAContinuousStream() {
        val payload = ByteArray(3500) { (it % 251).toByte() }
        val wire = ByteArrayOutputStream()
        val writer = HapOutputStream(wire, key)
        writer.write(payload, 0, 17)
        writer.write(payload, 17, payload.size - 17)
        val source = object : FilterInputStream(ByteArrayInputStream(wire.toByteArray())) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(3, len))
        }
        val reader = HapInputStream(source, key)
        assertEquals(payload[0].toInt(), reader.read())
        assertArrayEquals(payload.copyOfRange(1, payload.size), reader.readBytes())
        assertEquals(-1, reader.read())
        assertEquals(0, reader.read(ByteArray(0)))
    }

    @Test fun partialWritePermanentlyClosesOutput() {
        var writes = 0
        val output = HapOutputStream(object : OutputStream() {
            override fun write(value: Int) { writes++; throw IOException("broken socket") }
        }, key)
        assertThrows(IOException::class.java) { output.write(byteArrayOf(1, 2, 3)) }
        assertThrows(IOException::class.java) { output.write(4) }
        assertEquals(1, writes)
    }

    @Test fun truncatedTagIsNeverReturnedAsPlaintext() {
        val frame = HapFrameCipher(key).encode(byteArrayOf(1, 2, 3))
        val input = HapInputStream(ByteArrayInputStream(frame.copyOf(frame.size - 1)), key)
        assertThrows(EOFException::class.java) { input.read() }
        assertThrows(IllegalStateException::class.java) { input.read() }
    }
}
