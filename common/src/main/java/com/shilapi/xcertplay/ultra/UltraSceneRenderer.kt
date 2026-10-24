package com.shilapi.xcertplay.ultra

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.SweepGradient
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import kotlin.math.roundToInt
import kotlin.math.atan2
import kotlin.math.hypot

/** Exact frame selection by native input identity, opaque token and source-clock PTS. */
fun interface UltraVideoFrameSource {
    /** Borrow a latched frame; its owner must keep it alive until render returns. */
    fun bitmap(identifier: String, token: Long, pts: Double): Bitmap
}

/** Bounded CPU layer/image/video compositor. Not a complete CP bridge or live Ultra renderer. */
class UltraSceneRenderer(private val textureCacheBytes: Int = 0) : Closeable {
    private val textures = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    private var retainedBytes = 0L
    private var closed = false
    init { require(textureCacheBytes in 0..32 * 1024 * 1024) }

    internal val retainedTextureBytes: Long get() = synchronized(this) { retainedBytes }

    @Synchronized override fun close() {
        closed = true
        textures.values.forEach(Bitmap::recycle)
        textures.clear(); retainedBytes = 0
    }

    // Only called after all drawing has finished, so eviction never recycles a frame's borrowed image.
    private fun retain(name: String, bitmap: Bitmap): Boolean {
        val bytes = bitmap.allocationByteCount.toLong()
        if (bytes > textureCacheBytes || textureCacheBytes == 0) return false
        while (textures.size >= 64 || retainedBytes + bytes > textureCacheBytes) {
            val oldest = textures.entries.iterator().next()
            retainedBytes -= oldest.value.allocationByteCount
            oldest.value.recycle(); textures.remove(oldest.key)
        }
        textures.put(name, bitmap)?.let { retainedBytes -= it.allocationByteCount; it.recycle() }
        retainedBytes += bytes
        return true
    }
    fun render(scene: JSONObject, loadPng: (String) -> ByteArray): Bitmap = draw(scene, loadPng, null)

    fun render(scene: JSONObject, loadPng: (String) -> ByteArray, videoFrames: UltraVideoFrameSource): Bitmap =
        draw(scene, loadPng, videoFrames)

