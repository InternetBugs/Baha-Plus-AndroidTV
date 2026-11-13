package app.bahaplus.tv.data

import app.bahaplus.tv.core.Anime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class OfficialAnimeApiTest {
    private val anime = Anime("10", "測試動畫", "https://p2.bahamut.com.tw/a.jpg", latestVideoId = "20")

    internal class Response(
        url: URL,
        private val payload: String,
        private val status: Int = 200,
        private val mime: String = "application/json; charset=utf-8",
        private val length: Int = payload.toByteArray(Charsets.UTF_8).size
    ) : HttpURLConnection(url) {
        var disconnected = false
        var streamOpened = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentType() = mime
        override fun getContentLength() = length
        override fun getInputStream(): ByteArrayInputStream {
            streamOpened = true
            return ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8))
        }
    }

    @Test fun mapsOnlyPublicDetailsAndPreservesEpisodeAndSpecialGroupOrder() {
        lateinit var response: Response
        val client = OfficialAnimeApi { url -> Response(url, fixture().toString()).also { response = it } }
        val detail = client.detail(anime)
        assertEquals("https://api.gamer.com.tw/mobile_app/anime/v4/video.php?sn=20", response.url.toString())
        assertEquals("測試動畫", detail.title)
        assertEquals("作品簡介。", detail.description)
        assertEquals(listOf("20", "21", "22"), detail.episodes.map { it.id })
        assertEquals(listOf("1", "2", "特別篇"), detail.episodes.map { it.label })
        assertTrue(detail.metadata.contains("評分" to "4.3 / 5"))
        assertTrue(detail.metadata.contains("作品類型" to "奇幻、冒險"))
        assertFalse(detail.toString().contains("account-private"))
        assertNull(response.getRequestProperty("Cookie"))
        assertNull(response.getRequestProperty("Authorization"))
        assertEquals("application/json", response.getRequestProperty("Accept"))
        assertEquals(12000, response.connectTimeout)
        assertEquals(12000, response.readTimeout)
        assertFalse(response.instanceFollowRedirects)
        assertFalse(response.useCaches)
        assertTrue(response.disconnected)
    }

    @Test fun seriesOnlyCardsResolveVideoBySeriesIdRatherThanTitleOrFirstResult() {
        val urls = mutableListOf<String>()
        val client = OfficialAnimeApi { url ->
            urls += url.toString()
            val payload = if (url.path.endsWith("search.php")) {
                """{"data":{"animeList":[{"animeSn":11,"videoSn":99},{"animeSn":10,"videoSn":20}]}}"""
            } else fixture().toString()
            Response(url, payload)
        }
        assertEquals("10", client.detail(anime.copy(latestVideoId = "")).animeId)
        assertEquals(2, urls.size)
        assertTrue(urls[0].startsWith("https://api.gamer.com.tw/anime/v1/search.php?kw="))
        assertTrue(urls[1].endsWith("video.php?sn=20"))
    }

    @Test fun resumeEpisodeIsUsedWithoutSearchEvenWhenTheCardHasNoVideoId() {
        val urls = mutableListOf<String>()
        val body = fixture()
        body.getJSONObject("data").getJSONObject("video").put("videoSn", 21)
        val client = OfficialAnimeApi { url -> urls += url.toString(); Response(url, body.toString()) }
        assertEquals(3, client.detail(anime.copy(latestVideoId = ""), "21").episodes.size)
        assertEquals(listOf("https://api.gamer.com.tw/mobile_app/anime/v4/video.php?sn=21"), urls)
    }

    @Test fun rejectsSeriesAndVideoMismatchesDuplicatesMissingRequestedEpisodesAndEmptyLists() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("video").put("animeSn", 11) },
            { it.getJSONObject("video").put("videoSn", 99) },
            { it.getJSONObject("anime").put("animeSn", 11) },
            { it.getJSONObject("anime").getJSONObject("episodes").getJSONArray("0").put(
                JSONObject().put("videoSn", 20).put("episode", 3)) },
            { it.getJSONObject("anime").getJSONObject("episodes").getJSONArray("0").remove(0) },
            { it.getJSONObject("anime").put("episodes", JSONObject()) },
            { it.getJSONObject("anime").put("cover", "https://attacker.invalid/poster.jpg") },
            { it.getJSONObject("anime").put("score", 6) },
            { it.getJSONObject("anime").put("title", "") }
        )
        for (mutate in mutations) {
            val body = fixture()
            mutate(body.getJSONObject("data"))
            assertFailure("detail-invalid", OfficialAnimeApi { Response(it, body.toString()) })
        }
    }

    @Test fun rejectsMalformedAndOfficiallyRejectedResponsesWithoutExposingTheirContent() {
        for (body in listOf("", "<html>account-private</html>", "{}", """{"data":[]}""",
            """{"error":{"code":403,"message":"account-private"}}""")) {
            val client = OfficialAnimeApi { Response(it, body) }
            val error = runCatching { client.detail(anime) }.exceptionOrNull()
            assertTrue(error is AnimeDetailException)
            assertFalse(error.toString().contains("account-private"))
        }
    }

    @Test fun redirectsAndHttpFailuresAreNotFollowedOrParsed() {
        for (status in listOf(301, 302, 401, 403, 404, 429, 500)) {
            lateinit var response: Response
            assertFailure("detail-http", OfficialAnimeApi { Response(it, fixture().toString(), status).also { response = it } })
            assertFalse(response.streamOpened)
            assertTrue(response.disconnected)
        }
    }

    @Test fun responseTypeAndBothDeclaredAndStreamedSizeAreBounded() {
        assertFailure("detail-invalid", OfficialAnimeApi { Response(it, fixture().toString(), mime = "text/html") })
        assertFailure("detail-too-large", OfficialAnimeApi {
            Response(it, "{}", length = OfficialAnimeApi.MAX_BYTES + 1)
        })
        assertFailure("detail-too-large", OfficialAnimeApi {
            Response(it, "x".repeat(OfficialAnimeApi.MAX_BYTES + 1), length = -1)
        })
        val body = fixture()
        body.getJSONObject("data").getJSONObject("anime").put("episodes", JSONObject().put("0",
            JSONArray((0..5000).map { JSONObject().put("videoSn", it + 20).put("episode", it) })))
        assertFailure("detail-invalid", OfficialAnimeApi { Response(it, body.toString()) })
    }

    @Test fun missingOptionalMetadataIsAllowedButInvalidEpisodeLabelsFail() {
        val body = fixture()
        val series = body.getJSONObject("data").getJSONObject("anime")
        for (key in listOf("score", "tags", "maker", "publisher", "director", "content")) series.remove(key)
        assertEquals("", OfficialAnimeApi { Response(it, body.toString()) }.detail(anime).description)
        for (label in listOf("", "x".repeat(101))) {
            series.getJSONObject("episodes").getJSONArray("0").getJSONObject(0).put("episode", label)
            assertFailure("detail-invalid", OfficialAnimeApi { Response(it, body.toString()) })
        }
    }

    @Test fun invalidIdsAreRejectedBeforeOpeningAConnectionAndClosePreventsNewRequests() {
        var opened = false
        val client = OfficialAnimeApi { opened = true; Response(it, fixture().toString()) }
        for (id in listOf("0", "-1", "../20", "12345678901")) {
            assertFailure("detail-invalid", client, anime.copy(id = id))
        }
        assertFalse(opened)
        client.close()
        assertFailure("detail-cancelled", client)
        assertFalse(opened)
    }

    @Test fun networkFailuresHaveFixedCodesAndNoBrowserFallback() {
        assertFailure("detail-network", OfficialAnimeApi { throw IOException("https://private.invalid/account-private") })
        val client = OfficialAnimeApi {
            Response(it, """{"data":{"animeList":[{"animeSn":11,"videoSn":99}]}}""")
        }
        assertFailure("detail-video-unavailable", client, anime.copy(latestVideoId = ""))
    }

    private fun assertFailure(code: String, client: OfficialAnimeApi, target: Anime = anime) {
        val error = runCatching { client.detail(target) }.exceptionOrNull()
        assertTrue("Expected $code, got $error", error is AnimeDetailException)
        assertEquals(code, (error as AnimeDetailException).code)
    }

    companion object {
        internal fun fixture(): JSONObject = JSONObject("""
            {"data":{
              "video":{"videoSn":20,"animeSn":10,"ratingDesc":"輔導級","breakpoint":"account-private"},
              "anime":{"animeSn":10,"title":"測試動畫 [1]","cover":"https://p2.bahamut.com.tw/poster.jpg",
                "content":"作品簡介。＜STAFF＞製作人員","seasonStart":"2026/10/01",
                "score":4.3,"tags":["奇幻","冒險"],"director":"導演","maker":"製作","publisher":"代理",
                "favorite":"account-private","episodes":{
                  "1":[{"videoSn":22,"episode":"特別篇"}],
                  "0":[{"videoSn":20,"episode":1,"state":1},{"videoSn":21,"episode":2,"state":0}]}}
            }}
        """.trimIndent())
    }
}
