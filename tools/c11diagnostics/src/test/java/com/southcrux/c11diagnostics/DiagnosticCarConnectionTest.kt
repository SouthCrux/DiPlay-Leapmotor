package com.southcrux.c11diagnostics

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class DiagnosticCarConnectionTest {
    private class FakeSession : DiagnosticCarSession {
        var disconnects = 0
        override fun propertyManager(): Any = error("Connection must not query properties")
        override fun disconnect() { disconnects++ }
    }

    private class FakeFactory : DiagnosticCarFactory {
        val session = FakeSession()
        var listener: ((DiagnosticCarSession, Boolean) -> Unit)? = null
        var failure: Throwable? = null
        var synchronousReady = false
        var available = true
        var creates = 0
        override fun create(context: Context, handler: Handler, lifecycle: (DiagnosticCarSession, Boolean) -> Unit): DiagnosticCarSession? {
            creates++
            failure?.let { throw it }
            listener = lifecycle
            if (synchronousReady) lifecycle(session, true)
            return session.takeIf { available }
        }
        fun ready(value: Boolean) { listener!!(session, value) }
    }

    private fun connection(factory: FakeFactory, cleanup: Executor = Executor { it.run() }) =
        DiagnosticCarConnection(RuntimeEnvironment.getApplication(), factory = factory, cleanup = cleanup)

    @Test fun synchronousReadyIsDeliveredAfterFactoryReturnsAndOnlyOnce() {
        val factory = FakeFactory().apply { synchronousReady = true }
        val connection = connection(factory)
        val results = mutableListOf<String>()
        connection.open { session, status ->
            assertSame(factory.session, session)
            results += status
            connection.close()
        }
        assertTrue(results.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        factory.ready(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(listOf("CONNECTED_PUBLIC_API"), results)
        assertEquals(1, factory.session.disconnects)
        assertEquals(1, factory.creates)
    }

    @Test fun timeoutDisconnectsAndSuppressesLateCallbacksWithoutRetry() {
        val factory = FakeFactory()
        val connection = connection(factory)
        val results = mutableListOf<String>()
        connection.open { session, status -> assertNull(session); results += status }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        factory.ready(true)
        factory.ready(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(listOf("CONNECT_TIMEOUT"), results)
        assertEquals(1, factory.session.disconnects)
        assertEquals(1, factory.creates)
    }

    @Test fun serviceLossIsReportedOnceAndOldConnectionCannotCompleteNewAttempt() {
        val factory = FakeFactory()
        val connection = connection(factory)
        val results = mutableListOf<String>()
        connection.open { _, status -> results += status }
        factory.ready(true)
        shadowOf(Looper.getMainLooper()).idle()
        val oldListener = factory.listener!!
        factory.ready(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("CONNECTED_PUBLIC_API", "SERVICE_DISCONNECTED"), results)
        connection.open { _, status -> results += status }
        oldListener(factory.session, true)
        oldListener(factory.session, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, results.size)
        factory.ready(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("CONNECTED_PUBLIC_API", results.last())
        connection.close()
    }

    @Test fun closingBeforeQueuedReadyCancelsDeliveryAndReleasesOffCallerThread() {
        val cleanup = mutableListOf<Runnable>()
        val factory = FakeFactory().apply { synchronousReady = true }
        val connection = connection(factory, Executor { cleanup += it })
        connection.open { _, _ -> fail("Closed Activity must not receive a result") }
        connection.close()
        connection.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(0, factory.session.disconnects)
        assertEquals(1, cleanup.size)
        cleanup.single().run()
        assertEquals(1, factory.session.disconnects)
    }

    @Test fun permissionAndMissingApiFailuresAreTerminal() {
        val cases = listOf(
            SecurityException("denied") to "CONNECT_ACCESS_DENIED",
            NoClassDefFoundError("android.car.Car") to "CAR_LIBRARY_NOT_AVAILABLE",
            NoSuchMethodError("createCar") to "CAR_LIBRARY_NOT_AVAILABLE",
            IllegalStateException("failed") to "CONNECT_FAILED",
        )
        for ((failure, expected) in cases) {
            val factory = FakeFactory().apply { this.failure = failure }
            val results = mutableListOf<String>()
            connection(factory).open { session, status -> assertNull(session); results += status }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            assertEquals(listOf(expected), results)
            assertEquals(1, factory.creates)
        }
        val factory = FakeFactory().apply { available = false }
        connection(factory).open { session, status ->
            assertNull(session)
            assertEquals("CAR_API_NOT_AVAILABLE", status)
        }
    }
}
