package vn.camerold.camera

import android.content.Context
import android.util.Log
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ProCapturer
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import vn.camerold.R
import vn.camerold.alerts.BatteryAlerts
import vn.camerold.data.CamConfig
import vn.camerold.data.Prefs
import vn.camerold.signaling.SigCrypto
import vn.camerold.signaling.serialExecutor
import vn.camerold.signaling.every
import vn.camerold.signaling.later
import vn.camerold.signaling.post
import vn.camerold.signaling.Signaling

/**
 * Camera core: capture -> hardware encoding (H.264/VP8) -> P2P via WebRTC to each viewer.
 * All logic runs on a single thread [exec].
 */
class CameraEngine(private val ctx: Context, private var cfg: CamConfig) {

    data class Status(val brokersUp: Int, val brokersTotal: Int, val viewers: Int, val message: String)

    companion object {
        private const val TAG = "CameraEngine"
        private const val MAX_VIEWERS = 3
        /** Nobody watching and the camera screen closed: turn the camera off after this long (less heat). */
        private const val IDLE_AFTER_S = 20L
        /** Light on, light off: each this long. */
        private const val STROBE_STEP_MS = 250L
        /** Flashing stops this long after the viewer's last "still holding" (connection lost, page closed). */
        private const val STROBE_HOLD_MS = 2500L
        /** Capture rate while only a timelapse needs the camera. */
        private const val TIMELAPSE_FPS = 10
        @Volatile var current: CameraEngine? = null

        const val ASPECT_WIDE = "16:9"
        const val ASPECT_FULL = "4:3"

        /**
         * Requested capture size for a quality tier (by height) and frame shape.
         * 4:3 is the sensor's native shape: it shows the most of the scene (16:9 crops top and bottom).
         * ProCapturer picks the closest size the camera really offers with the same shape,
         * e.g. 2592x1944 for the 4:3 top tier on a Pixel 5 (hardware encoders can't do 4032x3024 video).
         */
        fun sizeFor(res: Int, aspect: String): Pair<Int, Int> {
            val h = when (res) { 2160 -> 2160; 720 -> 720; else -> 1080 }
            return (if (aspect == ASPECT_FULL) h * 4 / 3 else h * 16 / 9) to h
        }

        /** Max bitrate, scaled by pixel count so 4:3 doesn't get more than it needs. */
        fun bitrateFor(res: Int, fps: Int, aspect: String, ultraWide: Boolean = false, heat: Int = 0): Int {
            val base = when (res) {
                2160 -> 16_000_000
                720 -> 2_500_000
                else -> 6_000_000
            }
            val shape = if (aspect == ASPECT_FULL) 0.75 else 1.0
            // Ultra-wide: much more fine detail per frame, so the same bitrate looks smeared
            // Warm phone: encoding fewer bits is less work for the chip
            return (base * shape * (if (fps > 30) 1.5 else 1.0) * (if (ultraWide) 1.35 else 1.0) * (if (heat > 0) 0.8 else 1.0)).toInt()
        }

        val DEFAULT_ICE: List<PeerConnection.IceServer> = listOf(
            PeerConnection.IceServer.builder(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")).createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        )
    }

    /** [owner]: came through the main link (full control); false = the view-only link (watch only). */
    private inner class Peer(val viewerId: String, val session: Int, val pc: PeerConnection, val sender: RtpSender, val owner: Boolean) {
        /** Key in [peers]: the two links are kept apart, so a view-only viewer can't reach someone else's connection. */
        val id get() = peerId(viewerId, owner)
        val pending = mutableListOf<IceCandidate>()
        /** Timelapse videos: list, download, delete. */
        var files: TlChannel? = null
        /** Recordings: browse, download, cut, keep, delete. */
        var rec: RecChannel? = null
        var remoteSet = false
        var offer: String? = null
        var state = PeerConnection.PeerConnectionState.NEW
        var disposed = false
    }

    val exec = serialExecutor()
    val eglBase: EglBase = EglBase.create()

    @Volatile var statusListener: ((Status) -> Unit)? = null
    /** Lets CameraService update the notification (viewer count…) even when the app is in the background. */
    @Volatile var serviceListener: ((Status) -> Unit)? = null
    private val brokers = vn.camerold.signaling.Brokers.list(cfg)
    @Volatile var lastStatus = Status(0, brokers.size, 0, ctx.getString(R.string.status_starting))
        private set

    /** Intermediate sink so the Activity can attach/detach the preview at any time. */
    private val previewProxy = object : VideoSink {
        @Volatile var target: VideoSink? = null
        override fun onFrame(frame: VideoFrame) { target?.onFrame(frame) }
    }

    private lateinit var factory: PeerConnectionFactory
    private lateinit var key: SecretKeySpec
    private lateinit var topic: String
    private var signaling: Signaling? = null
    /** The view-only link (see CamConfig.viewPassword): its own key and topic. */
    private var viewKey: SecretKeySpec? = null
    private var viewTopic: String? = null
    private var capturer: ProCapturer? = null
    private var lenses: List<ProCapturer.Lens> = emptyList()
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var adm: AudioDeviceModule? = null
    private val peers = LinkedHashMap<String, Peer>()
    private var brokersUp = 0
    private var stopped = false

    /** Heat level from [ThermalGuard] (0 normal … 3 very hot): limits what the camera runs at, see [eff]. */
    private var heat = 0
    private val thermal = ThermalGuard(ctx, exec) { level -> exec.post { applyHeat(level) } }

    /** Timelapse controlled from viewers; gets every camera frame and keeps one now and then. */
    val timelapse = Timelapse(ctx, exec) { listChanged -> exec.post { onTimelapse(listChanged) } }
    /** Sends timelapse videos to viewers (one transfer per viewer at a time). */
    private val filePool = java.util.concurrent.Executors.newCachedThreadPool()
    private var tlInfoAt = 0L

    /** Continuous recording (when turned on); gets every camera frame too. */
    val recorder = Recorder(ctx, exec, eglBase.eglBaseContext) { listChanged -> exec.post { onRecorder(listChanged) } }
    private var recInfoAt = 0L
    private var recState = ""

    /** Camera turned off because nobody is watching (it comes back on the next viewer or when the screen opens). */
    private var camIdle = false
    private var idleTimer: ScheduledFuture<*>? = null

    fun setPreview(sink: VideoSink?) {
        previewProxy.target = sink
        exec.post { updateIdle() }
    }

    /** When streaming started (for "running for 3 days" on the viewer). */
    private val startedAt = android.os.SystemClock.elapsedRealtime()

    /**
     * The old phone's condition, for viewers: battery, charging, temperature, free storage, how long it's been streaming.
     * A low battery that isn't charging usually means the charger came loose.
     */
    private fun health(): JSONObject {
        val o = JSONObject().put("up", (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000)
        try {
            ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))?.let { b ->
                val level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) o.put("bat", level * 100 / scale)
                o.put("chg", b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0)
                val t = b.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (t != Int.MIN_VALUE) o.put("temp", t / 10.0)
            }
        } catch (_: Exception) {}
        try { o.put("free", android.os.StatFs(ctx.filesDir.path).availableBytes) } catch (_: Exception) {}
        return o
    }

