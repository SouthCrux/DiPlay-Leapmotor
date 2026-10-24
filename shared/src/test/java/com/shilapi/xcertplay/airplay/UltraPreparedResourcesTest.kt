package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Synthetic source-only packages; these checks require neither JNI nor manufacturer resources. */
class UltraPreparedResourcesTest {
    private class Fixture : Closeable {
        val root = Files.createTempDirectory("ultra-prepared-test").toFile()
        val source = File(root, "source").apply { mkdirs() }
        val cache = File(root, "private").apply { mkdirs() }
        val files = linkedMapOf<String, ByteArray>(
            "main.lua" to "return {}".toByteArray(),
            "r/PackageData.lua" to "return {}".toByteArray(),
            "LayoutController.lua" to "return {}".toByteArray(),
            "Workspace.lua" to "return {}".toByteArray(),
            "Info.plist" to BplistCodec.encode(mapOf(
                "CFBundleIdentifier" to "44C9FA31EC", "AccessoryContentVersion" to 985L,
                "CFBundleVersion" to "985", "MinimumSDKVersion" to "19.0")),
            "r/picture.png" to byteArrayOf(1, 2, 3, 4))
        var manifest: MutableMap<String, Any> = linkedMapOf()
        lateinit var identity: UltraResourceIdentity

        init { writeFiles(); writeManifest() }

        fun writeFiles() {
            files.forEach { (name, bytes) ->
                File(source, name).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
            }
        }

        fun writeManifest(change: (MutableMap<String, Any>) -> Unit = {}) {
            manifest = linkedMapOf(
                "archiveSha256" to "a".repeat(64),
                "sourceModules" to files.keys.count { it.endsWith(".lua") },
                "bridgeVersionRequired" to "1.4.0",
                "bytecodeExtracted" to false, "scriptsExecuted" to false,
                "signatureVerified" to false,
                "files" to files.mapValues { sha256(it.value) })
            change(manifest)
            val bytes = BplistCodec.encode(manifest)
            File(source, "research-input.plist").writeBytes(bytes)
            identity = UltraResourceIdentity("a".repeat(64), sha256(bytes),
                "44C9FA31EC", 985L, "19.0", 4)
        }

        fun prepare() = UltraPreparedResources.prepare(source, cache, identity)

        fun assertRejected() {
            assertThrows(Exception::class.java) { prepare().close() }
            assertEquals("Failed preparation must remove its partial snapshot", emptyList<String>(),
                cache.listFiles()!!.map { it.name })
            assertTrue("Source package must be retained", File(source, "main.lua").exists())
        }

        override fun close() {
            Files.walk(root.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test fun verifiedSnapshotExcludesUnlistedFilesAndSurvivesSourceChanges() {
        Fixture().use { fixture ->
            File(fixture.source, "unlisted.txt").writeText("not selected")
            fixture.prepare().use { prepared ->
                assertNotEquals(fixture.source.canonicalFile, prepared.directory.canonicalFile)
                assertTrue(prepared.directory.canonicalPath.startsWith(fixture.cache.canonicalPath + File.separator))
                assertFalse(File(prepared.directory, "unlisted.txt").exists())
                assertArrayEquals(fixture.files.getValue("r/picture.png"), prepared.readPng("r/picture.png"))
                File(fixture.source, "r/picture.png").writeBytes(byteArrayOf(9))
                File(fixture.source, "main.lua").writeText("changed after preparation")
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), prepared.readPng("r/picture.png"))
                assertEquals("return {}", File(prepared.directory, "main.lua").readText())
                assertThrows(Exception::class.java) { prepared.readPng("main.lua") }
                assertThrows(Exception::class.java) { prepared.readPng("unlisted.png") }
                assertThrows(Exception::class.java) { prepared.readPng("../r/picture.png") }
            }
            assertTrue(fixture.cache.listFiles()!!.isEmpty())
        }
    }

    @Test fun trustedManifestDigestRejectsSelfConsistentResourceReplacement() {
        Fixture().use { fixture ->
            val selected = fixture.identity
            fixture.files["main.lua"] = "return 'replacement'".toByteArray()
            fixture.writeFiles()
            fixture.writeManifest()
            fixture.identity = selected
            fixture.assertRejected()
        }
    }

    @Test fun changedFileCannotPassItsOriginalManifestAndLeavesNoPartialSnapshot() {
        Fixture().use { fixture ->
            File(fixture.source, "Workspace.lua").writeText("tampered")
            fixture.assertRejected()
            assertEquals("tampered", File(fixture.source, "Workspace.lua").readText())
        }
    }

    @Test fun manifestMustDeclareSourceOnlyInputsWithCorrectTypesAndModuleCount() {
        val changes: List<(MutableMap<String, Any>) -> Unit> = listOf(
            { it.remove("files") },
            { it["files"] = listOf("main.lua") },
            { it["sourceModules"] = 3 },
            { it["sourceModules"] = "4" },
            { it["archiveSha256"] = "b".repeat(64) },
            { it["bridgeVersionRequired"] = "9.9.9" },
            { it["bytecodeExtracted"] = true },
            { it["scriptsExecuted"] = true },
            { it.remove("bytecodeExtracted") })
        changes.forEach { change ->
            Fixture().use { fixture -> fixture.writeManifest(change); fixture.assertRejected() }
        }
    }

