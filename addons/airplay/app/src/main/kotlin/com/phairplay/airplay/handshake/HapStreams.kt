package com.phairplay.airplay.handshake

import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

/** Adapts authenticated HAP frames to the byte stream consumed by the HTTP/RTSP parser. */
class HapInputStream(private val source: InputStream, key: ByteArray) : InputStream() {
    private val cipher = HapFrameCipher(key)
    private var frame = ByteArray(0)
    private var position = 0
    private var closed = false

    override fun read(): Int {
        checkOpen()
        if (!ensureFrame()) return -1
        return frame[position++].toInt() and 255
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        checkOpen()
        if (length == 0) return 0
        if (!ensureFrame()) return -1
        val count = minOf(length, frame.size - position)
        frame.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }

    private fun ensureFrame(): Boolean {
        while (position == frame.size) {
            frame = cipher.readFrame(source) ?: return false
            position = 0
        }
        return true
    }

    private fun checkOpen() { if (closed) throw IOException("HAP input closed") }
    override fun available(): Int = if (closed) 0 else frame.size - position
    override fun close() { closed = true; frame = ByteArray(0); source.close() }
}

/** One instance per connection/direction: serialized writes cannot reuse or reorder nonces. */
class HapOutputStream(private val destination: OutputStream, key: ByteArray) : OutputStream() {
    private val cipher = HapFrameCipher(key)
    private var closed = false

    @Synchronized override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

    @Synchronized override fun write(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        if (closed) throw IOException("HAP output closed")
        if (length == 0) return
        try {
            destination.write(cipher.encode(buffer.copyOfRange(offset, offset + length)))
        } catch (e: Exception) {
            // A partial TCP write makes the peer's frame/counter position unknowable.
            closed = true
            runCatching { destination.close() }
            throw e
        }
    }

    @Synchronized override fun flush() {
        if (closed) throw IOException("HAP output closed")
        try { destination.flush() } catch (e: Exception) {
            closed = true
            runCatching { destination.close() }
            throw e
        }
    }

    @Synchronized override fun close() { closed = true; destination.close() }
}