    /** Last battery state published on "<topic>/s" and when. */
    private var lastPub: Pair<Int, Boolean>? = null
    private var lastPubAt = 0L

    /**
     * Publishes the battery state as a retained, encrypted message on "<topic>/s". Viewer phones check it in the
     * background every ~15 min and notify when the battery is low and not charging (alerts/BatteryAlerts).
     * Sent when it changes, and at least every [BatteryAlerts.REFRESH_MS] so viewers can tell it's current.
     */
    private fun publishStatus(on: Boolean = true) {
        val sig = signaling ?: return
        val h = health()
        val now = System.currentTimeMillis()
        val state = h.optInt("bat", -1) to h.optBoolean("chg")
        if (on && state == lastPub && now - lastPubAt < BatteryAlerts.REFRESH_MS) return
        lastPub = state
        lastPubAt = now
        fun msg() = JSONObject().put("t", "status").put("on", on).also { m ->
            if (cfg.name.isNotEmpty()) m.put("name", cfg.name)
            if (on && state.first >= 0) m.put("bat", state.first).put("chg", state.second)
        }
        sig.send("$topic/s", msg(), retain = true)
        // View-only viewers get battery alerts too
        val vk = viewKey
        if (vk != null) sig.send("$viewTopic/s", msg(), retain = true, key = vk)
    }

