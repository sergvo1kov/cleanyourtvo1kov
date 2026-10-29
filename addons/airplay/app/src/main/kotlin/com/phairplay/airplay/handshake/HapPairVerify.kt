package com.phairplay.airplay.handshake

import java.security.SecureRandom
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * HomeKit Pair-Verify (M1–M4) for a controller that already completed pair-setup. After success the X25519
 * shared secret is the key material of the encrypted control channel ("Control-Salt" keys). One per connection.
 */
class HapPairVerify(
    private val keys: PairingKeys,
    private val accessoryId: String,
    private val lookup: (controllerId: String) -> ByteArray?,
    private val random: SecureRandom = SecureRandom(),
) : HapSecureSession {
    private var stage = 1
    private var shared = ByteArray(0)
    private var ourPublic = ByteArray(0)
    private var theirPublic = ByteArray(0)
    private var encKey = ByteArray(0)
    var controllerId: String? = null
        private set

    override val complete: Boolean get() = stage == 5

    /** Returns the response TLV. Throws [HapAuthException] for an unknown controller / bad signature. */
    fun handle(body: ByteArray): ByteArray {
        val fields = HapTlv.decode(body)
        val state = HapTlv.state(fields)
        if (state != stage) throw IllegalStateException("pair-verify state $state, expected $stage")
        return when (stage) {
            1 -> {
                theirPublic = requireNotNull(fields[HapTlv.PUBLIC_KEY]).also { require(it.size == 32) }
                val priv = X25519PrivateKeyParameters(random)
                ourPublic = priv.generatePublicKey().encoded
                shared = ByteArray(32).also {
                    X25519Agreement().apply { init(priv) }.calculateAgreement(X25519PublicKeyParameters(theirPublic, 0), it, 0)
                }
                encKey = HapCrypto.hkdf(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
                val accId = accessoryId.toByteArray(Charsets.UTF_8)
                val signature = keys.sign(ourPublic + accId + theirPublic)
                val plain = HapTlv.encode(HapTlv.IDENTIFIER to accId, HapTlv.SIGNATURE to signature)
                stage = 3
                HapTlv.encode(
                    HapTlv.STATE to byteArrayOf(2), HapTlv.PUBLIC_KEY to ourPublic,
                    HapTlv.ENCRYPTED to HapCrypto.seal(encKey, "PV-Msg02", plain)
                )
            }
            3 -> {
                val inner = try { HapTlv.decode(HapCrypto.open(encKey, "PV-Msg03", requireNotNull(fields[HapTlv.ENCRYPTED]))) }
                    catch (e: Exception) { stage = -1; throw HapAuthException("M3 decrypt failed") }
                val id = String(requireNotNull(inner[HapTlv.IDENTIFIER]), Charsets.UTF_8)
                val sig = requireNotNull(inner[HapTlv.SIGNATURE])
                val ltpk = lookup(id) ?: run { stage = -1; throw HapAuthException("unknown controller $id") }
                if (!HapCrypto.verifyEd25519(ltpk, theirPublic + id.toByteArray(Charsets.UTF_8) + ourPublic, sig)) {
                    stage = -1; throw HapAuthException("bad controller signature")
                }
                controllerId = id
                stage = 5
                HapTlv.encode(HapTlv.STATE to byteArrayOf(4))
            }
            else -> error("pair-verify finished")
        }
    }

    override fun key(salt: String, info: String): ByteArray {
        check(complete)
        return HapCrypto.hkdf(shared, salt, info)
    }
}
