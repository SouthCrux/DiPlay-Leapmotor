package com.southcrux.c11diagnostics

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class DiagnosticActivityTest {
    @Test fun openingOnlyShowsInstructionsAndPermissionRequestRequiresExplicitTap() {
        val controller = Robolectric.buildActivity(DiagnosticActivity::class.java).setup()
        val activity = controller.get()
        val shadow = shadowOf(activity)
        assertNull(shadow.lastRequestedPermission)
        assertTrue(shadowOf(activity.application).boundServiceConnections.isEmpty())
        val content = (activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            .getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout
        (0 until content.childCount).map { content.getChildAt(it) }.filterIsInstance<Button>()
            .single { it.text == activity.getString(R.string.energy_permission) }.performClick()
        assertArrayEquals(arrayOf(ENERGY_PERMISSION), shadow.lastRequestedPermission.requestedPermissions)
        val requested = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions!!.toList()
        assertEquals(listOf(ENERGY_PERMISSION), requested)
        assertFalse(requested.contains(Manifest.permission.INTERNET))
        controller.pause().stop().destroy()
    }
}
