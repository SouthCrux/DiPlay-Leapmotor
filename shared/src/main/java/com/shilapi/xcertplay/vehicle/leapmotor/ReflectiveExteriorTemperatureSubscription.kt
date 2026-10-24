package com.shilapi.xcertplay.vehicle.leapmotor

import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Activates the platform CONTINUOUS producer at 1Hz. Caller runs start/close off the UI thread.
 * Callback values are deliberately not published; typed GET validation remains the only data path.
 * A failed/partially completed registration is retired with the identical callback and property.
 */
class ReflectiveExteriorTemperatureSubscription(private val manager: Any,
    onFirstChange: () -> Unit = {}, onError: () -> Unit) : Closeable {
    private val firstChange = java.util.concurrent.atomic.AtomicBoolean()
    private val register = manager.javaClass.methods.single {
        it.name == "registerCallback" && it.parameterTypes.size == 3 &&
            it.parameterTypes[0].isInterface && it.parameterTypes[0].simpleName == "CarPropertyEventCallback" &&
            it.parameterTypes[1] == Int::class.javaPrimitiveType &&
            it.parameterTypes[2] == Float::class.javaPrimitiveType
    }
    private val callbackType = register.parameterTypes[0]
    private val unregister = manager.javaClass.getMethod("unregisterCallback", callbackType,
        Int::class.javaPrimitiveType)
    private val callback: Any = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "DiPlayExteriorTemperatureCallback"
            "onChangeEvent" -> {
                if (!closed && firstChange.compareAndSet(false, true)) onFirstChange()
                null
            }
            "onErrorEvent" -> {
                if (!closed && args?.firstOrNull() == LeapmotorExteriorTemperatureReader.PROPERTY_ID) onError()
                null
            }
            // No callback timestamp/value is trusted or substituted for the GET result.
            else -> null
        }
    }
    private var attempted = false
    @Volatile private var closed = false
    @Synchronized fun start() {
        if (closed || attempted) return
        attempted = true
        val accepted = invoke { register.invoke(manager, callback, LeapmotorExteriorTemperatureReader.PROPERTY_ID, 1.0f) }
        check(accepted == true) { "Exterior callback registration rejected" }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        if (attempted) invoke { unregister.invoke(manager, callback, LeapmotorExteriorTemperatureReader.PROPERTY_ID) }
    }
    private fun <T> invoke(block: () -> T): T = try { block() }
        catch (error: InvocationTargetException) { throw error.targetException }
}
