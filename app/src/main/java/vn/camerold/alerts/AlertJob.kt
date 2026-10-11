package vn.camerold.alerts

import android.app.job.JobParameters
import android.app.job.JobService

/** The periodic background check scheduled by [BatteryAlerts.schedule]. */
class AlertJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try { BatteryAlerts.check(applicationContext) } catch (_: Exception) {}
            jobFinished(params, false)
        }.start()
        return true
    }

    /** Stopped by the system (e.g. network lost): the next period runs again anyway. */
    override fun onStopJob(params: JobParameters) = false
}
