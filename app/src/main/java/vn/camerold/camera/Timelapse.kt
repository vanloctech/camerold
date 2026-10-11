package vn.camerold.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import vn.camerold.Gallery
import vn.camerold.signaling.post

/**
 * Timelapse on the camera phone, started and stopped from a viewer.
 *
 * While it runs, one frame of the camera feed is saved every [Session.interval] seconds as a JPEG in
 * files/timelapse/<id>/. When it stops (viewer, auto-stop time, storage running out, or streaming stopped), the photos
 * become an MP4 at [FPS] frames per second and are deleted. Photos already on disk survive a crash: an unfinished
 * timelapse is finished the next time the camera starts ([recover]).
 *
 * Light on heat: nothing is encoded while it runs (one JPEG now and then), only a short burst at the end.
 * Viewers list, download and delete the videos over a WebRTC data channel (CameraEngine), see docs/PROTOCOL.md.
 */
class Timelapse(
    private val ctx: Context,
    private val exec: ScheduledExecutorService,
    /** Something viewers show changed (state, photo count, list of videos). Called on [exec]. */
    private val changed: (listChanged: Boolean) -> Unit,
) : VideoSink {

    companion object {
        private const val TAG = "Timelapse"
        const val FPS = 30
        val INTERVALS = listOf(1, 2, 5, 10, 30, 60, 300)
        /** Stops (and keeps what it has) when free storage falls below this. */
        const val MIN_FREE = 300L * 1024 * 1024
        private const val JPEG_QUALITY = 88

        /** Frame size for a quality (720 / 1080 = short side), never upscaled, even numbers for the encoder. */
        fun fit(w: Int, h: Int, q: Int): Pair<Int, Int> {
            val s = minOf(1.0, q.toDouble() / minOf(w, h))
            return ((w * s).toInt() and 1.inv()) to ((h * s).toInt() and 1.inv())
        }

        /** Video length for a number of photos, in seconds. */
        fun videoSeconds(frames: Int) = frames.toDouble() / FPS
    }

    /** A running timelapse. Saved as session.json next to its photos so a crash doesn't lose it. */
    class Session(val id: String, val interval: Int, val q: Int, val stopAt: Long, val stamp: Boolean, val rot: Int,
                  val gallery: Boolean, val startedAt: Long, val name: String) {
        fun json(): JSONObject = JSONObject().put("id", id).put("interval", interval).put("q", q).put("stopAt", stopAt)
            .put("stamp", stamp).put("rot", rot).put("gallery", gallery).put("startedAt", startedAt).put("name", name)

        companion object {
            fun from(o: JSONObject) = Session(o.getString("id"), o.optInt("interval", 10), o.optInt("q", 1080),
                o.optLong("stopAt"), o.optBoolean("stamp"), o.optInt("rot"), o.optBoolean("gallery"),
                o.optLong("startedAt"), o.optString("name"))
        }
    }

    val dir = File(ctx.filesDir, "timelapse").apply { mkdirs() }
    private val io = Executors.newSingleThreadExecutor { Thread(it, "timelapse") }

    @Volatile var session: Session? = null
        private set
    @Volatile var frames = 0
        private set
    /** Videos being made from photos (on [io]). */
    @Volatile var finishing = 0
        private set
    /** Why the last timelapse stopped by itself ("storage") or failed ("encode"), for viewers. */
    @Volatile var lastEvent: String? = null
        private set
    /** Bumped whenever the list of videos changes, so viewers know to fetch it again. */
    @Volatile var listVersion = 0
        private set

    private var nextAt = 0L
    private val grabbing = AtomicBoolean(false)
    /** Next photo number (io thread). */
    private var seq = 0

    val active get() = session != null

    // ---------------- Control (on exec) ----------------

    fun start(interval: Int, q: Int, minutes: Int, stamp: Boolean, rot: Int, gallery: Boolean, name: String) {
        if (session != null) return
        val now = System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val s = Session(id, interval.coerceIn(1, 3600), if (q == 720) 720 else 1080,
            if (minutes > 0) now + minutes * 60_000L else 0L, stamp, ((rot % 360) + 360) % 360, gallery, now, name)
        val d = File(dir, s.id).apply { mkdirs() }
        File(d, "session.json").writeText(s.json().toString())
        frames = 0
        io.execute { seq = 0 }
        nextAt = 0L
        lastEvent = null
        session = s
        Log.i(TAG, "start ${s.json()}")
        changed(false)
    }

    /** Stops and turns the photos into a video in the background. */
    fun stop(reason: String? = null) {
        val s = session ?: return
        session = null
        if (reason != null) lastEvent = reason
        Log.i(TAG, "stop (${reason ?: "asked"}) after $frames photos")
        finishLater(s)
        changed(false)
    }

    /** Auto-stop time reached? Called every few seconds by the engine. */
    fun tick() {
        val s = session ?: return
        if (s.stopAt in 1..System.currentTimeMillis()) stop()
    }

    /** Finishes timelapses left unfinished by a crash or a forced stop. */
    fun recover() {
        dir.listFiles()?.filter { it.isDirectory && it.name != session?.id }?.forEach { d ->
            val s = try { Session.from(JSONObject(File(d, "session.json").readText())) } catch (_: Exception) { null }
            if (s == null) { d.deleteRecursively(); return@forEach }
            Log.i(TAG, "recovering ${s.id}")
            finishLater(s)
        }
    }

    fun json(): JSONObject {
        val s = session
        val o = JSONObject().put("on", s != null).put("frames", frames).put("busy", finishing > 0).put("ver", listVersion)
        // Free and total storage, for the viewer's storage bar and estimates
        try { StatFs(dir.path).let { o.put("free", it.availableBytes).put("total", it.totalBytes) } } catch (_: Exception) {}
        lastEvent?.let { o.put("event", it) }
        if (s != null) o.put("interval", s.interval).put("q", s.q).put("stopAt", s.stopAt).put("stamp", s.stamp)
            .put("startedAt", s.startedAt).put("gallery", s.gallery)
        return o
    }

    // ---------------- Capture (camera thread) ----------------

    override fun onFrame(frame: VideoFrame) {
        val s = session ?: return
        val now = SystemClock.elapsedRealtime()
        if (now < nextAt || !grabbing.compareAndSet(false, true)) return
        nextAt = now + s.interval * 1000L
        val wall = System.currentTimeMillis()
        val i420 = try {
            val b = frame.buffer
            val (w, h) = fit(b.width, b.height, s.q)
            val scaled = b.cropAndScale(0, 0, b.width, b.height, w, h)
            try { scaled.toI420() } finally { scaled.release() }
        } catch (e: Exception) { Log.w(TAG, "frame", e); null }
        if (i420 == null) { grabbing.set(false); return }
        val rot = (frame.rotation + s.rot) % 360
        io.execute {
            try {
                val d = File(dir, s.id)
                if (!d.isDirectory) return@execute // taken just as it stopped: the video is already made
                // Photo number, capture time and turn are kept in the name (used when making the video)
                val n = File(d, "%06d_%d_%d.jpg".format(Locale.US, seq++, wall, rot))
                val tmp = File(d, n.name + ".tmp")
                FileOutputStream(tmp).use { writeJpeg(i420, it) }
                tmp.renameTo(n)
                exec.post { onSaved(s) }
            } catch (e: Exception) {
                Log.w(TAG, "save photo", e)
            } finally {
                i420.release()
                grabbing.set(false)
            }
        }
    }

    private fun onSaved(s: Session) {
        if (session !== s) return
        frames++
        val free = try { StatFs(dir.path).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
        if (free < MIN_FREE) stop("storage") else changed(false)
    }

    /** I420 -> NV21 -> JPEG (YuvImage only takes NV21). */
    private fun writeJpeg(i: VideoFrame.I420Buffer, out: FileOutputStream) {
        val w = i.width; val h = i.height
        val nv = ByteArray(w * h * 3 / 2)
        val y = i.dataY.duplicate()
        for (r in 0 until h) { y.position(r * i.strideY); y.get(nv, r * w, w) }
        val cw = w / 2; val ch = h / 2
        val u = i.dataU.duplicate(); val v = i.dataV.duplicate()
        val ru = ByteArray(cw); val rv = ByteArray(cw)
        var o = w * h
        for (r in 0 until ch) {
            u.position(r * i.strideU); u.get(ru)
            v.position(r * i.strideV); v.get(rv)
            for (c in 0 until cw) { nv[o++] = rv[c]; nv[o++] = ru[c] }
        }
        YuvImage(nv, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), JPEG_QUALITY, out)
    }

    // ---------------- Photos -> video (io thread) ----------------

    private fun finishLater(s: Session) {
        finishing++
        io.execute {
            var ok = false
            try { ok = finish(s) } catch (e: Exception) { Log.e(TAG, "finish ${s.id}", e) }
            exec.post {
                finishing--
                if (!ok) lastEvent = "encode"
                listVersion++
                changed(true)
            }
        }
    }

    private fun photos(d: File) = (d.listFiles { f -> f.name.endsWith(".jpg") } ?: emptyArray()).sortedBy { it.name }

    /** Makes <id>.mp4, <id>.jpg (thumbnail) and <id>.json, then deletes the photos. */
    private fun finish(s: Session): Boolean {
        val d = File(dir, s.id)
        val list = photos(d)
        if (list.size < 2) { d.deleteRecursively(); return list.isEmpty() } // nothing worth a video
        val mp4 = File(dir, s.id + ".mp4")
        val (w, h) = outputSize(list.first())
        val made = encode(list, mp4, s, w, h) || (minOf(w, h) > 720 && encode(list, mp4, s, w * 720 / minOf(w, h) and 1.inv(), h * 720 / minOf(w, h) and 1.inv()))
        if (!made) { mp4.delete(); return false }
        thumbnail(list[list.size / 2], File(dir, s.id + ".jpg"))
        val (vw, vh) = videoSize(mp4) ?: (w to h)
        File(dir, s.id + ".json").writeText(JSONObject().put("id", s.id).put("name", s.name).put("created", s.startedAt)
            .put("frames", list.size).put("interval", s.interval).put("dur", videoSeconds(list.size))
            .put("w", vw).put("h", vh).put("size", mp4.length()).put("stamp", s.stamp).toString())
        if (s.gallery) copyToGallery(mp4, fileName(s))
        d.deleteRecursively()
        Log.i(TAG, "made ${mp4.name}: ${list.size} photos, ${mp4.length() / 1024} KB")
        return true
    }

    fun fileName(id: String, name: String, created: Long): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date(created))
        return Gallery.safeName("Camerold timelapse ${name.ifEmpty { android.os.Build.MODEL }} $stamp.mp4")
    }
    private fun fileName(s: Session) = fileName(s.id, s.name, s.startedAt)

    private fun rotOf(f: File) = f.name.substringBeforeLast('.').split('_').getOrNull(2)?.toIntOrNull() ?: 0
    private fun timeOf(f: File) = f.name.split('_').getOrNull(1)?.toLongOrNull() ?: f.lastModified()

    /** Size of the first photo as shown (turned upright). */
    private fun outputSize(first: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(first.path, o)
        val swap = rotOf(first) % 180 != 0
        val w = if (swap) o.outHeight else o.outWidth
        val h = if (swap) o.outWidth else o.outHeight
        return (w and 1.inv()) to (h and 1.inv())
    }

    private fun encoderSupports(w: Int, h: Int) = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
            ci.isEncoder && ci.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                ci.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities.isSizeSupported(w, h)
        }
    } catch (_: Exception) { true }

    /** H.264 from the photos, drawn onto the encoder's input surface (turned, fitted, optional date and time). */
    private fun encode(list: List<File>, out: File, s: Session, w: Int, h: Int): Boolean {
        if (w < 16 || h < 16 || !encoderSupports(w, h)) return false
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var surface: android.view.Surface? = null
        var muxing = false
        try {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                // Every photo differs a lot from the last one: give it more bits than live video
                setInteger(MediaFormat.KEY_BIT_RATE, (w.toLong() * h * FPS / 6).coerceIn(2_000_000, 20_000_000).toInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (android.os.Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var written = 0L
            fun drain(end: Boolean) {
                var idle = 0
                while (true) {
                    val i = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        // At the end, wait for the last frames (up to 30 s: slow software encoders on old phones)
                        i == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!end || ++idle > 3000) return }
                        i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true }
                        i >= 0 -> {
                            val buf = codec.getOutputBuffer(i)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && muxing) {
                                // Photos are evenly spaced in the video, whatever time they were drawn at
                                info.presentationTimeUs = written * 1_000_000L / FPS
                                written++
                                muxer.writeSampleData(track, buf, info)
                            }
                            codec.releaseOutputBuffer(i, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                            idle = 0
                        }
                    }
                }
            }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = h / 26f; setShadowLayer(h / 300f + 1, 0f, 0f, Color.BLACK)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
            val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val m = Matrix()
            for (f in list) {
                val bmp = BitmapFactory.decodeFile(f.path) ?: continue
                val c: Canvas = try { surface.lockHardwareCanvas() } catch (_: Exception) { surface.lockCanvas(null) }
                c.drawColor(Color.BLACK)
                val rot = rotOf(f)
                val bw = if (rot % 180 != 0) bmp.height else bmp.width
                val bh = if (rot % 180 != 0) bmp.width else bmp.height
                val k = minOf(w.toFloat() / bw, h.toFloat() / bh)
                m.reset()
                m.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                m.postRotate(rot.toFloat())
                m.postScale(k, k)
                m.postTranslate(w / 2f, h / 2f)
                c.drawBitmap(bmp, m, paint)
                if (s.stamp) c.drawText(clock.format(Date(timeOf(f))), h / 40f, h - h / 30f, text)
                surface.unlockCanvasAndPost(c)
                bmp.recycle()
                drain(false)
            }
            codec.signalEndOfInputStream()
            drain(true)
            if (written < list.size) Log.w(TAG, "encoded $written of ${list.size} photos")
            return written > 0
        } catch (e: Exception) {
            Log.e(TAG, "encode ${w}x$h", e)
            return false
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { surface?.release() } catch (_: Exception) {}
            try { if (muxing) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun videoSize(f: File): Pair<Int, Int>? = try {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(f.path)
            val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            w to h
        } finally { r.release() }
    } catch (_: Exception) { null }

    private fun thumbnail(src: File, out: File) {
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.path, o)
            o.inJustDecodeBounds = false
            o.inSampleSize = maxOf(1, maxOf(o.outWidth, o.outHeight) / 480)
            val b = BitmapFactory.decodeFile(src.path, o) ?: return
            val rot = rotOf(src)
            val t = if (rot == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            FileOutputStream(out).use { t.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        } catch (e: Exception) { Log.w(TAG, "thumbnail", e) }
    }

    private fun copyToGallery(mp4: File, name: String) {
        val w = Gallery.openVideo(ctx, name) ?: return
        var ok = false
        try { mp4.inputStream().use { it.copyTo(w.out) }; ok = true } catch (e: Exception) { Log.w(TAG, "gallery", e) }
        w.finish(ok)
    }

    // ---------------- Videos for viewers ----------------

    /** Finished videos, newest first, with a small thumbnail (base64 JPEG). */
    fun list(): JSONArray {
        val a = JSONArray()
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).sortedByDescending { it.name }.forEach { f ->
            try {
                val o = JSONObject(f.readText())
                val mp4 = File(dir, o.getString("id") + ".mp4")
                if (!mp4.exists()) return@forEach
                o.put("size", mp4.length()).put("file", fileName(o.getString("id"), o.optString("name"), o.optLong("created")))
                File(dir, o.getString("id") + ".jpg").takeIf { it.exists() }?.let {
                    o.put("thumb", android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP))
                }
                a.put(o)
            } catch (_: Exception) {}
        }
        return a
    }

    /** The video file for an id from [list], or null (ids are checked: no paths from outside). */
    fun video(id: String): File? = id.takeIf { it.matches(Regex("\\d{8}-\\d{6}")) }
        ?.let { File(dir, "$it.mp4") }?.takeIf { it.exists() }

    fun delete(id: String) {
        if (!id.matches(Regex("\\d{8}-\\d{6}"))) return
        listOf(".mp4", ".jpg", ".json").forEach { File(dir, id + it).delete() }
        exec.post { listVersion++; changed(true) }
    }

    fun shutdown() {
        // Let a video being made finish; then the thread ends
        io.shutdown()
        try { io.awaitTermination(0, TimeUnit.SECONDS) } catch (_: Exception) {}
    }
}
