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
}
