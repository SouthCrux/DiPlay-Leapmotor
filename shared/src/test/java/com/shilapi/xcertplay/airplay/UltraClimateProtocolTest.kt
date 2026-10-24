// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.Opack.Array
import com.shilapi.xcertplay.airplay.Opack.Bool
import com.shilapi.xcertplay.airplay.Opack.Data
import com.shilapi.xcertplay.airplay.Opack.Dict
import com.shilapi.xcertplay.airplay.Opack.Integer
import com.shilapi.xcertplay.airplay.Opack.Real
import com.shilapi.xcertplay.airplay.Opack.Text
import com.shilapi.xcertplay.airplay.Opack.Value
import com.shilapi.xcertplay.airplay.UltraClimateBackend.Zone
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable

class UltraClimateProtocolTest {
    private class Backend : UltraClimateBackend {
        var readings: UltraClimateBackend.Snapshot? = UltraClimateBackend.Snapshot(mapOf(Zone.DRIVER to 21.0, Zone.PASSENGER to 22.0))
        var limits = Zone.entries.map { UltraClimateBackend.Capability(it, 16.0, 32.0, 0.5,
            if (it == Zone.DRIVER) UltraClimateBackend.Seat.FRONT_LEFT else UltraClimateBackend.Seat.FRONT_RIGHT) }
        val callbacks = mutableMapOf<Zone, (Boolean) -> Unit>()
        var calls = 0
        var cancelled = 0
        override fun capabilities() = limits
        override fun snapshot() = readings
        override fun requestTemperature(zone: Zone, celsius: Double, complete: (Boolean) -> Unit): Closeable {
            calls++
            callbacks[zone] = complete
            return Closeable { cancelled++ }
        }
    }
    private fun engine(backend: Backend, time: () -> Long = { 0L }) =
        VehicleStatePrototype(VehicleStatusProvider { null }, backend, elapsedMillis = time)
    private fun request(command: String, value: Value) = Opack.encode(Opack.dict(
        "pluginID" to Integer(0), "pluginData" to Data(Opack.encode(Opack.dict(
            "command" to Text(command), "transactionID" to Integer(42), "values" to value))),
    ))
    private fun open(message: VehicleStatePrototype.Message): Dict =
        Opack.decode(((Opack.decode(message.bytes) as Dict)["pluginData"] as Data).bytes()) as Dict
    private fun messages(engine: VehicleStatePrototype) = (engine.poll() as VehicleStatePrototype.Poll.Messages).messages
    private fun writes(vararg values: Pair<Long, Value>) = request("writeRequest", Dict(values.map { Integer(it.first) to it.second }))
    private fun climateCount(engine: VehicleStatePrototype): Int {
        val config = ((engine.description()["pluginConfigs"] as Array).values.single() as Dict)
        return (config["accessories"] as Array).values.count { (it as Dict)["iid"] == Integer(512) }
    }

    @Test fun onlyVerifiedReadableCapabilitiesAppearAndHaveNoSyntheticInitialValue() {
        val backend = Backend()
        backend.readings = null
        assertEquals(0, climateCount(engine(backend)))
        backend.readings = UltraClimateBackend.Snapshot(mapOf(Zone.DRIVER to 21.0))
        backend.limits = backend.limits.map { it.copy(stepCelsius = Double.NaN) }
        assertEquals(0, climateCount(engine(backend)))
        backend.limits = listOf(UltraClimateBackend.Capability(Zone.DRIVER,16.0,32.0,0.5,UltraClimateBackend.Seat.FRONT_LEFT))
        val engine = engine(backend)
        assertEquals(1, climateCount(engine))
        assertTrue(engine.infoPlist().isNotEmpty())
        val result = open(engine.handle(VehicleStatePrototype.Lane.NORMAL,
            request("registerRequest", Array(listOf(Integer(520), Integer(534))))).single())
        assertEquals(Dict(listOf(Integer(520) to Real(21.0))), result["values"])
        assertEquals(Dict(listOf(Integer(534) to Integer(-6727))), result["errors"])
    }

    @Test fun physicalSeatIsExplicitAndClimateOnlyDoesNotAdvertiseRange() {
        val backend = Backend()
        backend.limits = listOf(UltraClimateBackend.Capability(Zone.DRIVER, 16.0, 32.0, 0.5,
            UltraClimateBackend.Seat.FRONT_RIGHT))
        val engine = engine(backend)
        val config = ((engine.description()["pluginConfigs"] as Array).values.single() as Dict)
        val accessories = (config["accessories"] as Array).values
        assertEquals(1, accessories.size)
        assertEquals(Integer(512), (accessories.single() as Dict)["iid"])
        val result = open(engine.handle(VehicleStatePrototype.Lane.NORMAL,
            request("registerRequest", Array(listOf(Integer(522))))).single())
        assertEquals(Dict(listOf(Integer(522) to Text("seat_front_right"))), result["values"])
    }

