package com.phairplay.airplay.handshake

import org.junit.Assert.*
import org.junit.Test

class HapTransientPairingTest {
    private fun message(vararg fields: Pair<Int, ByteArray>) = HapTransientPairing.encode(*fields)
    private fun m1() = message(6 to byteArrayOf(1), 0 to byteArrayOf(0), 19 to byteArrayOf(16))

    @Test fun requiresTransientFlagAndCorrectSequence() {
        val pairing = HapTransientPairing()
        assertThrows(IllegalArgumentException::class.java) {
            pairing.handle(message(6 to byteArrayOf(1), 0 to byteArrayOf(0)))
        }
        assertFalse(pairing.complete)
        assertThrows(IllegalArgumentException::class.java) { pairing.handle(m1()) }
        assertThrows(IllegalStateException::class.java) { pairing.key("Control-Salt", "Control-Read-Encryption-Key") }
    }

    @Test fun rejectsZeroPublicKeyAndNeverEnablesEncryption() {
        val pairing = HapTransientPairing()
        val response = HapTransientPairing.decode(pairing.handle(m1()))
        assertEquals(384, response[3]!!.size)
        assertEquals(16, response[2]!!.size)
        assertThrows(IllegalArgumentException::class.java) {
            pairing.handle(message(6 to byteArrayOf(3), 3 to byteArrayOf(0), 4 to ByteArray(64)))
        }
        assertFalse(pairing.complete)
    }

    @Test fun rejectsTruncatedTlvAndWrongProof() {
        assertThrows(IllegalArgumentException::class.java) { HapTransientPairing.decode(byteArrayOf(3, 5, 1)) }
        val pairing = HapTransientPairing()
        pairing.handle(m1())
        assertThrows(IllegalArgumentException::class.java) {
            pairing.handle(message(6 to byteArrayOf(3), 3 to byteArrayOf(5), 4 to ByteArray(64)))
        }
        assertFalse(pairing.complete)
    }
}
