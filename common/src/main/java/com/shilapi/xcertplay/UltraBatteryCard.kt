package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.text.BidiFormatter
import androidx.core.text.TextDirectionHeuristicsCompat
import android.text.SpannableString
import android.text.Spanned
import android.text.Layout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import java.text.NumberFormat
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Physical parent coordinates only. Never shrinks the video or guesses an empty area inside it. */
internal object UltraBatteryCardPlacement {
    fun place(width: Int, height: Int, content: RectF?, exclusions: List<RectF>,
              cardWidth: Int, cardHeight: Int, gap: Int, topOnly: Boolean = false): Rect? {
        if (width <= 0 || height <= 0 || cardWidth <= 0 || cardHeight <= 0 || gap < 0 ||
            content == null || exclusions.size > MAX_EXCLUSIONS) return null
        fun finite(rect: RectF) = listOf(rect.left, rect.top, rect.right, rect.bottom).all(Float::isFinite)
        if (!finite(content) || content.width() <= 0 || content.height() <= 0 ||
            content.left < 0 || content.top < 0 || content.right > width || content.bottom > height ||
            exclusions.any { !finite(it) }) return null
        var spaces = (if (topOnly) listOf(RectF(0f, 0f, width.toFloat(), content.top)) else listOf(
            RectF(0f, 0f, width.toFloat(), content.top),
            RectF(0f, content.bottom, width.toFloat(), height.toFloat()),
            RectF(0f, content.top, content.left, content.bottom),
            RectF(content.right, content.top, width.toFloat(), content.bottom),
        )).map { RectF(it).apply { inset(gap.toFloat(), gap.toFloat()) } }
            .filter { it.width() > 0 && it.height() > 0 }
        for (excluded in exclusions) {
            if (excluded.width() <= 0 || excluded.height() <= 0) continue
            val obstacle = RectF(excluded).apply { inset(-gap.toFloat(), -gap.toFloat()) }
            spaces = spaces.flatMap { space ->
                val cut = RectF(space)
                if (!cut.intersect(obstacle)) listOf(space) else listOf(
                    RectF(space.left, space.top, space.right, cut.top),
                    RectF(space.left, cut.bottom, space.right, space.bottom),
                    RectF(space.left, cut.top, cut.left, cut.bottom),
                    RectF(cut.right, cut.top, space.right, cut.bottom),
                ).filter { it.width() > 0 && it.height() > 0 }
            }
        }
        return spaces.filter { it.width() >= cardWidth && it.height() >= cardHeight }
            .sortedByDescending { it.width().toDouble() * it.height() }
            .firstNotNullOfOrNull { space ->
                val left = ceil((space.left + space.right - cardWidth) / 2).toInt()
                val top = ceil((space.top + space.bottom - cardHeight) / 2).toInt()
                if (left + cardWidth > floor(space.right) || top + cardHeight > floor(space.bottom)) null
                else Rect(left, top, left + cardWidth, top + cardHeight)
            }
    }

    private const val MAX_EXCLUSIONS = 32
}

