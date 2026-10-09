package vn.camerold.camera

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

object Rtc {
    private var initialized = false

    @Synchronized
    fun ensureInit(ctx: Context) {
        if (initialized) return
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
        initialized = true
    }

    private val encoderCache = HashMap<String, List<MediaCodecInfo.VideoCapabilities>>()

    /** Video capabilities of the phone's hardware encoders for a codec ("H264", "VP8", "" = both). */
    @Synchronized
    private fun hwEncoders(codec: String): List<MediaCodecInfo.VideoCapabilities> = encoderCache.getOrPut(codec) {
        val mimes = when (codec.uppercase()) { "VP8" -> listOf("video/x-vnd.on2.vp8"); "H264" -> listOf("video/avc")
            else -> listOf("video/avc", "video/x-vnd.on2.vp8") }
        try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { ci ->
                ci.isEncoder && (if (Build.VERSION.SDK_INT >= 29) ci.isHardwareAccelerated
                    else SOFTWARE_PREFIXES.none { ci.name.startsWith(it, ignoreCase = true) })
            }.flatMap { ci ->
                mimes.filter { m -> ci.supportedTypes.any { it.equals(m, ignoreCase = true) } }
                    .mapNotNull { m -> try { ci.getCapabilitiesForType(m).videoCapabilities } catch (_: Exception) { null } }
            }
        } catch (_: Exception) { emptyList() }
    }
    private val SOFTWARE_PREFIXES = listOf("OMX.google.", "c2.android.", "OMX.SEC.", "c2.google.")

    /**
     * Highest quality tier (2160 / 1080 / 720) the phone can encode in hardware for this frame shape.
     * Many older phones top out at 1080p; asking more would push WebRTC to software encoding (hot and laggy).
     */
    fun maxRes(codec: String, aspect: String): Int {
        val enc = hwEncoders(codec)
        if (enc.isEmpty()) return 720 // software only: keep it light
        return listOf(2160, 1080).firstOrNull { res ->
            val (w, h) = CameraEngine.sizeFor(res, aspect)
            enc.any { it.isSizeSupported(w, h) || it.isSizeSupported(h, w) }
        } ?: 720
    }

    /**
     * User-entered TURN URLs: split on commas/newlines, prepend "turn:" if missing.
     * A plain "turn:host:port" is only tried over UDP by WebRTC, and many networks (some Wi-Fi, most 4G carriers)
     * block UDP to port 3478, so it's also offered over TCP. Same rule as web/js/config.js.
     */
    fun parseTurnUrls(raw: String): List<String> = raw.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        .map { if (it.startsWith("turn:") || it.startsWith("turns:") || it.startsWith("stun:")) it else "turn:$it" }
        .flatMap { if (it.startsWith("turn:") && "transport=" !in it) listOf("$it?transport=udp", "$it?transport=tcp") else listOf(it) }
        .distinct()

    /**
     * Real TURN check: create a relay-only PeerConnection and see whether a relay address can be obtained.
     * Runs on its own thread and calls [done] (on that thread) with the result.
     */
    fun testTurn(ctx: Context, urls: List<String>, user: String, pass: String, done: (Boolean) -> Unit) = Thread {
        var ok = false
        try {
            ensureInit(ctx)
            val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
            val ice = PeerConnection.IceServer.builder(urls).setUsername(user).setPassword(pass).createIceServer()
            val cfg = PeerConnection.RTCConfiguration(listOf(ice)).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                iceTransportsType = PeerConnection.IceTransportsType.RELAY
            }
            val latch = CountDownLatch(1)
            val pc = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
                override fun onIceCandidate(c: IceCandidate) { if (c.sdp.contains(" typ relay")) { ok = true; latch.countDown() } }
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                    if (s == PeerConnection.IceGatheringState.COMPLETE) latch.countDown()
                }
                override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                override fun onAddStream(s: MediaStream?) {}
                override fun onRemoveStream(s: MediaStream?) {}
                override fun onDataChannel(d: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
            })
            if (pc != null) {
                pc.createDataChannel("probe", DataChannel.Init())
                pc.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) { pc.setLocalDescription(this, sdp) }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(e: String?) { latch.countDown() }
                    override fun onSetFailure(e: String?) { latch.countDown() }
                }, MediaConstraints())
                latch.await(12, TimeUnit.SECONDS)
                pc.dispose()
            }
            factory.dispose()
        } catch (_: Exception) {}
        done(ok)
    }.start()
}
