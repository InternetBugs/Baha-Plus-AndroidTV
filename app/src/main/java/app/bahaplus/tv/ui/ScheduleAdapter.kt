package app.bahaplus.tv.ui

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
import app.bahaplus.tv.data.PosterLoader

/** One weekday's update list: time, artwork, title and next episode. */
class ScheduleAdapter(
    private val context: Context,
    private val items: List<Anime>,
    private val posters: PosterLoader,
    private val leftTarget: Int,
    private val timeFallback: String,
    private val onLeft: () -> Unit,
    private val onClick: (Anime) -> Unit
) : RecyclerView.Adapter<ScheduleAdapter.Holder>() {
    class Holder(val root: LinearLayout, val time: TextView, val image: ImageView,
                 val title: TextView, val subtitle: TextView) : RecyclerView.ViewHolder(root)

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
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(60)).apply { leftMargin = dp(10) }
        }
        val info = TvStyle.vertical(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) }
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
        root.addView(time)
        root.addView(image)
        root.addView(info)
        return Holder(root, time, image, title, subtitle)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val anime = items[position]
        val time = anime.updateTime ?: timeFallback
        holder.time.text = time
        holder.title.text = anime.title
        holder.subtitle.text = anime.episodeLabel
        holder.root.contentDescription = listOf(time, anime.title, anime.episodeLabel)
            .filter { it.isNotEmpty() }.joinToString(" ")
        holder.root.nextFocusLeftId = leftTarget
        holder.root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                onLeft()
                true
            } else false
        }
        holder.root.setOnClickListener { onClick(anime) }
        posters.load(holder.image, anime.cover)
    }

    override fun onViewRecycled(holder: Holder) {
        posters.cancel(holder.image)
        holder.image.setImageDrawable(null)
    }

    private fun dp(value: Int) = TvStyle.dp(context, value)
}
