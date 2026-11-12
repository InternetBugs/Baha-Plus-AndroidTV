package app.bahaplus.tv.ui

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.bahaplus.tv.core.Anime
import app.bahaplus.tv.core.WatchEntry
import app.bahaplus.tv.data.PosterLoader

sealed class CatalogRow {
    data class Heading(val text: String) : CatalogRow()
    data class Card(val anime: Anime, val history: WatchEntry? = null) : CatalogRow() {
        val key get() = if (history == null) "anime:${anime.id}" else "history:${anime.id}"
    }
}

class CatalogAdapter(
    private val context: Context,
    val rows: List<CatalogRow>,
    private val posters: PosterLoader,
    private val leftTarget: Int,
    private val onLeft: () -> Unit,
    private val onFocus: (Int, CatalogRow.Card) -> Unit,
    private val onClick: (CatalogRow.Card) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    class HeadingHolder(val label: android.widget.TextView) : RecyclerView.ViewHolder(label)
    class CardHolder(val root: LinearLayout, val image: ImageView, val title: android.widget.TextView,
                     val subtitle: android.widget.TextView, val progress: ProgressBar) : RecyclerView.ViewHolder(root)
    override fun getItemViewType(position: Int) = if (rows[position] is CatalogRow.Heading) 0 else 1
    override fun getItemCount() = rows.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 0) return HeadingHolder(TvStyle.label(context, "", 21f, bold = true).apply {
            setPadding(TvStyle.dp(context, 4), TvStyle.dp(context, 14), 0, TvStyle.dp(context, 12))
            layoutParams = RecyclerView.LayoutParams(-1, -2)
        })
        val root = TvStyle.vertical(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            background = TvStyle.focusBackground(context)
            setPadding(TvStyle.dp(context, 7), TvStyle.dp(context, 7), TvStyle.dp(context, 7), TvStyle.dp(context, 9))
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                setMargins(TvStyle.dp(context, 5), TvStyle.dp(context, 4), TvStyle.dp(context, 5), TvStyle.dp(context, 8))
            }
        }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(TvStyle.rail)
            layoutParams = LinearLayout.LayoutParams(-1, TvStyle.dp(context, 96))
        }
        val title = TvStyle.label(context, "", 16f, bold = true).apply {
            maxLines = 2
            minLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, TvStyle.dp(context, 7), 0, TvStyle.dp(context, 3))
        }
        val subtitle = TvStyle.label(context, "", 13f, TvStyle.muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(TvStyle.focus)
            layoutParams = LinearLayout.LayoutParams(-1, TvStyle.dp(context, 3)).apply { topMargin = TvStyle.dp(context, 7) }
        }
        root.addView(image)
        root.addView(title)
        root.addView(subtitle)
        root.addView(progress)
        return CardHolder(root, image, title, subtitle, progress)
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        if (row is CatalogRow.Heading && holder is HeadingHolder) {
            holder.label.text = row.text
        } else if (row is CatalogRow.Card && holder is CardHolder) {
            holder.title.text = row.anime.title
            holder.subtitle.text = row.history?.let { "${it.label}  ${it.progressLabel}" } ?: row.anime.episodeLabel
            holder.progress.visibility = if (row.history?.progress != null) View.VISIBLE else View.INVISIBLE
            holder.progress.progress = ((row.history?.progress ?: 0.0) * 1000).toInt()
            holder.root.contentDescription = "${row.anime.title} ${holder.subtitle.text}"
            holder.root.nextFocusLeftId = if (column(position) == 0) leftTarget else View.NO_ID
            holder.root.setOnKeyListener { _, keyCode, event ->
                val index = holder.bindingAdapterPosition
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN &&
                    index != RecyclerView.NO_POSITION && column(index) == 0) {
                    onLeft()
                    true
                } else false
            }
            holder.root.setOnFocusChangeListener { _, focused ->
                val index = holder.bindingAdapterPosition
                if (focused && index != RecyclerView.NO_POSITION) onFocus(index, row)
            }
            holder.root.setOnClickListener { onClick(row) }
            posters.load(holder.image, row.anime.cover)
        }
    }
    private fun column(position: Int): Int {
        var count = 0
        for (index in 0 until position) {
            if (rows[index] is CatalogRow.Heading) count = 0 else count++
        }
        return count % 4
    }
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is CardHolder) {
            posters.cancel(holder.image)
            holder.image.setImageDrawable(null)
        }
    }
    fun spans() = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int) = if (rows[position] is CatalogRow.Heading) 4 else 1
    }
}
