package app.bahaplus.tv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.bahaplus.tv.BuildConfig
import app.bahaplus.tv.R
import app.bahaplus.tv.core.AppStrings
import app.bahaplus.tv.core.Preferences
import app.bahaplus.tv.diagnostics.Diagnostics

/** In-place settings screen: toggling options updates button text without tearing down ScrollView. */
@SuppressLint("ViewConstructor")
internal class SettingsScreen(
    context: Context,
    private val preferences: Preferences,
    private val leftTargetId: Int,
    initialFocusKey: String,
    initialScrollY: Int,
    private val onLeft: () -> Unit,
    private val onFocusChanged: (String) -> Unit,
    private val onToggleLowResources: () -> Unit,
    private val onToggleAutoNext: () -> Unit,
    private val onToggleLanguage: () -> Unit,
    private val onAccount: () -> Unit,
    private val onRefresh: () -> Unit,
    private val onOpenDiagnostics: () -> Unit,
    private val onCheckUpdates: () -> Unit,
    private val onCompatibility: () -> Unit,
    private val onLicenses: () -> Unit
) : ScrollView(context) {
    private val buttons = HashMap<String, TextView>()
    private val resourcesButton: TextView
    private val autoNextButton: TextView
    private val languageButton: TextView
    private val accountButton: TextView
    private val diagnosticsDescription: TextView
    private val updateButton: TextView

    init {
        isFillViewport = true
        val list = TvStyle.vertical(context)
        addView(list, FrameLayout.LayoutParams(-1, -2))

        fun addSetting(key: String, label: String, description: String, action: () -> Unit): Pair<TextView, TextView> {
            val button = TvStyle.button(context, label, action).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                textSize = 17f
                nextFocusLeftId = leftTargetId
                setOnKeyListener { _, code, event ->
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                        onLeft(); true
                    } else false
                }
                setOnFocusChangeListener { _, focused -> if (focused) onFocusChanged(key) }
            }
            val desc = TvStyle.label(context, description, 13f, TvStyle.muted).apply {
                setPadding(dp(12), dp(6), dp(12), dp(12))
            }
            list.addView(button, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })
            list.addView(desc)
            buttons[key] = button
            return button to desc
        }

        resourcesButton = addSetting(
            "resources",
            "",
            tr("减少动画、封面尺寸、图片缓存与预加载；视频画质单独设置。", "減少動畫、封面尺寸、圖片快取與預先載入；影片畫質獨立設定。"),
            onToggleLowResources
        ).first
        autoNextButton = addSetting(
            "autoNext",
            "",
            tr("同一作品有下一集时自动继续。", "同一作品有下一集時自動繼續。"),
            onToggleAutoNext
        ).first
        languageButton = addSetting(
            "language",
            "",
            tr("番剧标题和简介保留官方原文。", "動畫標題與簡介保留官方原文。"),
            onToggleLanguage
        ).first
        accountButton = addSetting(
            "account",
            "",
            tr("登录和观看进度由官方机制处理。", "登入與觀看進度由官方機制處理。"),
            onAccount
        ).first
        addSetting(
            "refresh",
            tr("刷新官方内容", "重新整理官方內容"),
            tr("重新读取首页、追番与观看历史。", "重新讀取首頁、追番與觀看紀錄。"),
            onRefresh
        )
        diagnosticsDescription = addSetting(
            "diagnostics",
            tr("日志与反馈", "日誌與回報"),
            "",
            onOpenDiagnostics
        ).second
        updateButton = addSetting(
            "updates",
            "",
            tr("当前版本 ${BuildConfig.VERSION_NAME} · 从 GitHub 检查新版本", "目前版本 ${BuildConfig.VERSION_NAME} · 從 GitHub 檢查新版本"),
            onCheckUpdates
        ).first
        addSetting(
            "compatibility",
            tr("系统与兼容信息", "系統與相容資訊"),
            tr("查看版本信息，或复制不含账号凭据的诊断信息。", "查看版本資訊，或複製不含帳號憑證的診斷資訊。"),
            onCompatibility
        )
        addSetting(
            "licenses",
            tr("开源许可", "開源授權"),
            "Baha Plus TV · MIT / Apache 2.0",
            onLicenses
        )

        post { scrollTo(0, initialScrollY) }
        buttons[initialFocusKey]?.let { /* focus handled explicitly by MainActivity.focusContent() */ }
    }

    fun refresh(accountId: String?, accountName: String, updateChecking: Boolean) {
        val trad = preferences.traditional
        resourcesButton.text = context.getString(
            R.string.setting_value,
            tr("低资源模式", "低資源模式"),
            AppStrings.enabled(trad, preferences.lowResources)
        )
        autoNextButton.text = context.getString(
            R.string.setting_value,
            tr("自动播放下一集", "自動播放下一集"),
            AppStrings.enabled(trad, preferences.autoNext)
        )
        languageButton.text = tr("界面语言：简体中文", "介面語言：繁體中文")
        accountButton.text = if (accountId == null) tr("登录账号", "登入帳號") else tr("账号：$accountName", "帳號：$accountName")
        diagnosticsDescription.text = if (Diagnostics.recording) {
            tr("正在记录。复现问题后在这里停止并发送日志。", "正在記錄。重現問題後在此停止並傳送日誌。")
        } else {
            tr("记录问题过程，手机扫码取走日志并反馈给开发者。", "記錄問題過程，手機掃碼取得日誌並回報給開發者。")
        }
        updateButton.text = if (updateChecking) tr("正在检查更新…", "正在檢查更新…") else tr("检查更新", "檢查更新")
    }

    fun focusSetting(key: String) {
        (buttons[key] ?: resourcesButton).requestFocus()
    }

    private fun tr(simplified: String, traditional: String) = preferences.text(simplified, traditional)
    private fun dp(value: Int) = TvStyle.dp(context, value)
}
