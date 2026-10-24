package com.shilapi.xcertplay

import com.shilapi.xcertplay.vehicle.LeapmotorTelemetry
import com.shilapi.xcertplay.vehicle.ReportedExteriorObservation
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpAmbientDecoder.Channel
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpAmbientState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ReportedExteriorTemperatureTest {
    private val exterior = LeapmotorSomeIpAmbientState.Reading(Channel.EXTERIOR_CORRECTED,
        LeapmotorSomeIpAmbientState.Status.OBSERVED_UNVERIFIED, 13.0, 1)

    @Test fun lastReceivedObservationSurvivesSilenceWithItsRealAgeAndClearsOnInvalidation() {
        var now = 100_000L
        val reported = ReportedExteriorObservation { now }
        reported.receive(exterior, now - 1)
        now += 600_000L
        assertEquals(13.0, reported.reading()!!.celsius, 0.0)
        assertEquals(600_001L, reported.reading()!!.receivedAgeMillis)
        reported.receive(exterior.copy(channel = Channel.INTERIOR, rawValue = 25.0), now)
        assertEquals(600_001L, reported.reading()!!.receivedAgeMillis)
        reported.receive(exterior.copy(rawValue = null), now)
        assertNull(reported.reading())
        reported.receive(exterior, now)
        reported.clear()
        assertNull(reported.reading())
    }

    @Test fun presentationUsesTheReceivedExteriorWithoutChangingItsUnverifiedSensorStatus() {
        assertEquals(13.0, LeapmotorTelemetry.reportedExteriorCelsius(exterior)!!, 0.0)
        assertEquals(0.0, LeapmotorTelemetry.reportedExteriorCelsius(exterior.copy(rawValue = 0.0))!!, 0.0)
        assertEquals(LeapmotorSomeIpAmbientState.Status.OBSERVED_UNVERIFIED, exterior.status)
    }

    @Test fun staleMissingWrongLocationAndImplausibleValuesAreNotPresented() {
        for (reading in listOf(exterior.copy(channel = Channel.INTERIOR),
            exterior.copy(status = LeapmotorSomeIpAmbientState.Status.STALE), exterior.copy(rawValue = null),
            exterior.copy(receivedAgeMillis = null), exterior.copy(receivedAgeMillis = 60_001),
            exterior.copy(receivedAgeMillis = -1), exterior.copy(rawValue = Double.NaN),
            exterior.copy(rawValue = Double.POSITIVE_INFINITY), exterior.copy(rawValue = 65535.0))) {
            assertNull(LeapmotorTelemetry.reportedExteriorCelsius(reading))
        }
    }
}
