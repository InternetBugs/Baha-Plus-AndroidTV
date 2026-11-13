package app.bahaplus.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.webkit.WebViewCompat
import app.bahaplus.tv.core.*
import app.bahaplus.tv.data.*
import app.bahaplus.tv.experiment.NativeApiClient
import app.bahaplus.tv.diagnostics.Diagnostics
import app.bahaplus.tv.player.NativePlayback
import app.bahaplus.tv.ui.CatalogAdapter
import app.bahaplus.tv.ui.CatalogRow
import app.bahaplus.tv.ui.DiagnosticsView
import app.bahaplus.tv.ui.QrLoginView
import app.bahaplus.tv.ui.TvStyle
import app.bahaplus.tv.ui.TvGridLayoutManager
import org.json.JSONObject

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var preferences: Preferences
    private lateinit var posters: PosterLoader
    private lateinit var root: FrameLayout
    private lateinit var shell: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var rail: LinearLayout
    private lateinit var accountButton: TextView
    private lateinit var accountLabel: TextView
    private lateinit var notice: TextView
    private lateinit var playerLayer: FrameLayout
    private var loginWebView: WebView? = null
    private var portalInstance: OfficialPortal? = null
    private val portal: OfficialPortal get() = checkNotNull(portalInstance)
    private var portalAvailable = false
    private var loginOpening = false
    private val navigation = linkedMapOf<String, TextView>()
    private var tab = "home"
    private var detailsOpen = false
    private var selectedAnime: Anime? = null
    private var currentDetail: AnimeDetail? = null
    private var native: NativePlayback? = null
    private var playingVideo = ""
    private var playingDetail: AnimeDetail? = null
    private var autoStartVideo: String? = null
    private var searchQuery = ""
    private var searchDraft = ""
    private var searchResults = emptyList<Anime>()
    private var catalog = emptyList<Anime>()
    private var favorites = emptyList<Anime>()
    private var history = emptyList<WatchEntry>()
    private var accountId: String? = null
    private var accountName = ""
    private var accountSynced = false
    private var lastError = ""
    private var busy = true
    private val detailLoader = AnimeDetailLoader(deliver = { handler.post(it) })
    private val catalogLoader = NativeTaskLoader { handler.post(it) }
    private val searchLoader = NativeTaskLoader { handler.post(it) }
    private val accountLoader = NativeTaskLoader { handler.post(it) }
    private val favoriteLoader = NativeTaskLoader { handler.post(it) }
    @Volatile private var accountRevision = 0
    @Volatile private var activityDestroyed = false
    private var detailLoading = false
    private var detailFailed = false
    private var favoritePending = false
    private var playbackErrorVisible = false
    private var grid: RecyclerView? = null
    private var gridStateKey: String? = null
    private var lastCardPosition = 0
    private var detailFocus = 0
    private var episodePosition = 0
    private var episodeGrid: RecyclerView? = null
    private var settingsFocus = "resources"
    private var settingsScrollY = 0
    private var settingsScroll: ScrollView? = null
    private val gridStates = mutableMapOf<String, Parcelable?>()
    private val focusedKeys = mutableMapOf<String, String>()
    private val detailCache = object : LinkedHashMap<String, AnimeDetail>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnimeDetail>) = size > 6
    }
    private var primaryContent: View? = null
    private var activeDialog: AlertDialog? = null
    private var qrLoginView: QrLoginView? = null
    private var diagnosticsView: DiagnosticsView? = null
    private var diagnosticPhase = ""
    private lateinit var updateChecker: GitHubUpdateChecker
    private var updateChecking = false
    private var manualUpdateCheck = false
    private var updateCheckButton: TextView? = null
    private var pendingUpdateResult: UpdateCheckResult? = null
    private var updateUiResumed = false
    private val presentUpdateResult = Runnable { showPendingUpdateResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = Preferences(this)
        Diagnostics.record("app.created")
        posters = PosterLoader(this, preferences.lowResources)
        updateChecker = GitHubUpdateChecker(BuildConfig.VERSION_NAME)
        tab = savedInstanceState?.getString("tab")?.takeIf { it in setOf("home", "search", "favorites", "history", "settings") } ?: "home"
        searchQuery = savedInstanceState?.getString("query").orEmpty().take(200)
        searchDraft = searchQuery
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { back() }
        }
        root = FrameLayout(this).apply { setBackgroundColor(TvStyle.background) }
        setContentView(root)
        playerLayer = FrameLayout(this).apply { visibility = View.GONE }
        buildShell()
        root.addView(playerLayer, FrameLayout.LayoutParams(-1, -1))
        loadCatalog()
        syncAccount()
        if (tab == "search" && searchQuery.isNotBlank()) submitSearch(searchQuery)
        render()
        checkForUpdates(manual = false)
    }

    private fun buildShell() {
        if (::shell.isInitialized) root.removeView(shell)
        shell = TvStyle.horizontal(this)
        rail = TvStyle.vertical(this).apply {
            setBackgroundColor(TvStyle.rail)
            setPadding(dp(16), dp(26), dp(12), dp(22))
        }
        val brand = TvStyle.label(this, "Baha Plus", 23f, bold = true)
        val brandTv = TvStyle.label(this, "TV", 14f, TvStyle.focus, true).apply {
            setPadding(0, dp(4), 0, dp(24))
        }
        rail.addView(brand)
        rail.addView(brandTv)
        navigation.clear()
        val labels = listOf("home" to tr("首页", "首頁"), "search" to tr("搜索", "搜尋"),
            "favorites" to tr("追番", "追番"), "history" to tr("历史", "歷史"), "settings" to tr("设置", "設定"))
        for ((key, label) in labels) {
            val button = TvStyle.button(this, label) { changeTab(key) }.apply {
                gravity = Gravity.CENTER_VERTICAL
                textSize = 18f
                background = TvStyle.focusBackground(this@MainActivity, TvStyle.rail)
                setOnKeyListener { _, code, event ->
                    if (code == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                        focusContent()
                        true
                    } else false
                }
            }
            navigation[key] = button
            rail.addView(button, LinearLayout.LayoutParams(-1, dp(49)).apply { bottomMargin = dp(7) })
        }
        rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        accountLabel = TvStyle.label(this, "", 13f, TvStyle.muted).apply { maxLines = 2 }
        accountButton = TvStyle.button(this, "", ::accountAction).apply { textSize = 15f }
        rail.addView(accountLabel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        rail.addView(accountButton, LinearLayout.LayoutParams(-1, -2))
        shell.addView(rail, LinearLayout.LayoutParams(dp(148), -1))
        content = TvStyle.vertical(this).apply { setPadding(dp(24), dp(25), dp(26), dp(18)) }
        shell.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(shell, 0, FrameLayout.LayoutParams(-1, -1))
        updateAccount()
    }

    private fun createPortal(ready: () -> Unit) {
        if (portalAvailable) { ready(); return }
        runCatching {
            if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
            val webView = WebView(this).apply {
                alpha = .01f
                isFocusable = false
                isFocusableInTouchMode = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            loginWebView = webView
            root.addView(webView, FrameLayout.LayoutParams(1, 1))
            portalInstance = OfficialPortal(webView, ::onOfficialEvent, ::onRendererGone)
            portalAvailable = true
            portal.initialize(ready)
        }.onFailure {
            loginOpening = false
            portalAvailable = false
            val failedPortal = portalInstance
            val failedBrowser = loginWebView
            failedBrowser?.let { root.removeView(it) }
            loginWebView = null
            portalInstance = null
            runCatching { if (failedPortal != null) failedPortal.destroy() else failedBrowser?.destroy() }
            closeQrLogin()
            render()
            showNotice(tr("系统 WebView 不可用，无法登录；仍可浏览和播放。", "系統 WebView 無法使用，無法登入；仍可瀏覽及播放。"))
        }
    }

    private fun releasePortal() {
        loginOpening = false
        if (!portalAvailable) return
        portalAvailable = false
        loginWebView?.let { root.removeView(it) }
        val previous = portalInstance
        portalInstance = null
        loginWebView = null
        previous?.destroy()
    }

    private fun changeTab(key: String) {
        Diagnostics.record("ui.tab", "screen" to key)
        if (detailsOpen) {
            detailLoader.cancel()
            autoStartVideo = null
            detailsOpen = false
        }
        saveGrid()
        tab = key
        render(restoreFocus = false)
        if ((key == "history" || key == "favorites") && accountId != null) syncAccount()
    }
    private fun render(restoreFocus: Boolean = true) {
        if (!::content.isInitialized || native != null || playingVideo.isNotEmpty() ||
            qrLoginView != null || diagnosticsView != null) return
        val restoreContentFocus = restoreFocus && content.hasFocus()
        shell.visibility = View.VISIBLE
        playerLayer.visibility = View.GONE
        saveGrid()
        settingsScroll?.let { settingsScrollY = it.scrollY }
        // Recycle old cards now so their pending poster work is cancelled.
        grid?.adapter = null
        content.removeAllViews()
        grid = null
        episodeGrid = null
        settingsScroll = null
        updateCheckButton = null
        gridStateKey = null
        primaryContent = null
        navigation.forEach { (key, view) -> view.isSelected = key == tab }
        updateAccount()
        if (detailsOpen) {
            renderDetail()
            if (restoreContentFocus) content.post { focusContent() }
            return
        }
        when (tab) {
            "search" -> renderSearch()
            "settings" -> renderSettings()
            "favorites" -> {
                heading(tr("我的追番", "我的追番"), if (accountId == null) tr("登录后查看官方追番", "登入後查看官方追番")
                    else if (accountSynced) tr("已从官方同步", "已從官方同步") else tr("等待官方同步", "等待官方同步"))
                if (accountId == null) emptyAccount()
                else if (favorites.isEmpty()) empty(tr("还没有追番", "還沒有追番"), tr("在番剧详情中添加追番，或刷新官方收藏。", "在動畫詳情中加入追番，或重新整理官方收藏。"), ::refresh)
                else renderGrid(favorites.map { CatalogRow.Card(it) })
            }
            "history" -> {
                heading(tr("观看历史", "觀看紀錄"), tr("续播位置以官方记录为准", "續播位置以官方紀錄為準"))
                if (accountId == null) emptyAccount()
                else if (history.isEmpty()) empty(tr("暂时没有观看记录", "暫時沒有觀看紀錄"), tr("播放后刷新，读取官方保存的观看记录。", "播放後重新整理，讀取官方保存的觀看紀錄。"), ::refresh)
                else renderGrid(history.map { CatalogRow.Card(it.anime, it) })
            }
            else -> {
                heading(tr("今天想看什么？", "今天想看什麼？"), tr("新番与热门动画", "新番與熱門動畫"))
                if (catalog.isEmpty()) {
                    empty(if (busy) tr("正在连接动画疯", "正在連線動畫瘋") else tr("首页暂不可用", "首頁暫時無法使用"),
                        if (busy) tr("正在读取官方接口。", "正在讀取官方介面。")
                        else tr("请检查网络后重试。", "請檢查網路後重試。"), ::refresh)
                } else {
                    val rows = mutableListOf<CatalogRow>()
                    if (history.isNotEmpty()) {
                        rows += CatalogRow.Heading(tr("继续观看", "繼續觀看"))
                        rows += history.take(8).map { CatalogRow.Card(it.anime, it) }
                    }
                    rows += CatalogRow.Heading(tr("本季与热门", "本季與熱門"))
                    rows += catalog.map { CatalogRow.Card(it) }
                    renderGrid(rows)
                }
            }
        }
        if (restoreContentFocus) content.post { focusContent(preferGrid = tab != "search") }
    }

    private fun heading(title: String, subtitle: String) {
        content.addView(TvStyle.label(this, title, 28f, bold = true))
        notice = TvStyle.label(this, subtitle, 14f, TvStyle.muted).apply {
            maxLines = 2
            setPadding(0, dp(8), 0, dp(13))
        }
        content.addView(notice, LinearLayout.LayoutParams(-1, -2))
    }
    private fun empty(title: String, subtitle: String, retry: (() -> Unit)? = null): LinearLayout {
        val panel = TvStyle.vertical(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(26), dp(14), dp(26), dp(26))
        }
        panel.addView(TvStyle.label(this, title, 22f, bold = true))
        panel.addView(TvStyle.label(this, subtitle, 17f, TvStyle.muted).apply {
            setPadding(0, dp(14), 0, dp(20))
        })
        if (retry != null) {
            val button = TvStyle.button(this, tr("重试", "重試"), retry)
            panel.addView(button, LinearLayout.LayoutParams(dp(120), -2))
            primaryContent = button
        }
        content.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))
        return panel
    }
    private fun emptyAccount() {
        empty(tr("连接你的巴哈姆特账号", "連接你的巴哈姆特帳號"),
            tr("登录后查看追番和观看历史。", "登入後查看追番和觀看紀錄。"))
        val button = TvStyle.button(this, tr("登录账号", "登入帳號"), ::login)
        content.addView(button, LinearLayout.LayoutParams(dp(180), -2))
        primaryContent = button
    }
    private fun renderGrid(rows: List<CatalogRow>) {
        val list = RecyclerView(this)
        val manager = TvGridLayoutManager(this, 4, preferences.lowResources).apply {
            initialPrefetchItemCount = if (preferences.lowResources) 4 else 8
        }
        val adapter = CatalogAdapter(this, rows, posters, navigation[tab]!!.id,
            onLeft = { navigation[tab]?.requestFocus() },
            onFocus = { position, card ->
                lastCardPosition = position
                gridStateKey?.let { focusedKeys[it] = card.key }
            },
            onClick = { card -> openDetail(card.anime, card.history?.videoId) })
        manager.spanSizeLookup = adapter.spans()
        list.layoutManager = manager
        list.adapter = adapter
        list.itemAnimator = null
        list.setItemViewCacheSize(if (preferences.lowResources) 4 else 8)
        list.clipToPadding = false
        list.setPadding(0, 0, 0, dp(14))
        list.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        grid = list
        val key = gridKey()
        gridStateKey = key
        gridStates[key]?.let(manager::onRestoreInstanceState)
        val focused = focusedKeys[key]
        lastCardPosition = rows.indexOfFirst { it is CatalogRow.Card && it.key == focused }
            .takeIf { it >= 0 } ?: rows.indexOfFirst { it is CatalogRow.Card }.coerceAtLeast(0)
        primaryContent = list
    }
    private fun gridKey() = if (tab == "search") "search:$searchQuery" else tab
    private fun saveGrid() {
        if (detailsOpen) return
        val key = gridStateKey ?: return
        grid?.layoutManager?.onSaveInstanceState()?.also { gridStates[key] = it }
    }
    private fun focusContent(preferGrid: Boolean = false) {
        if (detailsOpen) {
            val episodes = episodeGrid
            if (detailFocus == 3 && episodes != null && (episodes.adapter?.itemCount ?: 0) > 0) {
                val position = episodePosition.coerceIn(0, episodes.adapter!!.itemCount - 1)
                episodes.scrollToPosition(position)
                episodes.post {
                    episodes.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                        ?: episodes.post { episodes.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
                }
            } else primaryContent?.requestFocus()
            return
        }
        if (tab == "search" && !detailsOpen && !preferGrid) { primaryContent?.requestFocus(); return }
        val list = grid
        if (list == null) { primaryContent?.requestFocus(); return }
        val position = lastCardPosition.coerceIn(0, (list.adapter?.itemCount ?: 1) - 1)
        list.post {
            val child = list.findViewHolderForAdapterPosition(position)?.itemView
            if (child != null) child.requestFocus()
            else {
                list.scrollToPosition(position)
                list.post { list.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
            }
        }
    }

    private fun renderSearch() {
        heading(tr("搜索动画", "搜尋動畫"), tr("输入片名，支持简繁中文", "輸入片名，支援簡繁中文"))
        val row = TvStyle.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply {
            id = View.generateViewId()
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(searchDraft)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { searchDraft = s.toString() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            textSize = 19f
            setTextColor(TvStyle.text)
            setHintTextColor(TvStyle.muted)
            hint = tr("输入番剧名称", "輸入動畫名稱")
            background = TvStyle.focusBackground(this@MainActivity)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnKeyListener { _, key, event ->
                if (key in intArrayOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER) && event.action == KeyEvent.ACTION_DOWN) {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                    true
                } else false
            }
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    submitSearch(text.toString())
                    true
                } else false
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(TvStyle.button(this, tr("搜索", "搜尋")) { submitSearch(input.text.toString()) },
            LinearLayout.LayoutParams(dp(92), dp(48)).apply { leftMargin = dp(12) })
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        primaryContent = input
        if (searchResults.isNotEmpty()) {
            renderGrid(searchResults.map { CatalogRow.Card(it) })
            primaryContent = input
        } else {
            empty(if (busy && searchQuery.isNotBlank()) tr("正在搜索", "正在搜尋")
                else if (searchQuery.isBlank()) tr("从片名开始", "從片名開始") else tr("没有找到结果", "沒有找到結果"),
                tr("按确认键打开电视键盘，再选择搜索。", "按確認鍵開啟電視鍵盤，再選擇搜尋。"))
        }
    }
    private fun submitSearch(raw: String) {
        Diagnostics.record("ui.search")
        val query = raw.trim().codePoints().limit(100).toArray().let { String(it, 0, it.size) }
        if (query.isBlank()) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0)
        searchQuery = query
        searchDraft = query
        searchResults = emptyList()
        busy = true
        render()
        val queries = listOf(query, preferences.toTraditional(query)).distinct()
        searchLoader.load(OfficialHttpSession(), { NativeCatalogApi(it).search(queries) }) { result ->
            if (searchQuery != query) return@load
            busy = false
            result.onSuccess { searchResults = it }.onFailure { nativeRequestError(it) }
            if (!detailsOpen && tab == "search") {
                render()
                if (searchResults.isNotEmpty()) focusContent(preferGrid = true)
            }
        }
    }

    private fun openDetail(anime: Anime, resumeVideo: String? = null) {
        Diagnostics.record("ui.detail")
        detailLoader.cancel()
        saveGrid()
        detailsOpen = true
        selectedAnime = anime
        currentDetail = detailCache[anime.id]
        autoStartVideo = resumeVideo
        detailFocus = 0
        episodePosition = 0
        detailLoading = currentDetail == null
        detailFailed = false
        render()
        focusContent()
        if (resumeVideo != null && currentDetail?.episodes?.any { it.id == resumeVideo } == true) {
            play(resumeVideo)
            return
        }
        requestDetail(anime, resumeVideo)
    }
    private fun requestDetail(anime: Anime, resumeVideo: String? = autoStartVideo) {
        detailLoading = true
        detailFailed = false
        render()
        val knownVideo = anime.latestVideoId.ifEmpty {
            (catalog + searchResults + history.map { it.anime }).firstOrNull {
                it.id == anime.id && it.latestVideoId.isNotEmpty()
            }?.latestVideoId.orEmpty()
        }
        Diagnostics.record("detail.request")
        detailLoader.load(anime.copy(latestVideoId = knownVideo), resumeVideo) { result ->
            if (isFinishing || isDestroyed || !detailsOpen || playingVideo.isNotEmpty() || selectedAnime?.id != anime.id) {
                return@load
            }
            detailLoading = false
            result.fold(onSuccess = { detail ->
                Diagnostics.record("detail.completed", "count" to detail.episodes.size)
                detailCache[detail.animeId] = detail
                currentDetail = detail
                render()
                val video = autoStartVideo
                autoStartVideo = null
                if (video != null) play(video)
            }, onFailure = { error ->
                detailFailed = true
                lastError = (error as? AnimeDetailException)?.code ?: "detail-invalid"
                Diagnostics.record("detail.failed", "code" to lastError)
                render()
                if (currentDetail != null) showNotice(tr("详情更新失败，请稍后重试。", "詳情更新失敗，請稍後重試。"))
            })
        }
    }
    private fun renderDetail() {
        val anime = selectedAnime ?: return
        val detail = currentDetail
        heading(detail?.title ?: anime.title, if (detail == null && detailFailed) tr("详情读取失败", "詳情讀取失敗")
            else if (detail == null) tr("正在读取番剧信息", "正在讀取動畫資訊")
            else tr("${detail.episodes.size} 集 · 官方内容", "${detail.episodes.size} 集 · 官方內容"))
        if (detail == null) {
            val placeholder = empty(if (detailFailed) tr("暂时无法读取详情", "暫時無法讀取詳情")
                else tr("正在加载详情", "正在載入詳情"),
                if (detailFailed) tr("请检查网络连接后重试。", "請檢查網路連線後重試。")
                else tr("正在读取官方简介与集数。", "正在讀取官方簡介與集數。"),
                if (detailLoading) null else ({ requestDetail(anime) }))
            if (detailLoading) primaryContent = placeholder.apply { isFocusable = true }
            return
        }
        val header = TvStyle.horizontal(this)
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(TvStyle.rail)
        }
        posters.load(image, detail.poster ?: anime.cover)
        header.addView(image, LinearLayout.LayoutParams(dp(240), dp(136)).apply { rightMargin = dp(20) })
        val info = TvStyle.vertical(this)
        val description = TvStyle.label(this, detail.description.ifBlank { tr("暂无简介", "暫無簡介") }, 15f, TvStyle.muted).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        info.addView(description)
        if (detail.metadata.isNotEmpty()) info.addView(TvStyle.label(this,
            detail.metadata.take(2).joinToString("  ") { "${it.first} ${it.second}" }.take(200), 13f, TvStyle.muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(7), 0, dp(8))
        })
        val actions = TvStyle.horizontal(this)
        val playButton = TvStyle.button(this, tr("开始播放", "開始播放")) {
            detail.episodes.firstOrNull()?.let { play(it.id) }
        }.apply {
            isEnabled = detail.episodes.isNotEmpty()
            setOnFocusChangeListener { _, focused -> if (focused) detailFocus = 0 }
        }
        val favoriteButton = TvStyle.button(this, favoriteLabel(anime.id)) { toggleFavorite(anime.id) }.apply {
            setOnFocusChangeListener { _, focused -> if (focused) detailFocus = 1 }
        }
        val introButton = TvStyle.button(this, tr("完整简介", "完整簡介")) {
            showText(detail.title, detail.description.ifBlank { tr("暂无简介", "暫無簡介") })
        }.apply { setOnFocusChangeListener { _, focused -> if (focused) detailFocus = 2 } }
        actions.addView(playButton, LinearLayout.LayoutParams(-2, -2))
        actions.addView(favoriteButton, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        actions.addView(introButton, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        info.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        header.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(header)
        content.addView(TvStyle.label(this, tr("选集", "選集"), 21f, bold = true).apply { setPadding(0, dp(18), 0, dp(10)) })
        if (detail.episodes.isEmpty()) {
            empty(tr("暂未读取到可播放集数", "暫未讀取到可播放集數"), tr("请刷新详情后重试。", "請重新整理詳情後重試。")) {
                requestDetail(anime)
            }
        } else {
            val episodes = RecyclerView(this).apply {
                layoutManager = TvGridLayoutManager(this@MainActivity, 6, preferences.lowResources)
                itemAnimator = null
                adapter = EpisodeAdapter(detail.episodes)
                setItemViewCacheSize(6)
            }
            content.addView(episodes, LinearLayout.LayoutParams(-1, 0, 1f))
            episodeGrid = episodes
        }
        primaryContent = listOf(playButton, favoriteButton, introButton)[detailFocus.coerceIn(0, 2)]
    }
    private inner class EpisodeAdapter(private val episodes: List<Episode>) : RecyclerView.Adapter<EpisodeAdapter.Holder>() {
        inner class Holder(val button: TextView) : RecyclerView.ViewHolder(button)
        override fun getItemCount() = episodes.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            TvStyle.button(this@MainActivity, "") {}.apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = RecyclerView.LayoutParams(-1, dp(48)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val episode = episodes[position]
            holder.button.text = episode.label
            holder.button.isSelected = episode.id == playingVideo
            holder.button.nextFocusLeftId = if (position % 6 == 0) navigation[tab]!!.id else View.NO_ID
            holder.button.setOnKeyListener { _, keyCode, event ->
                val index = holder.bindingAdapterPosition
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN &&
                    index != RecyclerView.NO_POSITION && index % 6 == 0) {
                    navigation[tab]?.requestFocus()
                    true
                } else false
            }
            holder.button.setOnClickListener { play(episode.id) }
            holder.button.setOnFocusChangeListener { _, focused ->
                val index = holder.bindingAdapterPosition
                if (focused && index != RecyclerView.NO_POSITION) {
                    detailFocus = 3
                    episodePosition = index
                }
            }
        }
    }
    private fun toggleFavorite(id: String) {
        if (accountId == null) { login(); return }
        if (favoritePending) return
        val anime = selectedAnime?.takeIf { it.id == id } ?: return
        val owner = accountId ?: return
        val desired = favorites.none { it.id == id }
        accountLoader.cancel()
        favoritePending = true
        favoriteLoader.load(accountSession(), { NativeAccountApi(it).favorite(anime, desired, owner) }) { result ->
            favoritePending = false
            result.onSuccess {
                favorites = if (desired) (favorites + anime).distinctBy { it.id } else favorites.filterNot { it.id == id }
                detailFocus = 1
                render()
                syncAccount()
            }.onFailure { nativeRequestError(it) }
        }
        showNotice(tr("正在更新追番", "正在更新追番"))
    }
    private fun favoriteLabel(id: String) = if (favorites.any { it.id == id }) tr("取消追番", "取消追番") else tr("加入追番", "加入追番")

    private fun play(videoId: String) {
        val detail = currentDetail ?: return
        if (detail.episodes.none { it.id == videoId }) return
        detailLoader.cancel()
        detailLoading = false
        Diagnostics.record("player.request")
        diagnosticPhase = ""
        activeDialog?.dismiss()
        native?.release()
        native = null
        playerLayer.removeAllViews()
        playingVideo = videoId
        playingDetail = detail
        autoStartVideo = null
        playbackErrorVisible = false
        shell.visibility = View.GONE
        playerLayer.visibility = View.VISIBLE
        val current = playingVideo
        val revision = accountRevision
        val session = NativeApiClient(OfficialHttpSession.snapshotCookies(),
            isCurrent = { !activityDestroyed && accountRevision == revision }) { stage, status ->
            if (status != null && status in 100..599) {
                Diagnostics.record("player.http", "phase" to stage, "httpStatus" to status)
            }
        }
        val candidate = NativePlayback(this, preferences, session, detail, current,
            onActive = {
                Diagnostics.record("player.active")
                if (playingVideo == current) {
                    shell.visibility = View.GONE
                    playerLayer.visibility = View.VISIBLE
                }
            },
            onEpisode = { next -> play(next) },
            onEnd = { if (playingVideo == current) ended() },
            onExit = ::exitPlayback,
            onError = ::playbackError)
        native = candidate
        playerLayer.addView(candidate.view, FrameLayout.LayoutParams(-1, -1))
    }
    private fun ended() {
        Diagnostics.record("player.ended", "autoNext" to preferences.autoNext)
        val detail = playingDetail ?: return
        val index = detail.episodes.indexOfFirst { it.id == playingVideo }
        val next = detail.episodes.getOrNull(index + 1)
        if (preferences.autoNext && index >= 0 && next != null) {
            play(next.id)
            return
        }
        activeDialog = AlertDialog.Builder(this).setTitle(tr("本集播放结束", "本集播放結束"))
            .setPositiveButton(tr("重播", "重播")) { _, _ -> play(playingVideo) }
            .setNeutralButton(tr("选集", "選集")) { _, _ -> chooseEndedEpisode() }
            .setNegativeButton(tr("返回详情", "返回詳情")) { _, _ -> exitPlayback() }
            .setOnCancelListener { exitPlayback() }.show()
    }
    private fun chooseEndedEpisode() {
        val detail = playingDetail ?: return
        activeDialog = AlertDialog.Builder(this).setTitle(tr("选集", "選集"))
            .setItems(detail.episodes.map { it.label }.toTypedArray()) { _, index -> play(detail.episodes[index].id) }
            .setNegativeButton(tr("返回详情", "返回詳情")) { _, _ -> exitPlayback() }.show()
    }
    private fun playbackError(code: String) {
        Diagnostics.record("player.error", "code" to code)
        if (isFinishing || playbackErrorVisible || playingVideo.isEmpty()) return
        playbackErrorVisible = true
        lastError = code.take(80)
        native?.pauseForLifecycle()
        val message = when (code) {
            "parent-verification-required" -> tr("当前账号要求家长验证，接口未获准播放。请检查官方账号的家长设置后重试。", "目前帳號要求家長驗證，介面未獲准播放。請檢查官方帳號的家長設定後重試。")
            "verification-required" -> tr("官方要求额外验证，请完成登录验证后重试。", "官方要求額外驗證，請完成登入驗證後重試。")
            "short-ad-needs-fallback" -> tr("当前广告未完成所需播放时长，请重试。", "目前廣告未完成所需播放時長，請重試。")
            else -> tr("官方播放请求未完成，请检查网络、地区或账号状态后重试。", "官方播放請求未完成，請檢查網路、地區或帳號狀態後重試。")
        }
        activeDialog = AlertDialog.Builder(this).setTitle(tr("播放暂时无法继续", "播放暫時無法繼續"))
            .setMessage(message).setPositiveButton(tr("重试", "重試")) { _, _ -> play(playingVideo) }
            .setNegativeButton(tr("返回详情", "返回詳情")) { _, _ -> exitPlayback() }
            .setOnCancelListener { exitPlayback() }.show()
    }

    private fun exitPlayback() {
        if (playingVideo.isEmpty()) return
        Diagnostics.record("player.exit")
        activeDialog?.dismiss()
        activeDialog = null
        native?.release()
        native = null
        playerLayer.removeAllViews()
        playingVideo = ""
        playingDetail = null
        playbackErrorVisible = false
        detailsOpen = true
        render()
        focusContent()
        syncAccount()
    }

    private fun login() {
        if (loginOpening || qrLoginView != null) return
        loginOpening = true
        Diagnostics.record("account.login")
        retireAccountRequests()
        closeQrLogin()
        createPortal {
            loginOpening = false
            qrLoginView = QrLoginView(this, preferences,
                onRefresh = { portal.refreshQrLogin() },
                onCancel = ::cancelQrLogin)
            root.addView(qrLoginView, FrameLayout.LayoutParams(-1, -1))
            portal.qrLogin()
        }
    }

    private fun closeQrLogin() {
        qrLoginView?.let { view -> view.dispose(); root.removeView(view) }
        qrLoginView = null
        handler.post(presentUpdateResult)
    }
    private fun cancelQrLogin() {
        closeQrLogin()
        releasePortal()
        syncAccount()
        render()
        if (detailsOpen) focusContent() else navigation[tab]?.requestFocus()
    }
    private fun accountAction() {
        if (accountId == null) login()
        else activeDialog = AlertDialog.Builder(this).setTitle(tr("当前账号", "目前帳號"))
            .setMessage(accountName).setPositiveButton(tr("刷新同步", "重新同步")) { _, _ ->
                syncAccount()
            }.setNegativeButton(tr("退出登录", "登出")) { _, _ ->
                retireAccountRequests()
                val clear = {
                    clearAccount()
                    releasePortal()
                    render()
                }
                if (portalAvailable) portal.logout(clear) else OfficialPortal.logout(clear)
            }.setNeutralButton(tr("返回", "返回"), null).show()
    }
    private fun updateAccount() {
        if (!::accountButton.isInitialized) return
        accountLabel.text = if (accountId == null) tr("访客浏览", "訪客瀏覽") else accountName
        accountButton.text = if (accountId == null) tr("登录账号", "登入帳號") else tr("账号", "帳號")
    }
    private fun refresh() {
        Diagnostics.record("ui.refresh")
        if (detailsOpen) { selectedAnime?.let { requestDetail(it) }; return }
        loadCatalog()
        syncAccount()
        if (tab == "search" && searchQuery.isNotBlank()) submitSearch(searchQuery)
        render()
    }

    private fun loadCatalog() {
        busy = true
        catalogLoader.load(OfficialHttpSession(), { NativeCatalogApi(it).catalog() }) { result ->
            busy = false
            result.onSuccess { catalog = it }
            if (!detailsOpen && tab == "home") render()
            result.exceptionOrNull()?.let(::nativeRequestError)
        }
    }

    private fun accountSession(cookies: Map<String, String> = OfficialHttpSession.snapshotCookies()): OfficialHttpSession {
        val revision = accountRevision
        return OfficialHttpSession(cookies,
            isCurrent = { !activityDestroyed && revision == accountRevision })
    }

    private fun syncAccount(afterLogin: Boolean = false) {
        if (favoritePending) return
        val cookies = OfficialHttpSession.snapshotCookies()
        if (cookies.values.all { it.isBlank() }) {
            accountLoader.cancel()
            clearAccount()
            if (afterLogin) qrLoginView?.error() else render()
            return
        }
        accountLoader.load(accountSession(cookies), { NativeAccountApi(it).identity() }) { result ->
            result.onSuccess { identity ->
                if (identity == null) clearAccount()
                else {
                    if (!identity.id.equals(accountId, true)) {
                        retireAccountRequests()
                        clearAccount()
                    }
                    accountId = identity.id
                    accountName = identity.name
                    updateAccount()
                }
                if (afterLogin) {
                    if (identity == null) {
                        qrLoginView?.error()
                        return@onSuccess
                    }
                    closeQrLogin()
                    releasePortal()
                }
                if (afterLogin || detailsOpen || tab in setOf("home", "favorites", "history", "settings")) render()
                if (afterLogin) {
                    if (detailsOpen) focusContent() else navigation[tab]?.requestFocus()
                }
                if (identity != null) syncAccountData(identity.id)
            }.onFailure {
                if (afterLogin) qrLoginView?.error()
                nativeRequestError(it)
            }
        }
    }

    private fun syncAccountData(owner: String) {
        accountLoader.load(accountSession(), { NativeAccountApi(it).account(owner) }) { result ->
            result.onSuccess { account ->
                if (account == null) clearAccount()
                else {
                    accountId = account.id
                    accountName = account.name
                    favorites = account.favorites
                    history = account.history
                    accountSynced = true
                    updateAccount()
                }
                if (detailsOpen || tab in setOf("home", "favorites", "history", "settings")) render()
            }.onFailure {
                nativeRequestError(it)
                if (accountId != null) showNotice(tr(
                    "已登录，追番或观看历史同步未完成，请稍后刷新重试。",
                    "已登入，追番或觀看紀錄同步未完成，請稍後重新整理重試。"))
            }
        }
    }

    private fun retireAccountRequests() {
        accountRevision++
        accountLoader.cancel()
        favoriteLoader.cancel()
        favoritePending = false
    }

    private fun clearAccount() {
        accountId = null
        accountName = ""
        accountSynced = false
        favorites = emptyList()
        history = emptyList()
        updateAccount()
    }

    private fun nativeRequestError(error: Throwable) {
        lastError = (error as? NativeApiException)?.code ?: "native-data-invalid"
        if (lastError in setOf("login-required", "account-stale")) clearAccount()
        Diagnostics.record("api.error", "code" to lastError)
        showNotice(tr("官方请求未完成，请稍后刷新重试。", "官方請求未完成，請稍後重新整理重試。"))
    }

    private fun onOfficialEvent(body: JSONObject) {
        if (isFinishing || isDestroyed || !portalAvailable) return
        when (body.optString("type")) {
            "qr-login-ready" -> runCatching { qrLoginView?.ready(QrLoginCode.parse(body)) }
                .onFailure { qrLoginView?.error() }
            "qr-login-retrying" -> qrLoginView?.retrying()
            "qr-login-expired" -> qrLoginView?.error(expired = true)
            "qr-login-error" -> qrLoginView?.error()
            "qr-login-complete" -> {
                qrLoginView?.authorizing()
                portal.completeQrLogin()
            }
            "login-complete" -> {
                android.webkit.CookieManager.getInstance().flush()
                syncAccount(afterLogin = true)
            }
            "operation-error", "navigation-error", "verification-required" -> qrLoginView?.error()
        }
    }

    private fun onRendererGone() {
        Diagnostics.record("web.renderer-gone")
        closeQrLogin()
        releasePortal()
        render()
        showNotice(tr("登录页面已断开，请重新打开登录。", "登入頁面已中斷，請重新開啟登入。"))
    }

    private fun renderSettings() {
        heading(tr("设置", "設定"), tr("按你的电视调整观看体验", "依你的電視調整觀看體驗"))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val list = TvStyle.vertical(this)
        scroll.addView(list, FrameLayout.LayoutParams(-1, -2))
        fun setting(key: String, label: String, description: String, action: () -> Unit): TextView {
            val button = TvStyle.button(this, label, action).apply {
                gravity = Gravity.CENTER_VERTICAL
                textSize = 18f
                setOnFocusChangeListener { _, focused -> if (focused) settingsFocus = key }
            }
            list.addView(button, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(7) })
            list.addView(TvStyle.label(this, description, 14f, TvStyle.muted).apply { setPadding(dp(12), dp(7), dp(12), dp(13)) })
            if (settingsFocus == key) primaryContent = button
            return button
        }
        setting("resources", tr("低资源模式：", "低資源模式：") + enabled(preferences.lowResources),
            tr("减少动画、封面尺寸、图片缓存与预加载；视频画质单独设置。", "減少動畫、封面尺寸、圖片快取與預先載入；影片畫質獨立設定。")) {
            preferences.lowResources = !preferences.lowResources
            posters.lowResources = preferences.lowResources
            render()
            focusContent(preferGrid = true)
        }
        setting("autoNext", tr("自动播放下一集：", "自動播放下一集：") + enabled(preferences.autoNext),
            tr("同一作品有下一集时自动继续。", "同一作品有下一集時自動繼續。")) {
            preferences.autoNext = !preferences.autoNext
            render()
            focusContent()
        }
        setting("language", tr("界面语言：简体中文", "介面語言：繁體中文"), tr("番剧标题和简介保留官方原文。", "動畫標題與簡介保留官方原文。")) {
            preferences.traditional = !preferences.traditional
            buildShell()
            render()
            focusContent()
        }
        setting("account", if (accountId == null) tr("登录账号", "登入帳號") else tr("账号：$accountName", "帳號：$accountName"),
            tr("登录和观看进度由官方机制处理。", "登入與觀看進度由官方機制處理。"), ::accountAction)
        setting("refresh", tr("刷新官方内容", "重新整理官方內容"), tr("重新读取首页、追番与观看历史。", "重新讀取首頁、追番與觀看紀錄。"), ::refresh)
        setting("diagnostics", tr("日志与反馈", "日誌與回報"),
            if (Diagnostics.recording) tr("正在记录。复现问题后在这里停止并发送日志。", "正在記錄。重現問題後在此停止並傳送日誌。")
            else tr("记录问题过程，手机扫码取走日志并反馈给开发者。", "記錄問題過程，手機掃碼取得日誌並回報給開發者。"), ::openDiagnostics)
        updateCheckButton = setting("updates", updateCheckLabel(),
            tr("当前版本 ${BuildConfig.VERSION_NAME} · 从 GitHub 检查新版本", "目前版本 ${BuildConfig.VERSION_NAME} · 從 GitHub 檢查新版本")) {
            checkForUpdates(manual = true)
        }
        setting("compatibility", tr("系统与兼容信息", "系統與相容資訊"), tr("查看版本信息，或复制不含账号凭据的诊断信息。", "查看版本資訊，或複製不含帳號憑證的診斷資訊。")) {
            val info = compatibility()
            activeDialog = AlertDialog.Builder(this).setTitle(tr("系统与兼容信息", "系統與相容資訊")).setMessage(info)
                .setPositiveButton(tr("复制", "複製")) { _, _ ->
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Baha Plus TV", info))
                }.setNegativeButton(tr("返回", "返回"), null).show()
        }
        setting("licenses", tr("开源许可", "開源授權"), "Baha Plus TV · MIT / Apache 2.0") {
            val license = listOf("LICENSE", "NOTICE", "licenses/Apache-2.0.txt",
                "compat/Babel-LICENSE.txt", "compat/CoreJS-LICENSE.txt").joinToString("\n\n") { path ->
                assets.open(path).bufferedReader().use { it.readText() }
            }
            showText(tr("开源许可", "開源授權"), license)
        }
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        settingsScroll = scroll
        scroll.post { scroll.scrollTo(0, settingsScrollY) }
    }
    private fun openDiagnostics() {
        if (diagnosticsView != null) return
        val view = DiagnosticsView(this, preferences, onStarted = {
            diagnosticPhase = ""
            Diagnostics.record("app.state", "screen" to tab, "signedIn" to (accountId != null),
                "lowResources" to preferences.lowResources, "autoNext" to preferences.autoNext,
                "code" to lastError)
        }, onClose = ::closeDiagnostics)
        diagnosticsView = view
        shell.visibility = View.GONE
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.bringToFront()
    }
    private fun closeDiagnostics() {
        val view = diagnosticsView ?: return
        view.dispose()
        root.removeView(view)
        diagnosticsView = null
        render()
        focusContent()
        handler.post(presentUpdateResult)
    }
    private fun compatibility(): String {
        val provider = runCatching { WebViewCompat.getCurrentWebViewPackage(this)?.versionName }.getOrNull() ?: "Unavailable"
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return "Baha Plus TV ${BuildConfig.VERSION_NAME}\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n" +
            "ABI ${Build.SUPPORTED_ABIS.joinToString()}\nWebView $provider\n" +
            "App process PSS ${memory.totalPss / 1024} MiB\n" +
            "Low resources ${preferences.lowResources}\nLast error ${lastError.ifBlank { "none" }}"
    }
    private fun showText(title: String, text: String) {
        val scroll = ScrollView(this).apply { isFocusable = true }
        scroll.addView(TvStyle.label(this, text, 17f).apply { setPadding(dp(24), dp(16), dp(24), dp(16)) })
        activeDialog = AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton(tr("返回", "返回"), null).show()
    }
    private fun showNotice(text: String) {
        if (::notice.isInitialized && playingVideo.isEmpty()) {
            notice.text = text
            notice.setTextColor(TvStyle.warning)
        }
    }
    private fun updateCheckLabel() = if (updateChecking) tr("正在检查更新…", "正在檢查更新…") else tr("检查更新", "檢查更新")

    private fun checkForUpdates(manual: Boolean) {
        manualUpdateCheck = manualUpdateCheck || manual
        // A manual click can join the startup request; repeated clicks do not start more requests.
        if (updateChecking) return
        updateChecking = true
        updateCheckButton?.text = updateCheckLabel()
        updateChecker.check { result ->
            if (isFinishing || isDestroyed) return@check
            updateChecking = false
            updateCheckButton?.text = updateCheckLabel()
            if (result is UpdateCheckResult.Available || manualUpdateCheck) pendingUpdateResult = result
            manualUpdateCheck = false
            showPendingUpdateResult()
        }
    }

    private fun showPendingUpdateResult() {
        if (pendingUpdateResult == null || !updateUiResumed || !hasWindowFocus() || isFinishing || isDestroyed ||
            loginOpening || qrLoginView != null || diagnosticsView != null ||
            playingVideo.isNotEmpty() || activeDialog?.isShowing == true) return
        val result = pendingUpdateResult
        pendingUpdateResult = null
        when (result) {
            is UpdateCheckResult.Available -> showUpdateDialog(result.release)
            UpdateCheckResult.UpToDate -> Toast.makeText(this, tr("无更新", "無更新"), Toast.LENGTH_SHORT).show()
            UpdateCheckResult.Failed -> Toast.makeText(this,
                tr("检查更新失败，请稍后重试", "檢查更新失敗，請稍後重試"), Toast.LENGTH_LONG).show()
            null -> Unit
        }
    }

    private fun showUpdateDialog(release: AppRelease) {
        val message = tr("当前版本：", "目前版本：") + BuildConfig.VERSION_NAME + "\n" +
            tr("最新版本：", "最新版本：") + release.versionName +
            if (release.notes.isBlank()) "" else "\n\n" + release.notes
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr("发现新版本", "發現新版本"))
            .setMessage(message)
            .setPositiveButton(tr("前往 GitHub 下载", "前往 GitHub 下載")) { _, _ -> openReleasePage(release.pageUrl) }
            .setNegativeButton(tr("取消", "取消"), null)
            .create()
        activeDialog = dialog
        dialog.show()
        dialog.findViewById<TextView>(android.R.id.message)?.gravity = Gravity.START
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }

    private fun openReleasePage(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            handler.post { if (!isFinishing && !isDestroyed) {
                showText(tr("未找到浏览器", "找不到瀏覽器"),
                    tr("请在其他设备打开 GitHub 发版页面：\n", "請在其他裝置開啟 GitHub 發布頁面：\n") + url)
            } }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) handler.post(presentUpdateResult)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (native?.handle(event) == true) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            back()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    @Deprecated("Handled by remote keys and the platform back callback")
    // API 33+ uses the platform callback registered in onCreate. This override
    // retains the same behavior for older TV systems and hardware remotes.
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() { back() }
    private fun back() {
        if (diagnosticsView != null) {
            closeDiagnostics()
        } else if (qrLoginView != null || loginOpening) {
            cancelQrLogin()
        } else if (playingVideo.isNotEmpty()) {
            if (native?.handleBack() != true) exitPlayback()
        }
        else if (detailsOpen) {
            detailLoader.cancel()
            detailsOpen = false
            autoStartVideo = null
            render()
            focusContent(preferGrid = true)
        } else if (tab != "home") {
            changeTab("home")
            navigation["home"]?.requestFocus()
        } else finish()
    }
    override fun onResume() {
        super.onResume()
        Diagnostics.record("app.resumed")
        updateUiResumed = true
        handler.post(presentUpdateResult)
        if (portalAvailable) portal.foreground(true)
        native?.resumeLifecycle()
    }
    override fun onPause() {
        Diagnostics.record("app.paused")
        updateUiResumed = false
        native?.pauseForLifecycle()
        if (portalAvailable) portal.foreground(false)
        super.onPause()
    }
    override fun onStop() {
        diagnosticsView?.background()
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tab", tab)
        outState.putString("query", searchQuery)
        super.onSaveInstanceState(outState)
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Diagnostics.record("app.memory", "memoryLevel" to level)
        if (::posters.isInitialized) posters.trim()
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) detailCache.clear()
    }
    override fun onDestroy() {
        Diagnostics.record("app.destroyed")
        diagnosticsView?.dispose()
        diagnosticsView = null
        if (::updateChecker.isInitialized) updateChecker.destroy()
        activityDestroyed = true
        accountRevision++
        catalogLoader.destroy()
        searchLoader.destroy()
        accountLoader.destroy()
        favoriteLoader.destroy()
        detailLoader.destroy()
        closeQrLogin()
        activeDialog?.dismiss()
        native?.release()
        native = null
        handler.removeCallbacksAndMessages(null)
        releasePortal()
        if (::posters.isInitialized) posters.destroy()
        super.onDestroy()
    }
    private fun tr(simplified: String, traditional: String) = preferences.text(simplified, traditional)
    private fun enabled(value: Boolean) = if (value) tr("开启", "開啟") else tr("关闭", "關閉")
    private fun dp(value: Int) = TvStyle.dp(this, value)
}
