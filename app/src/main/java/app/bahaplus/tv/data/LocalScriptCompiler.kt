package app.bahaplus.tv.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.bahaplus.tv.core.OfficialScriptPolicy
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A lazy compiler with no access to remote pages, cookies, DOM or network.
 * Its Java interface exists only on a local asset page, never on OfficialPortal.
 * Calls originate on shouldInterceptRequest's background thread.
 */
internal class LocalScriptCompiler(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var closed = false
    @Volatile private var pending: Job? = null
    private var engine: WebView? = null // main thread only
    private var startup: CountDownLatch? = null // main thread only
    private var initialized = false // main thread only
    private val release = Runnable { releaseEngine() }
    private val polyfills by lazy {
        context.assets.open("compat/polyfills.js").bufferedReader().use { it.readText() }
    }

    private class Job(val source: String) {
        val id = UUID.randomUUID().toString()
        val loaded = CountDownLatch(1)
        val done = CountDownLatch(1)
        val output = StringBuilder()
        @Volatile var success = false
        @Volatile var available = false
    }

    fun transform(source: String, major: Int): String? = synchronized(lock) {
        check(Looper.myLooper() != Looper.getMainLooper())
        if (closed) return@synchronized null
        val job = Job(source)
        pending = job
        main.post {
            main.removeCallbacks(release)
            if (closed) job.loaded.countDown()
            else if (engine != null && initialized) {
                job.available = true
                job.loaded.countDown()
            } else if (engine == null) createEngine(job)
        }
        try {
            if (!job.loaded.await(30, TimeUnit.SECONDS) || !job.available || closed) return@synchronized null
            main.post {
                if (!closed && pending === job) {
                    engine?.evaluateJavascript("bahaCompile(${JSONObject.quote(job.id)},$major);", null)
                }
            }
            if (job.done.await(60, TimeUnit.SECONDS) && job.success && !closed) {
                synchronized(job) { job.output.toString() }
            } else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            pending = null
            // Release Babel's AST/heap and its renderer after the initial page's
            // adjacent scripts have been compiled. Cached episodes need none.
            main.post {
                main.removeCallbacks(release)
                if (!job.success) releaseEngine()
                else main.postDelayed(release, 10_000)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createEngine(job: Job) {
        startup = job.loaded
        initialized = false
        runCatching {
            val view = WebView(context)
            engine = view
            with(view.settings) {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                domStorageEnabled = false
                blockNetworkLoads = true
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            view.addJavascriptInterface(Bridge(), "compiler")
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val uri = request.url
                    val asset = if (request.method == "GET" && uri.scheme == "https" &&
                        uri.host == "baha-compiler.invalid" && uri.port == -1 &&
                        uri.query == null && uri.fragment == null) {
                        when (uri.path) {
                            "/index.html" -> "compat/index.html"
                            "/babel.min.js" -> "compat/babel.min.js"
                            "/compiler.js" -> "compat/compiler.js"
                            else -> null
                        }
                    } else null
                    if (asset != null) {
                        val mime = if (asset.endsWith(".html")) "text/html" else "application/javascript"
                        return WebResourceResponse(mime, "UTF-8", context.assets.open(asset))
                    }
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
                        emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    pending?.done?.countDown()
                    releaseEngine()
                    return true
                }
            }
            view.loadUrl("https://baha-compiler.invalid/index.html")
        }.onFailure {
            job.loaded.countDown()
            releaseEngine()
        }
    }

    private inner class Bridge {
        @JavascriptInterface fun polyfills(): String = this@LocalScriptCompiler.polyfills

        @JavascriptInterface fun ready(ok: Boolean) {
            main.post {
                initialized = ok
                pending?.available = ok
                startup?.countDown()
                startup = null
            }
        }

        @JavascriptInterface fun input(id: String, index: Int): String {
            val job = pending ?: return ""
            if (job.id != id || index < 0 || index > job.source.length / 32768) return ""
            val start = index * 32768
            return job.source.substring(start, minOf(start + 32768, job.source.length))
        }

        @JavascriptInterface fun output(id: String, part: String) {
            val job = pending ?: return
            if (job.id != id) return
            synchronized(job) {
                check(part.length <= 32768 &&
                    job.output.length + part.length <= OfficialScriptPolicy.MAX_OUTPUT_BYTES)
                job.output.append(part)
            }
        }

        @JavascriptInterface fun finish(id: String, success: Boolean) {
            val job = pending ?: return
            if (job.id != id) return
            job.success = success && job.output.isNotEmpty()
            job.done.countDown()
        }
    }

    private fun releaseEngine() {
        initialized = false
        startup?.countDown()
        startup = null
        engine?.apply {
            removeJavascriptInterface("compiler")
            stopLoading()
            destroy()
        }
        engine = null
    }

    fun close() {
        closed = true
        pending?.loaded?.countDown()
        pending?.done?.countDown()
        main.post {
            main.removeCallbacks(release)
            releaseEngine()
        }
    }
}
