package com.shilapi.xcertplay.vehicle.leapmotor

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.round

/** Seat groups from the captured C11 HAL, not a driver-side assumption. */
enum class LeapmotorClimateSide(val areaId: Int) { LEFT(49), RIGHT(68) }

data class LeapmotorClimateArea(val areaId: Int, val minCelsius: Float, val maxCelsius: Float)

data class LeapmotorClimateConfiguration(
    val propertyId: Int,
    val access: Int,
    val type: Class<*>,
    val changeMode: Int,
    val areas: List<LeapmotorClimateArea>,
)

/** Inject an already authorized manager. No service binding, generic property writes or automation. */
interface LeapmotorClimateAccess {
    fun configurations(): List<LeapmotorClimateConfiguration>
    fun readTarget(areaId: Int): CarPropertyReading?
    fun writeTarget(areaId: Int, celsius: Float)
}

enum class LeapmotorClimateState {
    AVAILABLE, NOT_EXPOSED, INVALID_CONFIG, ACCESS_DENIED, UNAVAILABLE, STALE, INVALID_VALUE, ERROR,
}

data class LeapmotorClimateObservation(
    val state: LeapmotorClimateState,
    val targetCelsius: Double? = null,
    val timestampNanos: Long? = null,
    val minCelsius: Double? = null,
    val maxCelsius: Double? = null,
    /** Null until independently verified; the captured C11 configArray has no increment. */
    val verifiedStepCelsius: Double? = null,
    val commandsEnabled: Boolean = false,
)

enum class LeapmotorClimateCommandState {
    DISABLED, NOT_READY, INVALID_TARGET, ACCESS_DENIED, ERROR, SUBMITTED_UNCONFIRMED, CONFIRMED,
    SUPERSEDED,
}

/** Opaque controller-owned receipt. A successful setter alone does not confirm an HVAC change. */
class LeapmotorClimateReceipt internal constructor(
    internal val side: LeapmotorClimateSide,
    internal val celsius: Double,
    internal val submittedAtNanos: Long,
)

data class LeapmotorClimateCommandResult(
    val state: LeapmotorClimateCommandState,
    val receipt: LeapmotorClimateReceipt? = null,
)

/**
 * Explicit synchronous operations on the caller's worker thread. Inactive by default, no polling,
 * retry or optimistic state. Caller must verify the temperature increment and map physical sides
 * to driver/passenger before advertising any Siri/Ultra capability. No production wiring yet.
 */
