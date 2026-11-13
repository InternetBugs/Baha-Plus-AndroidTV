package app.bahaplus.tv.data

import app.bahaplus.tv.core.Anime
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OfficialRatingApiTest {
    @Test fun readsOfficialRatingAndStarCountsWithoutCookiesOrReviewContent() {
        lateinit var response: OfficialAnimeApiTest.Response
        val client = OfficialAnimeApi { url ->
            OfficialAnimeApiTest.Response(url, fixture().toString()).also { response = it }
        }
        val rating = client.rating("67798")
        assertEquals("https://api.gamer.com.tw/acg/v1/reviews_list.php?sn=67798&t=4", response.url.toString())
        assertEquals(4.8, rating.average, 0.0)
        assertEquals(649, rating.count)
        assertEquals(listOf(7, 4, 20, 55, 563), rating.distribution)
        assertFalse(rating.toString().contains("review-private"))
        assertNull(response.getRequestProperty("Cookie"))
        assertNull(response.getRequestProperty("Authorization"))
        assertEquals("application/json", response.getRequestProperty("Accept"))
        assertEquals(12000, response.readTimeout)
        assertFalse(response.instanceFollowRedirects)
        assertTrue(response.disconnected)
    }

    @Test fun detailExposesTheFanIdAndAverageWithoutRequestingRatingsBeforeEpisodesAreDelivered() {
        val body = OfficialAnimeApiTest.fixture()
        body.getJSONObject("data").getJSONObject("anime").put("fanId", 67798).put("acgSn", 148486)
        val urls = mutableListOf<String>()
        val detail = OfficialAnimeApi { url ->
            urls += url.toString()
            OfficialAnimeApiTest.Response(url, body.toString())
        }.detail(Anime("10", "測試動畫", "https://p2.bahamut.com.tw/a.jpg", latestVideoId = "20"))
        assertEquals("67798", detail.ratingFanId)
        assertEquals(4.3, detail.score!!, 0.0)
        assertNull(detail.rating)
        assertEquals(1, urls.size)
        assertTrue(urls.single().contains("video.php"))
    }

    @Test fun missingOrInvalidFanIdLeavesDetailsAndEpisodesAvailable() {
        for (fanId in listOf(null, 0, -1, "0", "../1", "12345678901", JSONObject())) {
            val body = OfficialAnimeApiTest.fixture()
            body.getJSONObject("data").getJSONObject("anime").put("fanId", fanId)
            val detail = OfficialAnimeApi { OfficialAnimeApiTest.Response(it, body.toString()) }
                .detail(Anime("10", "動畫", "https://p2.bahamut.com.tw/a.jpg", latestVideoId = "20"))
            assertNull(detail.ratingFanId)
            assertEquals(3, detail.episodes.size)
        }
    }

    @Test fun zeroRatingsSupportsNullOrZeroAverageWithoutDividingByZero() {
        for (average in listOf(null, 0)) {
            val body = fixture()
            body.getJSONObject("data").getJSONObject("rating").apply {
                put("avg", average)
                put("count", 0)
                put("star_chart", JSONObject("""{"1":0,"2":0,"3":0,"4":0,"5":0}"""))
            }
            val rating = OfficialAnimeApi { OfficialAnimeApiTest.Response(it, body.toString()) }.rating("67798")
            assertEquals(0.0, rating.average, 0.0)
            assertEquals(0, rating.count)
            assertEquals(List(5) { 0 }, rating.distribution)
        }
    }

    @Test fun rejectsMissingFractionalNegativeOverflowingAndInconsistentCounts() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("avg", 5.1) },
            { it.put("avg", "4.8") },
            { it.put("avg", JSONObject.NULL) },
            { it.put("count", -1) },
            { it.put("count", 649.5) },
            { it.put("count", "649") },
            { it.put("count", 2147483648L) },
            { it.put("count", 650) },
            { it.getJSONObject("star_chart").put("1", -7) },
            { it.getJSONObject("star_chart").put("1", 7.5) },
            { it.getJSONObject("star_chart").put("1", "7") },
            { it.getJSONObject("star_chart").put("1", JSONObject.NULL) },
            { it.getJSONObject("star_chart").put("1", 2147483648L) },
            { it.getJSONObject("star_chart").remove("1") },
            { it.getJSONObject("star_chart").put("6", 0) },
            { it.remove("star_chart") },
            { it.put("count", 0) }
        )
        for (mutate in mutations) {
            val body = fixture()
            mutate(body.getJSONObject("data").getJSONObject("rating"))
            assertFailure("detail-invalid", OfficialAnimeApi { OfficialAnimeApiTest.Response(it, body.toString()) })
        }
    }

    @Test fun httpRedirectsMimeAndSizeFailuresNeverReadTheResponseOrFallBackToHtml() {
        for (status in listOf(302, 403, 429, 500)) {
            lateinit var response: OfficialAnimeApiTest.Response
            assertFailure("detail-http", OfficialAnimeApi { url ->
                OfficialAnimeApiTest.Response(url, fixture().toString(), status).also { response = it }
            })
            assertFalse(response.streamOpened)
            assertTrue(response.disconnected)
        }
        assertFailure("detail-invalid", OfficialAnimeApi {
            OfficialAnimeApiTest.Response(it, "<html>review-private</html>", mime = "text/html")
        })
        assertFailure("detail-too-large", OfficialAnimeApi {
            OfficialAnimeApiTest.Response(it, "{}", length = OfficialAnimeApi.MAX_BYTES + 1)
        })
        assertFailure("detail-rejected", OfficialAnimeApi {
            OfficialAnimeApiTest.Response(it, """{"error":{"message":"review-private"}}""")
        })
        assertFailure("detail-network", OfficialAnimeApi { throw IOException("review-private") })
    }

    @Test fun rejectsInvalidFanIdsBeforeNetworkingAndHonorsClose() {
        var opened = false
        val client = OfficialAnimeApi { opened = true; OfficialAnimeApiTest.Response(it, fixture().toString()) }
        for (id in listOf("0", "-1", "../1", "67798&t=1", "12345678901")) {
            assertFailure("detail-invalid", client, fanId = id)
        }
        assertFalse(opened)
        client.close()
        assertFailure("detail-cancelled", client)
        assertFalse(opened)
    }

    private fun assertFailure(code: String, client: OfficialAnimeApi, fanId: String = "67798") {
        val error = runCatching { client.rating(fanId) }.exceptionOrNull()
        assertTrue("Expected $code, got $error", error is AnimeDetailException)
        assertEquals(code, (error as AnimeDetailException).code)
        assertFalse(error.toString().contains("review-private"))
    }

    companion object {
        internal fun fixture() = JSONObject("""
            {"data":{"page":2,"rating":{"avg":4.8,"count":649,
              "star_chart":{"5":563,"1":7,"4":55,"2":4,"3":20}},
              "reviewed":[],"list":[{"content":"review-private"}]}}
        """.trimIndent())
    }
}
