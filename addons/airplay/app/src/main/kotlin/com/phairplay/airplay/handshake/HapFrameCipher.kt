package com.phairplay.airplay.handshake

import java.io.EOFException
import java.io.InputStream
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/** One HAP direction. Create separate instances for reading and writing each connection.
 * Wire format matches pyatv/auth/hap_session.py. Never reuse after authentication failure.
 */
class HapFrameCipher(key: ByteArray) {
    private val key = key.copyOf().also { require(it.size == 32) }
    private var counter = 0L
    private var failed = false

    @Synchronized fun encode(plaintext: ByteArray): ByteArray {
        check(!failed) { "HAP channel is closed" }
        val output = java.io.ByteArrayOutputStream()
        plaintext.asList().chunked(1024).forEach { chunk ->
            val block = chunk.toByteArray()
            val header = byteArrayOf(block.size.toByte(), (block.size ushr 8).toByte())
            output.write(header)
            output.write(crypt(true, header, block))
        }
        return output.toByteArray()
    }

    /** Returns null only for clean EOF between frames; partial frames fail closed. */
    @Synchronized fun readFrame(input: InputStream): ByteArray? {
        check(!failed) { "HAP channel is closed" }
        try {
            val first = input.read()
            if (first < 0) return null
            val second = input.read()
            if (second < 0) throw EOFException("Truncated HAP header")
            val size = first or (second shl 8)
            require(size <= 1024) { "Oversized HAP frame" }
            val ciphertext = ByteArray(size + 16)
            for (i in ciphertext.indices) {
                val byte = input.read()
                if (byte < 0) throw EOFException("Truncated HAP payload")
                ciphertext[i] = byte.toByte()
            }
            return crypt(false, byteArrayOf(first.toByte(), second.toByte()), ciphertext)
        } catch (e: Exception) {
            failed = true
            throw e
        }
    }

    private fun crypt(encrypt: Boolean, header: ByteArray, bytes: ByteArray): ByteArray {
        check(counter >= 0) { "HAP nonce limit reached" }
        val nonce = ByteArray(12)
        for (i in 0..7) nonce[i + 4] = (counter ushr (8 * i)).toByte()
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, header))
        val output = ByteArray(cipher.getOutputSize(bytes.size))
        val written = cipher.processBytes(bytes, 0, bytes.size, output, 0)
        cipher.doFinal(output, written)
        counter++
        return output
    }
}
