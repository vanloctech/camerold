package vn.camerold.camera

import java.util.concurrent.ExecutorService
import org.json.JSONObject
import org.webrtc.DataChannel

/**
 * One viewer's "tl" data channel: list, download and delete timelapse videos (docs/PROTOCOL.md).
 * [canDelete]: false for viewers on the view-only link (they may list and download, not delete).
 */
class TlChannel(dc: DataChannel, private val tl: Timelapse, pool: ExecutorService,
                private val canDelete: Boolean = true) : FileChannel(dc, pool) {

    override fun onRequest(m: JSONObject) {
        when (m.optString("op")) {
            "list" -> later { sendList() }
            "get" -> { val id = m.optString("id"); startJob { sendVideo(id) } }
            "del" -> if (canDelete) later { tl.delete(m.optString("id")) }
        }
    }

    /** Lets viewers refresh their list (a video was finished or deleted). */
    fun notifyChanged() = later { sendList() }

    private fun sendList() {
        val items = tl.list()
        // Thumbnails go one by one: a whole list in one message could pass the data channel's message size limit
        val thumbs = ArrayList<Pair<String, String>>()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            o.optString("thumb").takeIf { it.isNotEmpty() }?.let { thumbs += o.getString("id") to it }
            o.remove("thumb")
        }
        if (!sendJson(JSONObject().put("op", "list").put("items", items))) return
        thumbs.forEach { (id, data) -> sendJson(JSONObject().put("op", "thumb").put("id", id).put("data", data)) }
    }

    private fun sendVideo(id: String) {
        val f = tl.video(id) ?: run { sendErr(id); return }
        val name = tl.list().let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("id") == id } }
            ?.optString("file") ?: "$id.mp4"
        sendFile(id, f, name)
    }
}
