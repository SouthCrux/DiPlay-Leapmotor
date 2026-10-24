// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.Opack.Array
import com.shilapi.xcertplay.airplay.Opack.Bool
import com.shilapi.xcertplay.airplay.Opack.Data
import com.shilapi.xcertplay.airplay.Opack.Dict
import com.shilapi.xcertplay.airplay.Opack.Integer
import com.shilapi.xcertplay.airplay.Opack.Null
import com.shilapi.xcertplay.airplay.Opack.Real
import com.shilapi.xcertplay.airplay.Opack.Text
import com.shilapi.xcertplay.airplay.Opack.Value
import com.shilapi.xcertplay.airplay.Opack.dict
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable

class VehicleStateRadioIntegrationTest {
    private var now = 100_000_000_000L
    private var range: VehicleStatusSnapshot? = VehicleStatusSnapshot(227, null, 53.0, null, null, null, null)
    private inner class Radio : UltraRadioBackend {
        var value: UltraRadioBackend.Snapshot? = UltraRadioBackend.Snapshot(now, true,
            listOf(UltraRadioBackend.Station("one", "One", "One", "Ensemble"),
                UltraRadioBackend.Station("two", "Two", "Two", "Ensemble")), "one", true)
        var calls = 0
        var cancellations = 0
        var completion: ((Boolean) -> Unit)? = null
        override fun verifiedDabCapabilities() = UltraRadioBackend.Capabilities(setOf(UltraRadioBackend.CommandKind.TUNE_IDENTIFIER))
        override fun snapshot() = value
        override fun request(command: UltraRadioBackend.Command, complete: (Boolean) -> Unit): Closeable {
            calls++; completion = complete
            return Closeable { cancellations++ }
        }
    }
    private class Climate : UltraClimateBackend {
        var value = 21.0
        var completion: ((Boolean) -> Unit)? = null
        override fun capabilities() = listOf(UltraClimateBackend.Capability(UltraClimateBackend.Zone.DRIVER,
            16.0, 32.0, 0.5, UltraClimateBackend.Seat.FRONT_LEFT))
        override fun snapshot() = UltraClimateBackend.Snapshot(mapOf(UltraClimateBackend.Zone.DRIVER to value))
        override fun requestTemperature(zone: UltraClimateBackend.Zone, celsius: Double, complete: (Boolean) -> Unit): Closeable {
            completion = complete
            return Closeable {}
        }
    }
    private fun protocol(radio: Radio?, climate: Climate? = null) = VehicleStatePrototype(VehicleStatusProvider { range },
        climate, radio, radioElapsedNanos = { now }, elapsedMillis = { now / 1_000_000 })
    private fun request(command: String, values: Value = Null): ByteArray = Opack.encode(dict("pluginID" to Integer(0),
        "pluginData" to Data(Opack.encode(dict("command" to Text(command), "transactionID" to Integer(73), "values" to values)))))
    private fun open(message: VehicleStatePrototype.Message) =
        Opack.decode(((Opack.decode(message.bytes) as Dict)["pluginData"] as Data).bytes()) as Dict
    private fun messages(protocol: VehicleStatePrototype) = (protocol.poll() as VehicleStatePrototype.Poll.Messages).messages
    private fun accessoryIds(protocol: VehicleStatePrototype): List<Value?> {
        val plugin = (protocol.description()["pluginConfigs"] as Array).values.single() as Dict
        return (plugin["accessories"] as Array).values.map { (it as Dict)["iid"] }
    }
    private fun tune() = dict("identifier" to Text("two"), "sourceIdentifier" to Text("DAB"))

    @Test fun configurationMergesDisjointRangeClimateAndRadioUnderOnePlugin() {
        val protocol = protocol(Radio(), Climate())
        assertEquals(Integer(1), protocol.description()["pluginCount"])
        assertEquals(listOf(Integer(1792), Integer(512), Integer(4096), Integer(4352)), accessoryIds(protocol))
        val config = protocol.handle(VehicleStatePrototype.Lane.NORMAL, request("configRequest"))
        assertEquals(1, config.size)
        assertEquals(Integer(73), open(config.single())["transactionID"])
        assertTrue(protocol.infoPlist().isNotEmpty())
    }

    @Test fun mixedRegistrationGetsOneResponseAndOneUnifiedHighLaneUpdate() {
        val radio = Radio()
        val climate = Climate()
        val protocol = protocol(radio, climate)
        val response = protocol.handle(VehicleStatePrototype.Lane.NORMAL, request("registerRequest",
            Array(listOf(Integer(1794), Integer(520), Integer(4102), Integer(9999)))))
        assertEquals(1, response.size)
        assertEquals(Dict(listOf(Integer(1794) to Integer(227), Integer(520) to Real(21.0), Integer(4102) to Text("one"))),
            open(response.single())["values"])
        assertEquals(Dict(listOf(Integer(9999) to Integer(-6727))), open(response.single())["errors"])
        range = range!!.copy(rangeKm = 220)
        climate.value = 23.0
        radio.value = radio.value!!.copy(currentIdentifier = "two")
        val update = messages(protocol).single()
        assertEquals(VehicleStatePrototype.Lane.HIGH, update.lane)
        assertEquals(Dict(listOf(Integer(1794) to Integer(220), Integer(520) to Real(23.0), Integer(4102) to Text("two"))),
            open(update)["values"])
    }

