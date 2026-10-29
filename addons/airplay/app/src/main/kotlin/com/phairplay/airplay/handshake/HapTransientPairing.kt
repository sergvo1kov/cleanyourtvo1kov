package com.phairplay.airplay.handshake

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/** Connection-local, transient (M1–M4) pairing. Never use when receiver PIN access is enabled. */
class HapTransientPairing(private val random: SecureRandom = SecureRandom()) : HapSecureSession {
    private val n = SRP6StandardGroups.rfc5054_3072.n
    private val g = SRP6StandardGroups.rfc5054_3072.g
    private var stage = 1
    private var salt = ByteArray(0)
    private var privateKey = BigInteger.ZERO
    private var publicKey = BigInteger.ZERO
    private var verifier = BigInteger.ZERO
    private var secret: ByteArray? = null

    override val complete: Boolean get() = stage == 5

    fun handle(body: ByteArray): ByteArray {
        try {
            val fields = decode(body)
            val state = fields[6]?.singleOrNull()?.toInt()
            require(state == stage)
            return when (stage) {
                1 -> {
                    require(fields[0]?.contentEquals(byteArrayOf(0)) == true)
                    require(fields[19]?.contentEquals(byteArrayOf(16)) == true)
                    salt = ByteArray(16).also { random.nextBytes(it) }
                    privateKey = BigInteger(256, random).add(BigInteger.ONE)
                    verifier = g.modPow(number(hash(salt, hash("Pair-Setup:3939".toByteArray()))), n)
                    val k = number(hash(pad(n), pad(g)))
                    publicKey = k.multiply(verifier).add(g.modPow(privateKey, n)).mod(n)
                    stage = 3
                    encode(6 to byteArrayOf(2), 2 to salt, 3 to pad(publicKey))
                }
                3 -> {
                    val rawA = requireNotNull(fields[3])
                    require(rawA.size in 1..384)
                    val a = number(rawA)
                    require(a > BigInteger.ZERO && a < n)
                    val proof = requireNotNull(fields[4])
                    require(proof.size == 64)
                    val u = number(hash(pad(a), pad(publicKey)))
                    require(u != BigInteger.ZERO)
                    val shared = a.multiply(verifier.modPow(u, n)).modPow(privateKey, n)
                    val key = hash(bytes(shared))
                    val hn = hash(bytes(n))
                    val hg = hash(bytes(g))
                    val xor = ByteArray(64) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
                    val expected = hash(xor, hash("Pair-Setup".toByteArray()), salt, bytes(a), bytes(publicKey), key)
                    require(MessageDigest.isEqual(expected, proof))
                    secret = key
                    stage = 5
                    privateKey = BigInteger.ZERO
                    verifier = BigInteger.ZERO
                    encode(6 to byteArrayOf(4), 4 to hash(bytes(a), expected, key))
                }
                else -> error("Pairing already finished")
            }
        } catch (e: Exception) {
            stage = -1
            secret = null
            privateKey = BigInteger.ZERO
            verifier = BigInteger.ZERO
            throw e
        }
    }

    override fun key(salt: String, info: String): ByteArray {
        check(complete)
        val generator = HKDFBytesGenerator(SHA512Digest())
        generator.init(HKDFParameters(requireNotNull(secret), salt.toByteArray(), info.toByteArray()))
        return ByteArray(32).also { generator.generateBytes(it, 0, it.size) }
    }

    private fun number(value: ByteArray) = BigInteger(1, value)
    private fun bytes(value: BigInteger): ByteArray = value.toByteArray().let {
        if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
    }
    private fun pad(value: BigInteger): ByteArray = ByteArray(384).also {
        val raw = bytes(value); raw.copyInto(it, it.size - raw.size)
    }
    private fun hash(vararg values: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").run {
        values.forEach { update(it) }; digest()
    }

    companion object {
        fun decode(data: ByteArray): Map<Int, ByteArray> {
            require(data.size <= 4096)
            val fields = linkedMapOf<Int, ByteArray>()
            var offset = 0
            while (offset < data.size) {
                require(data.size - offset >= 2)
                val tag = data[offset++].toInt() and 255
                val size = data[offset++].toInt() and 255
                require(size <= data.size - offset)
                fields[tag] = (fields[tag] ?: ByteArray(0)) + data.copyOfRange(offset, offset + size)
                offset += size
            }
            return fields
        }

        fun encode(vararg fields: Pair<Int, ByteArray>): ByteArray = java.io.ByteArrayOutputStream().run {
            fields.forEach { (tag, value) ->
                if (value.isEmpty()) { write(tag); write(0) }
                var offset = 0
                while (offset < value.size) {
                    val size = minOf(255, value.size - offset)
                    write(tag); write(size); write(value, offset, size)
                    offset += size
                }
            }
            toByteArray()
        }
    }
}
