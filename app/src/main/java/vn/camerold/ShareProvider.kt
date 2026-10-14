package vn.camerold

import android.app.Activity
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands one of our own files (cache/share/) to the app the user shares with, read-only and only for that share.
 * The few lines of androidx's FileProvider this app needs, without the dependency.
 */
class ShareProvider : ContentProvider() {
    companion object {
        private fun dir(ctx: Context) = File(ctx.cacheDir, "share").apply { mkdirs() }

        /** Writes [bytes] as [name] and opens the share sheet. */
        fun share(a: Activity, name: String, bytes: ByteArray, mime: String, title: CharSequence) {
            val f = File(dir(a), Gallery.safeName(name))
            f.writeBytes(bytes)
            val uri = Uri.parse("content://${a.packageName}.share/${Uri.encode(f.name)}")
            val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(f.name, uri) // so the grant reaches the chosen app
            a.startActivity(Intent.createChooser(send, title))
        }
    }

    override fun onCreate() = true

    private fun file(uri: Uri): File? {
        val n = uri.lastPathSegment ?: return null
        if (n.contains('/') || n.startsWith(".")) return null
        return File(dir(context!!), n).takeIf { it.isFile }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        return ParcelFileDescriptor.open(file(uri) ?: throw FileNotFoundException(uri.toString()), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = when (uri.lastPathSegment?.substringAfterLast('.')?.lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "mp4" -> "video/mp4"; else -> null
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val f = file(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
