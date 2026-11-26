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
            pruneExpired(item.timeMs)
            val lane = findLane(item, count)
            if (lane != null) visible += DanmakuPlacement(item, lane)
        }
        pruneExpired(position)
    }

    private fun pruneExpired(nowMs: Long) {
        var index = visible.size - 1
        while (index >= 0) {
            if (visible[index].endMs <= nowMs) visible.removeAt(index)
            index--
        }
    }

    private fun findLane(item: Danmaku, count: Int): Int? {
        if (count <= 0) return null
        if (item.mode == DanmakuMode.BOTTOM) {
            for (candidate in count - 1 downTo 0) {
                if (laneAvailable(candidate, item)) return candidate
            }
            return null
        }
        for (candidate in 0 until count) {
            if (laneAvailable(candidate, item)) return candidate
        }
        return null
    }

    /**
     * Rolling messages release their lane entry once the prior rolling comment has safely
     * cleared the right edge + catch-up margin (ROLLING_ENTRY_COOLDOWN_MS). Fixed TOP/BOTTOM
     * comments keep their lane exclusively for their entire 5s lifetime.
     */
    private fun laneAvailable(lane: Int, incoming: Danmaku): Boolean {
        for (index in visible.indices) {
            val existing = visible[index]
            if (existing.lane != lane) continue
            if (incoming.mode != DanmakuMode.ROLLING || existing.item.mode != DanmakuMode.ROLLING) {
                return false
            }
            if (incoming.timeMs - existing.item.timeMs < ROLLING_ENTRY_COOLDOWN_MS) {
                return false
            }
        }
        return true
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

    companion object {
        const val ROLLING_ENTRY_COOLDOWN_MS = 3_500L
        const val MAX_PER_LANE = 3
    }
}
