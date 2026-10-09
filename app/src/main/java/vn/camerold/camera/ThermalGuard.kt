package vn.camerold.camera

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import vn.camerold.signaling.every
import vn.camerold.signaling.post

/**
 * Watches how hot the phone is. An old phone streaming all day on a charger heats up, and Android then
 * throttles or closes the camera. Lowering the load early (frame rate, resolution, bitrate) keeps it running.
 *
 * Level 0 normal · 1 warm · 2 hot · 3 very hot. It rises at once and falls one step at a time, only after
 * the phone has stayed cooler for a few minutes, so the quality doesn't keep flipping back and forth.
 * Sources: mainly the battery temperature (works everywhere), plus Android's own SEVERE/CRITICAL alarms.
 * Android's "moderate" status and its thermal forecast are deliberately ignored: on phones like the Pixel they
 * trigger during any long camera use, while the phone is barely warm to the touch.
 */
class ThermalGuard(
    private val ctx: Context,
    private val exec: ScheduledExecutorService,
    private val onChange: (Int) -> Unit,
) {
    companion object {
        private const val TAG = "ThermalGuard"
        private const val POLL_S = 20L
        private const val COOL_TICKS = 9 // ~3 minutes cooler before stepping down one level
        private const val HYSTERESIS_C = 1.5f
        /** Default start of protection. A battery around 40 °C feels only warm; phones stop charging and start to
         *  throttle in the mid 40s, and ~50 °C is where cameras get shut down. Users can change it (Camera settings). */
        const val DEFAULT_START_C = 44
        const val MIN_START_C = 38
        const val MAX_START_C = 52
        private const val STEP_C = 3f

        /** Battery °C -> level: [start] warm, +3 °C hot, +6 °C very hot (44 / 47 / 50 by default). */
        fun batteryLevel(c: Float, start: Float = DEFAULT_START_C.toFloat()) = when {
            c >= start + 2 * STEP_C -> 3
            c >= start + STEP_C -> 2
            c >= start -> 1
            else -> 0
        }

        /** Only Android's real alarms: SEVERE (it's throttling hard) and CRITICAL or worse (about to shut things down). */
        fun statusLevel(status: Int) = when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> 3
            status >= PowerManager.THERMAL_STATUS_SEVERE -> 2
            else -> 0
        }
    }

    @Volatile var level = 0; private set
    /** User settings: protection on/off and the battery temperature where it starts. */
    @Volatile private var enabled = true
    @Volatile private var startC = DEFAULT_START_C.toFloat()

    /** Apply new settings right away (turning it off returns to full quality at once). */
    fun configure(on: Boolean, start: Int) = exec.post {
        enabled = on
        startC = start.coerceIn(MIN_START_C, MAX_START_C).toFloat()
        coolTicks = 0
        if (!on && level != 0) { level = 0; onChange(0) } else check()
    }
    @Volatile var batteryTemp = Float.NaN; private set
    private var coolTicks = 0
    private var task: ScheduledFuture<*>? = null
    private val pm = ctx.getSystemService(PowerManager::class.java)
    // The system calls listeners on this executor; route through post() so a late call after stop is dropped
    private val onExec = Executor { r -> exec.post { r.run() } }
    private val statusListener: Any? =
        if (Build.VERSION.SDK_INT >= 29) PowerManager.OnThermalStatusChangedListener { check() } else null

    fun start() {
        if (Build.VERSION.SDK_INT >= 29) try {
            pm.addThermalStatusListener(onExec, statusListener as PowerManager.OnThermalStatusChangedListener)
        } catch (e: Exception) { Log.w(TAG, "thermal listener", e) }
        task = exec.every({ check() }, POLL_S, TimeUnit.SECONDS)
        exec.post { check() }
    }

    fun stop() {
        task?.cancel(false)
        if (Build.VERSION.SDK_INT >= 29) try {
            pm.removeThermalStatusListener(statusListener as PowerManager.OnThermalStatusChangedListener)
        } catch (_: Exception) {}
    }

    private fun readBattery(): Float {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return Float.NaN
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (t == Int.MIN_VALUE) Float.NaN else t / 10f
    }

    /** Always on [exec]. */
    private fun check() {
        val temp = try { readBattery() } catch (_: Exception) { Float.NaN }
        batteryTemp = temp
        if (!enabled) return
        val sys = if (Build.VERSION.SDK_INT >= 29) statusLevel(pm.currentThermalStatus) else 0
        val up = maxOf(sys, if (temp.isNaN()) 0 else batteryLevel(temp, startC))
        // Cooling down needs the battery a bit below the threshold, not just under it
        val down = maxOf(sys, if (temp.isNaN()) 0 else batteryLevel(temp + HYSTERESIS_C, startC))
        val old = level
        when {
            up > level -> { level = up; coolTicks = 0 }
            down < level -> if (++coolTicks >= COOL_TICKS) { level--; coolTicks = 0 }
            else -> coolTicks = 0
        }
        if (level != old) {
            Log.i(TAG, "heat level $old -> $level (battery $temp °C, system $sys)")
            onChange(level)
        }
    }
}
