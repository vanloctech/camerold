package vn.camerold.signaling

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The Android app and the web viewer must produce byte-identical keys, topics and packets.
 * Both are tested against the same file: test-vectors/crypto-v2.json (see tools/gen-test-vectors.mjs).
 */
class SigCryptoTest {

    private val vectors = JSONObject(File("../test-vectors/crypto-v2.json").readText()).getJSONArray("vectors")

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun derivesSameTopicAsWebAndDecryptsReferencePacket() {
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val keys = SigCrypto.deriveKeys(v.getString("room"), v.getString("password"))
            assertEquals(v.getString("topic"), keys.topic)
            assertEquals(v.getString("encKey"), keys.enc.encoded.joinToString("") { "%02x".format(it) })
            assertEquals(v.getString("plaintext"), SigCrypto.decrypt(keys.enc, hex(v.getString("packet")), v.getString("aad")))
        }
    }

    @Test
    fun rejectsWrongTopicTamperingAndWrongPassword() {
        val v = vectors.getJSONObject(0)
        val keys = SigCrypto.deriveKeys(v.getString("room"), v.getString("password"))
        val packet = hex(v.getString("packet"))
        assertNull("packet moved to another topic", SigCrypto.decrypt(keys.enc, packet, v.getString("aad") + "x"))
        assertNull("tampered packet", SigCrypto.decrypt(keys.enc, packet.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }, v.getString("aad")))
        val other = SigCrypto.deriveKeys(v.getString("room"), v.getString("password") + "!")
        assertNull("wrong password", SigCrypto.decrypt(other.enc, packet, v.getString("aad")))
        assertNotEquals("wrong password lands on another topic", keys.topic, other.topic)
    }

    @Test
    fun roundTripWithRandomIv() {
        val keys = SigCrypto.deriveKeys("roundtrip1", "password-123")
        val a = SigCrypto.encrypt(keys.enc, "hello ✓", "a/b")
        val b = SigCrypto.encrypt(keys.enc, "hello ✓", "a/b")
        assertNotEquals("IV must be random", a.toList(), b.toList())
        assertEquals("hello ✓", SigCrypto.decrypt(keys.enc, a, "a/b"))
    }

    @Test
    fun randomPasswordIsLongAndUnambiguous() {
        val p = SigCrypto.randomPassword()
        assertEquals(20, p.length)
        p.forEach { c -> assert(c !in "0O1lI") { "ambiguous character $c" } }
    }
}
