package app.bahaplus.tv.diagnostics

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit

/** Temporary, token-protected transfer of one immutable report on one LAN interface. */
class FeedbackServer(
    address: InetAddress,
    private val report: ByteArray,
    private val issueUrl: String = ISSUE_URL,
    private val lifetimeMillis: Long = 10 * 60 * 1000,
    private val onClosed: () -> Unit = {}
) : Closeable {
    private val server: ServerSocket
    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lifetimeMillis)
    private val timer: Timer
    @Volatile private var closed = false
    @Volatile private var client: Socket? = null
    val running: Boolean get() = !closed
    val url: String
    private val host: String

    init {
        require(address is Inet4Address && (address.isSiteLocalAddress || address.isLoopbackAddress))
        require(report.size <= 600 * 1024 && lifetimeMillis in 1..10 * 60 * 1000)
        server = ServerSocket(0, 2, address).apply { soTimeout = 1000 }
        timer = Timer("feedback-expiry", true)
        host = "${address.hostAddress}:${server.localPort}"
        url = "http://$host/$token/"
        timer.schedule(object : TimerTask() {
            override fun run() { close() }
        }, lifetimeMillis)
        Thread({ serve() }, "feedback-transfer").apply { isDaemon = true; start() }
    }

    private fun serve() {
        try {
            while (!closed && System.nanoTime() < deadline) {
                try {
                    server.accept().use { socket ->
                        client = socket
                        socket.soTimeout = 1500
                        val timeout = object : TimerTask() {
                            override fun run() { runCatching { socket.close() } }
                        }
                        // The window/expiry may close the timer immediately after accept().
                        if (runCatching { timer.schedule(timeout, 5000) }.isFailure) return
                        try { runCatching { respond(socket) } } finally { timeout.cancel() }
                        client = null
                    }
                } catch (_: SocketTimeoutException) { /* Recheck expiration. */ }
            }
        } catch (_: java.io.IOException) {
            // Closing the screen closes the socket and unblocks accept/read.
        } finally { close() }
    }

    private fun respond(socket: Socket) {
        val input = socket.getInputStream()
        val request = ByteArrayOutputStream()
        var ending = 0
        val readDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (request.size() < 8192 && System.nanoTime() < readDeadline && System.nanoTime() < deadline) {
            val byte = input.read()
            if (byte < 0) return
            request.write(byte)
            ending = when {
                ending == 0 && byte == 13 -> 1
                ending == 1 && byte == 10 -> 2
                ending == 2 && byte == 13 -> 3
                ending == 3 && byte == 10 -> 4
                byte == 13 -> 1
                else -> 0
            }
            if (ending == 4) break
        }
        if (ending != 4 || System.nanoTime() >= deadline) return
        val lines = request.toString("US-ASCII").split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size != 3 || parts[2] !in setOf("HTTP/1.0", "HTTP/1.1")) {
            send(socket, "400 Bad Request", "text/plain", ByteArray(0)); return
        }
        val hosts = lines.drop(1).filter { it.startsWith("Host:", ignoreCase = true) }
        if (hosts.size != 1 || !hosts.single().substringAfter(':').trim().equals(host, ignoreCase = true)) {
            send(socket, "403 Forbidden", "text/plain", ByteArray(0)); return
        }
        if (parts[0] != "GET") {
            send(socket, "405 Method Not Allowed", "text/plain", ByteArray(0)); return
        }
        when (parts[1]) {
            "/$token/" -> send(socket, "200 OK", "text/html; charset=utf-8", page().toByteArray(Charsets.UTF_8))
            "/$token/report.txt" -> send(socket, "200 OK", "text/plain; charset=utf-8", report, download = true)
            else -> send(socket, "404 Not Found", "text/plain", ByteArray(0))
        }
    }

    private fun page(): String = """
        <!doctype html><html lang="zh-Hans"><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Baha Plus TV 日志反馈</title>
        <body><h1>Baha Plus TV 日志反馈</h1>
        <p>1. 下载日志，也可以长按下方内容复制。 / 下載日誌，或長按下方內容複製。</p>
        <p><a href="report.txt" download="BahaPlusTV-log.txt">下载日志 / 下載日誌</a></p>
        <p>2. 前往 GitHub，说明问题、复现步骤和发生时间，附上刚下载的 .txt 文件（也可粘贴日志）。需要 GitHub 账号。</p>
        <p>前往 GitHub，說明問題、重現步驟和發生時間，附上剛下載的 .txt 檔案（也可貼上日誌）。需要 GitHub 帳號。</p>
        <p><a href="${html(issueUrl)}" target="_blank" rel="noopener noreferrer">前往开发者反馈页面 / 前往開發者回報頁面</a></p>
        <p>也可以把文件发送给向你提供此应用的开发者。提交前请检查日志内容。</p>
        <p>也可以將檔案傳送給向你提供此應用程式的開發者。提交前請檢查日誌內容。</p>
        <p>此页面尚未发送任何反馈。电视关闭此窗口或 10 分钟后，下载链接失效。</p>
        <p>此頁面尚未傳送任何回報。電視關閉此視窗或 10 分鐘後，下載連結失效。</p>
        <details><summary>查看日志 / 查看日誌</summary><pre>${html(String(report, Charsets.UTF_8))}</pre></details>
        </body></html>
    """.trimIndent()

    private fun send(socket: Socket, status: String, type: String, body: ByteArray, download: Boolean = false) {
        val headers = "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Connection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
            "X-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'\r\n" +
            (if (download) "Content-Disposition: attachment; filename=\"BahaPlusTV-log.txt\"\r\n" else "") + "\r\n"
        socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        runCatching { client?.close() }
        runCatching { server.close() }
        timer.cancel()
        onClosed()
    }

    companion object {
        const val ISSUE_URL = "https://github.com/InternetBugs/Baha-Plus-AndroidTV/issues/new"
        private fun html(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    }
}
