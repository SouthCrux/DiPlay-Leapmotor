package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.transport.Iap2ThemeAsset
import org.junit.Assert.*
import org.junit.Test

class UltraNegotiationDiagnosticsTest {
    private fun zoneCommand(data: Map<String, Any>, kind: Long = 1) = BplistCodec.encode(mapOf(
        "type" to 2L, "data" to BplistCodec.encode(mapOf("type" to kind, "data" to data))))
    private fun command(kind: Long, layout: String = "private-layout") = BplistCodec.encode(mapOf("type" to 2L, "id" to "private-phone",
        "data" to BplistCodec.encode(mapOf("type" to kind,
            "data" to mapOf("layoutID" to layout, "extra" to "private-body")))))

    @Test fun configuredTopologyAndUiSyncAreNotCalledResourceAcceptance() {
        val base = AirPlayConfig("private-name", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0",
            AirPlayDisplayConfig(2560, 1176), ultraHandshake = true)
        val selected = UltraThemeBinding(Iap2ThemeAsset("44C9FA31EC", 985, "19.0"),
            UltraThemeDisplay("Center_Display", 1920, 720, 157)).configure(base)
        val report = UltraNegotiationDiagnostics.summary(selected, true)
        assertTrue(report.contains("selectedPanels=1 clusterDeclared=false mainCanvas=1920x720"))
        assertTrue(report.contains("uiSyncNegotiated=true resourceAcceptance=unverified topologyAcceptance=unverified"))
        assertFalse(report.contains("private"))
    }

    @Test fun ownerTraceSeparatesDirectionsAndRecordsOnlyMessageShape() {
        val diagnostic = UiSyncOwnerDiagnostics()
        val input = command(5)
        val first = diagnostic.record(0, true, input)!!
        assertTrue(first.contains("direction=sent type=2 command=5 layoutIdPresent=true"))
        assertTrue(first.endsWith("resourceAcceptance=unverified"))
        assertFalse(first.contains("private"))
        assertNull(diagnostic.record(0, true, input))
        assertNotNull(diagnostic.record(0, false, input))
        assertNull(diagnostic.record(-1, true, input))
        assertNull(diagnostic.record(0, true, byteArrayOf(1, 2)))
        val rest = (0L..1000L).mapNotNull { diagnostic.record(0, true, command(it)) }
        assertEquals(30, rest.size)
        assertTrue(rest.last().endsWith("observationLimitReached=true"))
        assertNull(diagnostic.record(1, true, input))
    }

    @Test fun receivedLayoutMustMatchTheLocalRequestWithoutExportingItsIdentifier() {
        val diagnostic = UiSyncOwnerDiagnostics()
        val expected = "9E19BC4E-5251-5821-A0BA-26479A79F722"
        val other = "BFB3D9B9-E393-546B-AC44-B21D6F5C1BF8"
        assertTrue(diagnostic.record(0, true, command(5, expected))!!.contains("layoutRelation=requested"))
        val matched = diagnostic.record(0, false, command(6, expected))!!
        assertTrue(matched.contains("layoutRelation=matchesRequest"))
        assertTrue(matched.contains("resourceAcceptance=unverified"))
        assertFalse(matched.contains(expected))
        assertTrue(diagnostic.record(0, false, command(6, other))!!.contains("layoutRelation=differsFromRequest"))
        assertTrue(diagnostic.record(1, false, command(6, expected))!!.contains("layoutRelation=noRequestObserved"))
    }

    @Test fun settingsPresentationAndDismissalAreDistinctRequestsNotProofOfRendering() {
        val diagnostic = UiSyncOwnerDiagnostics()
        val present = diagnostic.record(0, false, zoneCommand(mapOf("presentStream" to "private-input")))!!
        val dismiss = diagnostic.record(0, false, zoneCommand(mapOf("dismissStream" to "private-input")))!!
        assertTrue(present.contains("settingsStreamAction=PRESENT"))
        assertTrue(dismiss.contains("settingsStreamAction=DISMISS"))
        for (line in listOf(present, dismiss)) {
            assertFalse(line.contains("private"))
            assertTrue(line.endsWith("resourceAcceptance=unverified"))
        }
        assertNull(diagnostic.record(0, false, zoneCommand(mapOf("presentStream" to "different-private-input"))))
        assertNotNull(diagnostic.record(1, false, zoneCommand(mapOf("presentStream" to "private-input"))))
    }

    @Test fun malformedAmbiguousAndUnrelatedFieldsCannotLookLikeASettingsPresentation() {
        val cases = listOf(
            mapOf<String, Any>("presentStream" to "") to "INVALID",
            mapOf<String, Any>("presentStream" to 7L) to "INVALID",
            mapOf<String, Any>("presentStream" to "private://destination?token=secret") to "INVALID",
            mapOf<String, Any>("dismissStream" to "x".repeat(129)) to "INVALID",
            mapOf<String, Any>("presentStream" to "input", "dismissStream" to "input") to "AMBIGUOUS",
            mapOf<String, Any>("targetComponents" to listOf("private-component")) to "NONE",
        )
        for ((data, expected) in cases) {
            val line = UiSyncOwnerDiagnostics().record(0, false, zoneCommand(data))!!
            assertTrue(line.contains("settingsStreamAction=$expected"))
            assertFalse(line.contains("private")); assertFalse(line.contains("secret"))
        }
        assertTrue(UiSyncOwnerDiagnostics().record(0, false,
            zoneCommand(mapOf("presentStream" to "input"), kind = 6))!!.contains("settingsStreamAction=NONE"))
    }
}
