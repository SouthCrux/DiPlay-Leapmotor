package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.vehicle.leapmotor.*
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

/** Synthetic OEM input, real protocol/controller/adapter chain; no Android service or vehicle access. */
class UltraClimateEndToEndTest {
    private class Worker : LeapmotorClimateWorker {
        data class Task(val delay: Long, val run: () -> Unit, var cancelled: Boolean = false)
        private val tasks = mutableListOf<Task>()
        override fun execute(task: () -> Unit) = schedule(0, task)
        override fun schedule(delayMillis: Long, task: () -> Unit): Closeable {
            val item = Task(delayMillis, task)
            tasks += item
            return Closeable { item.cancelled = true }
        }
        fun drainImmediate() {
            while (true) {
                val item = tasks.firstOrNull { !it.cancelled && it.delay == 0L } ?: return
                tasks.remove(item); item.run()
            }
        }
        fun poll() {
            val item = tasks.filterNot { it.cancelled }.minBy { it.delay }
            tasks.remove(item); item.run(); drainImmediate()
        }
        override fun close() { tasks.clear() }
    }

    @Test fun phoneWriteReachesMappedOemZoneAndAcknowledgesOnlyAfterFreshObservedChange() {
        var now = 100_000_000_000L
        var timestamp = now - 1
        val readings = mutableMapOf(49 to 21f, 68 to 22f)
        val writes = mutableListOf<Pair<Int, Float>>()
        val access = object : LeapmotorClimateAccess {
            override fun configurations() = listOf(LeapmotorClimateConfiguration(
                358614275, 3, java.lang.Float::class.java, 1,
                listOf(LeapmotorClimateArea(49, 16f, 32f), LeapmotorClimateArea(68, 16f, 32f))))
            override fun readTarget(areaId: Int) = CarPropertyReading(358614275, areaId, 0, timestamp, readings[areaId])
            override fun writeTarget(areaId: Int, celsius: Float) { writes += areaId to celsius }
        }
        val worker = Worker()
        val controller = LeapmotorClimateController(access, true,
            LeapmotorClimateSide.entries.associateWith { 0.5 }, elapsedNanos = { now })
        // Right-hand driver is deliberate: wire zone names must not imply physical left.
        LeapmotorUltraClimateBackend(controller, mapOf(
            UltraClimateBackend.Zone.DRIVER to LeapmotorClimateSide.RIGHT,
            UltraClimateBackend.Zone.PASSENGER to LeapmotorClimateSide.LEFT), worker,
            { now }, 5_000_000_000L, 5_000_000_000L, 250).use { backend ->
            assertTrue(backend.refresh()); worker.drainImmediate()
            val protocol = VehicleStatePrototype(VehicleStatusProvider { null }, backend) { now / 1_000_000 }
            val request = Opack.encode(Opack.dict("pluginID" to Opack.Integer(0), "pluginData" to Opack.Data(
                Opack.encode(Opack.dict("command" to Opack.Text("writeRequest"),
                    "transactionID" to Opack.Integer(7),
                    "values" to Opack.Dict(listOf(Opack.Integer(520) to Opack.Real(24.0))))))))
            assertTrue(protocol.handle(VehicleStatePrototype.Lane.NORMAL, request).isEmpty())
            assertTrue(writes.isEmpty()) // Transport handler did not perform blocking OEM I/O.
            worker.drainImmediate()
            assertEquals(listOf(68 to 24f), writes)
            assertEquals(22.0, backend.snapshot()!!.targetsCelsius.getValue(UltraClimateBackend.Zone.DRIVER), 0.0)
            assertTrue((protocol.poll() as VehicleStatePrototype.Poll.Messages).messages.isEmpty())
            now += 250_000_000L
            timestamp = now
            readings[68] = 24f
            worker.poll()
            val message = (protocol.poll() as VehicleStatePrototype.Poll.Messages).messages.single()
            assertEquals(VehicleStatePrototype.Lane.NORMAL, message.lane)
            val outer = Opack.decode(message.bytes) as Opack.Dict
            val reply = Opack.decode((outer["pluginData"] as Opack.Data).bytes()) as Opack.Dict
            assertEquals(Opack.Text("writeResponse"), reply["command"])
            assertEquals(Opack.Integer(7), reply["transactionID"])
            assertEquals(Opack.Dict(listOf(Opack.Integer(520) to Opack.Integer(0))), reply["errors"])
            protocol.reset()
        }
    }
}
