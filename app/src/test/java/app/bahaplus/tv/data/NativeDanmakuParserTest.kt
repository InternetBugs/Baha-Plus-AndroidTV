package app.bahaplus.tv.data

import app.bahaplus.tv.core.DanmakuMode
import app.bahaplus.tv.experiment.ApiFailure
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeDanmakuParserTest {
    private fun row(id: Any, time: Any, position: Any = 0, size: Any = 1,
        text: Any = "<span>測試 &amp; 彈幕 &#x1f41f;</span>", color: Any = "#12AbEF") =
        JSONObject().put("sn", id).put("time", time).put("position", position)
            .put("size", size).put("text", text).put("color", color)

    private fun parse(vararg rows: Any) = NativeDanmakuParser.parse(
        JSONObject().put("data", JSONObject().put("danmu", JSONArray(rows.toList()))))

    @Test fun portsOfficialTenthsPositionsSizesColorsAndInertText() {
        val items = parse(row(3, 126.5, 2, 2), row(1, 0, 0, 0), row(2, 50, 1))
        assertEquals(listOf("1", "2", "3"), items.map { it.id })
        assertEquals(listOf(0L, 5000L, 12650L), items.map { it.timeMs })
        assertEquals(listOf(DanmakuMode.ROLLING, DanmakuMode.TOP, DanmakuMode.BOTTOM), items.map { it.mode })
        assertEquals(listOf(16, 24, 28), items.map { it.size })
        assertEquals("測試 & 彈幕 🐟", items[0].text)
        assertEquals(0xff12abef.toInt(), items[0].color)
        val inert = parse(row(4, 10, text = "<script>secret()</script><style>bad</style><b>纯文字</b>"))
        assertEquals("纯文字", inert.single().text)
    }

    @Test fun malformedRowsAreSkippedAndDefaultsStayCompatible() {
        val items = parse(row(1, -1), row(2, "100"), row("bad", 0), row(1.5, 0),
            row(3, 1_728_001), row(4, 0, text = ""), row(5, 0, text = "x".repeat(501)),
            row(6, 0, text = "x".repeat(10_001)), row(7, 0, text = JSONObject()), "bad",
            row(8, 0, 99, 99, color = "red"))
        assertEquals("8", items.single().id)
        assertEquals(DanmakuMode.ROLLING, items.single().mode)
        assertEquals(24, items.single().size)
        assertEquals(-1, items.single().color)
    }

    @Test fun deduplicatesIdsAndSortsNumericIdsWithoutLongOverflow() {
        val items = parse(row("99999999999999999999", 1), row("10", 1), row(2, 1),
            row("2", 2), row(9, 0))
        assertEquals(listOf("9", "2", "10", "99999999999999999999"), items.map { it.id })
        assertEquals(100L, items[1].timeMs)
    }

    @Test fun missingAndOversizedListsFailButEmptyEpisodesAreValid() {
        assertEquals(emptyList<Any>(), parse())
        for (body in listOf(JSONObject(), JSONObject("""{"data":{"danmu":{}}}"""),
            JSONObject().put("data", JSONObject().put("danmu", JSONArray(List(20_001) { JSONObject() }))))) {
            assertEquals("danmaku-format", assertThrows(ApiFailure::class.java) {
                NativeDanmakuParser.parse(body)
            }.stage)
        }
    }
}
