package com.example.video

import androidx.compose.ui.graphics.Color
import com.example.model.RetroEffectType
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Studio camera framing modes shared between live CameraX PreviewView and GLES export.
 */
enum class CameraFramingMode(val label: String) {
    PORTRAIT_CROP("Portrait Crop"),
    WIDE_FIT("Wide View")
}

/**
 * Shared retro effect shader and live-preview grading specification.
 * Guarantees consistent visual character between live studio preview and permanent GLSL export
 * while supporting OpenGL ES 2.0 on Android 6.0.1 (API 23).
 */
data class EffectShaderSpec(
    val effectType: RetroEffectType,
    val shaderModeId: Int,
    val contrast: Float,
    val saturation: Float,
    val fadeLift: Float,
    val warmth: Float,
    val chromaShift: Float,
    val grainStrength: Float,
    val trackingStrength: Float,
    val softness: Float,
    val previewTintColor: Color
)

/**
 * Portrait 9:16 crop or wide letterboxed fit parameters derived from input dimensions,
 * rotation metadata, target size, and selected CameraFramingMode.
 */
data class PortraitCropTransform(
    val effectiveSourceWidth: Int,
    val effectiveSourceHeight: Int,
    val uvScaleX: Float,
    val uvScaleY: Float,
    val needsMirroring: Boolean,
    val rotationDegrees: Int,
    val framingMode: CameraFramingMode = CameraFramingMode.PORTRAIT_CROP,
    val viewportX: Int = 0,
    val viewportY: Int = 0,
    val viewportWidth: Int = 0,
    val viewportHeight: Int = 0
)

object EffectShaderConfig {

    const val MODE_ORIGINAL = 0
    const val MODE_VHS_RETRO = 1
    const val MODE_NEON_80S = 2
    const val MODE_VINTAGE_70S = 3
    const val MODE_FILM_90S = 4
    const val MODE_BW_NOIR = 5

    fun getSpec(effect: RetroEffectType): EffectShaderSpec = when (effect) {
        RetroEffectType.NONE -> EffectShaderSpec(
            effectType = RetroEffectType.NONE,
            shaderModeId = MODE_ORIGINAL,
            contrast = 1.0f,
            saturation = 1.0f,
            fadeLift = 0.0f,
            warmth = 0.0f,
            chromaShift = 0.0f,
            grainStrength = 0.0f,
            trackingStrength = 0.0f,
            softness = 0.0f,
            previewTintColor = Color.Transparent
        )
        RetroEffectType.VHS_RETRO -> EffectShaderSpec(
            effectType = RetroEffectType.VHS_RETRO,
            shaderModeId = MODE_VHS_RETRO,
            contrast = 1.08f,
            saturation = 1.05f,
            fadeLift = 0.03f,
            warmth = 0.05f,
            chromaShift = 0.0045f,
            grainStrength = 0.06f,
            trackingStrength = 0.003f,
            softness = 0.25f,
            previewTintColor = Color(0x1E9C27B0)
        )
        RetroEffectType.NEON_80S -> EffectShaderSpec(
            effectType = RetroEffectType.NEON_80S,
            shaderModeId = MODE_NEON_80S,
            contrast = 1.18f,
            saturation = 1.28f,
            fadeLift = 0.0f,
            warmth = -0.06f,
            chromaShift = 0.002f,
            grainStrength = 0.0f,
            trackingStrength = 0.0f,
            softness = 0.0f,
            previewTintColor = Color(0x1C00F0FF)
        )
        RetroEffectType.VINTAGE_70S -> EffectShaderSpec(
            effectType = RetroEffectType.VINTAGE_70S,
            shaderModeId = MODE_VINTAGE_70S,
            contrast = 0.90f,
            saturation = 0.85f,
            fadeLift = 0.06f,
            warmth = 0.22f,
            chromaShift = 0.0f,
            grainStrength = 0.045f,
            trackingStrength = 0.0f,
            softness = 0.12f,
            previewTintColor = Color(0x22FF9800)
        )
        RetroEffectType.FILM_90S -> EffectShaderSpec(
            effectType = RetroEffectType.FILM_90S,
            shaderModeId = MODE_FILM_90S,
            contrast = 1.05f,
            saturation = 0.94f,
            fadeLift = 0.02f,
            warmth = 0.04f,
            chromaShift = 0.0015f,
            grainStrength = 0.055f,
            trackingStrength = 0.0f,
            softness = 0.20f,
            previewTintColor = Color(0x1A2E7D32)
        )
        RetroEffectType.BW_NOIR -> EffectShaderSpec(
            effectType = RetroEffectType.BW_NOIR,
            shaderModeId = MODE_BW_NOIR,
            contrast = 1.24f,
            saturation = 0.0f,
            fadeLift = 0.01f,
            warmth = 0.0f,
            chromaShift = 0.0f,
            grainStrength = 0.03f,
            trackingStrength = 0.0f,
            softness = 0.0f,
            previewTintColor = Color(0x28121212)
        )
    }

