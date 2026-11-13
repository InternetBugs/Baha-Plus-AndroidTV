package app.bahaplus.tv.data

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.bahaplus.tv.core.OfficialScriptPolicy
import app.bahaplus.tv.core.OfficialSite
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Official QR authorization session. Destroyed when the native login UI closes. */
class OfficialPortal(
    val webView: WebView,
    private val onEvent: (JSONObject) -> Unit,
    private val onRendererGone: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val requests = mutableSetOf<String>()
    private var document = ""
    private var destroyed = false
    private var ready = false
    private var foreground = true
    private var hasMessageListener = false
    private var qrRequest = ""
    @Volatile private var scriptCompatibility: OfficialScriptCompatibility? = null
    @SuppressLint("SetJavaScriptEnabled")
    fun initialize(onReady: () -> Unit) {
        var bootstrapping = true
        var initialized = false
        val initializationTimeout = Runnable {
            if (!destroyed && !initialized) onRendererGone()
        }
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = OfficialHttpSession.AGENT
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "baha", setOf(OfficialSite.ORIGIN)) {
                    _, message, origin, mainFrame, _ ->
                if (mainFrame && origin.toString().trimEnd('/') == OfficialSite.ORIGIN) {
                    message.data?.let(::receive)
                }
            }
            hasMessageListener = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                scriptCompatibility?.intercept(request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                if (bootstrapping && request.url.toString() == "about:blank") false
                else if (request.isForMainFrame) !OfficialSite.allowedNavigation(request.url.toString())
                else request.url.scheme != "https" && request.url.toString() != "about:blank"

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                ready = false
                document = UUID.randomUUID().toString()
                requests.clear()
                handler.removeCallbacks(poll)
                if (bootstrapping && url == "about:blank") return
                if (!OfficialSite.allowedNavigation(url)) {
                    view.stopLoading()
                    emit("navigation-error")
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (destroyed) return
                if (bootstrapping && url == "about:blank") {
                    bootstrapping = false
                    probeSyntax {
                        initialized = true
                        handler.removeCallbacks(initializationTimeout)
                        onReady()
                    }
                    return
                }
                if (url != view.url) return
                if (!OfficialSite.trusted(url)) { emit("navigation-error"); return }
                ready = true
                evaluate("window.__bahaDocumentNonce=${quote(document)};window.__bahaTVMessages=[];")
                refreshQrLogin()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) emit("navigation-error")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                onRendererGone()
                return true
            }
        }
        // A newly constructed WebView has no guaranteed script context. Create
        // a local blank document before evaluating capabilities, then load login.
        handler.postDelayed(initializationTimeout, 15_000)
        webView.loadUrl("about:blank")
    }

    private fun probeSyntax(onReady: () -> Unit) {
        val major = OfficialScriptPolicy.major(WebViewCompat.getCurrentWebViewPackage(webView.context)?.versionName)
        webView.evaluateJavascript("""
            (function(){try {
              new Function('class A { #f() { return 1; } get #v() { return this.#f(); } read() { return this.#v; } } var a; a ??= 1; a &&= 2; a ||= 3;');
              return true;
            } catch(e) { return false; }})()
        """.trimIndent()) { supported ->
            if (!destroyed) {
                if (OfficialScriptPolicy.needsTranslation(supported == "true")) {
                    scriptCompatibility = OfficialScriptCompatibility(webView.context.applicationContext,
                        major.takeIf { it > 0 } ?: 74)
                }
                onReady()
            }
        }
    }

    fun qrLogin() {
        if (destroyed) return
        cancelQrLogin()
        ready = false
        webView.loadUrl(OfficialSite.HOME)
    }

    fun refreshQrLogin() {
        if (destroyed) return
        if (!ready || !OfficialSite.trusted(webView.url.orEmpty())) { qrLogin(); return }
        cancelQrLogin()
        evaluate("window.__bahaMediaSuspended=${!foreground};")
        qrRequest = run(script("qr-login"))
    }

    fun completeQrLogin() {
        if (destroyed) return
        cancelQrLogin()
        CookieManager.getInstance().flush()
        emit("login-complete")
    }

    private fun cancelQrLogin() {
        requests.remove(qrRequest)
        qrRequest = ""
        evaluate("if(window.__bahaQrLogin)window.__bahaQrLogin.dispose();")
    }

    fun logout(after: () -> Unit) = Companion.logout(after)
    fun foreground(active: Boolean) {
        foreground = active
        if (destroyed) return
        if (active) webView.onResume() else webView.onPause()
        evaluate("window.__bahaMediaSuspended=${!active};if(window.__bahaQrLogin)window.__bahaQrLogin.${if (active) "resume" else "pause"}();")
        schedulePoll()
    }

    fun destroy() {
        if (destroyed) return
        cancelQrLogin()
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        scriptCompatibility?.close()
        requests.clear()
        webView.stopLoading()
        webView.destroy()
    }

    private fun schedulePoll() {
        handler.removeCallbacks(poll)
        if (!destroyed && !hasMessageListener && foreground && ready && requests.isNotEmpty()) {
            handler.postDelayed(poll, 750)
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!destroyed && !hasMessageListener && foreground && ready &&
                OfficialSite.trusted(webView.url.orEmpty())) {
                val expected = document
                evaluate("JSON.stringify({document:window.__bahaDocumentNonce,origin:location.origin,messages:(window.__bahaTVMessages||[]).splice(0,64)})") { raw ->
                    if (expected == document) runCatching {
                        val body = JSONObject(decodeString(raw) ?: return@runCatching)
                        if (body.optString("document") == document && body.optString("origin") == OfficialSite.ORIGIN) {
                            val messages = body.getJSONArray("messages")
                            for (index in 0 until messages.length()) receive(messages.getJSONObject(index).toString())
                        }
                    }
                    schedulePoll()
                }
            }
        }
    }

    private fun run(source: String): String {
        val request = UUID.randomUUID().toString()
        requests.add(request)
        val wrapped = """
            (function(){
              const ticket=${quote(document)},requestId=${quote(request)};
              if(location.origin!=='https://ani.gamer.com.tw'||window.__bahaDocumentNonce!==ticket)return;
              function bahaPostJSON(raw){
                if(location.origin!=='https://ani.gamer.com.tw'||window.__bahaDocumentNonce!==ticket)return;
                const body=JSON.parse(raw);body.document=ticket;body.requestId=requestId;
                if(window.baha&&window.baha.postMessage)window.baha.postMessage(JSON.stringify(body));
                else {const queue=window.__bahaTVMessages||(window.__bahaTVMessages=[]);if(queue.length<128)queue.push(body);}
              }
              try {$source} catch(e){bahaPostJSON(JSON.stringify({type:'operation-error'}));}
            })();true;
        """.trimIndent()
        evaluate(wrapped)
        schedulePoll()
        return request
    }

    private fun receive(raw: String) {
        if (destroyed || raw.length > 16384 || !OfficialSite.trusted(webView.url.orEmpty())) return
        runCatching {
            val body = JSONObject(raw)
            val request = body.optString("requestId")
            if (body.optString("document") != document || request !in requests) return
            val type = body.optString("type")
            if (type.startsWith("qr-login-") && request != qrRequest) return
            if (type in EVENTS) onEvent(body)
        }
    }

    private fun script(name: String) = webView.context.assets.open("adapters/$name.js").bufferedReader().use { it.readText() }
    private fun evaluate(code: String, callback: ((String) -> Unit)? = null) {
        if (!destroyed) webView.evaluateJavascript(code) { if (!destroyed) callback?.invoke(it) }
    }
    private fun emit(type: String) { onEvent(JSONObject().put("type", type)) }

    companion object {
        private val EVENTS = setOf("qr-login-ready", "qr-login-retrying", "qr-login-expired",
            "qr-login-error", "qr-login-complete", "operation-error")
        private fun quote(value: String) = JSONObject.quote(value)
        fun decodeString(raw: String): String? = runCatching { JSONArray("[$raw]").opt(0) as? String }.getOrNull()
        fun logout(after: () -> Unit) {
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                android.webkit.WebStorage.getInstance().deleteAllData()
                after()
            }
        }
    }
}
