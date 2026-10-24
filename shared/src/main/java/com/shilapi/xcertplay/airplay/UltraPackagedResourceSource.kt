// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import android.content.Context
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.transport.Iap2ThemeAsset
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/**
 * Opaque selection of the app-packaged development reference center. This is not a general production
 * resource provider: runtime creation explicitly enables the incomplete bridge research override.
 * Preparing a selection does not activate a renderer, connect to a phone or establish acceptance.
 */
@RequiresApi(26)
class UltraReferenceResourceSelection internal constructor(
    private val resources: UltraPreparedResources,
    private val bridge: ByteArray,
    private val entrypoint: ByteArray,
) : Closeable {
    val themeBinding: UltraThemeBinding = UltraThemeBinding(
        Iap2ThemeAsset("44C9FA31EC", 985, "19.0"),
        UltraThemeDisplay("Center_Display", 1920, 720, 157),
        composeMainDisplay = true,
    )
    val layoutId: String = "9E19BC4E-5251-5821-A0BA-26479A79F722"

    /** Development only; single-use. The runtime owns resources until native destruction. */
    internal fun openRuntime(loadLibrary: () -> Unit = { System.loadLibrary("diplay_ultra") }): UltraNativeRuntime =
        resources.openRuntime(bridge, entrypoint, researchOverride = true,
            lifetime = UltraRuntimeLifetime.SESSION_OWNED_INTEGRATION, loadLibrary = loadLibrary)

    /** Explicit caller-owned image source required. The pinned base entry remains unchanged. */
    internal fun openRuntimeWithSettings(loadLibrary: () -> Unit = { System.loadLibrary("diplay_ultra") }): UltraNativeRuntime {
        val declaration = "Offscreen.configureVideoInput(\"${UltraLocalSettingsInput.IDENTIFIER}\"," +
            "${UltraLocalSettingsInput.WIDTH},${UltraLocalSettingsInput.HEIGHT},1/30,1)\n"
        return resources.openRuntime(bridge, declaration.toByteArray(Charsets.UTF_8) + entrypoint,
            researchOverride = true, lifetime = UltraRuntimeLifetime.SESSION_OWNED_INTEGRATION,
            loadLibrary = loadLibrary)
    }

    internal fun readPng(name: String): ByteArray = resources.readPng(name)

    override fun close() = resources.close()
}

/** Explicitly packaged app assets only; no network, phone-delivered code or credential discovery. */
@RequiresApi(26)
object UltraPackagedResourceSource {
    private const val PREFIX = "ultra/reference-center/"
    private const val MANIFEST = "research-input.plist"
    private const val MAX_SCRIPT = 4 * 1024 * 1024
    private const val MAX_TOTAL = 128L * 1024 * 1024
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val DESCRIPTOR = mapOf(
        "profile" to "reference-center-wallpaper-v1", "assetId" to "44C9FA31EC",
        "assetVersion" to 985L, "sdkVersion" to "19.0", "panelId" to "Center_Display",
        "layoutId" to "9E19BC4E-5251-5821-A0BA-26479A79F722",
        "width" to 1920L, "height" to 720L, "densityDpi" to 157L,
    )

    fun prepare(context: Context): UltraReferenceResourceSelection? = prepareFromAssets(
        openAsset = { context.assets.open(it) },
        privateParent = { File(context.noBackupFilesDir, "ultra-resource-sessions") },
        expected = UltraReferenceCenterResources.identity,
    )

    internal fun prepare(
        openAsset: (String) -> InputStream,
        privateParent: File,
        expected: UltraResourceIdentity = UltraReferenceCenterResources.identity,
    ): UltraReferenceResourceSelection? = prepareFromAssets(openAsset, { privateParent }, expected)

