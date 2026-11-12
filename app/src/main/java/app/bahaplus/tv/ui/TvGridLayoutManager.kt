package app.bahaplus.tv.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Avoid running a smooth-scroll animation for every remote focus move in low mode. */
class TvGridLayoutManager(context: Context, spans: Int, private val lowResources: Boolean) :
    GridLayoutManager(context, spans) {
    override fun requestChildRectangleOnScreen(
        parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean
    ): Boolean = super.requestChildRectangleOnScreen(
        parent, child, rect, immediate || lowResources, focusedChildVisible)
}
