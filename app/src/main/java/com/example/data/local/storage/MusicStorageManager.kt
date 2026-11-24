package com.example.data.local.storage

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

sealed class StagedAudioResult {
    data class Success(
        val songId: String,
        val stagedFile: File,
        val fileSizeBytes: Long,
        val sha256Hash: String,
        val extension: String
    ) : StagedAudioResult()

    data class Error(val message: String) : StagedAudioResult()
}

class MusicStorageManager(private val context: Context) {

    private val baseDir = File(context.filesDir, "retrotok/music")
    val audioDir = File(baseDir, "audio")
    val artworkDir = File(baseDir, "artwork")
    val tempDir = File(baseDir, "temp")

    init {
        audioDir.mkdirs()
        artworkDir.mkdirs()
        tempDir.mkdirs()
    }

    /**
     * Copies audio from SAF Uri into temporary staging file and computes SHA-256 hash.
     */
    suspend fun stageAudioFromUri(uri: Uri, originalFilename: String): StagedAudioResult = withContext(Dispatchers.IO) {
        val songId = UUID.randomUUID().toString()
        val extension = originalFilename.substringAfterLast('.', "mp3")
            .filter { it.isLetterOrDigit() }
            .lowercase()
            .ifBlank { "mp3" }
        val tempFile = File(tempDir, "stage_${songId}.$extension")

        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext StagedAudioResult.Error("Could not open stream for $originalFilename")

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(16 * 1024)
            var bytesRead: Int
            var totalBytes = 0L

            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                    }
                    output.flush()
                }
            }

            if (totalBytes < 1024) {
                tempFile.delete()
                return@withContext StagedAudioResult.Error("File is empty or too small (< 1KB)")
            }

            val hashBytes = digest.digest()
            val hashString = hashBytes.joinToString("") { "%02x".format(it) }

            StagedAudioResult.Success(
                songId = songId,
                stagedFile = tempFile,
                fileSizeBytes = totalBytes,
                sha256Hash = hashString,
                extension = extension
            )
        } catch (e: Exception) {
            tempFile.delete()
            StagedAudioResult.Error("Import failed: ${e.localizedMessage ?: "I/O error"}")
        }
    }

    /**
     * Promotes a staged file to permanent storage atomically.
     * Returns the relative path.
     */
    suspend fun commitAudioFile(songId: String, stagedFile: File, extension: String): String = withContext(Dispatchers.IO) {
        val targetFile = File(audioDir, "${songId}.$extension")
        if (stagedFile.renameTo(targetFile)) {
            "retrotok/music/audio/${targetFile.name}"
        } else {
            // Fallback copy if rename fails
            stagedFile.copyTo(targetFile, overwrite = true)
            stagedFile.delete()
            "retrotok/music/audio/${targetFile.name}"
        }
    }

    /**
     * Saves extracted album artwork bytes to application-private storage.
     * Returns the relative path.
     */
    suspend fun saveArtwork(songId: String, artworkBytes: ByteArray?): String? = withContext(Dispatchers.IO) {
        if (artworkBytes == null || artworkBytes.isEmpty()) return@withContext null
        try {
            val artworkFile = File(artworkDir, "${songId}.webp")
            FileOutputStream(artworkFile).use { it.write(artworkBytes) }
            "retrotok/music/artwork/${artworkFile.name}"
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolves a relative path to absolute File.
     */
    fun getAbsoluteFile(relativePath: String): File {
        return File(context.filesDir, relativePath)
    }

    /**
     * Deletes a song file and its associated artwork safely.
     */
    suspend fun deleteSongFiles(audioRelativePath: String, artworkRelativePath: String?): Boolean = withContext(Dispatchers.IO) {
        var success = true
        val audioFile = getAbsoluteFile(audioRelativePath)
        if (audioFile.exists()) {
            success = audioFile.delete()
        }
        if (artworkRelativePath != null) {
            val artFile = getAbsoluteFile(artworkRelativePath)
            if (artFile.exists()) {
                artFile.delete()
            }
        }
        success
    }

    /**
     * Cleans dangling temporary files.
     */
    suspend fun clearTempStaging() = withContext(Dispatchers.IO) {
        tempDir.listFiles()?.forEach { it.delete() }
    }
}
