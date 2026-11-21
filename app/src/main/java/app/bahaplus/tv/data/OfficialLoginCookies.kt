package app.bahaplus.tv.data

import android.webkit.CookieManager
import java.net.URI

/** The WebView store owns the login across native operations and app restarts. */
internal class OfficialLoginCookies(
    private val readCookie: (String) -> String?,
    private val writeCookie: (String, String) -> Unit,
    private val flushCookies: () -> Unit = {}
) {
    private val lock = Any()
    private var generation = 0L
    private var loginOwner: Any? = null
    private var clearing = false

    fun snapshot(): Map<String, String> = synchronized(lock) {
        if (clearing) return emptyMap()
        runCatching { HOSTS.associateWith { readCookie("https://$it/").orEmpty() } }
            .getOrDefault(emptyMap())
    }

    fun rotationEpoch(): Long? = synchronized(lock) {
        generation.takeIf { !clearing && loginOwner == null }
    }

    fun beginLogin(owner: Any) = synchronized(lock) {
        generation++
        loginOwner = owner
    }

    fun endLogin(owner: Any) = synchronized(lock) {
        if (loginOwner === owner) loginOwner = null
    }

    fun isClearing(): Boolean = synchronized(lock) { clearing }

    fun clear(remove: (() -> Unit) -> Unit, after: () -> Unit) {
        synchronized(lock) {
            if (clearing) return
            clearing = true
            generation++
        }
        try {
            remove {
                synchronized(lock) { clearing = false }
                after()
            }
        } catch (error: Exception) {
            synchronized(lock) { clearing = false }
            throw error
        }
    }

    fun persist(epoch: Long?, uri: URI, sentHeader: String, headers: Map<String?, List<String>>, isCurrent: () -> Boolean) {
        if (epoch == null || uri.scheme != "https" || uri.host !in HOSTS ||
            (uri.port != -1 && uri.port != 443) || uri.userInfo != null || uri.fragment != null) return
        val rotation = headers.asSequence()
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value.asSequence() }
            .filter { it.substringBefore(';').substringBefore('=').trim() == TOKEN }
            .singleOrNull() ?: return
        if (!validRotation(uri.host, rotation)) return
        val sent = login(sentHeader) ?: return
        val url = uri.toASCIIString()
        // Chromium's two-argument setCookie is synchronous. Compare and write
        // under the same lock used to begin QR login and clear the browser store.
        runCatching {
            synchronized(lock) {
                if (generation != epoch || !isCurrent() || login(readCookie(url).orEmpty()) != sent ||
                    !isCurrent()) return
                writeCookie(url, rotation)
            }
            flushCookies()
        }
    }

    private fun validRotation(host: String, rotation: String): Boolean {
        if (rotation.any { it.isISOControl() }) return false
        val fields = rotation.split(';')
        if ('=' !in fields.first()) return false
        val domains = fields.drop(1).filter { it.substringBefore('=').trim().equals("Domain", ignoreCase = true) }
        if (domains.isEmpty()) return true
        val domain = domains.singleOrNull()?.substringAfter('=', "")?.trim()?.removePrefix(".") ?: return false
        return domain.equals(host, ignoreCase = true) || domain.equals("gamer.com.tw", ignoreCase = true)
    }

    private fun login(header: String): Pair<String, String>? {
        var id: String? = null
        var token: String? = null
        for (field in header.split(';')) {
            val pair = field.trim()
            val value = pair.substringAfter('=', "")
            when (pair.substringBefore('=')) {
                "BAHAID" -> {
                    if (id != null || value.isEmpty()) return null
                    id = value
                }
                TOKEN -> {
                    if (token != null || value.isEmpty()) return null
                    token = value
                }
            }
        }
        return (id ?: return null) to (token ?: return null)
    }

    companion object {
        private const val TOKEN = "BAHA" + "RUNE"
        private val HOSTS = setOf("ani.gamer.com.tw", "api.gamer.com.tw")
        val webView = OfficialLoginCookies(
            readCookie = { CookieManager.getInstance().getCookie(it) },
            writeCookie = { url, cookie -> CookieManager.getInstance().setCookie(url, cookie) },
            flushCookies = { CookieManager.getInstance().flush() }
        )
    }
}
