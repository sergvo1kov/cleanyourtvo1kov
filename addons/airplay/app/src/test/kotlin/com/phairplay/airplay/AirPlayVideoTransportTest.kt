package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AirPlayVideoTransportTest — Unit tests for the AirPlayVideoTransport enum.
 *
 * WHY: The transport profile determines the device identity advertised to senders.
 * Incorrect features or model strings mean the device won't appear in sender UIs
 * or won't be selectable for specific use cases (e.g., multi-room audio).
 *
 * These tests verify that each profile has correct feature bits and string values.
 */
class AirPlayVideoTransportTest {

    @Test
    fun `LEGACY profile has correct values`() {
        val profile = AirPlayVideoTransport.LEGACY
        assertEquals("AppleTV3,2", profile.model)
        assertEquals("220.68", profile.sourceVersion)
        assertEquals("1.0", profile.protocolVersion)
        assertEquals(0x5A7FFEF7L, profile.featuresLow)
        assertEquals(0x0L, profile.featuresHigh)
        assertFalse(profile.isModern)
    }

    @Test
    fun `AIRPLAY_2 profile has correct values`() {
        val profile = AirPlayVideoTransport.AIRPLAY_2
        assertEquals("AppleTV14,1", profile.model)
        assertEquals("377.40.00", profile.sourceVersion)
        assertEquals("1.1", profile.protocolVersion)
        assertEquals(0x5A7FFEF7L, profile.featuresLow)
        assertEquals(0x0L, profile.featuresHigh)
        assertTrue(profile.isModern)
    }

    @Test
    fun `MULTIROOM profile has correct values`() {
        val profile = AirPlayVideoTransport.MULTIROOM
        assertEquals("AppleTV14,1", profile.model)
        assertEquals("377.40.00", profile.sourceVersion)
        assertEquals("1.1", profile.protocolVersion)  // Modern protocol version
        assertEquals(0x5A7FFEF7L, profile.featuresLow)  // Same as LEGACY
        assertEquals(0x10300L, profile.featuresHigh)  // Multi-room feature bits
        assertTrue(profile.isModern)  // MULTIROOM is considered modern
    }

    @Test
    fun `MULTIROOM features text is correct`() {
        val profile = AirPlayVideoTransport.MULTIROOM
        assertEquals("0x5A7FFEF7,0x10300", profile.featuresTxt)
    }

    @Test
    fun `MULTIROOM features 64-bit is correct`() {
        val profile = AirPlayVideoTransport.MULTIROOM
        val expected = (0x10300L shl 32) or 0x5A7FFEF7L
        assertEquals(expected, profile.features)
    }

    @Test
    fun `LEGACY and AIRPLAY_2 have same features`() {
        val legacy = AirPlayVideoTransport.LEGACY
        val airplay2 = AirPlayVideoTransport.AIRPLAY_2
        assertEquals(legacy.features, airplay2.features)
        assertEquals(legacy.featuresTxt, airplay2.featuresTxt)
    }

    @Test
    fun `MULTIROOM features differ from LEGACY`() {
        val multiroom = AirPlayVideoTransport.MULTIROOM
        val legacy = AirPlayVideoTransport.LEGACY
        // High word differs
        assertTrue(multiroom.featuresHigh > 0)
        assertEquals(0x0L, legacy.featuresHigh)
        // But low word is the same
        assertEquals(legacy.featuresLow, multiroom.featuresLow)
    }
}
