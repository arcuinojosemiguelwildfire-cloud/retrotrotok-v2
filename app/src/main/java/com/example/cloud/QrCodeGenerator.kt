package com.example.cloud

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URI

sealed class QrCodeGenerationResult {
    data class Success(
        val bitmap: Bitmap,
        val encodedUrl: String
    ) : QrCodeGenerationResult()

    data class InvalidUrl(val reason: String) : QrCodeGenerationResult()
    data class EncodingError(val reason: String) : QrCodeGenerationResult()
}

/**
 * Pure-Java ZXing QR encoder compatible with Android 6.0.1 (API 23+).
 * Generates high-contrast black-on-white QR Bitmaps with an explicit quiet zone.
 */
object QrCodeGenerator {
    const val DEFAULT_QR_SIZE_PX = 512
    const val DEFAULT_QUIET_ZONE_MODULES = 4

    fun isValidViewingUrl(rawUrl: String?): Boolean {
        val candidate = rawUrl?.trim().orEmpty()
        if (candidate.isEmpty() || candidate.any { it.isWhitespace() }) {
            return false
        }
        return try {
            val uri = URI(candidate)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            (scheme == "https" || scheme == "http") && !host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    fun generate(
        viewingUrl: String?,
        sizePx: Int = DEFAULT_QR_SIZE_PX,
        quietZoneModules: Int = DEFAULT_QUIET_ZONE_MODULES
    ): QrCodeGenerationResult {
        val normalized = viewingUrl?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return QrCodeGenerationResult.InvalidUrl("Viewing URL is blank.")
        }
        if (!isValidViewingUrl(normalized)) {
            return QrCodeGenerationResult.InvalidUrl("Viewing URL is malformed or uses an unsupported scheme.")
        }

        val safeSize = sizePx.coerceIn(128, 2048)
        val safeMargin = quietZoneModules.coerceIn(1, 8)

        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to safeMargin
            )
            val matrix = QRCodeWriter().encode(
                normalized,
                BarcodeFormat.QR_CODE,
                safeSize,
                safeSize,
                hints
            )
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val rowOffset = y * width
                for (x in 0 until width) {
                    pixels[rowOffset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            QrCodeGenerationResult.Success(bitmap = bitmap, encodedUrl = normalized)
        } catch (t: Throwable) {
            QrCodeGenerationResult.EncodingError(t.message ?: "Unable to encode QR code.")
        }
    }

    fun generateQrBitmap(
        viewingUrl: String?,
        sizePx: Int = DEFAULT_QR_SIZE_PX
    ): Bitmap? {
        return when (val result = generate(viewingUrl = viewingUrl, sizePx = sizePx)) {
            is QrCodeGenerationResult.Success -> result.bitmap
            else -> null
        }
    }
}
