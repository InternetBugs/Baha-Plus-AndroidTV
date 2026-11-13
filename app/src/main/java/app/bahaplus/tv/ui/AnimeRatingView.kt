package app.bahaplus.tv.ui

import android.content.Context
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import app.bahaplus.tv.core.RatingSummary
import java.util.Locale

/** Static native views: no web content, animation, image requests, or focus stops. */
internal class AnimeRatingView(context: Context, private val traditional: Boolean) : LinearLayout(context) {
    constructor(context: Context) : this(context, false)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = TvStyle.shape(context, TvStyle.surface)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        minimumHeight = dp(116)
    }

    fun bind(score: Double?, rating: RatingSummary?, loading: Boolean) {
        removeAllViews()
        val average = rating?.average ?: score
        val summary = TvStyle.vertical(context).apply {
            addView(TvStyle.label(context, text("官方评分", "官方評分"), 12f, TvStyle.muted))
            addView(TvStyle.label(context,
                if (average == null || average == 0.0) "—" else String.format(Locale.US, "%.1f", average),
                40f, TvStyle.focus, true).apply { setPadding(0, dp(2), 0, dp(2)) })
            addView(TvStyle.label(context,
                if (rating == null) text("满分 5 分", "滿分 5 分")
                else text("${rating.count} 人评价", "${rating.count} 人評價"), 13f, TvStyle.muted))
        }
        addView(summary, LayoutParams(dp(120), -2).apply { rightMargin = dp(16) })
        val chart = TvStyle.vertical(context)
        addView(chart, LayoutParams(0, -2, 1f))
        if (rating == null) {
            chart.addView(TvStyle.label(context, if (loading) text("正在读取评分分布…", "正在讀取評分分布…")
                else text("评分分布暂不可用", "評分分布暫不可用"), 14f, TvStyle.muted))
            return
        }
        for (stars in 5 downTo 1) {
            val count = rating.distribution[stars - 1]
            val row = TvStyle.horizontal(context).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TvStyle.label(context, "★".repeat(stars), 13f, TvStyle.warning).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }, LayoutParams(dp(76), -2).apply { rightMargin = dp(10) })
            val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = false
                max = 10000
                progressDrawable = LayerDrawable(arrayOf(
                    TvStyle.shape(context, TvStyle.rail, radius = 3),
                    ClipDrawable(TvStyle.shape(context, TvStyle.focus, radius = 3), Gravity.START,
                        ClipDrawable.HORIZONTAL)
                )).apply {
                    setId(0, android.R.id.background)
                    setId(1, android.R.id.progress)
                }
                progress = if (rating.count == 0) 0 else (count.toLong() * max / rating.count).toInt()
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(progress, LayoutParams(0, dp(5), 1f))
            row.addView(TvStyle.label(context, count.toString(), 12f, TvStyle.muted).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }, LayoutParams(dp(62), -2).apply { leftMargin = dp(10) })
            row.contentDescription = text("$stars 星，$count 人", "$stars 星，$count 人")
            chart.addView(row, LayoutParams(-1, dp(18)))
        }
    }

    private fun dp(value: Int) = TvStyle.dp(context, value)
    private fun text(simplified: String, traditional: String) = if (this.traditional) traditional else simplified
}
