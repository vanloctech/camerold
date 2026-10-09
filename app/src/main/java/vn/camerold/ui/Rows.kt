package vn.camerold.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.StringRes
import vn.camerold.R

/** Builds the grouped settings lists used by every screen: section label, card, rows, notes. */
class Rows(private val a: Activity) {

    class Row(val view: View, val title: TextView, val summary: TextView, val actions: LinearLayout, val divider: View? = null) {
        /** Shows/hides the row together with the line above it. */
        var visible: Boolean
            get() = view.visibility == View.VISIBLE
            set(on) { (if (on) View.VISIBLE else View.GONE).let { view.visibility = it; divider?.visibility = it } }
        fun value(s: CharSequence?) {
            summary.text = s
            summary.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        var enabled: Boolean
            get() = view.isEnabled
            set(on) { view.isEnabled = on; view.alpha = if (on) 1f else 0.45f; setChildrenEnabled(actions, on) }
    }

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    /** Section label + an empty card to put rows in. */
    fun section(parent: LinearLayout, @StringRes title: Int): LinearLayout {
        parent.addView(TextView(a, null, 0, R.style.SectionTitle).apply { setText(title) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(24); bottomMargin = dp(8)
            })
        return card(parent)
    }

    fun card(parent: LinearLayout): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = a.getDrawable(R.drawable.bg_card)
        clipToOutline = true
        parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Small explanation under a card. */
    fun note(parent: LinearLayout, text: CharSequence): TextView =
        TextView(a, null, 0, R.style.Note).apply { this.text = text }.also {
            parent.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) })
        }

    fun row(card: LinearLayout, icon: Int, @StringRes title: Int, onClick: (() -> Unit)? = null): Row {
        val line = if (card.childCount > 0) divider().also { card.addView(it) } else null
        val v = a.layoutInflater.inflate(R.layout.row_item, card, false)
        v.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        val r = Row(v, v.findViewById(R.id.title), v.findViewById(R.id.summary), v.findViewById(R.id.actions), line)
        r.title.setText(title)
        if (onClick != null) v.setOnClickListener { onClick() } else v.background = null
        card.addView(v)
        return r
    }

    fun switchRow(card: LinearLayout, icon: Int, @StringRes title: Int, checked: Boolean, onChange: (Boolean) -> Unit): Pair<Row, Switch> {
        lateinit var sw: Switch
        val r = row(card, icon, title) { sw.toggle() }
        sw = Switch(a).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        r.actions.addView(sw)
        return r to sw
    }

    /** Text field row. The row view is kept in the field's tag so callers can enable/disable it. */
    fun editRow(card: LinearLayout, icon: Int, @StringRes title: Int, hint: String, type: Int): EditText {
        if (card.childCount > 0) card.addView(divider())
        val v = a.layoutInflater.inflate(R.layout.row_edit, card, false)
        v.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        v.findViewById<TextView>(R.id.title).setText(title)
        val e = v.findViewById<EditText>(R.id.edit)
        e.hint = hint
        e.inputType = type
        e.tag = v
        card.addView(v)
        return e
    }

    fun iconAction(parent: LinearLayout, icon: Int, @StringRes desc: Int, onClick: () -> Unit): ImageView =
        ImageView(a).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(a.getColor(R.color.text2))
            contentDescription = a.getString(desc)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = a.getDrawable(R.drawable.bg_round_ripple)
            setOnClickListener { onClick() }
            parent.addView(this, LinearLayout.LayoutParams(dp(48), dp(48)))
        }

    private fun divider() = View(a).apply {
        setBackgroundColor(a.getColor(R.color.line))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(64) }
    }

    fun choose(@StringRes title: Int, items: List<String>, selected: Int, pick: (Int) -> Unit) {
        AlertDialog.Builder(a)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), selected) { d, i -> pick(i); d.dismiss() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        fun setChildrenEnabled(v: View, on: Boolean) {
            v.isEnabled = on
            if (v is ViewGroup) for (i in 0 until v.childCount) setChildrenEnabled(v.getChildAt(i), on)
        }
    }
}
