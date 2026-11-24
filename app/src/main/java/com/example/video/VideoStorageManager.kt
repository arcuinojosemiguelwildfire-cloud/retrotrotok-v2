package com.example.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import com.example.model.RetroEffectType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import java.util.UUID

data class TempRecordingMetadata(
    val recordingId: String,
    val videoFileName: String,
    val songId: String,
    val songTitle: String,
    val songArtist: String,
    val effectName: String,
    val durationSeconds: Int,
    val createdAtMs: Long,
    val stage: String,
    val framingModeName: String = CameraFramingMode.WIDE_FIT.name
)

data class RecoverableRecording(
    val file: File,
    val metadata: TempRecordingMetadata?
) {
    val displayTitle: String
        get() = metadata?.songTitle?.ifBlank { "Unfinished Dance Take" } ?: "Unfinished Dance Take"

    val effectType: RetroEffectType
        get() = metadata?.effectName?.let { name ->
            RetroEffectType.values().firstOrNull { it.name == name }
        } ?: RetroEffectType.VHS_RETRO

    val framingMode: CameraFramingMode
        get() = metadata?.framingModeName?.let { name ->
            CameraFramingMode.values().firstOrNull { it.name == name }
        } ?: CameraFramingMode.WIDE_FIT
}

data class VideoStorageBreakdown(
    val publishedBytes: Long = 0L,
    val publishedCount: Int = 0,
    val importedBytes: Long = 0L,
    val importedCount: Int = 0,
    val tempStagingBytes: Long = 0L,
    val recoverableBytes: Long = 0L,
    val recoverableCount: Int = 0,
    val thumbnailBytes: Long = 0L,
    val thumbnailCount: Int = 0,
    val musicBytes: Long = 0L,
    val availableDeviceBytes: Long = 0L,
    val totalDeviceBytes: Long = 0L
) {
    val totalAppBytes: Long
        get() = publishedBytes + importedBytes + tempStagingBytes + recoverableBytes + thumbnailBytes + musicBytes

    val isLowStorageWarning: Boolean
        get() = availableDeviceBytes in 1L until (500L * 1024L * 1024L)

    val isRecordingBlocked: Boolean
        get() = availableDeviceBytes in 1L until (100L * 1024L * 1024L)
}

sealed class StagedImportedVideoResult {
    data class Success(
        val importedFile: File,
        val relativePath: String,
        val durationSeconds: Int,
        val width: Int,
        val height: Int,
        val fileSizeBytes: Long,
        val hasAudio: Boolean
    ) : StagedImportedVideoResult()

    data class Error(val message: String) : StagedImportedVideoResult()
}

/**
 * Manages application-private directories and file lifecycles for video recordings,
 * thumbnails, SAF imports, and local storage telemetry.
 *
 * Directory hierarchy:
 * files/
 * └── retrotok/
 *     └── videos/
 *         ├── temp/       (in-progress and unverified recordings + recovery metadata)
 *         ├── imported/   (imported external MP4/H.264/AAC clips)
 *         ├── thumbnails/ (JPEG thumbnails for published and imported videos)
 *         └── published/  (verified guest takes added to event feed)
 */
class VideoStorageManager(private val context: Context) {

    companion object {
        const val MIN_RECORDING_FREE_BYTES = 100L * 1024L * 1024L // 100 MB safety floor
    }

    private val baseVideosDir = File(context.filesDir, "retrotok/videos")
    val tempDir = File(baseVideosDir, "temp")
    val importedDir = File(baseVideosDir, "imported")
    val publishedDir = File(baseVideosDir, "published")
    val thumbnailsDir = File(baseVideosDir, "thumbnails")

    init {
        tempDir.mkdirs()
        importedDir.mkdirs()
        publishedDir.mkdirs()
        thumbnailsDir.mkdirs()
    }

    fun generateRecordingId(): String {
        val uuid = UUID.randomUUID().toString().take(8)
        val timestamp = System.currentTimeMillis()
        return "rec_${timestamp}_$uuid"
    }

    fun createTempRawVideoFile(recordingId: String): File {
        return File(tempDir, "raw_${recordingId}.mp4")
    }

    fun createTempMuxedVideoFile(recordingId: String): File {
        return File(tempDir, "mux_${recordingId}.mp4")
    }

