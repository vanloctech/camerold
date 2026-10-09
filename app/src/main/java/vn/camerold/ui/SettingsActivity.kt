package vn.camerold.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.LinearLayout
import vn.camerold.R

/** App-wide settings: appearance, language, about. Camera settings live on the camera screen. */
class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        topBar(R.string.settings_title)
        val content = findViewById<LinearLayout>(R.id.content)
        val rows = Rows(this)

        val general = rows.section(content, R.string.section_general)
        val themes = ThemePref.LABELS.keys.toList()
        rows.row(general, R.drawable.ic_palette, R.string.theme_title) {
            rows.choose(R.string.theme_title, themes.map { getString(ThemePref.LABELS.getValue(it)) }, themes.indexOf(ThemePref.get(this))) {
                ThemePref.set(this, themes[it])
                recreate()
            }
        }.value(getString(ThemePref.LABELS.getValue(ThemePref.get(this))))

        val langs = listOf(LangPref.SYSTEM) + LangPref.LANGUAGES.keys
        fun langName(tag: String) = LangPref.LANGUAGES[tag] ?: getString(R.string.lang_system)
        rows.row(general, R.drawable.ic_language, R.string.lang_title) {
            rows.choose(R.string.lang_title, langs.map(::langName), langs.indexOf(LangPref.get(this)).coerceAtLeast(0)) {
                LangPref.set(this, langs[it])
                recreate()
            }
        }.value(langName(LangPref.get(this)))

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
        rows.note(content, getString(R.string.home_footer))
    }
}
