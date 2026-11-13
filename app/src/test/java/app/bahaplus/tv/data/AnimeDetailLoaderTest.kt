package app.bahaplus.tv.data

import app.bahaplus.tv.core.Anime
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AnimeDetailLoaderTest {
    private val anime = Anime("10", "測試動畫", "https://p2.bahamut.com.tw/a.jpg", latestVideoId = "20")

    @Test fun queuedOldResponsesCannotReplaceANewerRequestEvenForTheSameSeries() {
        val deliveries = LinkedBlockingQueue<() -> Unit>()
        var completions = 0
        val loader = AnimeDetailLoader(deliver = { deliveries.add(it) }, clientFactory = {
            OfficialAnimeApi { OfficialAnimeApiTest.Response(it, OfficialAnimeApiTest.fixture().toString()) }
        })
        try {
            loader.load(anime, null) { completions++; fail("Stale response was delivered") }
            val old = deliveries.poll(5, TimeUnit.SECONDS) ?: error("No first result")
            loader.load(anime, null) { completions++; assertTrue(it.isSuccess) }
            val fresh = deliveries.poll(5, TimeUnit.SECONDS) ?: error("No second result")
            old()
            assertEquals(0, completions)
            fresh()
            assertEquals(1, completions)
        } finally { loader.destroy() }
    }

    @Test fun leavingThePageOrDestroyingTheLoaderInvalidatesAlreadyQueuedResults() {
        for (destroy in listOf(false, true)) {
            val deliveries = LinkedBlockingQueue<() -> Unit>()
            val loader = AnimeDetailLoader(deliver = { deliveries.add(it) }, clientFactory = {
                OfficialAnimeApi { OfficialAnimeApiTest.Response(it, OfficialAnimeApiTest.fixture().toString()) }
            })
            try {
                loader.load(anime, null) { fail("Cancelled result was delivered") }
                val queued = deliveries.poll(5, TimeUnit.SECONDS) ?: error("No result")
                if (destroy) loader.destroy() else loader.cancel()
                queued()
                if (destroy) loader.load(anime, null) { fail("Destroyed loader accepted a request") }
                assertTrue(deliveries.isEmpty())
            } finally { loader.destroy() }
        }
    }

    @Test fun failedNativeRequestsCanBeRetriedWithoutAWebView() {
        val deliveries = LinkedBlockingQueue<() -> Unit>()
        var attempt = 0
        val loader = AnimeDetailLoader(deliver = { deliveries.add(it) }, clientFactory = {
            attempt++
            val status = if (attempt == 1) 503 else 200
            OfficialAnimeApi { OfficialAnimeApiTest.Response(it, OfficialAnimeApiTest.fixture().toString(), status) }
        })
        try {
            loader.load(anime, null) { assertTrue(it.isFailure) }
            (deliveries.poll(5, TimeUnit.SECONDS) ?: error("No error"))()
            loader.load(anime, null) { assertTrue(it.isSuccess) }
            (deliveries.poll(5, TimeUnit.SECONDS) ?: error("No retry result"))()
            assertEquals(2, attempt)
        } finally { loader.destroy() }
    }

    @Test fun changingSeriesOrLeavingThePageRetiresQueuedRatingResults() {
        val deliveries = LinkedBlockingQueue<() -> Unit>()
        val loader = AnimeDetailLoader(deliver = { deliveries.add(it) }, clientFactory = {
            OfficialAnimeApi { OfficialAnimeApiTest.Response(it, OfficialRatingApiTest.fixture().toString()) }
        })
        try {
            loader.loadRating("67798") { fail("Previous series rating was delivered") }
            val old = deliveries.poll(5, TimeUnit.SECONDS) ?: error("No rating")
            loader.loadRating("67799") { assertEquals(649, it.getOrThrow().count) }
            val fresh = deliveries.poll(5, TimeUnit.SECONDS) ?: error("No replacement rating")
            old()
            fresh()
            loader.loadRating("67798") { fail("Rating arrived after leaving the page") }
            val queued = deliveries.poll(5, TimeUnit.SECONDS) ?: error("No queued rating")
            loader.cancel()
            queued()
        } finally { loader.destroy() }
    }
}
