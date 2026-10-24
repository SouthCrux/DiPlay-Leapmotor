// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Digests are selected out of band; package metadata alone is never a trust anchor. */
internal data class UltraResourceIdentity(
    val archiveSha256: String,
    val manifestSha256: String,
    val assetId: String,
    val assetVersion: Long,
    val sdkVersion: String,
    val sourceModules: Int,
    val bridgeVersionRequired: String = "1.4.0",
)

internal object UltraReferenceCenterResources {
    val identity = UltraResourceIdentity(
        archiveSha256 = "ddf1317ef051f8ac251c9d892b5ea0651a75e14dd59e0f5ac2ff13b37729d504",
        manifestSha256 = "a76a3daf759b480e7c165d29fad162a79efaaf3f795ec55baf2cc3d7b7c49796",
        assetId = "44C9FA31EC", assetVersion = 985, sdkVersion = "19.0", sourceModules = 193,
    )
}

/**
 * A private, digest-checked snapshot of explicitly selected source/PNG resources. This checks the
 * selected research input, not an Apple signature or acceptance by an iPhone. No unlisted input is
 * opened. A snapshot is single-use and remains on disk until its native runtime has been destroyed.
 */
@RequiresApi(26)
internal class UltraPreparedResources private constructor(
    val directory: File,
    private val files: Map<String, String>,
    private val onRetired: () -> Unit,
) : Closeable {
    private val monitor = Any()
    private var claimed = false
    private var active = false
    private var retiring = false
    private var removed = false

    companion object {
        private const val MANIFEST = "research-input.plist"
        private const val MAX_MANIFEST = 1024 * 1024
        private const val MAX_FILE = 4 * 1024 * 1024
        private const val MAX_TOTAL = 128L * 1024 * 1024
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val SAFE_PATH = Regex("[A-Za-z0-9_+./-]+")
        private val REQUIRED = setOf("main.lua", "r/PackageData.lua", "LayoutController.lua", "Workspace.lua", "Info.plist")

        internal fun selectedFiles(manifestBytes: ByteArray, expected: UltraResourceIdentity): Map<String, String> {
            require(SHA256.matches(expected.archiveSha256) && SHA256.matches(expected.manifestSha256))
            require(expected.sourceModules in 1..4096)
            require(manifestBytes.size <= MAX_MANIFEST)
            require(digest(manifestBytes) == expected.manifestSha256) { "Resource manifest digest mismatch" }
            val manifest = requireNotNull(BoundedPlist.decodeResourceManifest(manifestBytes)) { "Invalid resource manifest" }
            require(manifest["archiveSha256"] == expected.archiveSha256)
            require(manifest["sourceModules"] == expected.sourceModules.toLong())
            require(manifest["bridgeVersionRequired"] == expected.bridgeVersionRequired)
            for (flag in listOf("bytecodeExtracted", "scriptsExecuted", "signatureVerified")) {
                require(manifest[flag] == false) { "Unexpected resource preparation flag" }
            }
            val entries = manifest["files"] as? Map<*, *> ?: error("Resource file manifest missing")
            require(entries.size in 1..4096)
            val files = linkedMapOf<String, String>()
            for ((key, value) in entries) {
                val name = key as? String ?: error("Invalid resource name")
                validateName(name)
                val hash = value as? String ?: error("Invalid resource digest")
                require(SHA256.matches(hash))
                files[name] = hash
            }
            require(files.keys.containsAll(REQUIRED)) { "Required resource source missing" }
            require(files.keys.count { it.endsWith(".lua") } == expected.sourceModules)
            return files.toMap()
        }

        fun prepare(source: File, privateParent: File, expected: UltraResourceIdentity,
                    onRetired: () -> Unit = {}): UltraPreparedResources {
            val root = directoryAnchor(source)
            val parent = directoryAnchor(privateParent)
            require(!parent.startsWith(root)) { "Resource destination must be outside the source" }
            val manifestBytes = readBounded(root, MANIFEST, MAX_MANIFEST)
            val files = selectedFiles(manifestBytes, expected)

            val target = Files.createTempDirectory(parent, "ultra-resources-")
            try {
                var total = 0L
                for ((name, hash) in files) {
                    val bytes = readBounded(root, name, MAX_FILE)
                    total += bytes.size
                    require(total <= MAX_TOTAL) { "Resource aggregate size limit" }
                    require(digest(bytes) == hash) { "Resource content digest mismatch" }
                    val output = target.resolve(name)
                    Files.createDirectories(output.parent)
                    Files.write(output, bytes)
                    check(output.toFile().setReadOnly()) { "Cannot protect resource snapshot" }
                }
                val info = requireNotNull(BoundedPlist.decode(readBounded(target, "Info.plist", MAX_FILE))) {
                    "Invalid resource package metadata"
                }
                require(info["CFBundleIdentifier"] == expected.assetId)
                require(info["AccessoryContentVersion"] == expected.assetVersion)
                require(info["CFBundleVersion"] == expected.assetVersion.toString())
                require(info["MinimumSDKVersion"] == expected.sdkVersion)
                return UltraPreparedResources(target.toFile(), files.toMap(), onRetired)
            } catch (failure: Throwable) {
                try { deleteOwnedDirectory(target) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        private fun validateName(name: String) {
            require(name.length in 1..200 && SAFE_PATH.matches(name)) { "Unsafe resource path" }
            require(name.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
            require(name.endsWith(".lua") || name.endsWith(".png") || name.endsWith(".plist"))
            require(name.split('/').none { it.startsWith("research-input.") })
        }

        private fun directoryAnchor(file: File): Path {
            val path = file.toPath().toAbsolutePath().normalize()
            require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "Resource directory missing or symbolic" }
            // Canonicalize system aliases such as /var -> /private/var only after rejecting a
            // symbolic final directory supplied by the caller.
            return path.toRealPath()
        }

        private fun checkedFile(root: Path, name: String): Path {
            require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Resource root missing or symbolic" }
            var path = root
            val parts = name.split('/')
            for ((index, part) in parts.withIndex()) {
                path = path.resolve(part)
                require(if (index == parts.lastIndex) Files.isRegularFile(path, NOFOLLOW_LINKS)
                    else Files.isDirectory(path, NOFOLLOW_LINKS)) { "Resource path missing or symbolic" }
            }
            return path
        }

        private fun readBounded(root: Path, name: String, limit: Int): ByteArray {
            val path = checkedFile(root, name)
            require(Files.size(path) <= limit) { "Resource file size limit" }
            val output = ByteArrayOutputStream()
            Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    require(output.size().toLong() + count <= limit) { "Resource file size limit" }
                    output.write(chunk, 0, count)
                }
            }
            checkedFile(root, name)
            return output.toByteArray()
        }

        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

        private fun deleteOwnedDirectory(root: Path) {
            // No FOLLOW_LINKS: even if an owned entry is replaced with a symlink, cleanup never
            // follows it outside the fresh directory allocated by this loader.
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }

    fun readPng(name: String): ByteArray = synchronized(monitor) {
        check(!removed && (!retiring || active)) { "Resource snapshot closed" }
        validateName(name)
        require(name.endsWith(".png"))
        val expected = requireNotNull(files[name]) { "PNG is not in the selected manifest" }
        readBounded(directory.toPath(), name, MAX_FILE).also {
            require(digest(it) == expected) { "Resource PNG digest mismatch" }
        }
    }

    fun openRuntime(
        bridge: ByteArray,
        entrypoint: ByteArray,
        researchOverride: Boolean = false,
        lifetime: UltraRuntimeLifetime = UltraRuntimeLifetime.BOUNDED_RESEARCH,
        loadLibrary: () -> Unit = { System.loadLibrary("diplay_ultra") },
    ): UltraNativeRuntime {
        synchronized(monitor) {
            check(!claimed && !retiring && !removed) { "Resource snapshot already claimed or closed" }
            claimed = true
            active = true
            retiring = true
        }
        return try {
            UltraNativeRuntime.open(directory, bridge, entrypoint, researchOverride, lifetime,
                onClosed = { runtimeClosed() }, loadLibrary = loadLibrary)
        } catch (failure: Throwable) {
            try { runtimeClosed() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun runtimeClosed() = synchronized(monitor) {
        active = false
        removeIfUnused()
    }

    override fun close() = synchronized(monitor) {
        retiring = true
        removeIfUnused()
    }

    private fun removeIfUnused() {
        if (!active && !removed) {
            deleteOwnedDirectory(directory.toPath())
            removed = true
            onRetired()
        }
    }
}
