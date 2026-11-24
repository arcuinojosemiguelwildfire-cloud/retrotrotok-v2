package com.example.data.repository

import android.net.Uri
import com.example.model.RetroDecade
import com.example.model.SongItem
import kotlinx.coroutines.flow.Flow

data class ImportResultItem(
    val filename: String,
    val isSuccess: Boolean,
    val isDuplicate: Boolean = false,
    val errorMessage: String? = null,
    val importedSong: SongItem? = null
)

data class ImportSummary(
    val totalProcessed: Int,
    val successfulCount: Int,
    val duplicateCount: Int,
    val failedCount: Int,
    val results: List<ImportResultItem>
)

data class MusicStorageMetrics(
    val totalSongs: Int,
    val approvedSongs: Int,
    val totalFileSizeBytes: Long,
    val songsByDecade: Map<RetroDecade, Int>,
    val songsByGenre: Map<String, Int>
)

interface MusicCatalogRepository {
    fun getAllSongs(): Flow<List<SongItem>>
    fun getApprovedSongs(): Flow<List<SongItem>>
    fun getSongsByDecade(decade: RetroDecade): Flow<List<SongItem>>
    fun getApprovedSongsByDecade(decade: RetroDecade): Flow<List<SongItem>>
    fun searchSongs(query: String): Flow<List<SongItem>>
    fun searchApprovedSongs(query: String): Flow<List<SongItem>>
    suspend fun getSongById(songId: String): SongItem?

    suspend fun importAudioFiles(
        uris: List<Uri>,
        onProgress: (current: Int, total: Int, currentName: String) -> Unit = { _, _, _ -> }
    ): ImportSummary

    suspend fun updateSongMetadata(
        songId: String,
        title: String,
        artist: String,
        album: String,
        year: Int?,
        genre: String,
        decade: RetroDecade
    ): Boolean

    suspend fun updateLicenseStatus(songId: String, newStatus: String): Boolean
    suspend fun deleteSong(songId: String, isReferencedByVideos: Boolean = false): Boolean
    fun getSongMetrics(): Flow<MusicStorageMetrics>
}