    @Test fun climateWritesAndRadioControlsHaveSeparateConfirmedReplies() {
        val radio = Radio()
        val climate = Climate()
        val protocol = protocol(radio, climate)
        assertTrue(protocol.handle(VehicleStatePrototype.Lane.NORMAL, request("writeRequest",
            Dict(listOf(Integer(520) to Real(23.0), Integer(4102) to Text("two"))))).isEmpty())
        assertTrue(protocol.handle(VehicleStatePrototype.Lane.HIGH, request("controlRequest", Dict(listOf(Integer(4364) to tune())))).isEmpty())
        climate.value = 23.0
        climate.completion!!(true)
        now++
        radio.value = radio.value!!.copy(observedAtNanos = now, currentIdentifier = "two")
        radio.completion!!(true)
        val replies = messages(protocol)
        assertEquals(2, replies.size)
        assertEquals(VehicleStatePrototype.Lane.NORMAL, replies[0].lane)
        assertEquals(Dict(listOf(Integer(520) to Integer(0), Integer(4102) to Integer(-6727))), open(replies[0])["errors"])
        assertEquals(VehicleStatePrototype.Lane.HIGH, replies[1].lane)
        assertEquals(Dict(listOf(Integer(4364) to Null)), open(replies[1])["values"])
    }

    @Test fun mixedAmbiguousControlsDoNotPartiallyDispatchRadio() {
        val radio = Radio()
        val protocol = protocol(radio, Climate())
        val result = protocol.handle(VehicleStatePrototype.Lane.NORMAL, request("controlRequest",
            Dict(listOf(Integer(4364) to tune(), Integer(520) to Real(23.0)))))
        assertEquals(0, radio.calls)
        assertEquals(Dict(listOf(Integer(4364) to Integer(-6735), Integer(520) to Integer(-6735))), open(result.single())["errors"])
    }

    @Test fun radioMissingAtDescriptionTimeNeverAppearsLaterInThatOwner() {
        assertEquals(listOf(Integer(1792)), accessoryIds(protocol(null)))
        val radio = Radio()
        val reading = radio.value
        radio.value = null
        val protocol = protocol(radio)
        radio.value = reading
        assertEquals(listOf(Integer(1792)), accessoryIds(protocol))
        val refused = protocol.handle(VehicleStatePrototype.Lane.NORMAL, request("controlRequest", Dict(listOf(Integer(4364) to tune()))))
        assertEquals(Integer(-6735), ((open(refused.single())["errors"] as Dict).entries.single().second))
        assertEquals(0, radio.calls)
    }

    @Test fun announcedRadioLossRetiresOwnerDespiteRangeAndCancelsLateConfirmation() {
        val radio = Radio()
        val protocol = protocol(radio)
        protocol.handle(VehicleStatePrototype.Lane.NORMAL, request("controlRequest", Dict(listOf(Integer(4364) to tune()))))
        val late = radio.completion!!
        val reading = radio.value
        radio.value = null
        assertEquals(VehicleStatePrototype.Poll.SourceLost, protocol.poll())
        assertEquals(1, radio.cancellations)
        radio.value = reading
        late(true)
        assertFalse(protocol.isSourceAvailable())
        assertEquals(VehicleStatePrototype.Poll.SourceLost, protocol.poll())
        assertFalse(accessoryIds(protocol).contains(Integer(4096)))
    }

    @Test fun subscribedRangeLossCannotStayStaleWhenRadioKeepsPublishing() {
        val radio = Radio()
        val protocol = protocol(radio)
        protocol.handle(VehicleStatePrototype.Lane.NORMAL, request("registerRequest",
            Array(listOf(Integer(1794), Integer(4102)))))
        range = null
        assertEquals(VehicleStatePrototype.Poll.SourceLost, protocol.poll())
        assertFalse(protocol.isSourceAvailable())
    }

    @Test fun verifiedRadioCanServeARealDescriptionWithoutRangeOrClimate() {
        range = null
        val protocol = protocol(Radio())
        assertTrue(protocol.isSourceAvailable())
        assertEquals(listOf(Integer(4096), Integer(4352)), accessoryIds(protocol))
    }

    @Test fun resetCancelsRadioWithoutLeakingResponsesIntoFreshRegistration() {
        val radio = Radio()
        val protocol = protocol(radio)
        protocol.handle(VehicleStatePrototype.Lane.NORMAL, request("registerRequest", Array(listOf(Integer(4102)))))
        protocol.handle(VehicleStatePrototype.Lane.NORMAL, request("controlRequest", Dict(listOf(Integer(4364) to tune()))))
        val late = radio.completion!!
        protocol.reset()
        now++
        radio.value = radio.value!!.copy(observedAtNanos = now, currentIdentifier = "two")
        late(true)
        assertTrue(messages(protocol).isEmpty())
        assertEquals(1, radio.cancellations)
    }
}
