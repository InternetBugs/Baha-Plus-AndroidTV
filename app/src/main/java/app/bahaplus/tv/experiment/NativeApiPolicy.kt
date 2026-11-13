package app.bahaplus.tv.experiment

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import kotlin.math.abs
import kotlin.math.min

/** Pure protocol decisions shared by the native player and isolated guest experiment. */
object NativeApiPolicy {
    fun media(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme == "https" && uri.host == "bahamut.akamaized.net" &&
            uri.port in setOf(-1, 443) && uri.userInfo == null && uri.fragment == null &&
            uri.path.endsWith(".m3u8") && raw.length <= 4096
    }.getOrDefault(false)

    fun playlist(body: JSONObject): Pair<String, String> {
        val data = body.getJSONObject("data")
        val device = data.getString("deviceid").also { require(it.isNotBlank() && it.length <= 1024) }
        val cases = data.getJSONArray("srcUseCases")
        val selected = (0 until cases.length()).map { cases.getJSONObject(it) }
            .firstOrNull { it.optInt("deviceType") == 1 } ?: error("source-type")
        val source = selected.getJSONObject("src").getString("playlist")
        require(media(source)) { "source-origin" }
        return source to device
    }

    /** Read the current public configuration as JSON, without executing JavaScript. */
    fun videoAds(source: String): List<VideoAd> {
        val block = Regex("""function\s*\([^)]*\)\s*\{.*?var\s+adlist\s*=\s*(\[\[.*?]]);""",
            RegexOption.DOT_MATCHES_ALL).find(source)?.groupValues?.get(1) ?: error("ad-config-format")
        val list = JSONArray(block)
        return (0 until list.length()).map { list.getJSONArray(it) }.mapNotNull {
            val id = it.optString(0)
            val sid = it.optString(2)
            if (it.optString(3) != "video" || !id.matches(Regex("[0-9]{1,20}")) ||
                !sid.matches(Regex("[0-9]{1,20}"))) null else VideoAd(id, sid)
        }.also { require(it.isNotEmpty()) { "ad-config-empty" } }
    }

    fun progressParameter(position: Double, duration: Double): Long {
        require(position.isFinite() && duration.isFinite() && position >= 0 && duration > 0 &&
            position <= duration + 1)
        return if (abs(duration - position) <= 120) -1 else kotlin.math.floor(position + .5).toLong()
    }
}

data class VideoAd(val id: String, val sid: String) {
    val playlist: String get() = "https://bahamut.akamaized.net/ad/$id/playlist.m3u8"
}

/** A source request is allowed only after consent and the required real ad events. */
class PlaybackGate(private val needsAd: Boolean) {
    var consent = false
    var adStarted = false
        private set
    var adEnded = false
        private set
    fun started() { check(consent && needsAd); adStarted = true }
    fun ended(realPlaybackSeconds: Double) {
        check(adStarted && realPlaybackSeconds.isFinite() && realPlaybackSeconds >= 30)
        adEnded = true
    }
    val canRequestSource: Boolean get() = consent && (!needsAd || adEnded)
}

/** Accumulate real media advancement, excluding seeks, pauses and buffering. */
class WatchClock {
    var total = 0.0
        private set
    private var previousPosition: Double? = null
    private var previousRealtime: Long? = null
    private var completed = 0.0
    private var segmentMedia = 0.0
    private var segmentWall = 0.0
    fun sample(position: Double, playing: Boolean, seeking: Boolean, nowMs: Long, rate: Double = 1.0) {
        require(position.isFinite() && position >= 0 && rate.isFinite() && rate in .5..2.0)
        val previous = previousPosition
        val realtime = previousRealtime
        if (playing && !seeking && previous != null && realtime != null && nowMs >= realtime) {
            val elapsed = (nowMs - realtime) / 1000.0
            val advancement = position - previous
            if (elapsed <= 3 && advancement in 0.0..(elapsed * rate + .35)) {
                // Compare cumulative media/wall advancement within a continuous
                // run. Taking min on every tick permanently loses clock jitter.
                segmentMedia += advancement
                segmentWall += elapsed * rate
                total = completed + min(segmentMedia, segmentWall)
            } else commitSegment()
        } else commitSegment()
        previousPosition = if (playing && !seeking) position else null
        previousRealtime = if (playing && !seeking) nowMs else null
    }
    private fun commitSegment() {
        completed = total
        segmentMedia = 0.0
        segmentWall = 0.0
    }
    fun discontinuity() { commitSegment(); previousPosition = null; previousRealtime = null }
}
