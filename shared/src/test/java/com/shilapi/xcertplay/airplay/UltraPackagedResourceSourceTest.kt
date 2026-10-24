package com.shilapi.xcertplay.airplay

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class UltraPackagedResourceSourceTest {
    private class Fixture : Closeable {
        val parent = Files.createTempDirectory("ultra-packaged-test").toFile()
        val cache = File(parent, "private")
        val assets = linkedMapOf<String, ByteArray>()
        val resources = linkedMapOf(
            "main.lua" to "return {}".toByteArray(),
            "r/PackageData.lua" to "return {}".toByteArray(),
            "LayoutController.lua" to "return {}".toByteArray(),
            "Workspace.lua" to "return {}".toByteArray(),
            "r/picture.png" to byteArrayOf(1, 2, 3),
            "Info.plist" to BplistCodec.encode(mapOf("CFBundleIdentifier" to "44C9FA31EC",
                "AccessoryContentVersion" to 985L, "CFBundleVersion" to "985", "MinimumSDKVersion" to "19.0")))
        val descriptor = linkedMapOf<String, Any>("profile" to "reference-center-wallpaper-v1",
            "assetId" to "44C9FA31EC", "assetVersion" to 985L, "sdkVersion" to "19.0",
            "panelId" to "Center_Display", "layoutId" to "9E19BC4E-5251-5821-A0BA-26479A79F722",
            "width" to 1920L, "height" to 720L, "densityDpi" to 157L)
        val opened = mutableListOf<String>()
        lateinit var identity: UltraResourceIdentity

        init {
            assets[PREFIX + "bridge.luau"] = "fixture bridge".toByteArray()
            assets[PREFIX + "center_wallpaper_entry.luau"] = "return true".toByteArray()
            descriptor["bridgeSha256"] = hash(assets.getValue(PREFIX + "bridge.luau"))
            descriptor["entrySha256"] = hash(assets.getValue(PREFIX + "center_wallpaper_entry.luau"))
            rebuildManifest()
            writeDescriptor()
        }

        fun rebuildManifest(extraFiles: Map<String, String> = emptyMap()) {
            val manifest = BplistCodec.encode(mapOf("archiveSha256" to "a".repeat(64),
                "sourceModules" to 4, "bridgeVersionRequired" to "1.4.0", "bytecodeExtracted" to false,
                "scriptsExecuted" to false, "signatureVerified" to false,
                "files" to (resources.mapValues { hash(it.value) } + extraFiles)))
            resources.forEach { (name, bytes) -> assets[PREFIX + "resources/" + name] = bytes }
            assets[PREFIX + "resources/research-input.plist"] = manifest
            identity = UltraResourceIdentity("a".repeat(64), hash(manifest), "44C9FA31EC", 985L, "19.0", 4)
        }

        fun writeDescriptor() { assets[PREFIX + "selection.plist"] = BplistCodec.encode(descriptor) }
        fun open(path: String): InputStream {
            opened += path
            return ByteArrayInputStream(assets[path] ?: throw FileNotFoundException(path))
        }
        fun prepare() = UltraPackagedResourceSource.prepare(::open, cache, identity)
        fun assertRejected() {
            assertThrows(Exception::class.java) { prepare()?.close() }
            assertTrue(cache.listFiles().orEmpty().none { it.name != ".registry.lock" })
        }
        override fun close() { parent.deleteRecursively() }
    }

    @Test fun absentSelectionDoesNotCreateAnAppDirectoryOrInspectOtherAssets() {
        Fixture().use { f ->
            f.assets.remove(PREFIX + "selection.plist")
            assertNull(f.prepare())
            assertFalse(f.cache.exists())
            assertEquals(listOf(PREFIX + "selection.plist"), f.opened)
        }
    }

    @Test fun selectionIsCentralOnlyAndImportStagingIsRemovedBeforeReturning() {
        Fixture().use { f ->
            f.assets["unrelated-private-data"] = byteArrayOf(99)
            val selection = checkNotNull(f.prepare())
            try {
                assertEquals("44C9FA31EC", selection.themeBinding.asset.identifier)
                assertNull(selection.themeBinding.cluster)
                assertTrue(selection.themeBinding.composeMainDisplay)
                assertEquals("Center_Display", selection.themeBinding.main.id)
                assertEquals(1920, selection.themeBinding.main.width)
                assertEquals(720, selection.themeBinding.main.height)
                assertEquals("9E19BC4E-5251-5821-A0BA-26479A79F722", selection.layoutId)
                assertArrayEquals(byteArrayOf(1, 2, 3), selection.readPng("r/picture.png"))
                val sessions = f.cache.listFiles()!!.filter { it.name != ".registry.lock" }
                assertEquals(1, sessions.size)
                assertTrue(sessions.single().name.startsWith("ultra-session-"))
                assertFalse(File(sessions.single(), "import").exists())
                assertFalse("unrelated-private-data" in f.opened)
                f.assets[PREFIX + "resources/r/picture.png"] = byteArrayOf(8)
                assertArrayEquals(byteArrayOf(1, 2, 3), selection.readPng("r/picture.png"))
            } finally { selection.close() }
            assertTrue(f.cache.listFiles()!!.none { it.name != ".registry.lock" })
        }
    }

    @Test fun profileGeometryAndLayoutCannotBeChangedByTheDescriptor() {
        for ((key, value) in listOf("profile" to "unknown", "panelId" to "Cluster_Display",
            "width" to 2560L, "height" to 1176L, "assetVersion" to 986L,
            "layoutId" to "different", "unexpected" to true)) {
            Fixture().use { f ->
                f.descriptor[key] = value
                f.writeDescriptor()
                f.assertRejected()
                assertFalse(f.cache.exists())
            }
        }
    }

    @Test fun aPresentButIncompletePackageMustFailInsteadOfBecomingAnAbsentSelection() {
        for (name in listOf("bridge.luau", "center_wallpaper_entry.luau", "resources/main.lua")) {
            Fixture().use { f ->
                f.assets.remove(PREFIX + name)
                assertThrows(FileNotFoundException::class.java) { f.prepare() }
                assertTrue(f.cache.listFiles().orEmpty().none { it.name != ".registry.lock" })
            }
        }
        Fixture().use { f ->
            val error = IOException("fixture asset I/O failure")
            assertSame(error, assertThrows(IOException::class.java) {
                UltraPackagedResourceSource.prepare({ throw error }, f.cache, f.identity)
            })
            assertFalse(f.cache.exists())
        }
    }

    @Test fun modifiedScriptsManifestOrContentAreRejectedWithoutLeakingPrivateCopies() {
        for (name in listOf("bridge.luau", "center_wallpaper_entry.luau", "resources/research-input.plist", "resources/r/picture.png")) {
            Fixture().use { f ->
                f.assets[PREFIX + name] = "changed".toByteArray()
                f.assertRejected()
            }
        }
    }

    @Test fun manifestPathsAreValidatedBeforeAnyResourceAssetIsOpened() {
        for (path in listOf("../outside.lua", "/absolute.lua", "r//ambiguous.lua", "payload.luac")) {
            Fixture().use { f ->
                f.rebuildManifest(mapOf(path to "b".repeat(64)))
                f.assertRejected()
                assertFalse(f.opened.any { it.endsWith(path) })
                assertFalse(f.cache.exists())
            }
        }
    }

    @Test fun oversizedDescriptorAndSelectedScriptsCannotExpandWithoutBound() {
        for (name in listOf("selection.plist", "bridge.luau", "center_wallpaper_entry.luau")) {
            Fixture().use { f ->
                f.assets[PREFIX + name] = ByteArray(if (name == "selection.plist") 64 * 1024 + 1 else 4 * 1024 * 1024 + 1)
                f.assertRejected()
                assertFalse(f.cache.exists())
            }
        }
    }

    companion object {
        private const val PREFIX = "ultra/reference-center/"
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
