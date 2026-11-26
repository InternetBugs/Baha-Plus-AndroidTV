package app.bahaplus.tv.core

import org.junit.Assert.*
import org.junit.Test

class AppStringsTest {
    @Test fun metadataAndProgressLabelsFollowActiveScript() {
        assertEquals("分级说明", AppStrings.metadataLabel(false, "分級說明"))
        assertEquals("作品类型", AppStrings.metadataLabel(false, "作品類型"))
        assertEquals("导演", AppStrings.metadataLabel(false, "導演"))
        assertEquals("制作厂商", AppStrings.metadataLabel(false, "製作廠商"))
        assertEquals("评分", AppStrings.metadataLabel(false, "評分"))
        assertEquals("分級說明", AppStrings.metadataLabel(true, "分級說明"))

        assertEquals("观看结束", AppStrings.progressLabel(false, "觀看結束"))
        assertEquals("觀看結束", AppStrings.progressLabel(true, "觀看結束"))
        assertEquals("12:34", AppStrings.progressLabel(false, "12:34"))
    }

    @Test fun episodeNamesFormatNumericLabelsAndPreserveSpecials() {
        assertEquals("第 12 集", AppStrings.episodeName("12"))
        assertEquals("第 12.5 集", AppStrings.episodeName("12.5"))
        assertEquals("特別篇", AppStrings.episodeName("特別篇"))
        assertEquals("OVA", AppStrings.episodeName("OVA"))
        assertEquals("", AppStrings.episodeName("   "))
    }

    @Test fun scheduleMinuteParsingHandlesValidAndInvalidClockValues() {
        assertEquals(0, UpdateSchedule.parseMinutes("00:00"))
        assertEquals(1439, UpdateSchedule.parseMinutes("23:59"))
        assertEquals(135, UpdateSchedule.parseMinutes("02:15"))
        assertNull(UpdateSchedule.parseMinutes("24:00"))
        assertNull(UpdateSchedule.parseMinutes("invalid"))
        assertNull(UpdateSchedule.parseMinutes(null))
    }
}
