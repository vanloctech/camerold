package vn.camerold.camera

import android.util.Base64
import java.util.concurrent.ExecutorService
import org.json.JSONObject
import org.webrtc.DataChannel

/**
 * One viewer's "rec" data channel: browse recordings by day, download one or a cut of it, keep and delete
 * (docs/PROTOCOL.md). [owner]: full-control link (may keep and delete). [allowed]: false while view-only viewers
 * may not see recordings (the owner's choice); every request is then answered with {op: "denied"}.
 */
class RecChannel(dc: DataChannel, private val rec: Recorder, pool: ExecutorService, private val owner: Boolean,
                 private val allowed: () -> Boolean, private val camName: () -> String) : FileChannel(dc, pool) {
    companion object {
        /** Longest cut, in seconds. */
        private const val MAX_CUT_S = 3600.0
        private val DAY = Regex("\\d{4}-\\d{2}-\\d{2}")
    }

    override fun onRequest(m: JSONObject) {
        if (!allowed()) { later { sendJson(JSONObject().put("op", "denied")) }; return }
        val id = m.optString("id")
        when (m.optString("op")) {
            "days" -> later { sendJson(JSONObject().put("op", "days").put("items", rec.days())) }
            "list" -> m.optString("day").takeIf { it.matches(DAY) }?.let { day ->
                // Thumbnails the viewer already has aren't sent again
                val have = m.optJSONArray("have")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
                later { sendList(day, have) }
            }
            "get" -> startJob { if (rec.isFinished(id)) sendWhole(id) else sendCut(id, 0.0, Double.MAX_VALUE) }
            "clip" -> {
                val from = m.optDouble("from", 0.0).coerceAtLeast(0.0)
                val to = m.optDouble("to", 0.0)
                if (to > from) startJob { sendCut(id, from, minOf(to, from + MAX_CUT_S)) } else later { sendErr(id) }
            }
            "del" -> if (owner) rec.delete(id)
            "keep" -> if (owner) rec.keep(id, m.optBoolean("on"))
        }
    }

    /** The list of recordings changed (one finished, deleted, kept): viewers fetch what they show again. */
    fun notifyChanged(ver: Int) = later { if (allowed()) sendJson(JSONObject().put("op", "changed").put("ver", ver)) }

    private fun sendList(day: String, have: Set<String>) {
        val items = rec.list(day)
        if (!sendJson(JSONObject().put("op", "list").put("day", day).put("items", items))) return
        // Thumbnails one by one (message size limit), after the list
        for (i in 0 until items.length()) {
            val id = items.getJSONObject(i).getString("id")
            if (id in have) continue
            val f = rec.thumb(id) ?: continue
            val data = try { Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) } catch (_: Exception) { continue }
            if (!sendJson(JSONObject().put("op", "thumb").put("id", id).put("data", data))) return
        }
    }

    private fun sendWhole(id: String) {
        val f = rec.video(id) ?: run { sendErr(id); return }
        sendFile(id, f, Recorder.fileName(rec.startOf(id) ?: f.lastModified(), camName()))
    }

    /** [from] / [to]: seconds from the start of the recording. Made without encoding again, then sent. */
    private fun sendCut(id: String, from: Double, to: Double) {
        val parts = rec.parts(id)
        val start = rec.startOf(id)
        if (parts.isNullOrEmpty() || start == null) { sendErr(id); return }
        sendJson(JSONObject().put("op", "prep").put("id", id))
        val tmp = rec.tempFile()
        try {
            val toUs = if (to >= Double.MAX_VALUE / 2) Long.MAX_VALUE else (to * 1e6).toLong()
            val r = Mp4Cat.join(parts, tmp, (from * 1e6).toLong(), toUs) { cancelled || closed }
            when {
                r != null -> sendFile(id, tmp, Recorder.fileName(start + r.firstUs / 1000, camName()))
                cancelled -> sendJson(JSONObject().put("op", "end").put("id", id).put("cancelled", true))
                else -> sendErr(id)
            }
        } finally {
            tmp.delete()
        }
    }
}
