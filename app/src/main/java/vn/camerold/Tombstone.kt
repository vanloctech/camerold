package vn.camerold

/**
 * Turns Android's native crash report (a "tombstone", protobuf, from ApplicationExitInfo) into readable text:
 * signal, abort message, the backtrace of the thread that crashed, and the app's last log lines.
 * Only the fields we need are decoded (see AOSP system/core/debuggerd/proto/tombstone.proto).
 */
internal object Tombstone {
    private class F(val n: Int, val v: Long, val s: Int, val e: Int)

    /** Minimal protobuf reader: one level of fields; length-delimited ones keep their byte range. */
    private fun fields(b: ByteArray, from: Int = 0, to: Int = b.size): List<F> {
        val out = ArrayList<F>()
        var p = from
        fun vi(): Long {
            var r = 0L; var sh = 0
            while (true) {
                val x = b[p++].toInt() and 0xff
                r = r or ((x and 0x7f).toLong() shl sh)
                if (x < 0x80) return r
                sh += 7
            }
        }
        while (p < to) {
            val key = vi()
            val n = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> out += F(n, vi(), 0, 0)
                1 -> p += 8
                2 -> { val len = vi().toInt(); require(len >= 0 && p + len <= to); out += F(n, 0, p, p + len); p += len }
                5 -> p += 4
                else -> return out
            }
        }
        return out
    }

    private fun List<F>.str(b: ByteArray, n: Int) = firstOrNull { it.n == n }?.let { String(b, it.s, it.e - it.s, Charsets.UTF_8) }
    private fun List<F>.num(n: Int) = firstOrNull { it.n == n }?.v
    private fun List<F>.subs(b: ByteArray, n: Int) = filter { it.n == n }.map { fields(b, it.s, it.e) }

    private const val MAX_FRAMES = 40
    private const val MAX_LOGS = 60

    /** Readable report, or null if the bytes aren't a tombstone. */
    fun decode(b: ByteArray): String? = try {
        val top = fields(b)
        val pid = top.num(5)
        val tid = top.num(6)
        val sb = StringBuilder()
        top.subs(b, 10).firstOrNull()?.let { s ->
            sb.append("Signal: ${s.str(b, 2) ?: s.num(1)} (${s.str(b, 4) ?: s.num(3)})")
            if ((s.num(8) ?: 0L) != 0L) sb.append(" fault addr 0x${java.lang.Long.toHexString(s.num(9) ?: 0)}")
            sb.append('\n')
        }
        top.str(b, 14)?.takeIf { it.isNotBlank() }?.let { sb.append("Abort: $it\n") }
        top.subs(b, 15).forEach { c -> c.str(b, 1)?.let { sb.append("Cause: $it\n") } }

        // threads: map<uint32, Thread> -> entries {1: tid, 2: Thread}
        val threads = top.subs(b, 16).mapNotNull { e -> e.subs(b, 2).firstOrNull()?.let { (e.num(1) ?: -1L) to it } }
        val crashed = threads.firstOrNull { it.first == tid }?.second
        if (crashed != null) {
            sb.append("Crashed thread: ${crashed.str(b, 2)} ($tid)\n")
            crashed.subs(b, 4).take(MAX_FRAMES).forEachIndexed { i, fr ->
                val lib = fr.str(b, 6)?.substringAfterLast('/') ?: "?"
                val fn = fr.str(b, 4)?.takeIf { it.isNotEmpty() }
                val off = fr.num(5) ?: 0L
                sb.append(String.format(java.util.Locale.US, "  #%02d %s", i, lib))
                if (fn != null) sb.append(" ($fn+$off)") else sb.append(" pc ${java.lang.Long.toHexString(fr.num(1) ?: 0)}")
                sb.append('\n')
            }
        }

        // Last log lines of this process: WebRTC/ART write the real reason here just before abort()
        val logs = top.subs(b, 18).flatMap { buf -> buf.subs(b, 2) }
            .filter { pid == null || it.num(2) == pid }
            .takeLast(MAX_LOGS)
        if (logs.isNotEmpty()) {
            sb.append("Log:\n")
            logs.forEach { l ->
                val prio = "??VDIWEF".getOrElse((l.num(4) ?: 0L).toInt()) { '?' }
                val time = l.str(b, 1)?.substringAfter(' ')?.take(12) ?: ""
                sb.append("  $time $prio/${l.str(b, 5)}: ${l.str(b, 6)?.trimEnd()}\n")
            }
        }
        sb.toString().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}
