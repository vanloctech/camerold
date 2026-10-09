package vn.camerold.ui

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * App language chosen by the user: "" = follow the phone, or a language tag ("en", "vi").
 * Android 13+: the system stores and applies it (also shown in Settings › Apps › Language).
 * Older Android: stored here and applied to each screen through [wrap].
 */
object LangPref {
    const val SYSTEM = ""
    /**
     * Available languages (tag -> name written in that language, e.g. "Deutsch", "Tiếng Việt"), sorted by name.
     * The list comes from the translations in res/values-<lang>, collected at build time (BuildConfig.LOCALES).
     */
    val LANGUAGES: Map<String, String> by lazy {
        val collator = java.text.Collator.getInstance()
        vn.camerold.BuildConfig.LOCALES.associateWith { tag ->
            val l = Locale.forLanguageTag(tag)
            l.getDisplayName(l).replaceFirstChar { it.titlecase(l) }
        }.toList().sortedWith { a, b -> collator.compare(a.second, b.second) }.toMap()
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun get(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val l = ctx.getSystemService(LocaleManager::class.java).applicationLocales
            return if (l.isEmpty) SYSTEM else l[0].toLanguageTag()
        }
        return sp(ctx).getString("lang", SYSTEM) ?: SYSTEM
    }

    fun set(ctx: Context, tag: String) {
        sp(ctx).edit().putString("lang", tag).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        }
    }

    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("lang", SYSTEM) ?: SYSTEM
        if (tag.isEmpty()) return base
        val conf = Configuration(base.resources.configuration)
        conf.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(conf)
    }
}
