package app.bahaplus.tv.data

import app.bahaplus.tv.core.AppUpdates
import app.bahaplus.tv.core.UpdateCheckResult
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class GitHubReleaseClientTest {
    private val release = """{"tag_name":"v0.3.0","draft":false,"prerelease":false,"body":"更新说明"}"""

    private class Response(
        private val status: Int,
        private val payload: String = "",
        private val declaredLength: Int = payload.toByteArray(Charsets.UTF_8).size,
        private val error: IOException? = null
    ) : HttpURLConnection(URL(AppUpdates.latestReleaseApi)) {
        var disconnected = false
        var streamOpened = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode(): Int {
            error?.let { throw it }
            return status
        }
        override fun getContentLength() = declaredLength
        override fun getInputStream(): ByteArrayInputStream {
            streamOpened = true
            return ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8))
        }
    }

    @Test fun fetchesAndComparesAReleaseWithoutAccountCredentials() {
        val response = Response(200, release)
        val result = GitHubReleaseClient { response }.check("0.2.0")
        assertTrue(result is UpdateCheckResult.Available)
        assertEquals("application/vnd.github+json", response.getRequestProperty("Accept"))
        assertEquals("BahaPlusTV/0.2.0", response.getRequestProperty("User-Agent"))
        assertNull(response.getRequestProperty("Authorization"))
        assertNull(response.getRequestProperty("Cookie"))
        assertEquals(8000, response.connectTimeout)
        assertEquals(8000, response.readTimeout)
        assertFalse(response.instanceFollowRedirects)
        assertFalse(response.useCaches)
        assertTrue(response.disconnected)
    }

    @Test fun currentVersionAndNoPublishedReleaseReturnNoUpdates() {
        for (response in listOf(Response(200, release), Response(404))) {
            assertEquals(UpdateCheckResult.UpToDate, GitHubReleaseClient { response }.check("0.3.0"))
            assertTrue(response.disconnected)
        }
    }

    @Test fun rateLimitsServerErrorsAndRedirectsAreFailures() {
        for (status in listOf(301, 302, 401, 403, 429, 500, 503)) {
            val response = Response(status)
            assertEquals("HTTP $status", UpdateCheckResult.Failed, GitHubReleaseClient { response }.check("0.2.0"))
            assertFalse(response.streamOpened)
            assertTrue(response.disconnected)
        }
    }

    @Test fun malformedAndUnrecognizableResponsesAreFailures() {
        for (payload in listOf("", "<html>Gateway error</html>", "{}", """{"message":"error"}""",
            release.replace("v0.3.0", "unexpected"))) {
            val response = Response(200, payload)
            assertEquals(UpdateCheckResult.Failed, GitHubReleaseClient { response }.check("0.2.0"))
            assertTrue(response.disconnected)
        }
    }

    @Test fun networkFailuresAreRetryable() {
        val failed = Response(200, error = IOException("offline"))
        val successful = Response(200, release)
        val responses = ArrayDeque(listOf(failed, successful))
        val client = GitHubReleaseClient { responses.removeFirst() }
        assertEquals(UpdateCheckResult.Failed, client.check("0.2.0"))
        assertTrue(failed.disconnected)
        assertTrue(client.check("0.2.0") is UpdateCheckResult.Available)
        assertTrue(successful.disconnected)
    }

    @Test fun oversizedDeclaredOrStreamedResponsesAreRejected() {
        val declared = Response(200, release, declaredLength = 1024 * 1024 + 1)
        assertEquals(UpdateCheckResult.Failed, GitHubReleaseClient { declared }.check("0.2.0"))
        assertFalse(declared.streamOpened)
        val streamed = Response(200, "x".repeat(1024 * 1024 + 1), declaredLength = -1)
        assertEquals(UpdateCheckResult.Failed, GitHubReleaseClient { streamed }.check("0.2.0"))
        assertTrue(streamed.streamOpened)
        assertTrue(declared.disconnected && streamed.disconnected)
    }

    @Test fun closingPreventsNewConnections() {
        var opened = false
        val client = GitHubReleaseClient { opened = true; Response(200, release) }
        client.close()
        assertEquals(UpdateCheckResult.Failed, client.check("0.2.0"))
        assertFalse(opened)
    }
}
