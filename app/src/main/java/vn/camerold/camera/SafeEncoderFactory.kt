package vn.camerold.camera

import android.os.Build
import android.util.Log
import org.webrtc.EglBase
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.LegacyHwEncoderFactory
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoEncoderFallback
import org.webrtc.VideoFrame

/**
 * Replaces DefaultVideoEncoderFactory: hardware encoders are wrapped in [SafeEncoder] so that
 * if the driver throws (common with MediaCodec/EGL on real devices), WebRTC falls back to
 * software encoding instead of crashing the app.
 *
 * @param codec "H264" / "VP8": only offer this codec (if supported), "" = all.
 */
class SafeEncoderFactory(eglContext: EglBase.Context?, private val codec: String) : VideoEncoderFactory {
    // High profile disabled: WebRTC forces level 3 for High profile, which doesn't fit 1080p/4K on some chips
    private val hw = HardwareVideoEncoderFactory(eglContext, true, false)
    private val sw = SoftwareVideoEncoderFactory()
    // Android 8–9: WebRTC only trusts Qualcomm/Exynos encoders there; this adds MediaTek, Kirin, Unisoc…
    private val legacy = if (Build.VERSION.SDK_INT < 29) LegacyHwEncoderFactory(eglContext) else null

    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
        val h = try { hw.createEncoder(info) ?: legacy?.createEncoder(info) } catch (e: Throwable) { Log.e(TAG, "hw createEncoder", e); null }
        val s = sw.createEncoder(info)
        Log.i(TAG, "createEncoder ${info.name}: hw=${h?.implementationName} sw=${s != null}")
        return when {
            h != null && s != null -> VideoEncoderFallback(s, SafeEncoder(h))
            h != null -> SafeEncoder(h)
            else -> s
        }
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val all = LinkedHashMap<String, VideoCodecInfo>()
        (hw.supportedCodecs + (legacy?.supportedCodecs ?: emptyArray()) + sw.supportedCodecs).forEach { all.putIfAbsent(it.name + it.params, it) }
        val list = all.values.toList()
        val chosen = list.filter { it.name.equals(codec, ignoreCase = true) }
        return (chosen.ifEmpty { list }).toTypedArray()
    }

    private class SafeEncoder(private val d: VideoEncoder) : VideoEncoder {
        @Volatile private var broken = false

        private inline fun guard(what: String, onError: VideoCodecStatus, block: () -> VideoCodecStatus): VideoCodecStatus {
            if (broken && what != "release") return VideoCodecStatus.FALLBACK_SOFTWARE
            return try {
                block()
            } catch (e: Throwable) {
                Log.e(TAG, "hw encoder $what failed -> fallback", e)
                broken = true
                onError
            }
        }

        override fun isHardwareEncoder() = true
        override fun initEncode(settings: VideoEncoder.Settings, cb: VideoEncoder.Callback) =
            guard("init", VideoCodecStatus.FALLBACK_SOFTWARE) { d.initEncode(settings, cb) }
        override fun release() = guard("release", VideoCodecStatus.OK) { d.release() }
        override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo) =
            guard("encode", VideoCodecStatus.FALLBACK_SOFTWARE) { d.encode(frame, info) }
        @Deprecated("") override fun setRateAllocation(a: VideoEncoder.BitrateAllocation, fps: Int) =
            guard("rate", VideoCodecStatus.OK) { @Suppress("DEPRECATION") d.setRateAllocation(a, fps) }
        override fun setRates(p: VideoEncoder.RateControlParameters) =
            guard("rates", VideoCodecStatus.OK) { d.setRates(p) }
        override fun getScalingSettings(): VideoEncoder.ScalingSettings = d.scalingSettings
        override fun getResolutionBitrateLimits(): Array<VideoEncoder.ResolutionBitrateLimits> = d.resolutionBitrateLimits
        override fun getImplementationName(): String = d.implementationName
        override fun getEncoderInfo(): VideoEncoder.EncoderInfo = d.encoderInfo
    }

    companion object { private const val TAG = "SafeEncoder" }
}
