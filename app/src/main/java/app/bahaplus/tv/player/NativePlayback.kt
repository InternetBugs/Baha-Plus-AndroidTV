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
import app.bahaplus.tv.core.NativeSource
import app.bahaplus.tv.core.Preferences
import app.bahaplus.tv.data.OfficialPortal
import app.bahaplus.tv.diagnostics.Diagnostics
import app.bahaplus.tv.ui.TvStyle
import org.json.JSONObject
import java.util.Locale

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativePlayback(
    private val context: Context,
    private val portal: OfficialPortal,
    private val preferences: Preferences,
    private val source: NativeSource,
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
    private var currentQuality = preferences.quality.takeIf { source.variants.containsKey(it) } ?: 0
    private var released = false
    private var committing = false
    private var committed = false
    private var dialogOpen = false
    private var foreground = true
    private var endedSent = false
    private var errorSent = false
    private var clockPending = false
    private var lastClockAck = SystemClock.elapsedRealtime()
    private var timelineSecond = -1L
    private var timelineDurationSecond = -1L
    private var chooser: AlertDialog? = null
    private val hide = Runnable { if (player.isPlaying && !dialogOpen) hideControls() }

    private val factory = DefaultHttpDataSource.Factory()
        .setUserAgent(source.userAgent.ifEmpty { "BahaPlusTV/0.1" })
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

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            if (committed && foreground) {
                updateTimeline()
                if (!clockPending) clock()
                if (SystemClock.elapsedRealtime() - lastClockAck > 8000) fail("official-session-unresponsive")
            }
            handler.postDelayed(this, 1000)
        }
    }

    init {
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        view.addView(video, FrameLayout.LayoutParams(-1, -1))
        video.player = player
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        player.setHandleAudioBecomingNoisy(true)
        player.setPlaybackSpeed(preferences.speed)
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
        qualityButton = action(optionsRow, qualityLabel()) { chooseQuality() }
        speedButton = action(optionsRow, "${preferences.speed}×") { chooseSpeed() }
        action(optionsRow, tr("选集", "選集")) { chooseEpisode() }
        action(optionsRow, tr("返回", "返回")) { onExit() }
        controls.addView(optionsRow)
        view.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        view.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (!released && foreground) updateTimeline()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (released) return
                Diagnostics.record("player.state", "state" to state)
                if (state == Player.STATE_READY && !committed && !committing) commit()
                status.visibility = if (state == Player.STATE_BUFFERING || !committed) View.VISIBLE else View.GONE
                if (committed && state == Player.STATE_ENDED && !endedSent) {
                    endedSent = true
                    clock { accepted -> if (accepted && !released) onEnd() }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Diagnostics.record("player.playing", "playing" to isPlaying)
                view.keepScreenOn = isPlaying
                playButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                playButton.contentDescription = if (isPlaying) tr("暂停", "暫停") else tr("播放", "播放")
                if (committed && !released) {
                    if (!isPlaying && player.playbackState != Player.STATE_ENDED) showControls()
                    else scheduleHide()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                fail("media-${error.errorCode}")
            }
        })
        load(currentQuality, (source.position * 1000).toLong(), false)
        handler.post(tick)
        handler.postDelayed({ if (!released && !committed) fail("native-initialize-timeout") }, 25000)
    }

    private fun commit() {
        committing = true
        portal.commitNative { result ->
            if (released) return@commitNative
            if (result == null) {
                portal.failNative()
                fail("official-handoff-rejected")
                return@commitNative
            }
            val position = result.optDouble("position", source.position)
            if (!position.isFinite() || position < 0 || position > source.duration + 1) {
                fail("official-timeline-invalid")
                return@commitNative
            }
            player.seekTo((position * 1000).toLong())
            committed = true
            lastClockAck = SystemClock.elapsedRealtime()
            status.visibility = View.GONE
            onActive()
            player.playWhenReady = !result.optBoolean("paused", false) && foreground
            showControls()
            clock()
        }
    }
    private fun clock(callback: (Boolean) -> Unit = {}) {
        if (!committed || released) { callback(false); return }
        val duration = player.duration.takeIf { it > 0 } ?: (source.duration * 1000).toLong()
        val position = player.currentPosition.coerceIn(0, duration)
        val state = JSONObject().put("position", position / 1000.0).put("duration", duration / 1000.0)
            .put("paused", !player.isPlaying).put("rate", player.playbackParameters.speed.toDouble())
            .put("muted", player.volume == 0f).put("buffered", player.bufferedPosition.coerceAtLeast(0) / 1000.0)
            .put("seeking", false).put("ended", player.playbackState == Player.STATE_ENDED)
        clockPending = true
        portal.clock(state) { accepted ->
            if (released) return@clock
            clockPending = false
            if (accepted) lastClockAck = SystemClock.elapsedRealtime()
            else fail("official-session-denied")
            callback(accepted)
        }
    }
    private fun load(quality: Int, position: Long, play: Boolean) {
        val url = source.variants[quality] ?: source.master
        player.setMediaItem(MediaItem.fromUri(url), position.coerceAtLeast(0))
        player.playWhenReady = play
        player.prepare()
        updateTimeline()
    }
    private fun toggle() {
        if (released || !committed) return
        if (player.playbackState == Player.STATE_ENDED) {
            endedSent = false
            player.seekTo(0)
            player.play()
        } else if (player.isPlaying || player.playWhenReady) player.pause() else player.play()
        clock()
    }
    private fun seek(delta: Long) {
        if (!committed || released) return
        val duration = player.duration.takeIf { it > 0 } ?: return
        player.seekTo((player.currentPosition + delta).coerceIn(0, duration))
        Diagnostics.record("player.seek", "positionSeconds" to player.currentPosition / 1000)
        endedSent = false
        updateTimeline()
        clock()
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
        controls.visibility = View.GONE
        view.isFocusable = true
        view.requestFocus()
    }
    private fun scheduleHide() {
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, 4500)
    }
    fun handleBack(): Boolean {
        if (released || !committed) return false
        if (controls.visibility == View.VISIBLE) hideControls() else onExit()
        return true
    }
    fun handle(event: KeyEvent): Boolean {
        if (released || !committed) return false
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
        val values = listOf(0) + source.variants.keys.sortedDescending()
        showChoice(tr("画质", "畫質"), values.map { if (it == 0) tr("自动", "自動") else "${it}p" }, values.indexOf(currentQuality)) { index ->
            currentQuality = values[index]
            Diagnostics.record("player.quality", "quality" to currentQuality)
            preferences.quality = currentQuality
            val position = player.currentPosition
            val playing = player.playWhenReady
            load(currentQuality, position, playing)
            qualityButton.text = qualityLabel()
        }
    }
    private fun chooseSpeed() {
        val speeds = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        showChoice(tr("播放速度", "播放速度"), speeds.map { "${it}×" }, speeds.indexOf(preferences.speed)) { index ->
            preferences.speed = speeds[index]
            player.setPlaybackSpeed(speeds[index])
            speedButton.text = context.getString(R.string.playback_speed, speeds[index].toString())
            clock()
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
        player.pause()
        portal.failNative()
        onError(code)
    }
    fun pauseForLifecycle() {
        foreground = false
        player.pause()
        clock()
    }
    fun resumeLifecycle() {
        foreground = true
        lastClockAck = SystemClock.elapsedRealtime()
        showControls()
    }
    fun release() {
        if (released) return
        player.pause()
        clock()
        released = true
        handler.removeCallbacksAndMessages(null)
        chooser?.dismiss()
        chooser = null
        video.player = null
        player.release()
        view.keepScreenOn = false
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
