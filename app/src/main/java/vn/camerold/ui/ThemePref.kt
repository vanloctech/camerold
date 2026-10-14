package vn.camerold.ui

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
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
 * Every screen extends this: applies the user's theme and language, and rebuilds itself when they change.
 *
 * Screens that declare configChanges="uiMode|locale|layoutDirection" (the plain ones: home, settings) get the change
 * here instead of being torn down by the system, and rebuild once with a quick fade ([restyle]) rather than a blink.
 * Screens behind it rebuild out of sight. The camera and viewer screens keep the system's own handling.
 */
open class BaseActivity : Activity() {
    private var look = ""
    private var resumed = false

    /** What the screen was drawn with: the light/dark mode and the language actually in use. */
    private fun lookOf(c: Configuration): String {
        val theme = ThemePref.get(this)
        val lang = LangPref.get(this)
        // Android 12+ / 13+ apply the choice to the configuration; older versions only through attachBaseContext
        val night = if (Build.VERSION.SDK_INT >= 31 || theme == ThemePref.SYSTEM) "n" + (c.uiMode and Configuration.UI_MODE_NIGHT_MASK) else theme
        val locale = if (Build.VERSION.SDK_INT >= 33 || lang == LangPref.SYSTEM) c.locales.toLanguageTags() else lang
        return "$night|$locale"
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(ThemePref.wrap(LangPref.wrap(newBase)))

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        look = lookOf(resources.configuration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isFinishing || lookOf(newConfig) == look) return // e.g. the screen was rotated
        if (resumed) restyle() else recreate()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // Changed while this screen was in the background (older Android: nothing tells us)
        if (lookOf(resources.configuration) != look) recreate()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    /** Rebuilds this screen in the new theme or language: the new one fades in over the old one. */
    protected fun restyle() {
        if (isFinishing) return
        look = lookOf(resources.configuration)
        // Same screen and extras, in this task. Without clearing the flags, the launcher's NEW_TASK would only bring
        // the task forward, and finish() would then close the app.
        startActivity(Intent(intent).setFlags(0))
        @Suppress("DEPRECATION") overridePendingTransition(R.anim.restyle_in, R.anim.restyle_hold)
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(R.anim.restyle_in, R.anim.restyle_hold)
    }

    /**
     * After the user picked a theme or language. Where the system applies it, [onConfigurationChanged] follows;
     * older Android doesn't announce it, so the screen is rebuilt right away.
     */
    protected fun lookChanged() {
        if (lookOf(resources.configuration) != look) restyle()
    }

    /**
     * From Android 15 apps are drawn edge to edge (behind the status and navigation bars). Pad the content by the bars,
     * the display cutout and the keyboard so nothing hides behind them. The window background shows through the bars.
     * Hidden bars (full screen) report no inset, so full-screen content still uses the whole screen.
     * The insets are consumed here: otherwise a WebView inside would add them again (CSS env(safe-area-inset-*)),
     * leaving an empty band under the status bar.
     */
    override fun onPostCreate(savedInstanceState: android.os.Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Settings lists (layout/activity_list): a readable column on tablets
        findViewById<android.view.View>(R.id.content)?.let { Wide.centerColumn(it) }
        if (android.os.Build.VERSION.SDK_INT < 35) return
        val root = findViewById<android.view.View>(android.R.id.content)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val t = android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout() or
                android.view.WindowInsets.Type.ime()
            val b = insets.getInsets(t)
            v.setPadding(b.left, b.top, b.right, b.bottom)
            android.view.WindowInsets.CONSUMED
        }
        root.requestApplyInsets()
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
