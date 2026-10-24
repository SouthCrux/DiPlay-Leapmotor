package com.shilapi.xcertplay.media

import android.graphics.Bitmap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RetainedVideoFrameWorkerTest {
    @Test fun refreshDoesNotInvalidateAnAlreadyQueuedCodecOriginAndOlderPhoneTimeCannotReplaceTheCache() {
        val deliveries = LinkedBlockingQueue<Pair<Long, Long>>()
        val errors = LinkedBlockingQueue<String>()
        val worker = RetainedVideoFrameWorker({ frame ->
            try { deliveries.add(frame.token to frame.senderNanos) } finally { frame.close() }
        }, { errors.add("failure") })
        val a = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val stale = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            worker.offer(VideoCompositionFrame(VideoSurfaceOrigins.newToken(), 100, a))
            val first = checkNotNull(deliveries.poll(5, TimeUnit.SECONDS))
            val queued = VideoCompositionFrame(VideoSurfaceOrigins.newToken(), 200, b)
            worker.requestRefresh()
            val refresh = checkNotNull(deliveries.poll(5, TimeUnit.SECONDS))
            assertTrue(refresh.first > queued.token)
            assertEquals(first.second, refresh.second)
            worker.offer(queued)
            val next = checkNotNull(deliveries.poll(5, TimeUnit.SECONDS))
            assertTrue(next.first > refresh.first); assertEquals(200L, next.second)
            assertTrue(a.isRecycled)
            val rejected = CountDownLatch(1)
            worker.offer(VideoCompositionFrame(VideoSurfaceOrigins.newToken(), 150, stale) { rejected.countDown() })
            assertTrue(rejected.await(5, TimeUnit.SECONDS))
            worker.requestRefresh()
            assertEquals(200L, checkNotNull(deliveries.poll(5, TimeUnit.SECONDS)).second)
            assertTrue(errors.isEmpty()); assertTrue(deliveries.isEmpty())
            assertFalse(b.isRecycled); assertTrue(stale.isRecycled)
        } finally { worker.close(); assertTrue(worker.awaitClosed(5_000)) }
        assertTrue(b.isRecycled)
    }

    @Test fun failedRefreshClosesItsDeliveryAndCacheExactlyOnceAfterTheWorkerExits() {
        val first = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val recycled = CountDownLatch(1)
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        var calls = 0
        val worker = RetainedVideoFrameWorker({ frame ->
            if (++calls > 1) error("fixture refresh failure")
            frame.close(); first.countDown()
        }, { failed.countDown() })
        try {
            worker.offer(VideoCompositionFrame(VideoSurfaceOrigins.newToken(), 100, bitmap) { recycled.countDown() })
            assertTrue(first.await(5, TimeUnit.SECONDS))
            worker.requestRefresh(); assertTrue(failed.await(5, TimeUnit.SECONDS))
            assertTrue(worker.awaitClosed(5_000))
            assertTrue(recycled.await(5, TimeUnit.SECONDS)); assertTrue(bitmap.isRecycled)
            worker.requestRefresh(); assertEquals(2, calls)
        } finally { worker.close(); assertTrue(worker.awaitClosed(5_000)) }
    }
}
