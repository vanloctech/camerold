package vn.camerold.data

import android.content.Context
import vn.camerold.signaling.SigCrypto

data class CamConfig(
    val room: String,
    val password: String,
    val res: Int,       // 720 / 1080 / 2160
    val fps: Int,       // 15 / 24 / 30
    val lens: String,   // lens key from ProCapturer.listLenses ("" = main camera)
    val codec: String,  // "H264" / "VP8"
    val zoom: Float = 1f,
    val torch: Boolean = false,
    val night: Boolean = false,
    val ev: Int = 0,
    val focus: String = "auto", // auto | point | lock | manual | inf
    val fx: Float = 0.5f,
    val fy: Float = 0.5f,
    val fdist: Float = 0f,
    val stab: Boolean = false,
    val turnUrls: String = "",  // multiple URLs separated by commas / newlines
    val turnUser: String = "",
    val turnPass: String = "",
    val audio: Boolean = true,   // send mic audio to viewers
    val aspect: String = "16:9", // "16:9" fills a landscape screen, "4:3" shows the whole sensor
    // Where camera and viewers meet (see signaling/Brokers.kt): public brokers, or the user's own
    val brokerMode: String = "public",
    val brokerProvider: String = "hivemq",
    val brokerHost: String = "",
    val brokerUser: String = "",
    val brokerPass: String = "",
    val brokerBackup: Boolean = true, // own broker + public ones as a fallback
    val name: String = "",  // shown to viewers, e.g. "Living room"; "" = phone model
    val heatGuard: Boolean = true, // lower quality when the phone gets hot (see camera/ThermalGuard.kt)
    val heatStart: Int = 44,       // battery °C where that starts (then +3 / +6 °C for the next steps)
)

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("camera", Context.MODE_PRIVATE)

    fun load(ctx: Context): CamConfig {
        val p = sp(ctx)
        var room = p.getString("room", "") ?: ""
        if (room.isEmpty()) {
            room = SigCrypto.randomId(10)
            p.edit().putString("room", room).apply()
        }
        return CamConfig(
            room = room,
            password = p.getString("password", "") ?: "",
            res = p.getInt("res", 1080),
            fps = p.getInt("fps", 30),
            lens = p.getString("lens", "") ?: "",
            codec = p.getString("codec", "H264") ?: "H264",
            zoom = p.getFloat("zoom", 1f),
            torch = p.getBoolean("torch", false),
            night = p.getBoolean("night", false),
            ev = p.getInt("ev", 0),
            focus = p.getString("focus", "auto") ?: "auto",
            fx = p.getFloat("fx", 0.5f),
            fy = p.getFloat("fy", 0.5f),
            fdist = p.getFloat("fdist", 0f),
            stab = p.getBoolean("stab", false),
            turnUrls = p.getString("turnUrls", "") ?: "",
            turnUser = p.getString("turnUser", "") ?: "",
            turnPass = p.getString("turnPass", "") ?: "",
            audio = p.getBoolean("audio", true),
            aspect = p.getString("aspect", "16:9") ?: "16:9",
            brokerMode = p.getString("brokerMode", "public") ?: "public",
            brokerProvider = p.getString("brokerProvider", "hivemq") ?: "hivemq",
            brokerHost = p.getString("brokerHost", "") ?: "",
            brokerUser = p.getString("brokerUser", "") ?: "",
            brokerPass = p.getString("brokerPass", "") ?: "",
            brokerBackup = p.getBoolean("brokerBackup", true),
            name = p.getString("name", "") ?: "",
            heatGuard = p.getBoolean("heatGuard", true),
            heatStart = p.getInt("heatStart", 44),
        )
    }

    fun save(ctx: Context, c: CamConfig) {
        sp(ctx).edit()
            .putString("room", c.room)
            .putString("password", c.password)
            .putInt("res", c.res)
            .putInt("fps", c.fps)
            .putString("lens", c.lens)
            .putString("codec", c.codec)
            .putFloat("zoom", c.zoom)
            .putBoolean("torch", c.torch)
            .putBoolean("night", c.night)
            .putInt("ev", c.ev)
            .putString("focus", c.focus)
            .putFloat("fx", c.fx)
            .putFloat("fy", c.fy)
            .putFloat("fdist", c.fdist)
            .putBoolean("stab", c.stab)
            .putString("turnUrls", c.turnUrls)
            .putString("turnUser", c.turnUser)
            .putString("turnPass", c.turnPass)
            .putBoolean("audio", c.audio)
            .putString("aspect", c.aspect)
            .putString("brokerMode", c.brokerMode)
            .putString("brokerProvider", c.brokerProvider)
            .putString("brokerHost", c.brokerHost)
            .putString("brokerUser", c.brokerUser)
            .putString("brokerPass", c.brokerPass)
            .putBoolean("brokerBackup", c.brokerBackup)
            .putString("name", c.name)
            .putBoolean("heatGuard", c.heatGuard)
            .putInt("heatStart", c.heatStart)
            .apply()
    }
}