    @Test fun requiredControllersAndSelectedPackageIdentityMustMatch() {
        for (missing in listOf("main.lua", "r/PackageData.lua", "LayoutController.lua", "Workspace.lua", "Info.plist")) {
            Fixture().use { fixture ->
                fixture.files.remove(missing)
                fixture.writeManifest()
                fixture.assertRejected()
            }
        }
        val replacements = listOf(
            mapOf("CFBundleIdentifier" to "different"),
            mapOf("AccessoryContentVersion" to 986L),
            mapOf("AccessoryContentVersion" to "985"),
            mapOf("CFBundleVersion" to "986"),
            mapOf("MinimumSDKVersion" to "20.0"))
        replacements.forEach { replacement ->
            Fixture().use { fixture ->
                val info = linkedMapOf<String, Any>("CFBundleIdentifier" to "44C9FA31EC",
                    "AccessoryContentVersion" to 985L, "CFBundleVersion" to "985", "MinimumSDKVersion" to "19.0")
                info.putAll(replacement)
                fixture.files["Info.plist"] = BplistCodec.encode(info)
                fixture.writeFiles(); fixture.writeManifest(); fixture.assertRejected()
            }
        }
    }

    @Test fun listedTraversalAmbiguousPathsAndBytecodeAreRejected() {
        for (path in listOf("../outside.lua", "/absolute.lua", "r/../other.lua", "r//other.lua",
            "./other.lua", "r\\other.lua", "r/other.luac", "r/é.lua", "r/" + "x".repeat(201))) {
            Fixture().use { fixture ->
                fixture.writeManifest { manifest ->
                    manifest["files"] = fixture.files.mapValues { sha256(it.value) } + (path to "b".repeat(64))
                }
                fixture.assertRejected()
            }
        }
    }

    @Test fun symlinkedFileOrParentCannotReadOutsideTheSelectedSourceTree() {
        for (parentLink in listOf(false, true)) {
            Fixture().use { fixture ->
                val outside = File(fixture.root, "outside").apply { mkdirs() }
                File(outside, "picture.png").writeBytes(fixture.files.getValue("r/picture.png"))
                File(outside, "PackageData.lua").writeBytes(fixture.files.getValue("r/PackageData.lua"))
                if (parentLink) {
                    File(fixture.source, "r/picture.png").delete()
                    File(fixture.source, "r/PackageData.lua").delete()
                    File(fixture.source, "r").delete()
                    Files.createSymbolicLink(File(fixture.source, "r").toPath(), outside.toPath())
                } else {
                    File(fixture.source, "r/picture.png").delete()
                    Files.createSymbolicLink(File(fixture.source, "r/picture.png").toPath(), File(outside, "picture.png").toPath())
                }
                fixture.assertRejected()
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(outside, "picture.png").readBytes())
            }
        }
    }

    @Test fun oversizedSourceAndMalformedTrustedManifestAreRejected() {
        Fixture().use { fixture ->
            fixture.files["main.lua"] = ByteArray(4 * 1024 * 1024 + 1)
            fixture.writeFiles(); fixture.writeManifest(); fixture.assertRejected()
        }
        Fixture().use { fixture ->
            val malformed = "this is not a plist".toByteArray()
            File(fixture.source, "research-input.plist").writeBytes(malformed)
            fixture.identity = fixture.identity.copy(manifestSha256 = sha256(malformed))
            fixture.assertRejected()
        }
        Fixture().use { fixture ->
            val oversized = ByteArray(1024 * 1024 + 1)
            File(fixture.source, "research-input.plist").writeBytes(oversized)
            fixture.identity = fixture.identity.copy(manifestSha256 = sha256(oversized))
            fixture.assertRejected()
        }
        Fixture().use { fixture ->
            fixture.writeManifest { manifest ->
                manifest["files"] = (0..4096).associate { "r/image$it.png" to "b".repeat(64) }
            }
            fixture.assertRejected()
        }
    }

    @Test fun pngDigestIsCheckedAgainBeforeReturningSnapshotBytes() {
        Fixture().use { fixture ->
            fixture.prepare().use { prepared ->
                val image = File(prepared.directory, "r/picture.png")
                assertTrue(image.setWritable(true))
                image.writeBytes(byteArrayOf(8, 8, 8, 8))
                assertThrows(IllegalArgumentException::class.java) { prepared.readPng("r/picture.png") }
            }
        }
    }

    @Test fun nativeLibraryLoadFailureRetiresPreparedResourcesWithoutNativeCalls() {
        Fixture().use { fixture ->
            val prepared = fixture.prepare()
            val selectedFailure = IllegalStateException("synthetic library failure")
            val thrown = assertThrows(IllegalStateException::class.java) {
                prepared.openRuntime("bridge".toByteArray(), "entry".toByteArray(),
                    loadLibrary = { throw selectedFailure })
            }
            assertSame(selectedFailure, thrown)
            assertFalse(prepared.directory.exists())
            prepared.close()
            assertThrows(IllegalStateException::class.java) { prepared.readPng("r/picture.png") }
        }
    }

    @Test fun closeIsIdempotentAndRetiresTheSnapshot() {
        Fixture().use { fixture ->
            val prepared = fixture.prepare()
            val directory = prepared.directory
            prepared.close(); prepared.close()
            assertFalse(directory.exists())
            assertThrows(IllegalStateException::class.java) { prepared.readPng("r/picture.png") }
        }
    }

    companion object {
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
