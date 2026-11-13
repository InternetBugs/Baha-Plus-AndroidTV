package app.bahaplus.tv.diagnostics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun recordingRequiresOptInAndStopsWithoutRemovingSavedEvents() {
        val log = DiagnosticLog(temporary.newFolder())
        log.record("ui.search")
        assertEquals(0, log.size())
        log.start("Baha Plus TV test")
        log.record("ui.tab", "screen" to "search")
        log.stop()
        log.record("ui.detail")
        val report = log.snapshot("Device test")
        assertTrue(report.contains("screen=search"))
        assertTrue(report.contains("capture.stopped"))
        assertFalse(report.contains("ui.detail"))
        assertFalse(log.recording)
    }

    @Test fun onlyKnownEventsAndTypedFieldsReachDisk() {
        val log = DiagnosticLog(temporary.newFolder())
        log.start("Cookie: private-cookie\nhttps://example.invalid/private?token=secret\ncontact@example.invalid")
        log.record("ui.search", "query" to "private-search", "accountId" to "private-user",
            "screen" to "https://example.invalid/private", "code" to "private-token", "count" to 4)
        log.record("https://example.invalid/token")
        log.record("player.error", "code" to "media-2001")
        val report = log.snapshot("Authorization: Bearer private-bearer")
        for (secret in listOf("private-cookie", "private-search", "private-user", "private-token",
            "example.invalid", "private-bearer", "https://")) assertFalse(secret, report.contains(secret))
        assertTrue(report.contains("count=4"))
        assertTrue(report.contains("code=media-2001"))
    }

    @Test fun rotationKeepsRecentEventsWithinTheDiskBudget() {
        val directory = temporary.newFolder()
        val log = DiagnosticLog(directory, 1024)
        log.start("test")
        repeat(150) { log.record("player.seek", "positionSeconds" to it) }
        assertTrue(log.size() <= 2048)
        val report = log.snapshot("test")
        assertTrue(report.contains("positionSeconds=149"))
        assertFalse(report.contains("positionSeconds=0\n"))
        assertTrue(directory.listFiles()!!.size <= 2)
    }

    @Test fun processRestartRetainsTheLastReportButDoesNotResumeRecording() {
        val directory = temporary.newFolder()
        DiagnosticLog(directory).apply {
            start("test")
            record("web.error", "webError" to -2, "purpose" to "PLAY")
        }
        val restarted = DiagnosticLog(directory)
        assertFalse(restarted.recording)
        assertTrue(restarted.snapshot("test").contains("webError=-2"))
        restarted.start("new test")
        assertFalse(restarted.snapshot("test").contains("webError=-2"))
        restarted.clear()
        assertEquals(0, restarted.size())
    }

    @Test fun crashReportsKeepAppFramesAndExceptionTypesWithoutMessages() {
        val log = DiagnosticLog(temporary.newFolder())
        log.start("test")
        val error = IllegalStateException("https://example.invalid/?token=private", RuntimeException("private-account"))
        error.stackTrace = arrayOf(
            StackTraceElement("app.bahaplus.tv.MainActivity", "play", "MainActivity.kt", 42),
            StackTraceElement("external.Private", "privateMethod", "secret", 1)
        )
        log.crash(error)
        val report = log.snapshot("test")
        assertTrue(report.contains("app.crash"))
        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("app.bahaplus.tv.MainActivity.play:42"))
        assertFalse(report.contains("private"))
        assertFalse(report.contains("external.Private"))
        assertFalse(log.recording)
    }
}
