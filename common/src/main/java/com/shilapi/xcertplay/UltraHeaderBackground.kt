package com.shilapi.xcertplay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Alpha only in the approved top letterbox; the rest of the host remains opaque black. */
internal class UltraHeaderBackground : Drawable() {
    private val paint = Paint().apply { color = Color.BLACK }
    private var header: RectF? = null
    private var exclusions: List<RectF> = emptyList()

    fun setHeader(rect: RectF?, exclusions: List<RectF> = emptyList()) {
        header = rect?.takeIf { value ->
            listOf(value.left, value.top, value.right, value.bottom).all(Float::isFinite) &&
                value.width() > 0f && value.height() > 0f
        }?.let(::RectF)
        this.exclusions = exclusions.map(::RectF)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val area = RectF(bounds)
        val hole = header?.let(::RectF)?.takeIf { it.intersect(area) }
        if (hole == null) { canvas.drawRect(area, paint); return }
        canvas.drawRect(area.left, area.top, area.right, hole.top, paint)
        canvas.drawRect(area.left, hole.bottom, area.right, area.bottom, paint)
        canvas.drawRect(area.left, hole.top, hole.left, hole.bottom, paint)
        canvas.drawRect(hole.right, hole.top, area.right, hole.bottom, paint)
        for (excluded in exclusions) {
            val clipped = RectF(excluded)
            if (clipped.intersect(hole)) canvas.drawRect(clipped, paint)
        }
    }
    override fun setAlpha(alpha: Int) = Unit // This privacy boundary always stays fully opaque outside its hole.
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
