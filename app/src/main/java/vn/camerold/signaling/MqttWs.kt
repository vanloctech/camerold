package vn.camerold.signaling

import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Minimal MQTT 3.1.1 client over WebSocket (QoS 0 only: CONNECT / SUBSCRIBE / PUBLISH / PING).
 * Uses free public brokers as the "rendezvous" (signaling) channel for WebRTC.
 * All callbacks run on [exec] (single-threaded), so no locking is needed.
 */
class MqttWs(
    brokerUrl: String,
    private val exec: ScheduledExecutorService,
    private val cb: Callback,
) {
    /** Broker address without credentials; "wss://user:pass@host/path" logs in with user/pass (e.g. shiftr.io). */
    val url: String
    private val user: String?
    private val pass: String?
    init {
        val u = java.net.URI(brokerUrl)
        val info = u.rawUserInfo?.split(':', limit = 2)
        user = info?.getOrNull(0)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        pass = info?.getOrNull(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        url = java.net.URI(u.scheme, null, u.host, u.port, u.path, u.query, null).toString()
    }
    interface Callback {
        fun onUp(c: MqttWs)
        fun onDown(c: MqttWs)
        fun onPublish(c: MqttWs, topic: String, payload: ByteArray)
        /** The broker rejected the connection (CONNACK code, e.g. 4/5 = wrong user name or password). */
        fun onRefused(c: MqttWs, code: Int) {}
        /** A connection attempt failed before the broker answered (it keeps retrying). */
        fun onFailed(c: MqttWs) {}
    }

    companion object {
        private const val TAG = "MqttWs"
        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    var connected = false
        private set

    private val clientId = "cmo_" + SigCrypto.randomId(14)
    private var ws: WebSocket? = null
    private var gen = 0
    private var stopped = false
    private var retry = 0
    private var buf = ByteArray(0)
    private var packetId = 1
    private var lastRx = 0L
    private var pingTask: ScheduledFuture<*>? = null

    fun connect() = exec.post { stopped = false; open() }

    fun stop() = exec.post {
        stopped = true
        gen++
        ws?.let { if (connected) it.send(byteArrayOf(0xE0.toByte(), 0).toByteString()); it.close(1000, null) }
        ws = null
        pingTask?.cancel(false)
        connected = false
    }

    fun subscribe(topic: String) {
        if (!connected) return
        packetId = if (packetId >= 65535) 1 else packetId + 1
        send(packet(0x82, u16(packetId) + str(topic) + byteArrayOf(0)))
    }

    fun publish(topic: String, payload: ByteArray) {
        if (connected) send(packet(0x30, str(topic) + payload))
    }

    private fun open() {
        if (stopped) return
        val myGen = ++gen
        buf = ByteArray(0)
        val req = Request.Builder().url(url).header("Sec-WebSocket-Protocol", "mqtt").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = exec.post {
                if (myGen == gen) {
                    lastRx = System.currentTimeMillis()
                    // keep-alive 60s, clean session (+ username/password flags when the broker needs a login)
                    var flags = 2
                    var payload = str(clientId)
                    if (user != null) { flags = flags or 0x80; payload += str(user) }
                    if (user != null && pass != null) { flags = flags or 0x40; payload += str(pass) }
                    webSocket.send(packet(0x10, str("MQTT") + byteArrayOf(4, flags.toByte(), 0, 60) + payload).toByteString())
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = exec.post {
                if (myGen == gen) feed(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = exec.post {
                if (myGen == gen) lost("closed $code")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = exec.post {
                if (myGen == gen) lost(t.message ?: "failure")
            }
        })
        // No CONNACK after 20s -> retry
        exec.later({ if (myGen == gen && !connected) lost("timeout") }, 20, TimeUnit.SECONDS)
    }

    private fun lost(why: String) {
        Log.w(TAG, "$url lost: $why")
        val was = connected
        connected = false
        pingTask?.cancel(false)
        ws?.cancel()
        ws = null
        gen++
        if (was) cb.onDown(this) else if (!stopped) cb.onFailed(this)
        if (!stopped) {
            val delay = minOf(30L, 1L shl minOf(retry, 5))
            retry++
            exec.later({ open() }, delay, TimeUnit.SECONDS)
        }
    }

    private fun send(b: ByteArray) {
        ws?.send(b.toByteString())
    }

    private fun feed(data: ByteArray) {
        lastRx = System.currentTimeMillis()
        buf += data
        while (true) {
            if (buf.size < 2) return
            var mult = 1
            var len = 0
            var i = 1
            var b: Int
            do {
                if (i >= buf.size) return
                b = buf[i].toInt() and 0xff
                len += (b and 127) * mult
                mult *= 128
                i++
            } while (b and 128 != 0 && i < 5)
            if (buf.size < i + len) return
            val first = buf[0].toInt() and 0xff
            val body = buf.copyOfRange(i, i + len)
            buf = buf.copyOfRange(i + len, buf.size)
            handle(first shr 4, first and 0x0f, body)
        }
    }

    private fun handle(type: Int, flags: Int, body: ByteArray) {
        when (type) {
            2 -> { // CONNACK
                if (body.size >= 2 && body[1].toInt() == 0) {
                    connected = true
                    retry = 0
                    pingTask?.cancel(false)
                    pingTask = exec.every({ ping() }, 20, TimeUnit.SECONDS)
                    cb.onUp(this)
                } else {
                    cb.onRefused(this, if (body.size >= 2) body[1].toInt() and 0xff else -1)
                    lost("connack refused")
                }
            }
            3 -> { // PUBLISH
                val tlen = ((body[0].toInt() and 0xff) shl 8) or (body[1].toInt() and 0xff)
                val topic = String(body, 2, tlen, Charsets.UTF_8)
                var idx = 2 + tlen
                if ((flags shr 1) and 3 > 0) idx += 2
                cb.onPublish(this, topic, body.copyOfRange(idx, body.size))
            }
            // SUBACK (9), PINGRESP (13): nothing to do
        }
    }

    private fun ping() {
        if (!connected) return
        if (System.currentTimeMillis() - lastRx > 65_000) { lost("ping timeout"); return }
        send(byteArrayOf(0xC0.toByte(), 0))
    }

    private fun packet(header: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header)
        var x = body.size
        do {
            var d = x % 128
            x /= 128
            if (x > 0) d = d or 128
            out.write(d)
        } while (x > 0)
        out.write(body)
        return out.toByteArray()
    }

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun str(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return u16(b.size) + b
    }
}
