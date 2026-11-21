package app.bahaplus.tv.data

import app.bahaplus.tv.core.Anime
import app.bahaplus.tv.core.OfficialSite
import org.json.JSONArray
import org.json.JSONObject

/** Anonymous App catalog and public search endpoints, shared by every normal build. */
internal class NativeCatalogApi(private val session: OfficialHttpSession) {
    fun catalog(): List<Anime> = parseCatalog(session.json(
        "${OfficialHttpSession.API}/mobile_app/anime/v4/index.php", retries = 2).getJSONObject("data"))

    fun search(queries: List<String>): List<Anime> {
        require(queries.size in 1..2 && queries.all { it.isNotBlank() && it.codePointCount(0, it.length) <= 100 })
        val results = linkedMapOf<String, Anime>()
        for (keyword in queries.distinct()) {
            parseSearch(session.json("${OfficialHttpSession.API}/anime/v1/search.php",
                mapOf("kw" to keyword), retries = 1).getJSONObject("data")).forEach { results[it.id] = it }
        }
        require(results.size <= 5000)
        return results.values.toList()
    }

    companion object {
        fun parseCatalog(data: JSONObject): List<Anime> {
            val results = linkedMapOf<String, Anime>()
            val hot = if (data.isNull("hotAnime")) JSONArray() else data.getJSONArray("hotAnime")
            val added = if (data.isNull("newAdded")) JSONArray() else data.getJSONArray("newAdded")
            for ((section, rows) in listOf(
                "season" to data.getJSONObject("newAnime").getJSONArray("date"),
                "hot" to hot, "new" to added)) {
                require(rows.length() <= 5000)
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val seasonal = section == "season"
                    val item = parseAnime(
                        row,
                        if (seasonal) row.optString("volume")
                        else Regex("共\\s*\\d+\\s*集").find(row.optString("info"))?.value.orEmpty(),
                        day = if (seasonal) row.optInt("week", 0).takeIf { it in 1..7 }?.rem(7) else null,
                        time = if (seasonal) Anime.scheduleTime(row.optString("upTimeHours")) else null)
                    val previous = results[item.id]
                    results[item.id] = previous?.copy(sections = (previous.sections + section).distinct(),
                        latestVideoId = previous.latestVideoId.ifEmpty { item.latestVideoId },
                        updateDay = previous.updateDay ?: item.updateDay,
                        updateTime = previous.updateTime ?: item.updateTime)
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

        private fun parseAnime(row: JSONObject, label: String, day: Int? = null, time: String? = null): Anime {
            val id = row.get("animeSn").toString()
            val title = row.getString("title").trim()
            val cover = row.getString("cover")
            require(id.matches(Regex("[1-9][0-9]{0,9}")) && title.isNotEmpty() &&
                title.length < 500 && OfficialSite.image(cover) && label.length <= 200)
            return Anime(id, title, cover, label,
                row.opt("videoSn")?.toString()?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) }.orEmpty(),
                updateDay = day, updateTime = time)
        }
    }
}
