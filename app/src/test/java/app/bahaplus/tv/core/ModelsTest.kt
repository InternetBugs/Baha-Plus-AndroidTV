package app.bahaplus.tv.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
    @Test fun navigationRejectsImpersonationCredentialsAndInsecureSchemes() {
        assertTrue(OfficialSite.allowedNavigation("https://user.gamer.com.tw/login.php"))
        assertTrue(OfficialSite.trusted("https://ani.gamer.com.tw/"))
        listOf("https://gamer.com.tw.attacker.invalid/", "https://attacker.invalid/",
            "http://ani.gamer.com.tw/", "javascript:alert(1)", "https://user:secret@ani.gamer.com.tw/",
            "https://ani.gamer.com.tw:444/").forEach { assertFalse(it, OfficialSite.allowedNavigation(it)) }
        assertFalse(OfficialSite.trusted("https://user.gamer.com.tw/"))
    }
    @Test fun nativeSourcesRemainOnTheAuthorizedCdn() {
        assertTrue(OfficialSite.media("https://bahamut.akamaized.net/a/master.m3u8?authorization=example"))
        listOf("http://bahamut.akamaized.net/a.m3u8", "https://bahamut.akamaized.net.attacker.invalid/a.m3u8",
            "https://user:secret@bahamut.akamaized.net/a.m3u8", "https://bahamut.akamaized.net/a.m3u8#fragment",
            "https://bahamut.akamaized.net/a.mp4").forEach { assertFalse(it, OfficialSite.media(it)) }
    }
    @Test fun historyRejectsOutOfRangeOfficialProgress() {
        val item = JSONObject("""{"anime":{"id":"1","title":"動畫","cover":"https://p2.bahamut.com.tw/a.jpg","sections":[]},"videoId":"2","episodeLabel":"第 1 集","progress":0.5,"progressLabel":"10:00"}""")
        assertEquals(.5, WatchEntry.parse(item).progress!!, .001)
        assertThrows(IllegalArgumentException::class.java) {
            WatchEntry.parse(JSONObject(item.toString()).put("progress", 2))
        }
    }
    @Test fun qrLoginAcceptsOnlyAnUnexpiredOfficialAuthorizationLink() {
        val link = "https://user.gamer.com.tw/link_login.php?token=" + "a".repeat(288)
        fun body(value: String = link, expiry: Long = 1180) =
            JSONObject().put("link", value).put("expiresAt", expiry)
        assertEquals(link, QrLoginCode.parse(body(), 1000).link)
        for (invalid in listOf(
            link.replace("user.gamer.com.tw", "user.gamer.com.tw.attacker.invalid"),
            link.replace("https:", "http:"), "$link#fragment", "$link&token=duplicate",
            link.replace("user.gamer", "evil@user.gamer"), link.replace("/link_login.php", "/login.php")
        )) {
            assertThrows(IllegalArgumentException::class.java) { QrLoginCode.parse(body(invalid), 1000) }
        }
        for (expiry in listOf(1000L, 999L, 1601L)) {
            assertThrows(IllegalArgumentException::class.java) { QrLoginCode.parse(body(expiry = expiry), 1000) }
        }
    }

    @Test fun scheduleTimesAreOptionalAndValidated() {
        fun anime(day: Any?, time: Any?) = JSONObject().put("id", "1").put("title", "動畫")
            .put("cover", "https://p2.bahamut.com.tw/a.jpg")
            .apply { if (day != null) put("updateDay", day); if (time != null) put("updateTime", time) }
        val value = Anime.parse(anime(0, "23:30"))
        assertEquals(0, value.updateDay)
        assertEquals("23:30", value.updateTime)
        assertNull(Anime.parse(anime(7, "25:00")).updateDay)
        assertNull(Anime.parse(anime(0, "9:30")).updateTime)
        assertNull(Anime.parse(anime(null, null)).updateTime)
        assertThrows(IllegalArgumentException::class.java) { value.copy(updateTime = "24:00") }
    }

    @Test fun scheduleUsesTaipeiWeekdayAndSortsKnownTimesFirst() {
        assertEquals(1, UpdateSchedule.weekday(java.time.Instant.parse("2024-01-01T00:00:00Z").toEpochMilli()))
        assertEquals(0, UpdateSchedule.weekday(java.time.Instant.parse("2024-01-07T00:00:00Z").toEpochMilli()))
        assertEquals(2, UpdateSchedule.weekday(java.time.Instant.parse("2024-01-01T16:00:00Z").toEpochMilli()))
        fun anime(id: String, day: Int?, time: String?, sections: List<String> = listOf("season")) =
            Anime(id, "動畫$id", "https://p2.bahamut.com.tw/a.jpg", sections = sections,
                updateDay = day, updateTime = time)
        val catalog = listOf(anime("1", 3, null), anime("2", 3, "23:30"), anime("3", 3, "05:00"),
            anime("4", 2, "10:00"), anime("5", 3, "12:00", listOf("hot")))
        assertEquals(listOf("3", "2", "1"), UpdateSchedule.day(catalog, 3).map { it.id })
    }
}
