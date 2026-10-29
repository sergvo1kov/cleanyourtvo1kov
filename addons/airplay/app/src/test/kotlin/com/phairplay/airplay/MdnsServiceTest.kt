package com.phairplay.airplay

import android.content.Context
import android.net.nsd.NsdManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MdnsServiceTest — Unit tests for MdnsService.
 *
 * WHY: MdnsService is the gateway between PhairPlay and macOS discovery.
 * If the mDNS registration is wrong (wrong service type, missing TXT records),
 * macOS will never show PhairPlay in the AirPlay menu. These tests verify that
 * the registration is correct without actually using the network.
 *
 * HOW: We mock the Android [NsdManager] and [Context] to avoid needing a real
 * Android device. MockK is used to create mock objects and verify interactions.
 *
 * Test naming convention: test_[methodName]_[scenario]_[expectedResult]
 */
class MdnsServiceTest {

    // The class under test
    private lateinit var mdnsService: MdnsService

    // Mock objects — these simulate Android system services without real hardware
    private lateinit var mockContext: Context
    private lateinit var mockNsdManager: NsdManager

    @Before
    fun setup() {
        // Create mocks for Android dependencies
        mockContext = mockk(relaxed = true)
        mockNsdManager = mockk(relaxed = true)

        // Tell the mock context to return our mock NsdManager
        every { mockContext.getSystemService(Context.NSD_SERVICE) } returns mockNsdManager

        mdnsService = MdnsService(mockContext)
    }

    /**
     * Test: When start() is called, MdnsService registers exactly 2 mDNS services.
     *
     * WHY: AirPlay requires both _airplay._tcp AND _raop._tcp to be registered.
     * If either is missing, macOS may not show the device or may fail to connect.
     */
    @Test
    fun `start registers two mDNS services`() {
        mdnsService.start()

        // Verify that NsdManager.registerService was called exactly 2 times
        verify(exactly = 2) {
            mockNsdManager.registerService(any(), NsdManager.PROTOCOL_DNS_SD, any())
        }
    }

    /**
     * Test: Calling start() twice does not register services twice.
     *
     * WHY: If start() is accidentally called twice, we'd have duplicate mDNS
     * registrations, which could cause conflicts. The idempotency check must work.
     */
    @Test
    fun `start is idempotent when called twice`() {
        mdnsService.start()
        mdnsService.start()  // Second call should be ignored

        // Should still only be registered once (2 services from the first call)
        verify(exactly = 2) {
            mockNsdManager.registerService(any(), NsdManager.PROTOCOL_DNS_SD, any())
        }
    }

    /**
     * Test: When stop() is called after start(), both services are unregistered.
     *
     * WHY: When the app closes, mDNS services must be unregistered so they
     * disappear from the macOS AirPlay menu. Failing to unregister means the
     * device stays in the menu even when PhairPlay is not running.
     */
    @Test
    fun `stop unregisters services after start`() {
        mdnsService.start()
        mdnsService.stop()

        // Verify that unregisterService was called for each registered listener
        verify(exactly = 2) {
            mockNsdManager.unregisterService(any())
        }
    }

    /**
     * Test: Calling stop() without a prior start() does not crash.
     *
     * WHY: MainActivity.onDestroy() always calls receiver.stop(), even if
     * onCreate() failed before start() was called. Stop must be safe to call
     * in any state.
     */
    @Test
    fun `stop without start does not crash`() {
        // This must not throw any exception
        mdnsService.stop()
    }

    /**
     * Test: AIRPLAY_PORT is 7000 (the standard AirPlay port).
     *
     * WHY: AirPlay requires exactly port 7000. Using any other port means
     * macOS won't be able to connect to PhairPlay.
     */
    @Test
    fun `AIRPLAY_PORT is 7000`() {
        assertEquals(7000, MdnsService.AIRPLAY_PORT)
    }

    /**
     * Test: stop() without start() emits DISABLED state.
     *
     * WHY: After stop(), the UI should show the protocol as disabled
     * even if start() was never called.
     */
    @Test
    fun `stop emits DISABLED protocol state`() {
        val states = mutableListOf<com.phairplay.service.ProtocolState>()
        val service = MdnsService(mockContext, onStateChange = { states.add(it) })

        service.stop()

        assertTrue(states.contains(com.phairplay.service.ProtocolState.DISABLED))
    }

