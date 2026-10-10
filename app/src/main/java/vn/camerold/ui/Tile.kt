package vn.camerold.ui

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import vn.camerold.R

/** A dashboard tile (layout/tile.xml): round icon, title, status; "on" fills it with the primary color. */
class Tile(val view: View) {
    private val icon: ImageView = view.findViewById(R.id.tileIcon)
    private val title: TextView = view.findViewById(R.id.tileTitle)
    private val status: TextView = view.findViewById(R.id.tileStatus)

    fun set(iconRes: Int, titleText: CharSequence, statusText: CharSequence, on: Boolean = false) {
        val c = view.context
        icon.setImageResource(iconRes)
        title.text = titleText
        status.text = statusText
        view.background = c.getDrawable(if (on) R.drawable.bg_tile_on else R.drawable.bg_tile)
        icon.background = c.getDrawable(if (on) R.drawable.bg_icon_circle_on else R.drawable.bg_icon_circle)
        val fg = c.getColor(if (on) R.color.on_accent else R.color.text)
        icon.imageTintList = ColorStateList.valueOf(fg)
        title.setTextColor(fg)
        status.setTextColor(if (on) fg else c.getColor(R.color.text2))
        status.alpha = if (on) 0.85f else 1f
        view.contentDescription = "$titleText, $statusText"
    }

    fun onClick(action: () -> Unit) = view.setOnClickListener { action() }

    var enabled: Boolean
        get() = view.isEnabled
        set(on) { view.isEnabled = on; view.alpha = if (on) 1f else 0.5f }
}
