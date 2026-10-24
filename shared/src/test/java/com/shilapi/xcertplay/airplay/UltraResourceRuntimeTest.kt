package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.media.TimedVideoFrame
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit local reference inputs only; no authentication files, phone or physical display. */
class UltraResourceRuntimeTest {
    private fun input(name: String): File {
        val path = System.getenv(name)
        assumeTrue("Explicit reference input required: $name", path != null)
        return File(path!!).absoluteFile.also { require(it.exists()) }
    }

    private fun withPrepared(block: (UltraPreparedResources, File, File, File) -> Unit) {
        val original = input("DIPLAY_ULTRA_TEST_RESOURCES")
        val bridge = input("DIPLAY_ULTRA_BRIDGE_SOURCE")
        val library = input("DIPLAY_ULTRA_JNI_LIBRARY")
        val parent = Files.createTempDirectory("diplay-ultra-reference-snapshot").toFile()
        try {
            UltraPreparedResources.prepare(original, parent, UltraReferenceCenterResources.identity).use { resources ->
                block(resources, original, bridge, library)
            }
            assertTrue(parent.listFiles().orEmpty().isEmpty())
        } finally { parent.deleteRecursively() }
    }

    @Test fun verifiedSnapshotOwnsOriginalControllerAndImagesUntilItsPanelRetires() = withPrepared { resources, original, bridge, library ->
        val snapshot = resources.directory
        val manifest = checkNotNull(BoundedPlist.decodeResourceManifest(File(original, "research-input.plist").readBytes()))
        val files = manifest["files"] as Map<*, *>
        val png = files.keys.filterIsInstance<String>().first { it.endsWith(".png") }
        val expectedImage = resources.readPng(png)
        assertFalse(snapshot.canonicalFile == original.canonicalFile)
        val runtime = resources.openRuntime(bridge.readBytes(), File(bridge.parentFile, "center_wallpaper_entry.luau").readBytes(),
            researchOverride = true, lifetime = UltraRuntimeLifetime.SESSION_OWNED_INTEGRATION,
            loadLibrary = { System.load(library.path) })
        try {
            resources.close()
            assertTrue(snapshot.isDirectory)
            assertArrayEquals(expectedImage, resources.readPng(png))
            assertThrows(IllegalStateException::class.java) {
                resources.openRuntime(bridge.readBytes(), "return true".toByteArray(), loadLibrary = { error("Must not load") })
            }
            UltraComposedPanelSession<TimedVideoFrame>(runtime, "iPhoneVideo", UltraSceneInputMode.REFERENCE_CENTER_ACCESSORY).use { session ->
                fun decode(messages: List<ByteArray>) = messages.map { checkNotNull(BoundedPlist.decode(it)) }
                val reset = decode(session.poll()).first { it["type"] == 0L }
                val replies = decode(session.receive(BplistCodec.encode(mapOf(
                    "type" to 1L, "ssn" to reset.getValue("ssn"), "psn" to 1L,
                    "asn" to reset.getValue("psn"), "version" to 2L, "id" to "fixture-peer",
                )))).toMutableList()
                repeat(8) { replies += decode(session.poll()) }
                session.updateHostVisibility(resumed = true, surfaceReady = true)
                session.requestAccessoryScene(true)
                repeat(10) { replies += decode(session.poll()) }
                val commands = replies.filter { it["type"] == 2L }.mapNotNull { (it["data"] as? ByteArray)?.let(BoundedPlist::decode) }
                assertTrue(commands.any { it["type"] == 5L && (it["data"] as? Map<*, *>)?.get("layoutID") == "9E19BC4E-5251-5821-A0BA-26479A79F722" })
            }
            assertFalse(snapshot.exists())
            assertThrows(IllegalStateException::class.java) { resources.readPng(png) }
        } finally { runtime.close() }
    }

    @Test fun fatalNativeRequestRemovesItsSnapshotAndCannotAffectTheSelectedSource() = withPrepared { resources, original, bridge, library ->
        val root = resources.directory
        val runtime = resources.openRuntime(bridge.readBytes(), "return true".toByteArray(),
            loadLibrary = { System.load(library.path) })
        try {
            assertTrue(root.isDirectory)
            assertThrows(IllegalStateException::class.java) { runtime.request(2, byteArrayOf(0)) }
            assertFalse(root.exists())
            assertTrue(File(original, "main.lua").isFile)
        } finally { runtime.close() }
    }

    @Test fun failedNativeInitializationCleansTheVerifiedCopy() = withPrepared { resources, original, bridge, library ->
        val root = resources.directory
        assertThrows(IllegalStateException::class.java) {
            resources.openRuntime(bridge.readBytes(), "error('fixture initialization failure')".toByteArray(),
                loadLibrary = { System.load(library.path) })
        }
        assertFalse(root.exists())
        assertTrue(File(original, "main.lua").isFile)
    }
}
