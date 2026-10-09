package vn.camerold.signaling

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class BrokersTest {
    private val exec = serialExecutor()
    private val noop = object : MqttWs.Callback {
        override fun onUp(c: MqttWs) {}
        override fun onDown(c: MqttWs) {}
        override fun onPublish(c: MqttWs, topic: String, payload: ByteArray) {}
    }

    @Test fun loginIsTakenOutOfTheAddress() {
        val c = MqttWs("wss://public:public@public.cloud.shiftr.io", exec, noop)
        assertEquals("wss://public.cloud.shiftr.io", c.url)
        assertEquals("wss://broker.emqx.io:8084/mqtt", MqttWs("wss://broker.emqx.io:8084/mqtt", exec, noop).url)
    }

    /** The app and the web viewer must use the same brokers, or they won't find each other. */
    @Test fun appAndWebUseTheSameBrokers() {
        val js = File("../web/js/config.js").readText()
        val list = Regex("const BROKERS = \\[(.*?)];", RegexOption.DOT_MATCHES_ALL).find(js)!!.groupValues[1]
        val web = Regex("'([^']+)'").findAll(list).map { it.groupValues[1] }.toList()
        assertEquals(Signaling.BROKERS, web)
    }

    @Test fun ownBrokerUrls() {
        val c = vn.camerold.data.CamConfig("r", "p", 1080, 30, "", "H264", brokerMode = Brokers.MODE_OWN,
            brokerProvider = "hivemq", brokerHost = "abc.s1.eu.hivemq.cloud", brokerUser = "me", brokerPass = "p@ss:w/rd+1")
        // Same encoding as the web viewer (web/js/config.js) so QR codes made on either side match
        assertEquals("wss://me:p%40ss%3Aw%2Frd%2B1@abc.s1.eu.hivemq.cloud:8884/mqtt", Brokers.ownUrl(c))
        assertEquals(listOf(Brokers.ownUrl(c)) + Signaling.BROKERS, Brokers.list(c))
        assertEquals(listOf(Brokers.ownUrl(c)), Brokers.list(c.copy(brokerBackup = false)))
        assertEquals(Signaling.BROKERS, Brokers.list(c.copy(brokerMode = Brokers.MODE_PUBLIC)))
        assertEquals("wss://bob%3Abob:x@x.lmq.cloudamqp.com/ws/mqtt",
            Brokers.ownUrl(c.copy(brokerProvider = "cloudamqp", brokerHost = "x.lmq.cloudamqp.com", brokerUser = "bob", brokerPass = "x")))
        // MqttWs takes the login back out
        assertEquals("wss://abc.s1.eu.hivemq.cloud:8884/mqtt", MqttWs(Brokers.ownUrl(c)!!, exec, noop).url)
    }

    /** "Test server" gives the right answer for a working broker, a wrong password and a missing host (NETWORK_TESTS=1). */
    @Test fun serverTestResults() {
        assumeTrue(System.getenv("NETWORK_TESTS") == "1")
        fun run(url: String): Brokers.TestResult {
            val latch = CountDownLatch(1); var r: Brokers.TestResult? = null
            Brokers.test(url) { r = it; latch.countDown() }
            latch.await(20, TimeUnit.SECONDS); return r!!
        }
        assertEquals(Brokers.TestResult.OK, run("wss://public:public@public.cloud.shiftr.io"))
        assertEquals(Brokers.TestResult.LOGIN_REFUSED, run("wss://public:wrong@public.cloud.shiftr.io"))
        assertEquals(Brokers.TestResult.UNREACHABLE, run("wss://no-such-host.invalid/mqtt"))
    }

    /** Real round trip through every public broker. Opt-in (NETWORK_TESTS=1) so CI doesn't depend on them. */
    @Test fun everyBrokerDeliversAMessage() {
        assumeTrue(System.getenv("NETWORK_TESTS") == "1")
        val topic = "camerold/test/" + SigCrypto.randomId(12)
        val failed = Signaling.BROKERS.filter { url ->
            val got = CountDownLatch(1)
            lateinit var c: MqttWs
            c = MqttWs(url, exec, object : MqttWs.Callback {
                override fun onUp(c2: MqttWs) { c.subscribe(topic); exec.later({ c.publish(topic, "hi".toByteArray()) }, 400, TimeUnit.MILLISECONDS) }
                override fun onDown(c2: MqttWs) {}
                override fun onPublish(c2: MqttWs, t: String, payload: ByteArray) { got.countDown() }
            })
            c.connect()
            val ok = got.await(15, TimeUnit.SECONDS)
            c.stop()
            println("${if (ok) "OK  " else "FAIL"} $url")
            !ok
        }
        assertEquals(emptyList<String>(), failed)
    }
}
