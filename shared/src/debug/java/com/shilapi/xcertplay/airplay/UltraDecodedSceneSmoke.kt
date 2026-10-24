// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import android.graphics.Bitmap
import android.os.Looper
import com.shilapi.xcertplay.media.VideoCompositionFrame
import com.shilapi.xcertplay.media.VideoCompositionSmoke
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Debug pipeline with real encoded/decoded fixture video; no phone or physical presentation. */
object UltraDecodedSceneSmoke {
    fun run(args: Array<String>, draw: (ByteArray, VideoCompositionFrame, VideoCompositionFrame?) -> Bitmap,
            consume: (Bitmap) -> Unit) {
        require(args.size == 3) { "LIBRARY RESOURCE_DIRECTORY BRIDGE_SOURCE required" }
        if (Looper.getMainLooper() == null) {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
        }
        val entry = """
            Offscreen.configureOutput("fixture",64,48)
            Offscreen.configureVideoInput("phone-video",64,48,1/30)
            local root=CP.DPLayer.create();root.frame=CP.DPRect(0,0,64,48)
            root.backgroundColor=CP.DPColor(0,0,0,1)
            local video=CP.DPVideoLayer.create("phone-video");video.frame=CP.DPRect(0,0,64,48)
            root:addSublayer(video);video:subscribe()
            local marker=CP.DPLayer.create();marker.frame=CP.DPRect(0,0,8,8)
            marker.backgroundColor=CP.DPColor(1,1,0,1);root:addSublayer(marker)
            CP.setRootLayer("fixture",root)
            return "initialized"
        """.trimIndent().toByteArray()
        val runtime = UltraNativeRuntime.open(File(args[1]), File(args[2]).readBytes(), entry,
            loadLibrary = { System.load(args[0]) })
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val pipeline = UltraVideoPipeline("fixture", "phone-video", 64, 48, runtime, draw, { bitmap ->
            try {
                consume(bitmap)
                completed.countDown()
            } catch (error: Throwable) { failure.compareAndSet(null, error); throw error }
        }, {
            failure.compareAndSet(null, IllegalStateException("Composition worker failed")); completed.countDown()
        })
        try {
            VideoCompositionSmoke.feedGeneratedVideo(pipeline.videoSink)
            check(completed.await(15, TimeUnit.SECONDS)) { "No composed decoded frame" }
            failure.get()?.let { throw it }
        } finally {
            pipeline.close()
            check(pipeline.awaitClosed(15_000)) { "Video pipeline did not retire" }
        }
    }
}
