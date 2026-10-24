package com.shilapi.xcertplay.vehicle.leapmotor

import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot

/**
 * Normalized EV telemetry, not OEM property IDs. SOC and range must be measured or supplied
 * by a verified vehicle configuration. Unknown optional fields remain null, never false or zero.
 * [observedAtMillis] uses the same monotonic clock as the provider, never wall-clock or capture time.
 */
data class LeapmotorEvReading(
    val observedAtMillis: Long,
    val batteryPercent: Double,
    val rangeKm: Int,
    val maxRangeKm: Int? = null,
    val rangeWarning: Boolean? = null,
    val charging: Boolean? = null,
    val currentChargeWh: Long? = null,
    val maxChargeWh: Long? = null,
)

/**
 * Read-only boundary for a future verified C11 telemetry source. No polling, Android service lookup,
 * ADB, BYD fallback or vehicle writes. Not wired into the app until the C11 source is verified.
 * The iAP2 loop only reads a fresh cached snapshot; it never waits for vehicle I/O.
 */
class LeapmotorVehicleStatusProvider(
    private val freshnessMillis: Long = 60_000L,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : VehicleStatusProvider {
    init {
        require(freshnessMillis > 0) { "Freshness must be positive" }
    }

    private var latest: LeapmotorEvReading? = null

    /** Invalid or out-of-order input invalidates the cache, rather than retaining plausible old data. */
    @Synchronized
    fun publish(reading: LeapmotorEvReading): Boolean {
        val valid = reading.valid() && fresh(reading) &&
            (latest?.let { reading.observedAtMillis >= it.observedAtMillis } ?: true)
        latest = reading.takeIf { valid }
        return valid
    }

    /** Call when the source disconnects, permissions disappear, or a required property is unknown. */
    @Synchronized
    fun clear() {
        latest = null
    }

    @Synchronized
    override fun snapshot(): VehicleStatusSnapshot? {
        val reading = latest ?: return null
        if (!fresh(reading)) {
            latest = null
            return null
        }
        return VehicleStatusSnapshot(
            rangeKm = reading.rangeKm,
            rangeWarning = reading.rangeWarning,
            batteryPercent = reading.batteryPercent,
            currentChargeWh = reading.currentChargeWh,
            maxChargeWh = reading.maxChargeWh,
            maxRangeKm = reading.maxRangeKm,
            charging = reading.charging,
        )
    }

    private fun fresh(reading: LeapmotorEvReading): Boolean {
        val now = elapsedMillis()
        return reading.observedAtMillis <= now && now - reading.observedAtMillis in 0..freshnessMillis
    }

    private fun LeapmotorEvReading.valid(): Boolean =
        batteryPercent.isFinite() && batteryPercent in 0.0..100.0 &&
            rangeKm in 0..0xffff && (maxRangeKm == null || maxRangeKm in rangeKm..0xffff) &&
            (currentChargeWh == null || currentChargeWh in 0L..0xffff_ffffL) &&
            (maxChargeWh == null || maxChargeWh in 1L..0xffff_ffffL) &&
            (currentChargeWh == null || maxChargeWh == null || currentChargeWh <= maxChargeWh)
}
