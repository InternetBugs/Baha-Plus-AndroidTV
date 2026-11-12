package app.bahaplus.tv.data

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.bahaplus.tv.core.OfficialSite
import app.bahaplus.tv.core.Preferences
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
    enum class Purpose { HOME, LOGIN, PLAY }
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
                ready = false
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
                    Purpose.PLAY -> installPlayer(url)
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) emit("navigation-error")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                destroyed = true
                handler.removeCallbacksAndMessages(null)
                requests.clear()
                onRendererGone()
                return true
            }
        }
        handler.post(poll)
        home()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (!hasMessageListener && foreground && ready && OfficialSite.trusted(webView.url.orEmpty())) {
                val expected = document
                evaluate("JSON.stringify({document:window.__bahaDocumentNonce,origin:location.origin,messages:(window.__bahaTVMessages||[]).splice(0,64)})") { raw ->
                    if (expected != document) return@evaluate
                    val result = decodeString(raw) ?: return@evaluate
                    runCatching {
                        val data = JSONObject(result)
                        if (data.optString("document") == document && data.optString("origin") == OfficialSite.ORIGIN) {
                            val messages = data.getJSONArray("messages")
                            for (index in 0 until messages.length()) receive(messages.getJSONObject(index).toString())
                        }
                    }
                }
            }
            handler.postDelayed(this, 750)
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
            ${script("native-hls")}
            ${script("native-playback")}
        """.trimIndent())
        run(script("player").replace("%ANIME_ID%", animeId), sticky = true)
    }

    fun home() {
        if (destroyed) return
        purpose = Purpose.HOME
        animeId = ""
        videoId = ""
        nativeActive = false
        ready = false
        webView.loadUrl(OfficialSite.HOME)
    }
    fun login() {
        purpose = Purpose.LOGIN
        ready = false
        onVisible(true, "login")
        webView.loadUrl(OfficialSite.ACCOUNT)
    }
    fun play(series: String, video: String) {
        require(OfficialSite.validId(series) && OfficialSite.validId(video))
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
        if (ready && purpose != Purpose.PLAY) {
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
                const value=await window.__bahaPlayerBridge?.prepareNative();
                bahaPostJSON(JSON.stringify({type:'native-source',id:${quote(animeId)},videoId:${quote(videoId)},
                  source:value?{...value,userAgent:navigator.userAgent}:null}));
              } catch(e) { bahaPostJSON(JSON.stringify({type:'native-source',source:null})); }
            })();
        """.trimIndent())
    }
    fun commitNative(callback: (JSONObject?) -> Unit) {
        val expected = document
        evaluate("JSON.stringify(window.__bahaPlayerBridge?.commitNative()||null)") { raw ->
            val result = if (expected == document) decodeString(raw) else null
            val body = result?.let { runCatching { JSONObject(it) }.getOrNull() }
            nativeActive = body != null
            callback(body)
        }
    }
    fun clock(value: JSONObject, callback: (Boolean) -> Unit = {}) {
        val expected = document
        val code = """
            (function(){
              if(window.__bahaDocumentNonce!==${quote(expected)})return false;
              return window.__bahaPlayerBridge?.nativeStatus()==='ok' &&
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
    fun failNative() { evaluate("window.__bahaPlayerBridge?.failNative();") }
    fun reload() { if (!destroyed) webView.reload() }
    fun logout(after: () -> Unit) {
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
        evaluate("window.__bahaMediaSuspended=${!active};window.__bahaPlayerBridge?.${if (active) "resume" else "suspend"}();")
    }
    fun destroy() {
        if (destroyed) {
            runCatching { webView.destroy() }
            return
        }
        destroyed = true
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
                if(window.baha?.postMessage)window.baha.postMessage(JSON.stringify(body));
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