    fun start() = exec.post {
        current = this
        try {
            Rtc.ensureInit(ctx)
            adm = JavaAudioDeviceModule.builder(ctx.applicationContext)
                .setUseHardwareAcousticEchoCanceler(false) // the camera device plays no audio -> no echo cancellation needed
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(SafeEncoderFactory(eglBase.eglBaseContext, cfg.codec))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .createPeerConnectionFactory()
            startCamera()
            thermal.configure(cfg.heatGuard, cfg.heatStart)
            thermal.start()
            recorder.configure(Recorder.Settings.of(cfg))
            updateIdle()
            // Keep viewers' "camera phone" info fresh (only sent when someone is watching)
            exec.every({ if (!stopped && peers.isNotEmpty()) peers.values.forEach { sendTo(it, JSONObject().put("t", "info").put("health", health())) } },
                30, TimeUnit.SECONDS)
            status(ctx.getString(R.string.st_securing))
            val keys = SigCrypto.deriveKeys(cfg.room, cfg.password)
            key = keys.enc
            topic = keys.topic
            signaling = Signaling(exec, brokers, key, "$topic/c", { onSignal(it, owner = true) }) { up, _ ->
                brokersUp = up
                status(null)
            }.also { sig ->
                if (cfg.viewPassword.length >= 8) {
                    val vk = SigCrypto.deriveKeys(cfg.room, cfg.viewPassword)
                    viewKey = vk.enc
                    viewTopic = vk.topic
                    sig.listen(vk.enc, "${vk.topic}/c") { onSignal(it, owner = false) }
                }
                sig.start()
            }
            // Battery state for viewers' background alerts, kept by the brokers (retained)
            publishStatus()
            timelapse.recover()
            recorder.recover()
            exec.every({ if (!stopped) { timelapse.tick(); recorder.tick() } }, 5, TimeUnit.SECONDS)
            exec.every({ if (!stopped) publishStatus() }, 60, TimeUnit.SECONDS)
            status(ctx.getString(R.string.st_connecting))
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            status(ctx.getString(R.string.st_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    fun stop() = exec.post {
        stopped = true
        // Every step is guarded: one failure must never leave the camera, microphone or WebRTC running.
        fun step(name: String, block: () -> Unit) = try { block() } catch (e: Exception) { Log.w(TAG, "stop: $name", e) }
        step("thermal") { thermal.stop() }
        step("status") { publishStatus(on = false) } // viewers stop alerting about a camera that was turned off
        step("timelapse") { track?.removeSink(timelapse); timelapse.stop(); timelapse.shutdown() } // the video is still made
        step("recorder") { track?.removeSink(recorder); recorder.shutdown() } // the recording so far is still joined
        step("files") { filePool.shutdownNow() }
        step("signaling") { signaling?.stop() }
        peers.values.toList().forEach { p -> step("peer") { removePeer(p) } }
        step("capture") { capturer?.stopCapture() }
        step("capturer") { capturer?.dispose() }
        step("video track") { track?.dispose() }
        step("video source") { source?.dispose() }
        step("audio track") { audioTrack?.dispose() }
        step("audio source") { audioSource?.dispose() }
        step("texture helper") { helper?.dispose() }
        step("factory") { if (::factory.isInitialized) factory.dispose() }
        step("audio device") { adm?.release() }
        step("egl") { eglBase.release() }
        if (current === this) current = null
        // Last: work already queued (e.g. MQTT disconnect) still runs, nothing new is accepted.
        exec.shutdown()
    }

    // ---------------- Camera ----------------

    private val camEvents = object : ProCapturer.Events {
        override fun onCameraError(msg: String) { Log.e(TAG, msg); restartCameraLater(msg) }
        override fun onFirstFrame() { exec.post { camBusy = false; status(null); broadcastInfo() } }
        override fun onLensFailed(l: ProCapturer.Lens) {
            exec.post {
                if (stopped) return@post
                lenses = lenses.filter { it.key != l.key }
                if (cfg.lens == l.key) {
                    val main = currentLens()
                    cfg = cfg.copy(lens = main.key, zoom = main.zoomPreset ?: 1f)
                    Prefs.save(ctx, cfg)
                    capturer?.setLens(main)
                    capturer?.setControls(controlsFromCfg())
                }
                broadcastInfo()
            }
        }
        override fun onCameraBusy() {
            exec.post {
                camBusy = true
                status(ctx.getString(R.string.st_cam_busy))
                broadcastInfo()
                // Fallback in case the system's "camera available" signal is missed
                exec.later({ if (camBusy && !stopped) restartCameraLater(ctx.getString(R.string.st_reopening)) }, 15, TimeUnit.SECONDS)
            }
        }
    }
    @Volatile private var camBusy = false

    private fun currentLens(): ProCapturer.Lens =
        lenses.firstOrNull { it.key == cfg.lens }
            ?: lenses.filter { !it.front }.minByOrNull { abs(it.rel - 1f) }
            ?: lenses.first()

    private fun isUltraWide() = lenses.isNotEmpty() && currentLens().ultraWide(cfg.zoom)

    /**
     * What the camera really runs at: the user's settings ([cfg], which is what gets saved and shown),
     * limited while the phone is hot so Android doesn't shut the camera down.
     */
    private fun eff(): CamConfig {
        var c = cfg.let { it.copy(res = minOf(it.res, Rtc.maxRes(it.codec, it.aspect))) }
        // Only a timelapse or the recording needs the camera: their size and frame rate are plenty, and keep the phone cooler
        if (backgroundOnly()) {
            val rec = recorder.needsCamera
            val need = maxOf(if (rec) recorder.settings.q else 0, if (timelapse.active) timelapse.session?.q ?: 1080 else 0)
            c = c.copy(fps = minOf(c.fps, if (rec) recorder.settings.fps else TIMELAPSE_FPS), res = minOf(c.res, need.coerceAtLeast(720)))
        }
        return when (heat) {
            0 -> c
            1 -> c.copy(fps = minOf(c.fps, 24))
            2 -> c.copy(res = minOf(c.res, 1080), fps = minOf(c.fps, 15), stab = false)
            else -> c.copy(res = 720, fps = minOf(c.fps, 10), stab = false, torch = false)
        }
    }

    private fun controlsFromCfg() = eff().let { e ->
        val torch = if (strobeTask != null) strobeLit && heat < 3 else e.torch
        ProCapturer.Controls(e.zoom, torch, e.night, e.ev, e.focus, e.fx, e.fy, e.fdist, e.stab, lowPower = heat > 0)
    }

    // ---------------- Flashing the light (owner holds the torch button) ----------------

    private var strobeTask: ScheduledFuture<*>? = null
    private var strobeLit = false
    /** The viewer repeats {t: "strobe", on: true} while the button is held; without it the flashing stops by itself. */
    private var strobeUntil = 0L

    private fun strobe(on: Boolean) {
        val cap = capturer ?: return
        if (!on || camIdle) { stopStrobe(); return }
        strobeUntil = android.os.SystemClock.elapsedRealtime() + STROBE_HOLD_MS
        if (strobeTask != null) return
        strobeLit = false
        strobeTask = exec.every({
            if (stopped || android.os.SystemClock.elapsedRealtime() > strobeUntil) { stopStrobe(); return@every }
            strobeLit = !strobeLit
            cap.setControls(controlsFromCfg())
        }, STROBE_STEP_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopStrobe() {
        val t = strobeTask ?: return
        t.cancel(false)
        strobeTask = null
        strobeLit = false
        capturer?.setControls(controlsFromCfg()) // back to the torch setting
    }

    /** Capture format the camera was last asked for. */
    private var applied: Triple<Int, Int, Int>? = null

    private fun startCapture(cap: ProCapturer) {
        val e = eff()
        val (w, h) = sizeFor(e.res, e.aspect)
        applied = Triple(w, h, e.fps)
        cap.startCapture(w, h, e.fps)
    }

    /** Applies a changed size or frame rate from [eff] (heat, settings, timelapse-only) to the running camera. */
    private fun refreshFormat() {
        val cap = capturer ?: return
        if (camIdle) return
        val e = eff()
        val (w, h) = sizeFor(e.res, e.aspect)
        val f = Triple(w, h, e.fps)
        if (f == applied) return
        applied = f
        cap.changeCaptureFormat(w, h, e.fps)
    }

    /** Nobody watches, the camera screen is closed: only a timelapse or the recording uses the camera. */
    private fun backgroundOnly() = (timelapse.active || recorder.needsCamera) && peers.isEmpty() && previewProxy.target == null

    private fun applyHeat(level: Int) {
        if (stopped || level == heat) return
        val before = eff()
        heat = level
        recorder.setHeat(level)
        val after = eff()
        capturer?.let { cap ->
            if (before.res != after.res || before.fps != after.fps) refreshFormat()
            cap.setControls(controlsFromCfg())
        }
        peers.values.forEach { applyEncoding(it.sender) }
        status(null)
        broadcastInfo()
    }

    /** Turn the camera off when nobody needs it: no viewer and the camera screen isn't showing the preview. */
    private fun updateIdle() {
        if (stopped) return
        val cap = capturer ?: return
        if (peers.isNotEmpty() || previewProxy.target != null || timelapse.active || recorder.needsCamera) {
            idleTimer?.cancel(false); idleTimer = null
            if (camIdle) {
                camIdle = false
                Log.i(TAG, "camera on (someone is watching, a timelapse or the recording runs)")
                startCapture(cap)
                status(null)
            } else refreshFormat()
        } else if (!camIdle && idleTimer == null) {
            idleTimer = exec.later({
                idleTimer = null
                if (stopped || camIdle || peers.isNotEmpty() || previewProxy.target != null || timelapse.active || recorder.needsCamera) return@later
                Log.i(TAG, "camera resting (nobody watching)")
                camIdle = true
                camBusy = false
                try { cap.stopCapture() } catch (e: Exception) { Log.w(TAG, "idle stop", e) }
                status(null)
            }, IDLE_AFTER_S, TimeUnit.SECONDS)
        }
    }

    private fun startCamera() {
        lenses = ProCapturer.listLenses(ctx)
        Log.i(TAG, "lenses: $lenses")
        val h = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val src = factory.createVideoSource(false)
        val cap = ProCapturer(ctx, camEvents)
        cap.initialize(h, ctx.applicationContext, src.capturerObserver)
        val lens = currentLens()
        if (lens.key != cfg.lens) cfg = cfg.copy(lens = lens.key, zoom = lens.zoomPreset ?: 1f)
        cap.setLens(lens)
        cap.setControls(controlsFromCfg())
        startCapture(cap)
        helper = h; source = src; capturer = cap
        track = factory.createVideoTrack("cam0", src).also { it.addSink(previewProxy); it.addSink(timelapse); it.addSink(recorder) }
        startMic()
    }

    /** Mic: enabled only when the user allows it (setting + Android permission). */
    private fun startMic() {
        val granted = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!cfg.audio || !granted) return
        val c = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        audioSource = factory.createAudioSource(c)
        audioTrack = factory.createAudioTrack("mic0", audioSource)
    }

    private var restartPending = false
    private fun restartCameraLater(msg: String): Unit = exec.post {
        status(msg)
        if (restartPending || stopped || camIdle) return@post
        restartPending = true
        exec.later({
            restartPending = false
            if (stopped || camIdle) return@later
            try {
                val cap = capturer ?: return@later
                cap.stopCapture()
                startCapture(cap)
            } catch (e: Exception) {
                Log.e(TAG, "restart camera", e)
                restartCameraLater(ctx.getString(R.string.st_retrying))
            }
        }, 3, TimeUnit.SECONDS)
    }

    private fun applyEncoding(sender: RtpSender) {
        try {
            val p = sender.parameters
            // Prefer keeping resolution (sharp for zooming); on weak networks drop fps instead of sharpness
            p.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            p.encodings.forEach {
                val e = eff()
                it.maxBitrateBps = bitrateFor(e.res, e.fps, e.aspect, isUltraWide(), heat)
                it.maxFramerate = e.fps
            }
            sender.parameters = p
        } catch (e: Exception) {
            Log.w(TAG, "applyEncoding", e)
        }
    }

    /** Control commands from the viewer: res, fps, lens, zoom, torch, night, ev, focus, stab. */
    private fun changeConfig(m: JSONObject) {
        val cap = capturer ?: return
        var c = cfg
        m.optInt("res").takeIf { it in listOf(720, 1080, 2160) }?.let { c = c.copy(res = it) }
        m.optInt("fps").takeIf { it in 5..60 }?.let { c = c.copy(fps = it) }
        m.optString("aspect").takeIf { it == ASPECT_WIDE || it == ASPECT_FULL }?.let { c = c.copy(aspect = it) }
        val newLens = m.optString("lens").takeIf { it.isNotEmpty() && it != c.lens }
            ?.let { k -> lenses.firstOrNull { it.key == k } }
        if (newLens != null) c = c.copy(lens = newLens.key, zoom = newLens.zoomPreset ?: 1f)
        // Tapping the lens already in use (e.g. 1x after zooming to 3x) goes back to its own zoom
        else if (m.optString("lens") == c.lens && !m.has("zoom")) c = c.copy(zoom = lenses.firstOrNull { it.key == c.lens }?.zoomPreset ?: 1f)
        if (m.has("zoom")) c = c.copy(zoom = m.getDouble("zoom").toFloat())
        if (m.has("torch")) c = c.copy(torch = m.getBoolean("torch"))
        if (m.has("night")) c = c.copy(night = m.getBoolean("night"))
        if (m.has("ev")) c = c.copy(ev = m.getInt("ev"))
        m.optString("focus").takeIf { it in setOf("auto", "point", "lock", "manual", "inf") }?.let { c = c.copy(focus = it) }
        if (m.has("fx") && m.has("fy")) c = c.copy(fx = m.getDouble("fx").toFloat().coerceIn(0f, 1f), fy = m.getDouble("fy").toFloat().coerceIn(0f, 1f))
        if (m.has("fdist")) c = c.copy(fdist = m.getDouble("fdist").toFloat().coerceIn(0f, 1f))
        if (m.has("stab")) c = c.copy(stab = m.getBoolean("stab"))
        if (m.has("heatGuard")) c = c.copy(heatGuard = m.getBoolean("heatGuard"))
        // Relay server typed on a viewer (old camera phones are awkward to type on); {} clears it
        m.optJSONObject("turn")?.let { tj ->
            val urls = tj.optString("urls").trim().take(500)
            c = c.copy(turnUrls = urls, turnUser = tj.optString("user").trim().take(200), turnPass = tj.optString("pass").trim().take(200))
            testTurnLater(c)
        }
        m.optJSONObject("rec")?.let { c = recConfig(c, it) }
        m.optInt("heatStart").takeIf { it in ThermalGuard.MIN_START_C..ThermalGuard.MAX_START_C }?.let { c = c.copy(heatStart = it) }
        if (c.heatGuard != cfg.heatGuard || c.heatStart != cfg.heatStart) thermal.configure(c.heatGuard, c.heatStart)

        val before = eff()
        val wasWide = isUltraWide()
        cfg = c
        val after = eff()
        val reformat = after.res != before.res || after.fps != before.fps || after.aspect != before.aspect
        if (newLens != null) cap.setLens(newLens)
        cap.setControls(controlsFromCfg())
        if (reformat) refreshFormat()
        m.optJSONObject("tl")?.let { timelapseCommand(it) }
        if (m.has("rec")) { recorder.configure(Recorder.Settings.of(cfg)); updateIdle() }
        if (reformat || isUltraWide() != wasWide) peers.values.forEach { applyEncoding(it.sender) }
        Prefs.save(ctx, cfg)
        broadcastInfo()
        // Capabilities (max zoom, torch…) are only known after the camera has reopened
        if (newLens != null || reformat) exec.later({ broadcastInfo() }, 2, TimeUnit.SECONDS)
    }

    // ---------------- Recording ----------------

    /** From an owner: {on, q, fps, days, h264, viewOnly, rot}, any of them. */
    private fun recConfig(c: CamConfig, o: JSONObject): CamConfig {
        var r = c
        if (o.has("on")) r = r.copy(recOn = o.optBoolean("on"))
        o.optInt("q").takeIf { it in Recorder.QUALITIES }?.let { r = r.copy(recQ = it) }
        o.optInt("fps").takeIf { it in Recorder.FPS }?.let { r = r.copy(recFps = it) }
        if (o.has("days")) o.optInt("days", -1).takeIf { it in Recorder.KEEP_DAYS }?.let { r = r.copy(recDays = it) }
        if (o.has("h264")) r = r.copy(recH264 = o.optBoolean("h264"))
        if (o.has("viewOnly")) r = r.copy(recViewOnly = o.optBoolean("viewOnly"))
        if (o.has("rot")) r = r.copy(recRot = ((o.optInt("rot") % 360) + 360) % 360 / 90 * 90)
        return r
    }

    /** Recording settings changed on the camera screen while streaming. */
    fun setRecording(c: CamConfig) = exec.post {
        if (stopped) return@post
        cfg = cfg.copy(recOn = c.recOn, recQ = c.recQ, recFps = c.recFps, recDays = c.recDays, recH264 = c.recH264,
            recViewOnly = c.recViewOnly, recRot = c.recRot)
        recorder.configure(Recorder.Settings.of(cfg))
        updateIdle()
        broadcastInfo()
        status(null)
    }

    /** Recording changed: camera on/off and its format, viewers' info and lists, status. */
    private fun onRecorder(listChanged: Boolean) {
        if (stopped) return
        updateIdle()
        val j = recorder.json()
        val state = listOf("on", "active", "event", "ver", "codec", "viewOnly").joinToString("|") { "${j.opt(it)}" }
        val now = android.os.SystemClock.elapsedRealtime()
        if (listChanged || state != recState || now - recInfoAt >= 30_000) {
            recState = state
            recInfoAt = now
            peers.values.forEach { sendTo(it, JSONObject().put("t", "info").put("rec", j)) }
        }
        if (listChanged) peers.values.forEach { it.rec?.notifyChanged(recorder.listVersion) }
        status(null)
    }

    // ---------------- Timelapse ----------------

    /** From a viewer: {op: "start", interval, q, minutes (auto-stop, 0 = none), stamp, rot, gallery} or {op: "stop"}. */
    private fun timelapseCommand(o: JSONObject) {
        when (o.optString("op")) {
            "start" -> timelapse.start(o.optInt("interval", 10).takeIf { it in Timelapse.INTERVALS } ?: 10,
                o.optInt("q", 1080), o.optInt("minutes").coerceIn(0, 7 * 24 * 60), o.optBoolean("stamp"),
                o.optInt("rot"), o.optBoolean("gallery"), cfg.name)
            "stop" -> timelapse.stop()
        }
    }

    private var tlState = ""

    /** Timelapse changed: camera on/off and its frame rate, viewers' info (photo count at most every 5 s), status. */
    private fun onTimelapse(listChanged: Boolean) {
        if (stopped) return
        updateIdle()
        val state = "${timelapse.active}|${timelapse.finishing}|${timelapse.lastEvent}"
        val now = android.os.SystemClock.elapsedRealtime()
        if (listChanged || state != tlState || now - tlInfoAt >= 5000) {
            tlState = state
            tlInfoAt = now
            peers.values.forEach { sendTo(it, JSONObject().put("t", "info").put("tl", timelapse.json())) }
        }
        if (listChanged) peers.values.forEach { it.files?.notifyChanged() }
        status(null)
    }

    /** Result of testing the relay server sent by a viewer (null: not tested / none). */
    private var turnOk: Boolean? = null

    /** The camera checks a relay server it received from a viewer and tells the viewers whether it works. */
    private fun testTurnLater(c: CamConfig) {
        turnOk = null
        val urls = Rtc.parseTurnUrls(c.turnUrls)
        if (urls.isEmpty()) return
        Rtc.testTurn(ctx, urls, c.turnUser, c.turnPass) { ok ->
            exec.post {
                if (stopped || cfg.turnUrls != c.turnUrls || cfg.turnUser != c.turnUser) return@post
                turnOk = ok
                broadcastInfo()
            }
        }
    }

    /** Heat protection settings changed on the camera screen while streaming. */
    fun setHeatGuard(on: Boolean, start: Int) = exec.post {
        if (stopped) return@post
        cfg = cfg.copy(heatGuard = on, heatStart = start)
        thermal.configure(on, start)
        broadcastInfo()
    }

    /** Rename while streaming: viewers see the new name right away. */
    fun setName(name: String) = exec.post {
        if (stopped) return@post
        cfg = cfg.copy(name = name)
        broadcastInfo()
        status(null)
    }
    val name: String get() = cfg.name

    private fun broadcastInfo() = peers.values.forEach { sendTo(it, info(JSONObject().put("t", "info"), it)) }

    /** Everything about the camera for [p]: owners also get the view-only password (to share it) and relay settings. */
    private fun info(o: JSONObject, p: Peer): JSONObject {
        o.put("role", if (p.owner) "owner" else "view")
        if (p.owner) {
            o.put("viewPass", cfg.viewPassword)
            // Relay server in use (never the password); ok = result of the camera's own test, null = not tested
            o.put("turn", JSONObject().put("urls", cfg.turnUrls).put("user", cfg.turnUser).put("ok", turnOk ?: JSONObject.NULL))
        }
        o.put("res", cfg.res).put("fps", cfg.fps).put("aspect", cfg.aspect).put("model", android.os.Build.MODEL).put("codec", cfg.codec)
            .put("lens", cfg.lens).put("zoom", cfg.zoom.toDouble()).put("torch", cfg.torch).put("night", cfg.night)
            .put("ev", cfg.ev).put("focus", cfg.focus).put("stab", cfg.stab)
            .put("fx", cfg.fx.toDouble()).put("fy", cfg.fy.toDouble()).put("fdist", cfg.fdist.toDouble())
            .put("camBusy", camBusy).put("audio", audioTrack != null).put("heat", heat).put("name", cfg.name)
            .put("heatGuard", cfg.heatGuard).put("heatStart", cfg.heatStart)
            .put("health", health())
            .put("tl", timelapse.json())
            .put("rec", recorder.json())
            .put("viewers", connectedViewers())
            .put("lenses", JSONArray().also { a -> lenses.forEach { a.put(JSONObject().put("k", it.key).put("label", it.label).put("front", it.front)) } })
        capturer?.caps?.let {
            o.put("caps", JSONObject().put("zoomMin", it.zoomMin.toDouble()).put("zoomMax", it.zoomMax.toDouble())
                .put("torch", it.hasTorch).put("evMin", it.evMin).put("evMax", it.evMax).put("evStep", it.evStep.toDouble())
                .put("focusInf", it.canFocusInf).put("stab", it.canStab).put("maxFps", it.maxFps)
                .put("focusPoint", it.canFocusPoint).put("focusLock", it.canFocusLock).put("focusManual", it.canFocusManual)
                .put("w", it.width).put("h", it.height).put("maxRes", Rtc.maxRes(cfg.codec, cfg.aspect)))
        }
        return o
    }

    // ---------------- Signaling / WebRTC ----------------

    private fun sendTo(p: Peer, msg: JSONObject) {
        msg.put("s", p.session)
        val sig = signaling ?: return
        if (p.owner) sig.send("$topic/v/${p.viewerId}", msg)
        else viewKey?.let { sig.send("$viewTopic/v/${p.viewerId}", msg, key = it) }
    }

    private fun peerId(viewerId: String, owner: Boolean) = (if (owner) "o:" else "v:") + viewerId

    /** [owner]: the message came through the main link; the view-only link can watch but never change anything. */
    private fun onSignal(m: JSONObject, owner: Boolean) {
        if (stopped) return
        val vid = m.optString("v")
        if (vid.isEmpty()) return
        val pid = peerId(vid, owner)
        val s = m.optInt("s")
        when (m.optString("t")) {
            "hello" -> onHello(vid, s, m, owner)
            "answer" -> peers[pid]?.takeIf { it.session == s }?.let { p ->
                p.pc.setRemoteDescription(SdpObs(onSet = {
                    exec.post {
                        if (p.disposed) return@post
                        p.remoteSet = true
                        p.pending.forEach { p.pc.addIceCandidate(it) }
                        p.pending.clear()
                        applyEncoding(p.sender)
                    }
                }), SessionDescription(SessionDescription.Type.ANSWER, m.getString("sdp")))
            }
            "ice" -> peers[pid]?.takeIf { it.session == s }?.let { p ->
                val c = m.getJSONObject("c")
                val ic = IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.getString("candidate"))
                if (p.remoteSet) p.pc.addIceCandidate(ic) else p.pending.add(ic)
            }
            "cfg" -> if (owner) changeConfig(m)
            "strobe" -> if (owner) strobe(m.optBoolean("on"))
            "bye" -> peers[pid]?.takeIf { it.session == s }?.let { removePeer(it) }
        }
    }

    private fun onHello(vid: String, session: Int, m: JSONObject, owner: Boolean) {
        val old = peers[peerId(vid, owner)]
        if (old != null && old.session == session) {
            // Viewer resent "hello" because it hasn't received the offer -> resend
            old.offer?.let { sendTo(old, info(JSONObject().put("t", "offer").put("sdp", it).put("ice", turnJson()), old)) }
            return
        }
        old?.let { removePeer(it) }
        while (peers.size >= MAX_VIEWERS) {
            // View-only viewers make room first, and never push out someone with full control
            val out = peers.values.firstOrNull { !it.owner } ?: if (owner) peers.values.first() else return
            removePeer(out)
        }

        val ice = DEFAULT_ICE.toMutableList()
        ice += parseIce(turnJson())
        m.optJSONArray("ice")?.let { ice += parseIce(it) }
        val rtc = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        var peer: Peer? = null
        val pc = factory.createPeerConnection(rtc, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) = exec.post {
                peer?.takeIf { !it.disposed }?.let {
                    sendTo(it, JSONObject().put("t", "ice").put("c",
                        JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)))
                }
            }
            override fun onConnectionChange(st: PeerConnection.PeerConnectionState) = exec.post {
                val p = peer ?: return@post
                if (p.disposed) return@post
                p.state = st
                if (st == PeerConnection.PeerConnectionState.FAILED || st == PeerConnection.PeerConnectionState.CLOSED) removePeer(p)
                else status(null)
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        }) ?: return

        val tr = pc.addTransceiver(track, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("cam")))
        // Before the offer, so it's part of it: timelapse videos travel over the same encrypted connection
        val dc = pc.createDataChannel("tl", DataChannel.Init().apply { ordered = true })
        val rdc = pc.createDataChannel("rec", DataChannel.Init().apply { ordered = true })
        audioTrack?.let { pc.addTransceiver(it, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("cam"))) }
        applyEncoding(tr.sender)
        val p = Peer(vid, session, pc, tr.sender, owner)
        dc?.let { p.files = TlChannel(it, timelapse, filePool, canDelete = owner) }
        // View-only viewers see recordings only when the owner allows it
        rdc?.let { p.rec = RecChannel(it, recorder, filePool, owner, { owner || recorder.settings.viewOnly }, { name }) }
        peer = p
        peers[p.id] = p
        updateIdle() // wake the camera while the connection is set up
        status(null)

        pc.createOffer(SdpObs(onCreate = { sdp ->
            exec.post {
                if (p.disposed) return@post
                pc.setLocalDescription(SdpObs(onSet = {
                    exec.post {
                        if (p.disposed) return@post
                        p.offer = sdp.description
                        sendTo(p, info(JSONObject().put("t", "offer").put("sdp", sdp.description).put("ice", turnJson()), p))
                    }
                }), sdp)
            }
        }), MediaConstraints())

        // Clean up "ghost" connections: still not connected after 45s
        exec.later({
            if (peers[p.id] === p && p.state != PeerConnection.PeerConnectionState.CONNECTED) removePeer(p)
        }, 45, TimeUnit.SECONDS)
    }

