package com.phairplay.airplay

import android.content.Context
import android.view.Surface
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.*
import org.junit.Test
import android.os.HandlerThread
import android.os.Handler

class UrlVideoSurfaceTest {
    @Test fun `late valid surface attaches before prepare and repeated readiness does not reattach`() {
        mockkConstructor(HandlerThread::class, Handler::class)
        every { anyConstructed<HandlerThread>().looper } returns mockk(relaxed = true)
        var surface: Surface? = null
        val video = AirPlayVideoPlayer(mockk<Context>(relaxed = true), { surface })
        val player = mockk<ExoPlayer>(relaxed = true)
        val pending = AirPlayVideoPlayer::class.java.getDeclaredField("preparePending").apply { isAccessible = true }
        val attach = AirPlayVideoPlayer::class.java.getDeclaredMethod("attachSurfaceNow", ExoPlayer::class.java).apply { isAccessible = true }
        try {
            pending.setBoolean(video, true)
            attach.invoke(video, player)
            verify(exactly = 0) { player.prepare() }
            val invalid = mockk<Surface> { every { isValid } returns false }
            surface = invalid
            attach.invoke(video, player)
            verify(exactly = 0) { player.setVideoSurface(any()) }
            val valid = mockk<Surface>(relaxed = true) { every { isValid } returns true }
            surface = valid
            attach.invoke(video, player)
            attach.invoke(video, player)
            verifyOrder { player.setVideoSurface(valid); player.prepare() }
            verify(exactly = 1) { player.setVideoSurface(valid) }
            verify(exactly = 1) { player.prepare() }
            val recreated = mockk<Surface>(relaxed = true) { every { isValid } returns true }
            surface = recreated
            attach.invoke(video, player)
            verify(exactly = 1) { player.setVideoSurface(recreated) }
            surface = null
            attach.invoke(video, player)
            surface = recreated
            attach.invoke(video, player) // SurfaceHolder can reuse its Java Surface wrapper.
            verify(exactly = 2) { player.setVideoSurface(recreated) }
            verify(exactly = 1) { player.prepare() }
        } finally {
            video.release()
            unmockkConstructor(HandlerThread::class, Handler::class)
        }
    }
}
