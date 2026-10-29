package com.phairplay.airplay

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class UrlVideoReleaseTest {
    @Test fun `release waits for a slow renderer before replacement can acquire its surface`() {
        val released = AtomicBoolean(false)
        val renderer = mockk<Renderer>(relaxed = true)
        every { renderer.trackType } returns C.TRACK_TYPE_VIDEO
        every { renderer.release() } answers {
            // A real codec still owns its native window throughout this interval.
            // Longer than Media3's default 500 ms, without needing a hardware codec in JVM tests.
            Thread.sleep(800)
            released.set(true)
        }
        val factory = RenderersFactory { _, _, _, _, _ -> arrayOf(renderer) }
        // Robolectric's virtual elapsed time does not advance during Thread.sleep/wait.
        // Use wall elapsed time so the production release deadline is actually exercised.
        val clock = object : Clock by Clock.DEFAULT {
            override fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
        }
        val player = urlVideoPlayerBuilder(RuntimeEnvironment.getApplication(), Looper.getMainLooper())
            .setRenderersFactory(factory).setClock(clock).build()
        player.release()
        assertTrue("release returned while the previous renderer still owned the Surface", released.get())
    }
}
