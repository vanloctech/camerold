package vn.camerold

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Writes a video into the phone's Movies/Camerold folder so it shows in the gallery.
 * Android 10+: MediaStore, no permission needed. Older: a plain file, which needs the storage permission.
 */
object Gallery {
    class Writer(val out: OutputStream, private val done: (Boolean) -> Unit) {
        /** ok = false removes the unfinished file. */
        fun finish(ok: Boolean) {
            try { out.close() } catch (_: Exception) {}
            done(ok)
        }
    }

    fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(120)

    /** Null when it can't be written (no permission on Android 9 and older, or storage error). */
    fun openVideo(ctx: Context, name: String, mime: String = "video/mp4"): Writer? = try {
        val file = safeName(name)
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file)
                put(MediaStore.Video.Media.MIME_TYPE, mime)
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Camerold")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            })!!
            Writer(cr.openOutputStream(uri)!!) { ok ->
                if (ok) cr.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                else cr.delete(uri, null, null)
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Camerold")
            dir.mkdirs()
            val f = File(dir, file)
            Writer(FileOutputStream(f)) { ok ->
                if (ok) android.media.MediaScannerConnection.scanFile(ctx, arrayOf(f.path), arrayOf(mime), null) else f.delete()
            }
        }
    } catch (e: Exception) {
        android.util.Log.w("Gallery", "openVideo", e)
        null
    }
}
