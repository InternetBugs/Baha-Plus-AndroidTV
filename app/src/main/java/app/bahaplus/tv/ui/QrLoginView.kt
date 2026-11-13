package app.bahaplus.tv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import app.bahaplus.tv.core.Preferences
import app.bahaplus.tv.core.QrLoginCode
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** The authorization URL is rendered locally and never sent to a QR image service. */
@SuppressLint("ViewConstructor") // Constructed in code with explicit action callbacks.
class QrLoginView(
    context: Context,
    private val preferences: Preferences,
    onRefresh: () -> Unit,
    onCancel: () -> Unit
) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var code: QrLoginCode? = null
    private var bitmap: Bitmap? = null
    private val image = ImageView(context).apply {
        contentDescription = tr("官方手机登录二维码", "官方手機登入 QR Code")
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.WHITE)
        visibility = INVISIBLE
    }
    private val status = TvStyle.label(context, "", 17f, TvStyle.muted)
    private val refresh = TvStyle.button(context, tr("刷新二维码", "更新 QR Code")) {
        loading()
        onRefresh()
    }
    private val loadingTimeout = Runnable { error() }
    private val updateCountdown = object : Runnable {
        override fun run() {
            val remaining = (code?.expiresAt ?: return) - System.currentTimeMillis() / 1000
            if (remaining <= 0) { error(expired = true); return }
            status.text = tr("等待手机确认 · ${remaining} 秒后过期", "等待手機確認 · ${remaining} 秒後過期")
            handler.postDelayed(this, 1000)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundColor(TvStyle.background)
        setPadding(dp(36), dp(24), dp(36), dp(24))
        isFocusable = false
        addView(image, LayoutParams(dp(256), dp(256)).apply { rightMargin = dp(36) })
        val instructions = TvStyle.vertical(context)
        instructions.addView(TvStyle.label(context, tr("手机扫码登录", "手機掃碼登入"), 28f, bold = true))
        instructions.addView(TvStyle.label(context, tr(
            "用手机相机扫描左侧二维码。\n在巴哈姆特官方网页登录并确认授权。\n电视会自动完成登录。",
            "用手機相機掃描左側 QR Code。\n在巴哈姆特官方網頁登入並確認授權。\n電視會自動完成登入。"), 17f).apply {
            setLineSpacing(dp(5).toFloat(), 1f)
        }, LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(16) })
        instructions.addView(status, LayoutParams(-1, dp(48)))
        instructions.addView(refresh, LayoutParams(-1, dp(44)))
        instructions.addView(TvStyle.button(context, tr("返回", "返回"), onCancel),
            LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
        addView(instructions, LayoutParams(dp(380), -2))
        loading()
        post { refresh.requestFocus() }
    }

    fun loading() {
        clearCode()
        status.text = tr("正在向官方申请二维码…", "正在向官方申請 QR Code…")
        handler.postDelayed(loadingTimeout, 25000)
    }

    fun ready(value: QrLoginCode) {
        clearCode()
        val matrix = QRCodeWriter().encode(value.link, BarcodeFormat.QR_CODE, 512, 512,
            mapOf(EncodeHintType.MARGIN to 4))
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
        }
        bitmap = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.RGB_565)
        image.setImageDrawable(BitmapDrawable(resources, bitmap).apply { isFilterBitmap = false })
        image.visibility = VISIBLE
        code = value
        updateCountdown.run()
    }

    fun retrying() {
        status.text = tr("网络暂不可用，正在重试…", "網路暫時無法使用，正在重試…")
    }

    fun authorizing() {
        clearCode()
        status.text = tr("授权已完成，正在验证电视登录状态…", "授權已完成，正在驗證電視登入狀態…")
        handler.postDelayed(loadingTimeout, 25000)
    }

    fun error(expired: Boolean = false) {
        clearCode()
        status.text = if (expired) tr("二维码已过期，请刷新后重新扫描。", "QR Code 已過期，請更新後重新掃描。")
        else tr("扫码登录未完成，请刷新二维码后重试。", "掃碼登入未完成，請更新 QR Code 後重試。")
    }

    fun dispose() { clearCode() }

    private fun clearCode() {
        handler.removeCallbacksAndMessages(null)
        code = null
        image.visibility = INVISIBLE
        image.setImageDrawable(null)
        bitmap = null
    }

    private fun tr(simplified: String, traditional: String) = preferences.text(simplified, traditional)
    private fun dp(value: Int) = TvStyle.dp(context, value)
}
