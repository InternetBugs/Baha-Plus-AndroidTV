package app.bahaplus.tv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.bahaplus.tv.core.Anime
import app.bahaplus.tv.core.AnimeDetail
import app.bahaplus.tv.core.AppStrings
import app.bahaplus.tv.core.Episode
import app.bahaplus.tv.core.RatingSummary
import app.bahaplus.tv.core.WatchEntry
import app.bahaplus.tv.data.PosterLoader

/**
 * Full scrollable detail page: header + rating + episode range tabs + 6-column episode grid
 * in one RecyclerView so episodes can use the full viewport when scrolling down.
 */
internal class DetailAdapter(
    private val context: Context,
    private val anime: Anime,
    private var detail: AnimeDetail,
    private val posters: PosterLoader,
    private val traditional: Boolean,
    private val leftTarget: Int,
    private var favorited: Boolean,
    private var resumeEntry: WatchEntry?,
    private var playingVideoId: String,
    private var ratingLoading: Boolean,
    private val onLeft: () -> Unit,
    private val onPlay: (String) -> Unit,
    private val onToggleFavorite: () -> Unit,
    private val onShowIntro: (String) -> Unit,
    private val onFocusAction: (Int) -> Unit,
    private val onFocusEpisode: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var rangeIndex = initialRangeIndex()
    private val showRating get() = detail.score != null || detail.ratingFanId != null
    private val headerOffset get() = if (showRating) 3 else 2
    private var boundHeader: HeaderHolder? = null
    private var boundRating: RatingHolder? = null

    private val spanLookup = object : GridLayoutManager.SpanSizeLookup() {
        init {
            isSpanIndexCacheEnabled = true
            isSpanGroupIndexCacheEnabled = true
        }
        override fun getSpanSize(position: Int): Int =
            if (getItemViewType(position) == TYPE_EPISODE) 1 else SPAN_COUNT
    }

    class HeaderHolder(
        val root: LinearLayout,
        val image: ImageView,
        val description: TextView,
        val metadata: TextView,
        val playButton: TextView,
        val favoriteButton: TextView,
        val introButton: TextView
    ) : RecyclerView.ViewHolder(root)

    class RatingHolder(val ratingView: AnimeRatingView) : RecyclerView.ViewHolder(ratingView)

    class EpisodeHeaderHolder(
        val root: LinearLayout,
        val label: TextView,
        val rangeScroll: HorizontalScrollView,
        val rangeRow: LinearLayout
    ) : RecyclerView.ViewHolder(root)

    class EpisodeHolder(val button: TextView) : RecyclerView.ViewHolder(button)

    fun spans(): GridLayoutManager.SpanSizeLookup = spanLookup

    fun actionView(slot: Int): View? = when (slot) {
        0 -> boundHeader?.playButton
        1 -> boundHeader?.favoriteButton
        2 -> boundHeader?.introButton
        else -> null
    }

    fun episodeAdapterPosition(globalEpisodeIndex: Int): Int? {
        if (detail.episodes.isEmpty()) return null
        val clamped = globalEpisodeIndex.coerceIn(0, detail.episodes.lastIndex)
        val neededRange = clamped / RANGE_SIZE
        if (neededRange != rangeIndex) selectRange(neededRange)
        return headerOffset + (clamped - rangeIndex * RANGE_SIZE)
    }

    fun updateFavorite(isFavorited: Boolean) {
        if (favorited == isFavorited) return
        favorited = isFavorited
        boundHeader?.let(::bindFavoriteButton)
    }

    fun updateRating(rating: RatingSummary?, loading: Boolean) {
        ratingLoading = loading
        if (rating != null) detail = detail.copy(rating = rating)
        boundRating?.ratingView?.bind(detail.score, detail.rating, ratingLoading)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateDetail(updated: AnimeDetail, newFavorited: Boolean, newResume: WatchEntry?, loadingRating: Boolean) {
        detail = updated
        favorited = newFavorited
        resumeEntry = newResume
        ratingLoading = loadingRating
        val rangeCount = rangeCount()
        if (rangeIndex >= rangeCount) rangeIndex = (rangeCount - 1).coerceAtLeast(0)
        spanLookup.invalidateSpanIndexCache()
        spanLookup.invalidateSpanGroupIndexCache()
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun selectRange(index: Int) {
        val clamped = index.coerceIn(0, (rangeCount() - 1).coerceAtLeast(0))
        if (clamped == rangeIndex) return
        rangeIndex = clamped
        spanLookup.invalidateSpanIndexCache()
        spanLookup.invalidateSpanGroupIndexCache()
        notifyDataSetChanged()
    }

    private fun rangeCount(): Int =
        if (detail.episodes.isEmpty()) 0 else (detail.episodes.size + RANGE_SIZE - 1) / RANGE_SIZE

    private fun visibleEpisodes(): List<Episode> {
        if (detail.episodes.isEmpty()) return emptyList()
        val start = rangeIndex * RANGE_SIZE
        if (start >= detail.episodes.size) return emptyList()
        return detail.episodes.subList(start, minOf(start + RANGE_SIZE, detail.episodes.size))
    }

    private fun initialRangeIndex(): Int {
        val targetId = playingVideoId.ifEmpty { resumeEntry?.videoId.orEmpty() }
        if (targetId.isEmpty()) return 0
        val idx = detail.episodes.indexOfFirst { it.id == targetId }
        return if (idx >= 0) idx / RANGE_SIZE else 0
    }

    override fun getItemCount(): Int = headerOffset + visibleEpisodes().size

    override fun getItemViewType(position: Int): Int = when {
        position == 0 -> TYPE_HEADER
        showRating && position == 1 -> TYPE_RATING
        position == headerOffset - 1 -> TYPE_EPISODE_HEADER
        else -> TYPE_EPISODE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
        TYPE_HEADER -> createHeaderHolder()
        TYPE_RATING -> RatingHolder(AnimeRatingView(context, traditional).apply {
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        })
        TYPE_EPISODE_HEADER -> createEpisodeHeaderHolder()
        else -> createEpisodeHolder()
    }

    private fun createHeaderHolder(): HeaderHolder {
        val header = TvStyle.horizontal(context).apply {
            layoutParams = RecyclerView.LayoutParams(-1, -2)
        }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(TvStyle.rail)
            TvStyle.roundCorners(this, 6)
        }
        header.addView(image, LinearLayout.LayoutParams(dp(236), dp(134)).apply { rightMargin = dp(20) })
        val info = TvStyle.vertical(context)
        val description = TvStyle.label(context, "", 15f, TvStyle.muted).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        val metadata = TvStyle.label(context, "", 13f, TvStyle.muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(7), 0, dp(8))
        }
        info.addView(description)
        info.addView(metadata)

        val actions = TvStyle.horizontal(context)
        val playButton = TvStyle.primaryButton(context, "") {
            primaryPlayEpisode()?.let { onPlay(it.id) }
        }.apply {
            nextFocusLeftId = leftTarget
            setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                    onLeft(); true
                } else false
            }
            setOnFocusChangeListener { _, focused -> if (focused) onFocusAction(0) }
        }
        val favoriteButton = TvStyle.button(context, "") { onToggleFavorite() }.apply {
            setOnFocusChangeListener { _, focused -> if (focused) onFocusAction(1) }
        }
        val introButton = TvStyle.button(context, tr("完整简介", "完整簡介")) {
            onShowIntro(buildFullIntro())
        }.apply {
            setOnFocusChangeListener { _, focused -> if (focused) onFocusAction(2) }
        }
        actions.addView(playButton, LinearLayout.LayoutParams(-2, dp(44)))
        actions.addView(favoriteButton, LinearLayout.LayoutParams(-2, dp(44)).apply { leftMargin = dp(8) })
        actions.addView(introButton, LinearLayout.LayoutParams(-2, dp(44)).apply { leftMargin = dp(8) })
        info.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        header.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        return HeaderHolder(header, image, description, metadata, playButton, favoriteButton, introButton)
    }

    private fun createEpisodeHeaderHolder(): EpisodeHeaderHolder {
        val root = TvStyle.vertical(context).apply {
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                topMargin = dp(14)
                bottomMargin = dp(6)
            }
        }
        val label = TvStyle.label(context, "", 19f, bold = true)
        val rangeRow = TvStyle.horizontal(context)
        val rangeScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(rangeRow)
        }
        root.addView(label)
        root.addView(rangeScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return EpisodeHeaderHolder(root, label, rangeScroll, rangeRow)
    }

    private fun createEpisodeHolder(): EpisodeHolder {
        val button = TvStyle.button(context, "") {}.apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = RecyclerView.LayoutParams(-1, dp(46)).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
        }
        val holder = EpisodeHolder(button)
        button.setOnKeyListener { _, keyCode, event ->
            val pos = holder.bindingAdapterPosition
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN &&
                pos >= headerOffset && (pos - headerOffset) % SPAN_COUNT == 0) {
                onLeft()
                true
            } else false
        }
        button.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            val ep = visibleEpisodes().getOrNull(pos - headerOffset) ?: return@setOnClickListener
            onPlay(ep.id)
        }
        button.setOnFocusChangeListener { _, focused ->
            val pos = holder.bindingAdapterPosition
            if (focused && pos >= headerOffset) {
                onFocusEpisode(rangeIndex * RANGE_SIZE + (pos - headerOffset))
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderHolder -> bindHeader(holder)
            is RatingHolder -> {
                boundRating = holder
                holder.ratingView.bind(detail.score, detail.rating, ratingLoading)
            }
            is EpisodeHeaderHolder -> bindEpisodeHeader(holder)
            is EpisodeHolder -> bindEpisode(holder, position - headerOffset)
        }
    }

    private fun bindHeader(holder: HeaderHolder) {
        boundHeader = holder
        posters.load(holder.image, detail.poster ?: anime.cover)
        holder.description.text = detail.description.ifBlank { tr("暂无简介", "暫無簡介") }
        val metaSummary = formatMetadataLine()
        holder.metadata.text = metaSummary
        holder.metadata.visibility = if (metaSummary.isEmpty()) View.GONE else View.VISIBLE

        val resume = matchingResumeEpisode()
        val hasEpisodes = detail.episodes.isNotEmpty()
        holder.playButton.isEnabled = hasEpisodes
        holder.playButton.isFocusable = hasEpisodes
        holder.playButton.text = when {
            resume != null -> tr("继续播放 ", "繼續播放 ") + AppStrings.episodeName(resume.label)
            else -> tr("开始播放", "開始播放")
        }
        bindFavoriteButton(holder)
    }

    private fun bindFavoriteButton(holder: HeaderHolder) {
        holder.favoriteButton.isSelected = favorited
        holder.favoriteButton.text = if (favorited) tr("✓ 已追番", "✓ 已追番") else tr("+ 加入追番", "+ 加入追番")
        holder.favoriteButton.setTextColor(TvStyle.buttonTextColors(if (favorited) TvStyle.focus else TvStyle.text))
    }

    private fun bindEpisodeHeader(holder: EpisodeHeaderHolder) {
        val total = detail.episodes.size
        holder.label.text = if (total > 0) tr("选集（共 $total 集）", "選集（共 $total 集）")
            else tr("选集", "選集")
        val count = rangeCount()
        if (count <= 1) {
            holder.rangeScroll.visibility = View.GONE
            return
        }
        holder.rangeScroll.visibility = View.VISIBLE
        holder.rangeRow.removeAllViews()
        for (idx in 0 until count) {
            val start = idx * RANGE_SIZE + 1
            val end = minOf((idx + 1) * RANGE_SIZE, total)
            val active = idx == rangeIndex
            val tab = TvStyle.compactButton(context, "$start–$end") { selectRange(idx) }.apply {
                isSelected = active
                setTextColor(TvStyle.buttonTextColors(if (active) TvStyle.focus else TvStyle.text))
                if (idx == 0) {
                    nextFocusLeftId = leftTarget
                    setOnKeyListener { _, code, event ->
                        if (code == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                            onLeft(); true
                        } else false
                    }
                }
            }
            holder.rangeRow.addView(tab, LinearLayout.LayoutParams(-2, dp(38)).apply {
                if (idx > 0) leftMargin = dp(6)
            })
        }
    }

    private fun bindEpisode(holder: EpisodeHolder, localIndex: Int) {
        val episode = visibleEpisodes().getOrNull(localIndex) ?: return
        val isHighlighted = episode.id == playingVideoId ||
            (playingVideoId.isEmpty() && episode.id == resumeEntry?.videoId)
        holder.button.text = if (isHighlighted) "▶ ${episode.label}" else episode.label
        holder.button.isSelected = isHighlighted
        holder.button.setTextColor(TvStyle.buttonTextColors(if (isHighlighted) TvStyle.focus else TvStyle.text))
        holder.button.nextFocusLeftId = if (localIndex % SPAN_COUNT == 0) leftTarget else View.NO_ID
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is HeaderHolder) {
            posters.cancel(holder.image)
            holder.image.setImageDrawable(null)
            if (boundHeader === holder) boundHeader = null
        } else if (holder is RatingHolder && boundRating === holder) {
            boundRating = null
        }
    }

    private fun matchingResumeEpisode(): Episode? {
        val id = resumeEntry?.videoId ?: return null
        return detail.episodes.firstOrNull { it.id == id }
    }

    private fun primaryPlayEpisode(): Episode? = matchingResumeEpisode() ?: detail.episodes.firstOrNull()

    private fun formatMetadataLine(): String {
        val byKey = detail.metadata.toMap()
        val ordered = listOfNotNull(
            byKey["年份"]?.let { "${AppStrings.metadataLabel(traditional, "年份")} $it" },
            byKey["作品類型"]?.take(80),
            byKey["分級說明"]?.let { "${AppStrings.metadataLabel(traditional, "分級說明")} $it" }
        )
        return ordered.take(2).joinToString("  ·  ").take(200)
    }

    private fun buildFullIntro(): String = buildString {
        append(detail.description.ifBlank { tr("暂无简介", "暫無簡介") })
        if (detail.metadata.isNotEmpty()) {
            append("\n\n")
            detail.metadata.forEachIndexed { index, (rawLabel, value) ->
                if (index > 0) append("\n")
                append(AppStrings.metadataLabel(traditional, rawLabel))
                append("：")
                append(value)
            }
        }
    }

    private fun tr(simplified: String, traditionalText: String) =
        AppStrings.tr(traditional, simplified, traditionalText)

    private fun dp(value: Int) = TvStyle.dp(context, value)

    companion object {
        const val SPAN_COUNT = 6
        const val RANGE_SIZE = 30
        private const val TYPE_HEADER = 0
        private const val TYPE_RATING = 1
        private const val TYPE_EPISODE_HEADER = 2
        private const val TYPE_EPISODE = 3
    }
}
