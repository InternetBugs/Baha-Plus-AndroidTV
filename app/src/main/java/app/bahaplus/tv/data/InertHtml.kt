package app.bahaplus.tv.data

import java.util.Locale

/**
 * Bounded reader for the official account markup, not a browser or script engine.
 * Supports the small tag/class/attribute selector subset used by NativeAccountApi.
 * Script, style and comment contents are discarded before tokenization.
 */
internal class InertHtml private constructor(val tag: String, private val attributes: Map<String, String>,
    private val parent: InertHtml? = null) {
    private val content = mutableListOf<Any>()
    fun attr(name: String): String = attributes[name].orEmpty()
    fun hasClass(name: String) = attr("class").split(Regex("\\s+")).contains(name)
    fun text(): String = content.joinToString(" ") {
        if (it is InertHtml) it.text() else it.toString()
    }.replace(Regex("[\\s\\u00a0]+"), " ").trim()

    fun selectFirst(selector: String): InertHtml? = select(selector).firstOrNull()
    fun select(selector: String): List<InertHtml> {
        val alternatives = selector.split(',').map { it.trim().split(Regex("\\s+")) }
        val result = mutableListOf<InertHtml>()
        fun visit(node: InertHtml) {
            for (child in node.content.filterIsInstance<InertHtml>()) {
                if (alternatives.any { child.matchesChain(it) }) result += child
                visit(child)
            }
        }
        visit(this)
        return result
    }

    private fun matchesChain(parts: List<String>): Boolean {
        if (!matches(parts.last())) return false
        var ancestor = parent
        for (part in parts.dropLast(1).asReversed()) {
            while (ancestor != null && !ancestor.matches(part)) ancestor = ancestor.parent
            if (ancestor == null) return false
            ancestor = ancestor.parent
        }
        return true
    }

    private fun matches(selector: String): Boolean {
        val basic = selector.substringBefore('[')
        val wantedTag = basic.substringBefore('.')
        if (wantedTag.isNotEmpty() && wantedTag != tag) return false
        if (basic.contains('.') && !hasClass(basic.substringAfter('.'))) return false
        if (!selector.contains('[')) return true
        val condition = selector.substringAfter('[').removeSuffix("]")
        return if (condition.contains("*=")) attr(condition.substringBefore("*="))
            .contains(condition.substringAfter("*=").trim('\'', '"'))
        else attributes.containsKey(condition)
    }

    companion object {
        private val rawText = Regex("""<(script|style)\b[^>]*>.*?(?:</\1\s*>|\z)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val tokens = Regex("""<!--.*?(?:-->|\z)|<![^>]*>|<(/?)([A-Za-z][A-Za-z0-9:-]*)((?:"[^"]*"|'[^']*'|[^'">])*)>""",
            RegexOption.DOT_MATCHES_ALL)
        private val attrs = Regex("""([A-Za-z_:][A-Za-z0-9_:.-]*)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?""")
        private val voidTags = setOf("area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr")
        private val entities = mapOf("amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<",
            "gt" to ">", "nbsp" to "\u00a0", "hellip" to "…", "mdash" to "—", "ndash" to "–",
            "middot" to "·", "copy" to "©", "reg" to "®", "trade" to "™")
        private val entityPattern = Regex("&(#(?:[xX][0-9a-fA-F]{1,6}|[0-9]{1,7})|[A-Za-z][A-Za-z0-9]{1,31});")

        internal fun decode(text: String): String = entityPattern.replace(text) { match ->
            val name = match.groupValues[1]
            if (!name.startsWith("#")) entities[name] ?: match.value
            else {
                val value = if (name.startsWith("#x", true)) name.substring(2).toIntOrNull(16)
                    else name.substring(1).toIntOrNull()
                if (value == null || value !in 1..0x10ffff || value in 0xd800..0xdfff) "\ufffd"
                else String(Character.toChars(value))
            }
        }

        fun parse(source: String): InertHtml {
            require(source.length <= 2_000_000)
            val html = source.replace(rawText, "")
            val root = InertHtml("", emptyMap())
            val stack = mutableListOf(root)
            var position = 0
            var count = 0
            for (token in tokens.findAll(html)) {
                val text = html.substring(position, token.range.first)
                if (text.isNotBlank()) stack.last().content += decode(text)
                position = token.range.last + 1
                val tag = token.groupValues[2].lowercase(Locale.ROOT)
                if (tag.isEmpty()) continue
                if (token.groupValues[1] == "/") {
                    val index = stack.indexOfLast { it.tag == tag }
                    if (index > 0) while (stack.size > index) stack.removeAt(stack.lastIndex)
                    continue
                }
                require(++count <= 50000 && stack.size < 128)
                val attributes = linkedMapOf<String, String>()
                for (attribute in attrs.findAll(token.groupValues[3])) {
                    val name = attribute.groupValues[1].lowercase(Locale.ROOT)
                    val value = (2..4).firstNotNullOfOrNull { attribute.groups[it]?.value }.orEmpty()
                    // HTML keeps the first occurrence of a repeated attribute.
                    if (!attributes.containsKey(name)) attributes[name] = decode(value)
                }
                val node = InertHtml(tag, attributes, stack.last())
                stack.last().content += node
                if (tag !in voidTags && !token.groupValues[3].trimEnd().endsWith("/")) stack += node
            }
            if (position < html.length) stack.last().content += decode(html.substring(position))
            return root
        }
    }
}
