package vn.camerold.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * "Quick view" QR code: camerold://join?r=ID&k=PASSWORD - readable by both the viewer app and the web page.
 * Drawn in the app's style (same design as web/js/share.js): soft-rounded modules, rounded corner markers in the
 * accent gradient and the app logo in the middle. Error correction H (30%), or Q (25%) for long links, keeps it
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

    /** [viewOnly]: the second link, for people who may watch but not change anything (CamConfig.viewPassword). */
    fun joinUri(c: vn.camerold.data.CamConfig, viewOnly: Boolean = false): String {
        val own = if (c.brokerMode == vn.camerold.signaling.Brokers.MODE_OWN) vn.camerold.signaling.Brokers.ownUrl(c) else null
        return joinUri(vn.camerold.signaling.SigCrypto.normalizeRoom(c.room), if (viewOnly) c.viewPassword else c.password, own, c.brokerBackup)
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

        // Center badge: white gap, then the app logo
        val bx = o + b0 * cell; val bw = badgeMods * cell
        val inset = cell * 0.5f
        c.drawRoundRect(RectF(bx, bx, bx + bw, bx + bw), bw * 0.3f, bw * 0.3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        drawLogo(c, RectF(bx + inset, bx + inset, bx + bw - inset, bx + bw - inset))
        return bmp
    }

    /** The code to save or send: QR with the camera's name and which link it is under it, as PNG. */
    fun sharePng(text: String, name: String, role: String): ByteArray {
        val w = 1080; val pad = 60
        val bmp = Bitmap.createBitmap(w, w + 190, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.drawBitmap(bitmap(text, w - 2 * pad), pad.toFloat(), pad.toFloat(), null)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK; textSize = 52f; textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        c.drawText(ellipsize(name, p, w - 2f * pad), w / 2f, w + 40f, p)
        p.apply { color = 0xFF6A707B.toInt(); textSize = 38f; typeface = android.graphics.Typeface.DEFAULT }
        c.drawText(ellipsize("$role · Camerold", p, w - 2f * pad), w / 2f, w + 110f, p)
        return java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun ellipsize(s: String, p: Paint, max: Float): String {
        if (p.measureText(s) <= max) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > max) t = t.dropLast(1)
        return "$t…"
    }

    /** The app logo (web/icon.svg, ic_launcher_*): a phone whose screen is a camera lens, on the blue tile, scaled into [t]. */
    private fun drawLogo(c: Canvas, t: RectF) {
        c.save()
        c.translate(t.left, t.top)
        c.scale(t.width() / 108f, t.width() / 108f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 108f, 108f, 0xFF5A8FFF.toInt(), 0xFF1E4ED8.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(RectF(0f, 0f, 108f, 108f), 30f, 30f, p)
        c.scale(1.05f, 1.05f, 54f, 54f)
        p.shader = null; p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 5f
        c.drawRoundRect(RectF(36f, 27f, 72f, 81f), 9f, 9f, p)
        p.style = Paint.Style.FILL
        c.drawCircle(54f, 54f, 11f, p)
        p.shader = RadialGradient(52f, 51.5f, 8.5f, intArrayOf(0xFF3D7BFF.toInt(), 0xFF1A3C9E.toInt(), 0xFF0E2361.toInt()),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(54f, 54f, 6.5f, p)
        p.shader = null; p.color = 0xE6FFFFFF.toInt()
        c.drawCircle(51.8f, 51.8f, 1.8f, p)
        c.restore()
    }
}
