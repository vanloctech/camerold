package vn.camerold

import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import vn.camerold.ui.ThemePref

/** Records app crashes so the next launch can show them for the user to report. */
class App : Application() {
    // Notifications and other non-screen text follow the in-app language too (Android 12 and older)
    override fun attachBaseContext(base: Context) = super.attachBaseContext(vn.camerold.ui.LangPref.wrap(base))

    override fun onCreate() {
        super.onCreate()
        ThemePref.apply(this)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "crash.txt").writeText("Thread: ${t.name}\n${Log.getStackTraceString(e)}")
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        /** Shows a dialog if the previous run crashed (Java or native). */
        fun showLastCrash(ctx: Context) {
            val sp = ctx.getSharedPreferences("crash", Context.MODE_PRIVATE)
            val sb = StringBuilder()
            var ts = 0L
            if (Build.VERSION.SDK_INT >= 30) {
                val am = ctx.getSystemService(ActivityManager::class.java)
                am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull()?.let { info ->
                    val bad = info.reason in listOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                        ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)
                    if (bad && info.timestamp > sp.getLong("shown", 0)) {
                        ts = info.timestamp
                        sb.append("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp))}\n")
                        sb.append("Reason: ${info.reason} ${info.description ?: ""}\n")
                        if (info.reason == ApplicationExitInfo.REASON_ANR || info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                            try {
                                info.traceInputStream?.use { s ->
                                    val raw = s.readBytes()
                                    // Native crash: decode the tombstone (crashed thread + last log lines); fall back to its readable strings
                                    val text = if (info.reason == ApplicationExitInfo.REASON_ANR) String(raw)
                                    else Tombstone.decode(raw)
                                        ?: Regex("[\\x20-\\x7e]{6,}").findAll(String(raw, Charsets.ISO_8859_1)).map { it.value }.take(80).joinToString("\n")
                                    sb.append(text.take(12000)).append('\n')
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
            val f = File(ctx.filesDir, "crash.txt")
            if (f.exists()) {
                sb.append(f.readText().take(6000))
                f.delete()
                if (ts == 0L) ts = System.currentTimeMillis()
            }
            if (sb.isEmpty()) return
            sp.edit().putLong("shown", ts).apply()
            val text = "Camerold ${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName} / ${Build.MODEL} / Android ${Build.VERSION.RELEASE}\n$sb"
            AlertDialog.Builder(ctx)
                .setTitle(R.string.crash_title)
                .setMessage(text.take(3000))
                .setPositiveButton(R.string.crash_copy) { _, _ ->
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("crash", text))
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }
}
