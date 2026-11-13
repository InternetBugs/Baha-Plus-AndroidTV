package app.bahaplus.tv.player

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.bahaplus.tv.R
import app.bahaplus.tv.core.DanmakuSettings
import app.bahaplus.tv.core.Preferences
import app.bahaplus.tv.ui.TvStyle

/** Inline controls, inserted immediately below the player's progress bar. */
@SuppressLint("ViewConstructor") // Built in code with the playback's preferences and callbacks.
internal class DanmakuControls(
    context: Context,
    private val preferences: Preferences,
    private val onChange: (DanmakuSettings) -> Unit,
    private val onRetry: () -> Unit,
    private val onInteraction: () -> Unit
) : LinearLayout(context) {
    private var settings = preferences.danmaku
    private val toggle: TextView
    private val expand: TextView
    private val size: TextView
    private val opacity: TextView
    private val area: TextView
    private val rolling: TextView
    private val top: TextView
    private val bottom: TextView
    private val panel = TvStyle.vertical(context).apply { visibility = GONE }
    private val message = TvStyle.label(context, "", 13f, TvStyle.muted).apply {
        setPadding(TvStyle.dp(context, 6), TvStyle.dp(context, 5), 0, TvStyle.dp(context, 5))
        visibility = GONE
    }
    private val retry = TvStyle.button(context, tr("重试弹幕", "重試彈幕")) { onRetry(); onInteraction() }
        .apply { visibility = GONE }
    val expanded: Boolean get() = panel.visibility == VISIBLE

    init {
        orientation = VERTICAL
        val row = TvStyle.horizontal(context)
        toggle = button(row) { change(settings.copy(enabled = !settings.enabled)) }
        expand = button(row) {
            panel.visibility = if (expanded) GONE else VISIBLE
            updateLabels()
        }
        row.addView(retry, LayoutParams(0, -2, 1f).apply { setMargins(3, 0, 3, 0) })
        addView(row)
        addView(message)
        val appearance = TvStyle.horizontal(context)
        size = button(appearance) { change(settings.copy(scale = next(listOf(.75f, 1f, 1.25f), settings.scale))) }
        opacity = button(appearance) { change(settings.copy(opacity = next(listOf(40, 60, 80, 100), settings.opacity))) }
        area = button(appearance) { change(settings.copy(area = next(listOf(50, 75, 100), settings.area))) }
        panel.addView(appearance, LayoutParams(-1, -2).apply { topMargin = TvStyle.dp(context, 5) })
        val modes = TvStyle.horizontal(context)
        rolling = button(modes) { change(settings.copy(rolling = !settings.rolling)) }
        top = button(modes) { change(settings.copy(top = !settings.top)) }
        bottom = button(modes) { change(settings.copy(bottom = !settings.bottom)) }
        panel.addView(modes, LayoutParams(-1, -2).apply { topMargin = TvStyle.dp(context, 5) })
        addView(panel)
        updateLabels()
    }

    private fun button(row: LinearLayout, action: () -> Unit): TextView =
        TvStyle.button(context, "") { action(); onInteraction() }.also {
            row.addView(it, LayoutParams(0, -2, 1f).apply { setMargins(3, 0, 3, 0) })
        }

    private fun change(value: DanmakuSettings) {
        settings = value
        preferences.danmaku = value
        updateLabels()
        onChange(value)
    }

    private fun updateLabels() {
        toggle.text = label("弹幕", "彈幕", enabled(settings.enabled))
        expand.text = if (expanded) tr("收起弹幕设置", "收起彈幕設定") else tr("弹幕设置", "彈幕設定")
        size.text = label("字号", "字級", when (settings.scale) {
            .75f -> tr("小", "小"); 1.25f -> tr("大", "大"); else -> tr("标准", "標準")
        })
        opacity.text = label("不透明度", "不透明度", "${settings.opacity}%")
        area.text = label("显示范围", "顯示範圍", when (settings.area) {
            50 -> tr("上半屏", "上半屏"); 75 -> tr("上方 ¾", "上方 ¾"); else -> tr("全屏", "全螢幕")
        })
        rolling.text = label("滚动", "捲動", enabled(settings.rolling))
        top.text = label("顶部", "頂部", enabled(settings.top))
        bottom.text = label("底部", "底部", enabled(settings.bottom))
    }

    fun loading() {
        message.text = tr("正在加载弹幕…", "正在載入彈幕…")
        message.visibility = VISIBLE
        if (retry.hasFocus()) toggle.requestFocus()
        retry.visibility = GONE
    }

    fun loaded(count: Int) {
        message.text = tr("本集暂无弹幕", "本集暫無彈幕")
        message.visibility = if (count == 0) VISIBLE else GONE
        retry.visibility = GONE
    }

    fun failed() {
        message.text = tr("弹幕加载失败，视频可继续播放", "彈幕載入失敗，影片可繼續播放")
        message.visibility = VISIBLE
        retry.visibility = VISIBLE
    }

    fun collapse() {
        if (!expanded) return
        if (panel.hasFocus()) expand.requestFocus()
        panel.visibility = GONE
        updateLabels()
    }

    private fun enabled(value: Boolean) = if (value) tr("开", "開") else tr("关", "關")
    private fun label(simplified: String, traditional: String, value: String) =
        context.getString(R.string.danmaku_setting_value, tr(simplified, traditional), value)
    private fun <T> next(values: List<T>, value: T) = values[(values.indexOf(value) + 1) % values.size]
    private fun tr(simplified: String, traditional: String) = preferences.text(simplified, traditional)
}
