package app.bahaplus.tv.data

import app.bahaplus.tv.core.Anime
import app.bahaplus.tv.core.AnimeDetail
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Calls arrive on the UI thread. Every navigation retires the previous request and its delivery. */
internal class AnimeDetailLoader(
    private val deliver: (() -> Unit) -> Unit,
    private val clientFactory: () -> OfficialAnimeApi = { OfficialAnimeApi() }
) {
    private class Request(val client: OfficialAnimeApi) {
        var future: Future<*>? = null
    }
    private val executor = ThreadPoolExecutor(0, 2, 5, TimeUnit.SECONDS, LinkedBlockingQueue())
    @Volatile private var current: Request? = null
    @Volatile private var closed = false

    fun load(anime: Anime, videoId: String?, onResult: (Result<AnimeDetail>) -> Unit) {
        if (closed) return
        cancel()
        val request = Request(clientFactory())
        current = request
        request.future = executor.submit {
            val result = try {
                runCatching { request.client.detail(anime, videoId) }
            } finally {
                request.client.close()
            }
            if (!closed && current === request) deliver {
                if (!closed && current === request) {
                    current = null
                    onResult(result)
                }
            }
        }
    }

    fun cancel() {
        val request = current
        current = null
        request?.future?.cancel(true)
        request?.client?.close()
        executor.purge()
    }

    fun destroy() {
        closed = true
        cancel()
        executor.shutdownNow()
    }
}
