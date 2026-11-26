package app.bahaplus.tv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.bahaplus.tv.core.Anime
import app.bahaplus.tv.core.AppStrings
import app.bahaplus.tv.core.UpdateSchedule
import app.bahaplus.tv.data.PosterLoader

/** One weekday's update list: time, artwork, title, episode, and optional status/favorite badges. */
class ScheduleAdapter(
    private val context: Context,
    initialItems: List<Anime>,
    private val posters: PosterLoader,
    private val leftTarget: Int,
    private val traditional: Boolean,
    private val timeFallback: String,
    private var favoriteIds: Set<String> = emptySet(),
    private var isToday: Boolean = false,
    private var nowTaipeiMinutes: Int = 0,
    private val onLeft: () -> Unit,
    private val onFocus: (Int, Anime) -> Unit = { _, _ -> },
    private val onClick: (Anime) -> Unit
) : RecyclerView.Adapter<ScheduleAdapter.Holder>() {
    private var items: List<Anime> = initialItems

    class Holder(
        val root: LinearLayout,
        val time: TextView,
        val image: ImageView,
        val title: TextView,
        val subtitle: TextView,
        val statusBadge: TextView,
        val favoriteBadge: TextView
    ) : RecyclerView.ViewHolder(root)

    @SuppressLint("NotifyDataSetChanged")
    fun submit(
        newItems: List<Anime>,
        newFavoriteIds: Set<String>,
        newIsToday: Boolean,
        newNowTaipeiMinutes: Int
    ) {
        items = newItems
        favoriteIds = newFavoriteIds
        isToday = newIsToday
        nowTaipeiMinutes = newNowTaipeiMinutes
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val root = TvStyle.horizontal(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            gravity = Gravity.CENTER_VERTICAL
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            background = TvStyle.focusBackground(context)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            layoutParams = RecyclerView.LayoutParams(-1, dp(76)).apply {
                setMargins(dp(4), dp(3), dp(4), dp(3))
            }
        }
        val time = TvStyle.label(context, "", 16f, TvStyle.focus, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(dp(62), -2)
        }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(TvStyle.rail)
            TvStyle.roundCorners(this, 5)
            layoutParams = LinearLayout.LayoutParams(dp(100), dp(58)).apply { leftMargin = dp(10) }
        }
        val info = TvStyle.vertical(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(14)
                rightMargin = dp(10)
            }
        }
        val title = TvStyle.label(context, "", 17f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subtitle = TvStyle.label(context, "", 13f, TvStyle.muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(5), 0, 0)
        }
        info.addView(title)
        info.addView(subtitle)

        val statusBadge = TvStyle.badge(context, "", TvStyle.muted, TvStyle.subtleBadgeFill).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) }
        }
        val favoriteBadge = TvStyle.badge(
            context,
            AppStrings.tr(traditional, "已追番", "已追番"),
            TvStyle.focus,
            TvStyle.badgeFill
        ).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) }
        }

        root.addView(time)
        root.addView(image)
        root.addView(info)
        root.addView(statusBadge)
        root.addView(favoriteBadge)
        val holder = Holder(root, time, image, title, subtitle, statusBadge, favoriteBadge)
        root.nextFocusLeftId = leftTarget
        root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                onLeft()
                true
            } else false
        }
        root.setOnFocusChangeListener { _, focused ->
            val index = holder.bindingAdapterPosition
            if (focused && index in items.indices) onFocus(index, items[index])
        }
        root.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index in items.indices) onClick(items[index])
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val anime = items[position]
        val time = anime.updateTime ?: timeFallback
        holder.time.text = time
        holder.title.text = anime.title
        holder.subtitle.text = anime.episodeLabel
        val broadcastMinutes = if (isToday) UpdateSchedule.parseMinutes(anime.updateTime) else null
        if (broadcastMinutes != null) {
            val aired = broadcastMinutes <= nowTaipeiMinutes
            holder.statusBadge.text = if (aired) AppStrings.tr(traditional, "已更新", "已更新")
                else AppStrings.tr(traditional, "待更新", "待更新")
            holder.statusBadge.setTextColor(if (aired) TvStyle.muted else TvStyle.warning)
            holder.statusBadge.visibility = View.VISIBLE
        } else {
            holder.statusBadge.visibility = View.GONE
        }
        val favorited = anime.id in favoriteIds
        holder.favoriteBadge.visibility = if (favorited) View.VISIBLE else View.GONE
        holder.root.contentDescription = listOfNotNull(
            time,
            anime.title,
            anime.episodeLabel.takeIf(String::isNotEmpty),
            if (favorited) AppStrings.tr(traditional, "已追番", "已追番") else null
        ).joinToString(" ")
        posters.load(holder.image, anime.cover)
    }

    override fun onViewRecycled(holder: Holder) {
        posters.cancel(holder.image)
        holder.image.setImageDrawable(null)
    }

    private fun dp(value: Int) = TvStyle.dp(context, value)
}
