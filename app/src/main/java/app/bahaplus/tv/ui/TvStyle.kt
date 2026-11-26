package app.bahaplus.tv.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView

object TvStyle {
    val background = Color.rgb(16, 26, 36)
    val rail = Color.rgb(24, 43, 55)
    val surface = Color.rgb(28, 46, 60)
    val surfaceDisabled = Color.rgb(20, 33, 44)
    val primaryFill = Color.rgb(27, 86, 92)
    val focusFill = Color.rgb(32, 68, 78)
    val selectedFill = Color.rgb(36, 67, 77)
    val focus = Color.rgb(101, 212, 197)
    val text = Color.rgb(241, 246, 249)
    val muted = Color.rgb(166, 184, 196)
    val disabledText = Color.rgb(104, 122, 136)
    val warning = Color.rgb(235, 187, 112)
    val badgeFill = Color.argb(220, 24, 66, 74)
    val subtleBadgeFill = Color.argb(190, 32, 50, 64)
    val divider = Color.argb(64, 166, 184, 196)

    fun dp(context: Context, value: Int) = (context.resources.displayMetrics.density * value).toInt()

    fun shape(context: Context, fill: Int, stroke: Int = Color.TRANSPARENT, width: Int = 0, radius: Int = 7) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, radius).toFloat()
            if (width > 0) setStroke(dp(context, width), stroke)
        }

    fun focusBackground(context: Context, fill: Int = surface, radius: Int = 7) = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), shape(context, surfaceDisabled, radius = radius))
        addState(intArrayOf(android.R.attr.state_focused), shape(context, focusFill, focus, 3, radius))
        addState(intArrayOf(android.R.attr.state_pressed), shape(context, focusFill, focus, 3, radius))
        addState(intArrayOf(android.R.attr.state_selected), shape(context, selectedFill, radius = radius))
        addState(intArrayOf(), shape(context, fill, radius = radius))
    }

    fun railItemBackground(context: Context) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(context, focusFill, focus, 3, 7))
        addState(intArrayOf(android.R.attr.state_pressed), shape(context, focusFill, focus, 3, 7))
        val selectedCard = shape(context, selectedFill, radius = 7)
        val indicator = shape(context, focus, radius = 2)
        val selectedLayer = LayerDrawable(arrayOf(selectedCard, indicator)).apply {
            setLayerGravity(1, Gravity.START or Gravity.CENTER_VERTICAL)
            setLayerWidth(1, dp(context, 3))
            setLayerHeight(1, dp(context, 22))
            setLayerInsetStart(1, dp(context, 3))
        }
        addState(intArrayOf(android.R.attr.state_selected), selectedLayer)
        addState(intArrayOf(), shape(context, rail, radius = 7))
    }

    fun buttonTextColors(defaultColor: Int = text): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(disabledText, defaultColor)
    )

    fun bottomScrim(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0x000D1720, 0xCC0D1720.toInt(), 0xF20D1720.toInt())
    )

    fun roundCorners(view: View, radiusDp: Int = 5) {
        val radiusPx = dp(view.context, radiusDp).toFloat()
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
        view.clipToOutline = true
    }

    fun focusScale(view: View, focused: Boolean, lowResources: Boolean) {
        val target = if (focused && !lowResources) 1.03f else 1f
        if (lowResources) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.animate().scaleX(target).scaleY(target).setDuration(110).start()
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
        setTextColor(buttonTextColors())
        setPadding(dp(context, 14), dp(context, 9), dp(context, 14), dp(context, 9))
        background = focusBackground(context)
        setOnClickListener { action() }
    }

    fun primaryButton(context: Context, value: String, action: () -> Unit) = button(context, value, action).apply {
        background = focusBackground(context, primaryFill)
    }

    fun compactButton(context: Context, value: String, action: () -> Unit) = button(context, value, action).apply {
        textSize = 15f
        minHeight = dp(context, 40)
        setPadding(dp(context, 12), dp(context, 7), dp(context, 12), dp(context, 7))
    }

    fun badge(context: Context, value: String, textColor: Int = focus, fill: Int = badgeFill) =
        label(context, value, 12f, textColor, bold = true).apply {
            background = shape(context, fill, radius = 12)
            setPadding(dp(context, 9), dp(context, 4), dp(context, 9), dp(context, 4))
        }

    fun vertical(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun horizontal(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
}
