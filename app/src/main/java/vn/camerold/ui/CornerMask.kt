package vn.camerold.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import vn.camerold.R

/**
 * Rounds the corners of the preview. SurfaceView can't be clipped to an outline,
 * so the corners are painted over with the page background color.
 */
class CornerMask(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.bg) }
    private val path = Path()
    private val r = resources.getDimension(R.dimen.card_radius)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
        path.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), floatArrayOf(r, r, r, r, r, r, r, r), Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) = canvas.drawPath(path, paint)
}
