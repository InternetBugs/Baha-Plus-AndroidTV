package app.bahaplus.tv.player

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import app.bahaplus.tv.R
import app.bahaplus.tv.core.AnimeDetail
import app.bahaplus.tv.core.Preferences
import app.bahaplus.tv.diagnostics.Diagnostics
import app.bahaplus.tv.experiment.ApiFailure
import app.bahaplus.tv.experiment.NativeApiClient
import app.bahaplus.tv.experiment.WatchClock
import app.bahaplus.tv.ui.TvStyle
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.ceil

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativePlayback(
    private val context: Context,
    private val preferences: Preferences,
    private val session: NativeApiClient,
    private val detail: AnimeDetail,
    val videoId: String,
    private val onActive: () -> Unit,
    private val onEpisode: (String) -> Unit,
    private val onEnd: () -> Unit,
    private val onExit: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    val view = FrameLayout(context).apply { setBackgroundColor(android.graphics.Color.BLACK) }
    private val video = PlayerView(context).apply {
        useController = false
        isFocusable = false
        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
    }
    private val controls = TvStyle.vertical(context).apply {
        setPadding(TvStyle.dp(context, 26), TvStyle.dp(context, 14), TvStyle.dp(context, 26), TvStyle.dp(context, 22))
        setBackgroundColor(android.graphics.Color.rgb(16, 26, 36))
        visibility = View.GONE
    }
    private val heading = TvStyle.label(context, detail.title, 21f, bold = true)
    private val timeline = TvStyle.label(context, "", 15f, TvStyle.muted)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressBackgroundTintList = ColorStateList.valueOf(TvStyle.surface)
        secondaryProgressTintList = ColorStateList.valueOf(TvStyle.muted)
        progressTintList = ColorStateList.valueOf(TvStyle.focus)
    }
    private lateinit var playButton: ImageButton
    private lateinit var qualityButton: TextView
    private lateinit var speedButton: TextView
    private val status = TvStyle.label(context, tr("正在准备播放", "正在準備播放"), 21f).apply {
        gravity = Gravity.CENTER
        setPadding(20, 20, 20, 20)
        setBackgroundColor(0xb3101a24.toInt())
    }
    private var currentQuality = preferences.quality
    private var released = false
    private var committed = false
    private var dialogOpen = false
    private var foreground = true
    private var endedSent = false
    private var errorSent = false
    private val worker = Executors.newSingleThreadExecutor()
    private var phase = "preparing"
    private var preparation: NativeApiClient.Preparation? = null
    private val watch = WatchClock()
    private val adWatch = WatchClock()
    private var adStartRequested = false
    private var adStartAcknowledged = false
    private var adFinishing = false
    private var resumeAd = false
    private var progressPending = false
    private var danmakuLoading = false
    private var danmakuLoaded = false
    private var statePending = false
    private var nextProgress = 60.0
    private var consentDialog: AlertDialog? = null
    private val adPanel = TvStyle.horizontal(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(20, 12, 20, 12)
        setBackgroundColor(TvStyle.rail)
        visibility = View.GONE
    }
    private val adLabel = TvStyle.label(context, "", 18f)
    private val adSkip = TvStyle.button(context, tr("广告", "廣告"), ::finishAd).apply { isEnabled = false }
    private val adToggle = TvStyle.button(context, tr("暂停 / 播放", "暫停 / 播放")) {
        if (phase == "advertisement" && !adFinishing) {
            if (player.playWhenReady) player.pause() else player.play()
        }
    }
    private val adExit = TvStyle.button(context, tr("返回详情", "返回詳情"), onExit)
    private var timelineSecond = -1L
    private var timelineDurationSecond = -1L
    private var chooser: AlertDialog? = null
    private val hide = Runnable {
        if (player.isPlaying && !dialogOpen && !danmakuControls.expanded) hideControls()
    }

    private val factory = DefaultHttpDataSource.Factory()
        .setUserAgent(NativeApiClient.AGENT)
        .setConnectTimeoutMs(12000).setReadTimeoutMs(15000)
        .setAllowCrossProtocolRedirects(false)
        .setDefaultRequestProperties(mapOf("Referer" to "https://ani.gamer.com.tw/", "Origin" to "https://ani.gamer.com.tw"))
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(5000, 20000, 1000, 2000)
            .setTargetBufferBytes(if (preferences.lowResources) 16 * 1024 * 1024 else 32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false).setBackBuffer(0, false).build())
        .setMediaSourceFactory(HlsMediaSource.Factory(factory))
        .build()
    private val danmaku = DanmakuView(context, player, preferences.lowResources)
    private val danmakuControls = DanmakuControls(context, preferences,
        onChange = { settings ->
            danmaku.configure(settings)
            if (settings.enabled && !danmakuLoaded && !danmakuLoading) loadDanmaku()
        },
        onRetry = ::loadDanmaku,
        onInteraction = ::scheduleHide)

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            if (foreground && phase == "advertisement") {
                adWatch.sample(player.currentPosition.coerceAtLeast(0) / 1000.0, player.isPlaying,
                    false, SystemClock.elapsedRealtime())
                val available = adStartAcknowledged && adWatch.total >= 30 && !adFinishing
                if (available && !adSkip.isEnabled) adSkip.requestFocus()
                adSkip.isEnabled = available
                adSkip.text = if (available) tr("跳过广告", "跳過廣告")
                    else tr("广告 ", "廣告 ") + ceil((30 - adWatch.total).coerceAtLeast(0.0)).toInt() + tr(" 秒", " 秒")
                adLabel.text = tr("正在播放官方广告", "正在播放官方廣告")
            }
            if (committed) {
                sampleWatch(foreground && player.isPlaying)
                updateTimeline()
                if (watch.total >= nextProgress && !progressPending) {
                    nextProgress += 60
                    reportProgress()
                }
            }
            handler.postDelayed(this, 250)
        }
    }

    init {
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        view.addView(video, FrameLayout.LayoutParams(-1, -1))
        view.addView(danmaku, FrameLayout.LayoutParams(-1, -1))
        danmaku.configure(preferences.danmaku)
        video.player = player
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        player.setHandleAudioBecomingNoisy(true)
        controls.addView(heading)
        controls.addView(timeline, LinearLayout.LayoutParams(-1, -2).apply { topMargin = TvStyle.dp(context, 7) })
        val playbackRow = TvStyle.horizontal(context)
        val optionsRow = TvStyle.horizontal(context)
        fun action(row: LinearLayout, text: String, block: () -> Unit): TextView {
            val button = TvStyle.button(context, text) { block(); scheduleHide() }
            row.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(3, 0, 3, 0) })
            return button
        }
        fun iconAction(icon: Int, description: String, block: () -> Unit): ImageButton {
            val button = ImageButton(context).apply {
                id = View.generateViewId()
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                contentDescription = description
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(TvStyle.text)
                scaleType = ImageView.ScaleType.CENTER
                background = TvStyle.focusBackground(context)
                setOnClickListener { block(); scheduleHide() }
            }
            playbackRow.addView(button, LinearLayout.LayoutParams(0, TvStyle.dp(context, 44), 1f).apply {
                setMargins(3, 0, 3, 0)
            })
            return button
        }
        iconAction(R.drawable.ic_replay_10, tr("后退 10 秒", "後退 10 秒")) { seek(-10000) }
        playButton = iconAction(R.drawable.ic_play, tr("播放", "播放")) { toggle() }
        iconAction(R.drawable.ic_forward_10, tr("快进 10 秒", "快進 10 秒")) { seek(10000) }
        controls.addView(playbackRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = TvStyle.dp(context, 10) })
        controls.addView(progress, LinearLayout.LayoutParams(-1, TvStyle.dp(context, 5)).apply {
            topMargin = TvStyle.dp(context, 10)
            bottomMargin = TvStyle.dp(context, 13)
        })
        controls.addView(danmakuControls, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = TvStyle.dp(context, 6)
        })
        qualityButton = action(optionsRow, qualityLabel()) { chooseQuality() }
        speedButton = action(optionsRow, "${preferences.speed}×") { chooseSpeed() }
        action(optionsRow, tr("选集", "選集")) { chooseEpisode() }
        action(optionsRow, tr("返回", "返回")) { onExit() }
        controls.addView(optionsRow)
        view.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        view.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        adPanel.addView(adLabel, LinearLayout.LayoutParams(0, -2, 1f))
        adPanel.addView(adSkip)
        adPanel.addView(adToggle)
        adPanel.addView(adExit)
        view.addView(adPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (!released && foreground) {
                    updateTimeline()
                    danmaku.refresh()
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (released) return
                Diagnostics.record("player.state", "state" to state)
                if (state == Player.STATE_READY && phase == "starting") startContent()
                status.visibility = if (state == Player.STATE_BUFFERING || phase in setOf("preparing", "source", "starting"))
                    View.VISIBLE else View.GONE
                if (phase == "advertisement" && state == Player.STATE_ENDED) {
                    adWatch.sample(player.currentPosition.coerceAtLeast(0) / 1000.0, foreground,
                        false, SystemClock.elapsedRealtime())
                    if (adWatch.total >= 30) finishAd() else fail("short-ad-needs-fallback")
                }
                if (committed && state == Player.STATE_ENDED && !endedSent) {
                    endedSent = true
                    sampleWatch(foreground)
                    reportState()
                    reportProgress { if (!released && !errorSent) onEnd() }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Diagnostics.record("player.playing", "playing" to isPlaying)
                view.keepScreenOn = isPlaying
                playButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                playButton.contentDescription = if (isPlaying) tr("暂停", "暫停") else tr("播放", "播放")
                if (committed && !released) {
                    sampleWatch(foreground && isPlaying)
                    reportState()
                    if (!isPlaying && player.playbackState != Player.STATE_ENDED) showControls()
                    else scheduleHide()
                }
                if (phase == "advertisement" && isPlaying && !adStartRequested) {
                    adWatch.sample(player.currentPosition.coerceAtLeast(0) / 1000.0, foreground,
                        false, SystemClock.elapsedRealtime())
                    adStartRequested = true
                    async({ session.startAd() }) {
                        adStartAcknowledged = true
                        if (player.playbackState == Player.STATE_ENDED) finishAd()
                    }
                }
            }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                watch.discontinuity()
                adWatch.discontinuity()
                danmaku.refresh(discontinuity = true)
            }
            override fun onPlayerError(error: PlaybackException) {
                fail("media-${error.errorCode}")
            }
        })
        handler.post(tick)
        async({ session.prepare(videoId) }) {
            preparation = it
            phase = "consent"
            status.text = it.title
            if (foreground) showConsent()
        }
    }

    private fun showConsent() {
        if (phase != "consent" || consentDialog != null || released) return
        val warning = when (preparation?.rating) {
            1 -> tr("普遍级：一般观众皆可观看。", "普遍級：一般觀眾皆可觀看。")
            2 -> tr("保护级：未满 6 岁不宜观看，6～11 岁应由成人陪同。", "保護級：未滿 6 歲不宜觀看，6～11 歲應由成人陪同。")
            3 -> tr("辅 12 级：未满 12 岁不宜观看。", "輔 12 級：未滿 12 歲不宜觀看。")
            4 -> tr("辅 15 级：未满 15 岁不宜观看。", "輔 15 級：未滿 15 歲不宜觀看。")
            else -> tr("限制级：未满 18 岁不宜观看，仍须通过官方年龄验证。", "限制級：未滿 18 歲不宜觀看，仍須通過官方年齡驗證。")
        }
        consentDialog = AlertDialog.Builder(context).setTitle(tr("官方分级提示", "官方分級提示"))
            .setMessage(warning).setPositiveButton(tr("同意并继续", "同意並繼續")) { _, _ ->
                consentDialog = null
                session.consent()
                if (preparation?.needsAd == true) startAd() else requestSource()
            }.setNegativeButton(tr("不同意，返回详情", "不同意，返回詳情")) { _, _ -> onExit() }
            .setOnCancelListener { onExit() }.create().apply {
                show()
                getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
            }
    }

    private fun startAd() {
        val ad = preparation?.ad ?: return fail("ad-missing")
        phase = "advertisement"
        adPanel.visibility = View.VISIBLE
        status.visibility = View.GONE
        player.setPlaybackSpeed(1f)
        player.setMediaItem(MediaItem.fromUri(ad.playlist))
        player.prepare()
        player.playWhenReady = foreground
        adExit.requestFocus()
    }

    private fun finishAd() {
        if (released || phase != "advertisement" || adFinishing || !adStartAcknowledged || adWatch.total < 30) return
        adFinishing = true
        player.pause()
        adSkip.isEnabled = false
        val seconds = adWatch.total
        async({ session.endAd(seconds) }) { requestSource() }
    }

    private fun requestSource() {
        phase = "source"
        adPanel.visibility = View.GONE
        status.visibility = View.VISIBLE
        status.text = tr("正在取得官方播放源", "正在取得官方播放來源")
        async({ session.source() }) { source ->
            phase = "starting"
            player.setPlaybackSpeed(preferences.speed)
            applyQuality()
            val position = (preparation?.breakpoint ?: 0.0).coerceIn(0.0, 172800.0)
            player.setMediaItem(MediaItem.fromUri(source), (position * 1000).toLong())
            player.playWhenReady = false
            player.prepare()
        }
    }

    private fun startContent() {
        phase = "activating"
        async({ session.started() }) {
            committed = true
            phase = "content"
            danmaku.activate()
            status.visibility = View.GONE
            onActive()
            player.playWhenReady = foreground
            showControls()
            reportProgress()
            if (preferences.danmaku.enabled) loadDanmaku()
        }
    }

    /** Same serialized official session, but an optional read cannot fail playback. */
    private fun loadDanmaku() {
        if (released || errorSent || !committed || danmakuLoading) return
        danmakuLoading = true
        danmakuControls.loading()
        worker.execute {
            val result = runCatching { session.danmaku() }
            handler.post {
                if (released || errorSent) return@post
                danmakuLoading = false
                result.fold(onSuccess = { items ->
                    danmakuLoaded = true
                    danmaku.setItems(items)
                    danmakuControls.loaded(items.size)
                    Diagnostics.record("player.danmaku", "count" to items.size)
                }, onFailure = { error ->
                    danmakuControls.failed()
                    Diagnostics.record("player.danmaku-failed", "code" to
                        if (error is ApiFailure && error.stage == "danmaku-format") "danmaku-format" else "danmaku-load")
                })
            }
        }
    }

    private fun sampleWatch(playing: Boolean) {
        watch.sample(player.currentPosition.coerceAtLeast(0) / 1000.0, playing,
            false, SystemClock.elapsedRealtime(), player.playbackParameters.speed.toDouble())
    }

    private fun reportState() {
        if (!committed || released || statePending || errorSent) return
        statePending = true
        val seconds = watch.total
        async({ session.state(seconds) }) { statePending = false }
    }

    private fun reportProgress(after: () -> Unit = {}) {
        if (!committed || released || player.duration <= 0 || errorSent) return
        // The final report must be queued even if a periodic report is outstanding.
        if (progressPending && !endedSent) return
        val duration = player.duration / 1000.0
        val position = (player.currentPosition / 1000.0).coerceIn(0.0, duration)
        progressPending = true
        async({ session.progress(position, duration) }) {
            progressPending = false
            Diagnostics.record("player.progress", "code" to it)
            after()
        }
    }

    private fun <T> async(operation: () -> T, done: (T) -> Unit) {
        if (released || errorSent) return
        worker.execute {
            try {
                val value = operation()
                handler.post { if (!released && !errorSent) done(value) }
            } catch (error: Exception) {
                val code = if (error is ApiFailure) error.stage else "native-protocol"
                handler.post { if (!released && !errorSent) fail(code) }
            }
        }
    }

    private fun applyQuality() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, if (currentQuality == 0) Int.MAX_VALUE else currentQuality).build()
    }
    private fun toggle() {
        if (released || !committed) return
        if (player.playbackState == Player.STATE_ENDED) {
            endedSent = false
            player.seekTo(0)
            player.play()
        } else if (player.isPlaying || player.playWhenReady) player.pause() else player.play()
        reportState()
    }
    private fun seek(delta: Long) {
        if (!committed || released) return
        val duration = player.duration.takeIf { it > 0 } ?: return
        watch.discontinuity()
        player.seekTo((player.currentPosition + delta).coerceIn(0, duration))
        Diagnostics.record("player.seek", "positionSeconds" to player.currentPosition / 1000)
        endedSent = false
        updateTimeline()
        reportProgress()
    }
    private fun updateTimeline() {
        // Official clock updates continue independently while the controls are hidden.
        if (controls.visibility != View.VISIBLE) return
        val duration = player.duration.coerceAtLeast(0)
        val position = player.currentPosition.coerceAtLeast(0)
        val bufferedPosition = player.bufferedPosition.coerceAtLeast(position)
        fun scaled(value: Long) = if (duration > 0) (value.coerceIn(0, duration) * progress.max / duration).toInt() else 0
        if (position / 1000 != timelineSecond || duration / 1000 != timelineDurationSecond) {
            timelineSecond = position / 1000
            timelineDurationSecond = duration / 1000
            timeline.text = context.getString(R.string.playback_timeline, time(position), time(duration))
        }
        progress.progress = scaled(position)
        progress.secondaryProgress = scaled(bufferedPosition)
    }
    fun showControls() {
        if (released) return
        if (controls.visibility != View.VISIBLE) {
            controls.visibility = View.VISIBLE
            playButton.requestFocus()
        }
        updateTimeline()
        scheduleHide()
    }
    private fun hideControls() {
        danmakuControls.collapse()
        controls.visibility = View.GONE
        view.isFocusable = true
        view.requestFocus()
    }
    private fun scheduleHide() {
        handler.removeCallbacks(hide)
        if (!danmakuControls.expanded) handler.postDelayed(hide, 4500)
    }
    fun handleBack(): Boolean {
        if (released || !committed) return false
        if (danmakuControls.expanded) {
            danmakuControls.collapse()
            scheduleHide()
            return true
        }
        if (controls.visibility == View.VISIBLE) hideControls() else onExit()
        return true
    }
    fun handle(event: KeyEvent): Boolean {
        if (released) return false
        if (phase == "advertisement" && event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) adToggle.performClick()
            return true
        }
        if (!committed) return false
        val key = event.keyCode
        if (key == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) handleBack()
            return true
        }
        if (key in intArrayOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) when (key) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
                KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(10000)
                KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-10000)
                else -> toggle()
            }
            return true
        }
        if (controls.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN) scheduleHide()
            return false
        }
        if (key !in intArrayOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) return false
        if (event.action == KeyEvent.ACTION_DOWN) when (key) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) toggle()
            KeyEvent.KEYCODE_DPAD_LEFT -> seek(-10000)
            KeyEvent.KEYCODE_DPAD_RIGHT -> seek(10000)
            else -> showControls()
        }
        return true
    }
    private fun chooseQuality() {
        val heights = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it).height } }
            .filter { it in 144..4320 }.distinct().sortedDescending()
        val values = listOf(0) + heights
        showChoice(tr("画质", "畫質"), values.map { if (it == 0) tr("自动", "自動") else "${it}p" }, values.indexOf(currentQuality)) { index ->
            currentQuality = values[index]
            Diagnostics.record("player.quality", "quality" to currentQuality)
            preferences.quality = currentQuality
            watch.discontinuity()
            applyQuality()
            qualityButton.text = qualityLabel()
        }
    }
    private fun chooseSpeed() {
        val speeds = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        showChoice(tr("播放速度", "播放速度"), speeds.map { "${it}×" }, speeds.indexOf(preferences.speed)) { index ->
            preferences.speed = speeds[index]
            watch.discontinuity()
            player.setPlaybackSpeed(speeds[index])
            speedButton.text = context.getString(R.string.playback_speed, speeds[index].toString())
            reportState()
        }
    }
    private fun chooseEpisode() {
        showChoice(tr("选集", "選集"), detail.episodes.map { it.label }, detail.episodes.indexOfFirst { it.id == videoId }) { index ->
            val next = detail.episodes[index].id
            if (next != videoId) onEpisode(next)
        }
    }
    private fun showChoice(title: String, values: List<String>, selected: Int, action: (Int) -> Unit) {
        if (values.isEmpty()) return
        dialogOpen = true
        handler.removeCallbacks(hide)
        chooser = AlertDialog.Builder(context).setTitle(title)
            .setSingleChoiceItems(values.toTypedArray(), selected) { dialog, index -> dialog.dismiss(); action(index) }
            .setNegativeButton(tr("返回", "返回"), null).create().apply {
                setOnDismissListener { dialogOpen = false; chooser = null; if (!released) showControls() }
                show()
            }
    }
    private fun qualityLabel() = if (currentQuality == 0) tr("自动画质", "自動畫質") else "${currentQuality}p"
    private fun fail(code: String) {
        if (released || errorSent) return
        errorSent = true
        phase = "error"
        danmaku.setForeground(false)
        player.pause()
        session.close()
        onError(code)
    }
    fun pauseForLifecycle() {
        resumeAd = phase == "advertisement" && player.playWhenReady
        if (phase == "advertisement") adWatch.sample(player.currentPosition.coerceAtLeast(0) / 1000.0,
            foreground && player.isPlaying, false, SystemClock.elapsedRealtime())
        foreground = false
        danmaku.setForeground(false)
        sampleWatch(player.isPlaying)
        player.pause()
        watch.discontinuity()
        adWatch.discontinuity()
        reportState()
        reportProgress()
    }
    fun resumeLifecycle() {
        foreground = true
        danmaku.setForeground(true)
        if (phase == "consent") showConsent()
        if (committed) showControls()
        else if (phase == "advertisement") {
            if (resumeAd && !adFinishing) player.play()
            adExit.requestFocus()
        }
    }
    fun release() {
        if (released) return
        val duration = player.duration / 1000.0
        val position = if (duration > 0) (player.currentPosition / 1000.0).coerceIn(0.0, duration) else 0.0
        val shouldReport = committed && !errorSent && duration > 0
        sampleWatch(player.isPlaying)
        player.pause()
        released = true
        danmaku.release()
        if (!shouldReport) session.close()
        handler.removeCallbacksAndMessages(null)
        chooser?.dismiss()
        chooser = null
        consentDialog?.dismiss()
        consentDialog = null
        video.player = null
        player.release()
        view.keepScreenOn = false
        worker.execute {
            try {
                if (shouldReport) {
                    session.state(watch.total)
                    session.progress(position, duration)
                }
            } catch (_: Exception) {
                // Closing the page must not show a stale playback error.
            } finally { session.close() }
        }
        worker.shutdown()
    }
    private fun tr(simplified: String, traditional: String) = preferences.text(simplified, traditional)
    companion object {
        fun time(milliseconds: Long): String {
            val seconds = milliseconds.coerceAtLeast(0) / 1000
            return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
        }
    }
}
