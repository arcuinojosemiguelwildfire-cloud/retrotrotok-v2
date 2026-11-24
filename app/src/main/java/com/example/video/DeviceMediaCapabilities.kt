package com.example.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.os.Build
import android.util.Log

/**
 * Detected OpenGL ES / EGL surface rendering capabilities on the physical device.
 */
data class GlCapabilities(
    val eglSupported: Boolean,
    val glEsMajorVersion: Int,
    val summary: String
)

/**
 * Hardware-adapted portrait 9:16 composition profile safe for Android 6.0.1 (API 23).
 */
data class CompositionProfile(
    val outputWidth: Int,
    val outputHeight: Int,
    val fps: Int,
    val videoBitrate: Int,
    val iFrameIntervalSeconds: Int,
    val audioSampleRate: Int,
    val audioChannelCount: Int,
    val audioBitrate: Int,
    val avcEncoderName: String?,
    val isHardwareAvcEncoder: Boolean,
    val aacEncoderName: String?,
    val glCapabilities: GlCapabilities
)

/**
 * Inspects actual device MediaCodec and EGL/OpenGL ES capabilities at runtime with full
 * Android 6.0.1 Marshmallow (API 23) compatibility guards.
 */
object DeviceMediaCapabilities {

    private const val TAG = "DeviceMediaCaps"

    // Ordered preferred 9:16 portrait candidates (preferred kiosk native 720x1280 first)
    val PORTRAIT_CANDIDATES = listOf(
        Pair(720, 1280),
        Pair(544, 960),
        Pair(480, 854),
        Pair(368, 640)
    )

    fun probeCompositionProfile(prefersLowOverhead: Boolean = false): CompositionProfile {
        val avcInfo = findPreferredEncoder(MediaFormat.MIMETYPE_VIDEO_AVC)
        val aacInfo = findPreferredEncoder(MediaFormat.MIMETYPE_AUDIO_AAC)
        val glCaps = probeGlCapabilities()

        val videoCaps = try {
            avcInfo?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities
        } catch (e: Exception) {
            null
        }

        val widthAlign = (videoCaps?.widthAlignment ?: 16).coerceAtLeast(2)
        val heightAlign = (videoCaps?.heightAlignment ?: 16).coerceAtLeast(2)
        val maxWidth = videoCaps?.supportedWidths?.upper ?: 1920
        val maxHeight = videoCaps?.supportedHeights?.upper ?: 1920

        val selectedSize = selectAdaptivePortraitResolution(
            maxSupportedWidth = maxWidth,
            maxSupportedHeight = maxHeight,
            widthAlignment = widthAlign,
            heightAlignment = heightAlign,
            prefersLowOverhead = prefersLowOverhead,
            codecSizeChecker = { w, h ->
                try {
                    videoCaps?.isSizeSupported(w, h) ?: true
                } catch (_: Exception) {
                    true
                }
            }
        )

        val bitrate = when {
            selectedSize.first >= 720 -> 4_500_000
            selectedSize.first >= 540 -> 2_800_000
            selectedSize.first >= 480 -> 2_000_000
            else -> 1_200_000
        }

        val fps = if (videoCaps != null) {
            try {
                if (videoCaps.areSizeAndRateSupported(selectedSize.first, selectedSize.second, 30.0)) {
                    30
                } else if (videoCaps.areSizeAndRateSupported(selectedSize.first, selectedSize.second, 24.0)) {
                    24
                } else {
                    24
                }
            } catch (_: Exception) {
                30
            }
        } else {
            30
        }

        return CompositionProfile(
            outputWidth = selectedSize.first,
            outputHeight = selectedSize.second,
            fps = fps,
            videoBitrate = bitrate,
            iFrameIntervalSeconds = 1,
            audioSampleRate = 44100,
            audioChannelCount = 2,
            audioBitrate = 128_000,
            avcEncoderName = avcInfo?.name,
            isHardwareAvcEncoder = avcInfo?.let { isLikelyHardwareCodec(it) } ?: false,
            aacEncoderName = aacInfo?.name,
            glCapabilities = glCaps
        )
    }

    /**
     * Pure capability selector for 9:16 portrait output resolution.
     * Adapts down proportionally while preserving portrait framing and codec alignment.
     */
    fun selectAdaptivePortraitResolution(
        maxSupportedWidth: Int,
        maxSupportedHeight: Int,
        widthAlignment: Int = 16,
        heightAlignment: Int = 16,
        prefersLowOverhead: Boolean = false,
        codecSizeChecker: (Int, Int) -> Boolean = { _, _ -> true }
    ): Pair<Int, Int> {
        val candidates = if (prefersLowOverhead) {
            PORTRAIT_CANDIDATES.drop(1)
        } else {
            PORTRAIT_CANDIDATES
        }

        for ((rawW, rawH) in candidates) {
            val w = alignDown(rawW, widthAlignment).coerceAtLeast(160)
            val h = alignDown(rawH, heightAlignment).coerceAtLeast(288)
            if (w <= maxSupportedWidth && h <= maxSupportedHeight && codecSizeChecker(w, h)) {
                return Pair(w, h)
            }
        }

        // Last-resort safe aligned portrait fallback
        val fallbackW = alignDown(minOf(360, maxSupportedWidth), widthAlignment).coerceAtLeast(160)
        val fallbackH = alignDown(minOf(640, maxSupportedHeight), heightAlignment).coerceAtLeast(288)
        return Pair(fallbackW, fallbackH)
    }

    fun isMimeMediaMuxerCompatibleAudio(mimeType: String?): Boolean {
        return mimeType.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true)
    }

    fun isLikelyHardwareCodec(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated
        }
        val name = info.name.lowercase()
        val isSoftware = name.startsWith("omx.google.") ||
            name.startsWith("c2.android.") ||
            name.contains("sw")
        return !isSoftware
    }

    private fun findPreferredEncoder(mimeType: String): MediaCodecInfo? {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val matching = list.codecInfos.filter { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
            matching.firstOrNull { isLikelyHardwareCodec(it) } ?: matching.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Encoder query failed for $mimeType: ${e.message}")
            null
        }
    }

    fun probeGlCapabilities(): GlCapabilities {
        var display = EGL14.EGL_NO_DISPLAY
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                return GlCapabilities(false, 0, "EGL_NO_DISPLAY")
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return GlCapabilities(false, 0, "eglInitialize failed")
            }
            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val hasEs2 = EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0

            val major = if (hasEs2) 2 else 0
            return GlCapabilities(
                eglSupported = hasEs2,
                glEsMajorVersion = major,
                summary = if (hasEs2) "EGL ${version[0]}.${version[1]} / OpenGL ES 2.0+" else "OpenGL ES 2.0 unavailable"
            )
        } catch (e: Exception) {
            return GlCapabilities(false, 0, "EGL probe unavailable: ${e.message}")
        } finally {
            if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                try {
                    EGL14.eglTerminate(display)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun alignDown(value: Int, alignment: Int): Int {
        if (alignment <= 1) return value
        return (value / alignment) * alignment
    }
}
