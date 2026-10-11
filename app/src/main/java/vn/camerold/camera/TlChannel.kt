package vn.camerold.camera

import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import org.json.JSONObject
import org.webrtc.DataChannel

/**
 * One viewer's "tl" data channel: list, download and delete timelapse videos (docs/PROTOCOL.md).
 * It's part of the viewer's WebRTC connection, so only viewers with the password reach it, end-to-end encrypted.
 * Sending runs on [pool]; [close] may come from any thread, so every use of the channel is locked.
 */
class TlChannel(private val dc: DataChannel, private val tl: Timelapse, private val pool: ExecutorService) : DataChannel.Observer {
    companion object {
        private const val TAG = "TlChannel"
        private const val CHUNK = 16 * 1024
        /** Pause sending while this much is queued (keeps memory low, lets live video through). */
        private const val MAX_BUFFERED = 2L * 1024 * 1024
    }

    private val lock = Any()
    @Volatile private var closed = false
    @Volatile private var job: Future<*>? = null
    @Volatile private var cancelled = false

    init { dc.registerObserver(this) }

    override fun onBufferedAmountChange(previous: Long) {}
    override fun onStateChange() {}

    override fun onMessage(b: DataChannel.Buffer) {
        if (b.binary) return
        val bytes = ByteArray(b.data.remaining()).also { b.data.get(it) }
        val m = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { return }
        try {
            when (m.optString("op")) {
                "list" -> pool.execute { sendList() }
                "get" -> { stopJob(); cancelled = false; val id = m.optString("id"); job = pool.submit { sendFile(id) } }
                "cancel" -> stopJob()
                "del" -> pool.execute { tl.delete(m.optString("id")) }
            }
        } catch (e: Exception) { Log.w(TAG, "message", e) } // pool shut down: camera stopping
    }

    /** Lets viewers refresh their list (a video was finished or deleted). */
    fun notifyChanged() {
        try { pool.execute { sendList() } } catch (_: Exception) {} // camera stopping
    }

    private fun stopJob() { cancelled = true; job = null }

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

    private fun sendFile(id: String) {
        val f = tl.video(id)
        if (f == null) { sendJson(JSONObject().put("op", "err").put("id", id)); return }
        val name = tl.list().let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("id") == id } }
            ?.optString("file") ?: "$id.mp4"
        if (!sendJson(JSONObject().put("op", "file").put("id", id).put("size", f.length()).put("name", name))) return
        try {
            f.inputStream().use { input ->
                val buf = ByteArray(CHUNK)
                while (true) {
                    if (cancelled || closed) { sendJson(JSONObject().put("op", "end").put("id", id).put("cancelled", true)); return }
                    while (buffered() > MAX_BUFFERED) { if (closed || cancelled) break; Thread.sleep(15) }
                    val n = input.read(buf)
                    if (n < 0) break
                    if (!send(ByteBuffer.wrap(buf.copyOf(n)), true)) return
                }
            }
            sendJson(JSONObject().put("op", "end").put("id", id))
        } catch (e: InterruptedException) {
            return
        } catch (e: Exception) {
            Log.w(TAG, "send $id", e)
            sendJson(JSONObject().put("op", "err").put("id", id))
        }
    }

    private fun sendJson(o: JSONObject) = send(ByteBuffer.wrap(o.toString().toByteArray(Charsets.UTF_8)), false)

    private fun send(b: ByteBuffer, binary: Boolean): Boolean = synchronized(lock) {
        !closed && dc.state() == DataChannel.State.OPEN && dc.send(DataChannel.Buffer(b, binary))
    }

    private fun buffered(): Long = synchronized(lock) { if (closed) 0 else dc.bufferedAmount() }

    /** Before the peer connection is disposed. */
    fun close() {
        cancelled = true
        synchronized(lock) {
            if (closed) return
            closed = true
            try { dc.unregisterObserver() } catch (_: Exception) {}
            try { dc.close() } catch (_: Exception) {}
            try { dc.dispose() } catch (_: Exception) {}
        }
    }
}
