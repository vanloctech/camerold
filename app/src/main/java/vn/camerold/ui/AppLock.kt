package vn.camerold.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import vn.camerold.R

/**
 * Optional app lock: a 6-digit PIN (stored only as a salted PBKDF2 hash), and the phone's fingerprint or face
 * instead of typing it when the user wants. Locks when the app is opened and when it comes back after being away
 * longer than the chosen delay; [LockActivity] then covers whatever screen was showing. The camera keeps streaming
 * meanwhile: only the screens are locked.
 */
object AppLock {
    private const val PREFS = "lock"
    private const val ITERATIONS = 60_000
    /** Away this long (seconds), then the PIN is asked again. */
    val DELAYS = listOf(0, 60, 300)
    private const val FREE_TRIES = 5

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context) = sp(c).contains("hash")
    fun pinLength(c: Context) = sp(c).getInt("len", 4)
    fun bioOn(c: Context) = sp(c).getBoolean("bio", false)
    fun setBio(c: Context, on: Boolean) = sp(c).edit().putBoolean("bio", on).apply()
    fun delay(c: Context) = sp(c).getInt("delay", 0).takeIf { it in DELAYS } ?: 0
    fun setDelay(c: Context, s: Int) = sp(c).edit().putInt("delay", s).apply()

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)).encoded

    /** Turns the lock on, or changes the PIN. */
    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp(c).edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .putInt("len", pin.length).putInt("fails", 0).putLong("waitUntil", 0).apply()
        unlocked = true
    }

    fun disable(c: Context) = sp(c).edit().clear().apply()

    /** Seconds to wait before the next try (after too many wrong ones), 0 = may try now. */
    fun waitSeconds(c: Context): Int {
        val until = sp(c).getLong("waitUntil", 0)
        val now = System.currentTimeMillis()
        return if (until > now) ((until - now + 999) / 1000).toInt() else 0
    }

    /** True if right. Wrong ones past the first few make the next try wait longer and longer. */
    fun check(c: Context, pin: String): Boolean {
        if (waitSeconds(c) > 0) return false
        val p = sp(c)
        val salt = Base64.decode(p.getString("salt", "") ?: "", Base64.NO_WRAP)
        val want = Base64.decode(p.getString("hash", "") ?: "", Base64.NO_WRAP)
        val ok = want.isNotEmpty() && MessageDigest.isEqual(hash(pin, salt), want)
        if (ok) { p.edit().putInt("fails", 0).putLong("waitUntil", 0).apply(); return true }
        val fails = p.getInt("fails", 0) + 1
        val wait = if (fails < FREE_TRIES) 0L else 30_000L shl minOf(fails - FREE_TRIES, 5) // 30 s … 16 min
        p.edit().putInt("fails", fails).putLong("waitUntil", if (wait > 0) System.currentTimeMillis() + wait else 0).apply()
        return false
    }

    // ---------------- Fingerprint / face ----------------

    /** Class 2 ("weak") biometrics count too: on many phones that's what face unlock is. */
    private const val KINDS = BiometricManager.Authenticators.BIOMETRIC_WEAK

    /** The phone can check a fingerprint or face, and one is set up (Android 10 and newer; older ones use the PIN). */
    fun bioAvailable(c: Context): Boolean = try {
        if (Build.VERSION.SDK_INT < 29) false
        else {
            val bm = c.getSystemService(BiometricManager::class.java)
            if (Build.VERSION.SDK_INT >= 30) bm.canAuthenticate(KINDS) == BiometricManager.BIOMETRIC_SUCCESS
            else @Suppress("DEPRECATION") (bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS)
        }
    } catch (_: Exception) { false }

    /** Shows the system's fingerprint / face sheet; [ok] on success, [usePin] when the user would rather type. */
    fun askBio(a: Activity, ok: () -> Unit, usePin: () -> Unit = {}) {
        if (Build.VERSION.SDK_INT < 29) return
        val b = BiometricPrompt.Builder(a).setTitle(a.getString(R.string.bio_title))
        if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(KINDS)
        b.setNegativeButton(a.getString(R.string.bio_use_pin), a.mainExecutor) { _, _ -> usePin() }
        try {
            b.build().authenticate(CancellationSignal(), a.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = ok()
            })
        } catch (_: Exception) {}
    }

    // ---------------- When to lock ----------------

    @Volatile var unlocked = false
        private set
    private var started = 0
    private var leftAt = 0L

    fun unlockedNow() { unlocked = true }

    /** Watches every screen of the app: locks after it has been away, covers a screen that opens while locked. */
    fun install(app: Application) = app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(a: Activity) {
            if (started++ == 0 && unlocked && leftAt > 0 && SystemClock.elapsedRealtime() - leftAt > delay(a) * 1000L) unlocked = false
            if (a !is LockActivity && !unlocked && isEnabled(a)) {
                a.startActivity(Intent(a, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
        }
        override fun onActivityStopped(a: Activity) {
            // Turning the screen (the same screen made again) isn't leaving
            if (--started == 0 && !a.isChangingConfigurations) leftAt = SystemClock.elapsedRealtime()
            if (started == 0 && a.isChangingConfigurations) leftAt = 0
        }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    })
}
