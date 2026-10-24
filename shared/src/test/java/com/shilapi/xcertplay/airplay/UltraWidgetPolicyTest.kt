// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class UltraWidgetPolicyTest {
    private val cluster = UltraWidgetZone("Cluster_Display", "zone1")
    private val evidence = UltraWidgetVehicleEvidence(UltraWidget.CHARGING, 100, 200, true)

    @Test fun implementedWallpaperDoesNotAcquireZonesFromItsPanelName() {
        val center = UltraWidgetZone("Center_Display", "zone1")
        assertEquals(UltraWidgetPolicy.Decision.Rejected(UltraWidgetPolicy.Blocked.NO_IMPLEMENTED_ZONE),
            UltraWidgetPolicy().select(UltraWidget.CHARGING, center, evidence, 150))
        assertEquals(UltraWidgetPolicy.Decision.Rejected(UltraWidgetPolicy.Blocked.NO_IMPLEMENTED_ZONE),
            UltraWidgetPolicy().select(UltraWidget.CHARGING, cluster, evidence, 150))
    }

    @Test fun confirmedReadingDoesNotSubstituteForVerifiedPhoneSchema() {
        val policy = UltraWidgetPolicy(setOf(cluster))
        listOf(null, evidence.copy(phoneVehicleSchemaVerified = false), evidence.copy(widget = UltraWidget.TIRES)).forEach {
            assertEquals(UltraWidgetPolicy.Decision.Rejected(UltraWidgetPolicy.Blocked.VEHICLE_SCHEMA_UNVERIFIED),
                policy.select(UltraWidget.CHARGING, cluster, it, 150))
        }
    }

    @Test fun staleFutureAndInvalidReadingsCannotSelectWidget() {
        val policy = UltraWidgetPolicy(setOf(cluster))
        listOf(evidence.copy(observedAtMillis = 151), evidence.copy(observedAtMillis = -1),
            evidence.copy(validUntilMillis = 150), evidence.copy(validUntilMillis = 99)).forEach {
            assertEquals(UltraWidgetPolicy.Decision.Rejected(UltraWidgetPolicy.Blocked.NO_FRESH_VEHICLE_DATA),
                policy.select(UltraWidget.CHARGING, cluster, it, 150))
        }
    }

    @Test fun candidateKeepsExactImplementedDestinationAndConfirmedUrl() {
        val policy = UltraWidgetPolicy(setOf(cluster))
        val request = (policy.select(UltraWidget.CHARGING, cluster, evidence, 150)
            as UltraWidgetPolicy.Decision.Candidate).request
        assertEquals(cluster, request.zone)
        assertEquals("widget://?identifier=com.apple.charging", request.widget.contentUrl)
        assertEquals(UltraWidgetPolicy.Decision.Rejected(UltraWidgetPolicy.Blocked.NO_IMPLEMENTED_ZONE),
            policy.select(UltraWidget.CHARGING, cluster.copy(zoneIdentifier = "zone2"), evidence, 150))
    }

    @Test fun installedZoneSetIsCopiedAndCatalogContainsNoGuessedClimateWidget() {
        val mutable = mutableSetOf(cluster)
        val policy = UltraWidgetPolicy(mutable)
        mutable.clear()
        assertTrue(policy.select(UltraWidget.CHARGING, cluster, evidence, 150) is UltraWidgetPolicy.Decision.Candidate)
        assertEquals(setOf("com.apple.charging", "com.apple.tires", "com.apple.carBodyStatus"),
            UltraWidget.entries.map { it.contentUrl.substringAfter("identifier=") }.toSet())
        assertEquals(0x17000004L, UltraWidgetRequest.REQUEST_CONTENT_SERVICE_TYPE)
    }
}
