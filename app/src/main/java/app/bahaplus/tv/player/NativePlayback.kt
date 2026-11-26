package app.bahaplus.tv.player

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
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
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import app.bahaplus.tv.R
import app.bahaplus.tv.core.AnimeDetail
import app.bahaplus.tv.core.AppStrings
import app.bahaplus.tv.core.OfficialSite
import app.bahaplus.tv.core.Preferences
import app.bahaplus.tv.diagnostics.Diagnostics
import app.bahaplus.tv.experiment.ApiFailure
import app.bahaplus.tv.experiment.NativeApiClient
import app.bahaplus.tv.experiment.WatchClock
import app.bahaplus.tv.ui.TvDialog
import app.bahaplus.tv.ui.TvStyle
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
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
        // Status pill handles loading and buffering uniformly without a second overlapping spinner.
        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
    }
    private val controls = TvStyle.vertical(context).apply {
        setPadding(dp(28), dp(28), dp(28), dp(20))
        background = TvStyle.bottomScrim()
        visibility = View.GONE
    }
    private val episodeLabel = detail.episodes.firstOrNull { it.id == videoId }
        ?.let { AppStrings.episodeName(it.label) }.orEmpty()
    private val heading = TvStyle.label(
        context,
        if (episodeLabel.isEmpty()) detail.title else "${detail.title} · $episodeLabel",
        19f,
        bold = true
    ).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val timeline = TvStyle.label(context, "", 14f, TvStyle.muted).apply {
        maxLines = 1
    }
    private val progress = styledProgressBar()
    private val seekOsd = TvStyle.vertical(context).apply {
        background = TvStyle.shape(context, 0xD9101A24.toInt(), TvStyle.surface, width = 1, radius = 10)
        setPadding(dp(18), dp(10), dp(18), dp(12))
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
    }
    private val seekOsdLabel = TvStyle.label(context, "", 16f, TvStyle.text, bold = true).apply {
        maxLines = 1
    }
    private val seekOsdProgress = styledProgressBar()
    private lateinit var playButton: ImageButton
    private lateinit var qualityButton: TextView
    private lateinit var speedButton: TextView
    private val status = TvStyle.label(context, tr("正在准备播放", "正在準備播放"), 18f, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(22), dp(12), dp(22), dp(12))
        background = TvStyle.shape(context, 0xD9101A24.toInt(), TvStyle.surface, width = 1, radius = 10)
    }
    private var currentQuality = preferences.quality
    private var activeVideoHeight = 0
    private var released = false
    private var committed = false
    private var dialogOpen = false
    private var foreground = true
    private var endedSent = false
    private var errorSent = false
    private var backKeyDown = false
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
    private var seekDeltaAccumMs = 0L
    private var lastSeekAtMs = 0L
    private var consentDialog: AlertDialog? = null
    private val adPanel = TvStyle.horizontal(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(24), dp(14), dp(24), dp(14))
        setBackgroundColor(TvStyle.rail)
        visibility = View.GONE
    }
    private val adLabel = TvStyle.label(context, "", 17f, bold = true)
    private val adSkip = TvStyle.compactButton(context, tr("广告", "廣告"), ::finishAd).apply {
        isEnabled = false
        isFocusable = false
    }
    private val adToggle = TvStyle.compactButton(context, tr("暂停 / 播放", "暫停 / 播放")) {
        if (phase == "advertisement" && !adFinishing) {
            if (player.playWhenReady) player.pause() else player.play()
        }
    }
    private val adExit = TvStyle.compactButton(context, tr("返回详情", "返回詳情"), onExit)
    private var timelineSecond = -1L
    private var timelineDurationSecond = -1L
    private var chooser: AlertDialog? = null
    private val hide = Runnable {
        if (player.isPlaying && !dialogOpen && !danmakuControls.expanded) hideControls()
    }
    private val hideSeekOsd = Runnable {
        seekOsd.visibility = View.GONE
        seekDeltaAccumMs = 0L
    }
    private val flushSeekProgress = Runnable {
        reportProgress()
    }

    private val factory = DefaultHttpDataSource.Factory()
        .setUserAgent(OfficialSite.USER_AGENT)
        .setConnectTimeoutMs(12000).setReadTimeoutMs(15000)
        .setAllowCrossProtocolRedirects(false)
        .setDefaultRequestProperties(mapOf("Referer" to "https://ani.gamer.com.tw/", "Origin" to "https://ani.gamer.com.tw"))
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(5000, 20000, 1000, 2000)
            .setTargetBufferBytes(if (preferences.lowResources) 16 * 1024 * 1024 else 32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(if (preferences.lowResources) 5_000 else 15_000, false)
            .build())
        .setMediaSourceFactory(HlsMediaSource.Factory(factory))
        .build()
    private val danmaku = DanmakuView(context, player, preferences.lowResources)
    private lateinit var danmakuControls: DanmakuControls

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            if (foreground && phase == "advertisement") {
                adWatch.sample(player.currentPosition.coerceAtLeast(0) / 1000.0, player.isPlaying,
                    false, SystemClock.elapsedRealtime())
                val available = adStartAcknowledged && adWatch.total >= 30 && !adFinishing
                val wasAvailable = adSkip.isEnabled
                adSkip.isEnabled = available
                adSkip.isFocusable = available
                if (available && !wasAvailable) adSkip.requestFocus()
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

        val headerRow = TvStyle.horizontal(context).apply { gravity = Gravity.CENTER_VERTICAL }
        headerRow.addView(heading, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(16) })
        headerRow.addView(timeline, LinearLayout.LayoutParams(-2, -2))
        controls.addView(headerRow)
        controls.addView(progress, LinearLayout.LayoutParams(-1, dp(5)).apply {
            topMargin = dp(10)
            bottomMargin = dp(12)
        })

        fun iconAction(icon: Int, description: String, block: () -> Unit): ImageButton =
            ImageButton(context).apply {
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

        val replayButton = iconAction(R.drawable.ic_replay_10, tr("后退 10 秒", "後退 10 秒")) { seek(-10000) }
        playButton = iconAction(R.drawable.ic_play, tr("播放", "播放")) { toggle() }
        val forwardButton = iconAction(R.drawable.ic_forward_10, tr("快进 10 秒", "快進 10 秒")) { seek(10000) }
        danmakuControls = DanmakuControls(
            context = context,
            preferences = preferences,
            leadingViews = listOf(replayButton to 54, playButton to 68, forwardButton to 54),
            onChange = { settings ->
                danmaku.configure(settings)
                if (settings.enabled && !danmakuLoaded && !danmakuLoading) loadDanmaku()
            },
            onRetry = ::loadDanmaku,
            onInteraction = ::scheduleHide
        )
        controls.addView(danmakuControls, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })

        val optionsRow = TvStyle.horizontal(context)
        fun optionButton(first: Boolean = false, text: String, block: () -> Unit): TextView {
            val button = TvStyle.compactButton(context, text) { block(); scheduleHide() }
            optionsRow.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                if (!first) leftMargin = dp(6)
            })
            return button
        }
        qualityButton = optionButton(first = true, qualityLabel()) { chooseQuality() }
        speedButton = optionButton(text = context.getString(R.string.playback_speed, preferences.speed.toString())) { chooseSpeed() }
        optionButton(text = tr("选集", "選集")) { chooseEpisode() }
        optionButton(text = tr("返回", "返回")) { onExit() }
        controls.addView(optionsRow)

        seekOsd.addView(seekOsdLabel)
        seekOsd.addView(seekOsdProgress, LinearLayout.LayoutParams(dp(260), dp(4)).apply { topMargin = dp(8) })
        view.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        view.addView(seekOsd, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(36)
        })
        view.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        adPanel.addView(adLabel, LinearLayout.LayoutParams(0, -2, 1f))
        adPanel.addView(adSkip, LinearLayout.LayoutParams(-2, dp(42)).apply { leftMargin = dp(8) })
        adPanel.addView(adToggle, LinearLayout.LayoutParams(-2, dp(42)).apply { leftMargin = dp(8) })
        adPanel.addView(adExit, LinearLayout.LayoutParams(-2, dp(42)).apply { leftMargin = dp(8) })
        view.addView(adPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (!released && foreground) {
                    updateTimeline()
                    danmaku.refresh()
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height in 144..4320 && videoSize.height != activeVideoHeight) {
                    activeVideoHeight = videoSize.height
                    qualityButton.text = qualityLabel()
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (released) return
                Diagnostics.record("player.state", "state" to state)
                if (state == Player.STATE_READY && phase == "starting") startContent()
                if (committed && state == Player.STATE_BUFFERING) {
                    status.text = tr("正在缓冲…", "正在緩衝…")
                }
                status.visibility = if (state == Player.STATE_BUFFERING || phase in setOf("preparing", "source", "starting"))
                    View.VISIBLE else View.GONE
                if (phase == "advertisement" && state == Player.STATE_ENDED) {
                    adWatch.sample(player.currentPosition.coerceAtLeast(0) / 1000.0, foreground,
                        false, SystemClock.elapsedRealtime())
                    if (adWatch.total >= 30) finishAd() else fail("short-ad-needs-fallback")
                }
                if (committed && state == Player.STATE_ENDED && !endedSent) {
                    endedSent = true
                    handler.removeCallbacks(flushSeekProgress)
                    sampleWatch(foreground)
                    reportState()
                    reportProgress { if (!released && !errorSent) onEnd() }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Diagnostics.record("player.playing", "playing" to isPlaying)
                view.keepScreenOn = isPlaying
                val activePlay = isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
                playButton.setImageResource(if (activePlay) R.drawable.ic_pause else R.drawable.ic_play)
                playButton.contentDescription = if (activePlay) tr("暂停", "暫停") else tr("播放", "播放")
                if (committed && !released) {
                    sampleWatch(foreground && isPlaying)
                    reportState()
                    // Only reveal the full control bar when paused, never on transient seek/network buffering.
                    if (!player.playWhenReady && player.playbackState != Player.STATE_ENDED) showControls()
                    else if (controls.visibility == View.VISIBLE) scheduleHide()
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

    private fun styledProgressBar(): ProgressBar =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            progressDrawable = LayerDrawable(arrayOf(
                TvStyle.shape(context, TvStyle.surface, radius = 3),
                ClipDrawable(TvStyle.shape(context, TvStyle.muted, radius = 3), Gravity.START, ClipDrawable.HORIZONTAL),
                ClipDrawable(TvStyle.shape(context, TvStyle.focus, radius = 3), Gravity.START, ClipDrawable.HORIZONTAL)
            )).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.secondaryProgress)
                setId(2, android.R.id.progress)
            }
        }

    private fun showConsent() {
        if (phase != "consent" || consentDialog != null || released) return
        val warning = AppStrings.ratingWarning(preferences.traditional, preparation?.rating)
        consentDialog = TvDialog.confirm(
            context = context,
            title = tr("官方分级提示", "官方分級提示"),
            message = warning,
            actions = listOf(
                TvDialog.Action(tr("不同意，返回详情", "不同意，返回詳情")) { onExit() },
                TvDialog.Action(tr("同意并继续", "同意並繼續"), primary = true) {
                    consentDialog = null
                    session.consent()
                    if (preparation?.needsAd == true) startAd() else requestSource()
                }
            ),
            focusIndex = 1,
            onCancel = { onExit() }
        )
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
        adSkip.isFocusable = false
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
        val target = (player.currentPosition + delta).coerceIn(0, duration)
        watch.discontinuity()
        player.seekTo(target)
        Diagnostics.record("player.seek", "positionSeconds" to target / 1000)
        endedSent = false
        updateTimeline()
        if (controls.visibility != View.VISIBLE) showSeekOsd(delta, target, duration)
        // Debounce progress reporting so rapid multi-tap skips report the final landing spot.
        handler.removeCallbacks(flushSeekProgress)
        handler.postDelayed(flushSeekProgress, 1500L)
    }

    private fun showSeekOsd(deltaMs: Long, positionMs: Long, durationMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSeekAtMs <= 1200L && ((seekDeltaAccumMs >= 0 && deltaMs >= 0) || (seekDeltaAccumMs <= 0 && deltaMs <= 0))) {
            seekDeltaAccumMs += deltaMs
        } else {
            seekDeltaAccumMs = deltaMs
        }
        lastSeekAtMs = now
        val seconds = (abs(seekDeltaAccumMs) / 1000L).coerceAtLeast(1L)
        val direction = if (seekDeltaAccumMs >= 0) tr("快进", "快進") else tr("后退", "後退")
        seekOsdLabel.text = context.getString(
            R.string.playback_seek_osd,
            direction,
            seconds,
            time(positionMs),
            time(durationMs)
        )
        seekOsdProgress.progress = if (durationMs > 0) (positionMs * seekOsdProgress.max / durationMs).toInt() else 0
        seekOsd.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeekOsd)
        handler.postDelayed(hideSeekOsd, 1600L)
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
        handler.removeCallbacks(hideSeekOsd)
        seekOsd.visibility = View.GONE
        seekDeltaAccumMs = 0L
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
            if (event.action == KeyEvent.ACTION_DOWN) {
                backKeyDown = true
            } else if (event.action == KeyEvent.ACTION_UP) {
                val triggered = backKeyDown && !event.isCanceled
                backKeyDown = false
                if (triggered) handleBack()
            }
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
        val selectedIndex = values.indexOf(currentQuality).takeIf { it >= 0 }
            ?: if (currentQuality > 0) values.indexOfFirst { it in 1..currentQuality }.takeIf { it >= 0 } ?: 0 else 0
        val labels = values.map { height ->
            if (height == 0) {
                if (activeVideoHeight > 0) tr("自动 (${activeVideoHeight}p)", "自動 (${activeVideoHeight}p)")
                else tr("自动", "自動")
            } else "${height}p"
        }
        showChoice(tr("画质", "畫質"), labels, selectedIndex) { index ->
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
        showChoice(
            tr("选集", "選集"),
            detail.episodes.map { AppStrings.episodeName(it.label) },
            detail.episodes.indexOfFirst { it.id == videoId }
        ) { index ->
            val next = detail.episodes[index].id
            if (next != videoId) onEpisode(next)
        }
    }
    private fun showChoice(title: String, values: List<String>, selected: Int, action: (Int) -> Unit) {
        if (values.isEmpty()) return
        dialogOpen = true
        handler.removeCallbacks(hide)
        chooser = TvDialog.choice(
            context = context,
            title = title,
            items = values,
            selected = selected,
            cancelLabel = tr("返回", "返回"),
            onSelect = action,
            onDismiss = { dialogOpen = false; chooser = null; if (!released) showControls() }
        )
    }
    private fun qualityLabel(): String = if (currentQuality == 0) {
        if (activeVideoHeight > 0) tr("自动 · ${activeVideoHeight}p", "自動 · ${activeVideoHeight}p")
        else tr("自动画质", "自動畫質")
    } else "${currentQuality}p"

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
        handler.removeCallbacks(flushSeekProgress)
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
    private fun dp(value: Int) = TvStyle.dp(context, value)
    private fun tr(simplified: String, traditional: String) = preferences.text(simplified, traditional)
    companion object {
        fun time(milliseconds: Long): String {
            val seconds = milliseconds.coerceAtLeast(0) / 1000
            return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
        }
    }
}
