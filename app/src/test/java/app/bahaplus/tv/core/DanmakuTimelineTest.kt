package app.bahaplus.tv.core

import org.junit.Assert.*
import org.junit.Test

class DanmakuTimelineTest {
    private fun item(id: String, time: Long, mode: DanmakuMode = DanmakuMode.ROLLING) =
        Danmaku(id, "弹幕$id", time, mode, 24, -1)

    @Test fun lanesStayStableAndCrowdedMessagesDoNotAppearLate() {
        val timeline = DanmakuTimeline(listOf(item("1", 0), item("2", 1000), item("3", 2000)))
        timeline.update(2000, 2)
        assertEquals(listOf("1", "2"), timeline.active.map { it.item.id })
        assertEquals(listOf(0, 1), timeline.active.map { it.lane })
        timeline.update(10_000, 2)
        assertEquals("2", timeline.active.single().item.id)
        assertEquals(1, timeline.active.single().lane)
        timeline.update(11_000, 2)
        assertTrue(timeline.active.isEmpty())
        assertNull(timeline.nextChangeMs())
    }

    @Test fun seeksRebuildAtTheMediaPositionIncludingPausedSmallSeeks() {
        val timeline = DanmakuTimeline(listOf(item("1", 0), item("2", 20_000), item("3", 40_000)))
        timeline.update(5000, 2)
        assertEquals("1", timeline.active.single().item.id)
        timeline.update(22_000, 2, discontinuity = true)
        assertEquals("2", timeline.active.single().item.id)
        timeline.update(2000, 2, discontinuity = true)
        assertEquals("1", timeline.active.single().item.id)
        timeline.update(10_001, 2, discontinuity = true)
        assertTrue(timeline.active.isEmpty())
        timeline.update(9999, 2, discontinuity = true)
        assertEquals("1", timeline.active.single().item.id)
    }

    @Test fun fixedModesReserveOppositeEdgesAndSleepUntilTheirExpiry() {
        val timeline = DanmakuTimeline(listOf(item("1", 0, DanmakuMode.TOP),
            item("2", 0, DanmakuMode.BOTTOM), item("3", 8000)))
        timeline.update(0, 4)
        assertEquals(listOf(0, 3), timeline.active.map { it.lane })
        assertEquals(5000L, timeline.nextChangeMs())
        timeline.update(5000, 4)
        assertTrue(timeline.active.isEmpty())
        assertEquals(8000L, timeline.nextChangeMs())
        timeline.update(8000, 4)
        assertEquals("3", timeline.active.single().item.id)
    }

    @Test fun crowdedEpisodeHasBoundedVisibleWorkAndZeroLanesAreAllowed() {
        val timeline = DanmakuTimeline((1..20_000).map { item("$it", it.toLong()) })
        timeline.update(20_000, 6)
        assertTrue(timeline.active.size <= 6)
        timeline.update(20_000, 0)
        assertTrue(timeline.active.isEmpty())
        timeline.update(20_000, 12)
        assertTrue(timeline.active.size <= 12)
    }

    @Test fun settingsFilterOnlyEnabledModes() {
        val rolling = item("1", 0)
        val top = item("2", 0, DanmakuMode.TOP)
        val bottom = item("3", 0, DanmakuMode.BOTTOM)
        assertTrue(DanmakuSettings().accepts(rolling))
        assertFalse(DanmakuSettings(enabled = false).accepts(rolling))
        assertFalse(DanmakuSettings(rolling = false).accepts(rolling))
        assertFalse(DanmakuSettings(top = false).accepts(top))
        assertFalse(DanmakuSettings(bottom = false).accepts(bottom))
    }
}
