package app.bahaplus.tv.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object TvStyle {
    val background = Color.rgb(16, 26, 36)
    val rail = Color.rgb(24, 43, 55)
    val surface = Color.rgb(28, 46, 60)
    val focus = Color.rgb(101, 212, 197)
    val text = Color.rgb(241, 246, 249)
    val muted = Color.rgb(166, 184, 196)
    val warning = Color.rgb(235, 187, 112)
    fun dp(context: Context, value: Int) = (context.resources.displayMetrics.density * value).toInt()
    fun shape(context: Context, fill: Int, stroke: Int = Color.TRANSPARENT, width: Int = 0, radius: Int = 7) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, radius).toFloat()
            if (width > 0) setStroke(dp(context, width), stroke)
        }
    fun focusBackground(context: Context, fill: Int = surface) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(context, Color.rgb(32, 68, 78), focus, 3))
        addState(intArrayOf(android.R.attr.state_pressed), shape(context, Color.rgb(32, 68, 78), focus, 3))
        addState(intArrayOf(android.R.attr.state_selected), shape(context, Color.rgb(36, 67, 77)))
        addState(intArrayOf(), shape(context, fill))
    }
    fun label(context: Context, value: String, size: Float = 18f, color: Int = text, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = false
        }
    fun button(context: Context, value: String, action: () -> Unit) = label(context, value, 16f, text, true).apply {
        id = View.generateViewId()
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        gravity = Gravity.CENTER
        minHeight = dp(context, 44)
        setPadding(dp(context, 14), dp(context, 9), dp(context, 14), dp(context, 9))
        background = focusBackground(context)
        setOnClickListener { action() }
    }
    fun vertical(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun horizontal(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    fun space(context: Context, width: Int, height: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(context, width), dp(context, height))
    }
    fun matchHeight(height: Int = -1) = LinearLayout.LayoutParams(-1, height)
    fun colorStates() = ColorStateList.valueOf(text)
}
