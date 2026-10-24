package com.shilapi.xcertplay.vehicle.leapmotor

import java.lang.reflect.InvocationTargetException

/**
 * Read-only adapter for an already obtained Android CarPropertyManager. Does not load extracted JARs,
 * start/bind a service, grant a permission, reconnect, or expose a generic Binder transaction API.
 * Reflection avoids bundling OEM JARs in DiPlay; platform API loading remains the caller's job.
 */
class ReflectiveCarPropertyAccess(private val manager: Any) : ReadOnlyCarPropertyAccess {
    override fun configurations(): List<CarPropertyDescriptor> {
        val configs = call(manager, "getPropertyList") as? List<*>
            ?: throw IllegalStateException("CarPropertyManager returned no configuration list")
        val allowedIds = LeapmotorEvProperty.entries.map { it.id }.toSet()
        return configs.mapNotNull { config ->
            checkNotNull(config) { "Null property configuration" }
            val id = call(config, "getPropertyId") as Int
            if (id !in allowedIds) return@mapNotNull null
            CarPropertyDescriptor(
                id = id,
                access = call(config, "getAccess") as Int,
                global = call(config, "isGlobalProperty") as Boolean,
                type = call(config, "getPropertyType") as Class<*>,
                changeMode = call(config, "getChangeMode") as Int,
            )
        }
    }

    override fun read(property: LeapmotorEvProperty): CarPropertyReading? {
        val raw = try {
            manager.javaClass.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(manager, property.id, 0)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        } ?: return null
        return CarPropertyReading(
            id = call(raw, "getPropertyId") as Int,
            areaId = call(raw, "getAreaId") as Int,
            status = call(raw, "getStatus") as Int,
            timestampNanos = call(raw, "getTimestamp") as Long,
            value = call(raw, "getValue"),
        )
    }

    private fun call(target: Any, method: String): Any? = try {
        target.javaClass.getMethod(method).invoke(target)
    } catch (e: InvocationTargetException) {
        // Preserve SecurityException so permission loss is distinguishable from bad API/config data.
        throw e.targetException
    }
}
