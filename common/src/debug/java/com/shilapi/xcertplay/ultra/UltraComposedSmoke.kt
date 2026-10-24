// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.UltraNativeSmoke
import java.io.File

/** Development pipeline: Kotlin/inner protocol → JNI/original scripts → detached scene → Android PNG. */
object UltraComposedSmoke {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 5) { "LIBRARY RESOURCE_DIRECTORY BRIDGE_SOURCE ENTRY_SOURCE OUTPUT_PNG required" }
        val scene = UltraNativeSmoke.runResearch(args.copyOfRange(0, 4))
        val output = File(args[4])
        val sceneFile = File(output.path + ".scene.json")
        sceneFile.writeBytes(scene)
        UltraRenderProbe.main(arrayOf(sceneFile.path, args[1], output.path))
        println("{\"jni\":true,\"androidRasterization\":true,\"simulatedPeer\":true," +
            "\"phoneVideo\":false,\"synchronizedPresentation\":false,\"fullUltraUI\":false}")
    }
}
