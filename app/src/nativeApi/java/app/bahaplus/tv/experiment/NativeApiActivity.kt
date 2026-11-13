package app.bahaplus.tv.experiment

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.ceil

/** Separate guest-only experiment. Deliberately creates no WebView or browser bridge. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeApiActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var player: ExoPlayer
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private lateinit var timeline: TextView
    private lateinit var eventsView: TextView
    private lateinit var input: EditText
    private lateinit var load: Button
    private lateinit var toggle: Button
    private lateinit var skip: Button
    private lateinit var seek: Button
    private lateinit var speed: Button
    private var client: NativeApiClient? = null
    private var preparation: NativeApiClient.Preparation? = null
    private var phase = "idle"
    private var generation = 0
    private var foreground = false
    private var destroyed = false
    private var started = false
    private var adStartRequested = false
    private var adStartAcknowledged = false
    private var adFinishing = false
    private var progressPending = false
    private var nextProgress = 60.0
    private var guestProgress = false
    private var progressAccepted = 0
    private var mediaWidth = 0
    private var mediaHeight = 0
    private var watch = WatchClock()
    private var adWatch = WatchClock()
    private val events = ArrayDeque<JSONObject>()
    private var dialog: AlertDialog? = null
    private var lastEvidence = 0L
    private val ticker = object : Runnable {
        override fun run() {
            if (destroyed) return
            val now = SystemClock.elapsedRealtime()
            val position = player.currentPosition.coerceAtLeast(0) / 1000.0
            if (phase == "advertisement") {
                adWatch.sample(position, foreground && player.isPlaying, false, now)
                skip.isEnabled = adStartAcknowledged && adWatch.total >= 30 && !adFinishing
                skip.text = if (skip.isEnabled) "跳过广告" else "广告 ${ceil((30 - adWatch.total).coerceAtLeast(0.0)).toInt()} 秒"
            } else if (phase == "content") {
                watch.sample(position, foreground && player.isPlaying, false, now,
                    player.playbackParameters.speed.toDouble())
                if (watch.total >= nextProgress && !progressPending) {
                    nextProgress += 60
                    sendProgress()
                }
            }
            timeline.text = "${time(player.currentPosition)} / ${time(player.duration)} · " +
                "实际观看 ${watch.total.toInt()} 秒 · HTTP 进度确认 $progressAccepted" +
                if (guestProgress) " · 访客，无账号同步" else ""
            if (now - lastEvidence >= 1000) { evidence(); lastEvidence = now }
            main.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 12)
            setBackgroundColor(Color.rgb(12, 20, 30))
        }
        setContentView(panel)
        label("官方播放 API 实验 · 独立访客会话", 20f)
        val row = LinearLayout(this)
        input = EditText(this).apply {
            setText(intent.getStringExtra("video_id")?.takeIf { it.matches(Regex("[0-9]{1,10}")) } ?: "40190")
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            contentDescription = "官方影片编号"
            setSingleLine()
        }
        row.addView(input, LinearLayout.LayoutParams(220, -2))
        load = button(row, "加载影片") { prepare() }
        panel.addView(row)
        status = label("使用独立访客会话；不会读取原应用账号。", 16f)
        val media = PlayerView(this).apply { useController = false; isFocusable = false }
        panel.addView(media, LinearLayout.LayoutParams(-1, 0, 1f))
        val data = DefaultHttpDataSource.Factory().setUserAgent(NativeApiClient.AGENT)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(false)
            .setDefaultRequestProperties(mapOf("Referer" to NativeApiClient.WEB + "/", "Origin" to NativeApiClient.WEB))
        player = ExoPlayer.Builder(this).setMediaSourceFactory(HlsMediaSource.Factory(data))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5000, 20000, 1000, 2000)
                .setTargetBufferBytes(16 * 1024 * 1024).setBackBuffer(0, false).build()).build()
        media.player = player
        timeline = label("", 15f)
        val controls = LinearLayout(this)
        toggle = button(controls, "播放 / 暂停") {
            if (phase in setOf("advertisement", "content")) {
                if (player.playWhenReady) player.pause() else player.play()
            }
        }
        skip = button(controls, "广告") { finishAd() }
        seek = button(controls, "前进 10 秒") { seekForward() }
        speed = button(controls, "1× / 1.5×") {
            if (phase == "content") {
                watch.discontinuity()
                player.setPlaybackSpeed(if (player.playbackParameters.speed == 1f) 1.5f else 1f)
                record("speed", player.playbackParameters.speed.toDouble())
            }
        }
        panel.addView(controls)
        eventsView = label("", 12f)
        eventsView.maxLines = 3
        skip.isEnabled = false
        seek.isEnabled = false
        speed.isEnabled = false
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                record("media-state", state)
                if (phase == "content" && state == Player.STATE_READY && !started) {
                    started = true
                    val session = client ?: return
                    async({ session.started(); session.state(watch.total) }) {
                        record("native-content-started")
                        sendProgress()
                    }
                }
                if (state == Player.STATE_ENDED) {
                    // The 250 ms ticker can miss both ends of a 30 second ad.
                    // Flush actual media advancement before checking the gate.
                    val position = player.currentPosition.coerceAtLeast(0) / 1000.0
                    val now = SystemClock.elapsedRealtime()
                    if (phase == "advertisement") {
                        adWatch.sample(position, foreground, false, now)
                        if (adWatch.total >= 30) finishAd() else fail("short-ad-needs-fallback")
                    } else if (phase == "content") {
                        watch.sample(position, foreground, false, now,
                            player.playbackParameters.speed.toDouble())
                        record("content-ended")
                        state()
                        sendProgress()
                    }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                panel.keepScreenOn = isPlaying
                if (isPlaying) {
                    val position = player.currentPosition.coerceAtLeast(0) / 1000.0
                    val now = SystemClock.elapsedRealtime()
                    if (phase == "advertisement") adWatch.sample(position, foreground, false, now)
                    if (phase == "content") watch.sample(position, foreground, false, now,
                        player.playbackParameters.speed.toDouble())
                }
                if (phase == "advertisement" && isPlaying && !adStartRequested) {
                    adStartRequested = true
                    val session = client ?: return
                    async({ session.startAd() }) {
                        adStartAcknowledged = true
                        record("real-ad-started")
                    }
                }
                if (phase == "content") {
                    record(if (isPlaying) "content-playing" else "content-paused")
                    if (started) state()
                }
            }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                watch.discontinuity()
                adWatch.discontinuity()
                if (phase == "content" && reason == Player.DISCONTINUITY_REASON_SEEK) {
                    record("seek-completed", new.positionMs / 1000)
                }
            }
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                mediaWidth = size.width; mediaHeight = size.height
            }
            override fun onPlayerError(error: PlaybackException) { fail("media", error.errorCode) }
        })
        main.post(ticker)
        load.requestFocus()
        if (intent.getBooleanExtra("auto_prepare", false)) main.post { prepare() }
    }

    private fun prepare() {
        val id = input.text.toString().trim()
        if (!id.matches(Regex("[0-9]{1,10}"))) return
        generation++
        client?.close()
        player.stop()
        player.clearMediaItems()
        phase = "preparing"
        started = false; adStartRequested = false; adStartAcknowledged = false
        adFinishing = false; progressPending = false; nextProgress = 60.0
        guestProgress = false; progressAccepted = 0; mediaWidth = 0; mediaHeight = 0
        watch = WatchClock(); adWatch = WatchClock(); events.clear()
        load.isEnabled = false; skip.isEnabled = false; seek.isEnabled = false; speed.isEnabled = false
        status.text = "正在建立官方访客播放会话"
        val ticket = generation
        val session = NativeApiClient { stage, code ->
            main.post { if (ticket == generation && !destroyed) record("http-$stage", code) }
        }
        client = session
        async({ session.prepare(id) }) {
            preparation = it
            phase = "consent"
            status.text = it.title
            record("policy-ad-required", it.needsAd)
            if (foreground) showConsent()
        }
    }

    private fun showConsent() {
        val info = preparation ?: return
        if (dialog != null || phase != "consent") return
        val warning = when (info.rating) {
            1 -> "普遍级：一般观众皆可观看。"
            2 -> "保护级：未满 6 岁不宜观看，6～11 岁应由成人陪同。"
            3 -> "辅 12 级：未满 12 岁不宜观看。"
            4 -> "辅 15 级：未满 15 岁不宜观看。"
            else -> "限制级：未满 18 岁不宜观看；官方年龄验证仍须通过。"
        }
        dialog = AlertDialog.Builder(this).setTitle("官方分级提示").setMessage(warning)
            .setPositiveButton("同意并继续") { _, _ ->
                dialog = null
                client?.consent()
                record("age-consent", info.rating)
                if (info.needsAd) startAd(info) else requestSource()
            }.setNegativeButton("不同意") { _, _ -> dialog = null; fail("consent-declined") }
            .setOnCancelListener { dialog = null; fail("consent-declined") }.create()
        dialog?.show()
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            isFocusableInTouchMode = true
            requestFocus()
        }
    }

    private fun startAd(info: NativeApiClient.Preparation) {
        val ad = info.ad ?: return fail("ad-missing")
        phase = "advertisement"
        status.text = "官方视频广告 · 完成实际播放后才能请求正片"
        player.setPlaybackSpeed(1f)
        player.setMediaItem(MediaItem.fromUri(ad.playlist))
        player.prepare()
        player.playWhenReady = foreground
        toggle.requestFocus()
    }

    private fun finishAd() {
        if (phase != "advertisement" || adFinishing || !adStartAcknowledged || adWatch.total < 30) return
        adFinishing = true
        player.pause()
        skip.isEnabled = false
        val session = client ?: return
        val actual = adWatch.total
        async({ session.endAd(actual) }) {
            record("real-ad-completed-seconds", actual)
            requestSource()
        }
    }

    private fun requestSource() {
        phase = "source"
        status.text = "正在取得本次会话获准的播放源"
        val session = client ?: return
        async({ session.source() }) { source ->
            record("authorized-source-accepted")
            phase = "content"
            status.text = preparation?.title ?: "原生正片"
            val position = (preparation?.breakpoint ?: 0.0).takeIf { it.isFinite() && it in 0.0..172800.0 } ?: 0.0
            player.setMediaItem(MediaItem.fromUri(source), (position * 1000).toLong())
            player.prepare()
            player.playWhenReady = foreground
            load.isEnabled = true; seek.isEnabled = true; speed.isEnabled = true
            toggle.requestFocus()
        }
    }

    private fun state() {
        val session = client ?: return
        val total = watch.total
        async({ session.state(total) }) { record("state-notified") }
    }

    private fun sendProgress() {
        if (phase != "content" || progressPending || player.duration <= 0) return
        val session = client ?: return
        val duration = player.duration / 1000.0
        val position = (player.currentPosition / 1000.0).coerceIn(0.0, duration)
        progressPending = true
        async({ session.progress(position, duration) }) { result ->
            progressPending = false
            guestProgress = result == "guest"
            if (result == "ok") progressAccepted++
            record("progress-$result", position.toInt())
        }
    }

    private fun seekForward() {
        if (phase != "content" || player.duration <= 0) return
        watch.discontinuity()
        player.seekTo((player.currentPosition + 10000).coerceAtMost(player.duration))
    }

    private fun <T> async(block: () -> T, done: (T) -> Unit) {
        val ticket = generation
        worker.execute {
            try {
                val result = block()
                main.post { if (!destroyed && ticket == generation && phase != "error") done(result) }
            } catch (error: Exception) {
                main.post {
                    if (!destroyed && ticket == generation) {
                        if (error is ApiFailure) fail(error.stage, error.code)
                        else fail("protocol-" + error.javaClass.simpleName)
                    }
                }
            }
        }
    }

    private fun fail(stage: String, code: Int? = null) {
        if (phase == "error") return
        phase = "error"
        player.pause()
        status.text = "实验停止：$stage" + (code?.let { " ($it)" } ?: "") + " · 可重新加载"
        load.isEnabled = true; skip.isEnabled = false; seek.isEnabled = false; speed.isEnabled = false
        record("error-$stage", code)
        load.requestFocus()
    }

    private fun record(name: String, value: Any? = null) {
        events.addLast(JSONObject().put("event", name).put("value", value ?: JSONObject.NULL)
            .put("elapsedMs", SystemClock.elapsedRealtime()))
        while (events.size > 100) events.removeFirst()
        eventsView.text = events.takeLast(3).joinToString("\n") { it.optString("event") + " " + it.opt("value") }
        evidence()
    }

    private fun evidence() {
        val report = JSONObject().put("variant", "nativeApi").put("phase", phase)
            .put("androidApi", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS.joinToString())
            .put("webviewConstructedByExperiment", false).put("session", "fresh-guest")
            .put("videoId", input.text.toString().take(10))
            .put("positionSeconds", player.currentPosition.coerceAtLeast(0) / 1000.0)
            .put("durationSeconds", player.duration.coerceAtLeast(0) / 1000.0)
            .put("playing", player.isPlaying).put("rate", player.playbackParameters.speed.toDouble())
            .put("videoWidth", mediaWidth).put("videoHeight", mediaHeight)
            .put("realAdSeconds", adWatch.total).put("realWatchSeconds", watch.total)
            .put("progressAccepted", progressAccepted).put("guestProgress", guestProgress)
            .put("events", JSONArray(events.toList()))
        // Only sanitized protocol stages, numbers and public video id; no Cookie/device/media URL.
        File(filesDir, "native-api-evidence.json").writeText(report.toString(2))
    }

    private fun label(value: String, size: Float): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE)
        panel.addView(this)
    }
    private fun button(parent: LinearLayout, value: String, action: () -> Unit): Button =
        Button(this).apply {
            text = value; isFocusableInTouchMode = true
            setOnClickListener { action() }; parent.addView(this)
        }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (::player.isInitialized && phase == "consent") showConsent()
    }
    override fun onPause() {
        foreground = false
        if (::player.isInitialized) {
            player.pause()
            watch.discontinuity(); adWatch.discontinuity()
            if (phase == "content") sendProgress()
        }
        super.onPause()
    }
    override fun onDestroy() {
        destroyed = true
        generation++
        dialog?.dismiss()
        main.removeCallbacksAndMessages(null)
        client?.close()
        worker.shutdownNow()
        player.release()
        super.onDestroy()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            toggle.performClick()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    private fun time(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    }
}
