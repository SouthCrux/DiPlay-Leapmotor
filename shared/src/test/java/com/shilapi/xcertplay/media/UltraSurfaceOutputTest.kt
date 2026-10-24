// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Closeable

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class UltraSurfaceOutputTest {
    private class Fixture : Closeable, UltraSurfaceOutput.CanvasAccess {
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val frame = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        private val target = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        private val wrongSize = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        var resized = false
        var lockFailure: RuntimeException? = null
        var postFailure: RuntimeException? = null
        var posts = 0
        val output = UltraSurfaceOutput(this).also { it.attach(surface) }
        override fun lock(surface: Surface): Canvas {
            lockFailure?.let { throw it }
            return Canvas(if (resized) wrongSize else target)
        }
        override fun post(surface: Surface, canvas: Canvas) {
            posts++
            postFailure?.let { throw it }
        }
        override fun close() {
            output.close(); surface.release(); texture.release()
            frame.recycle(); target.recycle(); wrongSize.recycle()
        }
    }

    @Test fun abandonedSurfaceDuringLockDoesNotPreventTheNextSubmission() {
        Fixture().use { f ->
            f.lockFailure = IllegalArgumentException("Surface abandoned")
            assertFalse(f.output.publish(f.frame))
            assertEquals(UltraSurfaceOutput.Result.LOCK_FAILED, f.output.lastResult)
            assertEquals(0, f.posts)
            f.lockFailure = null
            assertTrue(f.output.publish(f.frame))
            assertEquals(UltraSurfaceOutput.Result.SUBMITTED, f.output.lastResult)
            assertEquals(1, f.posts)
        }
    }

    @Test fun abandonedSurfaceDuringPostDoesNotClaimReadinessOrCloseTheOutput() {
        Fixture().use { f ->
            f.postFailure = IllegalArgumentException("Surface abandoned")
            assertFalse(f.output.publish(f.frame))
            assertEquals(UltraSurfaceOutput.Result.POST_FAILED, f.output.lastResult)
            f.postFailure = null
            assertTrue(f.output.publish(f.frame))
            assertEquals(2, f.posts)
        }
    }

    @Test fun temporaryBufferResizeReleasesCanvasAndCanRecover() {
        Fixture().use { f ->
            f.resized = true
            assertFalse(f.output.publish(f.frame))
            assertEquals(UltraSurfaceOutput.Result.SIZE_MISMATCH, f.output.lastResult)
            assertEquals(1, f.posts)
            f.resized = false
            assertTrue(f.output.publish(f.frame))
            assertEquals(2, f.posts)
        }
    }

    @Test fun temporaryCanvasAllocationFailureCanRecover() {
        Fixture().use { f ->
            f.lockFailure = Surface.OutOfResourcesException("No buffer")
            assertFalse(f.output.publish(f.frame))
            f.lockFailure = null
            assertTrue(f.output.publish(f.frame))
        }
    }

    @Test fun unrelatedProgrammingErrorsAreStillReported() {
        Fixture().use { f ->
            val failure = IllegalStateException("broken output implementation")
            f.lockFailure = failure
            assertSame(failure, assertThrows(IllegalStateException::class.java) { f.output.publish(f.frame) })
        }
    }
}
