package app.bahaplus.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView

class RemoteCursor(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TvStyle.focus
        strokeWidth = TvStyle.dp(context, 3).toFloat()
        style = Paint.Style.STROKE
    }
    private var cursorX = 0f
    private var cursorY = 0f
    private var positioned = false
    var active = false
        set(value) {
            field = value
            if (value) {
                positioned = false
                positionIfReady()
            }
            visibility = if (value) VISIBLE else GONE
            invalidate()
        }
    init { isFocusable = false; isClickable = false; visibility = GONE }
    private fun positionIfReady() {
        if (!positioned && width > 2 && height > 2) {
            cursorX = width / 2f
            cursorY = height / 2f
            positioned = true
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 2 || h <= 2) {
            positioned = false
            return
        }
        positionIfReady()
        if (positioned) {
            cursorX = cursorX.coerceIn(1f, (w - 1).coerceAtLeast(1).toFloat())
            cursorY = cursorY.coerceIn(1f, (h - 1).coerceAtLeast(1).toFloat())
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active) return
        val radius = TvStyle.dp(context, 10).toFloat()
        paint.color = android.graphics.Color.BLACK
        paint.strokeWidth = TvStyle.dp(context, 6).toFloat()
        canvas.drawCircle(cursorX, cursorY, radius, paint)
        paint.color = TvStyle.focus
        paint.strokeWidth = TvStyle.dp(context, 3).toFloat()
        canvas.drawCircle(cursorX, cursorY, radius, paint)
        canvas.drawLine(cursorX - radius * 1.5f, cursorY, cursorX + radius * 1.5f, cursorY, paint)
        canvas.drawLine(cursorX, cursorY - radius * 1.5f, cursorX, cursorY + radius * 1.5f, paint)
    }
    fun key(event: KeyEvent, browser: WebView): Boolean {
        if (!active) return false
        if (event.keyCode !in intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK)) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) active = false
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val step = TvStyle.dp(context, if (event.repeatCount > 8) 24 else 10).toFloat()
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> cursorX -= step
            KeyEvent.KEYCODE_DPAD_RIGHT -> cursorX += step
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (cursorY <= step) browser.scrollBy(0, -TvStyle.dp(context, 60))
                cursorY -= step
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (cursorY >= height - step) browser.scrollBy(0, TvStyle.dp(context, 60))
                cursorY += step
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.repeatCount > 0) return true
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
                val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, cursorX, cursorY, 0)
                browser.dispatchTouchEvent(down)
                browser.dispatchTouchEvent(up)
                down.recycle()
                up.recycle()
            }
        }
        cursorX = cursorX.coerceIn(1f, (width - 1).coerceAtLeast(1).toFloat())
        cursorY = cursorY.coerceIn(1f, (height - 1).coerceAtLeast(1).toFloat())
        invalidate()
        return true
    }
}
