package app.bahaplus.tv.data

import app.bahaplus.tv.core.Anime
import app.bahaplus.tv.core.AnimeDetail
import app.bahaplus.tv.core.Episode
import app.bahaplus.tv.core.OfficialSite
import app.bahaplus.tv.core.RatingSummary
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Anonymous public metadata only. Account and playback use separate native HTTP sessions. */
internal class OfficialAnimeApi(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    private val lock = Any()
    @Volatile private var closed = false
    private var connection: HttpURLConnection? = null

    fun detail(anime: Anime, requestedVideo: String? = null): AnimeDetail {
        try {
            require(validId(anime.id))
            requestedVideo?.let { require(validId(it)) }
            val videoId = requestedVideo ?: anime.latestVideoId.takeIf(::validId) ?: resolveVideo(anime)
            val data = request(URL("$API/mobile_app/anime/v4/video.php?sn=$videoId"))
            val video = data.getJSONObject("video")
            val series = data.getJSONObject("anime")
            require(id(video, "videoSn") == videoId && id(video, "animeSn") == anime.id &&
                id(series, "animeSn") == anime.id)
            val groups = series.getJSONObject("episodes")
            val episodes = linkedMapOf<String, Episode>()
            // JSONObject iteration differs across Android and JVM implementations.
            val keys = groups.keys().asSequence().toList()
                .sortedWith(compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it })
            require(keys.size <= 100)
            for (key in keys) {
                val group = groups.getJSONArray(key)
                require(episodes.size + group.length() <= 5000)
                for (index in 0 until group.length()) {
                    val record = group.getJSONObject(index)
                    val episodeId = id(record, "videoSn")
                    val label = record.get("episode").let {
                        require(it is String || it is Number)
                        it.toString().trim()
                    }
                    require(label.isNotEmpty() && label.length <= 100 && !episodes.containsKey(episodeId))
                    episodes[episodeId] = Episode(episodeId, label)
                }
            }
            require(episodes.containsKey(videoId))
            val title = text(series, "title", 499, required = true)
                .replace(Regex("\\s*\\[[^\\]]+\\]\\s*$"), "").trim()
            require(title.isNotEmpty())
            val poster = text(series, "cover", 4096, required = true)
            require(OfficialSite.image(poster))
            val description = text(series, "content", 200000)
                .split(Regex("[＜<](?:製作團隊|制作团队|STAFF|CAST)[＞>]", RegexOption.IGNORE_CASE), limit = 2)
                .first().trim()
            val metadata = mutableListOf<Pair<String, String>>()
            fun add(label: String, value: String) {
                if (value.isNotEmpty()) metadata += label to value
            }
            add("年份", Regex("^\\d{4}/\\d{2}").find(text(series, "seasonStart", 100))?.value.orEmpty())
            add("分級說明", text(video, "ratingDesc", 2000))
            if (!series.isNull("tags")) {
                val tags = series.getJSONArray("tags")
                require(tags.length() <= 100)
                add("作品類型", (0 until tags.length()).map {
                    val tag = tags.get(it)
                    require(tag is String && tag.length <= 100)
                    tag.trim()
                }.filter { it.isNotEmpty() }.joinToString("、").take(4000))
            }
            for ((label, key) in listOf("導演" to "director", "製作廠商" to "maker", "代理商" to "publisher")) {
                add(label, text(series, key, 4000))
            }
            val average = if (!series.isNull("score")) {
                val score = series.get("score")
                require(score is Number && score.toDouble().isFinite() && score.toDouble() in 0.0..5.0)
                add("評分", "$score / 5")
                score.toDouble()
            } else null
            val fanId = series.opt("fanId")?.toString()?.takeIf(::validId)
            checkOpen()
            return AnimeDetail(anime.id, title, description, poster, episodes.values.toList(), metadata,
                score = average, ratingFanId = fanId)
        } catch (error: AnimeDetailException) {
            throw error
        } catch (_: IOException) {
            throw AnimeDetailException(if (closed) "detail-cancelled" else "detail-network")
        } catch (_: Exception) {
            throw AnimeDetailException(if (closed) "detail-cancelled" else "detail-invalid")
        }
    }

    /** fanId is supplied by the verified video detail, not the animeSn or acgSn. */
    fun rating(fanId: String): RatingSummary {
        try {
            require(validId(fanId))
            val data = request(URL("$API/acg/v1/reviews_list.php?sn=$fanId&t=4"))
            val rating = data.getJSONObject("rating")
            fun count(json: JSONObject, key: String): Int {
                val value = json.get(key)
                require(value is Number && value.toDouble().isFinite() &&
                    value.toDouble() in 0.0..Int.MAX_VALUE.toDouble() &&
                    value.toDouble() == value.toInt().toDouble())
                return value.toInt()
            }
            val total = count(rating, "count")
            val average = if (total == 0 && rating.isNull("avg")) 0.0 else {
                val value = rating.get("avg")
                require(value is Number)
                value.toDouble()
            }
            val chart = rating.getJSONObject("star_chart")
            require(chart.keys().asSequence().toSet() == (1..5).map { it.toString() }.toSet())
            val result = RatingSummary(average, total, (1..5).map { count(chart, it.toString()) })
            checkOpen()
            return result
        } catch (error: AnimeDetailException) {
            throw error
        } catch (_: IOException) {
            throw AnimeDetailException(if (closed) "detail-cancelled" else "detail-network")
        } catch (_: Exception) {
            throw AnimeDetailException(if (closed) "detail-cancelled" else "detail-invalid")
        }
    }

    private fun resolveVideo(anime: Anime): String {
        // Favorites and the homepage's popularity cards may contain only a series ID.
        val keyword = anime.title.trim().codePoints().limit(100).toArray().let { String(it, 0, it.size) }
        require(keyword.isNotBlank())
        val data = request(URL("$API/anime/v1/search.php?kw=${URLEncoder.encode(keyword, "UTF-8")}"))
        val records = data.getJSONArray("animeList")
        require(records.length() <= 5000)
        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            if (record.opt("animeSn")?.toString() == anime.id) {
                return id(record, "videoSn")
            }
        }
        throw AnimeDetailException("detail-video-unavailable")
    }

    private fun request(url: URL): JSONObject {
        checkOpen()
        val request = openConnection(url)
        synchronized(lock) {
            if (closed) {
                request.disconnect()
                throw AnimeDetailException("detail-cancelled")
            }
            connection = request
        }
        try {
            request.connectTimeout = 12000
            request.readTimeout = 12000
            request.instanceFollowRedirects = false
            request.useCaches = false
            request.setRequestProperty("Accept", "application/json")
            // Never access WebView CookieManager or persist Set-Cookie from these public responses.
            if (request.responseCode != HttpURLConnection.HTTP_OK) throw AnimeDetailException("detail-http")
            if (request.contentType?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
                throw AnimeDetailException("detail-invalid")
            }
            if (request.contentLength > MAX_BYTES) throw AnimeDetailException("detail-too-large")
            val raw = request.inputStream.use { stream ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkOpen()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > MAX_BYTES) throw AnimeDetailException("detail-too-large")
                    bytes.write(buffer, 0, count)
                }
                bytes.toString("UTF-8")
            }
            val body = JSONObject(raw)
            if (!body.isNull("error")) throw AnimeDetailException("detail-rejected")
            checkOpen()
            return body.getJSONObject("data")
        } finally {
            request.disconnect()
            synchronized(lock) { if (connection === request) connection = null }
        }
    }

    fun close() {
        val request = synchronized(lock) {
            closed = true
            connection.also { connection = null }
        }
        request?.disconnect()
    }

    private fun checkOpen() {
        if (closed || Thread.currentThread().isInterrupted) throw AnimeDetailException("detail-cancelled")
    }

    companion object {
        private const val API = "https://api.gamer.com.tw"
        internal const val MAX_BYTES = 2000000
        private val identifier = Regex("^[1-9][0-9]{0,9}$")
        private fun validId(value: String) = identifier.matches(value)
        private fun id(json: JSONObject, key: String): String {
            val value = json.get(key).toString()
            require(validId(value))
            return value
        }
        private fun text(json: JSONObject, key: String, maximum: Int, required: Boolean = false): String {
            if (json.isNull(key) && !required) return ""
            val value = json.get(key)
            require(value is String && value.length <= maximum && (!required || value.isNotBlank()))
            return value.trim()
        }
    }
}

/** Only fixed codes leave the transport; URLs, response bodies and cookies never enter logs. */
internal class AnimeDetailException(val code: String) : Exception(code)
