package com.phairplay.airplay.handshake

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException

class HapFrameCipherTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test fun matchesIndependentPythonCryptographyVector() {
        // Python cryptography ChaCha20Poly1305: key=00..1f, nonce=12 zero bytes,
        // plaintext=010203, AAD=0300. Header is prepended outside the AEAD.
        val expected = "030019ba41611b567fccdc98195a7cdfe1e09a28d2"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(expected, HapFrameCipher(key).encode(byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(1, 2, 3), HapFrameCipher(key).readFrame(ByteArrayInputStream(expected)))
    }

    @Test fun multipleFramesAndNonceProgression() {
        val data = ByteArray(2500) { (it % 251).toByte() }
        val input = ByteArrayInputStream(HapFrameCipher(key).encode(data))
        val receiver = HapFrameCipher(key)
        val output = java.io.ByteArrayOutputStream()
        repeat(3) { output.write(receiver.readFrame(input)!!) }
        assertArrayEquals(data, output.toByteArray())
        assertNull(receiver.readFrame(input))
    }

    @Test fun tamperingPermanentlyClosesDirection() {
        val frame = HapFrameCipher(key).encode(byteArrayOf(1, 2, 3))
        frame[2] = (frame[2].toInt() xor 1).toByte()
        val receiver = HapFrameCipher(key)
        assertThrows(Exception::class.java) { receiver.readFrame(ByteArrayInputStream(frame)) }
        assertThrows(IllegalStateException::class.java) { receiver.readFrame(ByteArrayInputStream(frame)) }
    }

    @Test fun partialAndOversizedFramesAreRejected() {
        assertThrows(EOFException::class.java) {
            HapFrameCipher(key).readFrame(ByteArrayInputStream(byteArrayOf(1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HapFrameCipher(key).readFrame(ByteArrayInputStream(byteArrayOf(1, 4)))
        }
    }
}
