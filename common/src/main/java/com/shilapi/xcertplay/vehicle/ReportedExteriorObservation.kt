package com.shilapi.xcertplay.vehicle

import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpAmbientDecoder
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorSomeIpAmbientState

/** Local last-received display only. Never changes the strict freshness cache or iPhone data. */
internal class ReportedExteriorObservation(private val elapsedMillis: () -> Long) {
    private var observation: Pair<Double, Long>? = null
    data class Reading(val celsius: Double, val receivedAgeMillis: Long)

    fun clear() { observation = null }

    fun receive(reading: LeapmotorSomeIpAmbientState.Reading, receivedAt: Long) {
        if (reading.channel != LeapmotorSomeIpAmbientDecoder.Channel.EXTERIOR_CORRECTED) return
        val value = LeapmotorTelemetry.reportedExteriorCelsius(reading)
        val age = elapsedMillis() - receivedAt
        observation = if (value != null && receivedAt >= 0 && age in 0..60_000L) value to receivedAt else null
    }

    fun reading(): Reading? {
        val (value, at) = observation ?: return null
        val age = elapsedMillis() - at
        if (age < 0) { clear(); return null }
        return Reading(value, age)
    }
}
