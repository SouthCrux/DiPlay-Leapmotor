package com.shilapi.xcertplay.airplay

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class UltraVehicleBatteryLifecycleTest {
    @Test fun unavailableAndIndependentValuesAreExplicitAndProviderIsThrottled() {
        var now = 10_000L
        var calls = 0
        var value: UltraVehicleBatteryState? = null
        val input = UltraVehicleBatteryLifecycle({ calls++; value }, { now })
        assertEquals(emptyMap<String, Any>(), BoundedPlist.decode(input.takePending()!!.payload()))
        assertNull(input.takePending())
        assertEquals(1, calls)
        value = UltraVehicleBatteryState(0.0, null, now)
        now += 1_000
        assertEquals(UltraVehicleBatteryLifecycle.Update(0.0, null), input.takePending())
        value = UltraVehicleBatteryState(null, 0.0, now)
        now += 1_000
        assertEquals(UltraVehicleBatteryLifecycle.Update(null, 0.0), input.takePending())
    }

    @Test fun repeatedCachedDeliveryExpiresWithoutBecomingAFreshOemMeasurement() {
        var now = 1_000L
        val cached = UltraVehicleBatteryState(75.0, 123.0, now)
        val input = UltraVehicleBatteryLifecycle({ cached }, { now })
        assertEquals(UltraVehicleBatteryLifecycle.Update(75.0, 123.0), input.takePending())
        now += 5_000
        assertNull(input.takePending())
        now++
        assertEquals(UltraVehicleBatteryLifecycle.Update(null, null), input.takePending())
        assertNull(input.takePending())
    }

    @Test fun providerLossFailureFutureTimeAndRetirementCannotRetainValues() {
        var now = 1_000L
        var state: UltraVehicleBatteryState? = UltraVehicleBatteryState(50.0, 100.0, now)
        var fail = false
        var calls = 0
        val input = UltraVehicleBatteryLifecycle({ calls++; if (fail) error("source lost") else state }, { now })
        assertNotNull(input.takePending())
        fail = true; now += 1_000
        assertEquals(UltraVehicleBatteryLifecycle.Update(null, null), input.takePending())
        fail = false; state = UltraVehicleBatteryState(50.0, 100.0, now + 10_000); now += 1_000
        assertNull(input.takePending())
        state = UltraVehicleBatteryState(50.0, 100.0, now); now += 1_000
        assertEquals(UltraVehicleBatteryLifecycle.Update(50.0, 100.0), input.takePending())
        state = null; now += 1_000
        assertEquals(UltraVehicleBatteryLifecycle.Update(null, null), input.takePending())
        input.close()
        val previousCalls = calls
        now += 10_000
        assertNull(input.takePending())
        assertEquals(previousCalls, calls)
    }

    @Test fun malformedValuesAreRejectedRatherThanClampedOrGivenStates() {
        for (percent in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 101.0))
            assertThrows(IllegalArgumentException::class.java) { UltraVehicleBatteryState(percent, null, 0) }
        for (range in listOf(Double.NaN, Double.NEGATIVE_INFINITY, -1.0, 100_001.0))
            assertThrows(IllegalArgumentException::class.java) { UltraVehicleBatteryState(null, range, 0) }
        assertThrows(IllegalArgumentException::class.java) { UltraVehicleBatteryState(null, null, -1) }
    }

    private fun withRuntime(declare: Boolean = true, original: Boolean = false, block: (UltraNativeRuntime) -> Unit) {
        val library = System.getenv("DIPLAY_ULTRA_JNI_LIBRARY")
        val bridge = System.getenv("DIPLAY_ULTRA_BRIDGE_SOURCE")
        val resources = System.getenv("DIPLAY_ULTRA_TEST_RESOURCES")
        assumeTrue(library != null && bridge != null && (!original || resources != null))
        val root = if (original) File(resources!!) else Files.createTempDirectory("diplay-ultra-battery").toFile()
        val updater = if (original) """
            local updater=require("VehicleDataUpdater").new(CP.getVehicleDataConfig())
            local function data()
                updater:_fetch(false,false)
                local values=updater:getData({"chargeLevel","remainingBatteryDistance"})
                local percent=values.chargeLevel
                local distance=values.remainingBatteryDistance
                return percent, if distance==CP.INVALIDVALUE then nil else distance
            end
        """ else """
            local function data()
                local values=CP.getVehicleData({2,3,4})
                return values[2], if values[3]==CP.INVALIDVALUE then nil else {[151]=values[3],[164]=values[4]}
            end
        """
        val entry = """
            Offscreen.configureOutput("fixture",40,30)
            ${if (declare) "Offscreen.configureBatteryService()" else ""}
            Offscreen.startProtocolForResearch()
            $updater
            local task=CP.DPLuaTask.create()
            task:run(function()while true do
                local percent,distance=data()
                local available=percent~=nil and percent~=CP.INVALIDVALUE
                CP.sendMessage(CP.createPlist({percent=if available then percent else -1,
                    kilometers=if distance then distance[151] else -1,
                    miles=if distance then distance[164] else -1,
                    declared=CP.getVehicleDataConfig().highVoltageBattery~=nil}))
                coroutine.yield()
            end end)
            return "initialized"
        """.trimIndent()
        try {
            UltraNativeRuntime.open(root, File(bridge!!).readBytes(), entry.toByteArray(),
                loadLibrary = { System.load(library!!) }).use(block)
        } finally { if (!original) root.delete() }
    }

    private fun read(runtime: UltraNativeRuntime): Map<String, Any?> {
        runtime.request(2, byteArrayOf(1))
        return checkNotNull(BoundedPlist.decode((runtime.request(6) as List<*>).single() as ByteArray))
    }

    private fun assertBattery(runtime: UltraNativeRuntime) {
        val initial = read(runtime)
        assertEquals(true, initial["declared"])
        assertEquals(-1L, initial["percent"])
        assertEquals(-1L, initial["kilometers"])
        runtime.request(9, UltraVehicleBatteryLifecycle.Update(75.0, 160.9344).payload())
        val actual = read(runtime)
        assertEquals(75.0, (actual["percent"] as Number).toDouble(), 0.0)
        assertEquals(160.9344, (actual["kilometers"] as Number).toDouble(), 0.000001)
        assertEquals(100.0, (actual["miles"] as Number).toDouble(), 0.000001)
        runtime.request(9, UltraVehicleBatteryLifecycle.Update(null, null).payload())
        val lost = read(runtime)
        assertEquals(-1L, lost["percent"])
        assertEquals(-1L, lost["kilometers"])
    }

    @Test fun nativeCpBatteryStartsInvalidConvertsUnitsAndInvalidates() = withRuntime { assertBattery(it) }

    @Test fun originalUnmodifiedVehicleDataUpdaterConsumesBatteryCpContract() =
        withRuntime(original = true) { assertBattery(it) }

    @Test fun nativeUndeclaredServiceAndMalformedInputRetireVm() {
        withRuntime(declare = false) { runtime ->
            assertThrows(IllegalStateException::class.java) {
                runtime.request(9, UltraVehicleBatteryLifecycle.Update(50.0, 100.0).payload())
            }
            assertThrows(IllegalStateException::class.java) { runtime.request(6) }
        }
        for (payload in listOf(mapOf("chargePercent" to 101.0), mapOf("rangeKilometers" to -1.0),
            mapOf("charging" to true))) withRuntime { runtime ->
            assertThrows(IllegalStateException::class.java) { runtime.request(9, BplistCodec.encode(payload)) }
            assertThrows(IllegalStateException::class.java) { runtime.request(6) }
        }
    }
}
