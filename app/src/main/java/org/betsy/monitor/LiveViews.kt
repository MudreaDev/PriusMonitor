package org.betsy.monitor

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

class MonitorButton(
    context: Context,
    private var action: () -> Unit,
    private var primary: Boolean,
) : Button(context) {
    init {
        setOnClickListener { action() }
    }

    fun updateFrom(other: MonitorButton) {
        action = other.action
        if (primary != other.primary) {
            primary = other.primary
            background = other.background
        }
    }
}

/** Keep attached controls and accessibility focus when only telemetry values change. */
object LiveViews {
    fun compatible(
        old: View,
        fresh: View,
    ): Boolean {
        if (old.javaClass != fresh.javaClass) return false
        if (old is ViewGroup && fresh is ViewGroup) {
            return old.childCount == fresh.childCount &&
                (0 until old.childCount).all {
                    compatible(old.getChildAt(it), fresh.getChildAt(it))
                }
        }
        return true
    }

    fun update(
        old: View,
        fresh: View,
    ) {
        if (old.contentDescription != fresh.contentDescription) old.contentDescription = fresh.contentDescription
        old.isEnabled = fresh.isEnabled
        old.visibility = fresh.visibility
        when {
            old is TextView && fresh is TextView -> {
                if (old.text.toString() != fresh.text.toString()) old.text = fresh.text
                if (old.currentTextColor != fresh.currentTextColor) old.setTextColor(fresh.textColors)
                if (old.textSize != fresh.textSize) old.setTextSize(TypedValue.COMPLEX_UNIT_PX, fresh.textSize)
                if (old.typeface != fresh.typeface) old.typeface = fresh.typeface
                if (old is MonitorButton && fresh is MonitorButton) old.updateFrom(fresh)
            }
            old is LineChart && fresh is LineChart -> old.updateFrom(fresh)
            old is HvChart && fresh is HvChart -> old.updateFrom(fresh)
            old is ViewGroup && fresh is ViewGroup ->
                (0 until old.childCount).forEach {
                    update(old.getChildAt(it), fresh.getChildAt(it))
                }
        }
    }
}
