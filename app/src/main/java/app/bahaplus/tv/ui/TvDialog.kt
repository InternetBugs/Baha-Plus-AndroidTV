package app.bahaplus.tv.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Theme-matched TV dialogs built on AlertDialog with high-contrast D-pad focus rings.
 */
internal object TvDialog {
    data class Action(
        val label: String,
        val primary: Boolean = false,
        val dismiss: Boolean = true,
        val onClick: () -> Unit = {}
    )

    fun confirm(
        context: Context,
        title: String,
        message: String? = null,
        actions: List<Action>,
        focusIndex: Int = 0,
        onCancel: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ): AlertDialog {
        val body = if (!message.isNullOrBlank()) {
            ScrollView(context).apply {
                isFocusable = false
                addView(TvStyle.label(context, message, 16f, TvStyle.muted).apply {
                    setLineSpacing(TvStyle.dp(context, 4).toFloat(), 1f)
                })
            }
        } else null
        return showCustom(context, title, body, actions, focusIndex, onCancel, onDismiss)
    }

    fun text(
        context: Context,
        title: String,
        body: String,
        actions: List<Action>,
        focusIndex: Int = 0,
        onDismiss: (() -> Unit)? = null
    ): AlertDialog {
        val scroll = ScrollView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(0, 0, TvStyle.dp(context, 6), 0)
            addView(TvStyle.label(context, body, 16f, TvStyle.text).apply {
                setLineSpacing(TvStyle.dp(context, 4).toFloat(), 1f)
            })
        }
        return showCustom(context, title, scroll, actions, focusIndex, onDismiss = onDismiss, maxBodyHeightDp = 300)
    }

    fun choice(
        context: Context,
        title: String,
        items: List<String>,
        selected: Int,
        cancelLabel: String,
        onSelect: (Int) -> Unit,
        onDismiss: (() -> Unit)? = null
    ): AlertDialog {
        val list = TvStyle.vertical(context)
        val scroll = ScrollView(context).apply {
            addView(list, FrameLayout.LayoutParams(-1, -2))
        }
        lateinit var dialog: AlertDialog
        val buttons = ArrayList<TextView>(items.size)
        items.forEachIndexed { index, label ->
            val isSelected = index == selected
            val display = if (isSelected) "✓  $label" else label
            val button = TvStyle.compactButton(context, display) {
                dialog.dismiss()
                onSelect(index)
            }.apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                this.isSelected = isSelected
                if (isSelected) setTextColor(TvStyle.buttonTextColors(TvStyle.focus))
            }
            list.addView(button, LinearLayout.LayoutParams(-1, TvStyle.dp(context, 44)).apply {
                bottomMargin = TvStyle.dp(context, 6)
            })
            buttons += button
        }
        dialog = showCustom(
            context,
            title,
            scroll,
            actions = listOf(Action(cancelLabel)),
            focusIndex = -1,
            onDismiss = onDismiss,
            maxBodyHeightDp = 300
        )
        val initialFocus = buttons.getOrNull(selected) ?: buttons.firstOrNull()
        initialFocus?.post {
            initialFocus.requestFocus()
            scroll.requestChildFocus(list, initialFocus)
        }
        return dialog
    }

    fun showCustom(
        context: Context,
        title: String,
        contentView: View?,
        actions: List<Action>,
        focusIndex: Int = 0,
        onCancel: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        maxBodyHeightDp: Int = 340
    ): AlertDialog {
        val root = TvStyle.vertical(context).apply {
            background = TvStyle.shape(context, TvStyle.background, TvStyle.surface, width = 2, radius = 12)
            setPadding(dp(context, 24), dp(context, 20), dp(context, 24), dp(context, 20))
        }
        root.addView(TvStyle.label(context, title, 21f, TvStyle.text, bold = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        root.addView(View(context).apply {
            setBackgroundColor(TvStyle.divider)
        }, LinearLayout.LayoutParams(-1, dp(context, 1).coerceAtLeast(1)).apply {
            topMargin = dp(context, 12)
            bottomMargin = dp(context, 14)
        })
        if (contentView != null) {
            val maxPx = dp(context, maxBodyHeightDp)
            val holder = object : FrameLayout(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val capped = MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST)
                    super.onMeasure(widthMeasureSpec, capped)
                }
            }
            holder.addView(contentView, FrameLayout.LayoutParams(-1, -2))
            root.addView(holder, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(context, 16)
            })
        }
        val dialog = AlertDialog.Builder(context)
            .setView(root)
            .apply { if (onCancel != null) setOnCancelListener { onCancel() } }
            .create()
        if (onDismiss != null) dialog.setOnDismissListener { onDismiss() }

        val actionButtons = ArrayList<TextView>(actions.size)
        if (actions.isNotEmpty()) {
            val row = TvStyle.horizontal(context).apply { gravity = Gravity.END }
            actions.forEachIndexed { index, action ->
                val click: () -> Unit = {
                    if (action.dismiss) dialog.dismiss()
                    action.onClick()
                }
                val button = if (action.primary) {
                    TvStyle.primaryButton(context, action.label, click)
                } else {
                    TvStyle.button(context, action.label, click)
                }
                row.addView(button, LinearLayout.LayoutParams(-2, dp(context, 44)).apply {
                    if (index > 0) leftMargin = dp(context, 10)
                })
                actionButtons += button
            }
            root.addView(row, LinearLayout.LayoutParams(-1, -2))
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        if (focusIndex in actionButtons.indices) {
            actionButtons[focusIndex].post { actionButtons[focusIndex].requestFocus() }
        }
        return dialog
    }

    private fun dp(context: Context, value: Int) = TvStyle.dp(context, value)
}
