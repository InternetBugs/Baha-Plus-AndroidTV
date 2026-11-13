package app.bahaplus.tv.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewCompatibilityTest {
    private val error = "Uncaught SyntaxError: Unexpected token ("
    private val player = "https://i2.bahamut.com.tw/build/js/anime_player.js"

    @Test fun recognizesOfficialPlayerParserErrorsWithCacheVersions() {
        assertTrue(WebViewCompatibility.officialPlayerSyntaxError(error, player))
        assertTrue(WebViewCompatibility.officialPlayerSyntaxError(error, "$player?v=20261113"))
        assertTrue(WebViewCompatibility.officialPlayerSyntaxError(error,
            "https://i2.bahamut.com.tw:443/build/js/anime_player.js"))
    }

    @Test fun unrelatedScriptsAndRuntimeErrorsCannotTriggerCompatibilityFailure() {
        assertFalse(WebViewCompatibility.officialPlayerSyntaxError("Uncaught TypeError: failed", player))
        assertFalse(WebViewCompatibility.officialPlayerSyntaxError("Uncaught ReferenceError: missing", player))
        assertFalse(WebViewCompatibility.officialPlayerSyntaxError("Reported $error", player))
        assertFalse(WebViewCompatibility.officialPlayerSyntaxError(error,
            "https://i2.bahamut.com.tw/js/ad.js"))
        assertFalse(WebViewCompatibility.officialPlayerSyntaxError(error,
            "https://ani.gamer.com.tw/animeVideo.php"))
    }

    @Test fun rejectsUntrustedOrMalformedSourceAddresses() {
        for (source in listOf(
            "http://i2.bahamut.com.tw/build/js/anime_player.js",
            "https://i2.bahamut.com.tw.attacker.invalid/build/js/anime_player.js",
            "https://i2.bahamut.com.tw@attacker.invalid/build/js/anime_player.js",
            "https://user@i2.bahamut.com.tw/build/js/anime_player.js",
            "https://i2.bahamut.com.tw:8443/build/js/anime_player.js",
            "/build/js/anime_player.js",
            "invalid source"
        )) assertFalse(source, WebViewCompatibility.officialPlayerSyntaxError(error, source))
    }
}
