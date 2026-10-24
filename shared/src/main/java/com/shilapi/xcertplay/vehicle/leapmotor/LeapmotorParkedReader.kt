package com.shilapi.xcertplay.vehicle.leapmotor

import android.os.SystemClock
import java.lang.reflect.InvocationTargetException

interface ReadOnlyLeapmotorGearAccess {
    fun configurations(): List<CarPropertyDescriptor>
    fun readGear(): CarPropertyReading?
}

enum class LeapmotorGearFreshness { OEM_EVENT_TIMESTAMP, CURRENT_HAL_SNAPSHOT }

data class LeapmotorParkedReading(val state: PropertyReadState, val parked: Boolean? = null,
    /** OEM event/change time, never replaced with poll time. */
    val timestampNanos: Long? = null,
    /** Local completion of a synchronous HAL get, not a vehicle measurement timestamp. */
    val halReadCompletedAtNanos: Long? = null)

/** Explicit freshness contract; a current HAL snapshot keeps OEM event time distinct from delivery. */
class LeapmotorParkedReader(private val access: ReadOnlyLeapmotorGearAccess,
    private val maxAgeNanos: Long = 5_000_000_000L,
    private val elapsedNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val freshness: LeapmotorGearFreshness = LeapmotorGearFreshness.OEM_EVENT_TIMESTAMP) {
    init { require(maxAgeNanos > 0) }
    fun read(): LeapmotorParkedReading = try {
        val configs = access.configurations().filter { it.id == GEAR_SELECTION }
        when {
            configs.isEmpty() -> LeapmotorParkedReading(PropertyReadState.NOT_EXPOSED)
            configs.size != 1 -> LeapmotorParkedReading(PropertyReadState.INVALID_CONFIG)
            else -> {
                val c = configs.single()
                if (c.access !in listOf(1, 3) || !c.global || c.type != java.lang.Integer::class.java || c.changeMode != 1)
                    LeapmotorParkedReading(PropertyReadState.INVALID_CONFIG)
                else {
                    val started = elapsedNanos()
                    val raw = access.readGear()
                    validate(raw, started)
                }
            }
        }
    } catch (_: SecurityException) { LeapmotorParkedReading(PropertyReadState.ACCESS_DENIED) }
    catch (_: Exception) { LeapmotorParkedReading(PropertyReadState.ERROR) }

    private fun validate(raw: CarPropertyReading?, started: Long): LeapmotorParkedReading {
        raw ?: return LeapmotorParkedReading(PropertyReadState.UNAVAILABLE)
        fun failure(state: PropertyReadState) = LeapmotorParkedReading(state, timestampNanos = raw.timestampNanos)
        if (raw.id != GEAR_SELECTION || raw.areaId != 0 || raw.value !is Int || raw.value !in GEARS)
            return failure(PropertyReadState.INVALID_VALUE)
        if (raw.status == 1) return failure(PropertyReadState.UNAVAILABLE)
        if (raw.status != 0) return failure(PropertyReadState.ERROR)
        val now = elapsedNanos()
        if (raw.timestampNanos < 0 || raw.timestampNanos > now) return failure(PropertyReadState.INVALID_VALUE)
        if (raw.timestampNanos == 0L || started < 0 || now < started || now - started > maxAgeNanos)
            return failure(PropertyReadState.STALE)
        if (freshness == LeapmotorGearFreshness.OEM_EVENT_TIMESTAMP && now - raw.timestampNanos > maxAgeNanos)
            return failure(PropertyReadState.STALE)
        return LeapmotorParkedReading(PropertyReadState.AVAILABLE, raw.value == 4, raw.timestampNanos, now)
    }
    companion object {
        const val GEAR_SELECTION = 289408000
        private val GEARS = setOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096)
    }
}

/** Existing platform manager only; no setters, extracted code or generic property access. */
class ReflectiveLeapmotorGearAccess(private val manager: Any) : ReadOnlyLeapmotorGearAccess {
    override fun configurations(): List<CarPropertyDescriptor> =
        (invoke(manager, "getPropertyList") as List<*>).mapNotNull { candidate ->
            val c = checkNotNull(candidate)
            val id = invoke(c, "getPropertyId") as Int
            if (id != LeapmotorParkedReader.GEAR_SELECTION) null else CarPropertyDescriptor(id,
                invoke(c, "getAccess") as Int, invoke(c, "isGlobalProperty") as Boolean,
                invoke(c, "getPropertyType") as Class<*>, invoke(c, "getChangeMode") as Int)
        }
    override fun readGear(): CarPropertyReading? {
        val raw = invoke(manager, "getProperty", arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            arrayOf(LeapmotorParkedReader.GEAR_SELECTION, 0)) ?: return null
        return CarPropertyReading(invoke(raw, "getPropertyId") as Int, invoke(raw, "getAreaId") as Int,
            invoke(raw, "getStatus") as Int, invoke(raw, "getTimestamp") as Long, invoke(raw, "getValue"))
    }
    private fun invoke(target: Any, method: String, types: Array<Class<*>> = emptyArray(),
        args: Array<Any> = emptyArray()): Any? = try { target.javaClass.getMethod(method, *types).invoke(target, *args) }
    catch (error: InvocationTargetException) { throw error.targetException }
}
