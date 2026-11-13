package app.bahaplus.tv.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

object OfficialSite {
    const val HOME = "https://ani.gamer.com.tw/"
    const val ORIGIN = "https://ani.gamer.com.tw"
    const val ACCOUNT = "${HOME}mygather.php"
    private val identifier = Regex("^[0-9]{1,10}$")
    fun validId(id: String) = identifier.matches(id)
    fun allowedNavigation(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        val host = uri.host?.lowercase() ?: return false
        uri.scheme == "https" && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
            (host == "gamer.com.tw" || host.endsWith(".gamer.com.tw"))
    }.getOrDefault(false)
    fun trusted(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        allowedNavigation(raw) && uri.host == "ani.gamer.com.tw"
    }.getOrDefault(false)
    fun image(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme == "https" && uri.host?.endsWith(".bahamut.com.tw") == true &&
            uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) && raw.length <= 4096
    }.getOrDefault(false)
    fun media(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme == "https" && uri.host == "bahamut.akamaized.net" &&
            uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
            uri.fragment == null && uri.path.endsWith(".m3u8", true) && raw.length <= 4096
    }.getOrDefault(false)
    fun series(id: String): String {
        require(validId(id))
        return "${HOME}animeRef.php?sn=$id"
    }
    fun video(id: String): String {
        require(validId(id))
        return "${HOME}animeVideo.php?sn=$id"
    }
}

data class Anime(
    val id: String,
    val title: String,
    val cover: String,
    val episodeLabel: String = "",
    val latestVideoId: String = "",
    val sections: List<String> = emptyList()
) {
    companion object {
        fun parse(json: JSONObject): Anime {
            val id = json.getString("id")
            val title = json.getString("title").trim()
            val cover = json.getString("cover")
            val video = json.optString("latestVideoId").takeIf { OfficialSite.validId(it) }.orEmpty()
            require(OfficialSite.validId(id) && title.isNotEmpty() && title.length < 500 && OfficialSite.image(cover))
            return Anime(id, title, cover, json.optString("episodeLabel").take(200), video,
                json.optJSONArray("sections")?.strings().orEmpty())
        }
    }
}

data class Episode(val id: String, val label: String) {
    companion object {
        fun parse(json: JSONObject): Episode {
            val id = json.getString("id")
            val label = json.getString("label")
            require(OfficialSite.validId(id) && label.length <= 100)
            return Episode(id, label)
        }
    }
}

data class AnimeDetail(
    val animeId: String,
    val title: String,
    val description: String,
    val poster: String?,
    val episodes: List<Episode>,
    val metadata: List<Pair<String, String>>
) {
    companion object {
        fun parse(id: String, json: JSONObject): AnimeDetail {
            require(OfficialSite.validId(id))
            val title = json.getString("title").trim()
            val description = json.optString("description")
            val episodes = json.getJSONArray("episodes").objects().map(Episode::parse).distinctBy { it.id }
            require(title.isNotEmpty() && title.length < 500 && description.length <= 200000 && episodes.size <= 5000)
            val metadata = json.optJSONArray("metadata")?.objects().orEmpty().take(100).map {
                it.optString("label").take(200) to it.optString("value").take(4000)
            }
            return AnimeDetail(id, title, description, json.optString("poster").takeIf(OfficialSite::image),
                episodes, metadata)
        }
    }
}

data class WatchEntry(val anime: Anime, val videoId: String, val label: String, val progress: Double?, val progressLabel: String) {
    companion object {
        fun parse(json: JSONObject): WatchEntry {
            val video = json.getString("videoId")
            require(OfficialSite.validId(video))
            val progress = if (json.has("progress") && !json.isNull("progress")) json.getDouble("progress") else null
            require(progress == null || (progress.isFinite() && progress in 0.0..1.0))
            return WatchEntry(Anime.parse(json.getJSONObject("anime")), video, json.optString("episodeLabel").take(200),
                progress, json.optString("progressLabel").take(200))
        }
    }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
