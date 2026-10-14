package vn.camerold.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import vn.camerold.R

/**
 * The PIN pad, for three jobs ([MODE]):
 *  - unlock: covers the app until the PIN (or a fingerprint / face) is given; Back leaves the app instead of getting past it.
 *  - verify: the current PIN before changing or removing it (settings); RESULT_OK when right.
 *  - set: a new 6-digit PIN, typed twice, then saved; RESULT_OK when done.
 */
class LockActivity : BaseActivity() {
    companion object {
        const val MODE = "mode"
        const val UNLOCK = "unlock"
        const val VERIFY = "verify"
        const val SET = "set"
        /** New PINs always have 6 digits. */
        const val LENGTH = 6
    }

    private val mode by lazy { intent.getStringExtra(MODE) ?: UNLOCK }
    private val typed = StringBuilder()
    /** Set mode: the PIN typed the first time, waiting to be typed again. */
    private var first: String? = null
    private lateinit var title: TextView
    private lateinit var dots: LinearLayout
    private lateinit var msg: TextView
    private var askedBio = false
    /** Wrong PIN / PINs don't match: shown until the next digit. */
    private var problem: String? = null
    private val bio by lazy { mode != SET && AppLock.bioOn(this) && AppLock.bioAvailable(this) }

    /** How many digits the PIN has. */
    private val len get() = if (mode == SET) LENGTH else AppLock.pinLength(this)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(getColor(R.color.bg))
        }
        root.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(64), dp(64)))
        title = TextView(this, null, 0, R.style.T_Title).apply { gravity = Gravity.CENTER }
        root.addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
        root.addView(dots, LinearLayout.LayoutParams(-2, dp(14)).apply { topMargin = dp(24) })
        msg = TextView(this, null, 0, R.style.T_Value).apply { gravity = Gravity.CENTER; minLines = 2 }
        root.addView(msg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        root.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        // Keypad: 1-9, then fingerprint/face (when on) · 0 · delete
        val key = dp(72)
        val pad = GridLayout(this).apply { columnCount = 3 }
        fun cell(v: View) = pad.addView(v, GridLayout.LayoutParams().apply { width = key; height = key; setMargins(dp(10), dp(6), dp(10), dp(6)) })
        for (n in 1..9) cell(digit(n.toString()))
        cell(if (bio) icon(R.drawable.ic_fingerprint, R.string.lock_bio) { askBio() } else View(this))
        cell(digit("0"))
        cell(icon(R.drawable.ic_delete, R.string.lock_delete) { if (typed.isNotEmpty()) { typed.setLength(typed.length - 1); show() } })
        root.addView(pad, LinearLayout.LayoutParams(-2, -2))
        // Unlocking: what to do without the PIN; choosing / checking one (from settings): a way back
        root.addView(TextView(this, null, 0, R.style.T_Value).apply {
            setText(if (mode == UNLOCK) R.string.lock_forgot else R.string.cancel)
            setTextColor(getColor(R.color.accent))
            setPadding(dp(16), dp(14), dp(16), dp(6))
            setOnClickListener { if (mode == UNLOCK) forgot() else cancel() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION")
            v.setPadding(dp(24), dp(48) + ins.systemWindowInsetTop, dp(24), dp(24) + ins.systemWindowInsetBottom)
            ins
        }
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { back() }
        show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = back()

    private fun back() { if (mode == UNLOCK) moveTaskToBack(true) else cancel() }

    private fun cancel() { setResult(Activity.RESULT_CANCELED); finish() }

    override fun onResume() {
        super.onResume()
        if (mode == UNLOCK && AppLock.unlocked) { finish(); return }
        // Fingerprint / face right away, once per showing
        if (!askedBio && bio) { askedBio = true; askBio() }
        show()
    }

    override fun onStop() {
        super.onStop()
        askedBio = false
    }

    private fun askBio() = AppLock.askBio(this, ok = { done() })

    private fun digit(n: String) = TextView(this).apply {
        text = n
        textSize = 28f
        gravity = Gravity.CENTER
        setTextColor(getColor(R.color.text))
        background = RippleDrawable(ColorStateList.valueOf(getColor(R.color.ripple)),
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.card)) }, null)
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (mode != SET && AppLock.waitSeconds(this@LockActivity) > 0) return@setOnClickListener
            if (typed.length >= len) return@setOnClickListener
            typed.append(n)
            problem = null
            show()
            // A moment to see the last dot filled
            if (typed.length == len) postDelayed({ if (mode == SET && first == null) next() else submit() }, 150)
        }
    }

    private fun icon(res: Int, label: Int, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(getColor(R.color.text2))
        contentDescription = getString(label)
        background = getDrawable(R.drawable.bg_round_ripple)
        setOnClickListener { onClick() }
    }

    private fun show() {
        title.setText(when {
            mode == UNLOCK -> R.string.lock_enter
            mode == VERIFY -> R.string.pin_current
            first == null -> R.string.pin_new
            else -> R.string.pin_again
        })
        val n = len
        while (dots.childCount < n) dots.addView(View(this), LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(8); marginEnd = dp(8) })
        while (dots.childCount > n) dots.removeViewAt(dots.childCount - 1)
        for (i in 0 until n) dots.getChildAt(i).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (i < typed.length) setColor(getColor(R.color.accent)) else setStroke(dp(2), getColor(R.color.text3))
        }
        val wait = if (mode == SET) 0 else AppLock.waitSeconds(this)
        msg.removeCallbacks(tick)
        msg.text = when {
            wait > 0 -> getString(R.string.pin_wait, wait).also { msg.postDelayed(tick, 1000) }
            problem != null -> problem
            mode == SET && first == null -> getString(R.string.pin_new_sub)
            else -> ""
        }
    }

    private val tick = Runnable { show() }

    /** Set mode: first PIN chosen, now typed again. */
    private fun next() {
        first = typed.toString()
        typed.setLength(0)
        problem = null
        show()
    }

    private fun submit() {
        val pin = typed.toString()
        typed.setLength(0)
        if (mode == SET) {
            if (pin == first) { AppLock.setPin(this, pin); done(); return }
            first = null
            wrong(getString(R.string.pin_mismatch))
            return
        }
        if (AppLock.check(this, pin)) { done(); return }
        wrong(getString(R.string.pin_wrong))
    }

    private fun wrong(text: String) {
        val d = resources.displayMetrics.density
        dots.animate().translationX(12 * d).setDuration(50).withEndAction {
            dots.animate().translationX(-12 * d).setDuration(80).withEndAction { dots.animate().translationX(0f).setDuration(50) }
        }
        dots.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
        problem = text
        show()
    }

    private fun done() {
        if (mode == UNLOCK) AppLock.unlockedNow()
        setResult(Activity.RESULT_OK)
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, android.R.anim.fade_out)
    }

    /** No way around it but starting over: the saved cameras go with the app's data. */
    private fun forgot() {
        AlertDialog.Builder(this)
            .setTitle(R.string.lock_forgot)
            .setMessage(R.string.lock_forgot_msg)
            .setPositiveButton(R.string.lock_open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
