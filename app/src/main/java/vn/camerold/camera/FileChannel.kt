package vn.camerold.camera

import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import org.json.JSONObject
import org.webrtc.DataChannel

/**
 * A data channel of one viewer's WebRTC connection that answers JSON requests and sends files in 16 KB pieces
 * ([TlChannel] for timelapse videos, [RecChannel] for recordings; docs/PROTOCOL.md). It's part of the viewer's
 * connection, so only viewers with a password reach it, end-to-end encrypted.
 * Requests run on [pool]; [close] may come from any thread, so every use of the channel is locked.
 */
abstract class FileChannel(private val dc: DataChannel, protected val pool: ExecutorService) : DataChannel.Observer {
    companion object {
        private const val TAG = "FileChannel"
        private const val CHUNK = 16 * 1024
        /** Pause sending while this much is queued (keeps memory low, lets live video through). */
        private const val MAX_BUFFERED = 2L * 1024 * 1024
    }

    private val lock = Any()
    @Volatile protected var closed = false
        private set
    @Volatile private var job: Future<*>? = null
    @Volatile protected var cancelled = false
        private set

    init { dc.registerObserver(this) }

    override fun onBufferedAmountChange(previous: Long) {}
    override fun onStateChange() {}

    override fun onMessage(b: DataChannel.Buffer) {
        if (b.binary) return
        val bytes = ByteArray(b.data.remaining()).also { b.data.get(it) }
        val m = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { return }
        try {
            if (m.optString("op") == "cancel") stopJob() else onRequest(m)
        } catch (e: Exception) { Log.w(TAG, "message", e) } // pool shut down: camera stopping
    }

    /** A request from the viewer (on the WebRTC thread: hand work to [pool] or [startJob]). */
    protected abstract fun onRequest(m: JSONObject)

    /** One transfer at a time: a new one cancels the last. */
    protected fun startJob(block: () -> Unit) {
        stopJob()
        cancelled = false
        job = pool.submit(block)
    }

    private fun stopJob() { cancelled = true; job = null }

    protected fun later(block: () -> Unit) {
        try { pool.execute(block) } catch (_: Exception) {} // camera stopping
    }

    /** {op: "file", id, size, name}, the file in pieces, then {op: "end", id} (cancelled: true) or {op: "err", id}. */
    protected fun sendFile(id: String, f: File, name: String) {
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
            sendErr(id)
        }
    }

    protected fun sendErr(id: String) = sendJson(JSONObject().put("op", "err").put("id", id))

    protected fun sendJson(o: JSONObject) = send(ByteBuffer.wrap(o.toString().toByteArray(Charsets.UTF_8)), false)

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
