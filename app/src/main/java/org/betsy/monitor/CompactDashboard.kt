package org.betsy.monitor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

data class CompactMetric(
    val title: String,
    val value: String,
    val unit: String,
)

/** A bounded native grid, never inside a ScrollView. All essential readings fit together. */
class CompactDashboard(
    context: Context,
    onConnection: () -> Unit,
) : LinearLayout(context) {
    private val accent = Color.rgb(69, 229, 193)
    private val ink = Color.rgb(237, 245, 251)
    private val muted = Color.rgb(147, 168, 186)
    private val grid = LinearLayout(context).apply { orientation = VERTICAL }
    private val state = text(8, 12, muted)
    private val footer = text(7, 11, muted).apply { maxLines = 2 }
    private val cells = mutableListOf<Triple<TextView, TextView, TextView>>()
    private var columns = 3
    private var readings = emptyList<CompactMetric>()

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val title =
            LinearLayout(context).apply {
                orientation = VERTICAL
                addView(text(12, 17, accent).apply { text = "LIVE · Prius Monitor" }, LayoutParams(-1, 0, 1f))
                addView(state, LayoutParams(-1, 0, 1f))
            }
        top.addView(title, LayoutParams(0, -1, 1f))
        top.addView(
            MonitorButton(context, onConnection, false).apply {
                text = "OBD"
                isAllCaps = false
                textSize = 12f
                setTextColor(accent)
                contentDescription = "Deschide conexiunea OBD"
                minimumWidth = 0
                minWidth = 0
                setPadding(0, 0, 0, 0)
            },
            LayoutParams(dp(64), -1),
        )
        addView(top, LayoutParams(-1, dp(42)))
        addView(grid, LayoutParams(-1, 0, 1f))
        addView(footer, LayoutParams(-1, dp(28)))
        rebuild()
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
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        setAutoSizeTextTypeUniformWithConfiguration(min, max, 1, TypedValue.COMPLEX_UNIT_SP)
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        val next = if (w > h) 5 else 3
        if (next != columns) {
            columns = next
            // Rebuild after the current layout pass so newly added rows are measured.
            post { rebuild() }
        }
    }

    private fun rebuild() {
        grid.removeAllViews()
        cells.clear()
        repeat(15 / columns) { rowIndex ->
            val row = LinearLayout(context)
            repeat(columns) { columnIndex ->
                val tile =
                    LinearLayout(context).apply {
                        orientation = VERTICAL
                        setPadding(dp(7), dp(5), dp(7), dp(5))
                        background =
                            GradientDrawable().apply {
                                setColor(Color.rgb(17, 30, 44))
                                cornerRadius = dp(12).toFloat()
                            }
                    }
                val label = text(7, 12, muted)
                val value =
                    text(10, 38, if (cells.size in setOf(0, 1, 2, 4)) accent else ink).apply {
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    }
                val unit = text(7, 11, muted)
                tile.addView(label, LayoutParams(-1, 0, 1f))
                tile.addView(value, LayoutParams(-1, 0, 2f))
                tile.addView(unit, LayoutParams(-1, 0, 1f))
                cells += Triple(label, value, unit)
                row.addView(tile, LayoutParams(0, -1, 1f).apply { if (columnIndex > 0) leftMargin = dp(5) })
            }
            grid.addView(row, LayoutParams(-1, 0, 1f).apply { topMargin = if (rowIndex == 0) dp(4) else dp(5) })
        }
        updateCells()
    }

    fun update(
        metrics: List<CompactMetric>,
        connection: String,
        note: String,
    ) {
        require(metrics.size == 15)
        readings = metrics
        state.text = connection
        footer.text = note
        updateCells()
    }

    private fun updateCells() {
        readings.forEachIndexed { i, metric ->
            cells.getOrNull(i)?.let { (label, value, unit) ->
                if (label.text.toString() != metric.title) label.text = metric.title
                if (value.text.toString() != metric.value) value.text = metric.value
                if (unit.text.toString() != metric.unit) unit.text = metric.unit
                (label.parent as LinearLayout).contentDescription = "${metric.title}: ${metric.value} ${metric.unit}"
            }
        }
    }
}
