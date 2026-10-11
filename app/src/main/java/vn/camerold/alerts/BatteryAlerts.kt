package vn.camerold.alerts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import vn.camerold.R
import vn.camerold.signaling.MqttWs
import vn.camerold.signaling.SigCrypto
import vn.camerold.signaling.later
import vn.camerold.signaling.post
import vn.camerold.signaling.serialExecutor
import vn.camerold.ui.ViewerActivity

/**
 * Low-battery alerts on viewer phones, without any push server.
 *
 * The camera publishes its battery state as a retained, encrypted message on "<topic>/s" (CameraEngine.publishStatus).
 * This phone keeps the cameras saved in the viewer (sent by the page, see [sync]) and checks them every ~15 minutes
 * with a system job ([AlertJob]), even when the app is closed: connect, read the retained message, disconnect.
 * Android may delay the job while the phone sleeps, so alerts can come late; that's fine for a battery running down.
 *
 * Only the derived key and topic are stored here, never the camera password.
 */
object BatteryAlerts {
    private const val TAG = "BatteryAlerts"
    /** Same threshold as the warning on the viewer screen (web/js/viewer.js). */
    const val LOW = 20
    /** After an alert, alert again only once the battery went back above this (or was charged). */
    const val RESET = 25
    /** The camera republishes at least this often, even when nothing changed. */
    const val REFRESH_MS = 15 * 60_000L
    /** An older status is stale (camera offline or switched off without saying so): no alert from it. */
    const val MAX_AGE_MS = 45 * 60_000L
    private const val JOB_ID = 4201
    private const val CHANNEL = "alerts"

    class Status(val on: Boolean, val bat: Int, val chg: Boolean, val ts: Long, val name: String?)

    /** One saved camera. [fp] identifies the code + password the key was derived from. */
    private class Cam(val room: String, val name: String?, val topic: String, val key: SecretKeySpec,
                      val brokers: List<String>, val fp: String, var alerted: Boolean)

