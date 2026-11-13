package app.bahaplus.tv.data

import app.bahaplus.tv.core.AppUpdates
import app.bahaplus.tv.core.UpdateCheckResult
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal class GitHubReleaseClient(
    private val openConnection: () -> HttpURLConnection = {
        URL(AppUpdates.latestReleaseApi).openConnection() as HttpURLConnection
    }
) {
    @Volatile private var closed = false
    @Volatile private var connection: HttpURLConnection? = null

    fun check(currentVersion: String): UpdateCheckResult = runCatching {
        check(!closed)
        val request = openConnection()
        connection = request
        try {
            check(!closed)
            request.connectTimeout = 8000
            request.readTimeout = 8000
            request.instanceFollowRedirects = false
            request.useCaches = false
            request.setRequestProperty("Accept", "application/vnd.github+json")
            request.setRequestProperty("User-Agent", "BahaPlusTV/$currentVersion")
            request.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            when (request.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> UpdateCheckResult.UpToDate
                HttpURLConnection.HTTP_OK -> {
                    val limit = 1024 * 1024
                    require(request.contentLength <= limit)
                    val json = request.inputStream.use { stream ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            check(!closed && !Thread.currentThread().isInterrupted)
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= limit)
                            output.write(buffer, 0, count)
                        }
                        output.toString("UTF-8")
                    }
                    AppUpdates.evaluate(currentVersion, JSONObject(json))
                }
                else -> UpdateCheckResult.Failed
            }
        } finally {
            request.disconnect()
            connection = null
        }
    }.getOrDefault(UpdateCheckResult.Failed)

    fun close() {
        closed = true
        connection?.disconnect()
    }
}
