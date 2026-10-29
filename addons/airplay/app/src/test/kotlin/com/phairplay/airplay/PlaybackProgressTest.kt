package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgressTest {

    private val progress = PlaybackProgress(positionMs = 10_000, durationMs = 60_000, anchorElapsedMs = 1_000)

    @Test
    fun `advances in real time while playing`() {
        assertEquals(15_000, progress.positionAt(nowElapsedMs = 6_000, paused = false))
    }

    @Test
    fun `holds while paused`() {
        assertEquals(10_000, progress.positionAt(nowElapsedMs = 6_000, paused = true))
    }

    @Test
    fun `never runs past the track end`() {
        assertEquals(60_000, progress.positionAt(nowElapsedMs = 999_000, paused = false))
    }
}
