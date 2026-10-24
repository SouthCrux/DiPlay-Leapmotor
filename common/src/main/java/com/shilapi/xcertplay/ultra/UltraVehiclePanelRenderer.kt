package com.shilapi.xcertplay.ultra

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.host.R
import java.text.NumberFormat
import java.util.Locale

/** Bounded local Android renderer, independent of DiPlay Settings and the reference Lua resources. */
internal open class UltraVehiclePanelRenderer(private val context: Context) {
    data class Style(val localeTags: String, val fontScale: Float, val rtl: Boolean)
    data class Rendered(val bitmap: Bitmap, val hits: List<UltraVehiclePanelSession.HitRegion>, val description: String)
    fun style(): Style = context.resources.configuration.let {
        Style(it.locales.toLanguageTags(), it.fontScale, it.layoutDirection == View.LAYOUT_DIRECTION_RTL)
    }

    open fun render(reading: UltraVehiclePanelSession.Reading?, page: UltraVehiclePanelSession.Page,
        width: Int, height: Int, style: Style): Rendered {
        val config = android.content.res.Configuration(context.resources.configuration).apply {
            setLocales(android.os.LocaleList.forLanguageTags(style.localeTags))
            fontScale = style.fontScale
        }
        val localized = context.createConfigurationContext(config)
        val locale = config.locales[0] ?: Locale.US
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val scale = minOf(width / 1650f, height / 720f)
            val dx = (width - 1650f * scale) / 2
            val dy = (height - 720f * scale) / 2
            canvas.drawColor(Color.BLACK)
            canvas.translate(dx, dy)
            canvas.scale(scale, scale)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(0f, 0f, 1650f, 720f, intArrayOf(0xFF13202A.toInt(), 0xFF080C12.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, 1650f, 720f, paint)
            paint.shader = null
            val hits = mutableListOf<UltraVehiclePanelSession.HitRegion>()
            fun rect(left: Float, top: Float, right: Float, bottom: Float): RectF =
                if (style.rtl) RectF(1650-right, top, 1650-left, bottom) else RectF(left, top, right, bottom)
            fun text(value: String, size: Float, left: Float, baseline: Float, available: Float,
                color: Int = Color.WHITE, bold: Boolean = false) {
                val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    textSize = size * style.fontScale
                    typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
                    textAlign = if (style.rtl) Paint.Align.RIGHT else Paint.Align.LEFT
                }
                val fitted = TextUtils.ellipsize(value, text, available, TextUtils.TruncateAt.END).toString()
                canvas.drawText(fitted, if (style.rtl) 1650-left else left, baseline, text)
            }
            fun icon(id: Int, bounds: RectF, color: Int = Color.WHITE) {
                ContextCompat.getDrawable(localized, id)?.mutate()?.apply {
                    setTint(color)
                    layoutDirection = if (style.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    setBounds(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
                    draw(canvas)
                }
            }
            fun hit(action: UltraVehiclePanelSession.Action, region: RectF) {
                hits += UltraVehiclePanelSession.HitRegion(action, dx + region.left * scale, dy + region.top * scale,
                    dx + region.right * scale, dy + region.bottom * scale)
            }
            val vehicle = localized.getString(R.string.settings_vehicle)
            val battery = localized.getString(R.string.vehicle_field_battery)
            val rangeLabel = localized.getString(R.string.ultra_vehicle_range)
            val unavailable = localized.getString(R.string.ultra_vehicle_data_unavailable)
            val percent = reading?.let { NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 }.format(it.percent / 100) } ?: "—"
            val range = reading?.let { localized.getString(R.string.turn_card_info_km, it.rangeKm.toDouble()) } ?: "—"
            val back = localized.getString(R.string.back)
            val detail = page == UltraVehiclePanelSession.Page.BATTERY_DETAILS
            if (detail) {
                val backRect = rect(48f, 22f, 340f, 112f)
                paint.color = 0xFF26343F.toInt()
                canvas.drawRoundRect(backRect, 28f, 28f, paint)
                icon(R.drawable.ic_dp_back, rect(68f, 44f, 108f, 84f))
                text(back, 28f, 126f, 80f, 185f)
                hit(UltraVehiclePanelSession.Action.BACK, backRect)
                text(battery, 38f, 390f, 83f, 1180f, bold = true)
            } else text(vehicle, 40f, 64f, 86f, 1500f, bold = true)
            val body = rect(64f, 152f, 1586f, 656f)
            paint.color = 0xFF182832.toInt()
            canvas.drawRoundRect(body, 40f, 40f, paint)
            if (detail) {
                text(battery, 30f, 108f, 235f, 1380f, 0xFFB7CAD4.toInt())
                text(percent, 106f, 108f, 430f, 650f, bold = true)
                text(rangeLabel, 30f, 820f, 285f, 680f, 0xFFB7CAD4.toInt())
                text(range, 58f, 820f, 405f, 680f, bold = true)
                if (reading != null) {
                    val track = rect(108f, 514f, 1542f, 560f)
                    paint.color = 0xFF304550.toInt()
                    canvas.drawRoundRect(track, 23f, 23f, paint)
                    val fill = rect(108f, 514f, 108f + 1434f * (reading.percent / 100).toFloat(), 560f)
                    paint.color = 0xFF65D7DF.toInt()
                    canvas.drawRoundRect(fill, 23f, 23f, paint)
                }
            } else {
                text(battery, 32f, 108f, 240f, 1050f, 0xFFB7CAD4.toInt())
                text(percent, 118f, 108f, 444f, 840f, bold = true)
                text(range, 48f, 108f, 570f, 1000f)
                icon(R.drawable.ic_dp_car, rect(1100f, 292f, 1430f, 510f), 0xFF65D7DF.toInt())
                if (reading != null) {
                    icon(R.drawable.ic_dp_chevron, rect(1494f, 362f, 1542f, 410f))
                    hit(UltraVehiclePanelSession.Action.BATTERY_DETAILS, body)
                }
            }
            if (reading == null) text(unavailable, 28f, 108f, 620f, 1380f, 0xFFB7CAD4.toInt())
            return Rendered(bitmap, hits, listOf(if (detail) battery else vehicle, battery, percent, rangeLabel, range,
                unavailable.takeIf { reading == null }, back.takeIf { detail }).filterNotNull().joinToString(" · "))
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }
}
