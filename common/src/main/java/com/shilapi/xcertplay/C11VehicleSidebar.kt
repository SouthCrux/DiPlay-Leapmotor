package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.text.BidiFormatter
import androidx.core.text.TextDirectionHeuristicsCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorQnxTireState.Wheel
import java.text.NumberFormat
import kotlin.math.roundToInt

/** Local readouts occupy their own viewport; they never cover CarPlay or forward touches to it. */
internal class C11VehicleSidebar(context: Context) : ScrollView(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(PADDING_DP), dp(PADDING_DP), dp(PADDING_DP), dp(PADDING_DP))
    }
    private fun field(label: Int, icon: Int): TextView {
        if (column.childCount > 0) column.addView(View(context).apply {
            setBackgroundColor(DIVIDER_COLOR)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, dp(DIVIDER_HEIGHT_DP)).apply {
            topMargin = dp(BLOCK_GAP_DP)
            bottomMargin = dp(BLOCK_GAP_DP)
        })
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(block, LinearLayout.LayoutParams(-1, -2))
        block.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(ICON_COLOR)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(ICON_SIZE_DP), dp(ICON_SIZE_DP)).apply {
                marginEnd = dp(ICON_GAP_DP)
            })
            addView(TextView(context).apply {
                text = context.getString(label)
                setTextColor(SECONDARY_COLOR)
                textSize = LABEL_SP
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-2, -2))
        return TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = VALUE_SP
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, dp(VALUE_GAP_DP), 0, 0)
            block.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }
    private val battery = field(R.string.c11_sidebar_battery, R.drawable.ic_c11_sidebar_battery)
    private val range = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = RANGE_SP
        gravity = Gravity.CENTER
        compoundDrawablePadding = dp(RANGE_ICON_GAP_DP)
        val icon = context.getDrawable(R.drawable.ic_c11_sidebar_range)!!.mutate().apply {
            setTint(SECONDARY_COLOR)
            setBounds(0, 0, dp(RANGE_ICON_SIZE_DP), dp(RANGE_ICON_SIZE_DP))
        }
        setCompoundDrawablesRelative(icon, null, null, null)
        (battery.parent as LinearLayout).addView(this, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(RANGE_GAP_DP)
        })
    }
    private val consumption = TextView(context).apply {
        setTextColor(SECONDARY_COLOR)
        textSize = LABEL_SP
        gravity = Gravity.CENTER
        setPadding(0, dp(VALUE_GAP_DP), 0, 0)
        (battery.parent as LinearLayout).addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private val exterior = field(R.string.vehicle_field_outside_short, R.drawable.ic_c11_sidebar_temperature)
    private val tyres = field(R.string.c11_sidebar_tyres, R.drawable.ic_c11_sidebar_tyres).apply { textSize = TYRE_VALUE_SP }
    private val gate = field(R.string.c11_sidebar_gate, R.drawable.ic_c11_sidebar_gate).apply {
        textSize = LABEL_SP
        setText(R.string.c11_gate_double_click)
    }
    private val gateBlock = gate.parent as View
    private val gateDivider = column.getChildAt(column.childCount - 2)

    init {
        visibility = GONE
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        setBackgroundColor(Color.rgb(17, 24, 31))
        addView(column, LayoutParams(-1, -2))
        update(null, null)
    }

    fun update(snapshot: VehicleStatusSnapshot?, exteriorCelsius: Double?, pressures: Map<Wheel, Int?> = emptyMap(),
        tireTemperaturesCelsius: Map<Wheel, Int?> = emptyMap(), currentTripConsumption: Double? = null,
        gateReady: Boolean = false, gateStatus: com.shilapi.xcertplay.vehicle.C11RefossGate.Status? = null) {
        val configured = gateReady || gateStatus != null
        gateBlock.visibility = if (configured) VISIBLE else GONE
        gateDivider.visibility = if (configured) VISIBLE else GONE
        gate.setText(when (gateStatus) {
            com.shilapi.xcertplay.vehicle.C11RefossGate.Status.WAITING_CARPLAY -> R.string.c11_gate_waiting_carplay
            com.shilapi.xcertplay.vehicle.C11RefossGate.Status.CHECKING -> R.string.c11_gate_checking
            com.shilapi.xcertplay.vehicle.C11RefossGate.Status.WAITING_BUTTON -> R.string.c11_gate_waiting_button
            com.shilapi.xcertplay.vehicle.C11RefossGate.Status.NO_WIFI -> R.string.c11_gate_no_wifi
            com.shilapi.xcertplay.vehicle.C11RefossGate.Status.UNREACHABLE -> R.string.c11_gate_unreachable
            com.shilapi.xcertplay.vehicle.C11RefossGate.Status.SENDING -> R.string.c11_gate_sending
            else -> R.string.c11_gate_double_click
        })
        val locale = resources.configuration.locales[0]
        val missing = context.getString(R.string.c11_sidebar_no_data)
        fun TextView.value(next: String) { if (text.toString() != next) text = next }
        val batteryValue = snapshot?.batteryPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }?.let {
            NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 }.format(it / 100.0)
        } ?: missing
        val rangeValue = snapshot?.rangeKm?.takeIf { it >= 0 }?.let {
            BidiFormatter.getInstance(locale).unicodeWrap(context.getString(R.string.c11_sidebar_range_km,
                NumberFormat.getIntegerInstance(locale).format(it)), TextDirectionHeuristicsCompat.LTR)
        }
        battery.value(batteryValue)
        battery.contentDescription = batteryValue
        range.value(rangeValue.orEmpty())
        range.visibility = if (rangeValue == null) GONE else VISIBLE
        range.contentDescription = rangeValue?.let { context.getString(R.string.c11_sidebar_range_description, it) }
        val consumptionValue = currentTripConsumption?.takeIf { it.isFinite() && it >= 0.0 }?.let {
            context.getString(R.string.c11_sidebar_trip_consumption,
                NumberFormat.getNumberInstance(locale).apply {
                    minimumFractionDigits = 1
                    maximumFractionDigits = 1
                }.format(it))
        }
        consumption.value(consumptionValue.orEmpty())
        consumption.visibility = if (consumptionValue == null) GONE else VISIBLE
        exterior.value(exteriorCelsius?.takeIf { it.isFinite() && it in -50.0..80.0 }?.let {
            BidiFormatter.getInstance(locale).unicodeWrap(
                NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(it) + " °C",
                TextDirectionHeuristicsCompat.LTR)
        } ?: missing)
        val pressureValues = Wheel.entries.map { wheel ->
            pressures[wheel]?.takeIf { it in 0..4094 }?.let { NumberFormat.getIntegerInstance(locale).format(it) }
        }
        val temperatureValues = Wheel.entries.map { wheel ->
            tireTemperaturesCelsius[wheel]?.takeIf { it in -50..150 }
                ?.let { NumberFormat.getIntegerInstance(locale).format(it) }
        }
        val hasTemperatures = temperatureValues.any { it != null }
        if (pressureValues.all { it == null } && !hasTemperatures) {
            tyres.value(missing)
            tyres.contentDescription = missing
        } else {
            val front = context.getString(R.string.c11_sidebar_tyres_front)
            val rear = context.getString(R.string.c11_sidebar_tyres_rear)
            fun row(left: Int, right: Int) = BidiFormatter.getInstance(locale).unicodeWrap(
                "${pressureValues[left] ?: "—"}   ${pressureValues[right] ?: "—"}", TextDirectionHeuristicsCompat.LTR)
            fun temperatureRow(left: Int, right: Int) = BidiFormatter.getInstance(locale).unicodeWrap(
                "${temperatureValues[left] ?: "—"} °C   ${temperatureValues[right] ?: "—"} °C",
                TextDirectionHeuristicsCompat.LTR)
            val text = SpannableStringBuilder().apply {
                fun smallLine(value: String, newline: Boolean = true) {
                    val start = length
                    append(value)
                    if (newline) append('\n')
                    setSpan(RelativeSizeSpan(0.55f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                smallLine(front)
                append(row(0, 1)).append('\n')
                if (hasTemperatures) smallLine(temperatureRow(0, 1))
                smallLine(rear)
                append(row(2, 3))
                if (hasTemperatures) {
                    append('\n')
                    smallLine(temperatureRow(2, 3), newline = false)
                }
            }
            if (tyres.text.toString() != text.toString()) tyres.text = text
            tyres.contentDescription = context.getString(R.string.c11_sidebar_tyres_description,
                pressureValues[0] ?: missing, pressureValues[1] ?: missing,
                pressureValues[2] ?: missing, pressureValues[3] ?: missing) + if (hasTemperatures) {
                ". " + context.getString(R.string.c11_sidebar_tyre_temperatures_description,
                    temperatureValues[0] ?: missing, temperatureValues[1] ?: missing,
                    temperatureValues[2] ?: missing, temperatureValues[3] ?: missing)
            } else ""
        }
    }

    companion object {
        private const val WIDTH_DP = 184
        private const val PADDING_DP = 12
        private const val VALUE_GAP_DP = 6
        private const val BLOCK_GAP_DP = 18
        private const val DIVIDER_HEIGHT_DP = 1
        private const val ICON_SIZE_DP = 22
        private const val ICON_GAP_DP = 8
        private const val RANGE_ICON_SIZE_DP = 18
        private const val RANGE_ICON_GAP_DP = 6
        private const val RANGE_GAP_DP = 4
        private const val LABEL_SP = 16f
        private const val VALUE_SP = 28f
        private const val RANGE_SP = 20f
        private const val TYRE_VALUE_SP = 24f
        private val ICON_COLOR = Color.rgb(147, 220, 198)
        private val SECONDARY_COLOR = Color.rgb(167, 182, 195)
        private val DIVIDER_COLOR = Color.rgb(44, 57, 68)
        fun widthPixels(resources: Resources, windowWidth: Int): Int =
            (WIDTH_DP * resources.displayMetrics.density * resources.configuration.fontScale.coerceAtLeast(1f))
                .roundToInt().coerceIn(0, windowWidth.coerceAtLeast(0) / 4)
    }
}
