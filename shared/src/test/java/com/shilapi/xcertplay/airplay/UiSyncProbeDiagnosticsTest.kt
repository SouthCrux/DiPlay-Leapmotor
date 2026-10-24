package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class UiSyncProbeDiagnosticsTest {
    private fun frame(type: Long, command: Long? = null): ByteArray = Opack.encode(Opack.dict(
        "displayUUID" to Opack.Text("private-panel-name"),
        "private-routing-field" to Opack.Text("private-routing-value"),
        "data" to Opack.Data(BplistCodec.encode(buildMap<String, Any> {
            put("type", type); put("ssn", 1L); put("psn", 2L); put("id", "private-phone-id")
            command?.let { put("data", BplistCodec.encode(mapOf("type" to it,
                "data" to mapOf("layoutID" to "private-layout", "value" to "private-value")))) }
        })),
    ))

    @Test fun commandSummaryContainsOnlyNumericKindsAndLocalState() {
        val report = UiSyncProbeDiagnostics().record(frame(2, 3), 1, true, true, 0)!!
        assertEquals("ultra handshake: UI Sync observation panelIndex=1 type=2 command=3 " +
            "readyBefore=true readyAfter=true replies=0 renderer=false", report)
        assertFalse(report.contains("private"))
    }

    @Test fun repeatsAndUniqueMessageFloodHaveBoundedOutput() {
        val diagnostics = UiSyncProbeDiagnostics()
        repeat(100) {
            val report = diagnostics.record(frame(2, 3), 0, true, true, 0)
            assertEquals(it == 0, report != null)
        }
        val reports = (0L..1000).mapNotNull { diagnostics.record(frame(2, it), 0, true, true, 0) }
        assertEquals(31, reports.size)
        assertTrue(reports.last().endsWith("observationLimitReached=true"))
        assertNull(diagnostics.record(frame(0), 0, false, false, 1))
    }

    @Test fun observingDoesNotAdvanceProtocolOrAcknowledgeUnrenderedCommands() {
        val protocol = UiSyncProtocol("private-panel-name")
        val diagnostics = UiSyncProbeDiagnostics()
        val packet = frame(2, 3)
        repeat(4) {
            diagnostics.record(packet, 0, protocol.ready, protocol.ready, 0)
            assertTrue(protocol.handle(packet).isEmpty())
            assertFalse(protocol.ready)
        }
        assertNull(diagnostics.record(byteArrayOf(1, 2), 0, false, false, 0))
        assertNull(diagnostics.record(frame(0), -1, false, false, 0))
    }
}
