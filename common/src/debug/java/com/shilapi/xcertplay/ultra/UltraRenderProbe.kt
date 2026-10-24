package com.shilapi.xcertplay.ultra

import android.graphics.Bitmap
import java.io.File
import org.json.JSONObject

/** Debug-only app_process entry point. Creates a PNG; never opens an Activity or display. */
object UltraRenderProbe {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3) { "Expected scene JSON, resource directory and output PNG" }
        val input = File(args[0])
        require(input.length() in 1..1024 * 1024) { "Scene input limit" }
        val root = File(args[1]).canonicalFile
        val report = JSONObject(input.readText())
        require(report.optBoolean("fixture", false) && !report.optBoolean("fullUltraUI", true)) { "Offscreen fixture only" }
        val image = UltraSceneRenderer().render(report.getJSONObject("scene")) { path ->
            val file = File(root, path)
            require(file.canonicalFile.path.startsWith(root.path + File.separator) && file.canonicalFile == file.absoluteFile) { "Texture path refused" }
            require(file.length() in 24..4 * 1024 * 1024) { "PNG size refused" }
            file.readBytes()
        }
        try {
            File(args[2]).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            println("offscreen fixture rendered ${image.width}x${image.height}; fullUltraUI=false")
        } finally { image.recycle() }
    }
}