    @Synchronized private fun draw(scene: JSONObject, loadPng: (String) -> ByteArray, videoFrames: UltraVideoFrameSource?): Bitmap {
        check(!closed) { "Scene renderer closed" }
        val rootBounds = bounds(scene)
        val width = rootBounds.width()
        val height = rootBounds.height()
        require(width % 1f == 0f && height % 1f == 0f && width in 1f..4096f && height in 1f..4096f &&
            width.toLong() * height.toLong() <= 8_000_000) { "Output dimensions refused" }
        val output = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val images = mutableMapOf<String, Bitmap>()
        val decoded = mutableMapOf<String, Bitmap>()
        var completed = false
        var decodedPixels = 0L
        var nodes = 0
        val deadline = System.nanoTime() + 10_000_000_000L
        fun texture(info: JSONObject): Bitmap {
            val name = info.getString("path")
            require(name.matches(Regex("[A-Za-z0-9_./-]{1,200}")) && name.endsWith(".png") &&
                name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Unsafe texture path" }
            val expectedWidth = number(info, "width")
            val expectedHeight = number(info, "height")
            images[name]?.let {
                require(it.width.toFloat() == expectedWidth && it.height.toFloat() == expectedHeight) { "Inconsistent texture dimensions" }
                return it
            }
            require(images.size < 64) { "Texture count limit" }
            require(expectedWidth % 1f == 0f && expectedHeight % 1f == 0f &&
                expectedWidth in 1f..8192f && expectedHeight in 1f..8192f &&
                expectedWidth.toLong() * expectedHeight.toLong() <= 16_000_000) { "Texture dimensions refused" }
            decodedPixels += expectedWidth.toLong() * expectedHeight.toLong()
            require(decodedPixels <= 32_000_000) { "Decoded texture limit" }
            textures[name]?.let {
                require(it.width.toFloat() == expectedWidth && it.height.toFloat() == expectedHeight) { "Inconsistent texture dimensions" }
                images[name] = it
                return it
            }
            val data = loadPng(name)
            require(data.size in 24..4 * 1024 * 1024 &&
                data.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) { "PNG input refused" }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
            require(options.outMimeType == "image/png" && options.outWidth.toFloat() == expectedWidth &&
                options.outHeight.toFloat() == expectedHeight && options.outWidth in 1..8192 && options.outHeight in 1..8192 &&
                options.outWidth.toLong() * options.outHeight <= 16_000_000) { "Texture dimensions refused" }
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: throw IllegalArgumentException("PNG decode failed")
            images[name] = bitmap
            decoded[name] = bitmap
            return bitmap
        }
        fun visit(node: JSONObject, inheritedOpacity: Float, depth: Int) {
            require(++nodes <= 4096 && depth <= 64 && System.nanoTime() < deadline) { "Scene traversal limit" }
            val hidden = node.get("hidden")
            val mask = node.get("maskToBounds")
            require(hidden is Boolean && mask is Boolean) { "Invalid scene flags" }
            if (hidden) return
            val ownOpacity = number(node, "opacity")
            require(ownOpacity in 0f..1f) { "Invalid opacity" }
            val opacity = inheritedOpacity * ownOpacity
            val rect = bounds(node)
            val position = node.getJSONObject("position")
            val anchor = node.getJSONObject("anchorPoint")
            val t = node.getJSONObject("transform")
            val matrix = Matrix().apply { setValues(floatArrayOf(
                number(t, "a"), number(t, "c"), number(t, "tx"),
                number(t, "b"), number(t, "d"), number(t, "ty"), 0f, 0f, 1f,
            )) }
            val save = canvas.save()
            try {
                canvas.translate(number(position, "x"), number(position, "y"))
                canvas.concat(matrix)
                canvas.translate(-number(anchor, "x") * rect.width() - rect.left,
                    -number(anchor, "y") * rect.height() - rect.top)
                if (mask) canvas.clipRect(rect)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                paint.color = color(node.getJSONObject("backgroundColor"), opacity)
                canvas.drawRect(rect, paint)
                if (node.has("gradient") && !node.isNull("gradient")) drawGradient(canvas, rect, node.getJSONObject("gradient"), opacity)
                if (node.has("shape") && !node.isNull("shape")) drawShape(canvas, node.getJSONObject("shape"), opacity)
                if (node.has("video") && !node.isNull("video")) {
                    val video = node.getJSONObject("video")
                    val active = video.get("active")
                    require(active is Boolean) { "Invalid video state" }
                    if (active && video.has("token") && !video.isNull("token") && opacity > 0 && rect.width() > 0 && rect.height() > 0) {
                        val identifier = video.getString("identifier")
                        val token = video.getLong("token")
                        val pts = video.getDouble("pts")
                        require(identifier.matches(Regex("[A-Za-z0-9_.-]{1,128}")) &&
                            token in 1..9007199254740991L && pts.isFinite() && pts >= 0) { "Invalid video binding" }
                        val source = videoFrames ?: error("Video input is not attached")
                        val image = source.bitmap(identifier, token, pts)
                        require(!image.isRecycled && image.width in 1..4096 && image.height in 1..4096 &&
                            image.width.toLong() * image.height <= 8_000_000 &&
                            (android.os.Build.VERSION.SDK_INT < 26 || image.config != Bitmap.Config.HARDWARE)) { "Video frame refused" }
                        paint.color = Color.WHITE; paint.alpha = (opacity * 255).roundToInt()
                        canvas.drawBitmap(image, null, rect, paint)
                    }
                }
                if (node.has("texture") && !node.isNull("texture") && opacity > 0 && rect.width() > 0 && rect.height() > 0) {
                    val image = texture(node.getJSONObject("texture"))
                    val tint = node.getJSONObject("tint")
                    val r = channel(tint, "r"); val g = channel(tint, "g")
                    val b = channel(tint, "b"); val a = channel(tint, "a")
                    paint.color = Color.WHITE
                    paint.alpha = (opacity * 255).roundToInt()
                    paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                        r, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, a, 0f,
                    )))
                    canvas.drawBitmap(image, null, rect, paint)
                }
                val children = node.get("children")
                require(children is JSONArray && children.length() <= 4096) { "Invalid children" }
                for (i in 0 until children.length()) visit(children.getJSONObject(i), opacity, depth + 1)
            } finally { canvas.restoreToCount(save) }
        }
        try {
            visit(scene, 1f, 0)
            completed = true
            return output
        } catch (error: Exception) {
            output.recycle()
            throw error
        } finally {
            decoded.forEach { (name, bitmap) ->
                if (!completed || !retain(name, bitmap)) bitmap.recycle()
            }
        }
    }

    private fun drawGradient(canvas: Canvas, rect: RectF, gradient: JSONObject, opacity: Float) {
        val stops = gradient.getJSONArray("stops")
        require(stops.length() <= 64) { "Gradient stop limit" }
        if (stops.length() == 0) return
        val colors = IntArray(stops.length())
        val positions = FloatArray(stops.length())
        for (i in 0 until stops.length()) {
            val stop = stops.getJSONObject(i)
            positions[i] = number(stop, "location")
            require(positions[i] in 0f..1f && (i == 0 || positions[i] >= positions[i - 1])) { "Invalid gradient stop" }
            colors[i] = color(stop.getJSONObject("color"), opacity)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (colors.size == 1) { paint.color = colors[0]; canvas.drawRect(rect, paint); return }
        val start = gradient.getJSONObject("startPoint"); val end = gradient.getJSONObject("endPoint")
        val x = rect.left + number(start, "x") * rect.width(); val y = rect.top + number(start, "y") * rect.height()
        val ex = rect.left + number(end, "x") * rect.width(); val ey = rect.top + number(end, "y") * rect.height()
        require(x.isFinite() && y.isFinite() && ex.isFinite() && ey.isFinite() && (x != ex || y != ey)) { "Invalid gradient endpoints" }
        paint.shader = when (gradient.getString("kind")) {
            "LINEAR" -> LinearGradient(x, y, ex, ey, colors, positions, Shader.TileMode.CLAMP)
            "RADIAL" -> RadialGradient(x, y, hypot(ex - x, ey - y), colors, positions, Shader.TileMode.CLAMP)
            "CONICAL" -> SweepGradient(x, y, colors, positions).apply {
                setLocalMatrix(Matrix().apply { setRotate(Math.toDegrees(atan2((ey - y).toDouble(), (ex - x).toDouble())).toFloat(), x, y) })
            }
            else -> throw IllegalArgumentException("Unsupported gradient kind")
        }
        canvas.drawRect(rect, paint)
    }

    private fun drawShape(canvas: Canvas, shape: JSONObject, opacity: Float) {
        val commands = shape.getJSONArray("commands")
        require(commands.length() <= 4096) { "Path command limit" }
        val path = Path()
        for (i in 0 until commands.length()) {
            val command = commands.getJSONObject(i)
            when (command.getString("op")) {
                "M" -> path.moveTo(number(command, "x"), number(command, "y"))
                "L" -> path.lineTo(number(command, "x"), number(command, "y"))
                "Q" -> path.quadTo(number(command, "cx"), number(command, "cy"), number(command, "x"), number(command, "y"))
                "C" -> path.cubicTo(number(command, "cx1"), number(command, "cy1"), number(command, "cx2"),
                    number(command, "cy2"), number(command, "x"), number(command, "y"))
                "Z" -> path.close()
                else -> throw IllegalArgumentException("Unsupported path command")
            }
        }
        path.fillType = when (shape.getString("fillRule")) {
            "EVENODD" -> Path.FillType.EVEN_ODD
            "NONZERO" -> Path.FillType.WINDING
            else -> throw IllegalArgumentException("Unsupported fill rule")
        }
        val antialiased = shape.get("antialiased")
        require(antialiased is Boolean) { "Invalid antialiasing flag" }
        val paint = Paint().apply { isAntiAlias = antialiased; style = Paint.Style.FILL }
        paint.color = color(shape.getJSONObject("fillColor"), opacity)
        canvas.drawPath(path, paint)
        val lineWidth = number(shape, "lineWidth")
        val miter = number(shape, "miterLimit")
        val start = number(shape, "strokeStart"); val end = number(shape, "strokeEnd")
        require(lineWidth in 0f..4096f && miter in 0f..4096f && start in 0f..1f && end in 0f..1f) { "Invalid stroke" }
        paint.strokeCap = Paint.Cap.valueOf(shape.getString("lineCap"))
        paint.strokeJoin = Paint.Join.valueOf(shape.getString("lineJoin"))
        if (lineWidth == 0f || end <= start) return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = lineWidth; paint.strokeMiter = miter
        paint.color = color(shape.getJSONObject("strokeColor"), opacity)
        canvas.drawPath(if (start == 0f && end == 1f) path else trim(path, start, end), paint)
    }

    private fun trim(path: Path, start: Float, end: Float): Path {
        val measure = PathMeasure(path, false)
        var total = 0f
        var contours = 0
        do {
            require(++contours <= 4096 && measure.length.isFinite()) { "Contour limit" }
            total += measure.length
        } while (measure.nextContour())
        require(total.isFinite() && total <= 1_000_000_000f) { "Path length limit" }
        measure.setPath(path, false)
        val out = Path(); var offset = 0f
        do {
            val length = measure.length
            val from = (total * start - offset).coerceIn(0f, length)
            val to = (total * end - offset).coerceIn(0f, length)
            if (to > from) measure.getSegment(from, to, out, true)
            offset += length
        } while (measure.nextContour())
        return out
    }

    private fun bounds(node: JSONObject): RectF {
        val b = node.getJSONObject("bounds")
        val origin = b.getJSONObject("origin"); val size = b.getJSONObject("size")
        val x = number(origin, "x"); val y = number(origin, "y")
        val w = number(size, "width"); val h = number(size, "height")
        require(w >= 0 && h >= 0) { "Negative bounds" }
        return RectF(x, y, x + w, y + h)
    }
    private fun number(obj: JSONObject, key: String): Float {
        val value = obj.get(key)
        require(value is Number && value.toDouble().isFinite() && value.toDouble() in -1_000_000.0..1_000_000.0) { "Invalid scene number" }
        return value.toFloat()
    }
    private fun channel(obj: JSONObject, key: String): Float = number(obj, key).also { require(it in 0f..1f) { "Invalid color" } }
    private fun color(obj: JSONObject, opacity: Float) = Color.argb((channel(obj, "a") * opacity * 255).roundToInt(),
        (channel(obj, "r") * 255).roundToInt(), (channel(obj, "g") * 255).roundToInt(), (channel(obj, "b") * 255).roundToInt())
}
