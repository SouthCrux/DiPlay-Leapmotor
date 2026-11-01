package com.shilapi.xcertplay.homeautomation

import org.junit.Assert.*
import org.junit.Test

class C11GateButtonGuardTest {
    @Test fun ignoresCachedDoubleUntilIdleThenAcceptsOneFreshGesture() {
        val guard = C11GateButtonGuard().apply { reset(100) }
        assertFalse(guard.accept(byteArrayOf(51), 1_200, 1_200, true))
        assertFalse(guard.accept(byteArrayOf(48), 1_210, 1_210, true))
        assertTrue(guard.accept(byteArrayOf(51), 1_220, 1_220, true))
        assertFalse(guard.accept(byteArrayOf(51), 3_000, 3_000, true))
        assertFalse(guard.accept(byteArrayOf(48), 3_010, 3_010, true))
        assertTrue(guard.accept(byteArrayOf(51), 3_020, 3_020, true))
    }

    @Test fun rejectsStaleFutureSingleAndOutsideContextEventsWithoutQueuing() {
        val guard = C11GateButtonGuard().apply { reset(100, knownIdle = true) }
        assertFalse(guard.accept(byteArrayOf(51), 1_200, 2_000, true))
        assertFalse(guard.accept(byteArrayOf(51), 2_100, 2_000, true))
        assertFalse(guard.accept(byteArrayOf(49), 2_000, 2_000, true))
        assertFalse(guard.accept(byteArrayOf(51), 2_010, 2_010, false))
        assertFalse(guard.accept(byteArrayOf(51), 2_020, 2_020, true))
        assertFalse(guard.accept(byteArrayOf(48), 2_030, 2_030, true))
        assertTrue(guard.accept(byteArrayOf(51), 2_040, 2_040, true))
        guard.reset(2_050)
        assertFalse(guard.accept(byteArrayOf(51), 3_100, 3_100, true))
    }

    @Test fun focusRestorationIsRestrictedToOwnerCentreScreenMirrorAdjustments() {
        fun display(id: Int, window: String, pkg: String, user: Int = 10) = """
            Display: mDisplayId=$id rootTasks=2
            mCurrentFocus=Window{abc u$user $window}
            mFocusedApp=ActivityRecord{def u$user $pkg/.MainActivity t10}
        """.trimIndent()
        val mirror = display(0, "CarControlWindow", "com.leapmotor.carcontrol")
        assertTrue(C11GateFocusPolicy.mirrorAdjustmentsFocused(display(3, "CarControlWindow", "com.leapmotor.carcontrol") + "\n" + mirror, 10))
        for ((window, pkg) in listOf("Camera" to "com.leapmotor.cameraaround", "Update" to "com.leapmotor.systemupdate",
                                    "Launcher" to "com.android.launcher3")) {
            assertFalse(C11GateFocusPolicy.mirrorAdjustmentsFocused(display(0, window, pkg) + "\n" + display(3, "CarControlWindow", "com.leapmotor.carcontrol"), 10))
        }
        assertFalse(C11GateFocusPolicy.mirrorAdjustmentsFocused(mirror, 0))
        assertFalse(C11GateFocusPolicy.mirrorAdjustmentsFocused("", 10))
    }
}
