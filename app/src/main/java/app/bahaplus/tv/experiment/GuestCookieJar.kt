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
    private val imported = mutableSetOf<Key>()
    private val origins = setOf("ani.gamer.com.tw", "api.gamer.com.tw")

    private fun trusted(uri: URI): Boolean = uri.scheme == "https" &&
        uri.host in origins && uri.port in setOf(-1, 443) && uri.userInfo == null

    /** Browser API supplies host-scoped request headers, never Set-Cookie attributes. */
    @Synchronized fun importHeader(uri: URI, header: String) {
        if (!trusted(uri)) return
        header.split(';').forEach { raw ->
            val pair = raw.trim().split('=', limit = 2)
            if (pair.size != 2 || !pair[0].matches(Regex("[A-Za-z0-9_!#$%&'*+.^`|~-]{1,256}")) ||
                pair[1].any { it.code !in 0x21..0x7e || it in "\";,\\" }) return@forEach
            val cookie = HttpCookie(pair[0], pair[1]).apply { path = "/"; secure = true }
            val key = Key(cookie.name, uri.host, "/")
            entries[key] = Stored(cookie, true)
            imported.add(key)
        }
    }

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
                // getCookie() has already applied browser domain rules. Replace
                // its matching snapshots when this native session rotates a cookie.
                val replaced = imported.filter {
                    it.name == key.name && it.path == key.path &&
                        (it.domain == domain || (!hostOnly && it.domain.endsWith(".$domain")))
                }
                replaced.forEach { entries.remove(it); imported.remove(it) }
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
    @Synchronized fun clear() { entries.clear(); imported.clear() }
}
