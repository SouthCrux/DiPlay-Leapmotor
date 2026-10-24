package com.shilapi.xcertplay.vehicle.leapmotor

import android.os.SystemClock

/** Standard Android 12 IDs, matched against the android.car.jar extracted from this C11. */
enum class LeapmotorEvProperty(val id: Int, internal val type: Class<*>, internal val changeMode: Int) {
    ENERGY_WH(291504905, java.lang.Float::class.java, 2),
    NOMINAL_CAPACITY_WH(291504390, java.lang.Float::class.java, 0),
    RANGE_METERS(291504904, java.lang.Float::class.java, 2),
    CHARGE_PORT_CONNECTED(287310603, java.lang.Boolean::class.java, 1),
}

data class CarPropertyDescriptor(
    val id: Int,
    val access: Int,
    val global: Boolean,
    val type: Class<*>,
    val changeMode: Int,
)

data class CarPropertyReading(
    val id: Int,
    val areaId: Int,
    val status: Int,
    val timestampNanos: Long,
    val value: Any?,
)

/** Caller owns the authorized manager/connection. This API has no setters or subscriptions. */
interface ReadOnlyCarPropertyAccess {
    fun configurations(): List<CarPropertyDescriptor>
    fun read(property: LeapmotorEvProperty): CarPropertyReading?
}

enum class PropertyReadState {
    AVAILABLE, NOT_EXPOSED, ACCESS_DENIED, INVALID_CONFIG, UNAVAILABLE, ERROR, STALE, INVALID_VALUE,
}

/** Numeric values are normalized Double (Wh or metres); the port value is Boolean. */
data class LeapmotorPropertyResult(
    val state: PropertyReadState,
    val value: Any? = null,
    val timestampNanos: Long? = null,
)

/**
 * Partial diagnostics, deliberately not a VehicleStatusSnapshot. A connected port is not charging,
 * nominal capacity is not verified present-day usable capacity, and no displayed SOC is inferred.
 */
data class LeapmotorEvTelemetry(val fields: Map<LeapmotorEvProperty, LeapmotorPropertyResult>) {
    val currentEnergyWh: Double? get() = available(LeapmotorEvProperty.ENERGY_WH) as? Double
    val nominalCapacityWh: Double? get() = available(LeapmotorEvProperty.NOMINAL_CAPACITY_WH) as? Double
    val rangeKm: Double? get() = (available(LeapmotorEvProperty.RANGE_METERS) as? Double)?.div(1000.0)
    val chargePortConnected: Boolean? get() = available(LeapmotorEvProperty.CHARGE_PORT_CONNECTED) as? Boolean

    private fun available(property: LeapmotorEvProperty) =
        fields[property]?.takeIf { it.state == PropertyReadState.AVAILABLE }?.value
}

/**
 * Explicit, synchronous read on the caller's worker thread. No connection, scheduling or iAP2 output.
 * Re-reads the permission-filtered configuration on every pass, retaining no last-known-good values.
 */
class LeapmotorCarPropertyReader(
    private val access: ReadOnlyCarPropertyAccess,
    private val maxAgeNanos: Long = 60_000_000_000L,
    private val elapsedNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    init { require(maxAgeNanos > 0) }

    fun read(): LeapmotorEvTelemetry {
        val configs = try {
            access.configurations()
        } catch (_: SecurityException) {
            return all(PropertyReadState.ACCESS_DENIED)
        } catch (_: Exception) {
            return all(PropertyReadState.ERROR)
        }
        val pending = LeapmotorEvProperty.entries.associateWith { property ->
            val matches = configs.filter { it.id == property.id }
            if (matches.isEmpty()) return@associateWith Pending(PropertyReadState.NOT_EXPOSED)
            if (matches.size != 1) return@associateWith Pending(PropertyReadState.INVALID_CONFIG)
            val config = matches.single()
            if (config.access !in listOf(1, 3) || !config.global || config.type != property.type ||
                config.changeMode != property.changeMode) return@associateWith Pending(PropertyReadState.INVALID_CONFIG)
            try {
                Pending(reading = access.read(property))
            } catch (_: SecurityException) {
                Pending(PropertyReadState.ACCESS_DENIED)
            } catch (_: Exception) {
                Pending(PropertyReadState.ERROR)
            }
        }
        // Evaluate age after all blocking reads, using the vehicle timestamp rather than poll time.
        val now = elapsedNanos()
        val fields = pending.mapValues { (property, result) -> normalize(property, result, now) }.toMutableMap()
        val energy = fields[LeapmotorEvProperty.ENERGY_WH]?.value as? Double
        val nominal = fields[LeapmotorEvProperty.NOMINAL_CAPACITY_WH]?.value as? Double
        if (energy != null && nominal != null && energy > nominal) {
            fields[LeapmotorEvProperty.ENERGY_WH] = LeapmotorPropertyResult(PropertyReadState.INVALID_VALUE)
        }
        return LeapmotorEvTelemetry(fields.toMap())
    }

    private data class Pending(val failure: PropertyReadState? = null, val reading: CarPropertyReading? = null)

    private fun normalize(property: LeapmotorEvProperty, pending: Pending, now: Long): LeapmotorPropertyResult {
        pending.failure?.let { return LeapmotorPropertyResult(it) }
        val raw = pending.reading ?: return LeapmotorPropertyResult(PropertyReadState.UNAVAILABLE)
        fun failure(state: PropertyReadState) = LeapmotorPropertyResult(state, timestampNanos = raw.timestampNanos)
        if (raw.id != property.id || raw.areaId != 0) return failure(PropertyReadState.INVALID_VALUE)
        if (raw.status == 1) return failure(PropertyReadState.UNAVAILABLE)
        if (raw.status != 0) return failure(PropertyReadState.ERROR)
        val time = raw.timestampNanos
        if (time < 0 || time > now) return failure(PropertyReadState.INVALID_VALUE)
        // Only the verified STATIC nominal capacity can have a zero/old timestamp.
        if (property.changeMode != 0 && (time == 0L || now - time > maxAgeNanos)) {
            return failure(PropertyReadState.STALE)
        }
        val value: Any = if (property == LeapmotorEvProperty.CHARGE_PORT_CONNECTED) {
            raw.value as? Boolean ?: return failure(PropertyReadState.INVALID_VALUE)
        } else {
            val number = raw.value as? Float ?: return failure(PropertyReadState.INVALID_VALUE)
            if (!number.isFinite() || number < 0 ||
                (property == LeapmotorEvProperty.NOMINAL_CAPACITY_WH && number == 0f)) {
                return failure(PropertyReadState.INVALID_VALUE)
            }
            number.toDouble()
        }
        return LeapmotorPropertyResult(PropertyReadState.AVAILABLE, value, time)
    }

    private fun all(state: PropertyReadState) = LeapmotorEvTelemetry(
        LeapmotorEvProperty.entries.associateWith { LeapmotorPropertyResult(state) },
    )
}
