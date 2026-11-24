package com.example.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Generates compact portrait JPEG thumbnails locally using MediaMetadataRetriever
 * without loading entire videos into memory or leaking Bitmap allocations on API 23.
 */
class VideoThumbnailGenerator(private val context: Context) {

    companion object {
        private const val TAG = "VideoThumbGen"
        private const val MAX_THUMB_EDGE_PX = 480
        private const val JPEG_QUALITY = 84
    }

    /**
     * Extracts a representative keyframe and writes a JPEG thumbnail under
     * `files/retrotok/videos/thumbnails/thumb_<videoId>.jpg`.
     * Returns the application-relative path on success, or null if extraction fails.
     */
    suspend fun generateThumbnail(
        videoFile: File,
        videoId: String,
        targetTimeMs: Long = 500L
    ): String? = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || videoFile.length() == 0L) {
            return@withContext null
        }

        val thumbnailsDir = File(context.filesDir, "retrotok/videos/thumbnails").apply { mkdirs() }
        val thumbFile = File(thumbnailsDir, "thumb_${videoId}.jpg")

        val retriever = MediaMetadataRetriever()
        var extractedBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null

        try {
            retriever.setDataSource(videoFile.absolutePath)
            val timeUs = (targetTimeMs.coerceAtLeast(0L)) * 1000L

            extractedBitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

            val source = extractedBitmap ?: return@withContext null

            val width = source.width
            val height = source.height
            val maxDim = maxOf(width, height)

            val bitmapToCompress = if (maxDim > MAX_THUMB_EDGE_PX && width > 0 && height > 0) {
                val scale = MAX_THUMB_EDGE_PX.toFloat() / maxDim.toFloat()
                val dstW = (width * scale).toInt().coerceAtLeast(1)
                val dstH = (height * scale).toInt().coerceAtLeast(1)
                scaledBitmap = Bitmap.createScaledBitmap(source, dstW, dstH, true)
                scaledBitmap
            } else {
                source
            }

            FileOutputStream(thumbFile).use { fos ->
                bitmapToCompress.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
                fos.flush()
            }

            if (thumbFile.exists() && thumbFile.length() > 0L) {
                "retrotok/videos/thumbnails/${thumbFile.name}"
            } else {
                thumbFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Thumbnail generation skipped for ${videoFile.name}: ${e.message}")
            if (thumbFile.exists()) thumbFile.delete()
            null
        } finally {
            try {
                if (scaledBitmap != null && scaledBitmap != extractedBitmap && !scaledBitmap.isRecycled) {
                    scaledBitmap.recycle()
                }
                if (extractedBitmap != null && !extractedBitmap.isRecycled) {
                    extractedBitmap.recycle()
                }
            } catch (_: Exception) {}
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }
}