    private fun createMetadataFile(recordingId: String): File {
        return File(tempDir, "meta_${recordingId}.properties")
    }

    suspend fun saveTempRecordingMetadata(metadata: TempRecordingMetadata) = withContext(Dispatchers.IO) {
        val metaFile = createMetadataFile(metadata.recordingId)
        val props = Properties().apply {
            setProperty("recordingId", metadata.recordingId)
            setProperty("videoFileName", metadata.videoFileName)
            setProperty("songId", metadata.songId)
            setProperty("songTitle", metadata.songTitle)
            setProperty("songArtist", metadata.songArtist)
            setProperty("effectName", metadata.effectName)
            setProperty("durationSeconds", metadata.durationSeconds.toString())
            setProperty("createdAtMs", metadata.createdAtMs.toString())
            setProperty("stage", metadata.stage)
            setProperty("framingModeName", metadata.framingModeName)
        }
        FileOutputStream(metaFile).use { fos ->
            props.store(fos, "RetroTok Temp Recording Recovery Metadata")
        }
    }

    fun extractRecordingIdFromFile(file: File): String? {
        val name = file.nameWithoutExtension
        return when {
            name.startsWith("mux_") -> name.removePrefix("mux_")
            name.startsWith("raw_") -> name.removePrefix("raw_")
            else -> null
        }
    }

    /**
     * Atomically moves a verified final video into the published directory.
     * Cleans companion temporary raw/metadata artifacts for the same recordingId.
     */
    suspend fun promoteToPublished(sourceFile: File, videoId: String): String = withContext(Dispatchers.IO) {
        publishedDir.mkdirs()
        val publishedFile = File(publishedDir, "pub_${videoId}.mp4")
        val recordingId = extractRecordingIdFromFile(sourceFile)

        val renamed = sourceFile.renameTo(publishedFile)
        if (!renamed) {
            sourceFile.copyTo(publishedFile, overwrite = true)
            if (publishedFile.exists() && publishedFile.length() == sourceFile.length()) {
                sourceFile.delete()
            }
        }

        if (recordingId != null) {
            createTempRawVideoFile(recordingId).let { if (it.exists()) it.delete() }
            createTempMuxedVideoFile(recordingId).let { if (it.exists()) it.delete() }
            createMetadataFile(recordingId).let { if (it.exists()) it.delete() }
        }

        "retrotok/videos/published/${publishedFile.name}"
    }

    /**
     * Copies an external video selected via Android SAF into application-private imported/
     * storage and validates H.264/AVC video and AAC/audio codec compatibility for API 23+.
     */
    suspend fun importAndValidateVideoFromUri(
        uri: Uri,
        videoId: String
    ): StagedImportedVideoResult = withContext(Dispatchers.IO) {
        importedDir.mkdirs()
        val targetFile = File(importedDir, "imp_${videoId}.mp4")

        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext StagedImportedVideoResult.Error("Unable to open selected video file.")

            val buffer = ByteArray(32 * 1024)
            var totalBytes = 0L
            input.use { stream ->
                FileOutputStream(targetFile).use { out ->
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        totalBytes += read
                    }
                    out.flush()
                }
            }

            if (!targetFile.exists() || totalBytes < 1024L) {
                targetFile.delete()
                return@withContext StagedImportedVideoResult.Error("Imported video is empty or unreadable.")
            }

