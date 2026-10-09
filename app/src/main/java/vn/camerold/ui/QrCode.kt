package vn.camerold.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * "Quick view" QR code: camerold://join?r=ID&k=PASSWORD - readable by both the viewer app and the web page.
 * Drawn in the app's style (same design as web/js/share.js): soft-rounded modules, rounded corner markers in the
 * accent gradient and a camera badge in the middle. Error correction H (30%), or Q (25%) for long links, keeps it
 * readable under the badge.
 */
object QrCode {
    /**
     * With an own broker the link carries it too (b = broker URL with login, p=0 = no public backup),
     * so viewers don't have to type the server details.
     */
    fun joinUri(room: String, password: String, broker: String? = null, publicBackup: Boolean = true): String =
        "camerold://join?r=${Uri.encode(room)}&k=${Uri.encode(password)}" +
            (broker?.let { "&b=${Uri.encode(it)}" + if (publicBackup) "" else "&p=0" } ?: "")

    fun joinUri(c: vn.camerold.data.CamConfig): String {
        val own = if (c.brokerMode == vn.camerold.signaling.Brokers.MODE_OWN) vn.camerold.signaling.Brokers.ownUrl(c) else null
        return joinUri(vn.camerold.signaling.SigCrypto.normalizeRoom(c.room), c.password, own, c.brokerBackup)
    }

    private const val MARGIN = 2           // quiet zone, in modules (the white card adds more)
    private const val BADGE = 0.22f        // badge width as a share of the code
    private const val INK = 0xFF161A23.toInt()
    private const val ACCENT_1 = 0xFF5B95FF.toInt()
    private const val ACCENT_2 = 0xFF2A62E8.toInt()

    fun bitmap(text: String, size: Int): Bitmap {
        // Longer links (own broker inside) get a lighter error correction so the code doesn't get too dense
        val ecc = if (text.length <= 150) ErrorCorrectionLevel.H else ErrorCorrectionLevel.Q
        val m = Encoder.encode(text, ecc, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix
        val n = m.width
        val cell = size.toFloat() / (n + 2 * MARGIN)
        val o = MARGIN * cell
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), ACCENT_1, ACCENT_2, Shader.TileMode.CLAMP)
        }

        // Modules hidden behind the badge (centered, whole modules)
        val badgeMods = ((n * BADGE).toInt() or 1)
        val b0 = (n - badgeMods) / 2
        fun inBadge(x: Int, y: Int) = x in b0 until b0 + badgeMods && y in b0 until b0 + badgeMods
        fun inFinder(x: Int, y: Int) = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)

        // Data: soft-rounded modules with thin gaps (rounder dots with wider gaps decoded noticeably worse)
        val dot = cell * 0.95f
        val pad = (cell - dot) / 2
        val r = dot * 0.25f
        val rect = RectF()
        for (y in 0 until n) for (x in 0 until n) {
            if (m.get(x, y).toInt() != 1 || inFinder(x, y) || inBadge(x, y)) continue
            rect.set(o + x * cell + pad, o + y * cell + pad, o + (x + 1) * cell - pad, o + (y + 1) * cell - pad)
            c.drawRoundRect(rect, r, r, ink)
        }

        // Corner markers: rounded 7x7 ring + rounded 3x3 center
        for ((fx, fy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
            val x0 = o + fx * cell; val y0 = o + fy * cell
            val ring = Path().apply {
                addRoundRect(RectF(x0, y0, x0 + 7 * cell, y0 + 7 * cell), 2.2f * cell, 2.2f * cell, Path.Direction.CW)
                addRoundRect(RectF(x0 + cell, y0 + cell, x0 + 6 * cell, y0 + 6 * cell), 1.4f * cell, 1.4f * cell, Path.Direction.CCW)
            }
            c.drawPath(ring, accent)
            c.drawRoundRect(RectF(x0 + 2 * cell, y0 + 2 * cell, x0 + 5 * cell, y0 + 5 * cell), cell, cell, accent)
        }

        // Center badge: white gap, accent rounded square, camera glyph
        val bx = o + b0 * cell; val bw = badgeMods * cell
        val inset = cell * 0.5f
        c.drawRoundRect(RectF(bx, bx, bx + bw, bx + bw), bw * 0.3f, bw * 0.3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val tile = RectF(bx + inset, bx + inset, bx + bw - inset, bx + bw - inset)
        c.drawRoundRect(tile, tile.width() * 0.28f, tile.width() * 0.28f, accent)
        drawCamera(c, tile)
        return bmp
    }

    /** Same camera outline as the app icon (24x24 viewBox), white stroke, scaled into [t]. */
    private fun drawCamera(c: Canvas, t: RectF) {
        val s = t.width() * 0.62f / 24f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * s
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        c.save()
        c.translate(t.centerX() - 12 * s, t.centerY() - 12 * s)
        c.scale(s, s)
        p.strokeWidth = 2f
        val body = Path().apply {
            moveTo(3f, 8.5f); arcTo(RectF(3f, 6f, 8f, 11f), 180f, 90f, false); lineTo(7.3f, 6f); lineTo(8.9f, 4f)
            lineTo(15.1f, 4f); lineTo(16.7f, 6f); lineTo(18.5f, 6f); arcTo(RectF(16f, 6f, 21f, 11f), 270f, 90f, false)
            lineTo(21f, 17.5f); arcTo(RectF(16f, 15f, 21f, 20f), 0f, 90f, false); lineTo(5.5f, 20f)
            arcTo(RectF(3f, 15f, 8f, 20f), 90f, 90f, false); close()
        }
        c.drawPath(body, p)
        c.drawCircle(12f, 13f, 3.8f, p)
        c.restore()
    }
}
