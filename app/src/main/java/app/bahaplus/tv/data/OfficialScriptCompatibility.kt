package app.bahaplus.tv.data

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import app.bahaplus.tv.core.OfficialScriptPolicy
import app.bahaplus.tv.diagnostics.Diagnostics
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Source-to-source syntax translation. All official player logic is retained.
 * A failed fetch/compile returns null so WebView uses its normal loader and the
 * existing parser-error UI remains available. Modern engines never call this.
 */
internal class OfficialScriptCompatibility(context: Context, private val major: Int) {
    private val compiler = LocalScriptCompiler(context)
    private val directory = File(context.cacheDir, "official-script-compat")
    private val lock = Any()
    @Volatile private var closed = false

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (closed || request.method != "GET" || request.isForMainFrame ||
            !OfficialScriptPolicy.allowed(request.url.toString())) return null
        return runCatching {
            // No redirects or account/cookie headers. These are public files.
            val connection = URL(request.url.toString()).openConnection() as HttpsURLConnection
            val original = try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("Accept-Encoding", "identity")
                request.requestHeaders.entries.firstOrNull { it.key.equals("User-Agent", true) }
                    ?.let { connection.setRequestProperty("User-Agent", it.value) }
                check(connection.responseCode == 200)
                check(connection.contentType.orEmpty().substringBefore(';').trim().lowercase() in
                    setOf("application/javascript", "text/javascript", "application/x-javascript"))
                check(connection.contentEncoding.isNullOrEmpty() || connection.contentEncoding == "identity")
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    val limit = OfficialScriptPolicy.MAX_SOURCE_BYTES + 1
                    while (output.size() < limit) {
                        val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    val bytes = output.toByteArray()
                    check(bytes.isNotEmpty() && bytes.size <= OfficialScriptPolicy.MAX_SOURCE_BYTES)
                    bytes
                }
            } finally { connection.disconnect() }
            if (closed) return null
            val start = android.os.SystemClock.elapsedRealtime()
            val key = OfficialScriptPolicy.cacheKey(original, major)
            var cached = false
            val translated = synchronized(lock) {
                if (closed) return null
                val file = File(directory, "$key.js")
                if (file.isFile && file.length() in 1..OfficialScriptPolicy.MAX_OUTPUT_BYTES.toLong()) {
                    cached = true
                    file.readBytes()
                } else {
                    val result = compiler.transform(original.toString(Charsets.UTF_8), major) ?: return null
                    val bytes = result.toByteArray(Charsets.UTF_8)
                    check(bytes.size in 1..OfficialScriptPolicy.MAX_OUTPUT_BYTES)
                    if (!closed) runCatching {
                        check(directory.isDirectory || directory.mkdirs())
                        // Atomic replacement; interrupted writes cannot become cache hits.
                        val temp = File(directory, "$key.tmp")
                        temp.writeBytes(bytes)
                        check(temp.renameTo(file))
                        var retainedBytes = 0L
                        directory.listFiles()?.filter { it.isFile }
                            ?.sortedByDescending { it.lastModified() }?.forEachIndexed { index, entry ->
                                retainedBytes += entry.length()
                                if (index >= 64 || retainedBytes > 24 * 1024 * 1024) entry.delete()
                            }
                    }
                    bytes
                }
            }
            Diagnostics.record("web.script-compat", "phase" to if (cached) "cached" else "compiled",
                "elapsedMs" to android.os.SystemClock.elapsedRealtime() - start)
            WebResourceResponse("application/javascript", "UTF-8", 200, "OK",
                mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
                ByteArrayInputStream(translated))
        }.getOrElse {
            Diagnostics.record("web.script-compat", "phase" to "failed")
            null
        }
    }

    fun close() {
        closed = true
        compiler.close()
    }
}
