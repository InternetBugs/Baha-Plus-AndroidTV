package app.bahaplus.tv.data

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.bahaplus.tv.core.OfficialSite
import app.bahaplus.tv.core.OfficialScriptPolicy
import app.bahaplus.tv.core.Preferences
import app.bahaplus.tv.core.WebViewCompatibility
import app.bahaplus.tv.diagnostics.Diagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A single official session. Messages are origin/frame/document/request scoped.
 * Older WebViews use a top-frame evaluateJavascript queue, never an unscoped
 * addJavascriptInterface shared with third-party login/ad frames.
 */
class OfficialPortal(
    val webView: WebView,
    private val preferences: Preferences,
    private val onEvent: (JSONObject) -> Unit,
    private val onVisible: (Boolean, String) -> Unit,
    private val onRendererGone: () -> Unit
) {
    enum class Purpose { HOME, LOGIN, QR_LOGIN, PLAY }
    private val handler = Handler(Looper.getMainLooper())
    private val assets = mutableMapOf<String, String>()
    private val requests = linkedMapOf<String, Boolean>()
    private val completed = mutableSetOf<String>()
    private var document = ""
    private var destroyed = false
    private var ready = false
    private var foreground = true
    private var hasMessageListener = false
    private var waitingHome: (() -> Unit)? = null
    @Volatile private var scriptCompatibility: OfficialScriptCompatibility? = null
    private var qrRequest = ""
    private var qrAuthorized = false
    var purpose = Purpose.HOME
        private set
    var animeId = ""
        private set
    var videoId = ""
        private set
    private var preparing = false
    var nativeActive = false
        private set

    @SuppressLint("SetJavaScriptEnabled")
    fun initialize() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            // The official desktop player exposes the same m3u8 policy/plugins
            // used by the reference app. The actual engine stays system WebView.
            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        }
        webView.setBackgroundColor(android.graphics.Color.BLACK)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "baha", setOf(OfficialSite.ORIGIN)) {
                    _, message, origin, mainFrame, _ ->
                if (mainFrame && origin.toString().trimEnd('/') == OfficialSite.ORIGIN) {
                    runCatching { message.data?.let(::receive) }.onFailure { emit("data-error") }
                }
            }
            hasMessageListener = true
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (purpose == Purpose.PLAY &&
                    message.messageLevel() == ConsoleMessage.MessageLevel.ERROR &&
                    WebViewCompatibility.officialPlayerSyntaxError(message.message(), message.sourceId())) {
                    emit("webview-script-incompatible")
                }
                return false
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                if (request.origin.toString().trimEnd('/') == OfficialSite.ORIGIN &&
                    OfficialSite.trusted(webView.url.orEmpty()) &&
                    request.resources.contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                } else request.deny()
            }
            override fun onCreateWindow(view: WebView?, dialog: Boolean, gesture: Boolean, result: android.os.Message?) = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                scriptCompatibility?.intercept(request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Official verification and advertising can embed third-party
                // frames. They never gain access to our main-frame bridge.
                if (!request.isForMainFrame) {
                    return request.url.scheme != "https" && request.url.toString() != "about:blank"
                }
                if (!OfficialSite.allowedNavigation(request.url.toString())) return true
                // Native playback owns episode transitions. An official ended
                // callback must not race our choice or bypass auto-next off.
                return purpose == Purpose.PLAY && request.isForMainFrame &&
                    request.url.host == "ani.gamer.com.tw" && request.url.path == "/animeVideo.php" &&
                    request.url.getQueryParameter("sn") != videoId
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                Diagnostics.record("web.started", "purpose" to purpose.name)
                ready = false
                handler.removeCallbacks(poll)
                document = UUID.randomUUID().toString()
                requests.clear()
                completed.clear()
                preparing = false
                if (nativeActive) {
                    nativeActive = false
                    emit("authorization-lost")
                }
                if (!OfficialSite.allowedNavigation(url)) {
                    view.stopLoading()
                    emit("navigation-error")
                }
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (destroyed || url != view.url) return
                Diagnostics.record("web.finished", "purpose" to purpose.name)
                if (!OfficialSite.trusted(url)) {
                    if (purpose == Purpose.LOGIN) onVisible(true, "login")
                    return
                }
                ready = true
                evaluate("window.__bahaDocumentNonce=${quote(document)};window.__bahaTVMessages=[];")
                evaluate(script("tv-navigation"))
                when (purpose) {
                    Purpose.HOME -> {
                        run(script("catalog").replace("%KIND%", "catalog"))
                        syncAccount()
                        waitingHome?.also { waitingHome = null; it() }
                    }
                    Purpose.LOGIN -> run(script("account-sync").replace("__BAHA_WATCH_ACCOUNT__", "true"), sticky = true)
                    Purpose.QR_LOGIN -> {
                        if (qrAuthorized) {
                            run(script("account-sync").replace("__BAHA_WATCH_ACCOUNT__", "false"))
                        } else refreshQrLogin()
                    }
                    Purpose.PLAY -> installPlayer(url)
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    Diagnostics.record("web.error", "webError" to error.errorCode, "purpose" to purpose.name)
                    emit("navigation-error")
                }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) Diagnostics.record("web.http-error",
                    "httpStatus" to response.statusCode, "purpose" to purpose.name)
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                destroyed = true
                scriptCompatibility?.close()
                handler.removeCallbacksAndMessages(null)
                requests.clear()
                onRendererGone()
                return true
            }
        }
        // Probe the actual engine before loading any official document. A modern
        // provider takes the unchanged loader path and never creates a compiler.
        val major = OfficialScriptPolicy.major(
            WebViewCompat.getCurrentWebViewPackage(webView.context)?.versionName)
        webView.evaluateJavascript("""
            (function(){try {
              new Function('class A { #f() { return 1; } get #v() { return this.#f(); } read() { return this.#v; } } var a; a ??= 1; a &&= 2; a ||= 3;');
              return true;
            } catch(e) { return false; }})()
        """.trimIndent()) { supported ->
            if (destroyed) return@evaluateJavascript
            if (OfficialScriptPolicy.needsTranslation(supported == "true")) {
                scriptCompatibility = OfficialScriptCompatibility(webView.context.applicationContext,
                    major.takeIf { it > 0 } ?: 74)
            }
            Diagnostics.record("web.script-compat", "phase" to if (scriptCompatibility == null) "direct" else "enabled")
            home()
        }
    }

    private fun schedulePoll() {
        handler.removeCallbacks(poll)
        if (!destroyed && !hasMessageListener && foreground && ready &&
            !nativeActive && requests.isNotEmpty()) {
            handler.postDelayed(poll, 750)
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (!hasMessageListener && foreground && ready && !nativeActive &&
                requests.isNotEmpty() && OfficialSite.trusted(webView.url.orEmpty())) {
                val expected = document
                evaluate("JSON.stringify({document:window.__bahaDocumentNonce,origin:location.origin,messages:(window.__bahaTVMessages||[]).splice(0,64)})") { raw ->
                    val result = decodeString(raw)
                    if (expected == document && result != null) runCatching {
                        val data = JSONObject(result)
                        if (data.optString("document") == document && data.optString("origin") == OfficialSite.ORIGIN) {
                            val messages = data.getJSONArray("messages")
                            for (index in 0 until messages.length()) receive(messages.getJSONObject(index).toString())
                        }
                    }
                    schedulePoll()
                }
            } else schedulePoll()
        }
    }

    private fun installPlayer(url: String) {
        val actualVideo = Uri.parse(url).getQueryParameter("sn")
        if (Uri.parse(url).path != "/animeVideo.php" || actualVideo != videoId) {
            emit("navigation-error")
            return
        }
        evaluate("""
            window.__bahaNativeBackend='android-exoplayer';
            window.__bahaMediaSuspended=${!foreground};
            window.__bahaDanmakuPreferences={enabled:false,rolling:false,top:false,bottom:false,opacity:1,scale:1};
            window.__bahaPreferredQuality=${preferences.quality};
            ${script("age-confirmation")}
            ${script("ad-skip")}
            ${script("native-hls")}
            ${script("native-playback")}
        """.trimIndent())
        run(script("player").replace("%ANIME_ID%", animeId), sticky = true)
    }

    fun home() {
        if (destroyed) return
        cancelQrLogin()
        purpose = Purpose.HOME
        animeId = ""
        videoId = ""
        nativeActive = false
        ready = false
        webView.loadUrl(OfficialSite.HOME)
    }
    fun login() {
        Diagnostics.record("account.login")
        cancelQrLogin()
        purpose = Purpose.LOGIN
        ready = false
        onVisible(true, "login")
        webView.loadUrl(OfficialSite.ACCOUNT)
    }
    fun qrLogin() {
        Diagnostics.record("account.login")
        if (destroyed) return
        cancelQrLogin()
        purpose = Purpose.QR_LOGIN
        ready = false
        webView.loadUrl(OfficialSite.HOME)
    }
    fun refreshQrLogin() {
        if (destroyed || purpose != Purpose.QR_LOGIN) return
        if (!ready || !OfficialSite.trusted(webView.url.orEmpty()) || qrAuthorized) {
            qrLogin()
            return
        }
        cancelQrLogin()
        evaluate("window.__bahaMediaSuspended=${!foreground};")
        qrRequest = run(script("qr-login"), sticky = true)
    }
    fun completeQrLogin() {
        if (destroyed || purpose != Purpose.QR_LOGIN || qrAuthorized) return
        cancelQrLogin()
        qrAuthorized = true
        CookieManager.getInstance().flush()
        ready = false
        // Read a fresh official account page before the native UI accepts login.
        webView.loadUrl(OfficialSite.HOME)
    }
    private fun cancelQrLogin() {
        if (qrRequest.isNotEmpty()) {
            requests.remove(qrRequest)
            completed.remove(qrRequest)
            qrRequest = ""
            evaluate("if(window.__bahaQrLogin)window.__bahaQrLogin.dispose();")
        }
        qrAuthorized = false
    }
    fun play(series: String, video: String) {
        require(OfficialSite.validId(series) && OfficialSite.validId(video))
        cancelQrLogin()
        purpose = Purpose.PLAY
        animeId = series
        videoId = video
        nativeActive = false
        preparing = false
        onVisible(true, "play")
        webView.loadUrl(OfficialSite.video(video))
    }
    fun finishLogin() {
        CookieManager.getInstance().flush()
        onVisible(false, "")
        home()
    }
    fun syncAccount() {
        if (ready && purpose in setOf(Purpose.HOME, Purpose.LOGIN)) {
            run(script("account-sync").replace("__BAHA_WATCH_ACCOUNT__", (purpose == Purpose.LOGIN).toString()),
                sticky = purpose == Purpose.LOGIN)
        }
    }
    fun whenHome(action: () -> Unit) {
        if (ready && purpose == Purpose.HOME && OfficialSite.trusted(webView.url.orEmpty())) action()
        else {
            waitingHome = action
            if (purpose != Purpose.HOME || !ready) home()
        }
    }
    fun detail(id: String): String {
        require(OfficialSite.validId(id))
        return run(script("detail").replace("%ANIME_ID%", id))
    }
    fun search(query: String): String {
        val input = query.trim().codePoints().limit(100).toArray().let { String(it, 0, it.size) }
        val queries = JSONArray(listOf(input, preferences.toTraditional(input)).distinct())
        return run("${script("tv-commands")}\nwindow.__bahaTVCommands.search($queries);")
    }
    fun favorite(id: String, desired: Boolean): String {
        require(OfficialSite.validId(id))
        return run("${script("tv-commands")}\nwindow.__bahaTVCommands.favorite(${quote(id)},$desired);")
    }
    fun prepareNative() {
        if (preparing || nativeActive || purpose != Purpose.PLAY) return
        preparing = true
        run("""
            (async function(){
              try {
                const bridge=window.__bahaPlayerBridge;
                const value=bridge?await bridge.prepareNative():null;
                bahaPostJSON(JSON.stringify({type:'native-source',id:${quote(animeId)},videoId:${quote(videoId)},
                  source:value?{...value,userAgent:navigator.userAgent}:null}));
              } catch(e) { bahaPostJSON(JSON.stringify({type:'native-source',source:null})); }
            })();
        """.trimIndent())
    }
    fun confirmAge(callback: (Boolean) -> Unit) = playerPrompt("confirmAge", callback)
    fun skipAd(callback: (Boolean) -> Unit) = playerPrompt("skipAd", callback)
    private fun playerPrompt(action: String, callback: (Boolean) -> Unit) {
        if (destroyed || !ready || !foreground || purpose != Purpose.PLAY || nativeActive ||
            !OfficialSite.trusted(webView.url.orEmpty())) {
            callback(false)
            return
        }
        val expected = document
        evaluate("!!window.__bahaPlayerBridge&&window.__bahaPlayerBridge.$action(${quote(expected)})===true") {
            callback(expected == document && it == "true")
        }
    }
    fun commitNative(callback: (JSONObject?) -> Unit) {
        val expected = document
        evaluate("JSON.stringify(window.__bahaPlayerBridge&&window.__bahaPlayerBridge.commitNative()||null)") { raw ->
            val result = if (expected == document) decodeString(raw) else null
            val body = result?.let { runCatching { JSONObject(it) }.getOrNull() }
            nativeActive = body != null
            schedulePoll()
            callback(body)
        }
    }
    fun clock(value: JSONObject, callback: (Boolean) -> Unit = {}) {
        val expected = document
        val code = """
            (function(){
              if(window.__bahaDocumentNonce!==${quote(expected)})return false;
              return !!window.__bahaPlayerBridge && window.__bahaPlayerBridge.nativeStatus()==='ok' &&
                window.__bahaPlayerBridge.syncNative($value)===true;
            })()
        """.trimIndent()
        evaluate(code) { callback(expected == document && it == "true") }
    }
    fun stopPlayback(after: () -> Unit) {
        nativeActive = false
        preparing = false
        evaluate(script("stop-playback")) {
            onVisible(false, "")
            after()
        }
    }
    fun failNative() { evaluate("if(window.__bahaPlayerBridge)window.__bahaPlayerBridge.failNative();") }
    fun reload() {
        if (destroyed) return
        if (purpose == Purpose.PLAY) onVisible(true, "play")
        webView.reload()
    }
    fun logout(after: () -> Unit) {
        Diagnostics.record("account.logout")
        // Only one account is supported. Clear its official browser session.
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            android.webkit.WebStorage.getInstance().deleteAllData()
            after()
            home()
        }
    }
    fun foreground(active: Boolean) {
        foreground = active
        if (destroyed) return
        if (active) webView.onResume() else webView.onPause()
        evaluate("window.__bahaMediaSuspended=${!active};if(window.__bahaPlayerBridge)window.__bahaPlayerBridge.${if (active) "resume" else "suspend"}();")
        if (purpose == Purpose.QR_LOGIN) evaluate("if(window.__bahaQrLogin)window.__bahaQrLogin.${if (active) "resume" else "pause"}();")
        schedulePoll()
    }
    fun destroy() {
        if (destroyed) {
            runCatching { webView.destroy() }
            return
        }
        destroyed = true
        scriptCompatibility?.close()
        handler.removeCallbacksAndMessages(null)
        requests.clear()
        webView.stopLoading()
        webView.destroy()
    }

    private fun run(source: String, sticky: Boolean = false): String {
        val operation = UUID.randomUUID().toString()
        requests[operation] = sticky
        while (requests.size > 32) requests.remove(requests.keys.first())
        val nonce = document
        val wrapped = """
            (function(){
              const ticket=${quote(nonce)},requestId=${quote(operation)};
              if(location.origin!=='https://ani.gamer.com.tw'||window.__bahaDocumentNonce!==ticket)return;
              function bahaPostJSON(raw){
                if(location.origin!=='https://ani.gamer.com.tw'||window.__bahaDocumentNonce!==ticket)return;
                const body=JSON.parse(raw);body.document=ticket;body.requestId=requestId;
                if(window.baha&&window.baha.postMessage)window.baha.postMessage(JSON.stringify(body));
                else {const queue=window.__bahaTVMessages||(window.__bahaTVMessages=[]);if(queue.length<128)queue.push(body);}
              }
              if(document.querySelector('#challenge-form,#challenge-running,#cf-challenge-running')||
                /^(Just a moment|Checking your browser)/i.test(document.title)){
                bahaPostJSON(JSON.stringify({type:'verification-required'}));return;
              }
              try {$source} catch(e){bahaPostJSON(JSON.stringify({type:'operation-error'}));}
            })();true;
        """.trimIndent()
        evaluate(wrapped)
        schedulePoll()
        if (!sticky) handler.postDelayed({
            if (!destroyed && nonce == document && requests.containsKey(operation) && !completed.contains(operation)) {
                requests.remove(operation)
                onEvent(JSONObject().put("type", "operation-error").put("requestId", operation))
            }
        }, 30000)
        return operation
    }
    private fun receive(raw: String) {
        if (destroyed || raw.length > 2_000_000 || !OfficialSite.trusted(webView.url.orEmpty())) return
        runCatching {
            val body = JSONObject(raw)
            val operation = body.optString("requestId")
            if (body.optString("document") != document || !requests.containsKey(operation)) return
            val type = body.optString("type")
            if (type.startsWith("qr-login-") && (purpose != Purpose.QR_LOGIN || operation != qrRequest)) return
            if (purpose == Purpose.PLAY && type in setOf("player-state", "player-detail", "native-source")) {
                if (body.optString("id") != animeId || body.optString("videoId") != videoId) return
            }
            if (type != "account-authenticated" && type != "player-state" && type != "player-detail") {
                completed.add(operation)
                if (requests[operation] == false) requests.remove(operation)
            }
            if (type == "verification-required") onVisible(true, "verification")
            onEvent(body)
        }.onFailure { emit("data-error") }
    }
    private fun script(name: String) = assets.getOrPut(name) {
        webView.context.assets.open("adapters/$name.js").bufferedReader().use { it.readText() }
    }
    private fun evaluate(code: String, callback: ((String) -> Unit)? = null) {
        if (!destroyed) webView.evaluateJavascript(code) { callback?.invoke(it) }
    }
    private fun emit(type: String) { onEvent(JSONObject().put("type", type)) }
    companion object {
        private fun quote(value: String) = JSONObject.quote(value)
        fun decodeString(raw: String): String? = runCatching {
            JSONArray("[$raw]").opt(0) as? String
        }.getOrNull()
    }
}
