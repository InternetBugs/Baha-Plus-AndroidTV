package app.bahaplus.tv.data

import app.bahaplus.tv.core.Anime
import app.bahaplus.tv.core.OfficialSite
import app.bahaplus.tv.core.WatchEntry
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import kotlin.math.floor

internal data class OfficialIdentity(val id: String, val name: String)

internal data class OfficialAccount(val id: String, val name: String, val favorites: List<Anime>,
    val history: List<WatchEntry>)

/** Inert HTML and official JSON over HTTP; never evaluates page scripts or onclick attributes. */
internal class NativeAccountApi(private val session: OfficialHttpSession) {
    /** Confirm the session before fetching optional collections/history. */
    fun identity(): OfficialIdentity? {
        val first = accountPage() ?: return null
        val profile = profile(first) ?: return null
        session.checkCurrent()
        return OfficialIdentity(profile.first, session.accountName(profile.first) ?: profile.second)
    }

    fun account(expectedOwner: String? = null): OfficialAccount? {
        val first = accountPage() ?: return null
        val profile = profile(first) ?: return null
        if (expectedOwner != null && !profile.first.equals(expectedOwner, true)) {
            throw NativeApiException("account-stale")
        }
        val favorites = favorites(first, profile.first)
        val history = history()
        requireProfile(page("/mygather.php"), profile.first)
        session.checkCurrent()
        return OfficialAccount(profile.first, session.accountName(profile.first) ?: profile.second,
            favorites, history)
    }

    private fun accountPage(): InertHtml? =
        try { page("/mygather.php") } catch (error: NativeApiException) {
            if (error.code == "login-required") null else throw error
        }

    private fun favorites(first: InertHtml, owner: String): List<Anime> {
        val favorites = linkedMapOf<String, Anime>()
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque(listOf("/mygather.php"))
        while (pending.isNotEmpty()) {
            val path = pending.removeFirst()
            if (!visited.add(path)) continue
            require(visited.size <= 50)
            val doc = if (visited.size == 1) first else page(path)
            requireProfile(doc, owner)
            require(doc.selectFirst(".theme-title")?.text()?.contains("我的動畫") == true)
            for (item in parseFavorites(doc)) favorites[item.id] = item
            require(favorites.size <= 5000)
            for (link in doc.select(".page_number a[href]")) {
                val href = link.attr("href")
                // java.net.URI.resolve("?page=2") drops the last path segment.
                // Preserve browser semantics for the site's query-only pagination links.
                val uri = if (href.startsWith("?")) URI(OfficialHttpSession.WEB + path.substringBefore('?') + href)
                    else URI(OfficialHttpSession.WEB + path).resolve(href)
                if (uri.scheme == "https" && uri.host == "ani.gamer.com.tw" && uri.userInfo == null &&
                    uri.port in setOf(-1, 443) && uri.path == "/mygather.php" && uri.fragment == null) {
                    val next = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
                    if (next !in visited && next !in pending) pending.addLast(next)
                }
            }
        }
        return favorites.values.toList()
    }

    fun favorite(anime: Anime, desired: Boolean, owner: String) {
        require(OfficialSite.validId(anime.id))
        requireProfile(page("/mygather.php"), owner)
        val button = favoriteButton(anime)
        val arguments = requireNotNull(gather.find(button.attr("onclick"))).groupValues
        var confirmed = desired
        if (favoriteSelected(button) != desired) {
            requireProfile(page("/mygather.php"), owner)
            val token = session.get("${OfficialHttpSession.WEB}/ajax/getCSRFToken.php").trim()
            require(token.isNotBlank() && token.length <= 500 && token.none { it in "<>\r\n" })
            session.checkCurrent()
            val result = session.post("${OfficialHttpSession.WEB}/ajax/" +
                if (desired) "want2play.php" else "delgather.php",
                mapOf("s" to arguments[1], "animeSn" to anime.id, "token" to token))
            if (desired) {
                val response = InertHtml.parse(result)
                if (response.selectFirst("errmsg") != null || response.selectFirst("okmsg") == null) {
                    throw NativeApiException("favorite-rejected")
                }
            } else {
                // The official removal handler only treats positive error codes
                // as failures. Success need not contain an integer error=0.
                // An absent/unknown acknowledgement is never enough on its own:
                // confirm the desired state from a fresh authenticated response.
                val error = runCatching { JSONObject(result).opt("error") }.getOrNull()
                if (error != null && error != JSONObject.NULL && error != false) {
                    val code = error.toString().toDoubleOrNull()
                    if (code == null || !code.isFinite() || code > 0) {
                        throw NativeApiException("favorite-rejected")
                    }
                }
            }
            confirmed = favoriteSelected(favoriteButton(anime))
        }
        requireProfile(page("/mygather.php"), owner)
        session.checkCurrent()
        if (confirmed != desired) throw NativeApiException("favorite-rejected")
    }

    private fun favoriteButton(anime: Anime): InertHtml {
        val doc = InertHtml.parse(session.get("${OfficialHttpSession.WEB}/search.php",
            mapOf("keyword" to anime.title)))
        return doc.select("[onclick*=toggleGather]").firstOrNull {
            gather.find(it.attr("onclick"))?.groupValues?.get(2) == anime.id
        } ?: throw NativeApiException("favorite-unavailable")
    }

    private fun favoriteSelected(button: InertHtml): Boolean =
        button.hasClass(if (button.attr("onclick").contains("btn-is-active")) "btn-is-active" else "ordered")