    private fun prepareFromAssets(
        openAsset: (String) -> InputStream,
        privateParent: () -> File,
        expected: UltraResourceIdentity,
    ): UltraReferenceResourceSelection? {
        // Only the missing selection is optional. A declared but damaged package must fail visibly.
        val descriptorStream = try { openAsset(PREFIX + "selection.plist") }
        catch (_: FileNotFoundException) { return null }
        val descriptor = requireNotNull(BoundedPlist.decode(readBounded(descriptorStream, 64 * 1024))) {
            "Invalid packaged resource selection"
        }
        require(descriptor.keys == DESCRIPTOR.keys + setOf("bridgeSha256", "entrySha256"))
        for ((key, value) in DESCRIPTOR) require(descriptor[key] == value) { "Unsupported packaged resource selection" }
        fun selectedHash(key: String): String {
            val hash = descriptor[key] as? String ?: error("Missing packaged source digest")
            require(SHA256.matches(hash))
            return hash
        }
        val bridgeHash = selectedHash("bridgeSha256")
        val entryHash = selectedHash("entrySha256")
        val manifest = readBounded(openAsset(PREFIX + "resources/" + MANIFEST), 1024 * 1024)
        val files = UltraPreparedResources.selectedFiles(manifest, expected)
        val bridge = readBounded(openAsset(PREFIX + "bridge.luau"), MAX_SCRIPT)
        require(digest(bridge) == bridgeHash) { "Packaged bridge digest mismatch" }
        val entry = readBounded(openAsset(PREFIX + "center_wallpaper_entry.luau"), MAX_SCRIPT)
        require(digest(entry) == entryHash) { "Packaged entrypoint digest mismatch" }

        val parent = privateParent().toPath().toAbsolutePath().normalize()
        if (Files.exists(parent, NOFOLLOW_LINKS)) {
            require(Files.isDirectory(parent, NOFOLLOW_LINKS)) { "Private resource parent is missing or symbolic" }
        } else {
            Files.createDirectories(parent)
        }
        val lease = UltraResourceSessionLease.acquire(parent.toFile())
        val staging = lease.directory.toPath().resolve("import")
        var snapshot: UltraPreparedResources? = null
        try {
            Files.createDirectory(staging)
            var total = 0L
            for ((name, hash) in files) {
                val bytes = readBounded(openAsset(PREFIX + "resources/" + name), MAX_SCRIPT)
                total += bytes.size
                require(total <= MAX_TOTAL) { "Packaged resource aggregate size limit" }
                require(digest(bytes) == hash) { "Packaged resource digest mismatch" }
                val output = staging.resolve(name)
                Files.createDirectories(output.parent)
                Files.write(output, bytes)
            }
            Files.write(staging.resolve(MANIFEST), manifest)
            snapshot = UltraPreparedResources.prepare(staging.toFile(), lease.directory, expected,
                onRetired = lease::close)
            deleteStaging(staging)
            return UltraReferenceResourceSelection(snapshot, bridge, entry)
        } catch (failure: Throwable) {
            try { snapshot?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            try { if (Files.exists(staging, NOFOLLOW_LINKS)) deleteStaging(staging) }
            catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            try { lease.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun readBounded(stream: InputStream, limit: Int): ByteArray = stream.use { input ->
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            require(output.size().toLong() + count <= limit) { "Packaged asset size limit" }
            output.write(chunk, 0, count)
        }
        output.toByteArray()
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun deleteStaging(root: Path) {
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

/** OS leases survive neither process death nor channel close, unlike in-memory owner registries. */
@RequiresApi(26)
internal class UltraResourceSessionLease private constructor(
    val directory: File,
    private val channel: FileChannel,
    private val lock: FileLock,
) : Closeable {
    private var closed = false

    companion object {
        private const val PREFIX = "ultra-session-"
        private const val OWNER = ".owner.lock"
        private val NAMES = Regex("ultra-session-[A-Za-z0-9_-]{1,80}")
        private val monitor = Any()
        private val activeOwners = mutableSetOf<Path>()

        fun acquire(parent: File): UltraResourceSessionLease = registry(parent.toPath()) { anchor ->
            recover(anchor)
            val path = Files.createTempDirectory(anchor, PREFIX)
            var channel: FileChannel? = null
            try {
                channel = openLock(path.resolve(OWNER))
                val lock = channel.lock()
                activeOwners.add(path)
                UltraResourceSessionLease(path.toFile(), channel, lock)
            } catch (failure: Throwable) {
                try { channel?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                try { deleteTree(path) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        private fun recover(parent: Path) {
            Files.newDirectoryStream(parent).use { children ->
                for (child in children) {
                    if (!NAMES.matches(child.fileName.toString()) || !Files.isDirectory(child, NOFOLLOW_LINKS)) continue
                    // POSIX record locks can be released by closing *any* descriptor for the same
                    // inode in this process. Do not even open another channel for our own owners.
                    if (child in activeOwners) continue
                    val owner = child.resolve(OWNER)
                    if (!Files.exists(owner, NOFOLLOW_LINKS)) {
                        // Creation and recovery share the registry lock, so no live creator can be
                        // between directory creation and acquiring its owner lease here.
                        deleteTree(child)
                        continue
                    }
                    if (!Files.isRegularFile(owner, NOFOLLOW_LINKS)) continue
                    openLock(owner).use { channel ->
                        val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                        if (lock != null) try { deleteTree(child) } finally { lock.release() }
                    }
                }
            }
        }

        private fun openLock(path: Path): FileChannel = FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS)

        private fun <T> registry(parent: Path, block: (Path) -> T): T = synchronized(monitor) {
            require(Files.isDirectory(parent, NOFOLLOW_LINKS)) { "Resource lease parent missing or symbolic" }
            val anchor = parent.toRealPath()
            openLock(anchor.resolve(".registry.lock")).use { channel ->
                val lock = channel.lock()
                try { block(anchor) } finally { lock.release() }
            }
        }

        private fun deleteTree(root: Path) {
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

    @Synchronized override fun close() {
        if (closed) return
        registry(directory.parentFile.toPath()) {
            try {
                if (Files.exists(directory.toPath(), NOFOLLOW_LINKS)) deleteTree(directory.toPath())
            } finally {
                closed = true
                activeOwners.remove(directory.toPath())
                try { lock.release() } finally { channel.close() }
            }
        }
    }
}
