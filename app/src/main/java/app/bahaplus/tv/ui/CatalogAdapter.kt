package app.bahaplus.tv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.bahaplus.tv.core.Anime
import app.bahaplus.tv.core.AppStrings
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
    initialRows: List<CatalogRow>,
    private val posters: PosterLoader,
    private val leftTarget: Int,
    private val traditional: Boolean,
    private var lowResources: Boolean,
    private val onLeft: () -> Unit,
    private val onFocus: (Int, CatalogRow.Card) -> Unit,
    private val onClick: (CatalogRow.Card) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var rows: List<CatalogRow> = initialRows
    private var columns: IntArray = computeColumns(initialRows)
    private val spanLookup = object : GridLayoutManager.SpanSizeLookup() {
        init {
            isSpanIndexCacheEnabled = true
            isSpanGroupIndexCacheEnabled = true
        }
        override fun getSpanSize(position: Int) = if (rows.getOrNull(position) is CatalogRow.Heading) 4 else 1
    }

    class HeadingHolder(val label: TextView) : RecyclerView.ViewHolder(label)
    class CardHolder(
        val root: LinearLayout,
        val image: ImageView,
        val title: TextView,
        val subtitle: TextView,
        val progress: ProgressBar
    ) : RecyclerView.ViewHolder(root)

    val currentRows: List<CatalogRow> get() = rows

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newRows: List<CatalogRow>, lowResources: Boolean = this.lowResources) {
        this.lowResources = lowResources
        if (rows == newRows) return
        rows = newRows
        columns = computeColumns(newRows)
        spanLookup.invalidateSpanIndexCache()
        spanLookup.invalidateSpanGroupIndexCache()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = if (rows[position] is CatalogRow.Heading) 0 else 1
    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 0) return HeadingHolder(TvStyle.label(context, "", 19f, bold = true).apply {
            setPadding(dp(4), dp(8), 0, dp(8))
            layoutParams = RecyclerView.LayoutParams(-1, -2)
        })
        val root = TvStyle.vertical(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            background = TvStyle.focusBackground(context)
            setPadding(dp(7), dp(7), dp(7), dp(9))
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                setMargins(dp(5), dp(4), dp(5), dp(8))
            }
        }
        val mediaBox = FrameLayout(context).apply {
            setBackgroundColor(TvStyle.rail)
            TvStyle.roundCorners(this, 5)
            layoutParams = LinearLayout.LayoutParams(-1, dp(104))
        }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            progressDrawable = LayerDrawable(arrayOf(
                TvStyle.shape(context, Color.argb(170, 12, 20, 28), radius = 0),
                ClipDrawable(TvStyle.shape(context, TvStyle.focus, radius = 0), Gravity.START, ClipDrawable.HORIZONTAL)
            )).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
            visibility = View.GONE
        }
        mediaBox.addView(image, FrameLayout.LayoutParams(-1, -1))
        mediaBox.addView(progress, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM))
        val title = TvStyle.label(context, "", 15f, bold = true).apply {
            maxLines = 2
            minLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(7), 0, dp(3))
        }
        val subtitle = TvStyle.label(context, "", 13f, TvStyle.muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(mediaBox)
        root.addView(title)
        root.addView(subtitle)
        val holder = CardHolder(root, image, title, subtitle, progress)
        root.setOnKeyListener { _, keyCode, event ->
            val index = holder.bindingAdapterPosition
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN &&
                index in rows.indices && columns[index] == 0) {
                onLeft()
                true
            } else false
        }
        root.setOnFocusChangeListener { view, focused ->
            TvStyle.focusScale(view, focused, lowResources)
            val index = holder.bindingAdapterPosition
            if (focused && index in rows.indices) {
                (rows[index] as? CatalogRow.Card)?.let { onFocus(index, it) }
            }
        }
        root.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index in rows.indices) {
                (rows[index] as? CatalogRow.Card)?.let(onClick)
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        if (row is CatalogRow.Heading && holder is HeadingHolder) {
            holder.label.text = row.text
        } else if (row is CatalogRow.Card && holder is CardHolder) {
            holder.title.text = row.anime.title
            holder.subtitle.text = row.history?.let {
                val localizedProgress = AppStrings.progressLabel(traditional, it.progressLabel)
                listOf(it.label, localizedProgress).filter(String::isNotBlank).joinToString("  ")
            } ?: row.anime.episodeLabel
            val watchProgress = row.history?.progress
            holder.progress.visibility = if (watchProgress != null) View.VISIBLE else View.GONE
            holder.progress.progress = ((watchProgress ?: 0.0) * 1000).toInt()
            holder.root.contentDescription = "${row.anime.title} ${holder.subtitle.text}"
            holder.root.nextFocusLeftId = if (columns[position] == 0) leftTarget else View.NO_ID
            holder.root.scaleX = 1f
            holder.root.scaleY = 1f
            posters.load(holder.image, row.anime.cover)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is CardHolder) {
            holder.root.animate().cancel()
            holder.root.scaleX = 1f
            holder.root.scaleY = 1f
            posters.cancel(holder.image)
            holder.image.setImageDrawable(null)
        }
    }

    fun spans(): GridLayoutManager.SpanSizeLookup = spanLookup

    private fun dp(value: Int) = TvStyle.dp(context, value)

    companion object {
        private fun computeColumns(rows: List<CatalogRow>): IntArray = IntArray(rows.size).also { values ->
            var column = 0
            rows.forEachIndexed { index, row ->
                if (row is CatalogRow.Heading) column = 0
                else {
                    values[index] = column
                    column = (column + 1) % 4
                }
            }
        }
    }
}