    /** TURN configured on the camera device, sent with the offer so the viewer can use it too (message is encrypted). */
    private fun turnJson(): JSONArray {
        val urls = Rtc.parseTurnUrls(cfg.turnUrls)
        if (urls.isEmpty()) return JSONArray()
        return JSONArray().put(JSONObject().put("urls", JSONArray(urls)).put("username", cfg.turnUser).put("credential", cfg.turnPass))
    }

    private fun parseIce(arr: JSONArray): List<PeerConnection.IceServer> = (0 until arr.length()).mapNotNull { i ->
        try {
            val o = arr.getJSONObject(i)
            val urls = o.get("urls").let { u -> if (u is JSONArray) (0 until u.length()).map { u.getString(it) } else listOf(u.toString()) }
            val b = PeerConnection.IceServer.builder(urls)
            if (o.has("username")) b.setUsername(o.getString("username"))
            if (o.has("credential")) b.setPassword(o.getString("credential"))
            b.createIceServer()
        } catch (e: Exception) { null }
    }

    /**
     * Idempotent: dispose() fires a CLOSED event -> onConnectionChange -> removePeer again;
     * calling dispose() twice on the same PeerConnection SIGSEGVs in native code (nativeClose).
     */
    private fun removePeer(p: Peer) {
        if (peers[p.id] === p) peers.remove(p.id)
        if (p.disposed) return
        p.disposed = true
        p.files?.close() // before the connection: the channels can't be used once the connection is gone
        p.rec?.close()
        try { p.pc.dispose() } catch (e: Exception) { Log.w(TAG, "dispose", e) }
        updateIdle()
        status(null)
    }

