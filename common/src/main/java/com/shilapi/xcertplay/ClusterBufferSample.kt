package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.os.Handler
import android.view.PixelCopy
import android.view.Surface

/** One debug-only buffer observation per visible stream activation, never an image capture file.
 * Call all lifecycle methods and callbacks on the owning window's thread.
 */
internal class ClusterBufferSample(
    private val handler: Handler,
    private val isCurrent: (Surface) -> Boolean,
    private val report: (String) -> Unit,
    private val copy: (Surface, Bitmap, (Int) -> Unit) -> Unit = { surface, bitmap, done ->
        PixelCopy.request(surface, bitmap, { done(it) }, handler)
    },
) {
    private var surface: Surface? = null
    private var activation: Any? = null
    private var pending: Runnable? = null

    fun update(next: Surface?, eligible: Boolean) {
        val usable = next.takeIf { eligible && it?.isValid == true }
        if (usable === surface && (usable == null || activation != null)) return
        cancel()
        if (usable == null) return
        surface = usable
        val owner = Any()
        activation = owner
        val request = Runnable {
            pending = null
            if (!current(owner, usable)) return@Runnable
            val bitmap = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
            try {
                copy(usable, bitmap) done@{ result ->
                    try {
                        if (!current(owner, usable)) return@done
                        val content = if (result == PixelCopy.SUCCESS) ClusterBufferContent.classify(bitmap) else "UNKNOWN"
                        if (current(owner, usable)) emit(result, content)
                    } catch (_: RuntimeException) {
                        if (current(owner, usable)) emit(-1, "UNKNOWN")
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            } catch (_: RuntimeException) {
                if (!bitmap.isRecycled) bitmap.recycle()
                if (current(owner, usable)) emit(-1, "UNKNOWN")
            }
        }
        pending = request
        handler.postDelayed(request, DELAY_MILLIS)
    }

    fun cancel() {
        activation = null
        surface = null
        pending?.let(handler::removeCallbacks)
        pending = null
    }

    private fun current(owner: Any, expected: Surface): Boolean = activation === owner &&
        surface === expected && expected.isValid && isCurrent(expected)

    private fun emit(result: Int, content: String) {
        runCatching { report(line(result, content)) }
    }

    private fun line(result: Int, content: String): String {
        val status = when (result) {
            PixelCopy.SUCCESS -> "SUCCESS"
            PixelCopy.ERROR_SOURCE_NO_DATA -> "NO_DATA"
            PixelCopy.ERROR_TIMEOUT -> "TIMEOUT"
            else -> "ERROR"
        }
        return "cluster buffer sample result=$status content=$content"
    }

    private companion object {
        const val SIDE = 64
        const val DELAY_MILLIS = 2_000L
    }
}

/** Coarse content classes only: no pixels, RGB values, hashes, positions or images leave memory.
 * Uniform means each RGB channel spans at most 8/255 across the sample. Uniform samples are dark
 * only if all RGB values <=24, light only if all >=224, otherwise other. Nonopaque is unknown.
 * VARIED says only that the buffer has variation; it does not establish map content or presentation.
 */
internal object ClusterBufferContent {
    fun classify(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        if (pixels.isEmpty() || pixels.any { (it ushr 24) != 255 }) return "UNKNOWN"
        val minima = intArrayOf(255, 255, 255)
        val maxima = intArrayOf(0, 0, 0)
        for (pixel in pixels) {
            for (channel in 0..2) {
                val value = (pixel ushr (channel * 8)) and 255
                minima[channel] = minOf(minima[channel], value)
                maxima[channel] = maxOf(maxima[channel], value)
            }
        }
        if ((0..2).any { maxima[it] - minima[it] > 8 }) return "VARIED"
        return when {
            maxima.all { it <= 24 } -> "UNIFORM_DARK"
            minima.all { it >= 224 } -> "UNIFORM_LIGHT"
            else -> "UNIFORM_OTHER"
        }
    }
}
