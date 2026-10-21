package org.betsy.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Locale
import kotlin.math.max

class LineChart(
    context: Context,
    private val values: List<Float?>,
    private val accent: Int,
    private val caption: String,
) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val d = resources.displayMetrics.density
        val left = 8f * d
        val right = width - 8f * d
        val top = 16f * d
        val bottom = height - 28f * d
        p.strokeWidth = d
        p.color = Color.rgb(38, 55, 72)
        for (i in 0..3) c.drawLine(left, top + (bottom - top) * i / 3, right, top + (bottom - top) * i / 3, p)
        val finite = values.filterNotNull().filter { it.isFinite() }
        if (finite.size < 2) {
            p.color = Color.rgb(137, 159, 180)
            p.textSize = 13 * resources.displayMetrics.scaledDensity
            c.drawText("Aștept date pentru grafic", left, (top + bottom) / 2, p)
            return
        }
        val min = minOf(0f, finite.min())
        val high = max(min + 1, finite.max() * 1.1f)
        val path = Path()
        var started = false
        values.forEachIndexed { i, value ->
            if (value == null || !value.isFinite()) {
                started = false
            } else {
                val x = left + (right - left) * i / max(1, values.size - 1)
                val y = bottom - (value - min) / (high - min) * (bottom - top)
                if (started) path.lineTo(x, y) else path.moveTo(x, y)
                started = true
            }
        }
        p.color = accent
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.5f * d
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        p.textSize = 11 * resources.displayMetrics.scaledDensity
        p.color = Color.rgb(137, 159, 180)
        c.drawText(caption, left, height - 5 * d, p)
        p.textAlign = Paint.Align.RIGHT
        c.drawText(String.format(Locale.getDefault(), "%.1f", high), right, top - 4 * d, p)
        p.textAlign = Paint.Align.LEFT
    }
}

class HvChart(
    context: Context,
    private val volts: List<Float>,
) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        if (volts.isEmpty()) return
        val min = volts.min() - .08f
        val max = volts.max() + .08f
        val slot = width / volts.size.toFloat()
        val bottom = height - 25 * d
        volts.forEachIndexed { i, v ->
            val h = ((v - min) / (max - min)) * (bottom - 24 * d)
            p.color = if (i == volts.indices.minByOrNull { volts[it] }) Color.rgb(255, 196, 107) else Color.rgb(69, 229, 193)
            c.drawRoundRect(i * slot + 3 * d, bottom - h, (i + 1) * slot - 3 * d, bottom, 3 * d, 3 * d, p)
            p.color = Color.rgb(137, 159, 180)
            p.textSize = 10 * resources.displayMetrics.scaledDensity
            p.textAlign = Paint.Align.CENTER
            c.drawText("${i + 1}", (i + .5f) * slot, height - 7 * d, p)
        }
        p.textAlign = Paint.Align.LEFT
    }
}
