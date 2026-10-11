package vn.camerold.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelapseTest {
    @Test fun photoSizeForQuality() {
        // Full HD keeps a 1080p feed, HD scales it down; sizes stay even for the encoder
        assertEquals(1920 to 1080, Timelapse.fit(1920, 1080, 1080))
        assertEquals(1280 to 720, Timelapse.fit(1920, 1080, 720))
        // 4:3 and portrait feeds: the short side follows the quality
        assertEquals(1440 to 1080, Timelapse.fit(2592, 1944, 1080))
        assertEquals(720 to 1280, Timelapse.fit(1080, 1920, 720))
        // Never upscaled
        assertEquals(640 to 480, Timelapse.fit(640, 480, 1080))
        assertEquals(958 to 720, Timelapse.fit(961, 721, 720)) // 959.7 -> even
    }

    @Test fun videoLength() {
        // 30 fps: one hour every 10 s = 360 photos = 12 s of video
        assertEquals(12.0, Timelapse.videoSeconds(3600 / 10), 1e-9)
    }
}
