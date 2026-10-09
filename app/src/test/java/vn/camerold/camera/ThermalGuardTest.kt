package vn.camerold.camera

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalGuardTest {
    @Test fun batteryThresholds() {
        // A battery at 40-43 °C is only warm to the touch: no action
        assertEquals(0, ThermalGuard.batteryLevel(42.9f))
        assertEquals(1, ThermalGuard.batteryLevel(44f))
        assertEquals(2, ThermalGuard.batteryLevel(47.2f))
        assertEquals(3, ThermalGuard.batteryLevel(50f))
        // User setting: start at 40 °C -> 40 / 43 / 46
        assertEquals(1, ThermalGuard.batteryLevel(41f, 40f))
        assertEquals(2, ThermalGuard.batteryLevel(43f, 40f))
        assertEquals(3, ThermalGuard.batteryLevel(46f, 40f))
        assertEquals(0, ThermalGuard.batteryLevel(47f, 48f))
        // Cooling down: 43 °C stays "warm" because the check adds the hysteresis
        assertEquals(1, ThermalGuard.batteryLevel(43f + 1.5f))
    }

    @Test fun systemStatus() {
        assertEquals(0, ThermalGuard.statusLevel(PowerManager.THERMAL_STATUS_LIGHT))
        // "Moderate" fires during any long camera use on some phones: ignored
        assertEquals(0, ThermalGuard.statusLevel(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals(2, ThermalGuard.statusLevel(PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals(3, ThermalGuard.statusLevel(PowerManager.THERMAL_STATUS_EMERGENCY))
    }

    @Test fun hotPhoneGetsLessBitrate() {
        val normal = CameraEngine.bitrateFor(1080, 30, CameraEngine.ASPECT_WIDE)
        assertEquals(6_000_000, normal)
        assertEquals(4_800_000, CameraEngine.bitrateFor(1080, 30, CameraEngine.ASPECT_WIDE, heat = 1))
    }
}
