// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import android.graphics.Bitmap
import android.os.Looper
import com.shilapi.xcertplay.media.VideoCompositionFrame
import com.shilapi.xcertplay.media.VideoCompositionSmoke
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Original selected center with a simulated peer/lifecycle and generated 1920x720 AVC, never a phone. */
@androidx.annotation.RequiresApi(26)
object UltraOriginalCenterSmoke {
    private const val PANEL = "Center_Display"
    private const val LAYOUT = "9E19BC4E-5251-5821-A0BA-26479A79F722"

    fun run(args: Array<String>, draw: (ByteArray, VideoCompositionFrame, VideoCompositionFrame?, (String) -> ByteArray) -> Bitmap,
            consume: (Bitmap) -> Unit) {
        require(args.size == 4) { "LIBRARY PREPARED_RESOURCES BRIDGE CENTER_ENTRY required" }
        if (Looper.getMainLooper() == null) {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
        }
        // The debug shell supplies the prepared development directory explicitly. The session
        // copy is private; a future Activity installer must supply its app-private cache parent.
        val cache = Files.createTempDirectory(File(args[1]).canonicalFile.parentFile.toPath(), "ultra-smoke-").toFile()
        try {
            UltraPreparedResources.prepare(File(args[1]), cache, UltraReferenceCenterResources.identity).use { resources ->
                resources.openRuntime(File(args[2]).readBytes(), File(args[3]).readBytes(),
                    researchOverride = true, lifetime = UltraRuntimeLifetime.SESSION_OWNED_INTEGRATION,
                    loadLibrary = { System.load(args[0]) }).use { runtime ->
                    val completed = CountDownLatch(1)
                    val failure = AtomicReference<Throwable?>()
                    val pipeline = UltraVideoPipeline(PANEL, "iPhoneVideo", 1920, 720, runtime, { bytes, frame, previous ->
                        try { draw(bytes, frame, previous, resources::readPng) } catch (error: Throwable) { failure.compareAndSet(null, error); throw error }
                    }, { image ->
                        try { consume(image); completed.countDown() }
                        catch (error: Throwable) { failure.compareAndSet(null, error); throw error }
                    }, { failure.compareAndSet(null, IllegalStateException("Original center pipeline failed")); completed.countDown() },
                        sceneInputMode = UltraSceneInputMode.REFERENCE_CENTER_ACCESSORY)
                    try {
                        // Exercise the explicit session lifetime past the research counter without ticking the
                        // protocol, draining its outbox or pretending this is a wall-clock endurance test.
                        val query = BplistCodec.encode(mapOf("action" to "state", "input" to "iPhoneVideo"))
                        repeat(5_000) { check(runtime.request(7, query) is Map<*, *>) }
                        val protocol = pipeline.panel(PANEL)
                        fun decode(messages: List<ByteArray>) = messages.map { checkNotNull(BoundedPlist.decode(it)) }
                        val reset = decode(protocol.poll()).first { it["type"] == 0L }
                        val replies = decode(protocol.receive(BplistCodec.encode(mapOf(
                            "type" to 1L, "ssn" to reset.getValue("ssn"), "psn" to 1L,
                            "asn" to reset.getValue("psn"), "version" to 2L, "id" to "fixture-peer",
                        )))).toMutableList()
                        repeat(8) { replies += decode(protocol.poll()) }
                        check(replies.any { it["type"] == 1L }) { "Original reset-complete missing" }
                        // Host state uses the same mailbox as the optional pipeline. Both host events and the
                        // explicit local UI request are artificial in this debug entry point.
                        pipeline.updateHostVisibility(resumed = true, surfaceReady = true)
                        pipeline.requestAccessoryScene(true)
                        repeat(10) { replies += decode(protocol.poll()) }
                        val requests = replies.filter { it["type"] == 2L }.mapNotNull {
                            (it["data"] as? ByteArray)?.let(BoundedPlist::decode)
                        }
                        check(requests.any { it["type"] == 5L && (it["data"] as? Map<*, *>)?.get("layoutID") == LAYOUT })
                        val acknowledged = replies.mapNotNull { it["psn"] as? Long }.max()
                        val commands = listOf(9L to mapOf("isTransitioning" to true), 3L to mapOf("layoutID" to LAYOUT),
                            6L to mapOf("layoutID" to LAYOUT, "regions" to emptyList<Any>()), 9L to mapOf("isTransitioning" to false))
                        for ((index, command) in commands.withIndex()) {
                            protocol.receive(BplistCodec.encode(mapOf("type" to 2L, "ssn" to reset.getValue("ssn"),
                                "psn" to index + 2L, "asn" to acknowledged,
                                "data" to BplistCodec.encode(mapOf("type" to command.first, "data" to command.second)))))
                            protocol.poll()
                        }
                        VideoCompositionSmoke.feedGeneratedVideo(pipeline.videoSink, 1920, 720)
                        check(completed.await(20, TimeUnit.SECONDS)) { "No original-layout composed frame" }
                        failure.get()?.let { throw it }
                    } finally {
                        pipeline.close()
                        check(pipeline.awaitClosed(15_000)) { "Original center pipeline did not retire" }
                    }
                }
            }
        } finally { check(cache.delete()) { "Original center resource cache did not retire" } }
    }
}
