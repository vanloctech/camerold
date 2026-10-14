package vn.camerold.camera

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoFrameDrawer
import org.webrtc.VideoSink
import vn.camerold.data.CamConfig
import vn.camerold.signaling.post

/**
 * Continuous recording on the camera phone, like a security camera's recorder.
 *
 * Every camera frame passes by ([onFrame]); at the recording frame rate one is drawn onto a hardware video encoder
 * (HEVC when the phone has one, else H.264) at its own size, independent of what viewers watch. The encoded video is
 * written in one-minute pieces (files/rec/live/<id>/): a piece is only readable once closed, so a crash or power cut
 * loses at most about a minute. Each half hour (on the clock: :00 and :30), the pieces are joined into one video
 * without encoding again ([Mp4Cat]): files/rec/<yyyy-MM-dd>/<HHmmss>.mp4, with a thumbnail (.jpg) and its details
 * (.json). Pieces left by a crash are joined the next time the camera starts ([recover]).
 *
 * Old recordings are deleted by themselves (oldest first): after [Settings.days] days, and whenever free storage falls
 * under [minFree]. Recordings marked "keep" are never deleted by that. Viewers list, download, cut, keep and delete
 * them over the "rec" data channel ([RecChannel], docs/PROTOCOL.md).
 */
class Recorder(
    private val ctx: Context,
    private val exec: ScheduledExecutorService,
    /** The camera's GL context: frames are textures in it, drawn onto the encoder's surface. */
    private val shared: EglBase.Context,
    /** Something viewers show changed (state, list of recordings). Called on any thread. */
    private val changed: (listChanged: Boolean) -> Unit,
) : VideoSink {

    companion object {
        private const val TAG = "Recorder"
        val QUALITIES = listOf(720, 1080)
        val FPS = listOf(10, 15)
        /** Days kept; 0 = until storage runs short. */
        val KEEP_DAYS = listOf(3, 7, 14, 30, 0)
        const val SEGMENT_MIN = 30
        /** Length of the pieces written while recording (the most a crash can lose). */
        const val PIECE_US = 60_000_000L
        /** No frame for this long (camera busy or restarting): the recording so far is closed, a new one starts after. */
        const val GAP_NS = 5_000_000_000L
        private const val KEY_FRAME_S = 4
        private const val THUMB_W = 320
        val ID = Regex("\\d{8}-\\d{6}")

        /** Free storage the recorder always leaves (for the phone and timelapses): it deletes old recordings first. */
        fun minFree(total: Long): Long = if (total in 1 until (16L shl 30)) 500L shl 20 else 1L shl 30

        /** Encoder bitrate (variable: still scenes use much less). About 270 MB an hour for HEVC 720p at 15 fps. */
        fun bitrate(w: Int, h: Int, fps: Int, hevc: Boolean): Int =
            (w.toLong() * h * fps * (if (hevc) 0.045 else 0.075)).toLong().coerceIn(300_000, 8_000_000).toInt()

        /** Which half hour of the local day a time falls in: recordings are cut when it changes. */
        fun slotOf(wall: Long, tz: TimeZone = TimeZone.getDefault()): Long =
            Math.floorDiv(wall + tz.getOffset(wall), SEGMENT_MIN * 60_000L)

        /** Recording frame rate while the phone is hot (see [ThermalGuard]): fewer frames, less work. */
        fun fpsFor(fps: Int, heat: Int) = when { heat >= 3 -> minOf(fps, 5); heat == 2 -> minOf(fps, 10); else -> fps }

        private fun isHardware(ci: MediaCodecInfo) = if (Build.VERSION.SDK_INT >= 29) ci.isHardwareAccelerated
            else !ci.name.startsWith("OMX.google.") && !ci.name.startsWith("c2.android.")

        /** Encoder for [mime] that can do this size; HEVC only in hardware (software HEVC is far too slow). */
        fun encoderFor(mime: String, w: Int, h: Int): String? = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { ci -> ci.isEncoder && ci.supportedTypes.any { it.equals(mime, true) } &&
                    (mime == MediaFormat.MIMETYPE_VIDEO_AVC || isHardware(ci)) &&
                    try { ci.getCapabilitiesForType(mime).videoCapabilities.isSizeSupported(w, h) } catch (_: Exception) { false } }
                .sortedByDescending { isHardware(it) }.firstOrNull()?.name
        } catch (_: Exception) { null }

        fun canHevc() = encoderFor(MediaFormat.MIMETYPE_VIDEO_HEVC, 1280, 720) != null

        /** Bytes an hour at most for these settings (16:9; variable bitrate makes it less in practice). */
        fun bytesPerHour(q: Int, fps: Int, hevc: Boolean): Long = bitrate(q * 16 / 9, q, fps, hevc).toLong() * 3600 / 8

        fun idOf(wall: Long): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(wall))
        fun dayOf(id: String) = "${id.substring(0, 4)}-${id.substring(4, 6)}-${id.substring(6, 8)}"

        /** Where recordings are kept: files/rec (only this app can read it). */
        fun rootOf(ctx: Context) = File(ctx.filesDir, "rec")
        fun fileOf(root: File, id: String, ext: String) = File(File(root, dayOf(id)), id.substring(9) + ext)
        private val DAY_DIR = Regex("\\d{4}-\\d{2}-\\d{2}")

        /** Finished recordings on disk, in time order. Also used without the camera running (the camera phone's own list). */
        fun stored(root: File): List<Seg> {
            val m = TreeMap<String, Seg>()
            root.listFiles { f -> f.isDirectory && f.name.matches(DAY_DIR) }?.forEach { d ->
                d.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
                    try {
                        val s = Seg.from(JSONObject(f.readText()))
                        if (fileOf(root, s.id, ".mp4").exists()) m[s.id] = s
                    } catch (_: Exception) {}
                }
            }
            return m.values.toList()
        }

        /** Name of a recording saved to the phone or a computer: "Camerold <camera> <yyyy-MM-dd HH.mm.ss>.mp4". */
        fun fileName(start: Long, name: String): String {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date(start))
            return vn.camerold.Gallery.safeName("Camerold ${name.ifEmpty { Build.MODEL }} $stamp.mp4")
        }

        /** Deletes a finished recording's files (and its day folder once empty). */
        fun removeFiles(root: File, id: String) {
            if (!id.matches(ID)) return
            listOf(".mp4", ".jpg", ".json").forEach { fileOf(root, id, it).delete() }
            File(root, dayOf(id)).takeIf { it.list()?.isEmpty() == true }?.delete()
        }

        /**
         * Recordings to delete, oldest first: older than [days] days (0 = no limit), then as many as needed to get
         * [minFree] free. Kept ones are never picked.
         */
        fun pickDeletions(items: Collection<Seg>, now: Long, days: Int, free: Long, minFree: Long): List<String> {
            val out = ArrayList<String>()
            var f = free
            for (s in items.filter { !it.keep }.sortedBy { it.start }) {
                val old = days > 0 && s.start < now - days * 86_400_000L
                if (old || f < minFree) { out += s.id; f += s.size }
            }
            return out
        }
    }

    /** Recording settings (CamConfig rec*); [rot] turns the picture like the viewer who turned it on shows it. */
    data class Settings(val on: Boolean, val q: Int, val fps: Int, val days: Int, val h264: Boolean, val viewOnly: Boolean, val rot: Int) {
        companion object {
            fun of(c: CamConfig) = Settings(c.recOn, if (c.recQ == 1080) 1080 else 720, if (c.recFps == 10) 10 else 15,
                c.recDays.takeIf { it in KEEP_DAYS } ?: 7, c.recH264, c.recViewOnly, ((c.recRot % 360) + 360) % 360)
        }
    }

    /** A finished recording. */
    class Seg(val id: String, val start: Long, val dur: Double, val size: Long, val w: Int, val h: Int, val codec: String,
              val fps: Int, val keep: Boolean) {
        val end get() = start + (dur * 1000).toLong()
        fun json(): JSONObject = JSONObject().put("id", id).put("start", start).put("end", end).put("dur", dur).put("size", size)
            .put("w", w).put("h", h).put("codec", codec).put("fps", fps).put("keep", keep)
        fun withKeep(k: Boolean) = Seg(id, start, dur, size, w, h, codec, fps, k)

        companion object {
            fun from(o: JSONObject) = Seg(o.getString("id"), o.getLong("start"), o.optDouble("dur"), o.optLong("size"),
                o.optInt("w"), o.optInt("h"), o.optString("codec"), o.optInt("fps"), o.optBoolean("keep"))
        }
    }

    /** The recording being written: its pieces are in [dir]. */
    private class Live(val id: String, val start: Long, val dir: File, val baseUs: Long, val slot: Long) {
        var seq = 0
    }

    /** The encoder and what it's writing (recording thread only). */
    private class Enc(val codec: MediaCodec, val egl: EglBase, val surface: Surface, val w: Int, val h: Int, val mime: String,
                      val s: Settings) {
        val drawer = GlRectDrawer()
        val frames = VideoFrameDrawer()
        val info = MediaCodec.BufferInfo()
        var format: MediaFormat? = null
        var live: Live? = null
        var muxer: MediaMuxer? = null
        var track = -1
        var piece: File? = null
        var pieceUs = 0L
        var lastUs = -1L
        var lastNs = 0L
        /** When the last frame was drawn (elapsedRealtime). */
        var lastAt = 0L
        /** Start a new recording at the next key frame (half hour passed). */
        var cut = false
    }

    val root = rootOf(ctx).apply { mkdirs() }
    private val liveRoot = File(root, "live").apply { mkdirs() }
    private val tmpRoot = File(root, "tmp").apply { deleteRecursively(); mkdirs() }

    private val thread = HandlerThread("recorder").apply { start() }
    private val handler = Handler(thread.looper)
    /** Joining pieces, thumbnails, deleting old recordings. */
    private val io = Executors.newSingleThreadExecutor { Thread(it, "recorder-io") }

    @Volatile var settings = Settings(false, 720, 15, 7, false, false, 0)
        private set
    /** Why recording is paused or failed: "storage" (full, nothing left to delete), "encode" (no encoder). */
    @Volatile var event: String? = null
        private set
    @Volatile private var paused = false
    @Volatile private var heat = 0
    /** Frames wanted with these settings; null = not recording. */
    @Volatile private var want: Settings? = null
    @Volatile private var live: Live? = null
    @Volatile private var encMime: String? = null
    /** Bumped whenever the list of recordings changes, so viewers know to fetch it again. */
    @Volatile var listVersion = 0
        private set

    /** Finished recordings by id (sorted by time). */
    private val index = TreeMap<String, Seg>()

    private var enc: Enc? = null
    private var retryAt = 0L
    private var nextNs = 0L
    private val busy = AtomicBoolean(false)

    /** The camera has to stay on for this. */
    val needsCamera get() = want != null

    init { io.execute { loadIndex() } }

    // ---------------- Control (engine thread) ----------------

    fun configure(s: Settings) {
        val before = settings
        settings = s
        if (!s.on) { paused = false; event = null }
        update()
        if (before.days != s.days) io.execute { retention() }
        if (before != s) changed(false)
    }

    fun setHeat(level: Int) { heat = level }

    private fun update() {
        val w = settings.takeIf { it.on && !paused }
        if (want == w) return
        want = w
        // Off, paused, or other settings: the recording so far is finished (other settings start a new one)
        handler.post { if (enc != null && enc?.s != w) closeEnc() }
    }

    /** Every few seconds: no frames for a while, storage back. */
    fun tick() {
        handler.post {
            val e = enc ?: return@post
            if (e.lastAt > 0 && SystemClock.elapsedRealtime() - e.lastAt > GAP_NS / 1_000_000 * 2) closeEnc()
        }
        if (paused && event == "storage" && free().let { (f, t) -> f > minFree(t) + (256L shl 20) }) {
            paused = false; event = null; update(); changed(false)
        }
    }

    /** Joins recordings left unfinished by a crash. */
    fun recover() {
        val current = live?.dir
        liveRoot.listFiles()?.filter { it.isDirectory && it != current }?.forEach { d -> io.execute { finish(d) } }
    }

    /** Camera stopping: the recording so far is finished (joined in the background), then the threads end. */
    fun shutdown() {
        want = null
        val done = CountDownLatch(1)
        handler.post {
            try { closeEnc() } finally { io.shutdown(); done.countDown() }
        }
        thread.quitSafely()
        try { done.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
    }

    // ---------------- Frames (camera thread) ----------------

    override fun onFrame(frame: VideoFrame) {
        val s = want ?: return
        val ts = frame.timestampNs
        val step = 1_000_000_000L / fpsFor(s.fps, heat)
        // Frames come at the camera's rate (e.g. 30 fps): keep one per step, with some slack for jitter
        if (ts < nextNs - step / 4) return
        if (!busy.compareAndSet(false, true)) return // still drawing the last one: skip
        nextNs = if (ts - nextNs > step) ts + step else nextNs + step
        frame.retain()
        val posted = handler.post {
            try { draw(frame, s) } catch (e: Exception) { Log.w(TAG, "draw", e); closeEnc() } finally { frame.release(); busy.set(false) }
        }
        if (!posted) { frame.release(); busy.set(false) }
    }

    // ---------------- Encoding (recording thread) ----------------

    private fun draw(frame: VideoFrame, s: Settings) {
        if (want != s) return
        val rot = (frame.rotation + s.rot) % 360
        val b = frame.buffer
        val (sw, sh) = if (rot % 180 == 0) b.width to b.height else b.height to b.width
        val ts = frame.timestampNs
        var e = enc
        // Other settings or a gap in the frames: finish this recording, start another. A different picture size
        // (a viewer joining changes the camera's format) doesn't: it's fitted into this recording's size below.
        if (e != null && (e.s != s || (e.lastNs > 0 && ts - e.lastNs > GAP_NS))) {
            closeEnc(); e = null
        }
        if (e == null) {
            if (SystemClock.elapsedRealtime() < retryAt) return
            val (w, h) = Timelapse.fit(sw, sh, s.q)
            e = openEnc(w, h, s)
            if (e == null) {
                retryAt = SystemClock.elapsedRealtime() + 60_000
                if (event != "encode") { event = "encode"; changed(false) }
                return
            }
            enc = e
            encMime = e.mime
            if (event == "encode") event = null
            changed(false)
        }
        e.live?.let { l ->
            if (!e.cut && slotOf(wallOf(ts)) != l.slot) {
                e.cut = true
                // Half hour passed: ask for a key frame now so the new recording starts right on time
                try { e.codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } catch (_: Exception) {}
            }
        }
        e.lastNs = ts
        e.lastAt = SystemClock.elapsedRealtime()
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        // Whole picture, centered, black bars if its shape changed since the recording started
        val k = minOf(e.w.toFloat() / sw, e.h.toFloat() / sh)
        val vw = Math.round(sw * k); val vh = Math.round(sh * k)
        e.frames.drawFrame(VideoFrame(b, rot, ts), e.drawer, null, (e.w - vw) / 2, (e.h - vh) / 2, vw, vh)
        e.egl.swapBuffers(ts)
        drain(e, false)
    }

    /** Time on the clock of a frame time (System.nanoTime base, as camera frames are). */
    private fun wallOf(ns: Long): Long {
        val ago = System.nanoTime() - ns
        return if (ago in 0..60_000_000_000L) System.currentTimeMillis() - ago / 1_000_000 else System.currentTimeMillis()
    }

    /** HEVC recording possible on this phone (asked once). */
    val hevcSupported: Boolean by lazy { canHevc() }

    private fun openEnc(w: Int, h: Int, s: Settings): Enc? {
        val mimes = if (s.h264) listOf(MediaFormat.MIMETYPE_VIDEO_AVC) else listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
        for (mime in mimes) {
            val name = encoderFor(mime, w, h) ?: continue
            var codec: MediaCodec? = null
            var surface: Surface? = null
            var egl: EglBase? = null
            try {
                codec = MediaCodec.createByCodecName(name)
                val hevc = mime == MediaFormat.MIMETYPE_VIDEO_HEVC
                val caps = codec.codecInfo.getCapabilitiesForType(mime)
                val fmt = MediaFormat.createVideoFormat(mime, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate(w, h, s.fps, hevc))
                    setInteger(MediaFormat.KEY_FRAME_RATE, s.fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_S)
                    // Variable bitrate: a still room costs almost nothing
                    if (caps.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true)
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = codec.createInputSurface()
                egl = EglBase.create(shared, EglBase.CONFIG_RECORDABLE)
                egl.createSurface(surface)
                egl.makeCurrent()
                codec.start()
                Log.i(TAG, "recording ${w}x$h ${s.fps} fps with $name ($mime)")
                return Enc(codec, egl, surface, w, h, mime, s)
            } catch (e: Exception) {
                Log.w(TAG, "encoder $name ${w}x$h", e)
                try { egl?.release() } catch (_: Exception) {}
                try { surface?.release() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun drain(e: Enc, end: Boolean) {
        val until = SystemClock.elapsedRealtime() + 3000
        while (true) {
            val i = try { e.codec.dequeueOutputBuffer(e.info, if (end) 10_000 else 0) } catch (_: IllegalStateException) { return }
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end || SystemClock.elapsedRealtime() > until) return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> e.format = e.codec.outputFormat
                i >= 0 -> {
                    val inf = e.info
                    val buf = e.codec.getOutputBuffer(i)
                    if (buf != null && inf.size > 0 && inf.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) write(e, buf, inf)
                    e.codec.releaseOutputBuffer(i, false)
                    if (inf.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun write(e: Enc, buf: ByteBuffer, inf: MediaCodec.BufferInfo) {
        val pts = inf.presentationTimeUs
        if (pts <= e.lastUs) return
        val key = inf.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        // New pieces start on a key frame
        if (key && (e.muxer == null || e.cut || pts - e.pieceUs >= PIECE_US)) newPiece(e, pts)
        val m = e.muxer ?: return
        try {
            inf.presentationTimeUs = pts - e.pieceUs
            m.writeSampleData(e.track, buf, inf)
            e.lastUs = pts
        } catch (x: Exception) {
            Log.w(TAG, "write", x)
            closePiece(e)
        }
    }

    private fun newPiece(e: Enc, pts: Long) {
        closePiece(e)
        val fmt = e.format ?: return
        var l = e.live
        if (l == null || e.cut) {
            l?.let { finishLater(it) }
            l = newLive(e, wallOf(pts * 1000), pts)
            e.live = l
            live = l
            e.cut = false
        }
        try {
            val f = File(l.dir, "%04d_%d.mp4.part".format(Locale.US, l.seq++, pts - l.baseUs))
            val m = MediaMuxer(f.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            e.track = m.addTrack(fmt)
            m.start()
            e.muxer = m; e.piece = f; e.pieceUs = pts
        } catch (x: Exception) { Log.w(TAG, "piece", x) }
        io.execute { retention() }
    }

    /** Closes the piece being written: it becomes readable (name without .part). */
    private fun closePiece(e: Enc) {
        val m = e.muxer ?: return
        val f = e.piece
        e.muxer = null; e.piece = null
        var ok = false
        try { m.stop(); ok = true } catch (x: Exception) { Log.w(TAG, "close piece", x) }
        try { m.release() } catch (_: Exception) {}
        if (f != null) { if (ok) f.renameTo(File(f.path.removeSuffix(".part"))) else f.delete() }
        changed(false)
    }

    private fun newLive(e: Enc, wall: Long, pts: Long): Live {
        var t = wall / 1000 * 1000
        // Two recordings never get the same name (e.g. a restart within the same second)
        while (File(liveRoot, idOf(t)).exists() || synchronized(index) { index.containsKey(idOf(t)) }) t += 1000
        val id = idOf(t)
        val d = File(liveRoot, id).apply { mkdirs() }
        File(d, "seg.json").writeText(JSONObject().put("id", id).put("start", t).put("w", e.w).put("h", e.h)
            .put("codec", codecName(e.mime)).put("fps", e.s.fps).toString())
        return Live(id, t, d, pts, slotOf(t))
    }

    private fun codecName(mime: String) = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) "hevc" else "h264"

    private fun closeEnc() {
        val e = enc ?: return
        enc = null
        try { e.codec.signalEndOfInputStream(); drain(e, true) } catch (x: Exception) { Log.w(TAG, "end", x) }
        closePiece(e)
        e.live?.let { finishLater(it) }
        live = null
        try { e.drawer.release() } catch (_: Exception) {}
        try { e.frames.release() } catch (_: Exception) {}
        try { e.codec.stop() } catch (_: Exception) {}
        try { e.codec.release() } catch (_: Exception) {}
        try { e.egl.release() } catch (_: Exception) {}
        try { e.surface.release() } catch (_: Exception) {}
        Log.i(TAG, "recording closed")
        changed(false)
    }

    private fun finishLater(l: Live) {
        try { io.execute { finish(l.dir) } } catch (_: Exception) {} // shutting down: joined at the next start
    }

    // ---------------- Files (io thread) ----------------

    private fun dayDir(id: String) = File(root, dayOf(id))
    private fun fileOf(id: String, ext: String) = fileOf(root, id, ext)

    private fun loadIndex() {
        val m = TreeMap<String, Seg>()
        root.listFiles { f -> f.isDirectory && f.name.matches(DAY_DIR) }?.forEach { d ->
            d.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
                try {
                    val s = Seg.from(JSONObject(f.readText()))
                    if (fileOf(s.id, ".mp4").exists()) m[s.id] = s else f.delete()
                } catch (_: Exception) {}
            }
            // Leftovers of a join cut short
            d.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        }
        synchronized(index) { index.clear(); index.putAll(m) }
        listVersion++
        changed(true)
    }

    private fun pieces(dir: File): List<Mp4Cat.Part> =
        (dir.listFiles { f -> f.name.endsWith(".mp4") } ?: emptyArray()).sortedBy { it.name }.mapNotNull { f ->
            f.name.removeSuffix(".mp4").split('_').getOrNull(1)?.toLongOrNull()?.let { Mp4Cat.Part(f, it) }
        }

    /** One-minute pieces -> one recording, its thumbnail and details. */
    private fun finish(dir: File) {
        try {
            val meta = try { JSONObject(File(dir, "seg.json").readText()) } catch (_: Exception) { null }
            val id = meta?.optString("id")?.takeIf { it.matches(ID) } ?: dir.name.takeIf { it.matches(ID) }
            val parts = pieces(dir)
            if (id == null || parts.isEmpty()) { dir.deleteRecursively(); return }
            dayDir(id).mkdirs()
            val mp4 = fileOf(id, ".mp4")
            val tmp = File(mp4.path + ".part")
            val r = Mp4Cat.join(parts, tmp)
            if (r == null || !tmp.renameTo(mp4)) { Log.w(TAG, "join $id failed"); tmp.delete(); dir.deleteRecursively(); return }
            val fps = meta?.optInt("fps")?.takeIf { it > 0 } ?: 15
            val dur = (r.lastUs - r.firstUs) / 1e6 + 1.0 / fps
            thumbnail(mp4, fileOf(id, ".jpg"), dur)
            val s = Seg(id, meta?.optLong("start")?.takeIf { it > 0 } ?: dir.lastModified(), Math.round(dur * 10) / 10.0,
                mp4.length(), meta?.optInt("w") ?: 0, meta?.optInt("h") ?: 0, meta?.optString("codec") ?: "", fps, false)
            fileOf(id, ".json").writeText(s.json().toString())
            synchronized(index) { index[id] = s }
            dir.deleteRecursively()
            Log.i(TAG, "recording $id: ${parts.size} pieces, ${"%.0f".format(dur)} s, ${mp4.length() / 1024} KB")
            listVersion++
            changed(true)
        } catch (e: Exception) {
            Log.e(TAG, "finish ${dir.name}", e)
        }
        retention()
    }

    private fun thumbnail(mp4: File, out: File, dur: Double) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(mp4.path)
            val b = r.getFrameAtTime((minOf(2.0, dur / 2) * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return
            val t = Bitmap.createScaledBitmap(b, THUMB_W, maxOf(1, b.height * THUMB_W / maxOf(1, b.width)), true)
            FileOutputStream(out).use { t.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail", e)
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    private fun free(): Pair<Long, Long> = try { StatFs(root.path).let { it.availableBytes to it.totalBytes } } catch (_: Exception) { Long.MAX_VALUE to 0L }

    /** Deletes old recordings (age, storage); pauses when storage is still short with nothing left to delete. */
    private fun retention() {
        val (free, total) = free()
        val min = minFree(total)
        val gone = pickDeletions(synchronized(index) { index.values.toList() }, System.currentTimeMillis(), settings.days, free, min)
        gone.forEach { removeFiles(it) }
        if (gone.isNotEmpty()) {
            Log.i(TAG, "deleted ${gone.size} old recordings")
            listVersion++
            changed(true)
        }
        if (want != null && free().first < min) exec.post {
            if (!settings.on || paused) return@post
            Log.w(TAG, "storage full: recording paused")
            paused = true; event = "storage"
            update()
            changed(false)
        }
    }

    private fun removeFiles(id: String) {
        removeFiles(root, id)
        synchronized(index) { index.remove(id) }
    }

    // ---------------- For viewers (any thread) ----------------

    fun json(): JSONObject {
        val s = settings
        val o = JSONObject().put("on", s.on).put("active", live != null).put("q", s.q).put("fps", s.fps).put("days", s.days)
            .put("h264", s.h264).put("viewOnly", s.viewOnly).put("hevc", hevcSupported).put("ver", listVersion)
        (encMime?.let { codecName(it) } ?: if (!s.h264 && hevcSupported) "hevc" else "h264").let { o.put("codec", it) }
        event?.let { o.put("event", it) }
        live?.let { o.put("since", it.start) }
        val segs = synchronized(index) { index.values.toList() }
        val liveBytes = liveRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        o.put("used", segs.sumOf { it.size } + liveBytes).put("count", segs.size)
        segs.firstOrNull()?.let { o.put("first", it.start) }
        // Bytes per hour, measured on the latest recordings (still scenes take much less than the bitrate)
        val recent = segs.takeLast(8).filter { it.dur >= 60 }
        if (recent.isNotEmpty()) o.put("rate", (recent.sumOf { it.size } / recent.sumOf { it.dur } * 3600).toLong())
        val (free, total) = free()
        if (total > 0) o.put("free", free).put("total", total).put("minFree", minFree(total))
        return o
    }

    /** Days with recordings, newest first: {day, n, size, dur}. */
    fun days(): JSONArray {
        val by = TreeMap<String, MutableList<Seg>>()
        synchronized(index) { index.values.forEach { by.getOrPut(dayOf(it.id)) { ArrayList() } += it } }
        live?.let { by.getOrPut(dayOf(it.id)) { ArrayList() } }
        val a = JSONArray()
        by.descendingMap().forEach { (d, l) -> a.put(JSONObject().put("day", d).put("n", l.size).put("size", l.sumOf { it.size }).put("dur", l.sumOf { it.dur })) }
        return a
    }

    /** Recordings of a day ("yyyy-MM-dd"), in time order; the one being written last, with live = true. */
    fun list(day: String): JSONArray {
        val a = JSONArray()
        synchronized(index) { index.values.filter { dayOf(it.id) == day }.forEach { a.put(it.json()) } }
        live?.takeIf { dayOf(it.id) == day }?.let { l ->
            val p = pieces(l.dir)
            a.put(JSONObject().put("id", l.id).put("start", l.start).put("live", true)
                .put("dur", (p.lastOrNull()?.let { it.offsetUs / 1e6 + 60 } ?: 0.0).coerceAtMost((System.currentTimeMillis() - l.start) / 1000.0))
                .put("size", p.sumOf { it.file.length() }))
        }
        return a
    }

    fun thumb(id: String): File? = id.takeIf { it.matches(ID) }?.let { fileOf(it, ".jpg") }?.takeIf { it.exists() }

    /** A recording's start time, or null. */
    fun startOf(id: String): Long? = synchronized(index) { index[id]?.start } ?: live?.takeIf { it.id == id }?.start

    /**
     * What a recording is made of: its video, or for the one being written the pieces closed so far
     * (the last minute is still being written). Null if there's no such recording.
     */
    fun parts(id: String): List<Mp4Cat.Part>? {
        if (!id.matches(ID)) return null
        if (synchronized(index) { index.containsKey(id) }) return fileOf(id, ".mp4").takeIf { it.exists() }?.let { listOf(Mp4Cat.Part(it, 0)) }
        val d = File(liveRoot, id)
        return if (d.isDirectory) pieces(d) else null
    }

    fun isFinished(id: String) = synchronized(index) { index.containsKey(id) }
    fun video(id: String): File? = if (isFinished(id)) fileOf(id, ".mp4").takeIf { it.exists() } else null

    /** A new temporary file for a cut (deleted after sending; the folder is emptied at each start). */
    fun tempFile(): File = File(tmpRoot, "cut-${System.nanoTime()}.mp4")

    fun delete(id: String) {
        if (!isFinished(id)) return
        io.execute { removeFiles(id); listVersion++; changed(true) }
    }

    /** From the camera phone's own list: deletes now (the list is shown again right after), viewers are told. */
    fun deleteNow(ids: Collection<String>) {
        ids.forEach { removeFiles(it) }
        listVersion++
        changed(true)
    }

    /** The recording being written: id and start time, or null. */
    fun current(): Pair<String, Long>? = live?.let { it.id to it.start }

    /** Kept recordings are never deleted to make room or for their age. */
    fun keep(id: String, on: Boolean) {
        io.execute {
            val s = synchronized(index) { index[id] } ?: return@execute
            val k = s.withKeep(on)
            try { fileOf(id, ".json").writeText(k.json().toString()) } catch (_: Exception) { return@execute }
            synchronized(index) { index[id] = k }
            listVersion++
            changed(true)
        }
    }
}
