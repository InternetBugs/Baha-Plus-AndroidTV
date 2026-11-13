package app.bahaplus.tv.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import app.bahaplus.tv.core.Preferences
import app.bahaplus.tv.diagnostics.Diagnostics
import app.bahaplus.tv.diagnostics.FeedbackServer
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.Executors

@SuppressLint("ViewConstructor")
class DiagnosticsView(
    context: Context,
    private val preferences: Preferences,
    private val onStarted: () -> Unit,
    onClose: () -> Unit
) : LinearLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private val transferWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "feedback-prepare").apply { isDaemon = true }
    }
    private val status = TvStyle.label(context, "", 17f, TvStyle.focus)
    private val hint = TvStyle.label(context, "", 15f, TvStyle.muted)
    private val toggle = TvStyle.button(context, "", ::toggleRecording)
    private val preview = TvStyle.button(context, tr("预览日志", "預覽日誌"), ::previewLog)
    private val send = TvStyle.button(context, tr("发送日志与反馈", "傳送日誌與回報"), ::exportLog)
    private val clear = TvStyle.button(context, tr("清除日志", "清除日誌"), ::clearLog)
    private var busy = false
    private var disposed = false
    private var generation = 0
    private var dialog: AlertDialog? = null
    private var server: FeedbackServer? = null
    private var hasLog = false

    init {
        orientation = VERTICAL
        setBackgroundColor(TvStyle.background)
        setPadding(dp(36), dp(22), dp(36), dp(22))
        addView(TvStyle.label(context, tr("日志与反馈", "日誌與回報"), 28f, bold = true))
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val body = TvStyle.vertical(context)
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))
        body.addView(TvStyle.label(context, tr(
            "开始记录 → 返回复现问题 → 停止记录 → 发送给开发者",
            "開始記錄 → 返回重現問題 → 停止記錄 → 傳送給開發者"), 17f).apply {
            setPadding(0, dp(12), 0, dp(12))
        })
        body.addView(status, LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        body.addView(toggle, LayoutParams(-1, dp(48)))
        body.addView(hint, LayoutParams(-1, -2).apply { topMargin = dp(9); bottomMargin = dp(12) })
        val row = TvStyle.horizontal(context)
        listOf(preview, send, clear).forEach { button ->
            row.addView(button, LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        body.addView(row)
        body.addView(TvStyle.label(context, tr(
            "仅记录应用操作、播放状态、错误码和设备版本，最多约 512 KiB。\n不记录账号、密码、Cookie、搜索词或播放链接；不会自动上传。\n发送前可预览。手机需与电视连接同一局域网；也可使用系统分享。",
            "僅記錄應用程式操作、播放狀態、錯誤碼與裝置版本，最多約 512 KiB。\n不記錄帳號、密碼、Cookie、搜尋詞或播放連結；不會自動上傳。\n傳送前可預覽。手機需與電視連接相同區域網路；也可使用系統分享。"),
            15f, TvStyle.muted).apply { setPadding(0, dp(16), 0, dp(12)) })
        addView(scroll, LayoutParams(-1, 0, 1f))
        addView(TvStyle.button(context, tr("返回，继续操作", "返回，繼續操作"), onClose),
            LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
        refresh()
        post { toggle.requestFocus() }
    }

    private fun refresh(message: String? = null) {
        if (disposed) return
        busy = true
        enableActions()
        Diagnostics.inspect { result ->
            if (disposed) return@inspect
            busy = false
            hasLog = (result.getOrNull() ?: 0) > 0
            status.text = when {
                Diagnostics.failed || result.isFailure -> tr("日志读写失败，请清除后重试。", "日誌讀寫失敗，請清除後重試。")
                Diagnostics.recording -> tr("正在记录 · 返回后继续采集", "正在記錄 · 返回後繼續擷取")
                hasLog -> tr("已停止 · 已保存日志，可预览或发送", "已停止 · 已儲存日誌，可預覽或傳送")
                else -> tr("未开始记录", "尚未開始記錄")
            }
            toggle.text = if (Diagnostics.recording) tr("停止记录", "停止記錄") else tr("开始记录", "開始記錄")
            hint.text = message ?: if (Diagnostics.recording) tr(
                "记录会持续到手动停止、发送或应用进程结束。应用重开后不会自动采集。",
                "記錄會持續到手動停止、傳送或應用程式程序結束。重新開啟後不會自動擷取。")
            else tr("开始新记录会替换上次日志。应用崩溃后可重新打开此页取回已有记录。",
                "開始新記錄會取代上次日誌。應用程式當機後可重新開啟此頁取回已有記錄。")
            enableActions()
        }
    }

    private fun enableActions() {
        toggle.isEnabled = !busy
        preview.isEnabled = !busy && hasLog
        send.isEnabled = !busy && hasLog
        clear.isEnabled = !busy && (hasLog || Diagnostics.failed)
    }

    private fun toggleRecording() {
        if (busy) return
        busy = true
        enableActions()
        val callback: (Result<Unit>) -> Unit = { result ->
            if (!disposed) {
                if (result.isSuccess && Diagnostics.recording) onStarted()
                refresh(if (result.isFailure) tr("无法记录日志，请检查设备可用空间。", "無法記錄日誌，請檢查裝置可用空間。") else null)
            }
        }
        if (Diagnostics.recording) Diagnostics.stop(callback)
        else Diagnostics.start(Diagnostics.environment(context, preferences), callback)
    }

    private fun previewLog() {
        if (busy) return
        busy = true
        enableActions()
        Diagnostics.preview(Diagnostics.environment(context, preferences)) { result ->
            if (disposed) return@preview
            refresh()
            result.onSuccess { text ->
                val scroll = ScrollView(context).apply { isFocusable = true }
                scroll.addView(TvStyle.label(context, text, 15f).apply {
                    setPadding(dp(20), dp(14), dp(20), dp(14))
                })
                dialog = AlertDialog.Builder(context).setTitle(tr("最近日志预览", "最近日誌預覽"))
                    .setView(scroll)
                    .setPositiveButton(tr("复制预览", "複製預覽")) { _, _ ->
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Baha Plus TV log", text))
                    }.setNegativeButton(tr("返回", "返回"), null).show()
            }
        }
    }

    private fun clearLog() {
        if (busy) return
        dialog = AlertDialog.Builder(context).setTitle(tr("清除日志？", "清除日誌？"))
            .setMessage(tr("会停止记录，并删除电视上保存的日志和导出文件。", "將停止記錄，並刪除電視上儲存的日誌與匯出檔案。"))
            .setPositiveButton(tr("清除", "清除")) { _, _ ->
                busy = true
                enableActions()
                Diagnostics.clear { if (!disposed) refresh() }
            }.setNegativeButton(tr("取消", "取消"), null).show()
    }

    private fun exportLog() {
        if (busy) return
        busy = true
        enableActions()
        Diagnostics.export(Diagnostics.environment(context, preferences)) { result ->
            if (disposed) return@export
            refresh()
            result.onSuccess { showTransfer(it) }
                .onFailure { hint.text = tr("导出失败，请检查可用空间后重试。", "匯出失敗，請檢查可用空間後重試。") }
        }
    }

    private fun showTransfer(file: File) {
        stopTransfer()
        val expected = ++generation
        val body = TvStyle.horizontal(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(12))
        }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = tr("手机取日志二维码", "手機取得日誌 QR Code")
            setBackgroundColor(Color.WHITE)
            visibility = INVISIBLE
        }
        body.addView(image, LayoutParams(dp(210), dp(210)).apply { rightMargin = dp(20) })
        val text = TvStyle.label(context, tr("正在准备…", "正在準備…"), 16f)
        body.addView(text, LayoutParams(0, -2, 1f))
        val scroll = ScrollView(context).apply { addView(body) }
        val transferDialog = AlertDialog.Builder(context).setTitle(tr("用手机发送反馈", "用手機傳送回報"))
            .setView(scroll)
            .setNeutralButton(tr("系统分享文件", "系統分享檔案")) { _, _ -> shareFile(file) }
            .setPositiveButton(tr("关闭", "關閉"), null).create()
        dialog = transferDialog
        transferDialog.setOnDismissListener { stopTransfer() }
        transferDialog.show()
        keepScreenOn = true
        transferWorker.execute {
            val result = runCatching {
                val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val address = connectivity.getLinkProperties(connectivity.activeNetwork)?.linkAddresses
                    ?.map { it.address }?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
                    ?: error("no-lan-address")
                FeedbackServer(address, file.readBytes(), onClosed = {
                    main.post {
                        if (!disposed && expected == generation) {
                            keepScreenOn = false
                            image.setImageDrawable(null)
                            image.visibility = INVISIBLE
                            text.text = tr("下载已结束，请关闭窗口后重新发送。", "下載已結束，請關閉視窗後重新傳送。")
                        }
                    }
                })
            }
            main.post {
                if (disposed || expected != generation || !transferDialog.isShowing) {
                    result.getOrNull()?.close()
                    return@post
                }
                result.onSuccess { value ->
                    server = value
                    val matrix = QRCodeWriter().encode(value.url, BarcodeFormat.QR_CODE, 420, 420,
                        mapOf(EncodeHintType.MARGIN to 4))
                    val pixels = IntArray(matrix.width * matrix.height) { index ->
                        if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
                    }
                    image.setImageBitmap(Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.RGB_565))
                    image.visibility = VISIBLE
                    text.text = tr(
                        "手机和电视连接同一局域网后扫码。\n下载日志，再前往 GitHub 填写问题并附上文件（需 GitHub 账号），或用你已有的联系方式发给开发者。\n\n保持此窗口打开，链接 10 分钟有效。\n${value.url}",
                        "手機與電視連接相同區域網路後掃碼。\n下載日誌，再前往 GitHub 填寫問題並附上檔案（需 GitHub 帳號），或使用既有聯絡方式傳給開發者。\n\n保持此視窗開啟，連結 10 分鐘有效。\n${value.url}")
                }.onFailure {
                    keepScreenOn = false
                    text.text = tr(
                        "无法建立局域网下载。请确认电视已连接 Wi-Fi 或有线网络。\n也可选择下方“系统分享文件”，通过设备上可用的应用发送日志。",
                        "無法建立區域網路下載。請確認電視已連接 Wi-Fi 或有線網路。\n也可選擇下方「系統分享檔案」，透過裝置上可用的應用程式傳送日誌。")
                }
            }
        }
    }

    private fun shareFile(file: File) {
        val result = runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.feedback", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Baha Plus TV 问题反馈")
                putExtra(Intent.EXTRA_TEXT, tr("请说明问题和复现步骤，并附上日志。\n", "請說明問題與重現步驟，並附上日誌。\n") + FeedbackServer.ISSUE_URL)
                clipData = ClipData.newRawUri("Baha Plus TV log", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, tr("发送日志给开发者", "傳送日誌給開發者")))
        }
        if (result.isFailure) hint.text = tr("设备没有可用的分享应用。请改用手机扫码下载。", "裝置沒有可用的分享應用程式。請改用手機掃碼下載。")
    }

    fun stopTransfer() {
        generation++
        server?.close()
        server = null
        keepScreenOn = false
    }

    /** Pause the temporary download when the TV screen leaves the foreground. */
    fun background() {
        stopTransfer()
        dialog?.dismiss()
        dialog = null
    }

    fun dispose() {
        disposed = true
        background()
        transferWorker.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }

    private fun tr(simplified: String, traditional: String) = preferences.text(simplified, traditional)
    private fun dp(value: Int) = TvStyle.dp(context, value)
}
