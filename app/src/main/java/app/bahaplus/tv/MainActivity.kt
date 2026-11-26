package app.bahaplus.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.webkit.WebViewCompat
import app.bahaplus.tv.core.*
import app.bahaplus.tv.data.*
import app.bahaplus.tv.diagnostics.Diagnostics
import app.bahaplus.tv.experiment.NativeApiClient
import app.bahaplus.tv.player.NativePlayback
import app.bahaplus.tv.ui.CatalogAdapter
import app.bahaplus.tv.ui.CatalogRow
import app.bahaplus.tv.ui.DetailAdapter
import app.bahaplus.tv.ui.DiagnosticsView
import app.bahaplus.tv.ui.QrLoginView
import app.bahaplus.tv.ui.ScheduleScreen
import app.bahaplus.tv.ui.SearchScreen
import app.bahaplus.tv.ui.SettingsScreen
import app.bahaplus.tv.ui.TvDialog
import app.bahaplus.tv.ui.TvGridLayoutManager
import app.bahaplus.tv.ui.TvStyle
import org.json.JSONObject
import java.util.concurrent.Executors

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val ioWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ui-assets").apply { isDaemon = true }
    }
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
    private var scheduleDay = -1
    private var catalog = emptyList<Anime>()
    private var favorites = emptyList<Anime>()
    private var history = emptyList<WatchEntry>()
    private var accountId: String? = null
    private var accountName = ""
    private var accountSynced = false
    private var lastError = ""
    private var catalogLoading = false
    private var catalogFailed = false
    private var searchLoading = false
    private var activityPaused = false
    private var backKeyDown = false
    private val detailLoader = AnimeDetailLoader(deliver = { handler.post(it) })
    private val ratingLoader = AnimeDetailLoader(deliver = { handler.post(it) })
    private var ratingLoading = false
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
    private var gridAdapter: CatalogAdapter? = null
    private var gridStateKey: String? = null
    private var detailList: RecyclerView? = null
    private var detailAdapter: DetailAdapter? = null
    private var scheduleScreen: ScheduleScreen? = null
    private var searchScreen: SearchScreen? = null
    private var settingsScreen: SettingsScreen? = null
    private var lastCardPosition = 0
    private var detailFocus = 0
    private var episodePosition = 0
    private var settingsFocus = "resources"
    private var settingsScrollY = 0
    private val gridStates = mutableMapOf<String, Parcelable?>()
    private val focusedKeys = mutableMapOf<String, String>()
    private val schedulePositions = mutableMapOf<Int, Int>()
    private val detailCache = object : LinkedHashMap<String, AnimeDetail>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnimeDetail>) = size > 6
    }
    private var primaryContent: View? = null
    private var activeDialog: AlertDialog? = null
    private var qrLoginView: QrLoginView? = null
    private var diagnosticsView: DiagnosticsView? = null
    private lateinit var updateChecker: GitHubUpdateChecker
    private var updateChecking = false
    private var manualUpdateCheck = false
    private var pendingUpdateResult: UpdateCheckResult? = null
    private var updateUiResumed = false
    private val presentUpdateResult = Runnable { showPendingUpdateResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = Preferences(this)
        Diagnostics.record("app.created")
        posters = PosterLoader(this, preferences.lowResources)
        updateChecker = GitHubUpdateChecker(BuildConfig.VERSION_NAME)
        tab = savedInstanceState?.getString("tab")
            ?.takeIf { it in setOf("home", "schedule", "search", "favorites", "history", "settings") } ?: "home"
        searchQuery = savedInstanceState?.getString("query").orEmpty().take(200)
        searchDraft = searchQuery
        scheduleDay = savedInstanceState?.getInt("scheduleDay", -1) ?: -1
        applyImmersiveMode()
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

    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }

    private fun buildShell() {
        if (::shell.isInitialized) root.removeView(shell)
        shell = TvStyle.horizontal(this)
        rail = TvStyle.vertical(this).apply {
            setBackgroundColor(TvStyle.rail)
            setPadding(dp(14), dp(22), dp(12), dp(20))
        }
        val brand = TvStyle.label(this, "Baha Plus", 22f, bold = true)
        val brandTv = TvStyle.label(this, "TV", 13f, TvStyle.focus, true).apply {
            setPadding(0, dp(3), 0, dp(18))
        }
        rail.addView(brand)
        rail.addView(brandTv)
        navigation.clear()
        val items = listOf(
            Triple("home", tr("首页", "首頁"), R.drawable.ic_nav_home),
            Triple("schedule", tr("时间表", "時間表"), R.drawable.ic_nav_schedule),
            Triple("search", tr("搜索", "搜尋"), R.drawable.ic_nav_search),
            Triple("favorites", tr("追番", "追番"), R.drawable.ic_nav_favorites),
            Triple("history", tr("历史", "歷史"), R.drawable.ic_nav_history),
            Triple("settings", tr("设置", "設定"), R.drawable.ic_nav_settings)
        )
        for ((key, label, iconRes) in items) {
            val button = TvStyle.button(this, label) { changeTab(key) }.apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                textSize = 17f
                background = TvStyle.railItemBackground(this@MainActivity)
                setPadding(dp(12), dp(8), dp(10), dp(8))
                val icon = ContextCompat.getDrawable(this@MainActivity, iconRes)?.mutate()
                if (icon != null) {
                    DrawableCompat.setTint(icon, TvStyle.muted)
                    icon.setBounds(0, 0, dp(19), dp(19))
                    setCompoundDrawablesRelative(icon, null, null, null)
                    compoundDrawablePadding = dp(10)
                }
                setOnKeyListener { _, code, event ->
                    if (code == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                        focusContent()
                        true
                    } else false
                }
            }
            navigation[key] = button
            rail.addView(button, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(6) })
        }
        rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        accountLabel = TvStyle.label(this, "", 13f, TvStyle.muted).apply { maxLines = 2 }
        accountButton = TvStyle.button(this, "", ::accountAction).apply {
            textSize = 15f
            setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                    focusContent()
                    true
                } else false
            }
        }
        rail.addView(accountLabel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        rail.addView(accountButton, LinearLayout.LayoutParams(-1, dp(42)))
        shell.addView(rail, LinearLayout.LayoutParams(dp(156), -1))
        content = TvStyle.vertical(this).apply { setPadding(dp(24), dp(20), dp(24), dp(14)) }
        shell.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(shell, 0, FrameLayout.LayoutParams(-1, -1))
        updateAccount()
    }

    private fun updateNavStyles() {
        navigation.forEach { (key, view) ->
            val selected = key == tab
            view.isSelected = selected
            val color = if (selected) TvStyle.focus else TvStyle.muted
            view.compoundDrawablesRelative.firstOrNull()?.let { DrawableCompat.setTint(it, color) }
        }
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
            ratingLoader.cancel()
            ratingLoading = false
            autoStartVideo = null
            detailsOpen = false
        }
        saveCurrentState()
        tab = key
        if (key == "schedule" && catalog.isEmpty() && !catalogLoading) loadCatalog()
        render(restoreFocus = false)
        if ((key == "history" || key == "favorites") && accountId != null) syncAccount()
    }

    private fun isOverlayActive(): Boolean =
        native != null || playingVideo.isNotEmpty() || qrLoginView != null || diagnosticsView != null

    private fun render(restoreFocus: Boolean = true) {
        if (!::content.isInitialized || isOverlayActive()) return
        val restoreContentFocus = restoreFocus && content.hasFocus()
        shell.visibility = View.VISIBLE
        playerLayer.visibility = View.GONE
        saveCurrentState()
        // Recycle old views now so their pending poster work is cancelled.
        grid?.adapter = null
        detailList?.adapter = null
        scheduleScreen?.dispose()
        searchScreen?.dispose()
        content.removeAllViews()
        grid = null
        gridAdapter = null
        detailList = null
        detailAdapter = null
        scheduleScreen = null
        searchScreen = null
        settingsScreen = null
        gridStateKey = null
        primaryContent = null
        updateNavStyles()
        updateAccount()
        if (detailsOpen) {
            renderDetail()
            if (restoreContentFocus) content.post { focusContent() }
            return
        }
        when (tab) {
            "schedule" -> renderSchedule()
            "search" -> renderSearch()
            "settings" -> renderSettings()
            "favorites" -> {
                heading(
                    tr("我的追番", "我的追番"),
                    if (accountId == null) tr("登录后查看官方追番", "登入後查看官方追番")
                    else if (accountSynced) tr("已从官方同步 · ${favorites.size} 部", "已從官方同步 · ${favorites.size} 部")
                    else tr("等待官方同步", "等待官方同步")
                )
                if (accountId == null) emptyAccount()
                else if (favorites.isEmpty()) empty(
                    tr("还没有追番", "還沒有追番"),
                    tr("在番剧详情中添加追番，或刷新官方收藏。", "在動畫詳情中加入追番，或重新整理官方收藏。"),
                    ::refresh
                )
                else renderGrid(favorites.map { CatalogRow.Card(it) })
            }
            "history" -> {
                heading(tr("观看历史", "觀看紀錄"), tr("续播位置以官方记录为准", "續播位置以官方紀錄為準"))
                if (accountId == null) emptyAccount()
                else if (history.isEmpty()) empty(
                    tr("暂时没有观看记录", "暫時沒有觀看紀錄"),
                    tr("播放后刷新，读取官方保存的观看记录。", "播放後重新整理，讀取官方保存的觀看紀錄。"),
                    ::refresh
                )
                else renderGrid(history.map { CatalogRow.Card(it.anime, it) })
            }
            else -> {
                heading(tr("今天想看什么？", "今天想看什麼？"), tr("新番与热门动画", "新番與熱門動畫"))
                if (catalog.isEmpty()) {
                    empty(
                        if (catalogLoading) tr("正在连接动画疯", "正在連線動畫瘋") else tr("首页暂不可用", "首頁暫時無法使用"),
                        if (catalogLoading) tr("正在读取官方接口。", "正在讀取官方介面。")
                        else tr("请检查网络后重试。", "請檢查網路後重試。"),
                        ::refresh
                    )
                } else {
                    renderGrid(buildHomeRows())
                }
            }
        }
        if (restoreContentFocus) content.post { focusContent(preferGrid = tab != "search") }
    }

    private fun buildHomeRows(): List<CatalogRow> {
        val rows = mutableListOf<CatalogRow>()
        if (history.isNotEmpty()) {
            rows += CatalogRow.Heading(tr("继续观看", "繼續觀看"))
            rows += history.take(8).map { CatalogRow.Card(it.anime, it) }
        }
        rows += CatalogRow.Heading(tr("本季与热门", "本季與熱門"))
        rows += catalog.map { CatalogRow.Card(it) }
        return rows
    }

    /** Single-line compact header bar: recovers vertical space so 2 full poster rows fit comfortably. */
    private fun heading(title: String, subtitle: String) {
        val bar = TvStyle.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(
            TvStyle.label(this, title, 24f, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(14) }
        )
        notice = TvStyle.label(this, subtitle, 13f, TvStyle.muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        bar.addView(notice, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun empty(title: String, subtitle: String, retry: (() -> Unit)? = null): LinearLayout {
        val panel = TvStyle.vertical(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(26), dp(14), dp(26), dp(26))
        }
        panel.addView(TvStyle.label(this, title, 22f, bold = true))
        panel.addView(TvStyle.label(this, subtitle, 16f, TvStyle.muted).apply {
            setPadding(0, dp(12), 0, dp(18))
        })
        if (retry != null) {
            val button = TvStyle.button(this, tr("重试", "重試"), retry).apply {
                nextFocusLeftId = navigation[tab]?.id ?: View.NO_ID
            }
            panel.addView(button, LinearLayout.LayoutParams(dp(120), -2))
            primaryContent = button
        }
        content.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))
        return panel
    }

    private fun emptyAccount() {
        empty(
            tr("连接你的巴哈姆特账号", "連接你的巴哈姆特帳號"),
            tr("登录后查看追番和观看历史。", "登入後查看追番和觀看紀錄。")
        )
        val button = TvStyle.primaryButton(this, tr("登录账号", "登入帳號"), ::login).apply {
            nextFocusLeftId = navigation[tab]?.id ?: View.NO_ID
        }
        content.addView(button, LinearLayout.LayoutParams(dp(180), -2))
        primaryContent = button
    }

    private fun renderGrid(rows: List<CatalogRow>) {
        val list = RecyclerView(this)
        val manager = TvGridLayoutManager(this, 4, preferences.lowResources).apply {
            initialPrefetchItemCount = if (preferences.lowResources) 4 else 8
        }
        val adapter = CatalogAdapter(
            context = this,
            initialRows = rows,
            posters = posters,
            leftTarget = navigation[tab]!!.id,
            traditional = preferences.traditional,
            lowResources = preferences.lowResources,
            onLeft = { navigation[tab]?.requestFocus() },
            onFocus = { position, card ->
                lastCardPosition = position
                gridStateKey?.let { focusedKeys[it] = card.key }
            },
            onClick = { card -> openDetail(card.anime, card.history?.videoId) }
        )
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
        gridAdapter = adapter
        val key = gridKey()
        gridStateKey = key
        gridStates[key]?.let(manager::onRestoreInstanceState)
        val focused = focusedKeys[key]
        lastCardPosition = rows.indexOfFirst { it is CatalogRow.Card && it.key == focused }
            .takeIf { it >= 0 } ?: rows.indexOfFirst { it is CatalogRow.Card }.coerceAtLeast(0)
        primaryContent = list
    }

    private fun gridKey(): String = when (tab) {
        "search" -> "search:$searchQuery"
        "schedule" -> "schedule:$scheduleDay"
        else -> tab
    }

    private fun saveCurrentState() {
        if (detailsOpen) return
        settingsScreen?.let { settingsScrollY = it.scrollY }
        scheduleScreen?.let { screen ->
            gridStates["schedule:${screen.selectedDay}"] = screen.saveListState()
            return
        }
        searchScreen?.let { screen ->
            gridStates["search:$searchQuery"] = screen.saveListState()
            return
        }
        val key = gridStateKey ?: return
        grid?.layoutManager?.onSaveInstanceState()?.also { gridStates[key] = it }
    }

    private fun focusContent(preferGrid: Boolean = false) {
        if (detailsOpen) {
            val dList = detailList
            val dAdapter = detailAdapter
            if (dList != null && dAdapter != null) {
                if (detailFocus == 3) {
                    val pos = dAdapter.episodeAdapterPosition(episodePosition)
                    if (pos != null) {
                        dList.scrollToPosition(pos)
                        dList.post {
                            dList.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                                ?: dList.post { dList.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
                        }
                        return
                    }
                }
                dList.scrollToPosition(0)
                dList.post {
                    dAdapter.actionView(detailFocus.coerceIn(0, 2))?.requestFocus()
                        ?: dList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
                return
            }
            primaryContent?.requestFocus()
            return
        }
        scheduleScreen?.let { screen ->
            val savedPos = schedulePositions[screen.selectedDay] ?: 0
            screen.focusTarget(preferList = preferGrid, position = savedPos)
            return
        }
        searchScreen?.let { screen ->
            screen.focusTarget(preferGrid = preferGrid, position = lastCardPosition)
            return
        }
        settingsScreen?.let { screen ->
            screen.focusSetting(settingsFocus)
            return
        }
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

    private fun renderSchedule() {
        val today = UpdateSchedule.weekday()
        if (scheduleDay !in 0..6) scheduleDay = today
        heading(
            tr("番剧时间表", "動畫時間表"),
            tr("台湾时间（UTC+8）每周更新", "台灣時間（UTC+8）每週更新")
        )
        val screen = ScheduleScreen(
            context = this,
            preferences = preferences,
            posters = posters,
            leftTargetId = navigation["schedule"]!!.id,
            initialDay = scheduleDay,
            onLeft = { navigation["schedule"]?.requestFocus() },
            onDaySelected = { day ->
                scheduleScreen?.let { current ->
                    gridStates["schedule:$scheduleDay"] = current.saveListState()
                }
                scheduleDay = day
                scheduleScreen?.bind(
                    day = scheduleDay,
                    catalog = catalog,
                    catalogLoading = catalogLoading,
                    favoriteIds = favoriteIds(),
                    savedState = gridStates["schedule:$scheduleDay"]
                )
            },
            onFocusRow = { day, pos, _ -> schedulePositions[day] = pos },
            onOpenAnime = { anime -> openDetail(anime) },
            onRetry = ::refresh
        )
        screen.bind(
            day = scheduleDay,
            catalog = catalog,
            catalogLoading = catalogLoading,
            favoriteIds = favoriteIds(),
            savedState = gridStates["schedule:$scheduleDay"]
        )
        content.addView(screen, LinearLayout.LayoutParams(-1, 0, 1f))
        scheduleScreen = screen
    }

    private fun renderSearch() {
        heading(tr("搜索动画", "搜尋動畫"), tr("支持简繁中文", "支援簡繁中文"))
        val screen = SearchScreen(
            context = this,
            preferences = preferences,
            posters = posters,
            leftTargetId = navigation["search"]!!.id,
            initialDraft = searchDraft,
            onLeft = { navigation["search"]?.requestFocus() },
            onDraftChanged = { searchDraft = it },
            onSubmit = ::submitSearch,
            onClear = {
                searchLoader.cancel()
                searchLoading = false
                searchQuery = ""
                searchDraft = ""
                searchResults = emptyList()
                searchScreen?.bind("", emptyList(), false)
            },
            onFocusCard = { pos, card ->
                lastCardPosition = pos
                focusedKeys["search:$searchQuery"] = card.key
            },
            onClickCard = { card -> openDetail(card.anime, card.history?.videoId) }
        )
        val focused = focusedKeys["search:$searchQuery"]
        lastCardPosition = searchResults.indexOfFirst { "anime:${it.id}" == focused }.coerceAtLeast(0)
        screen.bind(searchQuery, searchResults, searchLoading, gridStates["search:$searchQuery"])
        content.addView(screen, LinearLayout.LayoutParams(-1, 0, 1f))
        searchScreen = screen
    }

    private fun submitSearch(raw: String) {
        Diagnostics.record("ui.search")
        val query = raw.trim().codePoints().limit(100).toArray().let { String(it, 0, it.size) }
        if (query.isBlank()) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(window.decorView.windowToken, 0)
        preferences.addSearchHistory(query)
        searchQuery = query
        searchDraft = query
        searchResults = emptyList()
        searchLoading = true
        searchScreen?.setDraft(query)
        searchScreen?.bind(query, emptyList(), loading = true)
        val queries = listOf(query, preferences.toTraditional(query)).distinct()
        searchLoader.load(OfficialHttpSession(), { NativeCatalogApi(it).search(queries) }) { result ->
            if (searchQuery != query) return@load
            searchLoading = false
            result.onSuccess { searchResults = it }.onFailure { nativeRequestError(it) }
            if (!detailsOpen && tab == "search") {
                val activeSearch = searchScreen
                if (activeSearch != null) {
                    lastCardPosition = 0
                    activeSearch.bind(searchQuery, searchResults, loading = false)
                } else {
                    render()
                }
                if (searchResults.isNotEmpty()) focusContent(preferGrid = true)
            }
        }
    }

    private fun openDetail(anime: Anime, resumeVideo: String? = null) {
        Diagnostics.record("ui.detail")
        detailLoader.cancel()
        ratingLoader.cancel()
        ratingLoading = false
        saveCurrentState()
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
        ratingLoader.cancel()
        ratingLoading = false
        detailLoading = true
        detailFailed = false
        if (currentDetail == null) render()
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
                val cachedRating = currentDetail?.takeIf { it.ratingFanId == detail.ratingFanId }?.rating
                val updated = detail.copy(rating = cachedRating)
                detailCache[detail.animeId] = updated
                val hadView = detailAdapter != null
                currentDetail = updated
                if (hadView) {
                    if (::notice.isInitialized) {
                        notice.text = tr("${updated.episodes.size} 集 · 官方内容", "${updated.episodes.size} 集 · 官方內容")
                        notice.setTextColor(TvStyle.muted)
                    }
                    detailAdapter?.updateDetail(
                        updated = updated,
                        newFavorited = favorites.any { it.id == anime.id },
                        newResume = history.firstOrNull { it.anime.id == anime.id },
                        loadingRating = updated.rating == null && updated.ratingFanId != null
                    )
                } else {
                    render()
                }
                val video = autoStartVideo
                autoStartVideo = null
                if (video != null) play(video) else requestRating(updated)
            }, onFailure = { error ->
                detailFailed = true
                lastError = (error as? AnimeDetailException)?.code ?: "detail-invalid"
                Diagnostics.record("detail.failed", "code" to lastError)
                if (currentDetail == null) render()
                else showNotice(tr("详情更新失败，请稍后重试。", "詳情更新失敗，請稍後重試。"))
            })
        }
    }

    private fun requestRating(detail: AnimeDetail) {
        val fanId = detail.ratingFanId ?: return
        ratingLoading = true
        detailAdapter?.updateRating(detail.rating, loading = true)
        ratingLoader.loadRating(fanId) { result ->
            if (isFinishing || isDestroyed || !detailsOpen || playingVideo.isNotEmpty() ||
                currentDetail?.animeId != detail.animeId || currentDetail?.ratingFanId != fanId) return@loadRating
            ratingLoading = false
            result.onSuccess { rating ->
                currentDetail = currentDetail?.copy(rating = rating)
                currentDetail?.let { detailCache[it.animeId] = it }
            }
            detailAdapter?.updateRating(currentDetail?.rating, loading = false)
        }
    }

    private fun renderDetail() {
        val anime = selectedAnime ?: return
        val detail = currentDetail
        heading(
            detail?.title ?: anime.title,
            when {
                detail == null && detailFailed -> tr("详情读取失败", "詳情讀取失敗")
                detail == null -> tr("正在读取番剧信息", "正在讀取動畫資訊")
                else -> tr("${detail.episodes.size} 集 · 官方内容", "${detail.episodes.size} 集 · 官方內容")
            }
        )
        if (detail == null) {
            val placeholder = empty(
                if (detailFailed) tr("暂时无法读取详情", "暫時無法讀取詳情") else tr("正在加载详情", "正在載入詳情"),
                if (detailFailed) tr("请检查网络连接后重试。", "請檢查網路連線後重試。")
                else tr("正在读取官方简介与集数。", "正在讀取官方簡介與集數。"),
                if (detailLoading) null else ({ requestDetail(anime) })
            )
            if (detailLoading) primaryContent = placeholder.apply { isFocusable = true }
            return
        }
        val adapter = DetailAdapter(
            context = this,
            anime = anime,
            detail = detail,
            posters = posters,
            traditional = preferences.traditional,
            leftTarget = navigation[tab]!!.id,
            favorited = favorites.any { it.id == anime.id },
            resumeEntry = history.firstOrNull { it.anime.id == anime.id },
            playingVideoId = playingVideo,
            ratingLoading = ratingLoading || detailLoading,
            onLeft = { navigation[tab]?.requestFocus() },
            onPlay = ::play,
            onToggleFavorite = { toggleFavorite(anime.id) },
            onShowIntro = { fullText -> showText(detail.title, fullText) },
            onFocusAction = { slot -> detailFocus = slot },
            onFocusEpisode = { index ->
                detailFocus = 3
                episodePosition = index
            }
        )
        val manager = TvGridLayoutManager(this, DetailAdapter.SPAN_COUNT, preferences.lowResources).apply {
            spanSizeLookup = adapter.spans()
        }
        val list = RecyclerView(this).apply {
            layoutManager = manager
            this.adapter = adapter
            itemAnimator = null
            setItemViewCacheSize(if (preferences.lowResources) 8 else 16)
            clipToPadding = false
            setPadding(0, 0, 0, dp(16))
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        detailList = list
        detailAdapter = adapter
        primaryContent = list
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
                // NativeAccountApi.favorite() already confirmed server state; update UI in-place.
                if (detailsOpen && selectedAnime?.id == id) {
                    detailAdapter?.updateFavorite(desired)
                    showNotice(
                        if (desired) tr("已加入追番", "已加入追番") else tr("已取消追番", "已取消追番"),
                        isWarning = false
                    )
                }
            }.onFailure { nativeRequestError(it) }
        }
        showNotice(tr("正在更新追番", "正在更新追番"), isWarning = false)
    }

    private fun play(videoId: String) {
        val detail = currentDetail ?: return
        if (detail.episodes.none { it.id == videoId }) return
        detailLoader.cancel()
        ratingLoader.cancel()
        ratingLoading = false
        detailLoading = false
        Diagnostics.record("player.request")
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
        val loginCookies = OfficialLoginCookies.webView
        val session = NativeApiClient(
            loginCookies.snapshot(),
            isCurrent = { !activityDestroyed && accountRevision == revision },
            loginCookies = loginCookies
        ) { stage, status ->
            if (status != null && status in 100..599) {
                Diagnostics.record("player.http", "phase" to stage, "httpStatus" to status)
            }
        }
        val candidate = NativePlayback(
            this, preferences, session, detail, current,
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
            onError = ::playbackError
        )
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
        activeDialog = TvDialog.confirm(
            context = this,
            title = tr("本集播放结束", "本集播放結束"),
            actions = listOf(
                TvDialog.Action(tr("返回详情", "返回詳情")) { exitPlayback() },
                TvDialog.Action(tr("选集", "選集")) { chooseEndedEpisode() },
                TvDialog.Action(tr("重播", "重播"), primary = true) { play(playingVideo) }
            ),
            focusIndex = 2,
            onCancel = { exitPlayback() }
        )
    }

    private fun chooseEndedEpisode() {
        val detail = playingDetail ?: return
        var selected = false
        activeDialog = TvDialog.choice(
            context = this,
            title = tr("选集", "選集"),
            items = detail.episodes.map { AppStrings.episodeName(it.label) },
            selected = detail.episodes.indexOfFirst { it.id == playingVideo },
            cancelLabel = tr("返回详情", "返回詳情"),
            onSelect = { index ->
                selected = true
                play(detail.episodes[index].id)
            },
            onDismiss = { if (!selected) exitPlayback() }
        )
    }

    private fun playbackError(code: String) {
        Diagnostics.record("player.error", "code" to code)
        if (isFinishing || playbackErrorVisible || playingVideo.isEmpty()) return
        playbackErrorVisible = true
        lastError = code.take(80)
        native?.pauseForLifecycle()
        val message = AppStrings.playbackError(preferences.traditional, code)
        activeDialog = TvDialog.confirm(
            context = this,
            title = tr("播放暂时无法继续", "播放暫時無法繼續"),
            message = message,
            actions = listOf(
                TvDialog.Action(tr("返回详情", "返回詳情")) { exitPlayback() },
                TvDialog.Action(tr("重试", "重試"), primary = true) { play(playingVideo) }
            ),
            focusIndex = 1,
            onCancel = { exitPlayback() }
        )
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
        currentDetail?.takeIf { it.rating == null }?.let(::requestRating)
        syncAccount()
    }

    private fun login() {
        if (loginOpening || qrLoginView != null || OfficialLoginCookies.webView.isClearing()) return
        loginOpening = true
        Diagnostics.record("account.login")
        retireAccountRequests()
        closeQrLogin()
        createPortal {
            loginOpening = false
            qrLoginView = QrLoginView(
                this, preferences,
                onRefresh = { portal.refreshQrLogin() },
                onCancel = ::cancelQrLogin
            )
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
        if (accountId == null) {
            login()
            return
        }
        activeDialog = TvDialog.confirm(
            context = this,
            title = tr("当前账号", "目前帳號"),
            message = accountName,
            actions = listOf(
                TvDialog.Action(tr("返回", "返回")),
                TvDialog.Action(tr("退出登录", "登出")) {
                    retireAccountRequests()
                    releasePortal()
                    OfficialPortal.logout {
                        clearAccount()
                        refreshVisibleScreenForAccount()
                    }
                },
                TvDialog.Action(tr("刷新同步", "重新同步"), primary = true) { syncAccount() }
            ),
            focusIndex = 2
        )
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
        catalogLoading = true
        catalogFailed = false
        catalogLoader.load(OfficialHttpSession(), { NativeCatalogApi(it).catalog() }) { result ->
            catalogLoading = false
            result.onSuccess {
                catalog = it
                catalogFailed = false
            }.onFailure {
                catalogFailed = true
            }
            if (!detailsOpen && !isOverlayActive()) {
                when (tab) {
                    "home" -> {
                        val existingAdapter = gridAdapter
                        if (existingAdapter != null && catalog.isNotEmpty()) {
                            existingAdapter.submit(buildHomeRows(), preferences.lowResources)
                        } else render()
                    }
                    "schedule" -> {
                        scheduleScreen?.bind(
                            scheduleDay, catalog, catalogLoading, favoriteIds(),
                            savedState = gridStates["schedule:$scheduleDay"]
                        ) ?: render()
                    }
                }
            }
            result.exceptionOrNull()?.let { error ->
                if (!activityPaused) nativeRequestError(error)
            }
        }
    }

    private fun accountSession(cookies: Map<String, String> = OfficialLoginCookies.webView.snapshot()): OfficialHttpSession {
        val revision = accountRevision
        return OfficialHttpSession(
            cookies,
            isCurrent = { !activityDestroyed && revision == accountRevision },
            loginCookies = OfficialLoginCookies.webView
        )
    }

    private fun syncAccount(afterLogin: Boolean = false) {
        if (favoritePending) return
        val cookies = OfficialLoginCookies.webView.snapshot()
        if (cookies.values.all { it.isBlank() }) {
            accountLoader.cancel()
            val hadAccount = accountId != null
            clearAccount()
            if (afterLogin) qrLoginView?.error()
            else if (hadAccount) refreshVisibleScreenForAccount()
            return
        }
        accountLoader.load(accountSession(cookies), { NativeAccountApi(it).identityWithPage() }) { result ->
            result.onSuccess { pair ->
                val identity = pair?.first
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
                    render()
                    if (detailsOpen) focusContent() else navigation[tab]?.requestFocus()
                } else {
                    refreshVisibleScreenForAccount()
                }
                if (pair != null) syncAccountData(pair.first.id, pair.second)
            }.onFailure {
                if (afterLogin) qrLoginView?.error()
                nativeRequestError(it)
            }
        }
    }

    private fun syncAccountData(owner: String, firstPage: InertHtml?) {
        accountLoader.load(accountSession(), { NativeAccountApi(it).account(owner, firstPage) }) { result ->
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
                refreshVisibleScreenForAccount()
            }.onFailure {
                nativeRequestError(it)
                if (accountId != null) showNotice(
                    tr("已登录，追番或观看历史同步未完成，请稍后刷新重试。", "已登入，追番或觀看紀錄同步未完成，請稍後重新整理重試。")
                )
            }
        }
    }

    /** Update active screen in-place whenever possible so background account sync never causes focus jump/flicker. */
    private fun refreshVisibleScreenForAccount() {
        if (!::content.isInitialized || isOverlayActive()) return
        if (detailsOpen) {
            val detail = currentDetail
            val anime = selectedAnime
            if (detail != null && anime != null && detailAdapter != null) {
                detailAdapter?.updateDetail(
                    updated = detail,
                    newFavorited = favorites.any { it.id == anime.id },
                    newResume = history.firstOrNull { it.anime.id == anime.id },
                    loadingRating = ratingLoading
                )
            }
            return
        }
        when (tab) {
            "home" -> {
                val adapter = gridAdapter
                if (adapter != null && catalog.isNotEmpty()) adapter.submit(buildHomeRows(), preferences.lowResources)
            }
            "schedule" -> {
                scheduleScreen?.bind(scheduleDay, catalog, catalogLoading, favoriteIds())
            }
            "settings" -> {
                settingsScreen?.refresh(accountId, accountName, updateChecking)
            }
            "favorites", "history" -> render()
        }
    }

    private fun favoriteIds(): Set<String> = favorites.mapTo(HashSet()) { it.id }

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
            "login-complete" -> syncAccount(afterLogin = true)
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
        val screen = SettingsScreen(
            context = this,
            preferences = preferences,
            leftTargetId = navigation["settings"]!!.id,
            initialFocusKey = settingsFocus,
            initialScrollY = settingsScrollY,
            onLeft = { navigation["settings"]?.requestFocus() },
            onFocusChanged = { settingsFocus = it },
            onToggleLowResources = {
                preferences.lowResources = !preferences.lowResources
                posters.lowResources = preferences.lowResources
                settingsScreen?.refresh(accountId, accountName, updateChecking)
            },
            onToggleAutoNext = {
                preferences.autoNext = !preferences.autoNext
                settingsScreen?.refresh(accountId, accountName, updateChecking)
            },
            onToggleLanguage = {
                preferences.traditional = !preferences.traditional
                buildShell()
                render()
                focusContent()
            },
            onAccount = ::accountAction,
            onRefresh = ::refresh,
            onOpenDiagnostics = ::openDiagnostics,
            onCheckUpdates = { checkForUpdates(manual = true) },
            onCompatibility = ::showCompatibility,
            onLicenses = ::openLicensesAsync
        )
        screen.refresh(accountId, accountName, updateChecking)
        content.addView(screen, LinearLayout.LayoutParams(-1, 0, 1f))
        settingsScreen = screen
    }

    private fun showCompatibility() {
        val info = compatibility()
        activeDialog = TvDialog.text(
            context = this,
            title = tr("系统与兼容信息", "系統與相容資訊"),
            body = info,
            actions = listOf(
                TvDialog.Action(tr("复制", "複製")) {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Baha Plus TV", info))
                    showNotice(tr("已复制系统信息", "已複製系統資訊"), isWarning = false)
                },
                TvDialog.Action(tr("返回", "返回"), primary = true)
            ),
            focusIndex = 1
        )
    }

    private fun openLicensesAsync() {
        showNotice(tr("正在读取开源许可…", "正在讀取開源授權…"), isWarning = false)
        ioWorker.execute {
            val text = runCatching {
                listOf(
                    "LICENSE", "NOTICE", "licenses/Apache-2.0.txt",
                    "compat/Babel-LICENSE.txt", "compat/CoreJS-LICENSE.txt"
                ).joinToString("\n\n") { path ->
                    assets.open(path).bufferedReader().use { it.readText() }
                }
            }.getOrNull()
            handler.post {
                if (isFinishing || isDestroyed) return@post
                if (text != null) showText(tr("开源许可", "開源授權"), text)
                else showNotice(tr("无法读取许可文件", "無法讀取授權檔案"))
            }
        }
    }

    private fun openDiagnostics() {
        if (diagnosticsView != null) return
        val view = DiagnosticsView(this, preferences, onStarted = {
            Diagnostics.record(
                "app.state", "screen" to tab, "signedIn" to (accountId != null),
                "lowResources" to preferences.lowResources, "autoNext" to preferences.autoNext,
                "code" to lastError
            )
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
        activeDialog = TvDialog.text(
            context = this,
            title = title,
            body = text,
            actions = listOf(TvDialog.Action(tr("返回", "返回"), primary = true)),
            focusIndex = 0
        )
    }

    private fun showNotice(text: String, isWarning: Boolean = true) {
        if (::notice.isInitialized && playingVideo.isEmpty()) {
            notice.text = text
            notice.setTextColor(if (isWarning) TvStyle.warning else TvStyle.focus)
        }
    }

    private fun checkForUpdates(manual: Boolean) {
        manualUpdateCheck = manualUpdateCheck || manual
        // A manual click can join the startup request; repeated clicks do not start more requests.
        if (updateChecking) return
        updateChecking = true
        settingsScreen?.refresh(accountId, accountName, updateChecking)
        updateChecker.check { result ->
            if (isFinishing || isDestroyed) return@check
            updateChecking = false
            settingsScreen?.refresh(accountId, accountName, updateChecking)
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
            UpdateCheckResult.UpToDate -> showNotice(tr("当前已是最新版本", "目前已是最新版本"), isWarning = false)
            UpdateCheckResult.Failed -> showNotice(tr("检查更新失败，请稍后重试", "檢查更新失敗，請稍後重試"))
            null -> Unit
        }
    }

    private fun showUpdateDialog(release: AppRelease) {
        val message = tr("当前版本：", "目前版本：") + BuildConfig.VERSION_NAME + "\n" +
            tr("最新版本：", "最新版本：") + release.versionName +
            if (release.notes.isBlank()) "" else "\n\n" + release.notes
        activeDialog = TvDialog.confirm(
            context = this,
            title = tr("发现新版本", "發現新版本"),
            message = message,
            actions = listOf(
                TvDialog.Action(tr("取消", "取消")),
                TvDialog.Action(tr("前往 GitHub 下载", "前往 GitHub 下載"), primary = true) {
                    openReleasePage(release.pageUrl)
                }
            ),
            focusIndex = 0
        )
    }

    private fun openReleasePage(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            handler.post {
                if (!isFinishing && !isDestroyed) {
                    showText(
                        tr("未找到浏览器", "找不到瀏覽器"),
                        tr("请在其他设备打开 GitHub 发版页面：\n", "請在其他裝置開啟 GitHub 發布頁面：\n") + url
                    )
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
            handler.post(presentUpdateResult)
        }
    }

    // API 33+ registers OnBackInvokedCallback in onCreate; hardware TV remotes still dispatch KEYCODE_BACK.
    @SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (native?.handle(event) == true) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                backKeyDown = true
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) {
                val triggered = backKeyDown && !event.isCanceled
                backKeyDown = false
                if (triggered) back()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Handled by remote keys and the platform back callback")
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() { back() }

    private fun back() {
        if (diagnosticsView != null) {
            closeDiagnostics()
        } else if (qrLoginView != null || loginOpening) {
            cancelQrLogin()
        } else if (playingVideo.isNotEmpty()) {
            if (native?.handleBack() != true) exitPlayback()
        } else if (detailsOpen) {
            detailLoader.cancel()
            ratingLoader.cancel()
            ratingLoading = false
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
        activityPaused = false
        updateUiResumed = true
        handler.post(presentUpdateResult)
        if (portalAvailable) portal.foreground(true)
        native?.resumeLifecycle()
        if ((catalogFailed || catalog.isEmpty()) && !catalogLoading &&
            playingVideo.isEmpty() && qrLoginView == null && diagnosticsView == null) {
            loadCatalog()
            if (!detailsOpen && (tab == "home" || tab == "schedule")) render()
        }
    }

    override fun onPause() {
        Diagnostics.record("app.paused")
        activityPaused = true
        updateUiResumed = false
        backKeyDown = false
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
        outState.putInt("scheduleDay", scheduleDay)
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
        ratingLoader.destroy()
        ioWorker.shutdownNow()
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
    private fun dp(value: Int) = TvStyle.dp(this, value)
}
