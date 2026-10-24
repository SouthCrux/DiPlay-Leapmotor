package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import java.text.NumberFormat

/** A local C11 traction-battery fallback, never a substitute for a real map or maneuver. */
internal class ClusterVehicleCardView(context: Context) : FrameLayout(context) {
    internal val reading = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 32f
        setTextColor(Color.WHITE)
        setLineSpacing(12f * resources.displayMetrics.density, 1f)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_LOCALE
        isFocusable = false
    }

    init {
        isFocusable = false
        isClickable = false
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF15252E.toInt(), Color.BLACK))
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        addView(reading, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        visibility = View.GONE
    }

    fun setSnapshot(snapshot: VehicleStatusSnapshot?) {
        if (!valid(snapshot)) {
            reading.text = ""
            contentDescription = null
            visibility = View.GONE
            return
        }
        snapshot!!
        val locale = resources.configuration.locales[0]
        val label = context.getString(R.string.vehicle_field_battery)
        val percent = NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 }
            .format(snapshot.batteryPercent / 100.0)
        val range = context.getString(R.string.turn_card_info_km, snapshot.rangeKm.toDouble())
        reading.text = SpannableString("$label\n$percent\n$range").apply {
            setSpan(RelativeSizeSpan(0.5f), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), label.length + 1, label.length + 1 + percent.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.625f), length - range.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        contentDescription = "$label · $percent · $range"
        visibility = View.VISIBLE
    }

    companion object {
        fun valid(snapshot: VehicleStatusSnapshot?): Boolean = snapshot != null &&
            snapshot.batteryPercent.isFinite() && snapshot.batteryPercent in 0.0..100.0 &&
            snapshot.rangeKm in 0..0xffff
    }
}
