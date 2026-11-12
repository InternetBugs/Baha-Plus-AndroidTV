package app.bahaplus.tv.core

import android.content.Context
import org.json.JSONObject

class Preferences(context: Context) {
    private val store = context.getSharedPreferences("tv-preferences", Context.MODE_PRIVATE)
    var traditional: Boolean
        get() = store.getBoolean("traditional", false)
        set(value) { store.edit().putBoolean("traditional", value).apply() }
    var lowResources: Boolean
        get() = store.getBoolean("lowResources", true)
        set(value) { store.edit().putBoolean("lowResources", value).apply() }
    var autoNext: Boolean
        get() = store.getBoolean("autoNext", true)
        set(value) { store.edit().putBoolean("autoNext", value).apply() }
    var quality: Int
        get() = store.getInt("quality", 0)
        set(value) { store.edit().putInt("quality", value).apply() }
    var speed: Float
        get() = store.getFloat("speed", 1f)
        set(value) { store.edit().putFloat("speed", value).apply() }
    private val dictionary by lazy {
        JSONObject(context.assets.open("data/search-traditional.json").bufferedReader().use { it.readText() })
    }
    fun toTraditional(text: String): String = buildString {
        var offset = 0
        while (offset < text.length) {
            val codepoint = text.codePointAt(offset)
            val token = String(Character.toChars(codepoint))
            append(dictionary.optString(token, token))
            offset += Character.charCount(codepoint)
        }
    }
    fun text(simplified: String, traditional: String) = if (this.traditional) traditional else simplified
}
