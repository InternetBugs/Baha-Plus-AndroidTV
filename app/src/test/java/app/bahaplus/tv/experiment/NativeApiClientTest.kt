package app.bahaplus.tv.experiment

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI

class NativeApiClientTest {
    private class Response(uri: URI, private val body: String) : HttpURLConnection(uri.toURL()) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getHeaderFields(): Map<String?, List<String>> = emptyMap()
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getContentLength() = -1
    }

    private fun body(path: String, ad: Boolean): String = when (path) {
        "/animeVideo.php" -> "<html></html>"
        "/ajax/getdeviceid.php" -> """{"deviceid":"device-one"}"""
        "/anime/v1/video.php" -> """{"data":{"video":{"videoSn":10,"title":"動畫","rating":1,"duration":24}}}"""
        "/ajax/token.php" -> """{"time":${if (ad) 30 else 1},"r18":1}"""
        "/JS/ad/animeVideo.js" -> """function(config){ var adlist = [["123",0,"45","video"]]; }"""
        "/anime/v1/video_src.php" -> """{"data":{"deviceid":"device-two","srcUseCases":[
          {"deviceType":1,"src":{"playlist":"https://bahamut.akamaized.net/test/playlist.m3u8"}}]}}"""
        "/ajax/elapse.php" -> """{"state":"ok","device":"device-three"}"""
        else -> "{}"
    }

    @Test fun nativeSessionUsesLoginCookieSnapshotAndRotatedDeviceWithoutAnyBrowserBridge() {
        val requests = mutableListOf<URI>()
        val connections = mutableListOf<Response>()
        val session = NativeApiClient(mapOf("ani.gamer.com.tw" to "account=private",
            "api.gamer.com.tw" to "account=private"), openConnection = { uri ->
            requests += uri
            Response(uri, body(uri.path, false)).also { connections += it }
        }) { _, _ -> }
        session.prepare("10")
        assertThrows(IllegalStateException::class.java) { session.source() }
        assertTrue(requests.none { it.path.endsWith("video_src.php") })
        session.consent()
        assertEquals("https://bahamut.akamaized.net/test/playlist.m3u8", session.source())
        session.started()
        assertEquals("ok", session.progress(30.0, 1440.0))
        session.state(60.0)
        assertTrue(requests.first { it.path == "/ajax/checklock.php" }.query.contains("device=device-two"))
        assertTrue(connections.all { it.getRequestProperty("Cookie") == "account=private" })
        assertTrue(connections.all { !it.instanceFollowRedirects })
        session.close()
    }

    @Test fun sourceStaysGatedUntilConsentAndRealAdEventsComplete() {
        val requests = mutableListOf<URI>()
        val session = NativeApiClient(openConnection = { uri ->
            requests += uri
            Response(uri, body(uri.path, true))
        }) { _, _ -> }
        assertTrue(session.prepare("10").needsAd)
        session.consent()
        assertThrows(IllegalStateException::class.java) { session.source() }
        session.startAd()
        assertThrows(IllegalStateException::class.java) { session.endAd(29.9) }
        assertThrows(IllegalStateException::class.java) { session.source() }
        session.endAd(30.0)
        session.source()
        assertEquals(1, requests.count { it.path.endsWith("video_src.php") })
        assertEquals(2, requests.count { it.path.endsWith("videoCastcishu.php") })
        session.close()
    }

    @Test fun retiredSessionCannotContinueOrSendMoreProgress() {
        var current = true
        var count = 0
        val session = NativeApiClient(isCurrent = { current }, openConnection = { uri ->
            count++; Response(uri, body(uri.path, false))
        }) { _, _ -> }
        session.prepare("10")
        current = false
        val before = count
        assertThrows(ApiFailure::class.java) { session.progress(30.0, 1440.0) }
        assertEquals(before, count)
        session.close()
    }
}
