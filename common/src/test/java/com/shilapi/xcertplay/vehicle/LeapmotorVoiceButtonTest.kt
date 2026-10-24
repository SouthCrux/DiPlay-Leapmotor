package com.shilapi.xcertplay.vehicle

import android.os.Looper
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class LeapmotorVoiceButtonTest {
    private var singles = 0
    private var doubles = 0
    private var eligible = true
    private val button = LeapmotorVoiceButton({ eligible }, { singles++ }, { doubles++ })
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun send(pressed: Boolean) = button.receive(byteArrayOf(if (pressed) 49 else 48), SystemClock.elapsedRealtime())
    private fun ready() { button.start(); send(false); advance(1_001) }

    @Test fun firstCompleteLiveClickWorksWithoutAnInitialIdleNotification() {
        button.start(); advance(1_001)
        send(true); advance(8); send(false); advance(350)
        assertEquals(1, singles)
        button.stop()
    }

    @Test fun shortPressWaitsForDoubleWindowAndIgnoresRepeatedDown() {
        ready(); send(true); send(true); send(false)
        advance(349); assertEquals(0, singles)
        advance(1); assertEquals(1, singles); assertEquals(0, doubles)
        button.stop()
    }

    @Test fun doublePressDoesNotAlsoOpenSiri() {
        ready(); send(true); send(false); advance(200); send(true); send(false)
        advance(500); assertEquals(0, singles); assertEquals(1, doubles)
        button.stop()
    }

    @Test fun initialReplayAndHeldButtonDoNotTriggerAnAssistant() {
        button.start(); send(true); advance(1_100); send(true); send(false); advance(500)
        assertEquals(0, singles)
        send(true); send(false); advance(350); assertEquals(1, singles)
        button.stop()
    }

    @Test fun staleMalformedAndFutureMessagesCannotTrigger() {
        ready()
        val now = SystemClock.elapsedRealtime()
        button.receive(byteArrayOf(49), now - 600)
        button.receive(byteArrayOf(49), now + 1)
        button.receive(byteArrayOf(1), now)
        button.receive(byteArrayOf(49, 0), now)
        advance(500); assertEquals(0, singles); assertEquals(0, doubles)
        button.stop()
    }

    @Test fun losingSessionOrLeavingHostCancelsPendingInvocation() {
        ready(); send(true); send(false); eligible = false; advance(350)
        assertEquals(0, singles)
        eligible = true; send(true); send(false); button.stop(); advance(500)
        assertEquals(0, singles); assertEquals(0, doubles)
    }

    @Test fun pressesOutsideDoubleWindowRemainSeparate() {
        ready(); send(true); send(false); advance(400); send(true); send(false); advance(400)
        assertEquals(2, singles); assertEquals(0, doubles)
        button.stop()
    }

    @Test fun bounceAndSourceRestartCannotLaunchAnAssistantTwice() {
        ready(); send(true); send(false); advance(4); send(true); send(false); advance(350)
        assertEquals(1, singles); assertEquals(0, doubles)
        send(true); send(false)
        button.receive(null, -1L)
        advance(500); assertEquals(1, singles)
        send(true); send(false); advance(350); assertEquals(1, singles)
        button.stop()
    }
}
