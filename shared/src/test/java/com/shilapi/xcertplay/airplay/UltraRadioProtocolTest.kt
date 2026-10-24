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
import com.shilapi.xcertplay.airplay.UltraRadioBackend.CommandKind
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable

class UltraRadioProtocolTest {
    private var now = 100_000_000_000L
    private inner class Backend : UltraRadioBackend {
        var caps: UltraRadioBackend.Capabilities? = UltraRadioBackend.Capabilities(CommandKind.entries.toSet())
        var state: UltraRadioBackend.Snapshot? = UltraRadioBackend.Snapshot(now, true,
            listOf(UltraRadioBackend.Station("one", "One", "One", "Ensemble"),
                UltraRadioBackend.Station("two", "Two", "Two", "Ensemble")), "one", true)
        var lastCommand: UltraRadioBackend.Command? = null
        var completion: ((Boolean) -> Unit)? = null
        var calls = 0
        var reads = 0
        var cancelled = 0
        override fun verifiedDabCapabilities(): UltraRadioBackend.Capabilities? { reads++; return caps }
        override fun snapshot(): UltraRadioBackend.Snapshot? { reads++; return state }
        override fun request(command: UltraRadioBackend.Command, complete: (Boolean) -> Unit): Closeable {
            calls++; lastCommand = command; completion = complete
            return Closeable { cancelled++ }
        }
    }
    private fun engine(backend: Backend) = UltraRadioProtocol(backend, { now })
    private fun request(command: String, values: Value = Null): ByteArray = Opack.encode(dict(
        "pluginID" to Integer(0), "pluginData" to Data(Opack.encode(dict(
            "command" to Text(command), "transactionID" to Integer(9), "values" to values)))))
    private fun open(message: UltraRadioProtocol.Message) =
        Opack.decode(((Opack.decode(message.bytes) as Dict)["pluginData"] as Data).bytes()) as Dict
    private fun messages(engine: UltraRadioProtocol) = (engine.poll() as UltraRadioProtocol.Poll.Messages).messages
    private fun controls(vararg pairs: Pair<Long, Value>) = request("controlRequest", Dict(pairs.map { Integer(it.first) to it.second }))
    private fun tune(id: String, source: String = "DAB") = dict("identifier" to Text(id), "sourceIdentifier" to Text(source))
    private fun register(engine: UltraRadioProtocol, vararg ids: Long) = open(engine.handle(VehicleStatePrototype.Lane.NORMAL,
        request("registerRequest", Array(ids.map(::Integer)))).single())

    @Test fun absentUnverifiedEmptyAndStaleSourcesCannotAdvertiseAnyRadioService() {
        assertTrue(UltraRadioProtocol(elapsedNanos = { now }).accessories().values.isEmpty())
        val backend = Backend()
        backend.caps = null
        assertTrue(engine(backend).accessories().values.isEmpty())
        backend.caps = UltraRadioBackend.Capabilities(emptySet())
        backend.state = backend.state!!.copy(stations = emptyList())
        assertTrue(engine(backend).accessories().values.isEmpty())
        backend.state = UltraRadioBackend.Snapshot(1, true,
            listOf(UltraRadioBackend.Station("one", "One", "One", "Ensemble")), "one", true)
        assertTrue(engine(backend).accessories().values.isEmpty())
    }

    @Test fun descriptorMatchesReferenceAndDoesNotPromiseUnsupportedFrequencyOrSourceChanges() {
        val backend = Backend()
        backend.caps = UltraRadioBackend.Capabilities(setOf(CommandKind.PLAY, CommandKind.TUNE_IDENTIFIER))
        val protocol = engine(backend)
        val accessories = protocol.accessories().values.map { it as Dict }
        assertEquals(listOf(Integer(0x03000001), Integer(0x0F000001)), accessories.map { it["type"] })
        val service = ((accessories[1]["services"] as Array).values.single() as Dict)
        val described = (service["controls"] as Array).values.map { it as Dict }
        assertEquals(listOf(Integer(4360), Integer(4364)), described.map { it["iid"] })
        assertEquals(Integer(0x0F00004B), described[1]["type"])
        assertEquals(Integer(0), described[1]["sender"])
        assertEquals(Bool(true), described[1]["hasResponse"])
        assertEquals(listOf(Text("identifier"), Text("sourceIdentifier")),
            (described[1]["requestParameters"] as Array).values.map { (it as Dict)["name"] })
        val response = open(protocol.handle(VehicleStatePrototype.Lane.NORMAL, request("configRequest")).single())
        assertEquals(Integer(9), response["transactionID"])
        assertEquals(protocol.accessories(), (response["values"] as Dict)["accessories"])
    }

    @Test fun registrationAndUpdatesKeepRealStationAndUnknownMetadataSeparate() {
        val backend = Backend()
        val protocol = engine(backend)
        val registered = register(protocol, 4102, 4355, 9999)
        assertEquals(Dict(listOf(Integer(4102) to Text("one"))), registered["values"])
        assertEquals(Dict(listOf(Integer(4355) to Integer(-6727), Integer(9999) to Integer(-6727))), registered["errors"])
        assertTrue(messages(protocol).isEmpty())
        backend.state = backend.state!!.copy(currentIdentifier = "two", title = "Broadcast")
        val update = messages(protocol).single()
        assertEquals(VehicleStatePrototype.Lane.HIGH, update.lane)
        assertEquals(Dict(listOf(Integer(4102) to Text("two"), Integer(4355) to Text("Broadcast"))), open(update)["values"])
        assertTrue(messages(protocol).isEmpty())
    }

