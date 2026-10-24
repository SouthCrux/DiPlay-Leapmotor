package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.media.TimedVideoFrame
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real staged app assets and original controller, with an explicitly simulated UI Sync peer. */
class UltraPackagedResourceRuntimeTest {
    @Test fun localSettingsInputRequiresExplicitRuntimeSelectionAndNeverAppearsInTheDefaultEntry() {
        val packaged = System.getenv("DIPLAY_ULTRA_PACKAGED_ASSETS")
        val library = System.getenv("DIPLAY_ULTRA_JNI_LIBRARY")
        assumeTrue("Explicit staged app assets/native library required", packaged != null && library != null)
        val root = File(packaged!!)
        val parent = Files.createTempDirectory("ultra-app-settings-selection").toFile()
        try {
            for (enabled in listOf(false, true)) {
                checkNotNull(UltraPackagedResourceSource.prepare({ File(root, it).inputStream() }, parent)).use { selection ->
                    val runtime = if (enabled) selection.openRuntimeWithSettings { System.load(library!!) }
                        else selection.openRuntime { System.load(library!!) }
                    runtime.use {
                        val query = BplistCodec.encode(mapOf("action" to "state", "input" to UltraLocalSettingsInput.IDENTIFIER))
                        if (enabled) {
                            val state = it.request(7, query) as Map<*, *>
                            assertEquals(0L, state["currentToken"])
                            assertEquals(0L, state["pendingToken"])
                        } else {
                            assertThrows(IllegalStateException::class.java) { it.request(7, query) }
                        }
                    }
                }
            }
            assertTrue(parent.listFiles()!!.none { it.name != ".registry.lock" })
        } finally { parent.deleteRecursively() }
    }

    @Test fun foregroundBeforeFirstProtocolPollAllowsPeerToCompleteTheCurrentResetAndRequestLayout() {
        val packaged = System.getenv("DIPLAY_ULTRA_PACKAGED_ASSETS")
        val library = System.getenv("DIPLAY_ULTRA_JNI_LIBRARY")
        assumeTrue("Explicit staged app assets/native library required", packaged != null && library != null)
        val root = File(packaged!!)
        val parent = Files.createTempDirectory("ultra-app-assets-early-foreground").toFile()
        try {
            checkNotNull(UltraPackagedResourceSource.prepare({ File(root, it).inputStream() }, parent)).use { selection ->
                selection.openRuntime { System.load(library!!) }.use { runtime ->
                    UltraComposedPanelSession<TimedVideoFrame>(runtime, "iPhoneVideo", UltraSceneInputMode.REFERENCE_CENTER_ACCESSORY).use { session ->
                        session.requestAccessoryScene(true)
                        session.updateHostVisibility(resumed = true, surfaceReady = true)
                        val messages = mutableListOf<Map<String, Any?>>()
                        val resets = mutableListOf<Long>()
                        val queue = java.util.ArrayDeque<Map<String, Any?>>()
                        fun enqueue(bytes: List<ByteArray>) {
                            bytes.forEach { queue.add(checkNotNull(BoundedPlist.decode(it))) }
                        }
                        var budget = 256
                        repeat(24) {
                            enqueue(session.poll())
                            while (queue.isNotEmpty()) {
                                check(budget-- > 0) { "Simulated peer response budget exhausted" }
                                val message = queue.removeFirst()
                                messages += message
                                if (message["type"] == 0L) {
                                    resets += message.getValue("ssn") as Long
                                    // Foreground may legitimately reset again. A real peer must
                                    // answer each reset, rather than replying only to the oldest one.
                                    enqueue(session.receive(BplistCodec.encode(mapOf(
                                        "type" to 1L, "ssn" to message.getValue("ssn"), "psn" to 1L,
                                        "asn" to message.getValue("psn"), "version" to 2L, "id" to "fixture-peer",
                                    ))))
                                }
                            }
                        }
                        assertTrue("No reset emitted; simulated reset sequence=$resets", resets.isNotEmpty())
                        val currentSsn = resets.last()
                        assertTrue("Current reset did not complete; simulated reset sequence=$resets", messages.any {
                            it["type"] == 1L && it["ssn"] == currentSsn
                        })
                        val commands = messages.filter { it["type"] == 2L && it["ssn"] == currentSsn }.mapNotNull {
                            (it["data"] as? ByteArray)?.let(BoundedPlist::decode)
                        }
                        assertTrue("No layout request; simulated reset sequence=$resets", commands.any {
                            it["type"] == 5L && (it["data"] as? Map<*, *>)?.get("layoutID") == selection.layoutId
                        })
                    }
                }
            }
            assertTrue(parent.listFiles()!!.none { it.name != ".registry.lock" })
        } finally { parent.deleteRecursively() }
    }

    @Test fun appAssetSelectionOwnsScriptsAndPngUntilTheConnectionRuntimeCloses() {
        val packaged = System.getenv("DIPLAY_ULTRA_PACKAGED_ASSETS")
        val library = System.getenv("DIPLAY_ULTRA_JNI_LIBRARY")
        assumeTrue("Explicit staged app assets/native library required", packaged != null && library != null)
        val root = File(packaged!!)
        val parent = Files.createTempDirectory("ultra-app-assets-jni").toFile()
        try {
            val selection = checkNotNull(UltraPackagedResourceSource.prepare({ File(root, it).inputStream() }, parent))
            try {
                assertNull(selection.themeBinding.cluster)
                val manifest = checkNotNull(BoundedPlist.decodeResourceManifest(File(root, "ultra/reference-center/resources/research-input.plist").readBytes()))
                val png = (manifest["files"] as Map<*, *>).keys.filterIsInstance<String>().first { it.endsWith(".png") }
                val image = selection.readPng(png)
                val runtime = selection.openRuntime { System.load(library!!) }
                try {
                    selection.close()
                    assertArrayEquals(image, selection.readPng(png))
                    UltraComposedPanelSession<TimedVideoFrame>(runtime, "iPhoneVideo", UltraSceneInputMode.REFERENCE_CENTER_ACCESSORY).use { session ->
                        fun decode(messages: List<ByteArray>) = messages.map { checkNotNull(BoundedPlist.decode(it)) }
                        val reset = decode(session.poll()).first { it["type"] == 0L }
                        val replies = decode(session.receive(BplistCodec.encode(mapOf(
                            "type" to 1L, "ssn" to reset.getValue("ssn"), "psn" to 1L,
                            "asn" to reset.getValue("psn"), "version" to 2L, "id" to "fixture-peer",
                        )))).toMutableList()
                        repeat(8) { replies += decode(session.poll()) }
                        session.requestAccessoryScene(true)
                        session.updateHostVisibility(resumed = true, surfaceReady = true)
                        repeat(10) { replies += decode(session.poll()) }
                        val commands = replies.filter { it["type"] == 2L }.mapNotNull { (it["data"] as? ByteArray)?.let(BoundedPlist::decode) }
                        assertTrue(commands.any { it["type"] == 5L && (it["data"] as? Map<*, *>)?.get("layoutID") == selection.layoutId })
                    }
                    assertTrue(parent.listFiles()!!.none { it.name != ".registry.lock" })
                } finally { runtime.close() }
            } finally { selection.close() }
        } finally { parent.deleteRecursively() }
    }
}
