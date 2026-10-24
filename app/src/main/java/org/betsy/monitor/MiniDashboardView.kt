package org.betsy.monitor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Three large readings, sized to the actual Android picture-in-picture window. */
class MiniDashboardView(
    context: Context,
) : LinearLayout(context) {
    private val accent = Color.rgb(69, 229, 193)
    private val ink = Color.rgb(237, 245, 251)
    private val muted = Color.rgb(147, 168, 186)
    private val speed = text(18, 48, accent)
    private val speedUnit = text(8, 13, muted)
    private val average = text(12, 25, ink)
    private val live = text(12, 25, accent)
    private val liveUnit = text(7, 11, muted)
    private val state = text(7, 10, muted)

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(8, 15, 25))
        setPadding(dp(9), dp(5), dp(9), dp(5))
        val readings = LinearLayout(context)
        val velocity =
            LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                addView(text(7, 10, muted).apply { text = "VITEZĂ" }, LayoutParams(-1, 0, 1f))
                addView(speed, LayoutParams(-1, 0, 4f))
                addView(speedUnit, LayoutParams(-1, 0, 1f))
            }
        readings.addView(velocity, LayoutParams(0, -1, 1f))
        val consumption =
            LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(8), 0, 0, 0)
                addView(text(7, 10, muted).apply { text = "CONSUM MEDIU" }, LayoutParams(-1, 0, 1f))
                addView(average, LayoutParams(-1, 0, 2f))
                addView(text(7, 10, muted).apply { text = "l/100 km" }, LayoutParams(-1, 0, 1f))
                addView(text(7, 10, muted).apply { text = "CONSUM LIVE" }, LayoutParams(-1, 0, 1f))
                addView(live, LayoutParams(-1, 0, 2f))
                addView(liveUnit, LayoutParams(-1, 0, 1f))
            }
        readings.addView(consumption, LayoutParams(0, -1, 1.3f))
        addView(readings, LayoutParams(-1, 0, 1f))
        addView(state, LayoutParams(-1, dp(13)))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun text(
        min: Int,
        max: Int,
        tint: Int,
    ) = TextView(context).apply {
        setTextColor(tint)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setAutoSizeTextTypeUniformWithConfiguration(min, max, 1, TypedValue.COMPLEX_UNIT_SP)
    }

    fun update(readings: MiniMonitorReadings) {
        speed.text = readings.speed
        speedUnit.text = readings.speedUnit
        average.text = readings.average
        live.text = readings.live
        liveUnit.text = readings.liveUnit
        state.text = "PRIUS · ${readings.source}"
        speed.contentDescription = "Viteză: ${readings.speed} ${readings.speedUnit}"
        average.contentDescription = "Consum mediu: ${readings.average} l/100 km"
        live.contentDescription = "Consum live: ${readings.live} ${readings.liveUnit}"
    }
}
