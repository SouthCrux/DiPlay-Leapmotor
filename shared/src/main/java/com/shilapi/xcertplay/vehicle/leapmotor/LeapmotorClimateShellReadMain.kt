package com.shilapi.xcertplay.vehicle.leapmotor

import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import dalvik.system.PathClassLoader
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

/** Manual shell-only app_process entry point. Never installs a backend or invokes a setter. */
object LeapmotorClimateShellReadMain {
    private const val PERMISSION = "android.car.permission.CONTROL_CAR_CLIMATE"
    @JvmStatic fun main(args: Array<String>) {
        Thread({
            Thread.sleep(25_000)
            println("${LeapmotorClimateShellProbe.PREFIX}|TIMEOUT")
            exitProcess(2)
        }, "diplay-climate-read-deadline").apply { isDaemon = true; start() }
        val lines = if (args.contentEquals(arrayOf("inspect"))) {
            LeapmotorClimateShellProbe.inspect(Process.myUid(), SystemClock::elapsedRealtimeNanos) { Platform() }
        } else listOf("${LeapmotorClimateShellProbe.PREFIX}|INVALID_ARGUMENTS")
        lines.forEach(::println)
        exitProcess(0)
    }

    /** A real shell UID/package association is checked before constructing this attribution context. */
    private class ShellContext : ContextWrapper(null) {
        private val pmType = Class.forName("android.content.pm.IPackageManager")
        private val pm = Class.forName("android.content.pm.IPackageManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, service("package"))
            ?: error("Unavailable")
        init {
            check(Process.myUid() == 2000)
            val packages = call(pmType, pm, "getPackagesForUid", arrayOf(INT), arrayOf(Process.myUid())) as? Array<*>
            check(packages?.contains("com.android.shell") == true)
        }
        override fun getPackageName() = "com.android.shell"
        override fun getOpPackageName() = "com.android.shell"
        override fun getAttributionTag(): String? = null
        override fun checkSelfPermission(permission: String): Int {
            require(permission == PERMISSION)
            val activityManager = Class.forName("android.app.ActivityManager")
                .getMethod("getService").invoke(null) ?: error("Unavailable")
            return call(Class.forName("android.app.IActivityManager"), activityManager, "checkPermission",
                arrayOf(String::class.java, INT, INT), arrayOf(permission, Process.myPid(), Process.myUid())) as Int
        }
    }

    private class Platform : LeapmotorClimateShellProbe.Port {
        private val context = ShellContext()
        private var facade: ReadOnlyPropertyFacade? = null
        override fun permitted() = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
        private fun access(): ReflectiveLeapmotorClimateAccess {
            if (!permitted()) throw SecurityException()
            val current = facade ?: run {
                // Firmware's own platform library; no copied jar, SDK replacement or package impersonation.
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
                ReadOnlyPropertyFacade(propertyType, property).also { facade = it }
            }
            return ReflectiveLeapmotorClimateAccess(current)
        }
        override fun configurations() = access().configurations()
        override fun read(area: Int) = access().readTarget(area)
        override fun close() { facade?.close(); facade = null }
    }

    /** Public reflection target has GET methods only; no Car service binding or listener is acquired. */
    class ReadOnlyPropertyFacade internal constructor(private val type: Class<*>, property: Any) : java.io.Closeable {
        private var property: Any? = property
        fun getPropertyList(): Any? = call(type, checkNotNull(property), "getPropertyConfigList",
            arrayOf(IntArray::class.java), arrayOf(intArrayOf(LeapmotorClimateShellProbe.PROPERTY)))
        fun getProperty(id: Int, area: Int): Any? {
            require(id == LeapmotorClimateShellProbe.PROPERTY && LeapmotorClimateSide.entries.any { it.areaId == area })
            return call(type, checkNotNull(property), "getProperty", arrayOf(INT, INT), arrayOf(id, area))
        }
        override fun close() { property = null }
    }

    private val INT = Int::class.javaPrimitiveType!!
    private fun service(name: String): IBinder = Class.forName("android.os.ServiceManager")
        .getMethod("checkService", String::class.java).invoke(null, name) as? IBinder ?: error("Unavailable")
    private fun call(type: Class<*>, target: Any, name: String, types: Array<Class<*>>, args: Array<Any>): Any? =
        try { type.getMethod(name, *types).invoke(target, *args) }
        catch (error: InvocationTargetException) { throw error.targetException }
}