    /**
     * Computes framing parameters for either:
     * - [CameraFramingMode.PORTRAIT_CROP]: center-crop UV scale factors to fill portrait 9:16 output.
     * - [CameraFramingMode.WIDE_FIT]: uncropped UV (1.0, 1.0) with centered aspect-preserving GL viewport
     *   inside the portrait output canvas (black letterboxing above/below landscape sources).
     */
    fun computePortraitCropTransform(
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        targetWidth: Int,
        targetHeight: Int,
        isMirrored: Boolean,
        framingMode: CameraFramingMode = CameraFramingMode.PORTRAIT_CROP
    ): PortraitCropTransform {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        val swapDimensions = normalizedRotation == 90 || normalizedRotation == 270
        val effectiveW = if (swapDimensions) sourceHeight else sourceWidth
        val effectiveH = if (swapDimensions) sourceWidth else sourceHeight

        val safeTargetW = targetWidth.coerceAtLeast(1)
        val safeTargetH = targetHeight.coerceAtLeast(1)

        if (effectiveW <= 0 || effectiveH <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return PortraitCropTransform(
                effectiveSourceWidth = effectiveW.coerceAtLeast(1),
                effectiveSourceHeight = effectiveH.coerceAtLeast(1),
                uvScaleX = 1f,
                uvScaleY = 1f,
                needsMirroring = isMirrored,
                rotationDegrees = normalizedRotation,
                framingMode = framingMode,
                viewportX = 0,
                viewportY = 0,
                viewportWidth = safeTargetW,
                viewportHeight = safeTargetH
            )
        }

        val sourceAspect = effectiveW.toFloat() / effectiveH.toFloat()
        val targetAspect = safeTargetW.toFloat() / safeTargetH.toFloat()

        if (framingMode == CameraFramingMode.WIDE_FIT) {
            val fittedW: Int
            val fittedH: Int
            if (sourceAspect > targetAspect) {
                fittedW = safeTargetW
                fittedH = (safeTargetW.toFloat() / sourceAspect).roundToInt().coerceIn(1, safeTargetH)
            } else {
                fittedH = safeTargetH
                fittedW = (safeTargetH.toFloat() * sourceAspect).roundToInt().coerceIn(1, safeTargetW)
            }
            val vpX = ((safeTargetW - fittedW) / 2).coerceAtLeast(0)
            val vpY = ((safeTargetH - fittedH) / 2).coerceAtLeast(0)

            return PortraitCropTransform(
                effectiveSourceWidth = effectiveW,
                effectiveSourceHeight = effectiveH,
                uvScaleX = 1f,
                uvScaleY = 1f,
                needsMirroring = isMirrored,
                rotationDegrees = normalizedRotation,
                framingMode = framingMode,
                viewportX = vpX,
                viewportY = vpY,
                viewportWidth = fittedW,
                viewportHeight = fittedH
            )
        }

        // PORTRAIT_CROP: Scale UV span <= 1.0 around 0.5 center so we crop cleanly instead of stretching
        val uvScaleX: Float
        val uvScaleY: Float
        if (sourceAspect > targetAspect) {
            // Source is wider than 9:16 portrait (e.g. 16:9 or 4:3 webcam): crop left & right evenly
            uvScaleX = (targetAspect / sourceAspect).coerceIn(0.05f, 1.0f)
            uvScaleY = 1.0f
        } else {
            // Source is narrower/taller than target: crop top & bottom evenly
            uvScaleX = 1.0f
            uvScaleY = (sourceAspect / targetAspect).coerceIn(0.05f, 1.0f)
        }

        return PortraitCropTransform(
            effectiveSourceWidth = effectiveW,
            effectiveSourceHeight = effectiveH,
            uvScaleX = uvScaleX,
            uvScaleY = uvScaleY,
            needsMirroring = isMirrored,
            rotationDegrees = normalizedRotation,
            framingMode = framingMode,
            viewportX = 0,
            viewportY = 0,
            viewportWidth = safeTargetW,
            viewportHeight = safeTargetH
        )
    }

    /**
     * Determines whether an input video already matches 9:16 portrait geometry
     * (within 3% aspect tolerance) so zero-transcode fast muxing is valid when effect == NONE.
     */
    fun isAlreadyPortrait9x16(
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int
    ): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0) return false
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        val effW = if (normalizedRotation == 90 || normalizedRotation == 270) sourceHeight else sourceWidth
        val effH = if (normalizedRotation == 90 || normalizedRotation == 270) sourceWidth else sourceHeight
        if (effH <= effW) return false
        val actualAspect = effW.toFloat() / effH.toFloat()
        val targetAspect = 9f / 16f
        return abs(actualAspect - targetAspect) <= 0.03f
    }
}
