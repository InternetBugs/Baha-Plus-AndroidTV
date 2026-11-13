package app.bahaplus.tv.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    private fun release(tag: String = "v0.3.0") = JSONObject()
        .put("tag_name", tag)
        .put("draft", false)
        .put("prerelease", false)
        .put("body", "修复问题\n增加功能")

    @Test fun comparesVersionComponentsNumerically() {
        assertTrue(ReleaseVersion.parse("0.10.0") > ReleaseVersion.parse("0.9.9"))
        assertTrue(ReleaseVersion.parse("1.0.0") > ReleaseVersion.parse("0.99.99"))
        assertTrue(ReleaseVersion.parse("0.2.10") > ReleaseVersion.parse("0.2.9"))
        assertTrue(ReleaseVersion.parse("99999999999999999999.0.0") > ReleaseVersion.parse("9999999999999999999.9.9"))
    }

    @Test fun prefixAndBuildMetadataDoNotChangePrecedence() {
        assertEquals(0, ReleaseVersion.parse("v0.2.0").compareTo(ReleaseVersion.parse("0.2.0")))
        assertEquals(0, ReleaseVersion.parse("0.2.0+build.12").compareTo(ReleaseVersion.parse("v0.2.0+other")))
    }

    @Test fun followsSemverPrereleaseOrdering() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta",
            "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        ordered.zipWithNext().forEach { (left, right) ->
            assertTrue("$left < $right", ReleaseVersion.parse(left) < ReleaseVersion.parse(right))
        }
    }

    @Test fun rejectsMalformedVersionsInsteadOfGuessing() {
        listOf("", "latest", "0.2", "0.2.0.1", "v01.2.0", "0.02.0", "0.2.00",
            "0.2.0-rc.01", "0.2.0-", "0.2.0+", "0.2.0\n", "0.2.0/elsewhere",
            "1".repeat(101)).forEach { invalid ->
            assertThrows(invalid, IllegalArgumentException::class.java) { ReleaseVersion.parse(invalid) }
        }
    }

    @Test fun newerReleaseIncludesNotesAndCanonicalRepositoryPage() {
        val result = AppUpdates.evaluate("0.2.0", release().put("html_url", "https://unrelated.invalid/"))
        assertTrue(result is UpdateCheckResult.Available)
        val update = (result as UpdateCheckResult.Available).release
        assertEquals("0.3.0", update.versionName)
        assertEquals("https://github.com/InternetBugs/Baha-Plus-AndroidTV/releases/tag/v0.3.0", update.pageUrl)
        assertEquals("修复问题\n增加功能", update.notes)
    }

    @Test fun sameOlderAndRebuiltVersionsDoNotOfferAnUpdate() {
        for (version in listOf("v0.2.0", "0.2.0+build.3", "v0.1.9")) {
            assertEquals(version, UpdateCheckResult.UpToDate, AppUpdates.evaluate("0.2.0", release(version)))
        }
    }

    @Test fun stableReleaseCanUpgradeAnInstalledPrerelease() {
        assertTrue(AppUpdates.evaluate("0.3.0-rc.2", release("v0.3.0")) is UpdateCheckResult.Available)
        assertEquals(UpdateCheckResult.UpToDate, AppUpdates.evaluate("0.4.0-rc.1", release("v0.3.0")))
    }

    @Test fun skipsDraftsAndPrereleases() {
        assertEquals(UpdateCheckResult.UpToDate, AppUpdates.evaluate("0.2.0", release().put("draft", true)))
        assertEquals(UpdateCheckResult.UpToDate, AppUpdates.evaluate("0.2.0", release().put("prerelease", true)))
        assertEquals(UpdateCheckResult.UpToDate, AppUpdates.evaluate("0.2.0", release("v0.3.0-rc.1")))
    }

    @Test fun acceptsEmptyNotesAndLimitsDisplayedNotes() {
        for (json in listOf(release().put("body", JSONObject.NULL), release().apply { remove("body") })) {
            assertEquals("", (AppUpdates.evaluate("0.2.0", json) as UpdateCheckResult.Available).release.notes)
        }
        val result = AppUpdates.evaluate("0.2.0", release().put("body", "x".repeat(20_000))) as UpdateCheckResult.Available
        assertEquals(12_000, result.release.notes.length)
    }

    @Test fun malformedResponsesAreNotReportedAsNoUpdates() {
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdates.evaluate("0.2.0", release("unexpected-tag"))
        }
        assertThrows(org.json.JSONException::class.java) {
            AppUpdates.evaluate("0.2.0", JSONObject().put("message", "API rate limit exceeded"))
        }
    }
}
