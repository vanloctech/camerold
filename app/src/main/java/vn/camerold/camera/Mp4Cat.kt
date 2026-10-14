package vn.camerold.camera

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Joins and cuts MP4 videos without encoding them again: the compressed frames are copied as they are, so it's fast
 * and doesn't heat the phone. Used for recordings ([Recorder]): one-minute pieces become a 30-minute video, and a
 * few minutes are cut out of a recording for a viewer.
 */
object Mp4Cat {
    private const val TAG = "Mp4Cat"

    /** A video and where it starts on the joint timeline. All parts must have the same format (one encoder). */
    class Part(val file: File, val offsetUs: Long)

    /** First and last frame written, on the joint timeline. */
    class Result(val firstUs: Long, val lastUs: Long, val frames: Int)

    /**
     * Copies the video frames of [parts] between [fromUs] and [toUs] into [out]. It starts on the key frame at or before
     * [fromUs] (a video can't start in between). Parts that can't be read (cut short by a crash) are skipped.
     * Null when nothing was written, it failed, or [cancelled] said so; [out] is then deleted.
     */
    fun join(parts: List<Part>, out: File, fromUs: Long = 0, toUs: Long = Long.MAX_VALUE, cancelled: () -> Boolean = { false }): Result? {
        var muxer: MediaMuxer? = null
        var started = false
        var track = -1
        var base = -1L
        var last = -1L
        var frames = 0
        var buf: ByteBuffer? = null
        val info = MediaCodec.BufferInfo()
        var ok = false
        try {
            for ((i, p) in parts.withIndex()) {
                val next = parts.getOrNull(i + 1)?.offsetUs ?: Long.MAX_VALUE
                if (next <= fromUs) continue
                if (p.offsetUs > toUs) break
                val ex = MediaExtractor()
                try {
                    try { ex.setDataSource(p.file.path) } catch (e: Exception) { Log.w(TAG, "skip ${p.file.name}: ${e.message}"); continue }
                    val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                        ?: continue
                    ex.selectTrack(ti)
                    if (base < 0 && fromUs > p.offsetUs) ex.seekTo(fromUs - p.offsetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    if (muxer == null) {
                        val fmt = ex.getTrackFormat(ti)
                        val max = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                        buf = ByteBuffer.allocateDirect(maxOf(max, 2 shl 20))
                        muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        track = muxer.addTrack(fmt)
                        muxer.start()
                        started = true
                    }
                    val b = buf!!
                    while (true) {
                        if (cancelled()) return null
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) break
                        val t = p.offsetUs + ex.sampleTime
                        if (t > toUs) break
                        val key = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                        // Start on a key frame; skip frames that overlap the part before (never expected)
                        if ((base < 0 && !key) || t <= last) { ex.advance(); continue }
                        if (base < 0) base = t
                        info.set(0, n, t - base, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer!!.writeSampleData(track, b, info)
                        last = t
                        frames++
                        ex.advance()
                    }
                } finally {
                    ex.release()
                }
            }
            if (frames == 0) return null
            muxer?.stop() // writes the index at the end of the file: only now is it a playable video
            started = false
            ok = true
            return Result(base, last, frames)
        } catch (e: Exception) {
            Log.w(TAG, "join ${out.name}", e)
            return null
        } finally {
            try { if (started) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            if (!ok) out.delete()
        }
    }
}
