// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.ultra

import android.graphics.Bitmap
import android.graphics.Color
import com.shilapi.xcertplay.airplay.UltraOriginalCenterSmoke
import java.io.File

/** Original controller + original resource geometry; simulated peer/lifecycle and generated video. */
@androidx.annotation.RequiresApi(26)
object UltraOriginalCenterCompositionSmoke {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 5) { "LIBRARY PREPARED_RESOURCES BRIDGE CENTER_ENTRY OUTPUT_PNG required" }
        UltraOriginalCenterSmoke.run(args.copyOfRange(0, 4), UltraReferenceCenterDraw::draw, { image ->
            check(image.width == 1920 && image.height == 720)
            val red = image.getPixel(480, 180); val green = image.getPixel(1440, 180)
            val blue = image.getPixel(480, 540); val white = image.getPixel(1440, 540)
            check(Color.red(red) > 180 && Color.green(red) < 100 && Color.blue(red) < 100)
            check(Color.green(green) > 180 && Color.red(green) < 100 && Color.blue(green) < 100)
            check(Color.blue(blue) > 180 && Color.red(blue) < 100 && Color.green(blue) < 100)
            check(Color.red(white) > 180 && Color.green(white) > 180 && Color.blue(white) > 180)
            File(args[4]).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        })
        println("{\"originalLayoutController\":true,\"selectedWallpaperRequested\":true," +
            "\"layoutTransitionCompleted\":true,\"decodedPixelsVerified\":true,\"width\":1920,\"height\":720," +
            "\"simulatedPeer\":true,\"simulatedLifecycle\":true,\"generatedVideo\":true," +
            "\"sessionLifetimeBeyond4096\":true,\"pipelineRetired\":true," +
            "\"phoneConnected\":false,\"physicalPresentation\":false,\"fullUltraUI\":false}")
    }
}
