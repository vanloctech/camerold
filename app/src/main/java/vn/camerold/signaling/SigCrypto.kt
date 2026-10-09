package vn.camerold.signaling

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Signaling message encryption - protocol v2. Must match web/index.html byte for byte.
 *
 *  master = PBKDF2-HMAC-SHA256(password UTF-8, salt "camerold-v2:<room>", 600 000 iterations, 32 bytes)
 *  encKey = HMAC-SHA256(master, "camerold enc")            -> AES-256-GCM key
 *  topic  = "camerold/v2/" + hex(HMAC-SHA256(master, "camerold topic"))[0..32]
 *  packet = IV(12 bytes) || AES-GCM(json, AAD = receiving topic name)
 *
 * Why:
 *  - Topic depends on the password too: an eavesdropper on "camerold/#" on a public broker can't brute-force the Room ID
 *    and then attack the password separately; both must be guessed through 600k PBKDF2 iterations.
 *  - AAD = topic: messages can't be replayed onto another topic (e.g. from the viewer channel to the camera channel).
 *  - Replay protection: each message has "ts" + a random "id", see [Signaling].
 */
object SigCrypto {
    const val ITERATIONS = 600_000
    private val rnd = SecureRandom()
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    // Omit ambiguous characters (0/O, 1/l/I) so it can be read/typed by hand
    private const val PASS_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    class Keys(val enc: SecretKeySpec, val topic: String)

    fun normalizeRoom(s: String) = s.trim().lowercase().replace(" ", "")

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** Custom PBKDF2 to guarantee the password is UTF-8 encoded exactly like WebCrypto. */
    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val t = u.copyOf()
        for (i in 1 until iterations) {
            u = mac.doFinal(u)
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        return t
    }

    /** Intentionally slow (~1 second) - call on a background thread. */
    fun deriveKeys(room: String, password: String): Keys {
        val master = pbkdf2(password, "camerold-v2:$room".toByteArray(Charsets.UTF_8), ITERATIONS)
        val enc = hmac(master, "camerold enc".toByteArray())
        val topic = hmac(master, "camerold topic".toByteArray()).joinToString("") { "%02x".format(it) }.substring(0, 32)
        master.fill(0)
        return Keys(SecretKeySpec(enc, "AES"), "camerold/v2/$topic")
    }

    fun encrypt(key: SecretKeySpec, text: String, aad: String): ByteArray {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return iv + c.doFinal(text.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(key: SecretKeySpec, data: ByteArray, aad: String): String? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        String(c.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    } catch (e: Exception) {
        null // wrong password, tampered message, or junk on the public broker
    }

    fun randomId(n: Int): String = buildString { repeat(n) { append(ALPHABET[rnd.nextInt(ALPHABET.length)]) } }

    /** Random password, ~115 bits (20 chars x log2(57)). */
    fun randomPassword(n: Int = 20): String = buildString { repeat(n) { append(PASS_ALPHABET[rnd.nextInt(PASS_ALPHABET.length)]) } }
}
