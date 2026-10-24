package com.shilapi.xcertplay.vehicle.leapmotor

import android.os.SystemClock
import java.lang.reflect.InvocationTargetException

interface ReadOnlyLeapmotorExteriorAccess {
    fun configurations(): List<CarPropertyDescriptor>
    fun readExterior(): CarPropertyReading?
}

data class LeapmotorExteriorTemperatureReading(val state: PropertyReadState,
    val celsius: Float? = null, val timestampNanos: Long? = null,
    /** Local synchronous GET completion, never an OEM measurement timestamp. */
    val halReadCompletedAtNanos: Long? = null)

/** Standard ENV_OUTSIDE_TEMPERATURE Celsius contract, distinct from unverified SomeIP RAW.
 * Permission/configuration and delivery are rechecked on each worker pass. No last-good fallback.
 */
class LeapmotorExteriorTemperatureReader(private val access: ReadOnlyLeapmotorExteriorAccess,
    private val maxAgeNanos: Long = MAX_AGE_NANOS,
    private val elapsedNanos: () -> Long = SystemClock::elapsedRealtimeNanos) {
    init { require(maxAgeNanos > 0) }
    fun read(): LeapmotorExteriorTemperatureReading = try {
        val configs = access.configurations().filter { it.id == PROPERTY_ID }
        when {
            configs.isEmpty() -> LeapmotorExteriorTemperatureReading(PropertyReadState.NOT_EXPOSED)
            configs.size != 1 -> LeapmotorExteriorTemperatureReading(PropertyReadState.INVALID_CONFIG)
            else -> {
                val c = configs.single()
                if (c.access != 1 || !c.global || c.type != java.lang.Float::class.java || c.changeMode != 2)
                    LeapmotorExteriorTemperatureReading(PropertyReadState.INVALID_CONFIG)
                else {
                    val started = elapsedNanos()
                    validate(access.readExterior(), started)
                }
            }
        }
    } catch (_: SecurityException) { LeapmotorExteriorTemperatureReading(PropertyReadState.ACCESS_DENIED) }
    catch (_: Exception) { LeapmotorExteriorTemperatureReading(PropertyReadState.ERROR) }

    private fun validate(raw: CarPropertyReading?, started: Long): LeapmotorExteriorTemperatureReading {
        raw ?: return LeapmotorExteriorTemperatureReading(PropertyReadState.UNAVAILABLE)
        val now = elapsedNanos() // Evaluate freshness after potentially blocked GET.
        fun failure(state: PropertyReadState) = LeapmotorExteriorTemperatureReading(state,
            timestampNanos = raw.timestampNanos, halReadCompletedAtNanos = now)
        if (raw.id != PROPERTY_ID || raw.areaId != 0) return failure(PropertyReadState.INVALID_VALUE)
        if (raw.status == 1) return failure(PropertyReadState.UNAVAILABLE)
        if (raw.status != 0) return failure(PropertyReadState.ERROR)
        if (raw.value !is Float || !raw.value.isFinite() || raw.timestampNanos < 0 || raw.timestampNanos > now)
            return failure(PropertyReadState.INVALID_VALUE)
        if (raw.timestampNanos == 0L || started < 0 || now < started ||
            now - started > maxAgeNanos || now - raw.timestampNanos > maxAgeNanos)
            return failure(PropertyReadState.STALE)
        return LeapmotorExteriorTemperatureReading(PropertyReadState.AVAILABLE, raw.value, raw.timestampNanos, now)
    }
    companion object {
        const val PROPERTY_ID = 291505923
        const val MAX_AGE_NANOS = 60_000_000_000L
    }
}

/** Existing platform manager only. No setters, bundled OEM code or generic property access. */
class ReflectiveLeapmotorExteriorAccess(private val manager: Any) : ReadOnlyLeapmotorExteriorAccess {
    override fun configurations(): List<CarPropertyDescriptor> =
        (invoke(manager, "getPropertyList") as List<*>).mapNotNull { candidate ->
            val c = checkNotNull(candidate)
            val id = invoke(c, "getPropertyId") as Int
            if (id != LeapmotorExteriorTemperatureReader.PROPERTY_ID) null else CarPropertyDescriptor(id,
                invoke(c, "getAccess") as Int, invoke(c, "isGlobalProperty") as Boolean,
                invoke(c, "getPropertyType") as Class<*>, invoke(c, "getChangeMode") as Int)
        }
    override fun readExterior(): CarPropertyReading? {
        val raw = invoke(manager, "getProperty", arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            arrayOf(LeapmotorExteriorTemperatureReader.PROPERTY_ID, 0)) ?: return null
        return CarPropertyReading(invoke(raw, "getPropertyId") as Int, invoke(raw, "getAreaId") as Int,
            invoke(raw, "getStatus") as Int, invoke(raw, "getTimestamp") as Long, invoke(raw, "getValue"))
    }
    private fun invoke(target: Any, method: String, types: Array<Class<*>> = emptyArray(),
        args: Array<Any> = emptyArray()): Any? = try { target.javaClass.getMethod(method, *types).invoke(target, *args) }
    catch (error: InvocationTargetException) { throw error.targetException }
}
