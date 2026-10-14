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
 *
 * A camera can [listen] on a second inbox with its own key (the view-only link): each inbox only accepts messages
 * encrypted with its key, so the caller knows which link a message came through.
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

    private class Inbox(val key: SecretKeySpec, val topic: String, val onMessage: (JSONObject) -> Unit)
    private val inboxes = mutableListOf(Inbox(key, subscribeTopic, onMessage))

    private val clients = brokers.map { MqttWs(it, exec, this) }
    private val seen = LinkedHashMap<String, Long>() // id -> receive time
    /** Retained messages by topic, published again to brokers that (re)connect later. */
    private val retained = HashMap<String, ByteArray>()

    /** Another inbox on the same connections, with its own key. Call before [start]. */
    fun listen(key: SecretKeySpec, topic: String, onMessage: (JSONObject) -> Unit) { inboxes += Inbox(key, topic, onMessage) }

    fun start() = clients.forEach { it.connect() }
    fun stop() = clients.forEach { it.stop() }

    /**
     * Must be called on exec. [retain]: brokers keep it for later subscribers (see alerts/BatteryAlerts).
     * [key]: the link it belongs to (the main one unless given).
     */
    fun send(topic: String, msg: JSONObject, retain: Boolean = false, key: SecretKeySpec = this.key) {
        val id = SigCrypto.randomId(12)
        msg.put("id", id).put("ts", System.currentTimeMillis())
        remember(id) // don't process our own messages
        val data = SigCrypto.encrypt(key, msg.toString(), topic)
        if (retain) retained[topic] = data
        clients.forEach { it.publish(topic, data, retain) }
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
        inboxes.forEach { c.subscribe(it.topic) }
        retained.forEach { (topic, data) -> c.publish(topic, data, true) }
        report()
    }

    override fun onDown(c: MqttWs) = report()

    override fun onPublish(c: MqttWs, topic: String, payload: ByteArray) {
        val inbox = inboxes.firstOrNull { it.topic == topic } ?: return
        val text = SigCrypto.decrypt(inbox.key, payload, topic) ?: return
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        if (abs(System.currentTimeMillis() - msg.optLong("ts")) > MAX_SKEW_MS) return
        if (!remember(msg.optString("id"))) return
        inbox.onMessage(msg)
    }
}
