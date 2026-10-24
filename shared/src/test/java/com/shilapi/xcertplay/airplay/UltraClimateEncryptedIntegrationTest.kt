// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.vehicle.leapmotor.*
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real local encrypted sockets and controller/adapter; OEM observations are explicitly simulated. */
class UltraClimateEncryptedIntegrationTest {
    private val address = InetAddress.getByName("127.0.0.1")
    private val secret = ByteArray(32) { 37 }
    private class Access : LeapmotorClimateAccess {
        val denied = AtomicBoolean()
        val observed = AtomicReference(CarPropertyReading(358614275, 49, 0, System.nanoTime(), 21f))
        val dispatched = CountDownLatch(1)
        val lastWrite = AtomicReference<Pair<Int, Float>>()
        override fun configurations(): List<LeapmotorClimateConfiguration> {
            if (denied.get()) throw SecurityException("simulated permission revoked")
            return listOf(LeapmotorClimateConfiguration(358614275, 3, java.lang.Float::class.java, 1,
                listOf(LeapmotorClimateArea(49, 16f, 32f), LeapmotorClimateArea(68, 16f, 32f))))
        }
        override fun readTarget(areaId: Int): CarPropertyReading {
            if (denied.get()) throw SecurityException("simulated permission revoked")
            return observed.get().copy(areaId = areaId)
        }
        override fun writeTarget(areaId: Int, celsius: Float) {
            if (denied.get()) throw SecurityException("simulated permission revoked")
            lastWrite.set(areaId to celsius)
            dispatched.countDown()
            // Deliberately no optimistic observation or timestamp change.
        }
    }
    private class Worker : LeapmotorClimateWorker {
        private val executor = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "test-simulated-climate-io").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        override fun execute(task: () -> Unit) = schedule(0, task)
        override fun schedule(delayMillis: Long, task: () -> Unit): Closeable {
            val future = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
            return Closeable { future.cancel(false) }
        }
        fun barrier() { executor.submit {}.get(4, TimeUnit.SECONDS) }
        override fun close() { executor.shutdownNow() }
    }
    private inner class Fixture : Closeable {
        val access = Access()
        val worker = Worker()
        val backend = LeapmotorUltraClimateBackend(
            LeapmotorClimateController(access, true, LeapmotorClimateSide.entries.associateWith { 0.5 },
                elapsedNanos = System::nanoTime),
            mapOf(UltraClimateBackend.Zone.DRIVER to LeapmotorClimateSide.LEFT,
                UltraClimateBackend.Zone.PASSENGER to LeapmotorClimateSide.RIGHT),
            worker, System::nanoTime, 5_000_000_000L, 5_000_000_000L, 50)
        val registry = UltraClimateSessionRegistry()
        val registration: Closeable
        val lease: UltraClimateSessionRegistry.Lease
        val runtime: VehicleStateRuntime
        val high: Peer
        val normal: Peer
        init {
            assertTrue(backend.refresh())
            worker.barrier()
            registration = registry.register { UltraClimateSessionRegistry.OwnedBackend(backend, backend) }
            lease = registry.acquire()!!
            runtime = VehicleStateRuntime(secret, VehicleStatusProvider { null }, address, address, {}, lease.backend)
            high = Peer(runtime.setup(VehicleStatePrototype.Lane.HIGH.uuid, 3, "81")!!, "81")
            normal = Peer(runtime.setup(VehicleStatePrototype.Lane.NORMAL.uuid, 4, "82")!!, "82")
            high.send("registerRequest", Opack.Array(listOf(Opack.Integer(520))))
            assertEquals(Opack.Text("registerResponse"), high.read()["command"])
        }
        fun write() {
            normal.send("writeRequest", Opack.Dict(listOf(Opack.Integer(520) to Opack.Real(23.0))))
            assertTrue(access.dispatched.await(4, TimeUnit.SECONDS))
            assertEquals(49 to 23f, access.lastWrite.get())
        }
        override fun close() {
            runtime.close()
            lease.close()
            registration.close()
            registry.close()
            high.close()
            normal.close()
        }
    }
    private inner class Peer(bound: VehicleStateChannels.Bound, private val seed: String) : Closeable {
        val socket = Socket(address, bound.port).apply { soTimeout = 4000 }
        private fun key(direction: String) = AirPlayCrypto.hkdfSha512(secret,
            "DataStream-Salt$seed".toByteArray(), "DataStream-$direction-Encryption-Key".toByteArray())
        private val cipher = ControlCipher(key("Input"), key("Output"))
        private val parser = DataStreamPackage.Reader()
        fun send(command: String, values: Opack.Value) {
            val bytes = Opack.encode(Opack.dict("pluginID" to Opack.Integer(0), "pluginData" to Opack.Data(
                Opack.encode(Opack.dict("command" to Opack.Text(command),
                    "transactionID" to Opack.Integer(71), "values" to values)))))
            socket.getOutputStream().write(cipher.encrypt(DataStreamPackage.async(bytes)))
        }
        fun read(): Opack.Dict {
            val input = DataInputStream(socket.getInputStream())
            while (true) {
                val low = input.readUnsignedByte()
                val high = input.readUnsignedByte()
                val sealed = ByteArray((low or (high shl 8)) + 16)
                input.readFully(sealed)
                val packets = parser.feed(cipher.decrypt(byteArrayOf(low.toByte(), high.toByte()) + sealed).data)
                val packet = packets.firstOrNull() ?: continue
                val outer = Opack.decode(packet.body) as Opack.Dict
                return Opack.decode((outer["pluginData"] as Opack.Data).bytes()) as Opack.Dict
            }
        }
        override fun close() { socket.close() }
    }

    @Test fun encryptedWriteWaitsForFreshOemObservationThenConfirmsAndNotifies() {
        Fixture().use { f ->
            f.write()
            f.normal.socket.soTimeout = 150
            try { f.normal.read(); fail("Setter return must not confirm a change") }
            catch (_: SocketTimeoutException) { /* No reply until observation. */ }
            finally { f.normal.socket.soTimeout = 4000 }
            assertEquals(21f, f.access.observed.get().value)
            f.access.observed.set(CarPropertyReading(358614275, 49, 0, System.nanoTime(), 23f))
            val reply = f.normal.read()
            assertEquals(Opack.Text("writeResponse"), reply["command"])
            assertEquals(Opack.Integer(71), reply["transactionID"])
            assertEquals(Opack.Dict(listOf(Opack.Integer(520) to Opack.Integer(0))), reply["errors"])
            val update = f.high.read()
            assertEquals(Opack.Text("updateNotify"), update["command"])
            assertEquals(Opack.Dict(listOf(Opack.Integer(520) to Opack.Real(23.0))), update["values"])
        }
    }

    @Test fun revokedPermissionClosesBothLanesWithoutFalseAcknowledgement() {
        Fixture().use { f ->
            f.write()
            f.access.denied.set(true)
            assertTrue(f.backend.refresh())
            f.worker.barrier()
            assertNull(f.backend.snapshot())
            assertEquals(-1, f.normal.socket.getInputStream().read())
            assertEquals(-1, f.high.socket.getInputStream().read())
        }
    }

    @Test fun staleObservationClosesBothLanesWithoutPublishingRequestedValue() {
        Fixture().use { f ->
            f.write()
            f.access.observed.set(CarPropertyReading(358614275, 49, 0, 1, 23f))
            assertTrue(f.backend.refresh())
            f.worker.barrier()
            assertNull(f.backend.snapshot())
            assertEquals(-1, f.normal.socket.getInputStream().read())
            assertEquals(-1, f.high.socket.getInputStream().read())
        }
    }

    @Test fun sessionBrokerRevocationCancelsPendingRequestAndRetiresSocketOwners() {
        Fixture().use { f ->
            f.write()
            f.registration.close()
            assertNull(f.registry.acquire())
            assertNull(f.backend.snapshot())
            f.access.observed.set(CarPropertyReading(358614275, 49, 0, System.nanoTime(), 23f))
            assertEquals(-1, f.normal.socket.getInputStream().read())
            assertEquals(-1, f.high.socket.getInputStream().read())
        }
    }
}
