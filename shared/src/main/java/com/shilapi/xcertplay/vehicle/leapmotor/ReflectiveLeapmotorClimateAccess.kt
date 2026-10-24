package com.shilapi.xcertplay.vehicle.leapmotor

import java.lang.reflect.InvocationTargetException

/**
 * Narrow adapter over an existing authorized CarPropertyManager. It never obtains a service,
 * grants permissions or loads OEM code. Only the standard Float temperature target is writable.
 */
class ReflectiveLeapmotorClimateAccess(private val manager: Any) : LeapmotorClimateAccess {
    override fun configurations(): List<LeapmotorClimateConfiguration> {
        val configs = invoke(manager, "getPropertyList") as? List<*>
            ?: error("Missing climate property configuration")
        return configs.mapNotNull { candidate ->
            val config = checkNotNull(candidate)
            val id = invoke(config, "getPropertyId") as Int
            if (id != LeapmotorClimateController.TEMPERATURE_PROPERTY_ID) return@mapNotNull null
            val areas = invoke(config, "getAreaIds") as IntArray
            LeapmotorClimateConfiguration(
                id, invoke(config, "getAccess") as Int,
                invoke(config, "getPropertyType") as Class<*>,
                invoke(config, "getChangeMode") as Int,
                areas.filter { area -> LeapmotorClimateSide.entries.any { it.areaId == area } }.map { area ->
                    LeapmotorClimateArea(
                        area, invoke(config, "getMinValue", arrayOf(Int::class.javaPrimitiveType!!), arrayOf(area)) as Float,
                        invoke(config, "getMaxValue", arrayOf(Int::class.javaPrimitiveType!!), arrayOf(area)) as Float,
                    )
                },
            )
        }
    }

    override fun readTarget(areaId: Int): CarPropertyReading? {
        checkArea(areaId)
        val raw = invoke(manager, "getProperty", arrayOf(INT, INT), arrayOf(PROPERTY, areaId)) ?: return null
        return CarPropertyReading(
            invoke(raw, "getPropertyId") as Int,
            invoke(raw, "getAreaId") as Int,
            invoke(raw, "getStatus") as Int,
            invoke(raw, "getTimestamp") as Long,
            invoke(raw, "getValue"),
        )
    }

    override fun writeTarget(areaId: Int, celsius: Float) {
        checkArea(areaId)
        require(celsius.isFinite())
        invoke(manager, "setFloatProperty", arrayOf(INT, INT, Float::class.javaPrimitiveType!!),
            arrayOf(PROPERTY, areaId, celsius))
    }

    private fun checkArea(areaId: Int) {
        require(LeapmotorClimateSide.entries.any { it.areaId == areaId })
    }

    private fun invoke(
        target: Any, method: String,
        types: Array<Class<*>> = emptyArray(), values: Array<Any> = emptyArray(),
    ): Any? = try {
        target.javaClass.getMethod(method, *types).invoke(target, *values)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    companion object {
        private val INT = Int::class.javaPrimitiveType!!
        private const val PROPERTY = LeapmotorClimateController.TEMPERATURE_PROPERTY_ID
    }
}
