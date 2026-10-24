package com.shilapi.xcertplay.airplay

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Executes the actual opt-in JNI library; no phone, display or manufacturer resources required. */
class UltraRuntimeLifetimeTest {
    private fun withRuntime(lifetime: UltraRuntimeLifetime, entry: String,
                            block: (UltraNativeRuntime) -> Unit) {
        val library = System.getenv("DIPLAY_ULTRA_JNI_LIBRARY")
        val bridge = System.getenv("DIPLAY_ULTRA_BRIDGE_SOURCE")
        assumeTrue(library != null && bridge != null)
        val root = Files.createTempDirectory("diplay-ultra-lifetime").toFile()
        try {
            UltraNativeRuntime.open(root, File(bridge!!).readBytes(), entry.toByteArray(), lifetime = lifetime,
                loadLibrary = { System.load(library!!) }).use(block)
        } finally { root.delete() }
    }

    private val idle = """
        Offscreen.configureOutput("fixture",40,30)
        Offscreen.startProtocolForResearch()
        return "initialized"
    """.trimIndent()

    @Test fun defaultResearchBudgetStillRetiresAtItsOriginalBoundary() =
        withRuntime(UltraRuntimeLifetime.BOUNDED_RESEARCH, idle) { runtime ->
            repeat(4096) { assertEquals(emptyList<Any>(), runtime.request(6)) }
            assertThrows(IllegalStateException::class.java) { runtime.request(6) }
            assertThrows(IllegalStateException::class.java) { runtime.request(6) }
        }

    @Test fun explicitSessionOwnedRuntimeSurvivesBeyondTheResearchCounterAndClosesNormally() =
        withRuntime(UltraRuntimeLifetime.SESSION_OWNED_INTEGRATION, idle) { runtime ->
            repeat(8192) {
                if (it % 4 == 0) runtime.request(2, byteArrayOf(1))
                else assertEquals(emptyList<Any>(), runtime.request(6))
            }
            runtime.close()
            assertThrows(IllegalStateException::class.java) { runtime.request(6) }
        }

    @Test fun sessionOwnedRuntimeStillTerminatesAnOperationThatExceedsItsCpuDeadline() =
        withRuntime(UltraRuntimeLifetime.SESSION_OWNED_INTEGRATION, idle.replace("return \"initialized\"", """
            local task=CP.DPLuaTask.create()
            task:run(function()while true do end end)
            return "initialized"
        """.trimIndent())) { runtime ->
            assertThrows(IllegalStateException::class.java) { runtime.request(2, byteArrayOf(1)) }
            assertThrows(IllegalStateException::class.java) { runtime.request(6) }
        }
}
