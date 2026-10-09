package org.webrtc

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log

/**
 * Android 8–9 only. There, WebRTC's own HardwareVideoEncoderFactory accepts just Qualcomm and Exynos encoders,
 * so phones with MediaTek, Kirin, Unisoc… chips silently fall back to software encoding (hot, laggy, low resolution).
 * From Android 10 WebRTC asks the system instead (MediaCodecInfo.isHardwareAccelerated), so this isn't needed.
 *
 * This factory accepts any non-software H.264 / VP8 encoder. SafeEncoderFactory still wraps every encoder with a
 * software fallback, so a chip that misbehaves only costs quality, not a crash.
 * Lives in org.webrtc to reach the package-private encoder classes.
 */
internal class LegacyHwEncoderFactory(eglContext: EglBase.Context?) : VideoEncoderFactory {
    private val shared = eglContext as? EglBase14.Context
    private val types = listOf(VideoCodecMimeType.H264, VideoCodecMimeType.VP8)

    private fun find(type: VideoCodecMimeType): MediaCodecInfo? = (0 until MediaCodecList.getCodecCount()).asSequence()
        .mapNotNull { try { MediaCodecList.getCodecInfoAt(it) } catch (_: Exception) { null } }
        .firstOrNull { ci ->
            ci.isEncoder && MediaCodecUtils.codecSupportsType(ci, type) && !MediaCodecUtils.isSoftwareOnly(ci) &&
                try {
                    MediaCodecUtils.selectColorFormat(MediaCodecUtils.ENCODER_COLOR_FORMATS, ci.getCapabilitiesForType(type.mimeType())) != null
                } catch (_: Exception) { false }
        }

    private val found: Map<VideoCodecMimeType, MediaCodecInfo> by lazy {
        types.mapNotNull { t -> find(t)?.let { t to it } }.toMap().also { m ->
            Log.i(TAG, "extra hardware encoders: ${m.map { "${it.key}=${it.value.name}" }}")
        }
    }

    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
        val type = types.firstOrNull { it.name.equals(info.name, ignoreCase = true) } ?: return null
        val ci = found[type] ?: return null
        // Constrained Baseline only (same as the rest of the app), High profile is unreliable on these chips
        if (type == VideoCodecMimeType.H264 &&
            !H264Utils.isSameH264Profile(info.params, MediaCodecUtils.getCodecProperties(type, false))) return null
        return try {
            val caps = ci.getCapabilitiesForType(type.mimeType())
            HardwareVideoEncoder(
                MediaCodecWrapperFactoryImpl(), ci.name, type,
                MediaCodecUtils.selectColorFormat(MediaCodecUtils.TEXTURE_COLOR_FORMATS, caps),
                MediaCodecUtils.selectColorFormat(MediaCodecUtils.ENCODER_COLOR_FORMATS, caps),
                info.params, KEY_FRAME_INTERVAL_S, 0, BaseBitrateAdjuster(), shared,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "createEncoder ${ci.name}", e)
            null
        }
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = types.filter { found.containsKey(it) }.map { t ->
        VideoCodecInfo(t.name, if (t == VideoCodecMimeType.H264) MediaCodecUtils.getCodecProperties(t, false) else emptyMap(), emptyList())
    }.toTypedArray()

    companion object {
        private const val TAG = "LegacyHwEncoder"
        private const val KEY_FRAME_INTERVAL_S = 3600 // same as WebRTC's factory: key frames on demand only
    }
}