    private fun history(): List<WatchEntry> {
        val result = linkedMapOf<String, WatchEntry>()
        var total = 1
        var current = 1
        while (current <= total) {
            val data = session.json("${OfficialHttpSession.API}/anime/v3/history.php",
                mapOf("page" to current.toString())).getJSONObject("data")
            total = data.getInt("totalPage")
            require(total in 0..50)
            val rows = data.getJSONArray("history")
            require(rows.length() <= 1000)
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                if (row.opt("isTakenDown") in listOf(true, 1)) continue
                val entry = parseHistory(row)
                if (!result.containsKey(entry.anime.id)) result[entry.anime.id] = entry
                require(result.size <= 1000)
            }
            current++
        }
        return result.values.toList()
    }

    private fun page(path: String): InertHtml =
        InertHtml.parse(session.get(OfficialHttpSession.WEB + path))

    companion object {
        private val gather = Regex("toggleGather\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,")
        private val accountId = Regex("[A-Za-z0-9_-]{1,64}")
        private fun validOwner(id: String) = accountId.matches(id) && !id.equals("guest", true)

        internal fun profile(doc: InertHtml): Pair<String, String>? {
            val user = doc.selectFirst("a.user-info")
            val id = user?.selectFirst(".user-id")?.text()?.trim()
            val guest = doc.selectFirst("a[href*=user.gamer.com.tw/login.php]") != null
            // The official user menu is built by JavaScript and is absent from
            // HTTP responses. The authenticated collection page instead embeds
            // its owner in its server-rendered history link.
            val owners = doc.select("a[href]").mapNotNull { link ->
                val uri = runCatching { URI(OfficialHttpSession.WEB + "/").resolve(link.attr("href")) }.getOrNull()
                if (uri?.scheme != "https" || uri.host != "ani.gamer.com.tw" ||
                    uri.port !in setOf(-1, 443) || uri.userInfo != null || uri.fragment != null ||
                    uri.path != "/viewList.php") return@mapNotNull null
                val values = uri.rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "u" }
                if (values.size != 1) throw NativeApiException("account-format")
                val owner = runCatching {
                    URLDecoder.decode(values.single().substringAfter('=', "").replace("+", "%2B"), "UTF-8")
                }.getOrNull()
                if (owner == null || !validOwner(owner)) throw NativeApiException("account-format")
                owner
            }
            if (owners.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size > 1) {
                throw NativeApiException("account-stale")
            }
            if (!id.isNullOrBlank() && !id.equals("guest", true)) {
                if (!validOwner(id) || guest) throw NativeApiException("account-format")
                if (owners.any { !it.equals(id, true) }) throw NativeApiException("account-stale")
            } else {
                if (guest && owners.isEmpty()) return null
                val owner = owners.firstOrNull()
                if (guest || owner == null ||
                    doc.selectFirst(".theme-title")?.text()?.contains("我的動畫") != true) {
                    throw NativeApiException("account-format")
                }
                return owner to owner
            }
            val name = user?.selectFirst(".user-name")?.text()?.trim().orEmpty().ifBlank { id }
            require(name.length <= 200)
            return id to name
        }

        private fun requireProfile(doc: InertHtml, owner: String) {
            if (profile(doc)?.first?.equals(owner, true) != true) throw NativeApiException("account-stale")
        }

        internal fun parseFavorites(doc: InertHtml): List<Anime> = doc.select("a.theme-list-main, a.anime-card-block").map { card ->
            val href = URI(OfficialHttpSession.WEB).resolve(card.attr("href"))
            val id = card.selectFirst("[onclick*=toggleGather]")?.let {
                gather.find(it.attr("onclick"))?.groupValues?.get(2)
            } ?: if (href.host == "ani.gamer.com.tw" && href.path == "/animeRef.php")
                queryId(href) else null
            val image = card.selectFirst("img.theme-img, .anime-blocker img") ?: error("favorite-image")
            val cover = URI(OfficialHttpSession.WEB).resolve(
                image.attr("data-src").ifBlank { image.attr("src") }).toString()
            val json = JSONObject().put("id", requireNotNull(id)).put("title",
                card.selectFirst(".theme-name, .anime-name")?.text()).put("cover", cover)
            if (href.host == "ani.gamer.com.tw" && href.path == "/animeVideo.php") {
                queryId(href)?.let { json.put("latestVideoId", it) }
            }
            Anime.parse(json)
        }.distinctBy { it.id }

        internal fun parseHistory(row: JSONObject): WatchEntry {
            val duration = row.getDouble("duration")
            val point = row.optJSONObject("breakPoint")?.let {
                if (it.isNull("breakPoint")) null else it.getDouble("breakPoint")
            }
            require(duration.isFinite() && duration >= 0 && (point == null || (point.isFinite() && point >= -1)))
            val progress = if (point == null || (point != -1.0 && duration == 0.0)) null
                else if (point == -1.0) 1.0 else (point / (duration * 60)).coerceIn(0.0, 1.0)
            val seconds = floor((point ?: 0.0).coerceAtLeast(0.0)).toLong()
            val label = if (point == -1.0) "觀看結束"
                else if (seconds > 0) "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" else ""
            return WatchEntry.parse(JSONObject().put("videoId", row.get("videoSn").toString())
                .put("episodeLabel", row.getString("episode")).put("progress", progress)
                .put("progressLabel", label).put("anime", JSONObject()
                    .put("id", row.get("animeSn").toString()).put("title", row.getString("title"))
                    .put("cover", row.getString("cover"))))
        }

        private fun queryId(uri: URI) = uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("sn=") }
            ?.substringAfter('=')?.takeIf(OfficialSite::validId)
    }
}
