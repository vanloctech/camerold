package vn.camerold.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import vn.camerold.R
import vn.camerold.alerts.BatteryAlerts

/** App-wide settings: appearance, language, battery alerts, about. Camera settings live on the camera screen. */
class SettingsActivity : BaseActivity() {
    private var alertRow: Rows.Row? = null

    /** Battery alerts row: what it does, or that notifications are blocked (tap the text to fix). */
    private fun showAlertState() {
        val r = alertRow ?: return
        val blocked = BatteryAlerts.isEnabled(this) && !BatteryAlerts.canNotify(this)
        r.value(getString(if (blocked) R.string.alerts_blocked else R.string.alerts_sub))
        r.summary.setTextColor(getColor(if (blocked) R.color.warn else R.color.text2))
        r.summary.setOnClickListener(if (!blocked) null else View.OnClickListener {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        })
    }

    private fun askNotify() {
        if (Build.VERSION.SDK_INT >= 33 && !BatteryAlerts.canNotify(this))
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) =
        showAlertState()

    override fun onResume() {
        super.onResume()
        showAlertState()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        topBar(R.string.settings_title)
        val content = findViewById<LinearLayout>(R.id.content)
        val rows = Rows(this)

        val general = rows.section(content, R.string.section_general)
        val themes = ThemePref.LABELS.keys.toList()
        fun themeName() = getString(ThemePref.LABELS.getValue(ThemePref.get(this)))
        lateinit var themeRow: Rows.Row
        themeRow = rows.row(general, R.drawable.ic_palette, R.string.theme_title) {
            rows.choose(R.string.theme_title, themes.map { getString(ThemePref.LABELS.getValue(it)) }, themes.indexOf(ThemePref.get(this))) {
                ThemePref.set(this, themes[it])
                themeRow.value(themeName()) // when the screen already looks like that (e.g. Dark -> System at night)
                lookChanged()
            }
        }.apply { value(themeName()) }

        val langs = listOf(LangPref.SYSTEM) + LangPref.LANGUAGES.keys
        fun langName(tag: String) = LangPref.LANGUAGES[tag] ?: getString(R.string.lang_system)
        lateinit var langRow: Rows.Row
        langRow = rows.row(general, R.drawable.ic_language, R.string.lang_title) {
            rows.choose(R.string.lang_title, langs.map(::langName), langs.indexOf(LangPref.get(this)).coerceAtLeast(0)) {
                LangPref.set(this, langs[it])
                langRow.value(langName(LangPref.get(this)))
                lookChanged()
            }
        }.apply { value(langName(LangPref.get(this@SettingsActivity))) }

        buildLock(rows, content)

        // Low-battery alerts for cameras watched from this phone (alerts/BatteryAlerts)
        val alerts = rows.section(content, R.string.section_alerts)
        val (alertRow, _) = rows.switchRow(alerts, R.drawable.ic_battery, R.string.alerts_title, BatteryAlerts.isEnabled(this)) { on ->
            BatteryAlerts.setEnabled(this, on)
            if (on) askNotify()
            showAlertState()
        }
        this.alertRow = alertRow
        rows.note(content, getString(R.string.alerts_note))
        showAlertState()

        // Only in the GitHub build: Google Play doesn't allow links to personal donation pages
        if (vn.camerold.BuildConfig.DONATION_LINKS) {
            val support = rows.section(content, R.string.section_support)
            fun open(url: String) = startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            rows.row(support, R.drawable.ic_heart, R.string.support_sponsors) { open(getString(R.string.url_sponsors)) }
                .value(getString(R.string.support_sponsors_sub))
            rows.row(support, R.drawable.ic_coffee, R.string.support_coffee) { open(getString(R.string.url_coffee)) }
                .value(getString(R.string.support_coffee_sub))
            rows.note(content, getString(R.string.support_note))
        }

        val about = rows.section(content, R.string.section_about)
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        rows.row(about, R.drawable.ic_info, R.string.about_version).value(version)
        rows.row(about, R.drawable.ic_doc, R.string.about_licenses) {
            AlertDialog.Builder(this)
                .setTitle(R.string.about_licenses)
                .setMessage(R.string.licenses_text)
                .setPositiveButton(R.string.close, null)
                .show()
        }.value(getString(R.string.about_licenses_sub))
        rows.row(about, R.drawable.ic_external, R.string.about_privacy) {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(getString(R.string.privacy_url))))
        }
        rows.note(content, getString(R.string.home_footer))
    }

    // ---------------- App lock ----------------

    private lateinit var lockRows: List<Rows.Row>
    private lateinit var bioRow: Rows.Row
    private lateinit var delayRow: Rows.Row
    private lateinit var lockSwitch: android.widget.Switch
    private var quiet = false

    private fun buildLock(rows: Rows, content: LinearLayout) {
        val sec = rows.section(content, R.string.section_security)
        val (lockRow, sw) = rows.switchRow(sec, R.drawable.ic_lock, R.string.lock_title, AppLock.isEnabled(this)) { on ->
            if (quiet) return@switchRow
            setSwitch(!on) // stays as it was until the PIN is set / confirmed
            if (on) pad(LockActivity.SET, REQ_ON) else verify(REQ_OFF)
        }
        lockRow.value(getString(R.string.lock_sub))
        lockSwitch = sw
        val change = rows.row(sec, R.drawable.ic_key, R.string.lock_change) {
            verify(REQ_CHANGE)
        }
        // Turning it on waits for one successful check: that proves it works on this phone
        val (bio, bioSw) = rows.switchRow(sec, R.drawable.ic_fingerprint, R.string.lock_bio, AppLock.bioOn(this)) { on ->
            if (quiet) return@switchRow
            if (on) { showLock(); AppLock.askBio(this, ok = { AppLock.setBio(this, true); showLock() }) }
            else AppLock.setBio(this, false)
        }
        bioRow = bio
        bioSwitch = bioSw
        bio.value(getString(R.string.lock_bio_sub))
        delayRow = rows.row(sec, R.drawable.ic_shield, R.string.lock_delay) {
            rows.choose(R.string.lock_delay, AppLock.DELAYS.map(::delayLabel), AppLock.DELAYS.indexOf(AppLock.delay(this))) {
                AppLock.setDelay(this, AppLock.DELAYS[it]); showLock()
            }
        }
        lockRows = listOf(change, bio, delayRow)
        rows.note(content, getString(R.string.lock_note))
        showLock()
    }

    private lateinit var bioSwitch: android.widget.Switch

    private fun showLock() {
        val on = AppLock.isEnabled(this)
        setSwitch(on)
        lockRows.forEach { it.visible = on }
        bioRow.visible = on && AppLock.bioAvailable(this)
        quiet = true; bioSwitch.isChecked = AppLock.bioOn(this); quiet = false
        delayRow.value(delayLabel(AppLock.delay(this)))
    }

    private fun setSwitch(on: Boolean) { quiet = true; lockSwitch.isChecked = on; quiet = false }

    private fun delayLabel(s: Int) = if (s == 0) getString(R.string.lock_delay_now)
        else resources.getQuantityString(R.plurals.lock_delay_n, s / 60, s / 60)

    private fun toast(res: Int) = android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show()

    private val REQ_ON = 11
    private val REQ_OFF = 12
    private val REQ_CHANGE = 13
    private val REQ_NEW = 14

    /** The PIN pad (LockActivity) to choose or check a PIN; the answer comes to [onActivityResult]. */
    private fun pad(mode: String, req: Int) =
        @Suppress("DEPRECATION") startActivityForResult(Intent(this, LockActivity::class.java).putExtra(LockActivity.MODE, mode), req)

    /** The current PIN, or fingerprint / face when that's on, before changing or removing it. */
    private fun verify(req: Int) {
        if (AppLock.bioOn(this) && AppLock.bioAvailable(this)) AppLock.askBio(this, ok = { verified(req) }, usePin = { pad(LockActivity.VERIFY, req) })
        else pad(LockActivity.VERIFY, req)
    }

    private fun verified(req: Int) = when (req) {
        REQ_OFF -> { AppLock.disable(this); toast(R.string.pin_off); showLock() }
        REQ_CHANGE -> pad(LockActivity.SET, REQ_NEW)
        else -> {}
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_ON -> { toast(R.string.pin_set); showLock() }
            REQ_NEW -> toast(R.string.pin_changed)
            REQ_OFF, REQ_CHANGE -> verified(requestCode)
        }
    }
}
