package com.phairplay.airplay.handshake

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups

/**
 * HomeKit Pair-Setup (M1–M6) with a user-entered PIN, as used by AirPlay when the receiver shows a code.
 * One instance per TCP connection; the PIN lives in the caller and survives reconnects.
 * Spec: HAP "Pair Setup" (SRP-3072/SHA-512, username "Pair-Setup", Ed25519 identity exchange).
 */
class HapPinSetup(
    private val pin: String,
    private val keys: PairingKeys,
    private val accessoryId: String,
    private val onPaired: (controllerId: String, ltpk: ByteArray) -> Unit,
    private val random: SecureRandom = SecureRandom(),
) {
    private val n = SRP6StandardGroups.rfc5054_3072.n
    private val g = SRP6StandardGroups.rfc5054_3072.g
    private var stage = 1
    private var salt = ByteArray(0)
    private var privateKey = BigInteger.ZERO
    private var publicKey = BigInteger.ZERO
    private var verifier = BigInteger.ZERO
    private var sessionKey = ByteArray(0)

    val finished: Boolean get() = stage == 7

    /** Returns the response TLV. Throws [HapAuthException] on a wrong PIN / bad signature. */
    fun handle(body: ByteArray): ByteArray {
        val fields = HapTlv.decode(body)
        val state = HapTlv.state(fields)
        if (state != stage) throw IllegalStateException("pair-setup state $state, expected $stage")
        return when (stage) {
            1 -> {
                require(fields[HapTlv.METHOD]?.let { it.isEmpty() || it[0].toInt() == 0 } ?: true)
                salt = ByteArray(16).also { random.nextBytes(it) }
                privateKey = BigInteger(256, random).add(BigInteger.ONE)
                verifier = g.modPow(number(hash(salt, hash("Pair-Setup:$pin".toByteArray()))), n)
                val k = number(hash(pad(n), pad(g)))
                publicKey = k.multiply(verifier).add(g.modPow(privateKey, n)).mod(n)
                stage = 3
                HapTlv.encode(HapTlv.STATE to byteArrayOf(2), HapTlv.SALT to salt, HapTlv.PUBLIC_KEY to pad(publicKey))
            }
            3 -> {
                val a = number(requireNotNull(fields[HapTlv.PUBLIC_KEY]))
                require(a > BigInteger.ZERO && a < n && a.mod(n) != BigInteger.ZERO)
                val proof = requireNotNull(fields[HapTlv.PROOF])
                val u = number(hash(pad(a), pad(publicKey)))
                require(u != BigInteger.ZERO)
                val shared = a.multiply(verifier.modPow(u, n)).modPow(privateKey, n)
                val key = hash(bytes(shared))
                val hn = hash(bytes(n)); val hg = hash(bytes(g))
                val xor = ByteArray(64) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
                val expected = hash(xor, hash("Pair-Setup".toByteArray()), salt, bytes(a), bytes(publicKey), key)
                if (!MessageDigest.isEqual(expected, proof)) { stage = -1; throw HapAuthException("wrong PIN") }
                sessionKey = key
                stage = 5
                HapTlv.encode(HapTlv.STATE to byteArrayOf(4), HapTlv.PROOF to hash(bytes(a), expected, key))
            }
            5 -> {
                val encKey = HapCrypto.hkdf(sessionKey, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
                val inner = try { HapTlv.decode(HapCrypto.open(encKey, "PS-Msg05", requireNotNull(fields[HapTlv.ENCRYPTED]))) }
                    catch (e: Exception) { stage = -1; throw HapAuthException("M5 decrypt failed") }
                val id = requireNotNull(inner[HapTlv.IDENTIFIER]); val ltpk = requireNotNull(inner[HapTlv.PUBLIC_KEY])
                val sig = requireNotNull(inner[HapTlv.SIGNATURE])
                val controllerX = HapCrypto.hkdf(sessionKey, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
                if (!HapCrypto.verifyEd25519(ltpk, controllerX + id + ltpk, sig)) { stage = -1; throw HapAuthException("controller signature") }
                onPaired(String(id, Charsets.UTF_8), ltpk)
                val accessoryX = HapCrypto.hkdf(sessionKey, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
                val accId = accessoryId.toByteArray(Charsets.UTF_8)
                val ours = keys.edPublic
                val signature = keys.sign(accessoryX + accId + ours)
                val plain = HapTlv.encode(HapTlv.IDENTIFIER to accId, HapTlv.PUBLIC_KEY to ours, HapTlv.SIGNATURE to signature)
                stage = 7
                HapTlv.encode(HapTlv.STATE to byteArrayOf(6), HapTlv.ENCRYPTED to HapCrypto.seal(encKey, "PS-Msg06", plain))
            }
            else -> error("pair-setup finished")
        }
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
}
