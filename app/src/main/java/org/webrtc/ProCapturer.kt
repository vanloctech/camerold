// Lives in package org.webrtc to access WebRTC's internal helpers (CameraSession, TextureBufferImpl)
// handling frame rotation/mirroring exactly like WebRTC's Camera2Session.
package org.webrtc

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import vn.camerold.R

/**
 * Custom Camera2 capturer (replacing WebRTC's Camera2Capturer) to control:
 * lens selection (main / ultra-wide / front), sensor zoom, flash, night mode,
 * exposure compensation (EV), infinity focus, stabilization.
 */
class ProCapturer(context: Context, private val events: Events) : VideoCapturer {

    interface Events {
        fun onCameraError(msg: String)
        fun onFirstFrame()
        /** Camera taken by another (foreground) app; reacquired automatically when that app releases it. */
        fun onCameraBusy()
        /** A camera that isn't in the official list couldn't be opened: drop it and go back to the main lens. */
        fun onLensFailed(l: Lens) {}
    }

    /** A user-selectable "lens". [zoomPreset] != null: use zoom ratio on the logical camera (e.g. 0.6x). */
    data class Lens(
        val key: String,
        val cameraId: String,
        val physicalId: String?,
        val zoomPreset: Float?,
        val label: String,
        val front: Boolean,
        val rel: Float,
        val hidden: Boolean = false, // not in cameraIdList, found by probing (may refuse to open)
    ) {
        /** Ultra-wide picture: the 0.6x preset/zoom ratio below 1x, or a separate ultra-wide camera. */
        fun ultraWide(zoom: Float) = !front && (zoom < 0.95f || (rel < 0.85f && (zoomPreset ?: 1f) >= 1f))
    }

    data class Controls(
        val zoom: Float = 1f,
        val torch: Boolean = false,
        val night: Boolean = false,
        val ev: Int = 0,          // exposure compensation steps
        val focus: String = "auto", // "auto" | "point" | "lock" | "manual" | "inf"
        val fx: Float = 0.5f,       // focus point, normalized 0..1 coords on the frame the viewer sees (rotated upright)
        val fy: Float = 0.5f,
        val fdist: Float = 0f,      // manual: 0 = far (infinity) … 1 = closest
        val stab: Boolean = false,
        val lowPower: Boolean = false, // phone is warm: skip extra image processing
    )

    /** Capabilities of the open lens, sent to the viewer to render controls. */
    data class Caps(
        val zoomMin: Float, val zoomMax: Float, val hasTorch: Boolean,
        val evMin: Int, val evMax: Int, val evStep: Float,
        val canFocusInf: Boolean, val canStab: Boolean, val maxFps: Int,
        val canFocusPoint: Boolean, val canFocusLock: Boolean, val canFocusManual: Boolean,
        val width: Int, val height: Int,
    )

