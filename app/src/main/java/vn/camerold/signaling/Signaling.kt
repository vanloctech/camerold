package vn.camerold.signaling

import java.util.concurrent.ScheduledExecutorService
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import org.json.JSONObject

/**
 * Connects to several public MQTT brokers in parallel (if one dies, others remain),
 * publishes to all, receives from any broker, and dedupes by "id".
 *
 * Replay protection: each message carries "ts" (send time) and a random "id", inside the
 * encrypted part. Messages skewed beyond [MAX_SKEW_MS] or with an already-seen id are dropped.
 */
class Signaling(
    exec: ScheduledExecutorService,
    brokers: List<String>,
    private val key: SecretKeySpec,
    private val subscribeTopic: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onBrokers: (up: Int, total: Int) -> Unit,
) : MqttWs.Callback {

    companion object {
        const val MAX_SKEW_MS = 15 * 60 * 1000L
        val BROKERS = listOf(
            "wss://broker.emqx.io:8084/mqtt",
            "wss://broker.hivemq.com:8884/mqtt",
            "wss://test.mosquitto.org:8081/mqtt",
            "wss://public:public@public.cloud.shiftr.io", // shiftr.io's public broker asks for this shared login
        )
    }

    private val clients = brokers.map { MqttWs(it, exec, this) }
    private val seen = LinkedHashMap<String, Long>() // id -> receive time

    fun start() = clients.forEach { it.connect() }
    fun stop() = clients.forEach { it.stop() }

    /** Must be called on exec. */
    fun send(topic: String, msg: JSONObject) {
        val id = SigCrypto.randomId(12)
        msg.put("id", id).put("ts", System.currentTimeMillis())
        remember(id) // don't process our own messages
        val data = SigCrypto.encrypt(key, msg.toString(), topic)
        clients.forEach { it.publish(topic, data) }
    }

    private fun remember(id: String): Boolean {
        if (id.length < 8 || seen.containsKey(id)) return false
        val now = System.currentTimeMillis()
        seen[id] = now
        // Keep ids longer than the time window so replays within the window are always detected
        val it = seen.entries.iterator()
        while (it.hasNext()) { if (now - it.next().value > 2 * MAX_SKEW_MS) it.remove() else break }
        return true
    }

    private fun report() = onBrokers(clients.count { it.connected }, clients.size)

    override fun onUp(c: MqttWs) {
        c.subscribe(subscribeTopic)
        report()
    }

    override fun onDown(c: MqttWs) = report()

    override fun onPublish(c: MqttWs, topic: String, payload: ByteArray) {
        if (topic != subscribeTopic) return
        val text = SigCrypto.decrypt(key, payload, topic) ?: return
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        if (abs(System.currentTimeMillis() - msg.optLong("ts")) > MAX_SKEW_MS) return
        if (!remember(msg.optString("id"))) return
        onMessage(msg)
    }
}