            // Inspect video and audio tracks via MediaExtractor + MediaMetadataRetriever
            var extractor: MediaExtractor? = null
            var retriever: MediaMetadataRetriever? = null
            try {
                extractor = MediaExtractor().apply { setDataSource(targetFile.absolutePath) }
                var videoMime: String? = null
                var audioMime: String? = null
                var width = 0
                var height = 0
                var durationUs = 0L

                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") && videoMime == null) {
                        videoMime = mime
                        if (fmt.containsKey(MediaFormat.KEY_WIDTH)) width = fmt.getInteger(MediaFormat.KEY_WIDTH)
                        if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                        if (fmt.containsKey(MediaFormat.KEY_DURATION)) durationUs = fmt.getLong(MediaFormat.KEY_DURATION)
                    } else if (mime.startsWith("audio/") && audioMime == null) {
                        audioMime = mime
                    }
                }

                if (videoMime == null) {
                    targetFile.delete()
                    return@withContext StagedImportedVideoResult.Error("File does not contain a valid video track.")
                }

                val isVideoCodecSupported = videoMime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) ||
                    videoMime.equals("video/mp4v-es", ignoreCase = true)
                if (!isVideoCodecSupported) {
                    targetFile.delete()
                    return@withContext StagedImportedVideoResult.Error(
                        "Unsupported video codec ($videoMime). Use MP4 with H.264/AVC video."
                    )
                }

                if (audioMime != null &&
                    !audioMime.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true) &&
                    !audioMime.equals(MediaFormat.MIMETYPE_AUDIO_MPEG, ignoreCase = true)
                ) {
                    targetFile.delete()
                    return@withContext StagedImportedVideoResult.Error(
                        "Unsupported audio codec ($audioMime). Use AAC audio in MP4."
                    )
                }

                retriever = MediaMetadataRetriever().apply { setDataSource(targetFile.absolutePath) }
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                if (durationUs == 0L) {
                    durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L) * 1000L
                }
                if (width == 0) {
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 720
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 1280
                }
                val swap = (rotation % 180) != 0
                val effW = if (swap) height else width
                val effH = if (swap) width else height
                val durationSec = (durationUs / 1_000_000L).toInt().coerceAtLeast(1)

                StagedImportedVideoResult.Success(
                    importedFile = targetFile,
                    relativePath = "retrotok/videos/imported/${targetFile.name}",
                    durationSeconds = durationSec,
                    width = effW.coerceAtLeast(1),
                    height = effH.coerceAtLeast(1),
                    fileSizeBytes = totalBytes,
                    hasAudio = audioMime != null
                )
            } finally {
                try { extractor?.release() } catch (_: Exception) {}
                try { retriever?.release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            if (targetFile.exists()) targetFile.delete()
            StagedImportedVideoResult.Error("Video import failed: ${e.localizedMessage ?: "Unsupported file"}")
        }
    }

    fun getAbsoluteFile(relativePath: String): File {
        return if (relativePath.startsWith("/")) File(relativePath) else File(context.filesDir, relativePath)
    }

    /**
     * Safely deletes a published or imported video and its thumbnail ONLY.
     * Never deletes original music catalog audio files.
     */
    suspend fun deletePublishedAssets(
        localVideoPath: String?,
        thumbnailPath: String?
    ): Boolean = withContext(Dispatchers.IO) {
        var deletedVideo = false
        if (!localVideoPath.isNullOrBlank()) {
            val videoFile = getAbsoluteFile(localVideoPath)
            val inVideoStorage = videoFile.absolutePath.startsWith(publishedDir.absolutePath) ||
                videoFile.absolutePath.startsWith(importedDir.absolutePath)
            if (videoFile.exists() && inVideoStorage) {
                deletedVideo = videoFile.delete()
            }
        }
        if (!thumbnailPath.isNullOrBlank()) {
            val thumbFile = getAbsoluteFile(thumbnailPath)
            if (thumbFile.exists() && thumbFile.absolutePath.startsWith(thumbnailsDir.absolutePath)) {
                thumbFile.delete()
            }
        }
        deletedVideo
    }

    /**
     * Safely deletes an unpublished temporary file and companion metadata/raw artifacts.
     */
    suspend fun deleteTempFile(file: File): Boolean = withContext(Dispatchers.IO) {
        if (file.exists() && file.absolutePath.startsWith(tempDir.absolutePath)) {
            val recordingId = extractRecordingIdFromFile(file)
            val deleted = file.delete()
            if (recordingId != null) {
                createTempRawVideoFile(recordingId).let { if (it.exists() && it != file) it.delete() }
                createTempMuxedVideoFile(recordingId).let { if (it.exists() && it != file) it.delete() }
                createMetadataFile(recordingId).let { if (it.exists()) it.delete() }
            }
            deleted
        } else {
            false
        }
    }

    /**
     * Cleans up zero-byte temporary video files and orphaned recovery metadata after a failed recording.
     * Preserves any non-empty (> 0 bytes) temporary video file and its metadata for investigation.
     * Strictly constrained to tempDir; never touches published/, imported/, thumbnails/, or unrelated files.
     */
    suspend fun cleanupFailedTempRecording(rawFile: File?, recordingId: String?): Boolean = withContext(Dispatchers.IO) {
        val inTempDir = rawFile == null || rawFile.absolutePath.startsWith(tempDir.absolutePath)
        if (!inTempDir) {
            return@withContext false
        }

        if (rawFile != null && rawFile.exists() && rawFile.length() > 0L) {
            // Preserve recoverable non-empty file and its metadata
            return@withContext false
        }

        var cleaned = false
        if (rawFile != null && rawFile.exists() && rawFile.length() == 0L) {
            if (rawFile.delete()) cleaned = true
        }

        val resolvedId = recordingId ?: rawFile?.let { extractRecordingIdFromFile(it) }
        if (resolvedId != null) {
            val expectedRaw = createTempRawVideoFile(resolvedId)
            if (expectedRaw.exists() && expectedRaw.length() == 0L && expectedRaw.absolutePath.startsWith(tempDir.absolutePath)) {
                if (expectedRaw.delete()) cleaned = true
            }
            val expectedMux = createTempMuxedVideoFile(resolvedId)
            if (expectedMux.exists() && expectedMux.length() == 0L && expectedMux.absolutePath.startsWith(tempDir.absolutePath)) {
                if (expectedMux.delete()) cleaned = true
            }
            val metaFile = createMetadataFile(resolvedId)
            if (metaFile.exists() && metaFile.absolutePath.startsWith(tempDir.absolutePath)) {
                if (metaFile.delete()) cleaned = true
            }
        }
        cleaned
    }

    suspend fun discardRecoverableSession(session: RecoverableRecording): Boolean = withContext(Dispatchers.IO) {
        val deletedMain = deleteTempFile(session.file)
        session.metadata?.recordingId?.let { id ->
            createMetadataFile(id).let { if (it.exists()) it.delete() }
            createTempRawVideoFile(id).let { if (it.exists()) it.delete() }
            createTempMuxedVideoFile(id).let { if (it.exists()) it.delete() }
        }
        deletedMain
    }

    suspend fun cleanDanglingTempFiles(maxAgeMs: Long = 3600_000L): Int = withContext(Dispatchers.IO) {
        var count = 0
        val now = System.currentTimeMillis()
        tempDir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > maxAgeMs) {
                if (file.delete()) count++
            }
        }
        count
    }

    /**
     * Explicit Admin cleanup for staging/temp artifacts. Never touches published/ or imported/.
     */
    fun clearTempStorage(): Int {
        var count = 0
        tempDir.listFiles()?.forEach { file ->
            if (file.delete()) count++
        }
        return count
    }

    /**
     * Clears only transient composition staging files (`comp_vid_*`, `comp_aud_*`) while
     * preserving recoverable guest takes and all published/imported content.
     */
    suspend fun clearTransientStagingOnly(): Int = withContext(Dispatchers.IO) {
        var count = 0
        tempDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("comp_vid_") || file.name.startsWith("comp_aud_")) {
                if (file.delete()) count++
            }
        }
        count
    }

    suspend fun findRecoverableTempRecordings(): List<File> = withContext(Dispatchers.IO) {
        tempDir.listFiles()?.filter { file ->
            file.isFile &&
                file.extension.equals("mp4", ignoreCase = true) &&
                !file.name.startsWith("comp_vid_") &&
                !file.name.startsWith("comp_aud_") &&
                file.length() > 1024L
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    suspend fun findRecoverableTempSessions(): List<RecoverableRecording> = withContext(Dispatchers.IO) {
        val mp4Files = findRecoverableTempRecordings()
        if (mp4Files.isEmpty()) return@withContext emptyList()

        val metadataMap = mutableMapOf<String, TempRecordingMetadata>()
        tempDir.listFiles()?.filter { it.isFile && it.name.startsWith("meta_") && it.extension == "properties" }
            ?.forEach { metaFile ->
                readMetadataFile(metaFile)?.let { meta ->
                    metadataMap[meta.recordingId] = meta
                }
            }

        val seenRecordingIds = mutableSetOf<String>()
        val sessions = mutableListOf<RecoverableRecording>()

        val sortedFiles = mp4Files.sortedWith(
            compareByDescending<File> { it.name.startsWith("mux_") }
                .thenByDescending { it.lastModified() }
        )

        for (file in sortedFiles) {
            val recId = extractRecordingIdFromFile(file)
            if (recId != null) {
                if (!seenRecordingIds.add(recId)) continue
            }
            val meta = recId?.let { metadataMap[it] }
            sessions.add(RecoverableRecording(file = file, metadata = meta))
        }
        sessions
    }

    fun listPublishedAndImportedMp4Files(): List<File> {
        val pub = publishedDir.listFiles()?.filter { it.isFile && it.extension.equals("mp4", true) } ?: emptyList()
        val imp = importedDir.listFiles()?.filter { it.isFile && it.extension.equals("mp4", true) } ?: emptyList()
        return pub + imp
    }

    suspend fun purgeOrphanedThumbnails(referencedThumbnailPaths: Set<String>): Int = withContext(Dispatchers.IO) {
        var purged = 0
        thumbnailsDir.listFiles()?.forEach { file ->
            val relPath = "retrotok/videos/thumbnails/${file.name}"
            if (file.isFile && relPath !in referencedThumbnailPaths && file.absolutePath !in referencedThumbnailPaths) {
                if (file.delete()) purged++
            }
        }
        purged
    }

    fun getAvailableDeviceBytes(): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBytes
        } catch (_: Exception) {
            0L
        }
    }

    fun getTotalDeviceBytes(): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.totalBytes
        } catch (_: Exception) {
            0L
        }
    }

    fun hasSufficientSpaceForRecording(): Boolean {
        val available = getAvailableDeviceBytes()
        // If StatFs reports 0 in virtualized environments, don't block; block only when strictly below floor
        return available == 0L || available >= MIN_RECORDING_FREE_BYTES
    }

    suspend fun computeStorageBreakdown(musicBytes: Long): VideoStorageBreakdown = withContext(Dispatchers.IO) {
        val pubFiles = publishedDir.listFiles()?.filter { it.isFile } ?: emptyList()
        val impFiles = importedDir.listFiles()?.filter { it.isFile } ?: emptyList()
        val thumbFiles = thumbnailsDir.listFiles()?.filter { it.isFile } ?: emptyList()
        val tempFiles = tempDir.listFiles()?.filter { it.isFile } ?: emptyList()

        val stagingFiles = tempFiles.filter {
            it.name.startsWith("comp_vid_") || it.name.startsWith("comp_aud_")
        }
        val recoverableFiles = tempFiles.filterNot {
            it.name.startsWith("comp_vid_") || it.name.startsWith("comp_aud_")
        }
        val recoverableMp4Count = recoverableFiles.count { it.extension.equals("mp4", true) && it.length() > 1024L }

        VideoStorageBreakdown(
            publishedBytes = pubFiles.sumOf { it.length() },
            publishedCount = pubFiles.size,
            importedBytes = impFiles.sumOf { it.length() },
            importedCount = impFiles.size,
            tempStagingBytes = stagingFiles.sumOf { it.length() },
            recoverableBytes = recoverableFiles.sumOf { it.length() },
            recoverableCount = recoverableMp4Count,
            thumbnailBytes = thumbFiles.sumOf { it.length() },
            thumbnailCount = thumbFiles.size,
            musicBytes = musicBytes,
            availableDeviceBytes = getAvailableDeviceBytes(),
            totalDeviceBytes = getTotalDeviceBytes()
        )
    }

    private fun readMetadataFile(file: File): TempRecordingMetadata? {
        return try {
            val props = Properties()
            FileInputStream(file).use { fis -> props.load(fis) }
            val recId = props.getProperty("recordingId") ?: return null
            TempRecordingMetadata(
                recordingId = recId,
                videoFileName = props.getProperty("videoFileName", ""),
                songId = props.getProperty("songId", ""),
                songTitle = props.getProperty("songTitle", ""),
                songArtist = props.getProperty("songArtist", ""),
                effectName = props.getProperty("effectName", RetroEffectType.VHS_RETRO.name),
                durationSeconds = props.getProperty("durationSeconds", "30").toIntOrNull() ?: 30,
                createdAtMs = props.getProperty("createdAtMs", "0").toLongOrNull() ?: 0L,
                stage = props.getProperty("stage", "RAW"),
                framingModeName = props.getProperty("framingModeName", CameraFramingMode.WIDE_FIT.name)
            )
        } catch (_: Exception) {
            null
        }
    }
}
