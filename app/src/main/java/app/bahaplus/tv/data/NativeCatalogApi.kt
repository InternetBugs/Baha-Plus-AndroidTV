package app.bahaplus.tv.data

import app.bahaplus.tv.core.Anime
import app.bahaplus.tv.core.OfficialSite
import org.json.JSONObject

/** Anonymous App catalog and public search endpoints, shared by every normal build. */
internal class NativeCatalogApi(private val session: OfficialHttpSession) {
    fun catalog(): List<Anime> = parseCatalog(session.json(
        "${OfficialHttpSession.API}/mobile_app/anime/v4/index.php").getJSONObject("data"))

    fun search(queries: List<String>): List<Anime> {
        require(queries.size in 1..2 && queries.all { it.isNotBlank() && it.codePointCount(0, it.length) <= 100 })
        val results = linkedMapOf<String, Anime>()
        for (keyword in queries.distinct()) {
            parseSearch(session.json("${OfficialHttpSession.API}/anime/v1/search.php",
                mapOf("kw" to keyword)).getJSONObject("data")).forEach { results[it.id] = it }
        }
        require(results.size <= 5000)
        return results.values.toList()
    }

    companion object {
        fun parseCatalog(data: JSONObject): List<Anime> {
            val results = linkedMapOf<String, Anime>()
            for ((section, rows) in listOf(
                "season" to data.getJSONObject("newAnime").getJSONArray("date"),
                "hot" to data.getJSONArray("hotAnime"), "new" to data.getJSONArray("newAdded"))) {
                require(rows.length() <= 5000)
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val item = parseAnime(row, if (section == "season") row.optString("volume")
                        else Regex("共\\s*\\d+\\s*集").find(row.optString("info"))?.value.orEmpty())
                    val previous = results[item.id]
                    results[item.id] = previous?.copy(sections = (previous.sections + section).distinct(),
                        latestVideoId = previous.latestVideoId.ifEmpty { item.latestVideoId })
                        ?: item.copy(sections = listOf(section))
                }
                require(results.size <= 5000)
            }
            require(results.isNotEmpty())
            return results.values.toList()
        }

        fun parseSearch(data: JSONObject): List<Anime> {
            val rows = data.getJSONArray("animeList")
            require(rows.length() <= 5000)
            return (0 until rows.length()).map {
                val row = rows.getJSONObject(it)
                val count = row.optInt("totalEpisode", 0)
                parseAnime(row, if (count > 0) "$count 集" else "")
            }.distinctBy { it.id }
        }

        private fun parseAnime(row: JSONObject, label: String): Anime {
            val id = row.get("animeSn").toString()
            val title = row.getString("title").trim()
            val cover = row.getString("cover")
            require(id.matches(Regex("[1-9][0-9]{0,9}")) && title.isNotEmpty() &&
                title.length < 500 && OfficialSite.image(cover) && label.length <= 200)
            return Anime(id, title, cover, label,
                row.opt("videoSn")?.toString()?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) }.orEmpty())
        }
    }
}
