package com.phairplay.airplay.handshake

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Round-trip tests of HAP pair-setup (PIN) + pair-verify against a minimal in-test controller. */
class HapPinPairingTest {
    private val n = SRP6StandardGroups.rfc5054_3072.n
    private val g = SRP6StandardGroups.rfc5054_3072.g
    private val accessoryKeys = PairingKeys.create(ByteArray(32) { it.toByte() })
    private val accessoryId = "AA:BB:CC:DD:EE:FF"

    private fun h(vararg v: ByteArray) = MessageDigest.getInstance("SHA-512").run { v.forEach { update(it) }; digest() }
    private fun big(b: ByteArray) = BigInteger(1, b)
    private fun mb(x: BigInteger) = x.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    private fun pad(x: BigInteger) = ByteArray(384).also { val r = mb(x); r.copyInto(it, it.size - r.size) }
    private fun tlv(vararg f: Pair<Int, ByteArray>) = HapTlv.encode(*f)

    /** Runs the controller side of pair-setup; returns (controller id, LTSK, accessory LTPK). */
    private class Paired(val id: String, val ltsk: Ed25519PrivateKeyParameters, val accessoryLtpk: ByteArray)

    private fun controllerSetup(setup: HapPinSetup, pin: String): Paired {
        val m2 = HapTlv.decode(setup.handle(tlv(HapTlv.METHOD to byteArrayOf(0), HapTlv.STATE to byteArrayOf(1))))
        val salt = m2.getValue(HapTlv.SALT); val bPub = big(m2.getValue(HapTlv.PUBLIC_KEY))
        val a = BigInteger(256, SecureRandom()).add(BigInteger.ONE); val aPub = g.modPow(a, n)
        val x = big(h(salt, h("Pair-Setup:$pin".toByteArray()))); val k = big(h(pad(n), pad(g)))
        val u = big(h(pad(aPub), pad(bPub)))
        val s = bPub.subtract(k.multiply(g.modPow(x, n))).mod(n).modPow(a.add(u.multiply(x)), n)
        val key = h(mb(s))
        val xor = ByteArray(64) { (h(mb(n))[it].toInt() xor h(mb(g))[it].toInt()).toByte() }
        val m1 = h(xor, h("Pair-Setup".toByteArray()), salt, mb(aPub), mb(bPub), key)
        val m4 = HapTlv.decode(setup.handle(tlv(HapTlv.STATE to byteArrayOf(3), HapTlv.PUBLIC_KEY to pad(aPub), HapTlv.PROOF to m1)))
        assertArrayEquals(h(mb(aPub), m1, key), m4.getValue(HapTlv.PROOF))
        val ltsk = Ed25519PrivateKeyParameters(SecureRandom()); val ltpk = ltsk.generatePublicKey().encoded
        val id = "11111111-2222-3333-4444-555555555555"; val idB = id.toByteArray()
        val cx = HapCrypto.hkdf(key, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        val sig = Ed25519Signer().run { init(true, ltsk); val m = cx + idB + ltpk; update(m, 0, m.size); generateSignature() }
        val ek = HapCrypto.hkdf(key, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val inner = tlv(HapTlv.IDENTIFIER to idB, HapTlv.PUBLIC_KEY to ltpk, HapTlv.SIGNATURE to sig)
        val m6 = HapTlv.decode(setup.handle(tlv(HapTlv.STATE to byteArrayOf(5), HapTlv.ENCRYPTED to HapCrypto.seal(ek, "PS-Msg05", inner))))
        val accInner = HapTlv.decode(HapCrypto.open(ek, "PS-Msg06", m6.getValue(HapTlv.ENCRYPTED)))
        val ax = HapCrypto.hkdf(key, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
        assertTrue(HapCrypto.verifyEd25519(accInner.getValue(HapTlv.PUBLIC_KEY), ax + accInner.getValue(HapTlv.IDENTIFIER) + accInner.getValue(HapTlv.PUBLIC_KEY), accInner.getValue(HapTlv.SIGNATURE)))
        assertTrue(setup.finished)
        return Paired(id, ltsk, accInner.getValue(HapTlv.PUBLIC_KEY))
    }

    @Test fun `pair-setup with correct PIN then pair-verify yields matching control keys`() {
        val store = HashMap<String, ByteArray>()
        val setup = HapPinSetup("4321", accessoryKeys, accessoryId, { id, ltpk -> store[id] = ltpk })
        val paired = controllerSetup(setup, "4321")
        assertNotNull(store[paired.id])
        assertArrayEquals(accessoryKeys.edPublic, paired.accessoryLtpk)

        val verify = HapPairVerify(accessoryKeys, accessoryId, { store[it] })
        val eph = X25519PrivateKeyParameters(SecureRandom()); val ep = eph.generatePublicKey().encoded
        val m2 = HapTlv.decode(verify.handle(tlv(HapTlv.STATE to byteArrayOf(1), HapTlv.PUBLIC_KEY to ep)))
        val sp = m2.getValue(HapTlv.PUBLIC_KEY)
        val shared = ByteArray(32).also { X25519Agreement().apply { init(eph) }.calculateAgreement(X25519PublicKeyParameters(sp, 0), it, 0) }
        val ek = HapCrypto.hkdf(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val inner = HapTlv.decode(HapCrypto.open(ek, "PV-Msg02", m2.getValue(HapTlv.ENCRYPTED)))
        assertTrue(HapCrypto.verifyEd25519(paired.accessoryLtpk, sp + inner.getValue(HapTlv.IDENTIFIER) + ep, inner.getValue(HapTlv.SIGNATURE)))
        val idB = paired.id.toByteArray()
        val sig = Ed25519Signer().run { init(true, paired.ltsk); val m = ep + idB + sp; update(m, 0, m.size); generateSignature() }
        val m3 = tlv(HapTlv.STATE to byteArrayOf(3), HapTlv.ENCRYPTED to HapCrypto.seal(ek, "PV-Msg03", tlv(HapTlv.IDENTIFIER to idB, HapTlv.SIGNATURE to sig)))
        val m4 = HapTlv.decode(verify.handle(m3))
        assertEquals(4, HapTlv.state(m4)); assertFalse(m4.containsKey(HapTlv.ERROR))
        assertTrue(verify.complete)
        assertArrayEquals(HapCrypto.hkdf(shared, "Control-Salt", "Control-Write-Encryption-Key"), verify.key("Control-Salt", "Control-Write-Encryption-Key"))
    }

    @Test fun `wrong PIN is rejected at M3`() {
        val setup = HapPinSetup("4321", accessoryKeys, accessoryId, { _, _ -> })
        try { controllerSetup(setup, "0000"); fail("expected HapAuthException") } catch (e: HapAuthException) { /* ok */ }
    }

    @Test fun `pair-verify rejects an unknown controller`() {
        val verify = HapPairVerify(accessoryKeys, accessoryId, { null })
        val eph = X25519PrivateKeyParameters(SecureRandom()); val ep = eph.generatePublicKey().encoded
        val m2 = HapTlv.decode(verify.handle(tlv(HapTlv.STATE to byteArrayOf(1), HapTlv.PUBLIC_KEY to ep)))
        val shared = ByteArray(32).also { X25519Agreement().apply { init(eph) }.calculateAgreement(X25519PublicKeyParameters(m2.getValue(HapTlv.PUBLIC_KEY), 0), it, 0) }
        val ek = HapCrypto.hkdf(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val m3 = tlv(HapTlv.STATE to byteArrayOf(3), HapTlv.ENCRYPTED to HapCrypto.seal(ek, "PV-Msg03", tlv(HapTlv.IDENTIFIER to "x".toByteArray(), HapTlv.SIGNATURE to ByteArray(64))))
        try { verify.handle(m3); fail("expected HapAuthException") } catch (e: HapAuthException) { /* ok */ }
        assertFalse(verify.complete)
    }

    @Test fun `seal and open round-trip and tamper detection`() {
        val key = ByteArray(32) { 7 }
        val ct = HapCrypto.seal(key, "PV-Msg02", "hello".toByteArray())
        assertArrayEquals("hello".toByteArray(), HapCrypto.open(key, "PV-Msg02", ct))
        ct[0] = (ct[0].toInt() xor 1).toByte()
        try { HapCrypto.open(key, "PV-Msg02", ct); fail("tamper not detected") } catch (e: Exception) { /* ok */ }
    }
}
