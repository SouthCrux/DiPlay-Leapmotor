package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterBufferSampleTest {
    private class Fixture : AutoCloseable {
        val textures = mutableListOf<SurfaceTexture>()
        val surfaces = mutableListOf<Surface>()
        val messages = mutableListOf<String>()
        val requests = mutableListOf<Pair<Bitmap, (Int) -> Unit>>()
        var eligible = true
        var throwOnRequest = false
        val sample = ClusterBufferSample(Handler(Looper.getMainLooper()), { eligible }, messages::add,
            copy = { _, bitmap, done ->
                requests += bitmap to done
                if (throwOnRequest) throw IllegalStateException("Synthetic request failure")
            })
        fun surface(): Surface {
            val texture = SurfaceTexture(0).also(textures::add)
            return Surface(texture).also(surfaces::add)
        }
        fun waitForSample() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        override fun close() {
            sample.cancel()
            requests.filter { !it.first.isRecycled }.forEach { it.second(PixelCopy.ERROR_UNKNOWN) }
            surfaces.forEach(Surface::release)
            textures.forEach(SurfaceTexture::release)
        }
    }

    @Test fun oneBoundedCopyPerVisibleActivationAndOnlyFixedMetadataEscapes() {
        Fixture().use { f ->
            val surface = f.surface()
            f.sample.update(surface, true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_999))
            assertTrue(f.requests.isEmpty())
            f.sample.update(surface, true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            val (bitmap, done) = f.requests.single()
            assertEquals(64, bitmap.width)
            assertEquals(64, bitmap.height)
            bitmap.eraseColor(Color.WHITE)
            done(PixelCopy.SUCCESS)
            assertTrue(bitmap.isRecycled)
            assertEquals(listOf("cluster buffer sample result=SUCCESS content=UNIFORM_LIGHT"), f.messages)
            f.sample.update(surface, true)
            f.waitForSample()
            assertEquals(1, f.requests.size)
            f.sample.update(surface, false)
            f.sample.update(surface, true)
            f.waitForSample()
            assertEquals(2, f.requests.size)
        }
    }

    @Test fun hiddenRetiredAndReplacedOwnersNeverReportLateResultsAndAlwaysRecycle() {
        Fixture().use { f ->
            val first = f.surface()
            val second = f.surface()
            f.sample.update(first, true)
            f.waitForSample()
            val old = f.requests.single()
            f.sample.update(second, true)
            old.second(PixelCopy.SUCCESS)
            assertTrue(old.first.isRecycled)
            assertTrue(f.messages.isEmpty())
            f.waitForSample()
            val replacement = f.requests.last()
            f.sample.update(second, false)
            replacement.second(PixelCopy.SUCCESS)
            assertTrue(replacement.first.isRecycled)
            assertTrue(f.messages.isEmpty())
            f.sample.update(second, true)
            f.waitForSample()
            val retired = f.requests.last()
            f.sample.cancel()
            retired.second(PixelCopy.SUCCESS)
            assertTrue(retired.first.isRecycled)
            assertTrue(f.messages.isEmpty())
        }
    }

    @Test fun cancellationBeforeAllocationAndExternalOwnerFencePreventRequests() {
        Fixture().use { f ->
            val surface = f.surface()
            f.sample.update(surface, true)
            f.sample.cancel()
            f.waitForSample()
            assertTrue(f.requests.isEmpty())
            f.sample.update(surface, true)
            f.eligible = false
            f.waitForSample()
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun ownerLossDuringCopyDoesNotPublishEvenWhenResultSucceeds() {
        Fixture().use { f ->
            f.sample.update(f.surface(), true)
            f.waitForSample()
            val (bitmap, done) = f.requests.single()
            bitmap.eraseColor(Color.BLUE)
            f.eligible = false
            done(PixelCopy.SUCCESS)
            assertTrue(bitmap.isRecycled)
            assertTrue(f.messages.isEmpty())
        }
    }

    @Test fun invalidSurfaceCannotStartOrFinishAProbe() {
        Fixture().use { f ->
            val invalid = f.surface().apply { release() }
            f.sample.update(invalid, true)
            f.waitForSample()
            assertTrue(f.requests.isEmpty())
            val valid = f.surface()
            f.sample.update(valid, true)
            f.waitForSample()
            val (bitmap, done) = f.requests.single()
            valid.release()
            done(PixelCopy.SUCCESS)
            assertTrue(bitmap.isRecycled)
            assertTrue(f.messages.isEmpty())
        }
    }

    @Test fun copyErrorsAndThrownRequestsRecycleAndExposeNoExceptionDetails() {
        for ((result, label) in listOf(PixelCopy.ERROR_SOURCE_NO_DATA to "NO_DATA",
            PixelCopy.ERROR_TIMEOUT to "TIMEOUT", PixelCopy.ERROR_SOURCE_INVALID to "ERROR")) {
            Fixture().use { f ->
                f.sample.update(f.surface(), true)
                f.waitForSample()
                val (bitmap, done) = f.requests.single()
                done(result)
                assertTrue(bitmap.isRecycled)
                assertEquals(listOf("cluster buffer sample result=$label content=UNKNOWN"), f.messages)
            }
        }
        Fixture().use { f ->
            f.throwOnRequest = true
            f.sample.update(f.surface(), true)
            f.waitForSample()
            assertTrue(f.requests.single().first.isRecycled)
            assertEquals(listOf("cluster buffer sample result=ERROR content=UNKNOWN"), f.messages)
        }
    }

    @Test fun coarseClassificationHasExplicitBoundariesAndDoesNotInferMaps() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            fun classification(color: Int): String {
                bitmap.eraseColor(color)
                return ClusterBufferContent.classify(bitmap)
            }
            assertEquals("UNIFORM_DARK", classification(Color.rgb(24, 24, 24)))
            assertEquals("UNIFORM_OTHER", classification(Color.rgb(25, 25, 25)))
            assertEquals("UNIFORM_OTHER", classification(Color.rgb(223, 223, 223)))
            assertEquals("UNIFORM_LIGHT", classification(Color.rgb(224, 224, 224)))
            assertEquals("UNIFORM_OTHER", classification(Color.RED))
            bitmap.setPixel(0, 0, Color.rgb(247, 0, 0))
            assertEquals("UNIFORM_OTHER", ClusterBufferContent.classify(bitmap))
            bitmap.setPixel(0, 0, Color.rgb(246, 0, 0))
            assertEquals("VARIED", ClusterBufferContent.classify(bitmap))
            assertEquals("UNKNOWN", classification(Color.TRANSPARENT))
        } finally { bitmap.recycle() }
    }
}
