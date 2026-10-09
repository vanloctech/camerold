package vn.camerold.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import vn.camerold.R
import vn.camerold.data.Prefs
import vn.camerold.ui.CameraActivity

/** Foreground service so the camera keeps streaming when the screen is off. */
class CameraService : Service() {

    companion object {
        const val ACTION_START = "vn.camerold.START"
        const val ACTION_STOP = "vn.camerold.STOP"
        private const val CHANNEL = "camera"
        private const val NOTIF_ID = 1
        @Volatile var running = false
            private set
    }

    private var engine: CameraEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val wifiLocks = mutableListOf<WifiManager.WifiLock>()
    private var notif: Notification.Builder? = null
    private var lastNotifText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (engine != null) return START_NOT_STICKY

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, CameraActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CameraService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val cfg = Prefs.load(this)
        notif = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(cfg.name.ifEmpty { getString(R.string.notif_title) })
            .setContentText(getString(R.string.status_starting))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, getString(R.string.btn_stop), stop).build())
        val n = notif!!.build()
        // With mic permission, also declare the "microphone" type so Android allows recording in the background
        val micOk = cfg.audio && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or (if (micOk && Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        // The "camera"/"microphone" type constants only exist from Android 11; before that the manifest's types apply
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, type)
        else startForeground(NOTIF_ID, n)

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "camerold:stream").apply { acquire() }
        // LOW_LATENCY only works while the app is in the foreground; HIGH_PERF keeps Wi-Fi awake in the background
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLocks += wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "camerold:wifi").apply { acquire() }
        if (Build.VERSION.SDK_INT >= 29)
            wifiLocks += wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "camerold:wifi-ll").apply { acquire() }

        engine = CameraEngine(applicationContext, cfg).also { e ->
            // Update the notification with the state (even when in the background / screen off)
            e.serviceListener = { st ->
                val title = e.name.ifEmpty { getString(R.string.notif_title) } // camera name can change while streaming
                val text = st.message
                if (title + text != lastNotifText) {
                    lastNotifText = title + text
                    notif?.let { b -> nm.notify(NOTIF_ID, b.setContentTitle(title).setContentText(text).build()) }
                }
            }
            e.start()
        }
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        engine?.serviceListener = null
        engine?.stop()
        engine = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLocks.forEach { if (it.isHeld) it.release() }
        wifiLocks.clear()
        super.onDestroy()
    }
}
