// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable

class VehicleStateCapabilityDiagnosticsTest {
    @Test fun localSocAndRangeNeverImplyWireBatteryOrClimateSupport() {
        val source = VehicleStatusProvider { VehicleStatusSnapshot(227, null, 12.3, null, null, null, null) }
        val diagnostic = VehicleStatePrototype(source).capabilityDiagnostics()
        assertEquals("vehicle state capabilities range=true climateZones=0 radio=false " +
            "highVoltageBattery=false localSocAvailable=true", diagnostic)
        assertFalse(diagnostic.contains("12.3"))
        assertFalse(diagnostic.contains("227"))
    }
    @Test fun absentSourceKeepsAllCapabilitiesAndLocalAvailabilityFalse() {
        assertEquals("vehicle state capabilities range=false climateZones=0 radio=false " +
            "highVoltageBattery=false localSocAvailable=false",
            VehicleStatePrototype(VehicleStatusProvider { null }).capabilityDiagnostics())
    }
    @Test fun onlyExplicitVerifiedClimateZonesAppearInCapabilityCount() {
        val backend = object : UltraClimateBackend {
            override fun capabilities() = listOf(UltraClimateBackend.Capability(UltraClimateBackend.Zone.DRIVER,
                16.0, 32.0, 0.5, UltraClimateBackend.Seat.FRONT_LEFT))
            override fun snapshot() = UltraClimateBackend.Snapshot(mapOf(UltraClimateBackend.Zone.DRIVER to 21.0))
            override fun requestTemperature(zone: UltraClimateBackend.Zone, celsius: Double, complete: (Boolean) -> Unit) = Closeable {}
        }
        assertEquals("vehicle state capabilities range=false climateZones=1 radio=false " +
            "highVoltageBattery=false localSocAvailable=false",
            VehicleStatePrototype(VehicleStatusProvider { null }, backend).capabilityDiagnostics())
    }
}
