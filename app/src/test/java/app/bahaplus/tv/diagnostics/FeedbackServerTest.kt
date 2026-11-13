package app.bahaplus.tv.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FeedbackServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun phoneCanDownloadAnExactReportAndOpenTheFeedbackPage() {
        val report = "Baha Plus TV\n日志 <not-html>\n".toByteArray(Charsets.UTF_8)
        FeedbackServer(loopback, report).use { server ->
            val address = URI(server.url)
            val page = request(address)
            assertTrue(page.startsWith("HTTP/1.1 200"))
            assertTrue(page.contains(FeedbackServer.ISSUE_URL))
            assertTrue(page.contains("&lt;not-html&gt;"))
            assertTrue(page.contains("Cache-Control: no-store"))
            assertTrue(page.contains("Referrer-Policy: no-referrer"))
            val download = request(address, address.path + "report.txt")
            assertTrue(download.contains("Content-Disposition: attachment;"))
            assertEquals(String(report, Charsets.UTF_8), download.substringAfter("\r\n\r\n"))
        }
    }

    @Test fun guessedTokensTraversalOtherHostsAndMutationsCannotReadTheReport() {
        FeedbackServer(loopback, "private report".toByteArray()).use { server ->
            val address = URI(server.url)
            for (path in listOf("/", "/unknown/report.txt", address.path + "../report.txt", address.path + "report.txt?x=1")) {
                val response = request(address, path)
                assertTrue(response.startsWith("HTTP/1.1 404"))
                assertFalse(response.contains("private report"))
            }
            assertTrue(request(address, host = "attacker.invalid").startsWith("HTTP/1.1 403"))
            assertTrue(request(address, method = "POST").startsWith("HTTP/1.1 405"))
        }
    }

    @Test fun closingRevokesTheUrlAndNotifiesOnlyOnce() {
        var notifications = 0
        val server = FeedbackServer(loopback, ByteArray(0), onClosed = { notifications++ })
        val address = URI(server.url)
        server.close()
        server.close()
        assertFalse(server.running)
        assertEquals(1, notifications)
        assertThrows(java.io.IOException::class.java) { request(address) }
    }

    @Test fun expirationClosesEvenAnIncompleteRequest() {
        val expired = CountDownLatch(1)
        val server = FeedbackServer(loopback, ByteArray(0), lifetimeMillis = 200, onClosed = { expired.countDown() })
        server.use {
            val address = URI(server.url)
            Socket(loopback, address.port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write("GET ".toByteArray())
                assertTrue(expired.await(3, TimeUnit.SECONDS))
                assertFalse(server.running)
                assertEquals(-1, socket.getInputStream().read())
            }
        }
    }

    @Test fun oversizedReportsAndPublicInterfacesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FeedbackServer(loopback, ByteArray(600 * 1024 + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FeedbackServer(InetAddress.getByName("203.0.113.1"), ByteArray(0))
        }
    }

    private fun request(address: URI, path: String = address.path, host: String = "${address.host}:${address.port}",
                        method: String = "GET"): String = Socket(loopback, address.port).use { socket ->
        socket.soTimeout = 3000
        socket.getOutputStream().write("$method $path HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray())
        String(socket.getInputStream().readBytes(), Charsets.UTF_8)
    }
}
