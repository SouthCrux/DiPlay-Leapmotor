package com.shilapi.xcertplay.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test

class LatestVideoFrameWorkerTest {
    @Test fun resumeRefreshRunsWithoutAnotherDecodedFrameAndCoalescesWhileDrawing() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val refreshed = CountDownLatch(1)
        val observed = CopyOnWriteArrayList<String>()
        val worker = LatestVideoFrameWorker<Frame>({ frame ->
            entered.countDown(); check(proceed.await(5, TimeUnit.SECONDS))
            observed += "frame"; frame.close()
        }, refresh = { observed += "refresh"; refreshed.countDown() })
        try {
            worker.offer(Frame(1)); assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(1_000) { worker.requestRefresh() }
            proceed.countDown(); assertTrue(refreshed.await(5, TimeUnit.SECONDS))
        } finally { proceed.countDown(); worker.close(); assertTrue(worker.awaitClosed(5_000)) }
        worker.requestRefresh()
        assertEquals(listOf("frame", "refresh"), observed)
    }

    @Test fun pendingDecodedFramePrecedesARefreshAndRefreshFailureRetiresPendingWork() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val observed = CopyOnWriteArrayList<Long>()
        val worker = LatestVideoFrameWorker<Frame>({ frame ->
            if (frame.token == 1L) { entered.countDown(); check(proceed.await(5, TimeUnit.SECONDS)) }
            observed += frame.token; frame.close()
        }, { failed.countDown() }, { error("fixture refresh failed") })
        try {
            worker.offer(Frame(1)); assertTrue(entered.await(5, TimeUnit.SECONDS))
            worker.requestRefresh(); worker.offer(Frame(2))
            proceed.countDown(); assertTrue(failed.await(5, TimeUnit.SECONDS))
            assertTrue(worker.awaitClosed(5_000))
            assertEquals(listOf(1L, 2L), observed)
            val late = Frame(3); worker.offer(late); assertTrue(late.closed)
        } finally { proceed.countDown(); worker.close(); assertTrue(worker.awaitClosed(5_000)) }
    }

    private class Frame(override val token: Long) : TimedVideoFrame {
        override val senderNanos = token * 1_000_000
        @Volatile var closed = false
        override fun close() { closed = true }
    }

    @Test fun slowCompositionDropsSupersededPendingFramesAndClosesNewOffersAfterShutdown() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val done = CountDownLatch(2)
        val observed = CopyOnWriteArrayList<Long>()
        val worker = LatestVideoFrameWorker<Frame>({ frame ->
            if (frame.token == 1L) { entered.countDown(); check(proceed.await(5, TimeUnit.SECONDS)) }
            observed += frame.token; frame.close(); done.countDown()
        })
        val first = Frame(1); val dropped = Frame(2); val latest = Frame(3)
        try {
            worker.offer(first); assertTrue(entered.await(5, TimeUnit.SECONDS))
            worker.offer(dropped); worker.offer(latest)
            assertTrue(dropped.closed); assertFalse(first.closed); assertFalse(latest.closed)
            proceed.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1L, 3L), observed)
        } finally { proceed.countDown(); worker.close(); assertTrue(worker.awaitClosed(5_000)) }
        val stale = Frame(4); worker.offer(stale); assertTrue(stale.closed)
    }

    @Test fun failedConsumerClosesItsInputAndStopsInsteadOfContinuingWithPartialState() {
        val failed = CountDownLatch(1)
        val frame = Frame(1)
        val worker = LatestVideoFrameWorker<Frame>({ error("fixture consumer failed") }, { failed.countDown() })
        worker.offer(frame)
        assertTrue(failed.await(5, TimeUnit.SECONDS)); assertTrue(worker.awaitClosed(5_000)); assertTrue(frame.closed)
        worker.close()
    }
}
