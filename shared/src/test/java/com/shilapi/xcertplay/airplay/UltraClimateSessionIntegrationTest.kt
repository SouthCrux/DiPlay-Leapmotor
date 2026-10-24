// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Closeable
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UltraClimateSessionIntegrationTest {
    private class Backend : UltraClimateBackend, Closeable {
        val closes = AtomicInteger()
        override fun capabilities() = listOf(UltraClimateBackend.Capability(
            UltraClimateBackend.Zone.DRIVER, 16.0, 32.0, 0.5, UltraClimateBackend.Seat.FRONT_LEFT))
        override fun snapshot() = UltraClimateBackend.Snapshot(mapOf(UltraClimateBackend.Zone.DRIVER to 21.0))
        override fun requestTemperature(zone: UltraClimateBackend.Zone, celsius: Double,
            complete: (Boolean) -> Unit): Closeable {
            complete(false)
            return Closeable {}
        }
        // Deliberately retain raw cached readings: the lease proxy must revoke them independently.
        override fun close() { closes.incrementAndGet() }
    }
    private fun config(enabled: Boolean) = AirPlayConfig("test", "02:00:00:00:00:02",
        "02:00:00:00:00:01", "1.0", AirPlayDisplayConfig(800, 480), vehicleStateProtocol = enabled)
    private fun session(engine: CarPlayMediaEngine, enabled: Boolean = true) = AirPlaySession(Socket(),
        config(enabled), AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, engine)
    private fun handle(session: AirPlaySession, method: String, path: String,
        fields: Map<String, Any>? = null): RtspMessage.Response =
        AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java)
            .apply { isAccessible = true }
            .invoke(session, RtspMessage.Request(method, path, "RTSP/1.0", emptyMap(),
                fields?.let(BplistCodec::encode) ?: byteArrayOf())) as RtspMessage.Response
    private fun info(session: AirPlaySession) = BplistCodec.decode(handle(session, "GET", "/info").body) as Map<*, *>
    private fun setup(session: AirPlaySession, features: List<String>) =
        BplistCodec.decode(handle(session, "SETUP", "/", mapOf("features" to features)).body) as Map<*, *>

    @Test fun emptyRegistryDoesNotAdvertiseOrNegotiateClimateOnlyVehicleServices() {
        UltraClimateSessionRegistry().use { registry ->
            val engine = CarPlayMediaEngine(object : MediaSink {}, climateSessionRegistry = registry)
            session(engine).use { session ->
                assertFalse(info(session).containsKey("vehicleStateProtocolInfo"))
                val response = setup(session, listOf("vehicleStateProtocol"))
                assertFalse("vehicleStateProtocol" in response["enabledFeatures"] as List<*>)
                assertFalse(session.vehicleStateNegotiated)
            }
        }
    }

    @Test fun disabledVehicleProtocolNeverAcquiresAuthorizedBackend() {
        UltraClimateSessionRegistry().use { registry ->
            val opens = AtomicInteger()
            registry.register { opens.incrementAndGet(); Backend().let { UltraClimateSessionRegistry.OwnedBackend(it, it) } }
            val engine = CarPlayMediaEngine(object : MediaSink {}, climateSessionRegistry = registry)
            session(engine, false).use { session ->
                assertFalse(info(session).containsKey("vehicleStateProtocolInfo"))
                setup(session, listOf("vehicleStateProtocol"))
                assertFalse(session.vehicleStateNegotiated)
            }
            assertEquals(0, opens.get())
        }
    }

    @Test fun repeatedInfoReusesOneLeaseWhileDifferentSessionsOwnIndependentBackends() {
        UltraClimateSessionRegistry().use { registry ->
            val backends = mutableListOf<Backend>()
            registry.register {
                Backend().also(backends::add).let { UltraClimateSessionRegistry.OwnedBackend(it, it) }
            }
            val engine = CarPlayMediaEngine(object : MediaSink {}, climateSessionRegistry = registry)
            val first = session(engine)
            val second = session(engine)
            try {
                val description = info(first)["vehicleStateProtocolInfo"]
                assertNotNull(description)
                assertEquals(description, info(first)["vehicleStateProtocolInfo"])
                assertEquals(1, backends.size)
                assertNotNull(info(second)["vehicleStateProtocolInfo"])
                assertEquals(2, backends.size)
                first.close()
                assertEquals(1, backends[0].closes.get())
                assertEquals(0, backends[1].closes.get())
                assertNotNull(info(second)["vehicleStateProtocolInfo"])
                assertEquals(2, backends.size)
                second.close()
                assertEquals(1, backends[1].closes.get())
            } finally { first.close(); second.close() }
        }
    }

    @Test fun setupWithoutVehicleFeatureReleasesPreflightLeaseExactlyOnce() {
        UltraClimateSessionRegistry().use { registry ->
            val backend = Backend()
            registry.register { UltraClimateSessionRegistry.OwnedBackend(backend, backend) }
            val engine = CarPlayMediaEngine(object : MediaSink {}, climateSessionRegistry = registry)
            session(engine).use { session ->
                assertNotNull(info(session)["vehicleStateProtocolInfo"])
                assertFalse("vehicleStateProtocol" in setup(session, emptyList())["enabledFeatures"] as List<*>)
                assertFalse(session.vehicleStateNegotiated)
                assertEquals(1, backend.closes.get())
                assertFalse(info(session).containsKey("vehicleStateProtocolInfo"))
                assertEquals(1, backend.closes.get())
            }
            assertEquals(1, backend.closes.get())
        }
    }

    @Test fun reconnectCanAcquireFreshLeaseAfterPriorOptOut() {
        UltraClimateSessionRegistry().use { registry ->
            val backends = mutableListOf<Backend>()
            registry.register {
                Backend().also(backends::add).let { UltraClimateSessionRegistry.OwnedBackend(it, it) }
            }
            val engine = CarPlayMediaEngine(object : MediaSink {}, climateSessionRegistry = registry)
            session(engine).use { declined ->
                assertNotNull(info(declined)["vehicleStateProtocolInfo"])
                setup(declined, emptyList())
                assertFalse(info(declined).containsKey("vehicleStateProtocolInfo"))
                assertEquals(1, backends.size)
                assertEquals(1, backends[0].closes.get())
            }
            // Feature SETUP starts the timing/event servers once per control connection.
            // Reconnect uses a new session, rather than attempting a second initial SETUP.
            session(engine).use { selected ->
                assertTrue("vehicleStateProtocol" in setup(selected, listOf("vehicleStateProtocol"))["enabledFeatures"] as List<*>)
                assertTrue(selected.vehicleStateNegotiated)
                assertEquals(2, backends.size)
                assertEquals(0, backends[1].closes.get())
            }
            assertEquals(1, backends[1].closes.get())
        }
    }

    @Test fun optOutDuringFactoryOpeningCannotPublishLeaseAndLaterInfoDoesNotReacquire() {
        UltraClimateSessionRegistry().use { registry ->
            val opened = CountDownLatch(1)
            val release = CountDownLatch(1)
            val opens = AtomicInteger()
            val backend = Backend()
            registry.register {
                opens.incrementAndGet()
                opened.countDown()
                check(release.await(4, TimeUnit.SECONDS))
                UltraClimateSessionRegistry.OwnedBackend(backend, backend)
            }
            val engine = CarPlayMediaEngine(object : MediaSink {}, climateSessionRegistry = registry)
            session(engine).use { session ->
                val result = AtomicReference<Map<*, *>>()
                val failure = AtomicReference<Throwable>()
                val thread = Thread {
                    try { result.set(info(session)) } catch (error: Throwable) { failure.set(error) }
                }.apply { start() }
                try {
                    assertTrue(opened.await(4, TimeUnit.SECONDS))
                    setup(session, emptyList())
                } finally { release.countDown() }
                thread.join(4000)
                assertFalse(thread.isAlive)
                failure.get()?.let { throw AssertionError("Concurrent info failed", it) }
                assertFalse(result.get().containsKey("vehicleStateProtocolInfo"))
                assertEquals(1, backend.closes.get())
                assertFalse(info(session).containsKey("vehicleStateProtocolInfo"))
                assertEquals(1, opens.get())
            }
        }
    }

    @Test fun brokerRevocationRemovesDescriptionEvenIfRawBackendRetainsCachedValues() {
        UltraClimateSessionRegistry().use { registry ->
            val backend = Backend()
            val registration = registry.register { UltraClimateSessionRegistry.OwnedBackend(backend, backend) }
            val engine = CarPlayMediaEngine(object : MediaSink {}, climateSessionRegistry = registry)
            session(engine).use { session ->
                assertNotNull(info(session)["vehicleStateProtocolInfo"])
                registration.close()
                assertNotNull(backend.snapshot())
                assertFalse(info(session).containsKey("vehicleStateProtocolInfo"))
                assertEquals(1, backend.closes.get())
            }
            assertEquals(1, backend.closes.get())
        }
    }

    @Test fun sessionClosureDuringFactoryOpeningClosesCandidateAndCannotPublishLateLease() {
        UltraClimateSessionRegistry().use { registry ->
            val opened = CountDownLatch(1)
            val release = CountDownLatch(1)
            val backend = Backend()
            registry.register {
                opened.countDown()
                check(release.await(4, TimeUnit.SECONDS))
                UltraClimateSessionRegistry.OwnedBackend(backend, backend)
            }
            val engine = CarPlayMediaEngine(object : MediaSink {}, climateSessionRegistry = registry)
            val session = session(engine)
            val result = AtomicReference<Map<*, *>>()
            val failure = AtomicReference<Throwable>()
            val thread = Thread {
                try { result.set(info(session)) } catch (error: Throwable) { failure.set(error) }
            }.apply { start() }
            try {
                assertTrue(opened.await(4, TimeUnit.SECONDS))
                session.close()
            } finally { release.countDown() }
            thread.join(4000)
            assertFalse(thread.isAlive)
            failure.get()?.let { throw AssertionError("Concurrent info failed", it) }
            assertFalse(result.get().containsKey("vehicleStateProtocolInfo"))
            assertEquals(1, backend.closes.get())
        }
    }
}
