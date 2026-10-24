package com.shilapi.xcertplay.vehicle.leapmotor

import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Binder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import dalvik.system.PathClassLoader
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.system.exitProcess

/** Explicit shell diagnostic only. No manifest component, automatic launch or production caller. */
object LeapmotorClimateShellWriteMain {
    @JvmStatic fun main(args: Array<String>) {
        val parsed = LeapmotorClimateShellWriteProbe.parse(args)
        if (parsed == null) {
            println("${LeapmotorClimateShellWriteProbe.PREFIX}|INVALID_ARGUMENTS")
            exitProcess(2)
        }
        Thread({
            Thread.sleep(25_000)
            println("${LeapmotorClimateShellWriteProbe.PREFIX}|TIMEOUT|physicalResult=UNKNOWN")
            exitProcess(2)
        }, "diplay-climate-set-deadline").apply { isDaemon = true; start() }
        val lines = LeapmotorClimateShellWriteProbe.run(Process.myUid(), parsed.first, parsed.second,
            SystemClock::elapsedRealtimeNanos, onDispatch = { println(it); System.out.flush() }) { Platform() }
        lines.filterNot { it.contains("|DISPATCH_BEGIN|") }.forEach(::println)
        exitProcess(LeapmotorClimateShellWriteProbe.exitCode(lines))
    }

    private class ShellContext : ContextWrapper(null) {
        init {
            check(Process.myUid() == 2000)
            val type = Class.forName("android.content.pm.IPackageManager")
            val pm = Class.forName("android.content.pm.IPackageManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, service("package"))
                ?: error("Unavailable")
            val packages = call(type, pm, "getPackagesForUid", arrayOf(INT), arrayOf(Process.myUid())) as? Array<*>
            check(packages?.contains("com.android.shell") == true)
        }
        override fun getPackageName() = "com.android.shell"
        override fun getOpPackageName() = "com.android.shell"
        override fun getAttributionTag(): String? = null
        override fun checkSelfPermission(permission: String): Int {
            require(permission == PERMISSION)
            val manager = Class.forName("android.app.ActivityManager").getMethod("getService")
                .invoke(null) ?: error("Unavailable")
            return call(Class.forName("android.app.IActivityManager"), manager, "checkPermission",
                arrayOf(String::class.java, INT, INT), arrayOf(permission, Process.myPid(), Process.myUid())) as Int
        }
    }

    private class Platform : LeapmotorClimateShellWriteProbe.Port {
        private val context = ShellContext()
        private var facade: WriteOnlyPropertyFacade? = null
        override fun permitted() = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
        private fun access(): ReflectiveLeapmotorClimateAccess {
            if (!permitted()) throw SecurityException()
            val current = facade ?: run {
                val loader = PathClassLoader("/system/framework/android.car.jar", javaClass.classLoader)
                val carType = Class.forName("android.car.ICar", true, loader)
                val car = Class.forName("android.car.ICar\$Stub", true, loader)
                    .getMethod("asInterface", IBinder::class.java).invoke(null, service("car_service"))
                    ?: error("Unavailable")
                val binder = call(carType, car, "getCarService", arrayOf(String::class.java), arrayOf("property")) as? IBinder
                    ?: error("Unavailable")
                val propertyType = Class.forName("android.car.hardware.property.ICarProperty", true, loader)
                val property = Class.forName("android.car.hardware.property.ICarProperty\$Stub", true, loader)
                    .getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: error("Unavailable")
                val valueType = Class.forName("android.car.hardware.CarPropertyValue", true, loader)
                val listenerType = Class.forName("android.car.hardware.property.ICarPropertyEventListener", true, loader)
                WriteOnlyPropertyFacade(propertyType, property, valueType, listenerType).also { facade = it }
            }
            return ReflectiveLeapmotorClimateAccess(current)
        }
        override fun configurations() = access().configurations()
        override fun write(areaId: Int, targetCelsius: Float) = access().writeTarget(areaId, targetCelsius)
        override fun observeSetError() = facade?.observeSetError()
        override fun close() { facade?.close(); facade = null }
    }

    /** Fixed firmware signature; only a bounded negative error prefix is read, never target state. */
    class WriteOnlyPropertyFacade internal constructor(
        private val type: Class<*>, property: Any, private val valueType: Class<*>, listenerType: Class<*>,
    ) : java.io.Closeable {
        private var property: Any? = property
        private val errors = LeapmotorClimateSetErrorObservation()
        private val callbackBinder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
                INTERFACE_TRANSACTION -> { reply?.writeString(LISTENER_DESCRIPTOR); true }
                1 -> {
                    data.enforceInterface(LISTENER_DESCRIPTOR)
                    try { readSetError(data) }
                    catch (_: RuntimeException) { /* Malformed/unsupported callback cannot be evidence. */ }
                    finally { data.setDataPosition(data.dataSize()) }
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
        /**
         * Captured onPropertySetError emits a singleton typed list. Read its fixed scalar prefix
         * only: no Parcelable creator, arbitrary class loading, value deserialization or raw logs.
         */
        private fun readSetError(data: Parcel) {
            if (data.dataAvail() !in 1..1_024 || data.readInt() != 1 || data.readInt() != 1) return
            val eventType = data.readInt()
            if (eventType != 1) return
            val errorCode = data.readInt()
            if (data.readString() != "android.car.hardware.CarPropertyValue") return
            if (data.dataAvail() < 20) return
            val id = data.readInt()
            val area = data.readInt()
            val status = data.readInt()
            val timestamp = data.readLong()
            errors.receive(eventType, id, area, status, timestamp, errorCode)
        }
        private val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
            when (method.name) {
                "asBinder" -> callbackBinder
                "onEvent" -> null
                "toString" -> "DiPlay climate diagnostic callback"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("Unsupported callback")
            }
        }
        private val setter = type.getMethod("setProperty", valueType, listenerType)
        fun getPropertyList(): Any? = call(type, checkNotNull(property), "getPropertyConfigList",
            arrayOf(IntArray::class.java), arrayOf(intArrayOf(LeapmotorClimateShellProbe.PROPERTY)))
        fun setFloatProperty(id: Int, area: Int, value: Float) {
            require(id == LeapmotorClimateShellProbe.PROPERTY && LeapmotorClimateSide.entries.any { it.areaId == area })
            require(value.isFinite() && value in 18f..32f && value % 1f == 0f)
            val target = checkNotNull(property)
            val wrapped = valueType.getConstructor(INT, INT, Any::class.java).newInstance(id, area, value)
            errors.begin(area)
            try { setter.invoke(target, wrapped, listener) }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
        fun observeSetError(maxWaitMillis: Long = 500): LeapmotorClimateSetErrorObservation.Result? =
            errors.awaitResult(maxWaitMillis)
        override fun close() { errors.awaitResult(0); property = null }
    }

    private const val PERMISSION = "android.car.permission.CONTROL_CAR_CLIMATE"
    private const val LISTENER_DESCRIPTOR = "android.car.hardware.property.ICarPropertyEventListener"
    private val INT = Int::class.javaPrimitiveType!!
    private fun service(name: String): IBinder = Class.forName("android.os.ServiceManager")
        .getMethod("checkService", String::class.java).invoke(null, name) as? IBinder ?: error("Unavailable")
    private fun call(type: Class<*>, target: Any, name: String, types: Array<Class<*>>, args: Array<Any>): Any? =
        try { type.getMethod(name, *types).invoke(target, *args) }
        catch (error: InvocationTargetException) { throw error.targetException }
}
