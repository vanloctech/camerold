package vn.camerold.ui

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import vn.camerold.R

/** Light / Dark / System theme - chosen by the user. */
object ThemePref {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    val LABELS = linkedMapOf(SYSTEM to R.string.theme_system, LIGHT to R.string.theme_light, DARK to R.string.theme_dark)

    fun get(ctx: Context): String = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("theme", SYSTEM) ?: SYSTEM

    fun set(ctx: Context, v: String) {
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("theme", v).apply()
        apply(ctx)
    }

    /** Android 12+: the system applies it app-wide (including the embedded web page). */
    fun apply(ctx: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        val mode = when (get(ctx)) {
            LIGHT -> UiModeManager.MODE_NIGHT_NO
            DARK -> UiModeManager.MODE_NIGHT_YES
            else -> UiModeManager.MODE_NIGHT_AUTO
        }
        ctx.getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
    }

    /** Older Android: switch the light/dark configuration per screen manually. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 31) return base
        val night = when (get(base)) {
            LIGHT -> Configuration.UI_MODE_NIGHT_NO
            DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> return base
        }
        val conf = Configuration(base.resources.configuration)
        conf.uiMode = (conf.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(conf)
    }

    fun isDark(ctx: Context) =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

/**
 * Every screen extends this: applies the user's theme and language, and rebuilds itself when they were changed
 * on another screen (Android versions where the system doesn't do it for us).
 */
open class BaseActivity : Activity() {
    private var look = ""
    private fun currentLook() = ThemePref.get(this) + "|" + LangPref.get(this)

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(ThemePref.wrap(LangPref.wrap(newBase)))

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        look = currentLook()
    }

    override fun onResume() {
        super.onResume()
        if (currentLook() != look) recreate()
    }

    /** Top app bar from layout/topbar.xml: back arrow, title and an optional action icon. */
    protected fun topBar(title: Int, actionIcon: Int = 0, actionLabel: Int = 0, action: (() -> Unit)? = null) {
        findViewById<android.view.View>(R.id.barBack).setOnClickListener { finish() }
        findViewById<android.widget.TextView>(R.id.barTitle).setText(title)
        if (action != null) findViewById<android.widget.ImageButton>(R.id.barAction).apply {
            visibility = android.view.View.VISIBLE
            setImageResource(actionIcon)
            contentDescription = getString(actionLabel)
            setOnClickListener { action() }
        }
    }
}
