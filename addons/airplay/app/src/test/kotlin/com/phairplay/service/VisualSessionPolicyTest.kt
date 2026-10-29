package com.phairplay.service

import com.phairplay.service.VisualSessionPolicy.Overlay
import com.phairplay.service.VisualSessionPolicy.Teardown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualSessionPolicyTest {

    // ─── overlay precedence (must match MainActivity.updateOverlay) ──────────

    @Test
    fun `overlay precedence is pin, now-playing, streaming, photo, none`() {
        assertEquals(Overlay.PIN, VisualSessionPolicy.overlayFor(true, true, true, true))
        assertEquals(Overlay.NOW_PLAYING, VisualSessionPolicy.overlayFor(false, true, true, true))
        assertEquals(Overlay.STREAMING, VisualSessionPolicy.overlayFor(false, false, true, true))
        assertEquals(Overlay.PHOTO, VisualSessionPolicy.overlayFor(false, false, false, true))
        assertEquals(Overlay.NONE, VisualSessionPolicy.overlayFor(false, false, false, false))
    }

    @Test
    fun `only streaming and photo overlays are visual takeovers`() {
        assertTrue(VisualSessionPolicy.isVisual(Overlay.STREAMING))
        assertTrue(VisualSessionPolicy.isVisual(Overlay.PHOTO))
        assertFalse("Back on Now Playing must not end anything", VisualSessionPolicy.isVisual(Overlay.NOW_PLAYING))
        assertFalse(VisualSessionPolicy.isVisual(Overlay.PIN))
        assertFalse(VisualSessionPolicy.isVisual(Overlay.NONE))
    }

    // ─── teardown choice ─────────────────────────────────────────────────────

    @Test
    fun `audio-only or idle is never touched`() {
        assertEquals(Teardown.NONE, VisualSessionPolicy.teardownFor(false, false, false))
        assertEquals(Teardown.NONE, VisualSessionPolicy.teardownFor(false, false, true))
    }

    @Test
    fun `mirror alone closes the connection so the sender sees it end`() {
        assertEquals(Teardown.CLOSE_CONNECTION, VisualSessionPolicy.teardownFor(true, false, false))
    }

    @Test
    fun `photo alone closes the connection`() {
        assertEquals(Teardown.CLOSE_CONNECTION, VisualSessionPolicy.teardownFor(false, true, false))
    }

    @Test
    fun `mirror with independent audio stops only the mirror streams`() {
        assertEquals(Teardown.END_MIRROR_STREAMS, VisualSessionPolicy.teardownFor(true, false, true))
        assertEquals(Teardown.END_MIRROR_STREAMS, VisualSessionPolicy.teardownFor(true, true, true))
    }

    @Test
    fun `photo with independent audio never cuts the audio`() {
        assertEquals(Teardown.NONE, VisualSessionPolicy.teardownFor(false, true, true))
    }
}
