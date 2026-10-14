package vn.camerold.ui

import android.content.res.Configuration
import android.view.View

/** Layout help for tablets and unfolded foldables, so screens don't look like a stretched phone. */
object Wide {
    /** Room for the camera preview next to the controls instead of above them. */
    fun isTwoPane(c: Configuration) = c.screenWidthDp >= 720 && c.screenWidthDp > c.screenHeightDp

    /**
     * Keeps [v]'s content in a column at most [maxDp] wide, in the middle: its side padding grows on wide screens.
     * Follows size changes (rotating, folding, split screen).
     */
    fun centerColumn(v: View, maxDp: Int = 640) {
        val start = v.paddingStart
        val end = v.paddingEnd
        val max = (maxDp * v.resources.displayMetrics.density).toInt()
        v.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val extra = ((r - l - start - end - max) / 2).coerceAtLeast(0)
            if (v.paddingStart != start + extra) v.post { v.setPaddingRelative(start + extra, v.paddingTop, end + extra, v.paddingBottom) }
        }
    }
}
