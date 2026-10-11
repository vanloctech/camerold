package vn.camerold.alerts

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.camerold.alerts.BatteryAlerts.Status
import vn.camerold.alerts.BatteryAlerts.decide

class BatteryAlertsTest {
    private val now = 1_800_000_000_000L
    private fun st(bat: Int, chg: Boolean = false, on: Boolean = true, ageMin: Int = 5) =
        Status(on, bat, chg, now - ageMin * 60_000L, null)

    @Test fun alertsOnceWhenLowAndNotCharging() {
        assertEquals(true to true, decide(st(20), false, now))
        assertEquals(true to true, decide(st(3), false, now))
        // Already told: no repeat while it stays low
        assertEquals(false to true, decide(st(12), true, now))
    }

    @Test fun noAlertWhenFineOrCharging() {
        assertEquals(false to false, decide(st(21), false, now))
        assertEquals(false to false, decide(st(10, chg = true), false, now))
        // Camera switched off on purpose
        assertEquals(false to false, decide(st(10, on = false), false, now))
    }

    @Test fun rearmsOnlyAfterRecovering() {
        // 21-25 %: still counted as the same low period
        assertEquals(false to true, decide(st(23), true, now))
        assertEquals(false to false, decide(st(26), true, now))
        assertEquals(false to false, decide(st(15, chg = true), true, now))
    }

    @Test fun staleOrMissingKeepsState() {
        assertEquals(false to false, decide(st(10, ageMin = 60), false, now))
        assertEquals(false to true, decide(null, true, now))
        // Unknown battery level (no "bat") never alerts
        assertEquals(false to false, decide(st(-1), false, now))
    }
}
