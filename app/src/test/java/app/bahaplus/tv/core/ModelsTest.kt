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
    @Test fun progressRejectsAnUnboundedOrInvalidNativeClock() {
        val valid = JSONObject("""{"master":"https://bahamut.akamaized.net/a.m3u8","variants":[],"position":42,"duration":120,"paused":false,"rate":1}""")
        assertEquals(42.0, NativeSource.parse(valid).position, .001)
        for (position in listOf(-1.0, 99999.0)) {
            val invalid = JSONObject(valid.toString()).put("position", position)
            assertThrows(IllegalArgumentException::class.java) { NativeSource.parse(invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeSource.parse(JSONObject(valid.toString()).put("duration", 172801))
        }
    }
    @Test fun historyRejectsOutOfRangeOfficialProgress() {
        val item = JSONObject("""{"anime":{"id":"1","title":"動畫","cover":"https://p2.bahamut.com.tw/a.jpg","sections":[]},"videoId":"2","episodeLabel":"第 1 集","progress":0.5,"progressLabel":"10:00"}""")
        assertEquals(.5, WatchEntry.parse(item).progress!!, .001)
        assertThrows(IllegalArgumentException::class.java) {
            WatchEntry.parse(JSONObject(item.toString()).put("progress", 2))
        }
    }
}
