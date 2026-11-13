package app.bahaplus.tv.experiment

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

/** In-memory guest cookies only. No WebView, browser cookie import or disk cache. */
class NativeApiClient(private val event: (String, Int?) -> Unit) : AutoCloseable {
    companion object {
        const val WEB = "https://ani.gamer.com.tw"
        const val API = "https://api.gamer.com.tw"
        const val AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
    private val cookies = GuestCookieJar()
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var closed = false
    private var device = ""
    private var video = ""
    private lateinit var gate: PlaybackGate
    private var ad: VideoAd? = null

    data class Preparation(val title: String, val rating: Int, val needsAd: Boolean,
        val breakpoint: Double, val duration: Double, val ad: VideoAd?)

    fun prepare(id: String): Preparation {
        require(id.matches(Regex("[0-9]{1,10}")))
        video = id
        // Fetch inert HTML only to establish the normal guest session.
        text("page", "$WEB/animeVideo.php", mapOf("sn" to video))
        device = json("device", "$WEB/ajax/getdeviceid.php").getString("deviceid")
        val metadata = json("metadata", "$API/anime/v1/video.php", mapOf("videoSn" to video))
            .getJSONObject("data").getJSONObject("video")
        if (metadata.optBoolean("requestParentPassword")) throw ApiFailure("parent-verification-required")
        val token = json("policy", "$WEB/ajax/token.php", mapOf("adID" to "undefined", "sn" to video,
            "device" to device, "hash" to UUID.randomUUID().toString().replace("-", "").take(12)))
        if (!token.has("time")) throw ApiFailure("policy-format")
        val needsAd = token.getInt("time") != 1
        gate = PlaybackGate(needsAd)
        if (needsAd) {
            // Current publisher video ads; real playback, normal START/END reporting.
            // SDK-only/interactive ad types are outside this experiment.
            val config = text("ad-config", "https://i2.bahamut.com.tw/JS/ad/animeVideo.js")
            ad = NativeApiPolicy.videoAds(config).random()
        }
        val breakpoint = metadata.optJSONObject("breakpoint")?.optDouble("breakPoint", 0.0) ?: 0.0
        return Preparation(metadata.getString("title"), token.optInt("r18", metadata.optInt("rating", 1)),
            needsAd, breakpoint.takeIf { it.isFinite() && it >= 0 } ?: 0.0,
            metadata.optDouble("duration", 0.0), ad)
    }

    fun consent() { gate.consent = true }

    fun startAd() {
        gate.started()
        jsonOrText("ad-start", "$WEB/ajax/videoCastcishu.php",
            mapOf("s" to requireNotNull(ad).sid, "sn" to video))
    }

    fun endAd(realPlaybackSeconds: Double) {
        check(gate.adStarted && realPlaybackSeconds >= 30)
        jsonOrText("ad-end", "$WEB/ajax/videoCastcishu.php",
            mapOf("s" to requireNotNull(ad).sid, "sn" to video, "ad" to "end"))
        gate.ended(realPlaybackSeconds)
    }

    fun source(): String {
        check(gate.canRequestSource)
        val result = json("source", "$API/anime/v1/video_src.php",
            mapOf("videoSn" to video, "deviceid" to device, "deviceTypeUseCases" to "1"))
        val (source, updatedDevice) = NativeApiPolicy.playlist(result)
        device = updatedDevice
        return source
    }

    fun started() {
        jsonOrText("device-lock", "$WEB/ajax/checklock.php", mapOf("sn" to video, "device" to device))
        jsonOrText("video-start", "$WEB/ajax/checklock.php", mapOf("sn" to video))
    }

    fun state(watchSeconds: Double) {
        jsonOrText("playback-state", "$WEB/ajax/unlock.php",
            mapOf("sn" to video, "ttl" to watchSeconds.toLong().toString()))
    }

    /** Returns guest separately; only an explicit 'ok' response confirms server progress. */
    fun progress(position: Double, duration: Double): String {
        val result = json("progress", "$WEB/ajax/elapse.php",
            mapOf("sn" to video, "device" to device,
                "time" to NativeApiPolicy.progressParameter(position, duration).toString()))
        val state = result.opt("state")
        if (state == "ok") {
            result.optString("device").takeIf { it.isNotBlank() }?.let { device = it }
            return "ok"
        }
        if (state?.toString() == "1") return "guest"
        throw ApiFailure("progress-denied", state?.toString()?.toIntOrNull())
    }

    private fun json(stage: String, url: String, args: Map<String, String> = emptyMap()): JSONObject =
        JSONObject(text(stage, url, args)).also { body ->
            val error = body.optJSONObject("error")
            if (error != null) throw ApiFailure(stage, error.optInt("code"))
        }

    private fun jsonOrText(stage: String, url: String, args: Map<String, String>) {
        val body = text(stage, url, args).trim()
        if (body.startsWith("{")) {
            JSONObject(body).optJSONObject("error")?.let { throw ApiFailure(stage, it.optInt("code")) }
        }
    }

    private fun text(stage: String, url: String, args: Map<String, String> = emptyMap()): String {
        check(!closed)
        val query = args.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val uri = URI(url + if (query.isEmpty()) "" else "?$query")
        require(uri.scheme == "https" && uri.host in setOf("ani.gamer.com.tw", "api.gamer.com.tw", "i2.bahamut.com.tw"))
        val request = uri.toURL().openConnection() as HttpURLConnection
        connection = request
        try {
            request.instanceFollowRedirects = false
            request.connectTimeout = 15000
            request.readTimeout = 15000
            request.useCaches = false
            request.setRequestProperty("User-Agent", AGENT)
            request.setRequestProperty("Referer", "$WEB/animeVideo.php?sn=$video")
            request.setRequestProperty("Origin", WEB)
            if (uri.host != "i2.bahamut.com.tw") {
                cookies.header(uri).takeIf { it.isNotEmpty() }?.let { request.setRequestProperty("Cookie", it) }
            }
            val status = request.responseCode
            event(stage, status)
            if (uri.host != "i2.bahamut.com.tw") {
                cookies.receive(uri, request.headerFields)
                if (stage == "device") {
                    event("guest-cookie-count", cookies.count)
                    event("guest-sign-present", if (cookies.contains("ANIME_SIGN")) 1 else 0)
                }
            }
            if (status !in 200..299) throw ApiFailure(stage, status)
            val bytes = request.inputStream.use { it.readBytesLimited(2_000_000) }
            val body = bytes.toString(Charsets.UTF_8)
            if (body.contains("challenge-platform") || body.contains("cf-chl-")) throw ApiFailure("verification-required")
            return body
        } catch (error: ApiFailure) {
            throw error
        } catch (error: Exception) {
            throw ApiFailure("transport-$stage-" + error.javaClass.simpleName +
                (error.cause?.let { "-" + it.javaClass.simpleName } ?: ""))
        } finally {
            request.disconnect()
            connection = null
        }
    }

    override fun close() { closed = true; connection?.disconnect(); cookies.clear() }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val length = read(buffer)
            if (length < 0) break
            if (output.size() + length > limit) throw ApiFailure("response-size")
            output.write(buffer, 0, length)
        }
        return output.toByteArray()
    }
}

class ApiFailure(val stage: String, val code: Int? = null) : Exception(stage)
