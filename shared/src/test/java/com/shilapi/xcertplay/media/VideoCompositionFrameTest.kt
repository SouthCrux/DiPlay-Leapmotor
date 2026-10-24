package com.shilapi.xcertplay.media

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoCompositionFrameTest {
    @Test fun redrawRetainsExactPixelsAndPhoneTimeUntilEveryDeliveryIsRetired() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        var closes = 0
        val source = VideoCompositionFrame(VideoSurfaceOrigins.newToken(), 123456789L, bitmap) { closes++ }
        val first = source.retainForComposition()
        val resumed = source.retainForComposition()
        assertTrue(first.token > source.token); assertTrue(resumed.token > first.token)
        assertEquals(source.senderNanos, resumed.senderNanos)
        assertSame(bitmap, first.bitmap); assertSame(bitmap, resumed.bitmap)
        first.close(); source.close()
        assertFalse(bitmap.isRecycled); assertEquals(0, closes)
        assertSame(bitmap, resumed.bitmap)
        assertThrows(IllegalStateException::class.java) { source.retainForComposition() }
        resumed.close(); resumed.close(); source.close()
        assertTrue(bitmap.isRecycled); assertEquals(1, closes)
    }
}
