package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.local.dao.SongDao
import com.example.data.local.entity.SongEntity
import com.example.data.local.metadata.AudioMetadataExtractor
import com.example.data.local.storage.MusicStorageManager
import com.example.data.local.storage.StagedAudioResult
import com.example.model.RetroDecade
import com.example.model.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class MusicCatalogRepositoryImpl(
    private val context: Context,
    private val songDao: SongDao,
    private val storageManager: MusicStorageManager,
    private val metadataExtractor: AudioMetadataExtractor
) : MusicCatalogRepository {

    override fun getAllSongs(): Flow<List<SongItem>> {
        return songDao.getAllSongs().map { list -> list.map { it.toSongItem() } }
    }

    override fun getApprovedSongs(): Flow<List<SongItem>> {
        return songDao.getApprovedSongs().map { list -> list.map { it.toSongItem() } }
    }

    override fun getSongsByDecade(decade: RetroDecade): Flow<List<SongItem>> {
        val decadeKey = decade.toDecadeKey()
        return if (decade == RetroDecade.ALL) {
            getAllSongs()
        } else {
            songDao.getSongsByDecade(decadeKey).map { list -> list.map { it.toSongItem() } }
        }
    }

    override fun getApprovedSongsByDecade(decade: RetroDecade): Flow<List<SongItem>> {
        val decadeKey = decade.toDecadeKey()
        return if (decade == RetroDecade.ALL) {
            getApprovedSongs()
        } else {
            songDao.getApprovedSongsByDecade(decadeKey).map { list -> list.map { it.toSongItem() } }
        }
    }

    override fun searchSongs(query: String): Flow<List<SongItem>> {
        return songDao.searchSongs(query).map { list -> list.map { it.toSongItem() } }
    }

    override fun searchApprovedSongs(query: String): Flow<List<SongItem>> {
        return songDao.searchApprovedSongs(query).map { list -> list.map { it.toSongItem() } }
    }

    override suspend fun getSongById(songId: String): SongItem? = withContext(Dispatchers.IO) {
        songDao.getSongById(songId)?.toSongItem()
    }

    override suspend fun importAudioFiles(
        uris: List<Uri>,
        onProgress: (current: Int, total: Int, currentName: String) -> Unit
    ): ImportSummary = withContext(Dispatchers.IO) {
        val results = mutableListOf<ImportResultItem>()
        var successCount = 0
        var duplicateCount = 0
        var failureCount = 0

        uris.forEachIndexed { index, uri ->
            val originalName = queryDisplayName(uri) ?: "Audio_Track_${index + 1}.mp3"
            onProgress(index + 1, uris.size, originalName)

            when (val stageResult = storageManager.stageAudioFromUri(uri, originalName)) {
                is StagedAudioResult.Error -> {
                    failureCount++
                    results.add(
                        ImportResultItem(
                            filename = originalName,
                            isSuccess = false,
                            errorMessage = stageResult.message
                        )
                    )
                }

                is StagedAudioResult.Success -> {
                    // Check duplicate SHA-256 hash
                    val existing = songDao.getSongByHash(stageResult.sha256Hash)
                    if (existing != null) {
                        stageResult.stagedFile.delete() // Safe purge
                        duplicateCount++
                        results.add(
                            ImportResultItem(
                                filename = originalName,
                                isSuccess = false,
                                isDuplicate = true,
                                errorMessage = "Duplicate track: Already in catalog as \"${existing.title}\"",
                                importedSong = existing.toSongItem()
                            )
                        )
                    } else {
                        // Extract metadata
                        val extracted = metadataExtractor.extract(stageResult.stagedFile, originalName)

                        // Save artwork if embedded
                        val artworkRelPath = storageManager.saveArtwork(
                            stageResult.songId,
                            extracted.artworkBytes
                        )

                        // Commit audio file atomically
                        val audioRelPath = storageManager.commitAudioFile(
                            stageResult.songId,
                            stageResult.stagedFile,
                            stageResult.extension
                        )

                        val entity = SongEntity(
                            id = stageResult.songId,
                            title = extracted.title,
                            artist = extracted.artist,
                            album = extracted.album,
                            releaseYear = extracted.releaseYear,
                            decade = extracted.decade,
                            genre = extracted.genre,
                            durationMs = extracted.durationMs,
                            audioFileName = originalName,
                            audioRelativePath = audioRelPath,
                            artworkRelativePath = artworkRelPath,
                            fileSizeBytes = stageResult.fileSizeBytes,
                            sha256Hash = stageResult.sha256Hash,
                            licenseApprovalStatus = "UNREVIEWED"
                        )

                        try {
                            songDao.insertSong(entity)
                            val domainSong = entity.toSongItem()
                            successCount++
                            results.add(
                                ImportResultItem(
                                    filename = originalName,
                                    isSuccess = true,
                                    importedSong = domainSong
                                )
                            )
                        } catch (e: android.database.sqlite.SQLiteConstraintException) {
                            // Uniqueness constraint collision (e.g. concurrent duplicate import)
                            storageManager.deleteSongFiles(audioRelPath, artworkRelPath)
                            duplicateCount++
                            val currentExisting = songDao.getSongByHash(stageResult.sha256Hash)
                            results.add(
                                ImportResultItem(
                                    filename = originalName,
                                    isSuccess = false,
                                    isDuplicate = true,
                                    errorMessage = "Duplicate track: Already in catalog as \"${currentExisting?.title ?: originalName}\"",
                                    importedSong = currentExisting?.toSongItem()
                                )
                            )
                        } catch (e: Exception) {
                            // Clean up committed files so no orphaned audio/artwork remains
                            storageManager.deleteSongFiles(audioRelPath, artworkRelPath)
                            failureCount++
                            results.add(
                                ImportResultItem(
                                    filename = originalName,
                                    isSuccess = false,
                                    errorMessage = "Failed to store track: ${e.message}"
                                )
                            )
                        }
                    }
                }
            }
        }

        ImportSummary(
            totalProcessed = uris.size,
            successfulCount = successCount,
            duplicateCount = duplicateCount,
            failedCount = failureCount,
            results = results
        )
    }

    override suspend fun updateSongMetadata(
        songId: String,
        title: String,
        artist: String,
        album: String,
        year: Int?,
        genre: String,
        decade: RetroDecade
    ): Boolean = withContext(Dispatchers.IO) {
        val existing = songDao.getSongById(songId) ?: return@withContext false
        val updated = existing.copy(
            title = title.trim(),
            artist = artist.trim(),
            album = album.trim(),
            releaseYear = year,
            genre = genre.trim(),
            decade = decade.toDecadeKey(),
            updatedAt = System.currentTimeMillis()
        )
        songDao.updateSong(updated)
        true
    }

    override suspend fun updateLicenseStatus(songId: String, newStatus: String): Boolean = withContext(Dispatchers.IO) {
        val existing = songDao.getSongById(songId) ?: return@withContext false
        val updated = existing.copy(
            licenseApprovalStatus = newStatus,
            updatedAt = System.currentTimeMillis()
        )
        songDao.updateSong(updated)
        true
    }

    override suspend fun deleteSong(songId: String, isReferencedByVideos: Boolean): Boolean = withContext(Dispatchers.IO) {
        val existing = songDao.getSongById(songId) ?: return@withContext false
        if (isReferencedByVideos) {
            // Safe retirement: Preserve physical audio file so existing published videos retain their soundtrack,
            // while retiring the track from active music picking.
            songDao.softDeleteSong(songId) > 0
        } else {
            // Unreferenced: Safe to delete physical files and purge SQLite record
            storageManager.deleteSongFiles(existing.audioRelativePath, existing.artworkRelativePath)
            songDao.deleteSongById(songId) > 0
        }
    }

    override fun getSongMetrics(): Flow<MusicStorageMetrics> {
        return songDao.getAllSongs().map { songs ->
            val totalBytes = songs.sumOf { it.fileSizeBytes }
            val approvedCount = songs.count { it.licenseApprovalStatus == "APPROVED_FOR_EVENT_USE" }

            val decadeMap = mutableMapOf<RetroDecade, Int>()
            RetroDecade.values().forEach { decadeMap[it] = 0 }
            songs.forEach { song ->
                val decade = song.decade.toRetroDecade()
                decadeMap[decade] = (decadeMap[decade] ?: 0) + 1
            }

            val genreMap = mutableMapOf<String, Int>()
            songs.forEach { song ->
                val g = song.genre.ifBlank { "Retro" }
                genreMap[g] = (genreMap[g] ?: 0) + 1
            }

            MusicStorageMetrics(
                totalSongs = songs.size,
                approvedSongs = approvedCount,
                totalFileSizeBytes = totalBytes,
                songsByDecade = decadeMap,
                songsByGenre = genreMap
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (_: Exception) {}
        return name
    }
}

// Extension Mappers
fun SongEntity.toSongItem(): SongItem {
    return SongItem(
        id = this.id,
        title = this.title,
        artist = this.artist,
        album = this.album,
        releaseYear = this.releaseYear,
        decade = this.decade.toRetroDecade(),
        genre = this.genre,
        durationSeconds = (this.durationMs / 1000).toInt().coerceAtLeast(1),
        energyBpm = 120,
        keyTag = this.decade.uppercase(),
        audioRelativePath = this.audioRelativePath,
        artworkRelativePath = this.artworkRelativePath,
        licenseApprovalStatus = this.licenseApprovalStatus,
        fileSizeBytes = this.fileSizeBytes,
        sha256Hash = this.sha256Hash,
        isAvailable = this.isAvailable
    )
}

fun RetroDecade.toDecadeKey(): String {
    return when (this) {
        RetroDecade.DECADE_70S -> "70s"
        RetroDecade.DECADE_80S -> "80s"
        RetroDecade.DECADE_90S -> "90s"
        RetroDecade.DECADE_00S -> "00s"
        RetroDecade.ALL -> "ALL"
        RetroDecade.DECADE_UNKNOWN -> "UNKNOWN"
    }
}

fun String.toRetroDecade(): RetroDecade {
    return when (this) {
        "70s" -> RetroDecade.DECADE_70S
        "80s" -> RetroDecade.DECADE_80S
        "90s" -> RetroDecade.DECADE_90S
        "00s" -> RetroDecade.DECADE_00S
        else -> RetroDecade.DECADE_UNKNOWN
    }
}
