package app.bahaplus.tv.data

import android.webkit.CookieManager
import app.bahaplus.tv.experiment.GuestCookieJar
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Per-operation cookies, fixed official origins, no redirects or global cookie handler. */
internal class OfficialHttpSession(
    cookieHeaders: Map<String, String> = emptyMap(),
    private val isCurrent: () -> Boolean = { true },
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection }
) : AutoCloseable {
    private val cookies = GuestCookieJar()
    private val lock = Any()
    @Volatile private var closed = false
    private var connection: HttpURLConnection? = null

    init {
        cookieHeaders.forEach { (host, header) -> cookies.importHeader(URI("https://$host/"), header) }
    }

    fun checkCurrent() {
        if (closed || Thread.currentThread().isInterrupted || !isCurrent()) throw NativeApiException("session-stale")
    }

    fun json(path: String, args: Map<String, String> = emptyMap()): JSONObject {
        val body = JSONObject(get(path, args))
        if (!body.isNull("error")) throw NativeApiException("official-rejected")
        return body
    }

    fun get(path: String, args: Map<String, String> = emptyMap()): String =
        request(endpoint(path, args), null)

    fun post(path: String, fields: Map<String, String>): String =
        request(endpoint(path), encode(fields))

    /** Display hint only; authorization must come from the authenticated HTTP page. */
    fun accountName(owner: String): String? {
        fun value(name: String): String? {
            val values = cookies.header(URI("$WEB/")).split(';').map { it.trim() }
                .filter { it.substringBefore('=') == name }
            if (values.size != 1) return null
            return runCatching {
                // js.cookie uses decodeURIComponent: a literal '+' stays '+'.
                URLDecoder.decode(values.single().substringAfter('=', "").replace("+", "%2B"), "UTF-8")
            }.getOrNull()
        }
        if (value("BAHAID")?.equals(owner, true) != true) return null
        return value("BAHANICK")?.let(InertHtml::decode)
            ?.takeIf { it.none { c -> c.isISOControl() } }?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 200 }
    }

    private fun request(uri: URI, body: String?): String {
        require(uri.scheme == "https" && uri.host in HOSTS && uri.port in setOf(-1, 443) &&
            uri.userInfo == null && uri.fragment == null)
        checkCurrent()
        val request = openConnection(uri)
        synchronized(lock) {
            if (closed) { request.disconnect(); throw NativeApiException("session-stale") }
            connection = request
        }
        try {
            request.instanceFollowRedirects = false
            request.connectTimeout = 12000
            request.readTimeout = 12000
            request.useCaches = false
            request.setRequestProperty("User-Agent", AGENT)
            request.setRequestProperty("Referer", "$WEB/")
            request.setRequestProperty("Origin", WEB)
            cookies.header(uri).takeIf { it.isNotEmpty() }?.let { request.setRequestProperty("Cookie", it) }
            if (body != null) {
                request.requestMethod = "POST"
                request.doOutput = true
                request.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                checkCurrent()
                request.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = request.responseCode
            cookies.receive(uri, request.headerFields)
            if (status in 300..399) {
                val target = runCatching { uri.resolve(request.getHeaderField("Location").orEmpty()) }.getOrNull()
                val login = target?.scheme == "https" && target.host == "user.gamer.com.tw" &&
                    target.path == "/login.php" && target.userInfo == null && target.port in setOf(-1, 443)
                throw NativeApiException(if (login) "login-required" else "redirect-rejected")
            }
            if (status != 200) throw NativeApiException("http-$status")
            if (request.contentLength > MAX_BYTES) throw NativeApiException("response-too-large")
            val text = request.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkCurrent()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_BYTES) throw NativeApiException("response-too-large")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            if (text.contains("challenge-platform") || text.contains("cf-chl-")) {
                throw NativeApiException("verification-required")
            }
            checkCurrent()
            return text
        } catch (error: NativeApiException) {
            throw error
        } catch (_: Exception) {
            checkCurrent()
            throw NativeApiException("network")
        } finally {
            request.disconnect()
            synchronized(lock) { if (connection === request) connection = null }
        }
    }

    override fun close() {
        val pending = synchronized(lock) {
            closed = true
            connection.also { connection = null }
        }
        pending?.disconnect()
        cookies.clear()
    }

    companion object {
        const val WEB = "https://ani.gamer.com.tw"
        const val API = "https://api.gamer.com.tw"
        const val AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val MAX_BYTES = 2_000_000
        private val HOSTS = setOf("ani.gamer.com.tw", "api.gamer.com.tw")
        fun snapshotCookies(): Map<String, String> = runCatching {
            val manager = CookieManager.getInstance()
            HOSTS.associateWith { manager.getCookie("https://$it/").orEmpty() }
        }.getOrDefault(emptyMap())
        private fun encode(values: Map<String, String>) = values.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        private fun endpoint(path: String, args: Map<String, String> = emptyMap()): URI =
            URI(path + if (args.isEmpty()) "" else "?${encode(args)}")
    }
}

/** Fixed codes only; never expose URLs, cookie values or response bodies. */
internal class NativeApiException(val code: String) : Exception(code)
