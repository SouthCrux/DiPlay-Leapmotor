// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.ultra

import android.graphics.Bitmap
import android.graphics.Color
import com.shilapi.xcertplay.airplay.UltraDecodedSceneSmoke
import com.shilapi.xcertplay.media.UltraSurfaceOutputSmoke
import java.io.File
import org.json.JSONObject

/** Debug-only real decode → latched pixels/origin → JNI frame transaction → local-layer composition. */
object UltraDecodedCompositionSmoke {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 4) { "LIBRARY RESOURCE_DIRECTORY BRIDGE_SOURCE OUTPUT_PNG required" }
        val checkedOutput = java.util.concurrent.atomic.AtomicBoolean()
        UltraDecodedSceneSmoke.run(args.copyOfRange(0, 3), { bytes, offered, current ->
            val report = JSONObject(String(bytes, Charsets.UTF_8))
            check(report.getBoolean("fixture") && !report.getBoolean("fullUltraUI"))
            UltraSceneRenderer().render(report.getJSONObject("scene"), { error("No PNG fixture input expected") },
                UltraVideoFrameSource { identifier, token, pts ->
                    check(identifier == "phone-video")
                    val frame = when (token) {
                        offered.token -> offered
                        current?.token -> checkNotNull(current)
                        else -> error("Scene selected a stale frame")
                    }
                    check(kotlin.math.abs(pts - frame.senderNanos / 1_000_000_000.0) <= 0.000001)
                    frame.bitmap
                })
        }, { bitmap ->
            check(bitmap.getPixel(2, 2) == Color.YELLOW) { "Local layer missing" }
            val red = bitmap.getPixel(16, 12); val blue = bitmap.getPixel(16, 36)
            check(Color.red(red) > 180 && Color.blue(red) < 100) { "Decoded red video missing" }
            check(Color.blue(blue) > 180 && Color.red(blue) < 100) { "Decoded blue video missing" }
            if (checkedOutput.compareAndSet(false, true)) UltraSurfaceOutputSmoke.verify(bitmap)
            File(args[3]).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        })
        println("{\"realAvcDecode\":true,\"latchedPixels\":true,\"nativeFrameTransaction\":true," +
            "\"videoAndLocalLayers\":true,\"originTimeMatched\":true,\"fixtureVideo\":true," +
            "\"pipelineRetired\":true,\"offscreenCanvasSubmission\":true,\"surfaceReplacement\":true," +
            "\"phoneConnected\":false,\"physicalPresentation\":false,\"fullUltraUI\":false}")
    }
}
