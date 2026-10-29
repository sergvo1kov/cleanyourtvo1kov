package com.phairplay.airplay

import android.content.Context
import android.net.nsd.NsdManager
import com.phairplay.airplay.handshake.PlistCodec
import com.phairplay.service.ProtocolState
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.After

/** Exercises the real HTTP parser and receiver lifecycle with a fake media decoder and clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class UrlVideoReplacementTest {
    @After fun resetMain() { Dispatchers.resetMain() }
    @Test fun `playlist replacement preserves player and cancels timeout in both transports`() = runTest {
        for (transport in AirPlayVideoTransport.values()) for (route in listOf("routeAirPlayHttp", "routeRequest")) {
            val fixture = fixture(transport, route)
            fixture.play("https://example.test/ep1.m3u8")
            runCurrent()
            advanceTimeBy(15_000)
            fixture.remove()
            runCurrent()
            advanceTimeBy(2_000)
            verify(exactly = 0) { fixture.player.release() }
            assertFalse(fixture.states.contains(ProtocolState.ADVERTISING))
            fixture.play("https://example.test/ep1.m3u8") // Quality replacement can reuse the URL.
            advanceTimeBy(6_000)
            runCurrent()
            verify { fixture.player.play("https://example.test/ep1.m3u8", 0.0, 0.0, 2L) }
            fixture.remove()
            runCurrent()
            advanceTimeBy(2_000)
            fixture.play("https://example.test/ep2.m3u8")
            advanceTimeBy(6_000)
            runCurrent()
            verify { fixture.player.play("https://example.test/ep2.m3u8", 0.0, 0.0, 3L) }
            verify(exactly = 0) { fixture.player.release() }
            assertFalse(fixture.states.contains(ProtocolState.ADVERTISING))
        }
    }

    @Test fun `playlist removal without replacement releases only after five seconds`() = runTest {
        for (transport in AirPlayVideoTransport.values()) for (route in listOf("routeAirPlayHttp", "routeRequest")) {
            val fixture = fixture(transport, route)
            fixture.play("https://example.test/ep1.m3u8")
            fixture.remove()
            runCurrent()
            advanceTimeBy(4_999)
            verify(exactly = 0) { fixture.player.release() }
            advanceTimeBy(1)
            runCurrent()
            verify(exactly = 1) { fixture.player.release() }
            assertTrue(fixture.states.contains(ProtocolState.ADVERTISING))
        }
    }

    @Test fun `reverse channel closing during a playlist replacement must not shorten its grace period`() = runTest {
        // Real Safari sequence: playlistRemove (episode switch) starts the 5s grace period,
        // then ~130ms later Safari closes its reverse (PTTH) channel as part of the same
        // switch. That closure used to unconditionally reschedule a shorter 2s stop, ending
        // the session at ~2.1s instead of the intended 5s — killing episode switches whose
        // replacement /play was still on the way.
        for (transport in AirPlayVideoTransport.values()) for (route in listOf("routeAirPlayHttp", "routeRequest")) {
            val fixture = fixture(transport, route)
            val scheduleUrlVideoStop = AirPlayReceiver::class.java.getDeclaredMethod("scheduleUrlVideoStop")
                .apply { isAccessible = true }
            fixture.play("https://example.test/ep1.m3u8")
            fixture.remove()
            runCurrent()
            advanceTimeBy(130)
            scheduleUrlVideoStop.invoke(fixture.receiver) // reverse channel closes shortly after
            runCurrent()
            // The old bug's 2s-from-close timer would fire at 130+2000=2130ms; check just past
            // that point to actually distinguish the fix from the regression.
            advanceTimeBy(2_200 - 130)
            runCurrent()
            verify(exactly = 0) { fixture.player.release() } // must NOT have died at ~2.2s
            advanceTimeBy(5_000 - 2_200)
            runCurrent()
            verify(exactly = 1) { fixture.player.release() } // dies at the full 5s grace period
        }
    }

    @Test fun `old mirror teardown and socket close preserve URL visual ownership`() = runTest {
        val f = fixture(AirPlayVideoTransport.LEGACY, "routeAirPlayHttp")
        f.play("https://example.test/ep1.m3u8")
        advanceTimeBy(16_000)
        f.remove()
        f.play("https://example.test/ep2.m3u8")
        StreamStats.videoShowing = true
        f.teardownMirrorAudio()
        runCurrent()
        assertTrue(AirPlayReceiver::class.java.getDeclaredField("videoPlaying")
            .apply { isAccessible = true }.getBoolean(f.receiver))
        for (method in listOf("stopMirrorAudio", "onStreamingStopped", "onStreamingStopped", "stopMirrorVideo")) {
            AirPlayReceiver::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(f.receiver)
            runCurrent()
            assertTrue("$method must retain URL visual ownership", AirPlayReceiver::class.java
                .getDeclaredField("videoPlaying").apply { isAccessible = true }.getBoolean(f.receiver))
            assertTrue("$method must retain URL readiness", StreamStats.videoShowing)
        }
        advanceTimeBy(6_000)
        runCurrent()
        verify(exactly = 0) { f.player.release() }
        assertEquals(ProtocolState.CONNECTED, f.states.last())
        assertFalse(f.states.contains(ProtocolState.ADVERTISING))
        // A late audio metadata callback must not cover the URL Surface with the audio card.
        AirPlayReceiver::class.java.getDeclaredField("audioPlaying").apply { isAccessible = true }.setBoolean(f.receiver, true)
        AirPlayReceiver::class.java.getDeclaredMethod("emitNowPlaying").apply { isAccessible = true }.invoke(f.receiver)
        assertNull(f.nowPlaying.last())
        StreamStats.videoShowing = false
    }

    private fun TestScope.fixture(transport: AirPlayVideoTransport, route: String): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.NSD_SERVICE) } returns mockk<NsdManager>(relaxed = true)
        val states = mutableListOf<ProtocolState>()
        val nowPlaying = mutableListOf<NowPlayingInfo?>()
        val receiver = AirPlayReceiver(context, videoTransport = transport,
            videoSurfaceProvider = { null }, onStateChanged = { states.add(it) }, onNowPlayingChanged = { nowPlaying.add(it) })
        val player = mockk<AirPlayVideoPlayer>(relaxed = true)
        AirPlayReceiver::class.java.getDeclaredField("scope").apply { isAccessible = true }.set(receiver, this)
        AirPlayReceiver::class.java.getDeclaredField("urlVideoPlayer").apply { isAccessible = true }.set(receiver, player)
        val start = AirPlayReceiver::class.java.getDeclaredMethod("startUrlVideo", String::class.java,
            Double::class.javaPrimitiveType, Double::class.javaPrimitiveType).apply { isAccessible = true }
        val reset = AirPlayReceiver::class.java.getDeclaredMethod("resetUrlVideoSession",
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val handler = RtspHandler(context, videoSurfaceProvider = { null }, onStreamingStarted = {},
            onStreamingStopped = {
                AirPlayReceiver::class.java.getDeclaredMethod("onStreamingStopped")
                    .apply { isAccessible = true }.invoke(receiver)
            },
            onMirrorAudioStop = {
                AirPlayReceiver::class.java.getDeclaredMethod("stopMirrorAudio")
                    .apply { isAccessible = true }.invoke(receiver)
            },
            onVideoPlay = { url, fraction, seconds -> start.invoke(receiver, url, fraction, seconds) },
            onVideoPlaylistRemove = { reset.invoke(receiver, true) })
        return Fixture(handler, player, states, route, receiver, nowPlaying)
    }

    private class Fixture(val handler: RtspHandler, val player: AirPlayVideoPlayer,
                          val states: List<ProtocolState>, val route: String, val receiver: AirPlayReceiver,
                          val nowPlaying: List<NowPlayingInfo?>) {
        fun teardownMirrorAudio() {
            val request = RtspRequest("TEARDOWN", "rtsp://sender/handoff", emptyMap(), "",
                bodyBytes = PlistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 96L)))))
            val response = handler.handleTeardownInternal(request)
            assertEquals(200, response.statusCode)
        }
        fun play(url: String) = post("/play", mapOf("Content-Location" to url))
        fun remove() = post("/action", mapOf("type" to "playlistRemove", "params" to emptyMap<String, Any>()))
        private fun post(path: String, body: Map<String, Any>) {
            val request = RtspRequest("POST", path, mapOf("Content-Type" to "application/x-apple-binary-plist"),
                "", bodyBytes = PlistCodec.encode(body), protocol = "HTTP/1.1")
            val response = RtspHandler::class.java.getDeclaredMethod(route, RtspRequest::class.java)
                .apply { isAccessible = true }.invoke(handler, request) as RtspResponse
            assertEquals(200, response.statusCode)
        }
    }
}
