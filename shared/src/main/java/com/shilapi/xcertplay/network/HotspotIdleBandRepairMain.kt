package com.shilapi.xcertplay.network

import android.os.Build
import android.os.IBinder
import android.os.Process
import kotlin.system.exitProcess

/** Manual shell entry point. Only boolean flags and fixed results can reach stdout. */
object HotspotIdleBandRepairMain {
    @JvmStatic fun main(args: Array<String>) {
        Thread({ Thread.sleep(25_000); exitProcess(2) }, "hotspot-idle-band-deadline")
            .apply { isDaemon = true; start() }
        val result = try {
            require(Process.myUid() == 2000 || Process.myUid() == 0)
            require(Build.VERSION.SDK_INT >= 31)
            require(args.size == 1 && args[0] in setOf("inspect", "keep-bands", "restore-idle-shutdown"))
            val platform = Platform()
            if (args[0] == "inspect") {
                val config = platform.read()
                "INSPECT|idleShutdown=${platform.idleShutdown(config)}|userConfig=${platform.isUserConfiguration(config)}"
            } else HotspotIdleBandRepair(platform).set(args[0] == "restore-idle-shutdown").name
        } catch (_: Throwable) { "UNAVAILABLE" }
        println("DIPLAY_IDLE_BAND_V1|$result")
        exitProcess(0)
    }

    private class Platform : HotspotIdleBandRepair.Port<Any> {
        private val configType = Class.forName("android.net.wifi.SoftApConfiguration")
        private val builderType = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
        private val wifi = Class.forName("android.net.wifi.IWifiManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null,
                Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                    .invoke(null, "wifi")) ?: error("Unavailable")
        override fun read(): Any = wifi.javaClass.getMethod("getSoftApConfiguration").invoke(wifi)
            ?: error("Unavailable")
        override fun idleShutdown(config: Any) = configType
            .getMethod("isBridgedModeOpportunisticShutdownEnabled").invoke(config) == true
        override fun isUserConfiguration(config: Any) = configType
            .getMethod("isUserConfiguration").invoke(config) == true
        override fun withIdleShutdown(config: Any, enabled: Boolean): Any {
            val builder = builderType.getConstructor(configType).newInstance(config)
            builderType.getMethod("setBridgedModeOpportunisticShutdownEnabled", Boolean::class.javaPrimitiveType)
                .invoke(builder, enabled)
            return builderType.getMethod("build").invoke(builder) ?: error("Unavailable")
        }
        override fun write(config: Any) = wifi.javaClass.getMethod("setSoftApConfiguration", configType,
            String::class.java).invoke(wifi, config, "com.android.shell") == true
    }
}
