package vn.camerold.camera

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderTest {
    private val hcm = TimeZone.getTimeZone("Asia/Ho_Chi_Minh") // UTC+7
    private val h = 3_600_000L
    private val day = 24 * h

    @Test fun recordingsAreCutEveryHalfHourOnTheLocalClock() {
        // 2026-10-13 09:29:59 and 09:30:00 local (02:29:59 / 02:30:00 UTC)
        val t0930 = 1_791_858_600_000L // 2026-10-13T02:30:00Z
        assertNotEquals(Recorder.slotOf(t0930 - 1000, hcm), Recorder.slotOf(t0930, hcm))
        assertEquals(Recorder.slotOf(t0930, hcm), Recorder.slotOf(t0930 + 29 * 60_000 + 59_000, hcm))
        assertNotEquals(Recorder.slotOf(t0930, hcm), Recorder.slotOf(t0930 + 30 * 60_000, hcm))
        // 48 half hours a day
        assertEquals(48L, Recorder.slotOf(t0930 + day, hcm) - Recorder.slotOf(t0930, hcm))
    }

    @Test fun idsAndDays() {
        assertEquals("2026-10-13", Recorder.dayOf("20261013-093000"))
        assertTrue(Recorder.ID.matches("20261013-093000"))
        assertTrue(!Recorder.ID.matches("../../etc"))
    }

    @Test fun bitrateAndSize() {
        // HEVC 720p at 15 fps: about 270 MB an hour at most; H.264 needs more for the same picture
        assertEquals(622_080, Recorder.bitrate(1280, 720, 15, hevc = true))
        assertEquals(279_936_000L, Recorder.bytesPerHour(720, 15, hevc = true))
        assertTrue(Recorder.bitrate(1280, 720, 15, hevc = false) > Recorder.bitrate(1280, 720, 15, hevc = true))
        assertTrue(Recorder.bytesPerHour(1080, 15, true) > Recorder.bytesPerHour(720, 15, true) * 2)
        // Bounded both ways
        assertEquals(300_000, Recorder.bitrate(160, 120, 5, true))
        assertEquals(8_000_000, Recorder.bitrate(3840, 2160, 60, false))
    }

    @Test fun hotPhoneRecordsFewerFrames() {
        assertEquals(15, Recorder.fpsFor(15, 0))
        assertEquals(15, Recorder.fpsFor(15, 1))
        assertEquals(10, Recorder.fpsFor(15, 2))
        assertEquals(5, Recorder.fpsFor(15, 3))
    }

    @Test fun storageReserve() {
        assertEquals(500L shl 20, Recorder.minFree(8L shl 30))
        assertEquals(1L shl 30, Recorder.minFree(64L shl 30))
        assertEquals(1L shl 30, Recorder.minFree(0)) // unknown size: the larger reserve
    }

    private fun seg(id: String, start: Long, size: Long, keep: Boolean = false) =
        Recorder.Seg(id, start, 1800.0, size, 1280, 720, "hevc", 15, keep)

    @Test fun oldRecordingsGoFirst() {
        val now = 100 * day
        val mb = 1L shl 20
        val items = listOf(
            seg("c", now - 2 * day, 100 * mb),
            seg("a", now - 9 * day, 100 * mb),
            seg("b", now - 8 * day, 100 * mb, keep = true),
            seg("d", now - h, 100 * mb),
        )
        // Plenty of space: only what's older than 7 days, never a kept one
        assertEquals(listOf("a"), Recorder.pickDeletions(items, now, 7, 10_000 * mb, 1000 * mb))
        // No age limit, plenty of space: nothing
        assertEquals(emptyList<String>(), Recorder.pickDeletions(items, now, 0, 10_000 * mb, 1000 * mb))
        // Short of 150 MB: the two oldest that aren't kept
        assertEquals(listOf("a", "c"), Recorder.pickDeletions(items, now, 0, 850 * mb, 1000 * mb))
        // Short of more than everything: all but the kept one
        assertEquals(listOf("a", "c", "d"), Recorder.pickDeletions(items, now, 0, 0, 1000 * mb))
    }
}