    private fun connectedViewers() = peers.values.count { it.state == PeerConnection.PeerConnectionState.CONNECTED }
    private var lastViewers = -1

    private fun status(msg: String?) {
        val viewers = connectedViewers()
        // Viewers see how many people are watching: tell them whenever it changes
        if (viewers != lastViewers) {
            lastViewers = viewers
            peers.values.forEach { sendTo(it, JSONObject().put("t", "info").put("viewers", viewers)) }
        }
        val text = msg ?: when {
            camBusy -> ctx.getString(R.string.st_cam_busy)
            brokersUp == 0 -> ctx.getString(R.string.st_no_internet)
            heat > 0 -> ctx.getString(R.string.st_hot)
            viewers > 0 -> ctx.resources.getQuantityString(R.plurals.st_viewers, viewers, viewers)
            timelapse.active -> ctx.resources.getQuantityString(R.plurals.st_timelapse, timelapse.frames, timelapse.frames)
            recorder.needsCamera -> ctx.getString(R.string.st_recording)
            camIdle -> ctx.getString(R.string.st_idle)
            else -> ctx.getString(R.string.st_ready)
        }
        lastStatus = Status(brokersUp, brokers.size, viewers, text)
        statusListener?.invoke(lastStatus)
        serviceListener?.invoke(lastStatus)
    }

    private class SdpObs(
        val onCreate: (SessionDescription) -> Unit = {},
        val onSet: () -> Unit = {},
    ) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = onCreate(sdp)
        override fun onSetSuccess() = onSet()
        override fun onCreateFailure(e: String?) { Log.e(TAG, "sdp create failure $e") }
        override fun onSetFailure(e: String?) { Log.e(TAG, "sdp set failure $e") }
    }
}