    /**
     * Test: restart() calls stop then start (2 unregistrations + 2 registrations).
     *
     * WHY: Restart must fully tear down and re-advertise so the device name
     * change from Settings takes effect immediately.
     */
    @Test
    fun `restart unregisters then re-registers services`() {
        val service = MdnsService(mockContext)
        service.start()
        service.restart()

        // After restart: stop (2 unregisters) + start (2 registers) = 4 register calls total
        // But first start = 2, restart start = 2 more
        verify(atLeast = 4) {
            mockNsdManager.registerService(any(), NsdManager.PROTOCOL_DNS_SD, any())
        }
    }

    // ─── Shutdown-race guards (regression for the "exit race" device log) ─────

    /**
     * Test: restart() after stop() must not re-register anything.
     *
     * WHY: stop() closes the RTSP connection, whose thread reports "streaming stopped", which
     * used to restart mDNS after shutdown began — a ghost picker entry with nothing listening.
     */
    @Test
    fun `restart after stop is a no-op`() {
        val service = MdnsService(mockContext)
        service.start()
        service.stop()

        service.restart()

        // Only the two registrations from the initial start(); none from the late restart().
        verify(exactly = 2) {
            mockNsdManager.registerService(any(), NsdManager.PROTOCOL_DNS_SD, any())
        }
    }

    /** start() after stop() re-arms the service (restart works again). */
    @Test
    fun `start after stop re-enables restart`() {
        val service = MdnsService(mockContext)
        service.start()
        service.stop()
        service.start()

        service.restart()

        verify(exactly = 6) {
            mockNsdManager.registerService(any(), NsdManager.PROTOCOL_DNS_SD, any())
        }
    }

    /**
     * Test: an unregisterService failure on one listener does not skip the other and does
     * not escape stop().
     */
    @Test
    fun `stop unregisters the second listener even if the first throws`() {
        val service = MdnsService(mockContext)
        service.start()
        every { mockNsdManager.unregisterService(any()) } throws
            IllegalArgumentException("listener not registered")

        service.stop()   // must not throw

        verify(exactly = 2) { mockNsdManager.unregisterService(any()) }
    }

    /** stop() twice unregisters each listener exactly once (listeners are nulled). */
    @Test
    fun `stop twice unregisters only once`() {
        val service = MdnsService(mockContext)
        service.start()
        service.stop()
        service.stop()

        verify(exactly = 2) { mockNsdManager.unregisterService(any()) }
    }

    /**
     * Test: stop() racing restart() from another thread never registers after stop, never
     * unregisters a listener twice, and never throws.
     *
     * The mock throws (like the real NsdManager) on a second unregister of the same listener,
     * which is exactly what the unserialized code did.
     */
    @Test
    fun `concurrent stop and restart never double-unregister or re-advertise after stop`() {
        val unregistered = java.util.Collections.synchronizedSet(mutableSetOf<Any>())
        var doubleUnregister = false
        every { mockNsdManager.unregisterService(any()) } answers {
            if (!unregistered.add(firstArg<Any>())) {
                doubleUnregister = true
                throw IllegalArgumentException("listener not registered")
            }
        }
        val registrationsAfterStop = java.util.concurrent.atomic.AtomicInteger()

        repeat(200) {
            unregistered.clear()
            val service = MdnsService(mockContext)
            service.start()
            val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val gate = java.util.concurrent.CountDownLatch(1)
            val restarter = Thread {
                gate.await()
                try { repeat(3) { service.restart() } } catch (t: Throwable) { errors.add(t) }
            }
            val stopper = Thread {
                gate.await()
                try { service.stop() } catch (t: Throwable) { errors.add(t) }
            }
            restarter.start(); stopper.start()
            gate.countDown()
            restarter.join(); stopper.join()

            // Once stop() has returned nothing may be (re)registered any more.
            io.mockk.clearMocks(mockNsdManager, answers = false, recordedCalls = true)
            service.restart()
            try {
                verify(exactly = 0) { mockNsdManager.registerService(any(), any(), any()) }
            } catch (t: AssertionError) {
                registrationsAfterStop.incrementAndGet()
            }
            assertTrue("iteration threw: ${errors.peek()}", errors.isEmpty())
        }
        assertEquals("registrations after stop()", 0, registrationsAfterStop.get())
        assertTrue("a listener was unregistered twice", !doubleUnregister)
    }
}
