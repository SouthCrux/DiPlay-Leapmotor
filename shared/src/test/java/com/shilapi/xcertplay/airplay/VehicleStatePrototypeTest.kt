package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.Opack.Array
import com.shilapi.xcertplay.airplay.Opack.Bool
import com.shilapi.xcertplay.airplay.Opack.Data
import com.shilapi.xcertplay.airplay.Opack.Date
import com.shilapi.xcertplay.airplay.Opack.Dict
import com.shilapi.xcertplay.airplay.Opack.Integer
import com.shilapi.xcertplay.airplay.Opack.Real
import com.shilapi.xcertplay.airplay.Opack.Text
import com.shilapi.xcertplay.airplay.Opack.Uuid
import com.shilapi.xcertplay.airplay.Opack.Value
import com.shilapi.xcertplay.airplay.VehicleStatePrototype.Lane
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorVehicleStatusProvider
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorEvReading
import org.junit.Assert.*
import org.junit.Test

class VehicleStatePrototypeTest {
    private var snapshot: VehicleStatusSnapshot? = VehicleStatusSnapshot(227, null, 53.1, null, null, null, null)
    private val prototype = VehicleStatePrototype(VehicleStatusProvider { snapshot })

    @Test fun diagnosticsExposeRejectedRequestsWithoutPeerTextOrValues() {
        val logs = mutableListOf<String>()
        val engine = VehicleStatePrototype(VehicleStatusProvider { snapshot }, diagnostic = { logs += it })
        assertTrue(engine.handle(Lane.HIGH, request("private spoken phrase\nforged log")).isEmpty())
        assertTrue(logs.contains("vehicle state request=unknown lane=HIGH"))
        assertTrue(logs.contains("vehicle state rejected=unsupported-command lane=HIGH"))
        assertFalse(logs.joinToString().contains("private spoken"))
        assertFalse(logs.joinToString().contains("forged log"))
        logs.clear()
        assertTrue(engine.handle(Lane.NORMAL, request("registerRequest", Array(listOf(Text("private value"))))).isEmpty())
        assertTrue(logs.contains("vehicle state rejected=register-id-type lane=NORMAL"))
        assertFalse(logs.joinToString().contains("private value"))
        logs.clear()
        assertTrue(engine.handle(Lane.HIGH, byteArrayOf()).isEmpty())
        assertTrue(logs.contains("vehicle state received bytes=0 lane=HIGH"))
        assertTrue(logs.contains("vehicle state rejected=outer-dictionary lane=HIGH"))
    }

    @Test fun diagnosticsDistinguishUnavailableRegistrationsWithoutChangingResponses() {
        val logs = mutableListOf<String>()
        val engine = VehicleStatePrototype(VehicleStatusProvider { snapshot }, diagnostic = { logs += it })
        val bytes = request("registerRequest", Array(listOf(Integer(1794), Integer(99999))))
        assertEquals(open(prototype.handle(Lane.NORMAL, bytes).single()), open(engine.handle(Lane.NORMAL, bytes).single()))
        assertTrue(logs.contains("vehicle state registration requested=2 available=1 unavailable=1 lane=NORMAL"))
        val failingLog = VehicleStatePrototype(VehicleStatusProvider { snapshot }, diagnostic = { error("logger unavailable") })
        assertEquals(1, failingLog.handle(Lane.HIGH, request("configRequest")).size)
    }

    @Test fun configContainsOnlyReadOnlyRangeAndRepliesOnTheRequestLane() {
        for (lane in Lane.entries) {
            val message = prototype.handle(lane, request("configRequest")).single()
            assertEquals(lane, message.lane)
            val response = open(message)
            assertEquals(Integer(8), response["transactionID"])
            assertEquals(Text("configResponse"), response["command"])
            val accessories = ((response["values"] as Dict)["accessories"] as Array).values
            assertEquals(1, accessories.size)
            val services = ((accessories.single() as Dict)["services"] as Array).values
            val characteristics = ((services.single() as Dict)["characteristics"] as Array).values
            assertEquals(2, characteristics.size)
            val km = characteristics[0] as Dict
            assertEquals(Integer(1794), km["iid"])
            assertEquals(Integer(0x3000004a), km["type"])
            assertEquals(Integer(151), km["units"])
            assertEquals(Integer(227), km["initialValue"])
            characteristics.forEach { assertEquals(Bool(false), (it as Dict)["writable"]) }
        }
        assertEquals(Integer(1), prototype.description()["pluginCount"])
    }

