// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.File

/** JNI ABI for the optional source-built Luau library. No implicit native load or resource discovery. */
internal object UltraNativeBindings {
    @JvmStatic external fun create(root: ByteArray, bridge: ByteArray, entrypoint: ByteArray, researchOverride: Boolean): Long
    @JvmStatic external fun createSessionOwned(root: ByteArray, bridge: ByteArray, entrypoint: ByteArray, researchOverride: Boolean): Long
    @JvmStatic external fun step(handle: Long, opcode: Int, payload: ByteArray): ByteArray
    @JvmStatic external fun destroy(handle: Long)
}

/** Both policies retain VM/memory/operation limits. Session-owned callers must close on disconnect. */
internal enum class UltraRuntimeLifetime { BOUNDED_RESEARCH, SESSION_OWNED_INTEGRATION }

/**
 * Persistent in-process VM. All calls belong on a worker thread, never an Activity/UI callback.
 * The default retains the research 4,096-step limit. Explicit session-owned integration removes only
 * that lifetime counter; the incomplete bridge restriction and per-operation/VM limits remain.
 * Loading it does not advertise a renderer, choose a layout or bind any phone/video/vehicle service.
 */
internal class UltraNativeRuntime private constructor(private var handle: Long, private val onClosed: () -> Unit) : Closeable {
    companion object {
        private const val MAX_BYTES = 1024 * 1024

        fun open(root: File, bridge: ByteArray, entrypoint: ByteArray,
                 researchOverride: Boolean = false,
                 lifetime: UltraRuntimeLifetime = UltraRuntimeLifetime.BOUNDED_RESEARCH,
                 onClosed: () -> Unit = {},
                 loadLibrary: () -> Unit = { System.loadLibrary("diplay_ultra") }): UltraNativeRuntime {
            require(root.isAbsolute && root.isDirectory && bridge.size <= 4 * MAX_BYTES && entrypoint.size <= 4 * MAX_BYTES)
            loadLibrary()
            val path = root.absolutePath.toByteArray(Charsets.UTF_8)
            val handle = when (lifetime) {
                UltraRuntimeLifetime.BOUNDED_RESEARCH -> UltraNativeBindings.create(path, bridge, entrypoint, researchOverride)
                UltraRuntimeLifetime.SESSION_OWNED_INTEGRATION -> UltraNativeBindings.createSessionOwned(path, bridge, entrypoint, researchOverride)
            }
            check(handle != 0L) { "Ultra VM initialization failed" }
            return UltraNativeRuntime(handle, onClosed)
        }
    }

    @Synchronized fun request(opcode: Int, payload: ByteArray = byteArrayOf()): Any? {
        check(handle != 0L) { "Ultra VM closed" }
        require(opcode in 1..9 && payload.size <= MAX_BYTES)
        try {
            val bytes = UltraNativeBindings.step(handle, opcode, payload)
            val response = BoundedPlist.decode(bytes)
            check(response?.get("ok") == true) { "Ultra VM response refused" }
            return response["data"]
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    /** Prevent protocol polling from interleaving with offer/tick/snapshot/commit of one frame. */
    @Synchronized fun <T> exclusive(block: () -> T): T {
        check(handle != 0L) { "Ultra VM closed" }
        return block()
    }

    @Synchronized override fun close() {
        val current = handle
        handle = 0
        if (current != 0L) try { UltraNativeBindings.destroy(current) } finally { onClosed() }
    }
}

/** Original scripts own all reset, sequence and ACK state; Kotlin only carries inner binary plists. */
internal class UltraScriptPanelSession(private val runtime: UltraNativeRuntime) : UiSyncPanelSession {
    override fun receive(bytes: ByteArray): List<ByteArray> {
        require(BoundedPlist.decode(bytes) != null) { "Invalid inner UI Sync message" }
        runtime.request(1, bytes)
        return poll()
    }

    override fun poll(): List<ByteArray> {
        runtime.request(2, byteArrayOf(1))
        val result = runtime.request(6) as? List<*> ?: error("Ultra outbox missing")
        require(result.size <= 64)
        var total = 0
        return result.map {
            val bytes = it as? ByteArray ?: error("Ultra outbox is not binary")
            total += bytes.size
            require(total <= Opack.MAX_BYTES && BoundedPlist.decode(bytes) != null)
            bytes
        }
    }

    override fun close() = runtime.close()
}