    /**
     * Whether to notify now, and the new "already alerted" state. Pure, so it's unit-tested.
     * No (or stale) news keeps the state: a camera that went quiet doesn't re-arm the alert.
     */
    fun decide(s: Status?, alerted: Boolean, now: Long): Pair<Boolean, Boolean> = when {
        s == null || now - s.ts > MAX_AGE_MS -> false to alerted
        !s.on || s.chg || s.bat > RESET -> false to false
        s.bat in 0..LOW -> !alerted to true
        else -> false to alerted
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("enabled", true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", on).apply()
        schedule(ctx)
    }

    fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun hasCameras(ctx: Context) = load(ctx).isNotEmpty()

    private fun load(ctx: Context): List<Cam> = try {
        val a = JSONArray(prefs(ctx).getString("cams", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val b = o.getJSONArray("brokers")
            Cam(o.getString("room"), o.optString("name").ifEmpty { null }, o.getString("topic"),
                SecretKeySpec(Base64.decode(o.getString("key"), Base64.NO_WRAP), "AES"),
                (0 until b.length()).map { b.getString(it) }, o.getString("fp"), o.optBoolean("alerted"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(ctx: Context, cams: List<Cam>) {
        val a = JSONArray()
        cams.forEach { c ->
            a.put(JSONObject().put("room", c.room).put("name", c.name ?: "").put("topic", c.topic)
                .put("key", Base64.encodeToString(c.key.encoded, Base64.NO_WRAP)).put("brokers", JSONArray(c.brokers))
                .put("fp", c.fp).put("alerted", c.alerted))
        }
        prefs(ctx).edit().putString("cams", a.toString()).apply()
    }

    private val worker = Executors.newSingleThreadExecutor()

    /**
     * The viewer page's saved cameras changed: [json] = [{room, pass, name, brokers: [url…]}].
     * Keys are derived (slow, PBKDF2) only for new codes or passwords.
     */
    fun sync(ctx: Context, json: String) = worker.execute {
        try {
            val old = load(ctx).associateBy { it.fp }
            val a = JSONArray(json)
            val cams = (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val room = o.getString("room")
                val pass = o.getString("pass")
                val b = o.getJSONArray("brokers")
                val brokers = (0 until b.length()).map { b.getString(it) }
                val name = o.optString("name").takeIf { it.isNotEmpty() && it != "null" }
                val fp = MessageDigest.getInstance("SHA-256").digest("$room\n$pass".toByteArray())
                    .joinToString("") { "%02x".format(it) }
                old[fp]?.let { Cam(room, name, it.topic, it.key, brokers, fp, it.alerted) }
                    ?: SigCrypto.deriveKeys(room, pass).let { k -> Cam(room, name, k.topic, k.enc, brokers, fp, false) }
            }
            save(ctx, cams)
            schedule(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "sync", e)
        }
    }

    /** Runs the periodic check while alerts are on and there is something to check. Survives reboots. */
    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (!isEnabled(ctx) || !hasCameras(ctx)) { js.cancel(JOB_ID); return }
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, AlertJob::class.java))
            .setPeriodic(REFRESH_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build())
    }

    /** One check of every saved camera. Blocking (about 15 s at most): call it off the main thread. */
    fun check(ctx: Context) {
        if (!isEnabled(ctx)) return
        val cams = load(ctx)
        if (cams.isEmpty()) return
        val found = fetch(cams)
        val now = System.currentTimeMillis()
        for (c in cams) {
            val s = found[c.topic]
            val (notify, alerted) = decide(s, c.alerted, now)
            if (notify && s != null) notify(ctx, c, s)
            if (c.alerted && !alerted) ctx.getSystemService(NotificationManager::class.java).cancel(c.topic.hashCode())
            c.alerted = alerted
        }
        // Saved cameras may have changed meanwhile: only write back the alert states
        val states = cams.associate { it.fp to it.alerted }
        val latest = load(ctx)
        latest.forEach { c -> states[c.fp]?.let { c.alerted = it } }
        save(ctx, latest)
    }

    /** Reads the retained status of every camera: one connection per broker, all at once. */
    private fun fetch(cams: List<Cam>): Map<String, Status> {
        val exec = serialExecutor()
        val byTopic = cams.associateBy { it.topic + "/s" }
        val found = HashMap<String, Status>() // camera topic -> newest status (brokers may hold different ones)
        val done = CountDownLatch(1)
        var ending = false
        val clients = cams.flatMap { it.brokers }.distinct().map { url ->
            val topics = cams.filter { url in it.brokers }.map { it.topic + "/s" }
            MqttWs(url, exec, object : MqttWs.Callback {
                override fun onUp(c: MqttWs) = topics.forEach { c.subscribe(it) }
                override fun onDown(c: MqttWs) {}
                override fun onPublish(c: MqttWs, topic: String, payload: ByteArray) {
                    val cam = byTopic[topic] ?: return
                    val s = parse(cam, topic, payload) ?: return
                    if ((found[cam.topic]?.ts ?: 0) < s.ts) found[cam.topic] = s
                    // Everyone answered: wait a moment for a newer copy on another broker, then finish
                    if (!ending && found.size == cams.size) { ending = true; exec.later({ done.countDown() }, 2, TimeUnit.SECONDS) }
                }
            })
        }
        clients.forEach { it.connect() }
        done.await(15, TimeUnit.SECONDS)
        val result = CountDownLatch(1)
        var copy: Map<String, Status> = emptyMap()
        exec.post { copy = HashMap(found); result.countDown() }
        result.await(2, TimeUnit.SECONDS)
        clients.forEach { it.stop() }
        exec.later({ exec.shutdown() }, 1, TimeUnit.SECONDS)
        return copy
    }

    private fun parse(cam: Cam, topic: String, payload: ByteArray): Status? {
        val o = try { JSONObject(SigCrypto.decrypt(cam.key, payload, topic) ?: return null) } catch (_: Exception) { return null }
        if (o.optString("t") != "status") return null
        return Status(o.optBoolean("on"), o.optInt("bat", -1), o.optBoolean("chg"), o.optLong("ts"),
            o.optString("name").ifEmpty { null })
    }

    private fun notify(ctx: Context, cam: Cam, s: Status) {
        if (!canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.alerts_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, ViewerActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val title = cam.name ?: s.name ?: (ctx.getString(R.string.camera_title) + " · " + cam.room) // tells several cameras apart
        val text = ctx.getString(R.string.alert_bat_low, s.bat)
        nm.notify(cam.topic.hashCode(), Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }
}
