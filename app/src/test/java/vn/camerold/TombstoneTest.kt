package vn.camerold

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TombstoneTest {
    // Tiny protobuf writer for building test tombstones
    private class Pb {
        val out = ByteArrayOutputStream()
        private fun vi(v: Long) { var x = v; while (x and 0x7fL.inv() != 0L) { out.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; out.write(x.toInt()) }
        fun num(n: Int, v: Long) = apply { vi((n shl 3).toLong()); vi(v) }
        fun bytes(n: Int, b: ByteArray) = apply { vi(((n shl 3) or 2).toLong()); vi(b.size.toLong()); out.write(b) }
        fun str(n: Int, s: String) = bytes(n, s.toByteArray())
        fun msg(n: Int, m: Pb) = bytes(n, m.out.toByteArray())
    }

    private fun thread(id: Int, name: String, vararg fn: String) = Pb().num(1, id.toLong()).str(2, name).apply {
        fn.forEach { msg(4, Pb().num(1, 0x1234).str(4, it).num(5, 16).str(6, "/system/lib64/libwebrtc.so")) }
    }

    @Test fun picksTheCrashedThreadAndLogs() {
        val t = Pb().num(5, 100).num(6, 120)
            .msg(10, Pb().num(1, 6).str(2, "SIGABRT").num(3, -1).str(4, "SI_QUEUE"))
            // An idle thread listed first, as in real tombstones
            .msg(16, Pb().num(1, 101).msg(2, thread(101, "CameraManagerGl", "__epoll_pwait")))
            .msg(16, Pb().num(1, 120).msg(2, thread(120, "signaling_thread", "abort", "rtc::FatalMessage")))
            .msg(18, Pb().str(1, "main")
                .msg(2, Pb().str(1, "2026-10-09 10:28:46.500").num(2, 100).num(4, 6).str(5, "rtc").str(6, "# Fatal error in: pc.cc"))
                .msg(2, Pb().str(1, "2026-10-09 10:28:46.501").num(2, 999).num(4, 6).str(5, "other").str(6, "not ours")))
        val text = Tombstone.decode(t.out.toByteArray())!!
        assertTrue(text, text.contains("Signal: SIGABRT (SI_QUEUE)"))
        assertTrue(text, text.contains("Crashed thread: signaling_thread (120)"))
        assertTrue(text, text.contains("#01 libwebrtc.so (rtc::FatalMessage+16)"))
        assertTrue(text, !text.contains("__epoll_pwait"))
        assertTrue(text, text.contains("E/rtc: # Fatal error in: pc.cc"))
        assertTrue(text, !text.contains("not ours"))
    }

    @Test fun garbageIsNotATombstone() {
        assertNull(Tombstone.decode(byteArrayOf(0x0a, 0x7f, 0x01)))
    }
}
