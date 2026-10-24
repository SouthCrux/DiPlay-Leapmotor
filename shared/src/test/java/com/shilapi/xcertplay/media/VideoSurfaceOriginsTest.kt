package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoSurfaceOriginsTest {
    @Test fun tokensIdentifyBuffersEvenWhenSenderTimesRepeat() {
        VideoSurfaceOrigins().use { origins ->
            val a = origins.queue(123)
            val b = origins.queue(123)
            assertNotEquals(a, b)
            assertEquals(123L, origins.latched(a))
            assertNull(origins.latched(a))
            assertEquals(123L, origins.latched(b))
        }
    }

    @Test fun latchingLatestRetiresDroppedFramesButPreservesFutureBuffers() {
        VideoSurfaceOrigins().use { origins ->
            val old = origins.queue(100)
            val current = origins.queue(200)
            val future = origins.queue(300)
            assertEquals(200L, origins.latched(current))
            assertNull(origins.latched(old))
            assertEquals(300L, origins.latched(future))
        }
    }

    @Test fun evictionCancellationAndCloseNeverGuessAnOrigin() {
        val origins = VideoSurfaceOrigins(2)
        val first = origins.queue(100)
        val second = origins.queue(200)
        val third = origins.queue(300)
        assertNull(origins.latched(first))
        origins.discard(second)
        assertNull(origins.latched(second))
        origins.close()
        assertNull(origins.latched(third))
        assertThrows(IllegalStateException::class.java) { origins.queue(400) }
        origins.close()
    }

    @Test fun aReplacementTargetCannotReuseRetiredTokens() {
        val token = VideoSurfaceOrigins().use { it.queue(100) }
        VideoSurfaceOrigins().use { replacement ->
            assertNull(replacement.latched(token))
            assertTrue(replacement.queue(100) > token)
        }
    }
}
