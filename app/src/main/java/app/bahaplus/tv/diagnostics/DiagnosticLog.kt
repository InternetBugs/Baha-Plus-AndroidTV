package app.bahaplus.tv.diagnostics

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Opt-in, bounded journal. Call on the diagnostics worker, except for the final crash record. */
class DiagnosticLog(private val directory: File, private val partBytes: Int = 256 * 1024) {
    private val current = File(directory, "current.txt")
    private val previous = File(directory, "previous.txt")
    private val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    @Volatile var recording = false
        private set
    @Volatile var failed = false
        private set

    init { require(partBytes >= 1024) }

    @Synchronized fun start(environment: String) {
        recording = false
        check(directory.isDirectory || directory.mkdirs()) { "diagnostic-directory" }
        check(!previous.exists() || previous.delete()) { "diagnostic-clear" }
        current.writeText(DiagnosticPrivacy.environment(environment).take(minOf(8192, partBytes / 8)) + "\n", Charsets.UTF_8)
        failed = false
        recording = true
        record("capture.started")
    }

    @Synchronized fun record(event: String, vararg fields: Pair<String, Any>) {
        if (!recording || event !in EVENTS) return
        val details = fields.mapNotNull { (key, value) -> safeField(key, value)?.let { "$key=$it" } }
        val line = "${date.format(Date())} $event${if (details.isEmpty()) "" else " " + details.joinToString(" ")}\n"
        append(line)
    }

    @Synchronized fun crash(error: Throwable) {
        if (!recording) return
        // Exception messages/causes can contain URLs, credentials or page content.
        record("app.crash")
        var cause: Throwable? = error
        repeat(3) {
            val item = cause ?: return@repeat
            append("exception=${item.javaClass.name.replace(Regex("[^A-Za-z0-9_.$]"), "_").take(160)}\n")
            item.stackTrace.filter { it.className.startsWith("app.bahaplus.tv.") }.take(20).forEach { frame ->
                append("  ${frame.className.take(160)}.${frame.methodName.replace(Regex("[^A-Za-z0-9_$<>-]"), "_").take(100)}:${frame.lineNumber}\n")
            }
            cause = item.cause?.takeIf { it !== item }
        }
        stop()
    }

    @Synchronized fun stop() {
        record("capture.stopped")
        recording = false
    }

    @Synchronized fun abort() {
        recording = false
        failed = true
    }

    @Synchronized fun clear() {
        recording = false
        check(!current.exists() || current.delete()) { "diagnostic-clear" }
        check(!previous.exists() || previous.delete()) { "diagnostic-clear" }
        failed = false
    }

    @Synchronized fun size(): Long = current.length() + previous.length()

    @Synchronized fun snapshot(environment: String): String {
        // Read only our two fixed files, including a journal left by a killed process.
        val text = listOf(previous, current).filter { it.isFile }.joinToString("") {
            it.inputStream().use { stream -> String(stream.readNBytesCompat(partBytes), Charsets.UTF_8) }
        }
        return DiagnosticPrivacy.environment(environment).take(8192) +
            "\n\n--- Recorded events (UTC; oldest records may rotate out) ---\n" + text
    }

    private fun append(line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (current.length() + bytes.size > partBytes) {
            check(!previous.exists() || previous.delete()) { "diagnostic-rotate" }
            check(current.renameTo(previous)) { "diagnostic-rotate" }
        }
        current.appendBytes(bytes)
    }

    private fun safeField(key: String, value: Any): String? = when (key) {
        "screen" -> (value as? String)?.takeIf { it in setOf("home", "search", "favorites", "history", "settings") }
        "purpose" -> (value as? String)?.takeIf { it in setOf("HOME", "LOGIN", "QR_LOGIN", "PLAY") }
        "phase" -> (value as? String)?.takeIf { it in setOf("content", "age", "advertisement", "error", "loading", "idle",
            "direct", "enabled", "compiled", "cached", "failed", "page", "device", "metadata", "policy",
            "ad-config", "ad-start", "ad-end", "source", "device-lock", "video-start", "playback-state", "progress") }
        "code" -> (value as? String)?.takeIf {
            it in ERROR_CODES || Regex("media-[0-9]{4,6}").matches(it)
        }
        "state", "count", "quality", "positionSeconds", "durationSeconds", "httpStatus", "webError", "memoryLevel", "elapsedMs" ->
            (value as? Number)?.toLong()?.toString()
        "signedIn", "lowResources", "autoNext", "playing", "crashed", "accepted" -> (value as? Boolean)?.toString()
        else -> null
    }

    companion object {
        private val ERROR_CODES = setOf(
            "webview-unavailable", "webview-renderer-gone", "operation-error", "navigation-error",
            "native-data-invalid", "native-protocol", "session-stale", "account-stale", "account-format",
            "network", "official-rejected", "response-too-large", "login-required", "redirect-rejected", "verification-required",
            "favorite-unavailable", "favorite-rejected", "parent-verification-required", "short-ad-needs-fallback",
            "progress-denied", "ok", "guest", "ad-missing", "video-mismatch", "policy-format", "policy-rejected",
            "detail-cancelled", "detail-network", "detail-http", "detail-invalid",
            "detail-too-large", "detail-rejected", "detail-video-unavailable",
            "danmaku-format", "danmaku-load"
        )
        private val OFFICIAL_EVENTS = setOf(
            "qr-login-ready", "qr-login-retrying", "qr-login-expired", "qr-login-error", "qr-login-complete",
            "login-complete", "login-finished", "verification-required", "operation-error", "navigation-error"
        )
        private val EVENTS = setOf(
            "capture.started", "capture.stopped", "app.created", "app.resumed", "app.paused", "app.destroyed",
            "app.crash", "app.state", "app.memory", "ui.tab", "ui.search", "ui.detail", "ui.refresh",
            "account.login", "account.logout", "player.request", "player.active", "player.ended",
            "player.exit", "player.error", "player.state", "player.playing", "player.seek", "player.quality",
            "web.started", "web.finished", "web.error", "web.http-error", "web.renderer-gone", "web.script-compat",
            "api.error", "player.http", "player.progress", "detail.request", "detail.completed", "detail.failed",
            "player.danmaku", "player.danmaku-failed"
        ) + OFFICIAL_EVENTS.map { "official.$it" }
    }
}

object DiagnosticPrivacy {
    fun environment(value: String): String = value.lineSequence().take(80).joinToString("\n") { line ->
        if (Regex("(?i)cookie|authorization|password|session.?id|access.?token|refresh.?token|bearer|[?&]token=")
                .containsMatchIn(line)) "[redacted]"
        else line.replace(Regex("(?i)(?:https?|wss?)://\\S+"), "[url]")
            .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[email]")
            .replace(Regex("[\\p{Cntrl}&&[^\\t]]"), " ").take(512)
    }
}

// InputStream.readNBytes was added after our minimum Android API.
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
