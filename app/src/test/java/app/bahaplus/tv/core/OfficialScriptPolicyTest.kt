package app.bahaplus.tv.core

import org.junit.Assert.*
import org.junit.Test

class OfficialScriptPolicyTest {
    @Test fun actualProviderAndFeatureProbeControlTranslation() {
        assertEquals(83, OfficialScriptPolicy.major("83.0.4103.120"))
        assertEquals(0, OfficialScriptPolicy.major(null))
        assertEquals(0, OfficialScriptPolicy.major("unknown"))
        assertTrue(OfficialScriptPolicy.needsTranslation(false))
        assertFalse(OfficialScriptPolicy.needsTranslation(true))
    }

    @Test fun acceptsOfficialStaticScriptsWithoutAnIndividualFilenameList() {
        for (file in listOf("build/js/anime_player", "js/anime_email_remind", "js/wall_2020",
            "new-player/unknown-future-script", "vendor/jquery.min")) {
            assertTrue(OfficialScriptPolicy.allowed("https://i2.bahamut.com.tw/$file.js"))
            assertTrue(OfficialScriptPolicy.allowed("https://i2.bahamut.com.tw:443/$file.js?v=20261113"))
        }
        assertTrue(OfficialScriptPolicy.allowed("https://ani.gamer.com.tw/js/new-module.mjs"))
        assertTrue(OfficialScriptPolicy.allowed("https://ani.gamer.com.tw/js/new-script.js?version=123"))
    }

    @Test fun rejectsOtherOriginsCredentialsRedirectShapesAndResources() {
        for (url in listOf(
            "http://i2.bahamut.com.tw/build/js/anime_player.js",
            "https://i2.bahamut.com.tw.attacker.invalid/build/js/anime_player.js",
            "https://i2.bahamut.com.tw@attacker.invalid/build/js/anime_player.js",
            "https://user@i2.bahamut.com.tw/build/js/anime_player.js",
            "https://i2.bahamut.com.tw:8443/build/js/anime_player.js",
            "https://i2.bahamut.com.tw/build/js/%61nime_player.js",
            "https://i2.bahamut.com.tw/build/js/../js/anime_player.js",
            "https://i2.bahamut.com.tw/build/js/anime_player.js#fragment",
            "https://ani.gamer.com.tw/animeVideo.php?sn=1",
            "https://third-party.invalid/ads.js",
            "https://ani.gamer.com.tw/ajax/video.php",
            "https://i2.bahamut.com.tw/video/master.m3u8",
            "/build/js/anime_player.js", "not a URL"
        )) assertFalse(url, OfficialScriptPolicy.allowed(url))
    }

    @Test fun cacheChangesWithSourceTargetAndCompilerVersion() {
        val first = OfficialScriptPolicy.cacheKey("source 1".toByteArray(), 83)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertEquals(first, OfficialScriptPolicy.cacheKey("source 1".toByteArray(), 83))
        assertNotEquals(first, OfficialScriptPolicy.cacheKey("source 2".toByteArray(), 83))
        assertNotEquals(first, OfficialScriptPolicy.cacheKey("source 1".toByteArray(), 84))
    }
}
