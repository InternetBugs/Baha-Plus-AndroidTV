package app.bahaplus.tv.experiment

import java.net.HttpCookie
import java.net.URI
import java.util.Locale

/**
 * Browser-style cookies for this experiment's two official HTTPS origins.
 *
 * java.net.CookieManager uses legacy domain/version rules on older Android.
 * In particular, Domain=gamer.com.tw must also match ani.gamer.com.tw, and
 * outbound cookies must not use RFC 2965's quoted/versioned representation.
 * Values stay in memory and are never exposed to diagnostics.
 */
internal class GuestCookieJar {
    private data class Key(val name: String, val domain: String, val path: String)
    private data class Stored(val cookie: HttpCookie, val hostOnly: Boolean)
    private val entries = linkedMapOf<Key, Stored>()
    private val origins = setOf("ani.gamer.com.tw", "api.gamer.com.tw")

    private fun trusted(uri: URI): Boolean = uri.scheme == "https" &&
        uri.host in origins && uri.port in setOf(-1, 443) && uri.userInfo == null

    @Synchronized fun receive(uri: URI, headers: Map<String?, List<String>>) {
        if (!trusted(uri)) return
        headers.filterKeys { it.equals("Set-Cookie", ignoreCase = true) }.values.flatten().forEach { raw ->
            val parsed = runCatching { HttpCookie.parse(raw) }.getOrDefault(emptyList())
            parsed.forEach cookieLoop@{ cookie ->
                val hostOnly = cookie.domain == null
                val domain = (cookie.domain ?: uri.host).removePrefix(".").lowercase(Locale.ROOT)
                // This is a closed-origin guest jar, not a general public-suffix implementation.
                if (domain != uri.host && domain != "gamer.com.tw") return@cookieLoop
                if (cookie.value.any { it.code !in 0x21..0x7e || it in "\";,\\" }) return@cookieLoop
                val requestPath = uri.path.takeIf { it.startsWith("/") } ?: "/"
                val defaultPath = requestPath.substringBeforeLast('/', "").ifEmpty { "/" }
                val path = cookie.path?.takeIf { it.startsWith("/") } ?: defaultPath
                val key = Key(cookie.name, domain, path)
                entries.remove(key)
                if (!cookie.hasExpired()) entries[key] = Stored(cookie, hostOnly)
            }
        }
    }

    @Synchronized fun header(uri: URI): String {
        if (!trusted(uri)) return ""
        entries.entries.removeAll { it.value.cookie.hasExpired() }
        val path = uri.path.ifEmpty { "/" }
        return entries.filter { (key, stored) ->
            val domainMatches = uri.host == key.domain ||
                (!stored.hostOnly && uri.host.endsWith("." + key.domain))
            val pathMatches = path == key.path ||
                (path.startsWith(key.path) && (key.path.endsWith("/") || path.getOrNull(key.path.length) == '/'))
            domainMatches && pathMatches && (!stored.cookie.secure || uri.scheme == "https")
        }.toList().sortedByDescending { (key, _) -> key.path.length }
            .joinToString("; ") { (key, stored) -> "${key.name}=${stored.cookie.value}" }
    }

    val count: Int get() = entries.size
    fun contains(name: String): Boolean = entries.keys.any { it.name == name }
    @Synchronized fun clear() { entries.clear() }
}
