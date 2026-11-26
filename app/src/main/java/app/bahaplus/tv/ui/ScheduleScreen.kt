package app.bahaplus.tv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Parcelable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.bahaplus.tv.core.Anime
import app.bahaplus.tv.core.AppStrings
import app.bahaplus.tv.core.Preferences
import app.bahaplus.tv.core.UpdateSchedule
import app.bahaplus.tv.data.PosterLoader

/** In-place weekly schedule view with weekday picker, favorite/status badges, and focus memory. */
@SuppressLint("ViewConstructor")
internal class ScheduleScreen(
    context: Context,
    private val preferences: Preferences,
    posters: PosterLoader,
    private val leftTargetId: Int,
    initialDay: Int,
    private val onLeft: () -> Unit,
    private val onDaySelected: (Int) -> Unit,
    private val onFocusRow: (Int, Int, Anime) -> Unit,
    private val onOpenAnime: (Anime) -> Unit,
    private val onRetry: () -> Unit
) : LinearLayout(context) {
    private val dayButtons = HashMap<Int, TextView>()
    private val adapter: ScheduleAdapter
    private val layoutManager = LinearLayoutManager(context).apply {
        initialPrefetchItemCount = if (preferences.lowResources) 4 else 8
    }
    val list: RecyclerView
    private val emptyPanel: LinearLayout
    private val emptyTitle: TextView
    private val emptySubtitle: TextView
    private val retryButton: TextView
    var selectedDay: Int = initialDay
        private set

    init {
        orientation = VERTICAL
        val today = UpdateSchedule.weekday()
        if (selectedDay !in 0..6) selectedDay = today
        val picker = TvStyle.horizontal(context)
        val order = intArrayOf(1, 2, 3, 4, 5, 6, 0)
        order.forEachIndexed { idx, day ->
            val label = AppStrings.weekday(preferences.traditional, day) +
                if (day == today) tr(" 今天", " 今天") else ""
            val button = TvStyle.compactButton(context, label) {
                if (selectedDay != day) {
                    selectedDay = day
                    onDaySelected(day)
                }
            }.apply {
                if (idx == 0) {
                    nextFocusLeftId = leftTargetId
                    setOnKeyListener { _, code, event ->
                        if (code == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                            onLeft(); true
                        } else false
                    }
                }
            }
            picker.addView(button, LayoutParams(-2, dp(42)).apply { rightMargin = dp(6) })
            dayButtons[day] = button
        }
        addView(picker, LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val body = FrameLayout(context)
        adapter = ScheduleAdapter(
            context = context,
            initialItems = emptyList(),
            posters = posters,
            leftTarget = leftTargetId,
            traditional = preferences.traditional,
            timeFallback = tr("待定", "待定"),
            onLeft = onLeft,
            onFocus = { pos, anime -> onFocusRow(selectedDay, pos, anime) },
            onClick = onOpenAnime
        )
        list = RecyclerView(context).apply {
            layoutManager = this@ScheduleScreen.layoutManager
            adapter = this@ScheduleScreen.adapter
            itemAnimator = null
            setItemViewCacheSize(if (preferences.lowResources) 8 else 16)
            clipToPadding = false
            setPadding(0, 0, 0, dp(14))
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        emptyPanel = TvStyle.vertical(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(24))
            visibility = View.GONE
        }
        emptyTitle = TvStyle.label(context, "", 22f, bold = true)
        emptySubtitle = TvStyle.label(context, "", 16f, TvStyle.muted).apply {
            setPadding(0, dp(12), 0, dp(18))
        }
        retryButton = TvStyle.button(context, tr("重试", "重試"), onRetry).apply {
            nextFocusLeftId = leftTargetId
        }
        emptyPanel.addView(emptyTitle)
        emptyPanel.addView(emptySubtitle)
        emptyPanel.addView(retryButton, LayoutParams(dp(120), -2))

        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        body.addView(emptyPanel, FrameLayout.LayoutParams(-1, -1))
        addView(body, LayoutParams(-1, 0, 1f))
    }

    fun bind(
        day: Int,
        catalog: List<Anime>,
        catalogLoading: Boolean,
        favoriteIds: Set<String>,
        savedState: Parcelable? = null
    ) {
        val today = UpdateSchedule.weekday()
        selectedDay = if (day in 0..6) day else today
        dayButtons.forEach { (key, button) ->
            val active = key == selectedDay
            button.isSelected = active
            button.setTextColor(TvStyle.buttonTextColors(if (active) TvStyle.focus else TvStyle.text))
        }
        val items = if (catalog.isEmpty()) emptyList() else UpdateSchedule.day(catalog, selectedDay)
        if (items.isEmpty()) {
            list.visibility = View.GONE
            emptyPanel.visibility = View.VISIBLE
            emptyTitle.text = when {
                catalog.isEmpty() && catalogLoading -> tr("正在读取时间表", "正在讀取時間表")
                catalog.isEmpty() -> tr("时间表暂不可用", "時間表暫時無法使用")
                else -> tr("本日暂无更新", "本日暫無更新")
            }
            emptySubtitle.text = when {
                catalog.isEmpty() && catalogLoading -> tr("正在读取官方更新信息。", "正在讀取官方更新資訊。")
                catalog.isEmpty() -> tr("请检查网络后刷新。", "請檢查網路後重新整理。")
                else -> tr("切换其他星期查看，或稍后刷新。", "切換其他星期查看，或稍後重新整理。")
            }
            val showRetry = catalog.isEmpty() && !catalogLoading
            retryButton.visibility = if (showRetry) View.VISIBLE else View.GONE
            adapter.submit(emptyList(), favoriteIds, selectedDay == today, UpdateSchedule.taipeiMinutesOfDay())
        } else {
            emptyPanel.visibility = View.GONE
            list.visibility = View.VISIBLE
            adapter.submit(items, favoriteIds, selectedDay == today, UpdateSchedule.taipeiMinutesOfDay())
            if (savedState != null) layoutManager.onRestoreInstanceState(savedState)
        }
    }

    fun saveListState(): Parcelable? =
        if (list.visibility == View.VISIBLE) layoutManager.onSaveInstanceState() else null

    fun focusTarget(preferList: Boolean, position: Int) {
        if (preferList && list.visibility == View.VISIBLE && adapter.itemCount > 0) {
            val clamped = position.coerceIn(0, adapter.itemCount - 1)
            list.post {
                val holder = list.findViewHolderForAdapterPosition(clamped)?.itemView
                if (holder != null) holder.requestFocus()
                else {
                    list.scrollToPosition(clamped)
                    list.post { list.findViewHolderForAdapterPosition(clamped)?.itemView?.requestFocus() }
                }
            }
            return
        }
        dayButtons[selectedDay]?.requestFocus()
    }

    fun dispose() {
        list.adapter = null
    }

    private fun tr(simplified: String, traditional: String) = preferences.text(simplified, traditional)
    private fun dp(value: Int) = TvStyle.dp(context, value)
}