    companion object {
        private const val TAG = "ProCapturer"
        private val LOCKING = setOf("point", "lock")
        /** Hidden camera ids that failed to open in this process: don't offer them again. */
        private val badHidden = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        private fun equivFocal(ch: CameraCharacteristics): Float? {
            val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
            val s = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            return f * 43.27f / hypot(s.width, s.height)
        }

        private fun zoomRange(ch: CameraCharacteristics): Range<Float>? =
            if (Build.VERSION.SDK_INT >= 30) ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null

        // Locale.US: a Vietnamese-locale device would print "0,6" instead of "0.6"
        private fun fmt(x: Float) = if (abs(x - x.roundToInt()) < 0.05f) "${x.roundToInt()}" else String.format(java.util.Locale.US, "%.1f", x)

        /** Lists every lens the app can use: regular cameras, physical cameras inside logical ones, ultra-wide via zoom < 1. */
        fun listLenses(ctx: Context): List<Lens> {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val raw = mutableListOf<Triple<String, String?, CameraCharacteristics>>()
            for (id in cm.cameraIdList) {
                val ch = try { cm.getCameraCharacteristics(id) } catch (e: Exception) { continue }
                raw += Triple(id, null, ch)
                if (Build.VERSION.SDK_INT >= 28 && ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                        ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true) {
                    for (pid in ch.physicalCameraIds) {
                        try { raw += Triple(id, pid, cm.getCameraCharacteristics(pid)) } catch (_: Exception) {}
                    }
                }
            }
            // Many older Samsung / Xiaomi / Oppo phones leave the ultra-wide or telephoto out of cameraIdList,
            // yet it opens by its id. Probe the small ids that aren't listed (or part of a listed logical camera).
            val known = raw.flatMap { listOfNotNull(it.first, it.second) }.toSet()
            val hidden = mutableSetOf<String>()
            for (n in 0..9) {
                val id = n.toString()
                if (id in known || id in badHidden) continue
                val ch = try { cm.getCameraCharacteristics(id) } catch (_: Exception) { continue }
                if (ch.get(CameraCharacteristics.LENS_FACING) == null ||
                    ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) == null) continue
                raw += Triple(id, null, ch)
                hidden += id
            }
            val isFront = { ch: CameraCharacteristics -> ch.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT }
            val mainBack = raw.firstOrNull { it.second == null && !isFront(it.third) }
            val ref = mainBack?.let { equivFocal(it.third) } ?: 26f
            val mainFront = raw.firstOrNull { it.second == null && isFront(it.third) }
            val refFront = mainFront?.let { equivFocal(it.third) } ?: ref

            val out = mutableListOf<Lens>()
            // Ultra-wide via CONTROL_ZOOM_RATIO (Android 11+): the official way, smooth switching
            val mainZoom = mainBack?.let { zoomRange(it.third) }
            if (mainBack != null && mainZoom != null && mainZoom.lower < 0.95f) {
                out += Lens("${mainBack.first}@${fmt(mainZoom.lower)}", mainBack.first, null, mainZoom.lower,
                    ctx.getString(R.string.lens_wide, fmt(mainZoom.lower)), false, mainZoom.lower)
            }
            for ((id, pid, ch) in raw) {
                val front = isFront(ch)
                val rel = (equivFocal(ch) ?: ref) / ref
                val relF = (equivFocal(ch) ?: refFront) / refFront
                val label = when {
                    front && relF < 0.85f -> ctx.getString(R.string.lens_front_wide)
                    front && relF > 1.3f -> ctx.getString(R.string.lens_front_zoom, fmt(relF))
                    front -> ctx.getString(R.string.lens_front)
                    rel < 0.85f -> ctx.getString(R.string.lens_wide, fmt(rel))
                    rel > 1.3f -> ctx.getString(R.string.lens_tele, fmt(rel))
                    else -> ctx.getString(R.string.lens_main)
                }
                val lens = Lens(if (pid == null) id else "$id/$pid", id, pid, if (pid == null && !front) 1f else null, label, front, rel,
                    hidden = pid == null && id in hidden)
                // Dedupe: same facing and nearly the same focal length
                if (out.any { it.front == front && abs(it.rel - rel) / rel < 0.08f }) continue
                out += lens
            }
            return out.sortedWith(compareBy({ it.front }, { it.rel }))
        }
    }

    private val appCtx = context.applicationContext
    private val cm = appCtx.getSystemService(CameraManager::class.java)
    private lateinit var helper: SurfaceTextureHelper
    private lateinit var observer: CapturerObserver

    @Volatile var lens: Lens? = null; private set
    @Volatile var controls = Controls(); private set
    @Volatile var caps: Caps? = null; private set

    private var width = 1920
    private var height = 1080
    private var fps = 30
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var ch: CameraCharacteristics? = null      // of the open lens (physical if any)
    private var logicalCh: CameraCharacteristics? = null
    private var sensorOrientation = 90
    private var front = false
    @Volatile private var lastRotation = 90
    private var running = false
    private var firstFrame = false
    private var openGen = 0

    override fun initialize(h: SurfaceTextureHelper, ctx: Context, obs: CapturerObserver) {
        helper = h
        observer = obs
        cm.registerAvailabilityCallback(availability, h.handler)
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    /**
     * Actual device orientation from the accelerometer (0/90/180/270), -1 = unknown.
     * Security cameras are often placed in landscape with auto-rotate off: relying only on display rotation,
     * the sent image would be sideways.
     */
    @Volatile private var physicalRotation = -1
    private val orientationListener = object : android.view.OrientationEventListener(appCtx) {
        override fun onOrientationChanged(o: Int) {
            if (o == ORIENTATION_UNKNOWN) return // device lying flat: keep previous orientation
            val cur = physicalRotation
            if (cur >= 0) {
                val diff = abs(o - cur).let { minOf(it, 360 - it) }
                if (diff < 60) return // hysteresis to avoid flipping around ~45°
            }
            physicalRotation = ((o + 45) / 90 * 90) % 360
        }
    }

    /** Waiting for another app to release the camera. */
    private var waitingForCamera = false

    // Android notifies as soon as the camera is free again -> reopen immediately, no retry loop wait
    private val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(id: String) {
            if (running && waitingForCamera && id == lens?.cameraId) {
                waitingForCamera = false
                close(); open()
            }
        }
    }

    private fun busy() {
        waitingForCamera = true
        close()
        events.onCameraBusy()
    }

    fun setLens(l: Lens) = post {
        val reopen = lens?.cameraId != l.cameraId || lens?.physicalId != l.physicalId
        lens = l
        l.zoomPreset?.let { controls = controls.copy(zoom = it) } ?: run { controls = controls.copy(zoom = 1f) }
        if (running) { if (reopen) { close(); open() } else applyRequest(controls.focus in LOCKING) }
    }

    fun setControls(c: Controls) = post {
        val old = controls
        controls = c
        // Spot / locked focus: rerun a single AF scan when the mode or point changes
        val trigger = c.focus in LOCKING && (c.focus != old.focus || c.fx != old.fx || c.fy != old.fy)
        if (running) applyRequest(trigger)
    }

    override fun startCapture(w: Int, h: Int, f: Int) = post {
        width = w; height = h; fps = f
        running = true
        open()
    }

    override fun stopCapture() {
        ThreadUtils.invokeAtFrontUninterruptibly(helper.handler) {
            running = false
            close()
        }
        observer.onCapturerStopped()
    }

    override fun changeCaptureFormat(w: Int, h: Int, f: Int) = post {
        width = w; height = h; fps = f
        if (running) { close(); open() }
    }

    override fun dispose() {
        orientationListener.disable()
        stopCapture()
        try { cm.unregisterAvailabilityCallback(availability) } catch (_: Exception) {}
    }
    override fun isScreencast() = false

    private fun post(r: () -> Unit) { helper.handler.post(r) }

    // ---------------- open / close camera (always on the helper's camera thread) ----------------

    private fun open() {
        val l = lens ?: return
        val gen = ++openGen
        firstFrame = false
        try {
            logicalCh = cm.getCameraCharacteristics(l.cameraId)
            ch = l.physicalId?.let { cm.getCameraCharacteristics(it) } ?: logicalCh
            front = ch!!.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
            sensorOrientation = ch!!.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            cm.openCamera(l.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (gen != openGen || !running) { d.close(); return }
                    device = d
                    createSession(gen)
                }
                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    // Camera taken by another app (the foreground app always has priority)
                    if (gen == openGen && running) { if (l.hidden && !firstFrame) openFailed(appCtx.getString(R.string.cam_open_failed)) else busy() }
                }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    if (gen != openGen || !running) return
                    if (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE || error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE) busy()
                    else openFailed(appCtx.getString(R.string.cam_error))
                }
            }, helper.handler)
        } catch (e: android.hardware.camera2.CameraAccessException) {
            Log.w(TAG, "open: ${e.reason}", e)
            if (l.hidden) openFailed(appCtx.getString(R.string.cam_open_failed))
            else if (e.reason == android.hardware.camera2.CameraAccessException.CAMERA_IN_USE ||
                e.reason == android.hardware.camera2.CameraAccessException.MAX_CAMERAS_IN_USE ||
                e.reason == android.hardware.camera2.CameraAccessException.CAMERA_DISCONNECTED) busy()
            else openFailed(appCtx.getString(R.string.cam_open_failed))
        } catch (e: Exception) {
            Log.e(TAG, "open", e)
            openFailed(appCtx.getString(R.string.cam_open_failed))
        }
    }

    /** A hidden (probed) camera that won't open is dropped instead of retried forever. */
    private fun openFailed(msg: String) {
        val l = lens
        if (l?.hidden == true) {
            Log.w(TAG, "hidden camera ${l.cameraId} can't be used")
            badHidden += l.cameraId
            events.onLensFailed(l)
        } else events.onCameraError(msg)
    }

    private fun chooseSize(): Size {
        val map = ch?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: logicalCh!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(width, height)
        val target = width.toLong() * height
        val sameAspect = sizes.filter { abs(it.width * height - it.height * width) <= width } // ~same aspect ratio
        return (sameAspect.ifEmpty { sizes }).minBy { abs(it.width.toLong() * it.height - target) }
    }

    private fun maxFpsFor(size: Size): Int {
        val map = ch?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: logicalCh?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return 30
        val d = try { map.getOutputMinFrameDuration(SurfaceTexture::class.java, size) } catch (_: Exception) { 0L }
        return if (d > 0) (1_000_000_000L / d).toInt() else 30
    }

    private fun createSession(gen: Int) {
        val d = device ?: return
        val size = chooseSize()
        helper.setTextureSize(size.width, size.height)
        helper.surfaceTexture.setDefaultBufferSize(size.width, size.height)
        val s = Surface(helper.surfaceTexture)
        surface = s
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(cs: CameraCaptureSession) {
                if (gen != openGen || !running) { cs.close(); return }
                session = cs
                updateCaps(size)
                helper.startListening(::onFrame)
                applyRequest(controls.focus in LOCKING)
                observer.onCapturerStarted(true)
            }
            override fun onConfigureFailed(cs: CameraCaptureSession) {
                cs.close()
                if (gen == openGen) openFailed(appCtx.getString(R.string.cam_unsupported))
            }
        }
        try {
            val pid = lens?.physicalId
            if (pid != null && Build.VERSION.SDK_INT >= 28) {
                val oc = OutputConfiguration(s).apply { setPhysicalCameraId(pid) }
                val exec = Executor { helper.handler.post(it) }
                d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(oc), exec, cb))
            } else {
                @Suppress("DEPRECATION")
                d.createCaptureSession(listOf(s), cb, helper.handler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "createSession", e)
            openFailed(appCtx.getString(R.string.cam_open_failed))
        }
    }

    private fun close() {
        openGen++
        try { helper.stopListening() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        surface?.release()
        session = null; device = null; surface = null
    }

    private fun onFrame(frame: VideoFrame) {
        if (!running || session == null) return
        if (!firstFrame) { firstFrame = true; events.onFirstFrame() }
        // Convert to the "display orientation" convention used by WebRTC's formula
        var rotation = physicalRotation.let { if (it >= 0) (360 - it) % 360 else CameraSession.getDeviceOrientation(appCtx) }
        if (!front) rotation = 360 - rotation
        lastRotation = (sensorOrientation + rotation) % 360
        val out = VideoFrame(
            CameraSession.createTextureBufferWithModifiedTransformMatrix(frame.buffer as TextureBufferImpl, front, -sensorOrientation),
            (sensorOrientation + rotation) % 360,
            frame.timestampNs,
        )
        observer.onFrameCaptured(out)
        out.release()
    }

    // ---------------- controls ----------------

    private fun updateCaps(size: Size) {
        val lc = logicalCh ?: return
        val c = ch ?: lc
        val zr = if (lens?.physicalId == null) zoomRange(lc) else null
        val maxDigital = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val ev = lc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val evStep = lc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val afModes = lc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val stabModes = lc.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
        val maxAfRegions = lc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val hasAfAuto = afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)
        caps = Caps(
            zoomMin = zr?.lower ?: 1f, zoomMax = zr?.upper ?: maxDigital,
            hasTorch = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            evMin = ev?.lower ?: 0, evMax = ev?.upper ?: 0, evStep = evStep,
            canFocusInf = minFocus > 0f && afModes.contains(CameraMetadata.CONTROL_AF_MODE_OFF),
            canStab = stabModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON),
            maxFps = maxFpsFor(size), width = size.width, height = size.height,
            canFocusPoint = hasAfAuto && maxAfRegions > 0, canFocusLock = hasAfAuto,
            canFocusManual = minFocus > 0f && afModes.contains(CameraMetadata.CONTROL_AF_MODE_OFF),
        )
    }

    private fun fpsRange(night: Boolean, maxFps: Int): Range<Int>? {
        val ranges = logicalCh?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        val factor = if (ranges.any { it.upper > 1000 }) 1000 else 1
        val want = minOf(fps, maxFps)
        val list = ranges.map { Range(it.lower / factor, it.upper / factor) to it }
        val sameTop = list.filter { it.first.upper == want }.ifEmpty { list.filter { it.first.upper <= want }.ifEmpty { list } }
        // Night: let fps drop low for longer exposure -> brighter image. Day: keep fps steady.
        val pick = if (night) sameTop.minBy { it.first.lower } else sameTop.maxBy { it.first.lower }
        return pick.second
    }

    /** Current FOV in pixel-array coordinates, same coordinate system as 3A regions (AF/AE regions). */
    private fun fovRect(z: Float): Rect? {
        val lc = logicalCh ?: return null
        val a = (ch ?: lc).get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        // Android 11+ with CONTROL_ZOOM_RATIO: 3A coordinates = the zoomed region, spread over the whole array
        if (usesZoomRatio() || z <= 1.01f) return Rect(0, 0, a.width(), a.height())
        val w = (a.width() / z).toInt(); val h = (a.height() / z).toInt()
        val l = (a.width() - w) / 2; val t = (a.height() - h) / 2
        return Rect(l, t, l + w, t + h)
    }

    private fun usesZoomRatio() = Build.VERSION.SDK_INT >= 30 && lens?.physicalId == null && logicalCh?.let { zoomRange(it) } != null

    /**
     * Maps the viewer's tap point (fx, fy) - on the upright-rotated frame - to a metering region on the sensor:
     * undo frame rotation -> undo mirroring (front camera) -> compensate the crop of a 16:9 stream from a 4:3 sensor -> FOV.
     */
    private fun meteringRegion(c: Controls, cap: Caps): MeteringRectangle? {
        var (sx, sy) = when (lastRotation) {
            90 -> c.fy to 1f - c.fx
            180 -> 1f - c.fx to 1f - c.fy
            270 -> 1f - c.fy to c.fx
            else -> c.fx to c.fy
        }
        if (front) sx = 1f - sx
        val fov = fovRect(c.zoom.coerceIn(cap.zoomMin, cap.zoomMax)) ?: return null
        val fovAspect = fov.width().toFloat() / fov.height()
        val outAspect = maxOf(cap.width, cap.height).toFloat() / minOf(cap.width, cap.height)
        if (outAspect > fovAspect) sy = 0.5f + (sy - 0.5f) * fovAspect / outAspect
        else sx = 0.5f + (sx - 0.5f) * outAspect / fovAspect
        val cx = fov.left + sx.coerceIn(0f, 1f) * fov.width()
        val cy = fov.top + sy.coerceIn(0f, 1f) * fov.height()
        val half = minOf(fov.width(), fov.height()) * 0.08f
        val l = (cx - half).toInt().coerceIn(fov.left, fov.right - 1)
        val t = (cy - half).toInt().coerceIn(fov.top, fov.bottom - 1)
        val r = (cx + half).toInt().coerceIn(l + 1, fov.right)
        val btm = (cy + half).toInt().coerceIn(t + 1, fov.bottom)
        return MeteringRectangle(Rect(l, t, r, btm), MeteringRectangle.METERING_WEIGHT_MAX)
    }

    private fun applyRequest(trigger: Boolean = false) {
        val d = device ?: return
        val cs = session ?: return
        val s = surface ?: return
        val lc = logicalCh ?: return
        val cap = caps ?: return
        val c = controls
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(s)
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            fpsRange(c.night, cap.maxFps)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, c.ev.coerceIn(cap.evMin, cap.evMax))
            if (cap.hasTorch) b.set(CaptureRequest.FLASH_MODE,
                if (c.torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)

            // Sensor zoom: crop at full resolution then downscale -> sharper than zooming on the viewer
            val z = c.zoom.coerceIn(cap.zoomMin, cap.zoomMax)
            if (usesZoomRatio()) {
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
            } else if (z > 1.01f) {
                val a = (ch ?: lc).get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (a != null) {
                    val w = (a.width() / z).toInt(); val h = (a.height() / z).toInt()
                    val l = (a.width() - w) / 2; val t = (a.height() - h) / 2
                    b.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, l + w, t + h))
                }
            }

            var afTrigger = false
            when {
                c.focus == "inf" && cap.canFocusInf -> {
                    b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                }
                c.focus == "manual" && cap.canFocusManual -> {
                    val minFocus = (ch ?: lc).get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                    b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    b.set(CaptureRequest.LENS_FOCUS_DISTANCE, c.fdist.coerceIn(0f, 1f) * minFocus) // in diopters
                }
                c.focus == "point" && cap.canFocusPoint -> {
                    b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                    meteringRegion(c, cap)?.let { r ->
                        b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(r))
                        // Also meter exposure at that point (e.g. picking a dark window brightens the image)
                        if ((lc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0)
                            b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(r))
                    }
                    afTrigger = trigger
                }
                c.focus == "lock" && cap.canFocusLock -> {
                    b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                    afTrigger = trigger
                }
                else -> {
                    val af = lc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                    if (af.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO))
                        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
            }
            if (cap.canStab) b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (c.stab) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            val nr = lc.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
            if (c.night) {
                if (nr.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY))
                    b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            } else if (lens?.ultraWide(z) == true && !c.lowPower) {
                // The ultra-wide's small pixels make the default processing smear fine detail:
                // sharpen harder and only lightly denoise (noise is low in good light, where 0.6x is used)
                val edge = lc.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()
                if (edge.contains(CameraMetadata.EDGE_MODE_HIGH_QUALITY))
                    b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
                if (nr.contains(CameraMetadata.NOISE_REDUCTION_MODE_FAST))
                    b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
            }
            cs.setRepeatingRequest(b.build(), null, helper.handler)
            if (afTrigger) {
                // Cancel the previous scan then run one AF scan; afterwards AF_MODE_AUTO holds (locks) focus
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                cs.capture(b.build(), null, helper.handler)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                cs.capture(b.build(), null, helper.handler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyRequest", e)
        }
    }
}
