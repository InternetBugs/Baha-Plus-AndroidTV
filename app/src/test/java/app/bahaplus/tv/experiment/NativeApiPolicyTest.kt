package app.bahaplus.tv.experiment

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeApiPolicyTest {
    @Test fun sourceRequiresAuthorizedHostAndRequestedDeviceType() {
        fun body(url: String, type: Int = 1) = JSONObject("""{"data":{"deviceid":"rotated","srcUseCases":[
            {"deviceType":$type,"src":{"playlist":"$url"}}]}}""")
        val url = "https://bahamut.akamaized.net/test/master.m3u8?session=ephemeral"
        assertEquals(url to "rotated", NativeApiPolicy.playlist(body(url)))
        assertThrows(Exception::class.java) { NativeApiPolicy.playlist(body("https://evil.example/test.m3u8")) }
        assertThrows(Exception::class.java) { NativeApiPolicy.playlist(body(url, 2)) }
        assertFalse(NativeApiPolicy.media("https://bahamut.akamaized.net:8443/test.m3u8"))
        assertFalse(NativeApiPolicy.media("https://user@bahamut.akamaized.net/test.m3u8"))
    }

    @Test fun sourceGateRequiresConsentAndRealAdCompletion() {
        val guest = PlaybackGate(true)
        assertFalse(guest.canRequestSource)
        assertThrows(IllegalStateException::class.java) { guest.started() }
        guest.consent = true
        guest.started()
        assertThrows(IllegalStateException::class.java) { guest.ended(29.9) }
        assertFalse(guest.canRequestSource)
        guest.ended(30.0)
        assertTrue(guest.canRequestSource)
        val exempt = PlaybackGate(false)
        assertFalse(exempt.canRequestSource)
        exempt.consent = true
        assertTrue(exempt.canRequestSource)
    }

    @Test fun watchClockExcludesSeekPauseAndBuffering() {
        val clock = WatchClock()
        for (i in 0..4) clock.sample(i / 4.0, true, false, i * 250L)
        assertEquals(1.0, clock.total, .001)
        clock.sample(61.0, true, false, 1250)
        assertEquals(1.0, clock.total, .001)
        clock.sample(61.25, true, false, 1500)
        assertEquals(1.25, clock.total, .001)
        clock.sample(61.25, false, false, 1750)
        clock.sample(61.25, true, false, 10000)
        assertEquals(1.25, clock.total, .001)
        clock.sample(61.50, true, false, 10250)
        assertEquals(1.5, clock.total, .001)
        clock.sample(61.50, true, false, 10500)
        assertEquals(1.5, clock.total, .001)
        clock.discontinuity()
        clock.sample(600.0, true, false, 10750)
        assertEquals(1.5, clock.total, .001)
    }

    @Test fun officialCompletionMarkerAndActualPositionArePreserved() {
        assertEquals(61L, NativeApiPolicy.progressParameter(60.5, 1500.0))
        assertEquals(-1L, NativeApiPolicy.progressParameter(1380.0, 1500.0))
        assertThrows(IllegalArgumentException::class.java) {
            NativeApiPolicy.progressParameter(Double.NaN, 1500.0)
        }
    }

    @Test fun naturalEndFlushCountsTheFinalFractionWithoutRoundingUpShortAds() {
        val clock = WatchClock()
        clock.sample(0.0, true, false, 0)
        for (i in 1..119) clock.sample(i / 4.0, true, false, i * 250L)
        val gate = PlaybackGate(true).apply { consent = true; started() }
        assertThrows(IllegalStateException::class.java) { gate.ended(clock.total) }
        clock.sample(30.03, true, false, 30030)
        assertEquals(30.03, clock.total, .001)
        gate.ended(clock.total)
        assertTrue(gate.canRequestSource)
        val short = WatchClock()
        short.sample(0.0, true, false, 0)
        for (i in 1..118) short.sample(i / 4.0, true, false, i * 250L)
        short.sample(29.9, true, false, 29900)
        val shortGate = PlaybackGate(true).apply { consent = true; started() }
        assertThrows(IllegalStateException::class.java) { shortGate.ended(short.total) }
    }

    @Test fun clockJitterDoesNotAccumulateLossOrPermitMoreThanElapsedPlayback() {
        val clock = WatchClock()
        clock.sample(0.0, true, false, 0)
        clock.sample(.24, true, false, 250)
        clock.sample(.50, true, false, 500)
        clock.sample(.74, true, false, 750)
        clock.sample(1.0, true, false, 1000)
        assertEquals(1.0, clock.total, .001)
        clock.sample(1.3, true, false, 1250)
        assertEquals(1.25, clock.total, .001)
        clock.sample(1.3, false, false, 1500)
        clock.sample(1.3, true, false, 2000)
        clock.sample(1.55, true, false, 2250)
        assertEquals(1.5, clock.total, .001)
        clock.discontinuity()
        clock.sample(100.0, true, false, 2500)
        assertEquals(1.5, clock.total, .001)
    }

    @Test fun publicAdConfigurationIsParsedWithoutJavaScript() {
        val source = """var getAd = function() {
            var iK = getAdIndex(8, 2);
            var adlist = [["123","https:\/\/www.gamer.com.tw\/adcounter.php?id=45","45","video"],
            ["456","","78","facebook"]]; return adlist[iK]; }"""
        assertEquals(listOf(VideoAd("123", "45")), NativeApiPolicy.videoAds(source))
        assertThrows(Exception::class.java) { NativeApiPolicy.videoAds("unexpected new config format") }
    }
}
