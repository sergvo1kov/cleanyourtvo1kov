package com.phairplay.airplay.handshake

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.signers.Ed25519Signer

/** A completed HAP key agreement whose derived keys protect the control channel. */
interface HapSecureSession {
    val complete: Boolean
    fun key(salt: String, info: String): ByteArray
}

/** HAP crypto primitives (HKDF-SHA512, ChaCha20-Poly1305 with 64-bit string nonces, Ed25519). */
object HapCrypto {
    fun hkdf(secret: ByteArray, salt: String, info: String, length: Int = 32): ByteArray {
        val generator = HKDFBytesGenerator(SHA512Digest())
        generator.init(HKDFParameters(secret, salt.toByteArray(), info.toByteArray()))
        return ByteArray(length).also { generator.generateBytes(it, 0, it.size) }
    }

    private fun nonce(label: String): ByteArray {
        require(label.length == 8)
        return ByteArray(12).also { label.toByteArray(Charsets.US_ASCII).copyInto(it, 4) }
    }

    private fun aead(encrypt: Boolean, key: ByteArray, label: String, input: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce(label), ByteArray(0)))
        val out = ByteArray(cipher.getOutputSize(input.size))
        val n = cipher.processBytes(input, 0, input.size, out, 0)
        cipher.doFinal(out, n)
        return out
    }

    fun seal(key: ByteArray, label: String, plaintext: ByteArray): ByteArray = aead(true, key, label, plaintext)

    /** Throws (InvalidCipherTextException) when the authentication tag does not match. */
    fun open(key: ByteArray, label: String, ciphertext: ByteArray): ByteArray = aead(false, key, label, ciphertext)

    fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = runCatching {
        Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(publicKey, 0))
            update(message, 0, message.size)
            verifySignature(signature)
        }
    }.getOrDefault(false)
}

/** Raised for HAP protocol authentication failures (wrong PIN, unknown controller, bad signature). */
class HapAuthException(message: String) : Exception(message)

/** HAP TLV8 tags used by pair-setup / pair-verify. */
object HapTlv {
    const val METHOD = 0
    const val IDENTIFIER = 1
    const val SALT = 2
    const val PUBLIC_KEY = 3
    const val PROOF = 4
    const val ENCRYPTED = 5
    const val STATE = 6
    const val ERROR = 7
    const val SIGNATURE = 10
    const val FLAGS = 19
    const val ERR_AUTHENTICATION = 2

    fun decode(data: ByteArray): Map<Int, ByteArray> = HapTransientPairing.decode(data)
    fun encode(vararg fields: Pair<Int, ByteArray>): ByteArray = HapTransientPairing.encode(*fields)
    fun state(fields: Map<Int, ByteArray>): Int? = fields[STATE]?.singleOrNull()?.toInt()
    fun error(state: Int): ByteArray = encode(STATE to byteArrayOf(state.toByte()), ERROR to byteArrayOf(ERR_AUTHENTICATION.toByte()))
}