class LeapmotorClimateController(
    private val access: LeapmotorClimateAccess,
    private val enableCommands: Boolean = false,
    verifiedStepCelsiusBySide: Map<LeapmotorClimateSide, Double> = emptyMap(),
    private val maxAgeNanos: Long = 5_000_000_000L,
    private val elapsedNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private val steps = verifiedStepCelsiusBySide.toMap()
    private var pending: LeapmotorClimateReceipt? = null

    init {
        require(maxAgeNanos > 0)
        require(steps.values.all { it.isFinite() && it > 0 })
    }

    @Synchronized
    fun observe(side: LeapmotorClimateSide): LeapmotorClimateObservation {
        return try {
            val configs = access.configurations().filter { it.propertyId == TEMPERATURE_PROPERTY_ID }
            if (configs.isEmpty()) return LeapmotorClimateObservation(LeapmotorClimateState.NOT_EXPOSED)
            if (configs.size != 1) return LeapmotorClimateObservation(LeapmotorClimateState.INVALID_CONFIG)
            val config = configs.single()
            if (config.access !in listOf(1, 3) || config.type != java.lang.Float::class.java ||
                config.changeMode != 1) return LeapmotorClimateObservation(LeapmotorClimateState.INVALID_CONFIG)
            val areas = config.areas.filter { it.areaId == side.areaId }
            if (areas.size != 1) return LeapmotorClimateObservation(LeapmotorClimateState.INVALID_CONFIG)
            val area = areas.single()
            if (!area.minCelsius.isFinite() || !area.maxCelsius.isFinite() ||
                area.minCelsius >= area.maxCelsius) return LeapmotorClimateObservation(LeapmotorClimateState.INVALID_CONFIG)
            val step = steps[side]
            if (step != null && !onGrid(area.maxCelsius.toDouble(), area.minCelsius.toDouble(), step)) {
                return LeapmotorClimateObservation(LeapmotorClimateState.INVALID_CONFIG)
            }
            val raw = access.readTarget(side.areaId)
                ?: return LeapmotorClimateObservation(LeapmotorClimateState.UNAVAILABLE)
            if (raw.id != TEMPERATURE_PROPERTY_ID || raw.areaId != side.areaId) {
                return LeapmotorClimateObservation(LeapmotorClimateState.INVALID_VALUE)
            }
            if (raw.status == 1) return LeapmotorClimateObservation(LeapmotorClimateState.UNAVAILABLE)
            if (raw.status != 0) return LeapmotorClimateObservation(LeapmotorClimateState.ERROR)
            val now = elapsedNanos()
            if (raw.timestampNanos < 0 || raw.timestampNanos > now) {
                return LeapmotorClimateObservation(LeapmotorClimateState.INVALID_VALUE)
            }
            if (raw.timestampNanos == 0L || now - raw.timestampNanos > maxAgeNanos) {
                return LeapmotorClimateObservation(LeapmotorClimateState.STALE)
            }
            val value = raw.value as? Float
            if (value == null || !value.isFinite() || value < area.minCelsius || value > area.maxCelsius ||
                (step != null && !onGrid(value.toDouble(), area.minCelsius.toDouble(), step))) {
                return LeapmotorClimateObservation(LeapmotorClimateState.INVALID_VALUE)
            }
            LeapmotorClimateObservation(
                LeapmotorClimateState.AVAILABLE, value.toDouble(), raw.timestampNanos,
                area.minCelsius.toDouble(), area.maxCelsius.toDouble(), step,
                enableCommands && config.access == 3 && step != null,
            )
        } catch (_: SecurityException) {
            LeapmotorClimateObservation(LeapmotorClimateState.ACCESS_DENIED)
        } catch (_: Exception) {
            LeapmotorClimateObservation(LeapmotorClimateState.ERROR)
        }
    }

    @Synchronized
    fun submitTarget(side: LeapmotorClimateSide, celsius: Double): LeapmotorClimateCommandResult {
        pending = null // Every new intent supersedes a prior unconfirmed write, even a rejected one.
        if (!enableCommands) return result(LeapmotorClimateCommandState.DISABLED)
        val before = observe(side)
        if (!before.commandsEnabled) return result(
            if (before.state == LeapmotorClimateState.ACCESS_DENIED) LeapmotorClimateCommandState.ACCESS_DENIED
            else LeapmotorClimateCommandState.NOT_READY,
        )
        val min = checkNotNull(before.minCelsius)
        val max = checkNotNull(before.maxCelsius)
        val step = checkNotNull(before.verifiedStepCelsius)
        if (!celsius.isFinite() || celsius < min || celsius > max || !onGrid(celsius, min, step) ||
            abs(celsius.toFloat().toDouble() - celsius) > minOf(0.0001, step / 100)) {
            return result(LeapmotorClimateCommandState.INVALID_TARGET)
        }
        return try {
            // Timestamp before setter: a post-write observed event is required for confirmation.
            val receipt = LeapmotorClimateReceipt(side, celsius, elapsedNanos())
            access.writeTarget(side.areaId, celsius.toFloat())
            pending = receipt
            LeapmotorClimateCommandResult(LeapmotorClimateCommandState.SUBMITTED_UNCONFIRMED, receipt)
        } catch (_: SecurityException) {
            result(LeapmotorClimateCommandState.ACCESS_DENIED)
        } catch (_: Exception) {
            result(LeapmotorClimateCommandState.ERROR)
        }
    }

    @Synchronized
    fun confirmTarget(receipt: LeapmotorClimateReceipt): LeapmotorClimateCommandResult {
        if (receipt !== pending) return result(LeapmotorClimateCommandState.SUPERSEDED)
        val current = observe(receipt.side)
        if (current.state == LeapmotorClimateState.ACCESS_DENIED) {
            pending = null
            return result(LeapmotorClimateCommandState.ACCESS_DENIED)
        }
        if (current.commandsEnabled && current.timestampNanos!! > receipt.submittedAtNanos &&
            abs(current.targetCelsius!! - receipt.celsius) <= 0.0001) {
            pending = null
            return result(LeapmotorClimateCommandState.CONFIRMED)
        }
        return LeapmotorClimateCommandResult(LeapmotorClimateCommandState.SUBMITTED_UNCONFIRMED, receipt)
    }

    @Synchronized
    fun cancelPending() { pending = null }

    private fun result(state: LeapmotorClimateCommandState) = LeapmotorClimateCommandResult(state)

    private fun onGrid(value: Double, min: Double, step: Double): Boolean {
        val count = (value - min) / step
        return count.isFinite() && abs(count - round(count)) <= 0.0001
    }

    companion object { const val TEMPERATURE_PROPERTY_ID = 358614275 }
}
