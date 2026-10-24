// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real offscreen Canvas submission/replacement, never a physical-display assertion. */
object UltraSurfaceOutputSmoke {
    fun verify(bitmap: Bitmap) {
        val thread = HandlerThread("diplay-ultra-output-check").apply { start() }
        val handler = Handler(thread.looper)
        val ready = CountDownLatch(2)
        val failure = AtomicReference<Throwable?>()
        fun reader() = ImageReader.newInstance(bitmap.width, bitmap.height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ input ->
                try {
                    input.acquireLatestImage()?.use { image ->
                        check(image.width == bitmap.width && image.height == bitmap.height)
                        // The fixture's native yellow layer occupies this pixel, independent of video.
                        val plane = image.planes.single()
                        val offset = 2 * plane.rowStride + 2 * plane.pixelStride
                        check((plane.buffer.get(offset).toInt() and 255) > 240)
                        check((plane.buffer.get(offset + 1).toInt() and 255) > 240)
                        check((plane.buffer.get(offset + 2).toInt() and 255) < 15)
                        ready.countDown()
                    }
                } catch (error: Throwable) { failure.compareAndSet(null, error); while (ready.count > 0) ready.countDown() }
            }, handler)
        }
        val first = reader()
        val second = reader()
        val output = UltraSurfaceOutput()
        try {
            output.attach(first.surface)
            check(output.publish(bitmap))
            output.attach(second.surface)
            output.detach(first.surface) // Late destroy must not clear the replacement.
            check(output.publish(bitmap))
            check(ready.await(10, TimeUnit.SECONDS)) { "No submitted output buffers" }
            failure.get()?.let { throw it }
            output.detach(second.surface)
            check(!output.publish(bitmap))
            output.attach(first.surface)
            output.close()
            check(!output.publish(bitmap))
            check(first.surface.isValid && second.surface.isValid) { "Host surfaces were released" }
        } finally {
            output.close()
            first.close(); second.close()
            thread.quitSafely(); thread.join(5_000)
            check(!thread.isAlive)
        }
    }
}
