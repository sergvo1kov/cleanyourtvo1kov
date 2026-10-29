package com.phairplay.airplay

import android.content.Context
import android.net.nsd.NsdManager
import com.phairplay.airplay.handshake.AudioStreamServer
import com.phairplay.airplay.handshake.MirrorStreamServer
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/** A repeated SETUP in one session must close the server bound by the first SETUP. */
class MirrorSetupReplacementTest {
    @After fun cleanup() { unmockkAll() }

    @Test fun `repeated mirror video setup stops first server`() = runTest {
        mockkConstructor(MirrorStreamServer::class)
        every { anyConstructed<MirrorStreamServer>().start(any()) } just Runs
        every { anyConstructed<MirrorStreamServer>().stop() } just Runs
        every { anyConstructed<MirrorStreamServer>().dataPort } returns 12345
        val receiver = receiver()
        val start = AirPlayReceiver::class.java.getDeclaredMethod("startMirrorStream", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        start.invoke(receiver, 1L)
        val first = field(receiver, "mirrorServer") as MirrorStreamServer
        verify(exactly = 0) { first.stop() }
        start.invoke(receiver, 2L)
        verify(exactly = 1) { first.stop() }
    }

    @Test fun `repeated mirror audio setup stops first server`() = runTest {
        mockkConstructor(AudioStreamServer::class)
        every { anyConstructed<AudioStreamServer>().start(any()) } just Runs
        every { anyConstructed<AudioStreamServer>().stop() } just Runs
        every { anyConstructed<AudioStreamServer>().dataPort } returns 12345
        every { anyConstructed<AudioStreamServer>().controlPort } returns 12346
        val receiver = receiver()
        val start = AirPlayReceiver::class.java.getDeclaredMethod("startMirrorAudio",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        start.invoke(receiver, 48_000, 2, 8, 352)
        val first = field(receiver, "audioServer") as AudioStreamServer
        verify(exactly = 0) { first.stop() }
        start.invoke(receiver, 48_000, 2, 8, 352)
        verify(exactly = 1) { first.stop() }
    }

    private fun receiver(): AirPlayReceiver {
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.NSD_SERVICE) } returns mockk<NsdManager>(relaxed = true)
        return AirPlayReceiver(context, videoSurfaceProvider = { null }, onStateChanged = {}).also {
            AirPlayReceiver::class.java.getDeclaredField("mirrorAesKey").apply { isAccessible = true }.set(it, ByteArray(16))
            AirPlayReceiver::class.java.getDeclaredField("mirrorEcdhSecret").apply { isAccessible = true }.set(it, ByteArray(32))
            AirPlayReceiver::class.java.getDeclaredField("mirrorAesIv").apply { isAccessible = true }.set(it, ByteArray(16))
        }
    }

    private fun field(receiver: AirPlayReceiver, name: String): Any? =
        AirPlayReceiver::class.java.getDeclaredField(name).apply { isAccessible = true }.get(receiver)
}