    @Test fun registrationReturnsKnownValuesAndErrorsWithoutGrowingUnknownSubscriptions() {
        val response = open(prototype.handle(Lane.NORMAL, request("registerRequest",
            Array(listOf(Integer(1794), Integer(1795), Integer(99999), Integer(1794))),
        )).single())
        assertEquals(Dict(listOf(Integer(1794) to Integer(227), Integer(1795) to Integer(141))), response["values"])
        assertEquals(Dict(listOf(Integer(99999) to Integer(-6727))), response["errors"])
        assertTrue(messages().isEmpty())
        snapshot = snapshot!!.copy(rangeKm = 226)
        val notification = messages().single()
        assertEquals(Lane.HIGH, notification.lane)
        val update = open(notification)
        assertEquals(Text("updateNotify"), update["command"])
        assertEquals(Dict(listOf(Integer(1794) to Integer(226), Integer(1795) to Integer(140))), update["values"])
        assertTrue(messages().isEmpty())
    }

    @Test fun writesAndControlsNeverAlterTelemetry() {
        listOf("writeRequest" to -6727L, "controlRequest" to -6735L).forEach { (command, error) ->
            val response = open(prototype.handle(Lane.HIGH, request(command,
                Dict(listOf(Integer(1794) to Integer(1), Integer(777) to Bool(true))),
            )).single())
            assertEquals(Dict(listOf(Integer(1794) to Integer(error), Integer(777) to Integer(error))), response["errors"])
        }
        assertEquals(227, snapshot!!.rangeKm)
        val registered = open(prototype.handle(Lane.NORMAL,
            request("registerRequest", Array(listOf(Integer(1794)))),
        ).single())
        assertEquals(Dict(listOf(Integer(1794) to Integer(227))), registered["values"])
    }

    @Test fun missingOrExpiredSourceNeverBecomesZeroAndRequiresChannelClosure() {
        var now = 1000L
        val source = LeapmotorVehicleStatusProvider(elapsedMillis = { now })
        val engine = VehicleStatePrototype(source)
        val missing = open(engine.handle(Lane.NORMAL, request("registerRequest", Array(listOf(Integer(1794))))).single())
        assertEquals(Dict(emptyList()), missing["values"])
        assertEquals(Dict(listOf(Integer(1794) to Integer(-6727))), missing["errors"])
        source.publish(LeapmotorEvReading(now, 53.1, 227))
        assertEquals(1, (engine.poll() as VehicleStatePrototype.Poll.Messages).messages.size)
        now += 60001
        assertEquals(VehicleStatePrototype.Poll.SourceLost, engine.poll())
        source.publish(LeapmotorEvReading(now, 53.1, 227))
        assertTrue((engine.poll() as VehicleStatePrototype.Poll.Messages).messages.isEmpty())
        // A fresh connection must register again.
        val newRegistration = open(engine.handle(Lane.NORMAL,
            request("registerRequest", Array(listOf(Integer(1794)))),
        ).single())
        assertEquals(Dict(listOf(Integer(1794) to Integer(227))), newRegistration["values"])
    }

    @Test fun resetAndInvalidSnapshotsClearSessionSubscriptions() {
        prototype.handle(Lane.NORMAL, request("registerRequest", Array(listOf(Integer(1794)))))
        prototype.reset()
        snapshot = snapshot!!.copy(rangeKm = 200)
        assertTrue(messages().isEmpty())
        listOf(-1, 65536).forEach {
            snapshot = snapshot!!.copy(rangeKm = it)
            assertEquals(VehicleStatePrototype.Poll.SourceLost, prototype.poll())
        }
    }

    @Test fun badEnvelopesCommandsAndValuesAreDroppedAtomically() {
        val invalid = listOf(
            byteArrayOf(), Opack.encode(Opack.dict("pluginID" to Integer(1), "pluginData" to Data(byteArrayOf(4)))),
            request("notACommand"), request("registerRequest", Array(listOf(Integer(1794), Text("bad")))),
            request("writeRequest", Array(emptyList())), request("controlRequest", Dict(listOf(Text("bad") to Bool(true)))),
            Opack.encode(Opack.dict("pluginID" to Integer(0), "pluginData" to Data(byteArrayOf(0xff.toByte())))),
        )
        invalid.forEach { assertTrue(prototype.handle(Lane.NORMAL, it).isEmpty()) }
        snapshot = snapshot!!.copy(rangeKm = 200)
        assertTrue(messages().isEmpty())
    }

    private fun messages(): List<VehicleStatePrototype.Message> =
        (prototype.poll() as VehicleStatePrototype.Poll.Messages).messages

    private fun request(command: String, values: Value = Opack.Null): ByteArray = Opack.encode(Opack.dict(
        "pluginID" to Integer(0), "pluginData" to Data(Opack.encode(Opack.dict(
            "command" to Text(command), "transactionID" to Integer(8), "values" to values,
        ))),
    ))

    private fun open(message: VehicleStatePrototype.Message): Dict {
        val outer = Opack.decode(message.bytes) as Dict
        assertEquals(Integer(0), outer["pluginID"])
        return Opack.decode((outer["pluginData"] as Data).bytes()) as Dict
    }
}
