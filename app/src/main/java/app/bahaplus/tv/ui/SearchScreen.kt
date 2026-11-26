package app.bahaplus.tv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Parcelable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.bahaplus.tv.core.Anime
import app.bahaplus.tv.core.Preferences
import app.bahaplus.tv.data.PosterLoader

/** In-place search view with clear button, local recent-query chips, and RecyclerView results. */
@SuppressLint("ViewConstructor")
internal class SearchScreen(
    context: Context,
    private val preferences: Preferences,
    posters: PosterLoader,
    private val leftTargetId: Int,
    initialDraft: String,
    private val onLeft: () -> Unit,
    private val onDraftChanged: (String) -> Unit,
    private val onSubmit: (String) -> Unit,
    private val onClear: () -> Unit,
    private val onFocusCard: (Int, CatalogRow.Card) -> Unit,
    private val onClickCard: (CatalogRow.Card) -> Unit
) : LinearLayout(context) {
    val input: EditText
    val list: RecyclerView
    private val clearButton: TextView
    private val historyRow: LinearLayout
    private val historyScroll: HorizontalScrollView
    private val adapter: CatalogAdapter
    private val layoutManager: TvGridLayoutManager
    private val emptyPanel: LinearLayout
    private val emptyTitle: TextView
    private val emptySubtitle: TextView
    private var updatingInput = false

    init {
        orientation = VERTICAL
        val row = TvStyle.horizontal(context).apply { gravity = Gravity.CENTER_VERTICAL }
        input = EditText(context).apply {
            id = View.generateViewId()
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(initialDraft)
            setSelection(initialDraft.length)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val value = s?.toString().orEmpty()
                    if (!updatingInput) onDraftChanged(value)
                    updateClearVisibility(value.isNotEmpty())
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            textSize = 18f
            setTextColor(TvStyle.text)
            setHintTextColor(TvStyle.muted)
            hint = tr("输入番剧名称", "輸入動畫名稱")
            background = TvStyle.focusBackground(context)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            nextFocusLeftId = leftTargetId
            setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when {
                    key == KeyEvent.KEYCODE_DPAD_LEFT && selectionStart == 0 && selectionEnd == 0 -> {
                        onLeft(); true
                    }
                    key in intArrayOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER) -> {
                        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                        true
                    }
                    else -> false
                }
            }
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    onSubmit(text.toString())
                    true
                } else false
            }
        }
        clearButton = TvStyle.compactButton(context, tr("清空", "清除")) {
            setDraft("")
            onClear()
            input.requestFocus()
        }
        val searchButton = TvStyle.primaryButton(context, tr("搜索", "搜尋")) {
            onSubmit(input.text.toString())
        }
        row.addView(input, LayoutParams(0, dp(46), 1f))
        row.addView(clearButton, LayoutParams(dp(82), dp(46)).apply { leftMargin = dp(8) })
        row.addView(searchButton, LayoutParams(dp(92), dp(46)).apply { leftMargin = dp(8) })
        addView(row, LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        updateClearVisibility(initialDraft.isNotEmpty())

        historyRow = TvStyle.horizontal(context).apply { gravity = Gravity.CENTER_VERTICAL }
        historyScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(historyRow)
        }
        addView(historyScroll, LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        refreshHistoryChips()

        val body = FrameLayout(context)
        layoutManager = TvGridLayoutManager(context, 4, preferences.lowResources).apply {
            initialPrefetchItemCount = if (preferences.lowResources) 4 else 8
        }
        adapter = CatalogAdapter(
            context = context,
            initialRows = emptyList(),
            posters = posters,
            leftTarget = leftTargetId,
            traditional = preferences.traditional,
            lowResources = preferences.lowResources,
            onLeft = onLeft,
            onFocus = onFocusCard,
            onClick = onClickCard
        )
        layoutManager.spanSizeLookup = adapter.spans()
        list = RecyclerView(context).apply {
            layoutManager = this@SearchScreen.layoutManager
            adapter = this@SearchScreen.adapter
            itemAnimator = null
            setItemViewCacheSize(if (preferences.lowResources) 4 else 8)
            clipToPadding = false
            setPadding(0, 0, 0, dp(14))
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        emptyPanel = TvStyle.vertical(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(24))
        }
        emptyTitle = TvStyle.label(context, "", 22f, bold = true)
        emptySubtitle = TvStyle.label(context, "", 16f, TvStyle.muted).apply {
            setPadding(0, dp(12), 0, dp(18))
        }
        emptyPanel.addView(emptyTitle)
        emptyPanel.addView(emptySubtitle)

        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        body.addView(emptyPanel, FrameLayout.LayoutParams(-1, -1))
        addView(body, LayoutParams(-1, 0, 1f))
    }

    fun setDraft(value: String) {
        if (input.text.toString() == value) return
        updatingInput = true
        input.setText(value)
        input.setSelection(value.length)
        updatingInput = false
        updateClearVisibility(value.isNotEmpty())
    }

    fun refreshHistoryChips() {
        val items = preferences.recentSearches
        historyRow.removeAllViews()
        if (items.isEmpty()) {
            historyScroll.visibility = View.GONE
            return
        }
        historyScroll.visibility = View.VISIBLE
        historyRow.addView(TvStyle.label(context, tr("最近搜索：", "最近搜尋："), 13f, TvStyle.muted).apply {
            setPadding(0, 0, dp(8), 0)
        })
        items.forEachIndexed { idx, term ->
            val chip = TvStyle.compactButton(context, term) {
                setDraft(term)
                onSubmit(term)
            }.apply {
                textSize = 13f
                minHeight = dp(34)
                setPadding(dp(11), dp(5), dp(11), dp(5))
                if (idx == 0) {
                    nextFocusLeftId = leftTargetId
                    setOnKeyListener { _, code, event ->
                        if (code == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                            onLeft(); true
                        } else false
                    }
                }
            }
            historyRow.addView(chip, LayoutParams(-2, dp(34)).apply { rightMargin = dp(6) })
        }
        val clearHistory = TvStyle.compactButton(context, tr("清除历史", "清除紀錄")) {
            preferences.clearSearchHistory()
            refreshHistoryChips()
            input.requestFocus()
        }.apply {
            textSize = 13f
            minHeight = dp(34)
            setTextColor(TvStyle.buttonTextColors(TvStyle.muted))
            setPadding(dp(11), dp(5), dp(11), dp(5))
        }
        historyRow.addView(clearHistory, LayoutParams(-2, dp(34)))
    }

    fun bind(query: String, results: List<Anime>, loading: Boolean, savedState: Parcelable? = null) {
        refreshHistoryChips()
        if (results.isNotEmpty()) {
            emptyPanel.visibility = View.GONE
            list.visibility = View.VISIBLE
            adapter.submit(results.map { CatalogRow.Card(it) }, preferences.lowResources)
            if (savedState != null) layoutManager.onRestoreInstanceState(savedState)
        } else {
            list.visibility = View.GONE
            emptyPanel.visibility = View.VISIBLE
            adapter.submit(emptyList(), preferences.lowResources)
            emptyTitle.text = when {
                loading && query.isNotBlank() -> tr("正在搜索", "正在搜尋")
                query.isBlank() -> tr("从片名开始", "從片名開始")
                else -> tr("没有找到结果", "沒有找到結果")
            }
            emptySubtitle.text = tr("按确认键打开电视键盘，再选择搜索。", "按確認鍵開啟電視鍵盤，再選擇搜尋。")
        }
    }

    fun focusTarget(preferGrid: Boolean, position: Int) {
        if (preferGrid && list.visibility == View.VISIBLE && adapter.itemCount > 0) {
            val clamped = position.coerceIn(0, adapter.itemCount - 1)
            list.post {
                val child = list.findViewHolderForAdapterPosition(clamped)?.itemView
                if (child != null) child.requestFocus()
                else {
                    list.scrollToPosition(clamped)
                    list.post { list.findViewHolderForAdapterPosition(clamped)?.itemView?.requestFocus() }
                }
            }
            return
        }
        input.requestFocus()
    }

    fun saveListState(): Parcelable? =
        if (list.visibility == View.VISIBLE) layoutManager.onSaveInstanceState() else null

    fun dispose() {
        list.adapter = null
    }

    private fun updateClearVisibility(hasText: Boolean) {
        clearButton.isEnabled = hasText
        clearButton.isFocusable = hasText
    }

    private fun tr(simplified: String, traditional: String) = preferences.text(simplified, traditional)
    private fun dp(value: Int) = TvStyle.dp(context, value)
}
