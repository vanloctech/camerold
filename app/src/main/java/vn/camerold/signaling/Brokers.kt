package vn.camerold.signaling

import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import vn.camerold.data.CamConfig

/**
 * Which MQTT brokers the camera and its viewers meet on.
 *
 * - "public" (default): the free public brokers in [Signaling.BROKERS], nothing to set up.
 * - "own": the user's own broker (free accounts at HiveMQ Cloud, EMQX Cloud, CloudAMQP…), optionally with the
 *   public ones as a backup. Viewers get it from the QR code, so it's entered only once, on the camera.
 *
 * A broker is written as one URL with the login inside: wss://user:pass@host:port/path (see [MqttWs]).
 * Same providers and rules as web/js/config.js.
 */
object Brokers {
    const val MODE_PUBLIC = "public"
    const val MODE_OWN = "own"

    class Provider(
        val id: String,
        val name: String,
        /** Where to create a free account; null for "other". */
        val signup: String?,
        /** Example host shown in the address field. */
        val hostHint: String,
        /** Host from the provider's console -> WebSocket URL (the browser viewer needs WebSocket + TLS). */
        val url: (String) -> String,
        /** Some brokers want the login in a special form (LavinMQ: "vhost:user"). */
        val login: (String) -> String = { it },
    )

    val PROVIDERS = listOf(
        Provider("hivemq", "HiveMQ Cloud", "https://console.hivemq.cloud/", "abc123.s1.eu.hivemq.cloud",
            { "wss://$it:8884/mqtt" }),
        Provider("emqx", "EMQX Cloud", "https://www.emqx.com/en/cloud/serverless-mqtt", "abc123.ala.asia-southeast1.emqxsl.com",
            { "wss://$it:8084/mqtt" }),
        // LavinMQ on CloudAMQP: MQTT over WebSocket at /ws/mqtt; on shared plans the vhost is the user name
        Provider("cloudamqp", "CloudAMQP (LavinMQ)", "https://customer.cloudamqp.com/signup", "abc-def.lmq.cloudamqp.com",
            { "wss://$it/ws/mqtt" }, { u -> if (':' in u || u.isEmpty()) u else "$u:$u" }),
        Provider("custom", "", null, "wss://mqtt.example.com:8084/mqtt", { it }),
    )

    fun provider(id: String) = PROVIDERS.firstOrNull { it.id == id } ?: PROVIDERS.first()

    /** The user's own broker as a single URL, or null if not set up. A full "wss://…" address is used as typed. */
    fun ownUrl(c: CamConfig): String? {
        val host = c.brokerHost.trim().removeSuffix("/")
        if (host.isEmpty()) return null
        val p = provider(c.brokerProvider)
        val base = if (host.contains("://")) host else p.url(host)
        return withLogin(base, p.login(c.brokerUser.trim()), c.brokerPass)
    }

    /** Brokers the camera listens on. */
    fun list(c: CamConfig): List<String> {
        val own = if (c.brokerMode == MODE_OWN) ownUrl(c) else null
        return when {
            own == null -> Signaling.BROKERS
            c.brokerBackup -> listOf(own) + Signaling.BROKERS
            else -> listOf(own)
        }
    }

    fun withLogin(url: String, user: String, pass: String): String {
        if (user.isEmpty()) return url
        val u = URI(url)
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val info = enc(user) + if (pass.isNotEmpty()) ":" + enc(pass) else ""
        val port = if (u.port > 0) ":${u.port}" else ""
        return "${u.scheme}://$info@${u.host}$port${u.rawPath ?: ""}" + (u.rawQuery?.let { "?$it" } ?: "")
    }

    /** Only encrypted WebSocket brokers are accepted (also what a browser viewer can reach). */
    fun isValid(url: String) = try { URI(url).let { it.scheme == "wss" && !it.host.isNullOrEmpty() } } catch (_: Exception) { false }

    enum class TestResult { OK, LOGIN_REFUSED, UNREACHABLE, NO_MESSAGES }

    /**
     * Real check: connect, subscribe, publish and wait for our own message (some brokers accept the login but
     * don't allow publishing). [done] is called once, on a background thread.
     */
    fun test(url: String, done: (TestResult) -> Unit) {
        val exec = serialExecutor()
        val topic = "camerold/test/" + SigCrypto.randomId(12)
        var finished = false
        lateinit var c: MqttWs
        fun finish(r: TestResult) = exec.post {
            if (finished) return@post
            finished = true
            c.stop()
            exec.later({ exec.shutdown() }, 1, TimeUnit.SECONDS)
            done(r)
        }
        c = MqttWs(url, exec, object : MqttWs.Callback {
            override fun onUp(c2: MqttWs) {
                c.subscribe(topic)
                exec.later({ c.publish(topic, "ping".toByteArray()) }, 500, TimeUnit.MILLISECONDS)
                exec.later({ finish(TestResult.NO_MESSAGES) }, 6, TimeUnit.SECONDS)
            }
            override fun onDown(c2: MqttWs) {}
            override fun onPublish(c2: MqttWs, topic: String, payload: ByteArray) = finish(TestResult.OK)
            override fun onRefused(c2: MqttWs, code: Int) = finish(TestResult.LOGIN_REFUSED)
            override fun onFailed(c2: MqttWs) = finish(TestResult.UNREACHABLE)
        })
        c.connect()
        exec.later({ finish(TestResult.UNREACHABLE) }, 15, TimeUnit.SECONDS)
    }
}
