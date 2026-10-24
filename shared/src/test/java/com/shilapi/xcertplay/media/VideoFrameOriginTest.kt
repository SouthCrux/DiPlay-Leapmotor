package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoFrameOriginTest {
    @Test fun outputReorderingKeepsExactPhoneClockAndConsumesEachOriginOnce() {
        val origins = VideoFrameOrigin()
        origins.queued(10, 1234567890123456L); origins.queued(20, 1234567906789456L)
        assertEquals(1234567906789456L, origins.take(20))
        assertEquals(1234567890123456L, origins.take(10))
        assertNull(origins.take(20))
    }

    @Test fun ambiguousCodecTimestampsAndMissingPhoneTimesAreNeverGuessed() {
        val origins = VideoFrameOrigin()
        origins.queued(10, 100); origins.queued(10, 200)
        assertNull(origins.take(10)); assertNull(origins.take(10))
        origins.queued(20, 0); assertNull(origins.take(20))
        origins.queued(30, 0); origins.queued(30, 300)
        assertNull(origins.take(30)); assertNull(origins.take(30))
        origins.queued(40, 400); origins.queued(40, 500)
        assertNull(origins.take(40)); origins.queued(40, 600)
        assertNull(origins.take(40)); assertNull(origins.take(40))
    }

    @Test fun capacityAndCodecResetDiscardOldOrigins() {
        val origins = VideoFrameOrigin(2)
        origins.queued(10, 100); origins.queued(20, 200); origins.queued(30, 300)
        assertNull(origins.take(10)); assertEquals(200L, origins.take(20))
        origins.queued(10, 999); assertNull(origins.take(10))
        origins.clear(); assertNull(origins.take(30))
    }
}
