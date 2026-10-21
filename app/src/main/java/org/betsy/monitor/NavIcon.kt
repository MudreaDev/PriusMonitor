package org.betsy.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Small vector navigation glyphs, drawn at the device resolution. */
class NavIcon(
    context: Context,
    private val kind: Int,
    private val tint: Int,
) : View(context) {
    override fun onDraw(c: Canvas) {
        val save = c.save()
        c.translate((width - height) / 2f, 0f)
        c.scale(height / 24f, height / 24f)
        val p =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tint
                style = Paint.Style.STROKE
                strokeWidth = 1.7f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

        fun path(vararg xy: Float) {
            val shape = Path()
            xy.toList().chunked(2).forEachIndexed { i, pair ->
                if (i ==
                    0
                ) {
                    shape.moveTo(pair[0], pair[1])
                } else {
                    shape.lineTo(pair[0], pair[1])
                }
            }
            c.drawPath(shape, p)
        }
        when (kind) {
            0 -> {
                for (x in listOf(4f, 14f)) for (y in listOf(4f, 14f)) c.drawRoundRect(x, y, x + 6, y + 6, 1.5f, 1.5f, p)
            }
            1 -> {
                c.drawRoundRect(4f, 3f, 14f, 21f, 2f, 2f, p)
                c.drawRect(7f, 6f, 11f, 10f, p)
                path(14f, 12f, 17f, 12f, 17f, 18f, 20f, 18f, 20f, 8f, 17f, 5f)
            }
            2 -> {
                c.drawRoundRect(3f, 6f, 20f, 19f, 2f, 2f, p)
                c.drawLine(21f, 10f, 21f, 15f, p)
                path(13f, 8f, 9f, 13f, 13f, 13f, 10f, 17f)
            }
            3 -> {
                c.drawCircle(12f, 12f, 9f, p)
                path(6f, 12f, 9f, 12f, 11f, 7f, 14f, 17f, 16f, 12f, 18f, 12f)
            }
            4 -> {
                path(8f, 6f, 17f, 15f, 12f, 20f, 12f, 4f, 17f, 9f, 8f, 18f)
            }
        }
        c.restoreToCount(save)
    }
}
