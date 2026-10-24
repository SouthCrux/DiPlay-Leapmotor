package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class AirPlayFeatureDiagnosticsTest {
    @Test fun proposalAndEnabledSelectionRemainSeparate() {
        assertEquals(
            "airplay features proposedState=list " +
                "proposed=[iAPChannel,uiSync,vehicleStateProtocol,fileTransfer] proposedUnknown=0 proposedInvalid=0 " +
                "enabled=[iAPChannel] enabledUnknown=0 enabledInvalid=0",
            AirPlayFeatureDiagnostics.summary(
                listOf("fileTransfer", "vehicleStateProtocol", "uiSync", "iAPChannel"),
                listOf("iAPChannel"),
            ),
        )
    }

    @Test fun knownFeaturesHaveStableOrderAndNoDuplicates() {
        assertEquals(
            "airplay features proposedState=list " +
                "proposed=[hevc,iAPChannel,viewAreas,altScreen,cornerMasks,videoPlayback,mainBuffered] " +
                "proposedUnknown=0 proposedInvalid=0 " +
                "enabled=[hevc,viewAreas] enabledUnknown=0 enabledInvalid=0",
            AirPlayFeatureDiagnostics.summary(
                listOf("mainBuffered", VideoInCar.FEATURE, "cornerMasks", "altScreen", "viewAreas", "iAPChannel", "hevc", "hevc"),
                listOf("viewAreas", "hevc", "viewAreas"),
            ),
        )
    }

    @Test fun absenceAndMalformedContainerAreDistinctFromAnEmptyList() {
        assertEquals(
            "airplay features proposedState=absent proposed=[] proposedUnknown=0 proposedInvalid=0 " +
                "enabled=[] enabledUnknown=0 enabledInvalid=0",
            AirPlayFeatureDiagnostics.summary(null, emptyList<Any?>()),
        )
        assertEquals(
            "airplay features proposedState=malformed proposed=[] proposedUnknown=0 proposedInvalid=0 " +
                "enabled=[] enabledUnknown=0 enabledInvalid=0",
            AirPlayFeatureDiagnostics.summary(mapOf("password" to "secret"), emptyList<Any?>()),
        )
        assertEquals(
            "airplay features proposedState=list proposed=[] proposedUnknown=0 proposedInvalid=0 " +
                "enabled=[] enabledUnknown=0 enabledInvalid=0",
            AirPlayFeatureDiagnostics.summary(emptyList<Any?>(), emptyList<Any?>()),
        )
    }

    @Test fun adversarialPeerValuesOnlyAffectCounts() {
        val unprintable = object {
            override fun toString(): String = error("Peer objects must never be rendered")
        }
        assertEquals(
            "airplay features proposedState=list proposed=[hevc] proposedUnknown=3 proposedInvalid=4 " +
                "enabled=[iAPChannel] enabledUnknown=1 enabledInvalid=1",
            AirPlayFeatureDiagnostics.summary(
                listOf("hevc", "hevc\npassword=secret", "token=private", "HEVC", null, 1L, mapOf("ssid" to "private"), unprintable),
                listOf("iAPChannel", "fileTransfer\r\ncertificate=private", unprintable),
            ),
        )
    }

    @Test fun largeUnknownProposalCannotExpandTheExportedSummary() {
        val summary = AirPlayFeatureDiagnostics.summary(
            List(1_000) { "password=private-$it\nbody=location" },
            emptyList<Any?>(),
        )
        assertEquals(
            "airplay features proposedState=list proposed=[] proposedUnknown=1000 proposedInvalid=0 " +
                "enabled=[] enabledUnknown=0 enabledInvalid=0",
            summary,
        )
        assertTrue(summary.length < 700)
        assertFalse(summary.contains('\n'))
        assertFalse(summary.contains("private"))
    }
}
