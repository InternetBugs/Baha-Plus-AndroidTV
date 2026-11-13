package app.bahaplus.tv.core

import java.net.URI

/** Recognize a parser failure in the official player, without logging URLs or
 * treating unrelated third-party console messages as a playback failure. */
object WebViewCompatibility {
    fun officialPlayerSyntaxError(message: String, source: String): Boolean {
        if (!message.startsWith("Uncaught SyntaxError:")) return false
        return runCatching {
            val uri = URI(source)
            uri.scheme == "https" && uri.host == "i2.bahamut.com.tw" &&
                uri.userInfo == null && uri.port in setOf(-1, 443) &&
                uri.path == "/build/js/anime_player.js"
        }.getOrDefault(false)
    }
}
