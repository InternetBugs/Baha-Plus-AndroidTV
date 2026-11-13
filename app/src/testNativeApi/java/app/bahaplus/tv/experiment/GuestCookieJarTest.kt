package app.bahaplus.tv.experiment

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class GuestCookieJarTest {
    private val device = URI("https://ani.gamer.com.tw/ajax/getdeviceid.php")
    private val policy = URI("https://ani.gamer.com.tw/ajax/token.php")

    @Test fun officialBareAndDottedParentDomainsUseUnquotedBrowserHeaders() {
        val jar = GuestCookieJar()
        jar.receive(device, mapOf("Set-Cookie" to listOf(
            "ANIME_SIGN=guest-sign; Domain=gamer.com.tw; Path=/",
            "nologinuser=guest; Domain=.gamer.com.tw; Path=/; Secure; HttpOnly")))
        assertEquals("ANIME_SIGN=guest-sign; nologinuser=guest", jar.header(policy))
        assertEquals("ANIME_SIGN=guest-sign; nologinuser=guest",
            jar.header(URI("https://api.gamer.com.tw/anime/v1/video.php")))
        assertEquals(2, jar.count)
    }

    @Test fun hostOnlyAndPathScopeDoNotLeak() {
        val jar = GuestCookieJar()
        jar.receive(device, mapOf("set-cookie" to listOf("local=one; Secure", "specific=two; Path=/ajax/token.php")))
        assertEquals("specific=two; local=one", jar.header(policy))
        assertEquals("", jar.header(URI("https://ani.gamer.com.tw/ajaxother/token.php")))
        assertEquals("local=one", jar.header(URI("https://ani.gamer.com.tw/ajax/token.php.extra")))
        assertEquals("", jar.header(URI("https://api.gamer.com.tw/ajax/token.php")))
    }

    @Test fun expiryReplacementAndUntrustedOriginsAreRespected() {
        val jar = GuestCookieJar()
        jar.receive(device, mapOf("Set-Cookie" to listOf(
            "sign=one; Domain=gamer.com.tw; Path=/; Max-Age=3600",
            "foreign=bad; Domain=example.com; Path=/",
            "suffix=bad; Domain=com.tw; Path=/")))
        assertEquals("sign=one", jar.header(policy))
        for (url in listOf("http://ani.gamer.com.tw/", "https://ani.gamer.com.tw:8443/",
                "https://i2.bahamut.com.tw/", "https://ani.gamer.com.tw.evil.example/")) {
            assertEquals("", jar.header(URI(url)))
            jar.receive(URI(url), mapOf("Set-Cookie" to listOf("injected=bad; Domain=gamer.com.tw; Path=/")))
        }
        jar.receive(device, mapOf("Set-Cookie" to listOf("sign=two; Domain=.gamer.com.tw; Path=/")))
        assertEquals("sign=two", jar.header(policy))
        jar.receive(device, mapOf("Set-Cookie" to listOf("sign=deleted; Domain=gamer.com.tw; Path=/; Max-Age=0")))
        assertEquals("", jar.header(policy))
        jar.clear()
        assertEquals(0, jar.count)
    }
}
