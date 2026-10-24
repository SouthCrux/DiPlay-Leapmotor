package com.shilapi.xcertplay

import android.content.DialogInterface
import android.view.Surface
import com.shilapi.xcertplay.hud.ClusterTurnGuidance

/** The decoder's output window, independent of OEM window type. */
internal interface ClusterMapWindow : DialogInterface {
    val outputSurface: Surface?
    val mapVisible: Boolean
    fun show()
    fun showing(): Boolean
    fun setOnDismissListener(listener: DialogInterface.OnDismissListener?)
    fun setStreamActive(active: Boolean)
    fun setMapVisible(visible: Boolean)
    fun setTurnCardOverlay(xPercent: Int, yPercent: Int, sizePercent: Int)
    fun setTurnCardNightMode(night: Boolean)
    fun setTurnCardOpacity(percent: Int)
    fun setTurnCardGuidance(guidance: ClusterTurnGuidance?)
}
