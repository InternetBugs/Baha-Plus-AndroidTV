package app.bahaplus.tv.data

import app.bahaplus.tv.core.Danmaku
import app.bahaplus.tv.core.DanmakuMode
import app.bahaplus.tv.experiment.ApiFailure
import org.json.JSONObject

/** Port of Baha-Plus NativeHttpApi.parseDanmaku; no HTML is executed or displayed. */
internal object NativeDanmakuParser {
    private val idPattern = Regex("[0-9]{1,20}")
    private val colorPattern = Regex("#[0-9a-fA-F]{6}")

    fun parse(response: JSONObject): List<Danmaku> {
        val rows = response.optJSONObject("data")?.optJSONArray("danmu")
            ?: throw ApiFailure("danmaku-format")
        if (rows.length() > 20_000) throw ApiFailure("danmaku-format")
        val items = LinkedHashMap<String, Danmaku>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val rawId = row.opt("sn")
            if (rawId !is String && rawId !is Int && rawId !is Long) continue
            val id = rawId.toString()
            if (!idPattern.matches(id) || items.containsKey(id)) continue
            val time = (row.opt("time") as? Number)?.toDouble() ?: continue
            if (!time.isFinite() || time < 0 || time > 1_728_000) continue
            val html = row.opt("text") as? String ?: continue
            if (html.length > 10_000) continue
            val text = try { InertHtml.parse(html).text() } catch (_: IllegalArgumentException) { continue }
            if (text.isEmpty() || text.length > 500) continue
            val rawColor = row.opt("color") as? String
            val color = if (rawColor != null && colorPattern.matches(rawColor))
                (0xff000000L or rawColor.substring(1).toLong(16)).toInt() else -1
            items[id] = Danmaku(id, text, (time * 100).toLong(), when (row.opt("position")) {
                1 -> DanmakuMode.TOP
                2 -> DanmakuMode.BOTTOM
                else -> DanmakuMode.ROLLING
            }, when (row.opt("size")) { 0 -> 16; 2 -> 28; else -> 24 }, color)
        }
        return items.values.sortedWith(compareBy<Danmaku> { it.timeMs }.thenBy { it.id.length }.thenBy { it.id })
    }
}
