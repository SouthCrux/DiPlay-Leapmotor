package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class UiSyncProtocolTest {
    private fun frame(header: Map<String, Any>, panel: String = "Cluster_Display") = Opack.encode(Opack.dict(
        "displayUUID" to Opack.Text(panel), "data" to Opack.Data(BplistCodec.encode(header)),
    ))
    private fun header(bytes: ByteArray): Map<String, Any?> =
        BoundedPlist.decode(((Opack.decode(bytes) as Opack.Dict)["data"] as Opack.Data).bytes())!!
    private fun reset() = mapOf("type" to 0L, "ssn" to 1L, "psn" to 1L, "asn" to 0L, "id" to "fixture", "version" to 3L)
    private fun complete() = mapOf("type" to 1L, "ssn" to 1L, "psn" to 1L, "version" to 3L)

    @Test fun referenceResetSequenceRequestsLayoutOnlyWhenRendererExists() {
        val sync = UiSyncProtocol("Cluster_Display") { true }
        assertTrue(sync.select(UiSyncProtocol.Layout("fixture-layout", "blue", mapOf("zone" to "component"))).isEmpty())
        val reset = header(sync.handle(frame(reset())).single())
        assertEquals(0L, reset["type"]); assertEquals(1L, reset["ssn"])
        assertEquals(1L, reset["psn"]); assertEquals(2L, reset["version"])
        assertEquals("fixture", reset["id"])
        assertTrue(sync.handle(frame(reset())).isEmpty())
        val out = sync.handle(frame(complete()))
        assertEquals(4, out.size)
        assertTrue(sync.ready)
        assertEquals(1L, header(out[0])["type"])
        val commands = out.drop(1).map { BoundedPlist.decode(header(it)["data"] as ByteArray)!!["type"] }
        assertEquals(listOf(0x11L, 5L, 7L), commands)
    }

    @Test fun visualCommandsAreAcknowledgedOnlyAfterApplicationAndNeverAppliedTwice() {
        val choices = mutableListOf<UiSyncProtocol.Choice>()
        val sync = UiSyncProtocol("Cluster_Display") { choices += it; true }
        sync.handle(frame(reset())); sync.handle(frame(complete()))
        val command = BplistCodec.encode(mapOf("type" to 3L, "data" to mapOf("layoutID" to "layout", "paletteID" to "blue")))
        val packet = frame(mapOf("type" to 2L, "ssn" to 1L, "psn" to 2L, "data" to command))
        assertEquals(3L, header(sync.handle(packet).single())["type"])
        assertEquals(listOf(UiSyncProtocol.Choice.Layout("layout", "blue")), choices)
        assertEquals(3L, header(sync.handle(packet).single())["type"])
        assertEquals(1, choices.size)
        assertTrue(sync.handle(frame(mapOf("type" to 2L, "ssn" to 99L, "psn" to 3L, "data" to command))).isEmpty())
    }

    @Test fun rendererAbsenceDoesNotGrantFocusOrAcknowledgeUnrenderedTransitions() {
        val sync = UiSyncProtocol("Cluster_Display")
        sync.handle(frame(reset()))
        assertEquals(1, sync.handle(frame(complete())).size)
        assertFalse(sync.rendererAvailable)
        val command = BplistCodec.encode(mapOf("type" to 3L, "data" to mapOf("layoutID" to "layout")))
        assertTrue(sync.handle(frame(mapOf("type" to 2L, "ssn" to 1L, "psn" to 2L, "data" to command))).isEmpty())
        sync.reopen(); assertFalse(sync.ready)
        assertTrue(sync.handle(frame(complete())).isEmpty())
        assertTrue(sync.handle(frame(reset(), "Passenger")).isEmpty())
    }

    @Test fun nestedPlistsRejectCyclesAndOversizedGraphs() {
        assertNull(BoundedPlist.decode(ByteArray(40)))
        val cycle = BplistCodec.encode(listOf(1)).also { it[9] = 0 }
        assertNull(BoundedPlist.decode(cycle))
        val oversized = BplistCodec.encode(mapOf("items" to List(8193) { 1 }))
        assertNull(BoundedPlist.decode(oversized))
        val deep = (0..40).fold(1 as Any) { v, _ -> listOf(v) }
        assertNull(BoundedPlist.decode(BplistCodec.encode(mapOf("deep" to deep))))
        val valid = BplistCodec.encode(mapOf("ssn" to 2L, "name" to "青", "data" to byteArrayOf(1)))
        assertEquals("青", BoundedPlist.decode(valid)!!["name"])
        valid.indices.forEach { assertNull(BoundedPlist.decode(valid.copyOf(it))) }
    }
}