    @Test fun tuneWaitsForActualFreshObservationAndRepliesOnRequestLane() {
        val backend = Backend()
        val protocol = engine(backend)
        register(protocol, 4102)
        assertTrue(protocol.handle(VehicleStatePrototype.Lane.NORMAL, controls(4364L to tune("two"))).isEmpty())
        assertEquals(UltraRadioBackend.Command(CommandKind.TUNE_IDENTIFIER, "two"), backend.lastCommand)
        assertEquals("one", backend.state!!.currentIdentifier)
        assertTrue(messages(protocol).isEmpty())
        now++
        backend.state = backend.state!!.copy(observedAtNanos = now, currentIdentifier = "two")
        backend.completion!!(true)
        val replies = messages(protocol)
        assertEquals(2, replies.size)
        assertEquals(VehicleStatePrototype.Lane.NORMAL, replies[0].lane)
        assertEquals(Dict(listOf(Integer(4364) to Null)), open(replies[0])["values"])
        assertEquals(VehicleStatePrototype.Lane.HIGH, replies[1].lane)
        assertEquals(1, backend.cancelled)
    }

    @Test fun dispatchAcknowledgementWithoutNewObservedStateNeverConfirmsControl() {
        val backend = Backend()
        val protocol = engine(backend)
        protocol.handle(VehicleStatePrototype.Lane.NORMAL, controls(4360L to Null))
        backend.completion!!(true)
        assertEquals(Dict(listOf(Integer(4360) to Integer(-6735))), open(messages(protocol).single())["errors"])
        now++
        backend.state = backend.state!!.copy(observedAtNanos = now)
        protocol.handle(VehicleStatePrototype.Lane.NORMAL, controls(4361L to Null))
        now++
        backend.state = backend.state!!.copy(observedAtNanos = now) // Still playing: requested pause was not applied.
        backend.completion!!(true)
        assertEquals(Dict(listOf(Integer(4361) to Integer(-6735))), open(messages(protocol).single())["errors"])
    }

    @Test fun invalidForeignAndAmbiguousCommandsNeverReachReceiver() {
        val backend = Backend()
        val protocol = engine(backend)
        val requests = listOf(controls(4364L to tune("missing")), controls(4364L to tune("one", "FM")),
            controls(4364L to dict("identifier" to Text("one"))), controls(4365L to dict("frequency" to Integer(101100))),
            controls(4366L to dict("identifier" to Text("DAB"))), controls(4360L to Bool(true)),
            controls(4360L to Null, 4361L to Null))
        requests.forEach { assertEquals(1, protocol.handle(VehicleStatePrototype.Lane.NORMAL, it).size) }
        assertEquals(0, backend.calls)
        val write = open(protocol.handle(VehicleStatePrototype.Lane.NORMAL,
            request("writeRequest", Dict(listOf(Integer(4102) to Text("two"))))).single())
        assertEquals(Dict(listOf(Integer(4102) to Integer(-6727))), write["errors"])
    }

    @Test fun timeoutCancelsAndOldCompletionCannotAcknowledgeReplacement() {
        val backend = Backend()
        val protocol = engine(backend)
        protocol.handle(VehicleStatePrototype.Lane.NORMAL, controls(4360L to Null))
        val old = backend.completion!!
        now += 5_000_000_000L
        backend.state = backend.state!!.copy(observedAtNanos = now)
        assertEquals(Dict(listOf(Integer(4360) to Integer(-6735))), open(messages(protocol).single())["errors"])
        assertEquals(1, backend.cancelled)
        protocol.handle(VehicleStatePrototype.Lane.NORMAL, controls(4361L to Null))
        old(true)
        assertTrue(messages(protocol).isEmpty())
        protocol.close()
        backend.completion!!(true)
        assertEquals(2, backend.cancelled)
        assertEquals(UltraRadioProtocol.Poll.SourceLost, protocol.poll())
    }

    @Test fun sourcePermissionLossAndMetadataWithdrawalRetireSubscriptionsAndPendingCommand() {
        val backend = Backend()
        backend.state = backend.state!!.copy(title = "Broadcast")
        val protocol = engine(backend)
        register(protocol, 4355)
        protocol.handle(VehicleStatePrototype.Lane.NORMAL, controls(4360L to Null))
        backend.state = backend.state!!.copy(title = null)
        assertEquals(UltraRadioProtocol.Poll.SourceLost, protocol.poll())
        assertEquals(1, backend.cancelled)
        val reads = backend.reads
        assertTrue(protocol.accessories().values.isEmpty())
        backend.completion!!(true)
        assertEquals(reads, backend.reads)
        val revoked = engine(backend)
        backend.caps = null
        assertEquals(UltraRadioProtocol.Poll.SourceLost, revoked.poll())
    }

    @Test fun malformedAndDuplicateStationSnapshotsCannotBeAdvertised() {
        val backend = Backend()
        val original = backend.state!!
        listOf(original.copy(observedAtNanos = 0), original.copy(observedAtNanos = now + 1),
            original.copy(currentIdentifier = "absent"), original.copy(currentIdentifier = null, playing = true),
            original.copy(stations = listOf(original.stations[0], original.stations[0])),
            original.copy(stations = listOf(original.stations[0].copy(name = "x".repeat(257)))),
            original.copy(stations = listOf(original.stations[0].copy(name = "private\nline")))).forEach {
            backend.state = it
            assertTrue(engine(backend).accessories().values.isEmpty())
        }
    }
}