    @Test fun writesWaitForActualObservationThenReplyOnRequestLaneAndNotifyHigh() {
        val backend = Backend()
        val engine = engine(backend)
        engine.handle(VehicleStatePrototype.Lane.NORMAL, request("registerRequest", Array(listOf(Integer(520)))))
        assertTrue(engine.handle(VehicleStatePrototype.Lane.NORMAL, writes(520L to Real(23.0))).isEmpty())
        assertTrue(messages(engine).isEmpty())
        backend.readings = UltraClimateBackend.Snapshot(mapOf(Zone.DRIVER to 23.0, Zone.PASSENGER to 22.0))
        backend.callbacks.getValue(Zone.DRIVER)(true)
        val replies = messages(engine)
        assertEquals(2, replies.size)
        assertEquals(VehicleStatePrototype.Lane.NORMAL, replies[0].lane)
        assertEquals(Integer(42), open(replies[0])["transactionID"])
        assertEquals(Dict(listOf(Integer(520) to Integer(0))), open(replies[0])["errors"])
        assertEquals(VehicleStatePrototype.Lane.HIGH, replies[1].lane)
        assertEquals(Dict(listOf(Integer(520) to Real(23.0))), open(replies[1])["values"])
        assertEquals(1, backend.cancelled)
    }

    @Test fun backendAcceptanceWithoutMatchingReadingCannotReportSuccess() {
        val backend = Backend()
        val engine = engine(backend)
        engine.handle(VehicleStatePrototype.Lane.HIGH, writes(520L to Real(23.0)))
        backend.callbacks.getValue(Zone.DRIVER)(true)
        assertEquals(Dict(listOf(Integer(520) to Integer(-6727))), open(messages(engine).single())["errors"])
        assertEquals(21.0, backend.readings!!.targetsCelsius[Zone.DRIVER]!!, 0.0)
    }

    @Test fun invalidValuesAndConcurrentRequestsDoNotReachVehicle() {
        val backend = Backend()
        val engine = engine(backend)
        listOf(Real(15.5), Real(32.5), Real(21.25), Real(Double.NaN), Text("23"), Bool(true)).forEach {
            assertEquals(Dict(listOf(Integer(520) to Integer(-6727))),
                open(engine.handle(VehicleStatePrototype.Lane.NORMAL, writes(520L to it)).single())["errors"])
        }
        assertEquals(0, backend.calls)
        engine.handle(VehicleStatePrototype.Lane.NORMAL, writes(520L to Real(23.0)))
        assertEquals(1, backend.calls)
        assertEquals(1, engine.handle(VehicleStatePrototype.Lane.HIGH, writes(534L to Real(24.0))).size)
        assertEquals(1, backend.calls)
    }

    @Test fun timeoutAndResetCancelWorkAndIgnoreLateConfirmation() {
        val backend = Backend()
        var now = 0L
        val engine = engine(backend) { now }
        engine.handle(VehicleStatePrototype.Lane.NORMAL, writes(520L to Real(23.0)))
        now = 10_000
        assertEquals(Dict(listOf(Integer(520) to Integer(-6727))), open(messages(engine).single())["errors"])
        backend.callbacks.getValue(Zone.DRIVER)(true)
        assertTrue(messages(engine).isEmpty())
        engine.handle(VehicleStatePrototype.Lane.NORMAL, writes(520L to Real(23.0)))
        engine.reset()
        backend.callbacks.getValue(Zone.DRIVER)(true)
        assertTrue(messages(engine).isEmpty())
        assertEquals(2, backend.cancelled)
    }

    @Test fun completedReplyQueueIsBoundedUntilPollingDrainsIt() {
        val backend = Backend()
        val engine = engine(backend)
        repeat(16) {
            assertTrue(engine.handle(VehicleStatePrototype.Lane.NORMAL, writes(520L to Real(23.0))).isEmpty())
            backend.callbacks.getValue(Zone.DRIVER)(false)
        }
        assertEquals(1, engine.handle(VehicleStatePrototype.Lane.NORMAL, writes(520L to Real(23.0))).size)
        assertEquals(16, backend.calls)
        assertEquals(16, messages(engine).size)
        assertTrue(engine.handle(VehicleStatePrototype.Lane.NORMAL, writes(520L to Real(23.0))).isEmpty())
        assertEquals(17, backend.calls)
    }

    @Test fun losingClimateReadingsRequiresChannelClosureEvenWhenWritePending() {
        val backend = Backend()
        val engine = engine(backend)
        assertTrue(engine.isSourceAvailable())
        engine.handle(VehicleStatePrototype.Lane.NORMAL, writes(520L to Real(23.0)))
        backend.readings = null
        assertFalse(engine.isSourceAvailable())
        assertEquals(VehicleStatePrototype.Poll.SourceLost, engine.poll())
        backend.callbacks.getValue(Zone.DRIVER)(true)
        assertEquals(1, backend.cancelled)
    }
}