/** Local vehicle indicator outside CarPlay. Caller owns source validity, eligibility and refresh. */
internal class UltraBatteryCard(context: Context, private val transparentHeader: Boolean = false,
                                private val compactHeader: Boolean = false) {
    private data class Style(val density: Float, val scaledDensity: Float, val fontScale: Float, val locales: String)
    private var style: Style? = null
    private var batteryIcon: BatteryIcon? = null
    private fun dp(value: Int) = (value * view.resources.displayMetrics.density).roundToInt()
    val view = TextView(context).apply {
        setTextColor(Color.WHITE)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_LOCALE
        isClickable = false
        isFocusable = false
        isLongClickable = false
        visibility = View.GONE
    }

    init { refreshStyle() }

    /** The host handles configuration changes itself, so this View can outlive font/density changes. */
    private fun refreshStyle(): Boolean {
        val resources = view.resources
        val metrics = resources.displayMetrics
        val configuration = resources.configuration
        val next = Style(metrics.density, metrics.scaledDensity, configuration.fontScale, configuration.locales.toLanguageTags())
        if (style == next) return false
        style = next
        view.textSize = TEXT_SP.toFloat()
        view.layoutDirection = configuration.layoutDirection
        view.setLineSpacing(dp(LINE_GAP_DP).toFloat(), 1f)
        val verticalPadding = if (compactHeader) COMPACT_VERTICAL_PADDING_DP else VERTICAL_PADDING_DP
        view.setPadding(dp(HORIZONTAL_PADDING_DP), dp(verticalPadding), dp(HORIZONTAL_PADDING_DP), dp(verticalPadding))
        view.compoundDrawablePadding = dp(ICON_GAP_DP)
        val icon = BatteryIcon().apply { setBounds(0, 0,
            dp(if (compactHeader) COMPACT_ICON_WIDTH_DP else ICON_WIDTH_DP),
            dp(if (compactHeader) COMPACT_ICON_HEIGHT_DP else ICON_HEIGHT_DP)) }
        batteryIcon = icon
        view.setCompoundDrawablesRelative(icon, null, null, null)
        view.setShadowLayer(if (transparentHeader) dp(SHADOW_RADIUS_DP).toFloat() else 0f, 0f, dp(SHADOW_OFFSET_DP).toFloat(), Color.BLACK)
        view.background = if (transparentHeader || compactHeader) null else GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(BACKGROUND_START, BACKGROUND_END)).apply {
            cornerRadius = dp(CORNER_DP).toFloat()
            setStroke(dp(BORDER_DP).coerceAtLeast(1), BORDER_COLOR)
        }
        return true
    }

    fun update(eligible: Boolean, viewportWidth: Int, viewportHeight: Int, contentRect: RectF?,
               exclusions: List<RectF>, snapshot: VehicleStatusSnapshot?, reportedExteriorCelsius: Double? = null,
               reportedExteriorAgeMillis: Long? = null) {
        val percent = snapshot?.batteryPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }
        val temperature = reportedExteriorCelsius?.takeIf { it.isFinite() && it in -50.0..80.0 }
        if (!eligible || percent == null && temperature == null) {
            hide(); return
        }
        val styleChanged = refreshStyle()
        val locale = view.resources.configuration.locales[0]
        val formatted = percent?.let { NumberFormat.getPercentInstance(locale).apply {
            maximumFractionDigits = 0
        }.format(it / 100.0) }
        val age = reportedExteriorAgeMillis?.takeIf { temperature != null && it > 60_000L }?.let {
            view.context.getString(R.string.vehicle_reading_received_minutes,
                NumberFormat.getIntegerInstance(locale).format(it / 60_000L))
        }
        val label = listOfNotNull(
            percent?.let { view.context.getString(R.string.vehicle_field_battery) },
            temperature?.let { view.context.getString(R.string.vehicle_field_exterior_reported) }, age).joinToString(" · ")
        val value = listOfNotNull(formatted, temperature?.let { BidiFormatter.getInstance(locale).unicodeWrap(
            NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(temperature) + " °C",
            TextDirectionHeuristicsCompat.LTR) }).joinToString(" · ")
        val icon = batteryIcon?.takeIf { percent != null }
        if (view.compoundDrawablesRelative[0] !== icon) view.setCompoundDrawablesRelative(icon, null, null, null)
        val text = if (compactHeader) listOfNotNull(formatted, temperature?.let {
            view.context.getString(R.string.vehicle_field_outside_short) + " " +
                BidiFormatter.getInstance(locale).unicodeWrap(
                    NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(it) + " °C",
                    TextDirectionHeuristicsCompat.LTR)
        }).joinToString("  ·  ") else if (transparentHeader) "$label · $value" else "$label\n$value"
        val textChanged = view.text.toString() != text
        if (textChanged) view.text = SpannableString(text).apply {
            // The newline belongs to the label's metrics too. Leaving it at the SOC size makes
            // StaticLayout reserve a second large baseline for the small heading.
            if (compactHeader) setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            else {
                setSpan(RelativeSizeSpan(LABEL_SP.toFloat() / TEXT_SP), 0, label.length + if (transparentHeader) 0 else 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(LABEL_COLOR), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.BOLD), label.length + if (transparentHeader) 3 else 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        batteryIcon?.setChargePercent(percent ?: 0.0)
        val description = "$label · $value"
        if (view.contentDescription?.toString() != description) view.contentDescription = description
        if (styleChanged || textChanged || view.measuredWidth <= 0 || view.isLayoutRequested) {
            if (!measureCard()) { hide(); return }
        }
        val rectangle = UltraBatteryCardPlacement.place(viewportWidth, viewportHeight, contentRect,
            exclusions, view.measuredWidth, view.measuredHeight, dp(if (compactHeader) COMPACT_MARGIN_DP else MARGIN_DP),
            topOnly = transparentHeader || compactHeader)
        if (rectangle == null) { hide(); return }
        val existing = view.layoutParams as? FrameLayout.LayoutParams
        if (existing == null || existing.width != rectangle.width() || existing.height != rectangle.height() ||
            existing.leftMargin != rectangle.left || existing.topMargin != rectangle.top ||
            existing.gravity != (Gravity.TOP or Gravity.LEFT)) {
            view.layoutParams = FrameLayout.LayoutParams(rectangle.width(), rectangle.height(), Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = rectangle.left; topMargin = rectangle.top
            }
        }
        if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
    }

    private fun measureCard(): Boolean {
        val padding = view.compoundPaddingLeft + view.compoundPaddingRight
        val rounding = dp(TEXT_ROUNDING_GUARD_DP).coerceAtLeast(1)
        // getDesiredWidth is only an estimate. Native TextView can choose different fallback/font
        // advances for the final styled layout, especially in RTL. Let that real layout size itself.
        var width = ceil(Layout.getDesiredWidth(view.text, TextPaint(view.paint)).toDouble()).toInt() + padding + rounding
        repeat(MAX_MEASURE_PASSES) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val layout = view.layout ?: return false
            val widest = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it).toDouble() } ?: return false
            val needed = ceil(widest).toInt() + padding
            if (layout.lineCount == (if (transparentHeader || compactHeader) 1 else 2) && needed <= view.measuredWidth) return true
            width = if (needed > width) needed + rounding
                // A wrapped heading hides its unwrapped advance; one measured base em gives the
                // next layout room to expose it. This changes width, never the driver's font size.
                else width + ceil(view.paint.textSize.toDouble()).toInt()
        }
        return false
    }

    fun hide() {
        if (view.visibility != View.GONE) view.visibility = View.GONE
        if (view.text.isNotEmpty()) view.text = ""
        if (view.contentDescription != null) view.contentDescription = null
        batteryIcon?.setChargePercent(0.0)
    }

    /** Proportional SOC with one constant colour, never inferred charging or warning states. */
    private class BatteryIcon : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
        private var fraction = 0f
        private var opacity = 255
        fun setChargePercent(percent: Double) {
            val next = (percent / 100.0).toFloat()
            if (fraction != next) { fraction = next; invalidateSelf() }
        }
        override fun draw(canvas: Canvas) {
            val area = RectF(bounds)
            paint.strokeWidth = area.height() / 20
            area.inset(paint.strokeWidth, paint.strokeWidth)
            val cap = area.width() / 12
            val body = RectF(area.left, area.top, area.right - 2 * cap, area.bottom)
            val radius = body.height() / 6
            paint.style = Paint.Style.STROKE; paint.color = ICON_OUTLINE; paint.alpha = opacity
            canvas.drawRoundRect(body, radius, radius, paint)
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(RectF(area.right - cap, area.top + area.height() / 3,
                area.right, area.bottom - area.height() / 3), radius / 2, radius / 2, paint)
            if (fraction > 0f) {
                val fill = RectF(body).apply { inset(body.height() / 6, body.height() / 6) }
                fill.right = fill.left + fill.width() * fraction
                paint.color = ICON_FILL; paint.alpha = opacity
                canvas.drawRoundRect(fill, radius / 2, radius / 2, paint)
            }
        }
        override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Drawable opacity") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val COMPACT_VERTICAL_PADDING_DP = 4
        const val COMPACT_ICON_WIDTH_DP = 24
        const val COMPACT_ICON_HEIGHT_DP = 16
        const val COMPACT_MARGIN_DP = 4
        const val SHADOW_RADIUS_DP = 3
        const val SHADOW_OFFSET_DP = 1
        const val TEXT_SP = 24
        const val LABEL_SP = 14
        const val LINE_GAP_DP = 2
        const val TEXT_ROUNDING_GUARD_DP = 2
        const val MAX_MEASURE_PASSES = 3
        const val HORIZONTAL_PADDING_DP = 16
        const val VERTICAL_PADDING_DP = 6
        const val ICON_WIDTH_DP = 44
        const val ICON_HEIGHT_DP = 30
        const val ICON_GAP_DP = 14
        const val MARGIN_DP = 8
        const val CORNER_DP = 14
        const val BORDER_DP = 1
        val BACKGROUND_START = 0xFF172733.toInt()
        val BACKGROUND_END = 0xFF121B25.toInt()
        val BORDER_COLOR = 0xFF31424E.toInt()
        val LABEL_COLOR = 0xFFB5C8D2.toInt()
        val ICON_OUTLINE = 0xFF8DA9B8.toInt()
        val ICON_FILL = 0xFF65D7DF.toInt()
    }
}
