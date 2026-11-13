package app.bahaplus.tv.core

enum class DanmakuMode { ROLLING, TOP, BOTTOM }

data class Danmaku(
    val id: String,
    val text: String,
    val timeMs: Long,
    val mode: DanmakuMode,
    val size: Int,
    val color: Int
) {
    val durationMs: Long get() = if (mode == DanmakuMode.ROLLING) 10_000 else 5_000
}

data class DanmakuSettings(
    val enabled: Boolean = true,
    val scale: Float = 1f,
    val opacity: Int = 80,
    val area: Int = 75,
    val rolling: Boolean = true,
    val top: Boolean = true,
    val bottom: Boolean = true
) {
    fun accepts(item: Danmaku) = enabled && when (item.mode) {
        DanmakuMode.ROLLING -> rolling
        DanmakuMode.TOP -> top
        DanmakuMode.BOTTOM -> bottom
    }
}

data class DanmakuPlacement(val item: Danmaku, val lane: Int) {
    val endMs: Long get() = item.timeMs + item.durationMs
}

/**
 * Advance a sorted cursor rather than scanning an episode on every frame.
 * A message keeps its lane for its entire lifetime. Crowded messages are skipped
 * once, so they cannot appear halfway across the screen when another expires.
 */
class DanmakuTimeline(private val items: List<Danmaku>) {
    private val visible = ArrayList<DanmakuPlacement>()
    val active: List<DanmakuPlacement> get() = visible
    private var cursor = 0
    private var lastPosition = -1L
    private var lanes = 0

    fun update(positionMs: Long, laneCount: Int, discontinuity: Boolean = false) {
        val position = positionMs.coerceAtLeast(0)
        val count = laneCount.coerceIn(0, 12)
        if (lastPosition < 0 || discontinuity || position < lastPosition ||
            position - lastPosition > 10_000 || lanes != count) {
            visible.clear()
            cursor = lowerBound((position - 10_000).coerceAtLeast(0))
        }
        lanes = count
        lastPosition = position
        // Reconstruct lane decisions in time order, including messages that
        // occupied a lane earlier in the visible window but have now expired.
        while (cursor < items.size && items[cursor].timeMs <= position) {
            val item = items[cursor++]
            visible.removeAll { it.endMs <= item.timeMs }
            val candidates = if (item.mode == DanmakuMode.BOTTOM) (count - 1 downTo 0)
                else (0 until count)
            val lane = candidates.firstOrNull { candidate -> visible.none { it.lane == candidate } }
            if (lane != null) visible += DanmakuPlacement(item, lane)
        }
        visible.removeAll { it.endMs <= position }
    }

    fun nextChangeMs(): Long? {
        var next = items.getOrNull(cursor)?.timeMs
        for (item in visible) if (next == null || item.endMs < next) next = item.endMs
        return next
    }

    private fun lowerBound(position: Long): Int {
        var low = 0
        var high = items.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (items[middle].timeMs < position) low = middle + 1 else high = middle
        }
        return low
    }
}
