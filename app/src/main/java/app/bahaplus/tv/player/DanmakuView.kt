package app.bahaplus.tv.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.media3.common.Player
import app.bahaplus.tv.core.Danmaku
import app.bahaplus.tv.core.DanmakuMode
import app.bahaplus.tv.core.DanmakuSettings
import app.bahaplus.tv.core.DanmakuTimeline
import kotlin.math.ceil
import kotlin.math.min

/** One Canvas over the video; no per-message Views, bitmaps, or browser. */
@SuppressLint("ViewConstructor") // The media player is supplied by NativePlayback, never XML.
internal class DanmakuView(
    context: Context,
    private val player: Player,
    private val lowResources: Boolean
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
    private val density = resources.displayMetrics.density
    private var settings = DanmakuSettings()
    private var items = emptyList<Danmaku>()
    private var timeline = DanmakuTimeline(emptyList())
    private val widths = object : LinkedHashMap<String, Float>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?) = size > 64
    }
    private var contentActive = false
    private var foreground = true
    private var disposed = false
    private var positionMs = 0L
    private var laneHeight = 1f
    private var laneCount = 0
    private val frame = Runnable { refresh() }

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun configure(value: DanmakuSettings) {
        settings = value
        measureLanes()
        resetItems()
    }

    fun setItems(value: List<Danmaku>) {
        items = value
        resetItems()
    }

    private fun resetItems() {
        widths.clear()
        timeline = DanmakuTimeline(items.filter(settings::accepts))
        refresh(discontinuity = true)
    }

    fun activate() {
        contentActive = true
        refresh(discontinuity = true)
    }

    fun setForeground(value: Boolean) {
        foreground = value
        refresh()
    }

    fun refresh(discontinuity: Boolean = false) {
        removeCallbacks(frame)
        if (disposed || !isAttachedToWindow) return
        if (!contentActive || !foreground || !settings.enabled) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        positionMs = player.currentPosition.coerceAtLeast(0)
        timeline.update(positionMs, laneCount, discontinuity)
        invalidate()
        if (!player.isPlaying || laneCount == 0) return
        val rolling = timeline.active.any { it.item.mode == DanmakuMode.ROLLING }
        if (rolling) {
            postOnAnimationDelayed(frame, if (lowResources) 32L else 16L)
        } else {
            // Fixed comments repaint only at entry/expiry. Empty intervals sleep.
            val next = timeline.nextChangeMs() ?: return
            val rate = player.playbackParameters.speed.toDouble().coerceAtLeast(.1)
            postDelayed(frame, ceil((next - positionMs).coerceAtLeast(1) / rate).toLong())
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh(discontinuity = true)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        measureLanes()
        refresh(discontinuity = true)
    }

    private fun measureLanes() {
        val available = (height * settings.area / 100f - 24f * density).coerceAtLeast(0f)
        laneCount = min(if (lowResources) 6 else 12, (available / (34f * density * settings.scale)).toInt())
        laneHeight = if (laneCount > 0) available / laneCount else 1f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val alpha = settings.opacity * 255 / 100
        val active = timeline.active
        for (index in active.indices) {
            val placement = active[index]
            val item = placement.item
            paint.textSize = item.size * density * settings.scale
            val textWidth = widths[item.id] ?: paint.measureText(item.text).also { widths[item.id] = it }
            val x = if (item.mode == DanmakuMode.ROLLING) {
                val elapsed = (positionMs - item.timeMs).toFloat() / item.durationMs
                width - (width + textWidth) * elapsed
            } else (width - textWidth) / 2f
            val y = 12f * density + placement.lane * laneHeight +
                (laneHeight + paint.textSize) / 2 - paint.descent()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * density
            paint.color = Color.BLACK
            paint.alpha = alpha
            canvas.drawText(item.text, x, y, paint)
            paint.style = Paint.Style.FILL
            paint.color = item.color
            paint.alpha = alpha
            canvas.drawText(item.text, x, y, paint)
        }
    }

    fun release() {
        disposed = true
        removeCallbacks(frame)
        items = emptyList()
        timeline = DanmakuTimeline(emptyList())
        widths.clear()
        visibility = GONE
    }
}
