package com.example.data.mock

import android.net.Uri
import com.example.data.repository.ImportResultItem
import com.example.data.repository.ImportSummary
import com.example.data.repository.MusicCatalogRepository
import com.example.data.repository.MusicStorageMetrics
import com.example.model.RetroDecade
import com.example.model.SongItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class MockMusicCatalogRepository : MusicCatalogRepository {

    private val _songs = MutableStateFlow<List<SongItem>>(MockDataProvider.mockSongs)

    override fun getAllSongs(): Flow<List<SongItem>> = _songs

    override fun getApprovedSongs(): Flow<List<SongItem>> {
        return _songs.map { list -> list.filter { it.isApprovedForGuestUse } }
    }

    override fun getSongsByDecade(decade: RetroDecade): Flow<List<SongItem>> {
        return _songs.map { list ->
            if (decade == RetroDecade.ALL) list
            else list.filter { it.decade == decade }
        }
    }

    override fun getApprovedSongsByDecade(decade: RetroDecade): Flow<List<SongItem>> {
        return _songs.map { list ->
            val approved = list.filter { it.isApprovedForGuestUse }
            if (decade == RetroDecade.ALL) approved
            else approved.filter { it.decade == decade }
        }
    }

    override fun searchSongs(query: String): Flow<List<SongItem>> {
        return _songs.map { list ->
            if (query.isBlank()) list
            else list.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true) ||
                it.album.contains(query, ignoreCase = true) ||
                it.genre.contains(query, ignoreCase = true)
            }
        }
    }

    override fun searchApprovedSongs(query: String): Flow<List<SongItem>> {
        return _songs.map { list ->
            val approved = list.filter { it.isApprovedForGuestUse }
            if (query.isBlank()) approved
            else approved.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true) ||
                it.album.contains(query, ignoreCase = true) ||
                it.genre.contains(query, ignoreCase = true)
            }
        }
    }

    override suspend fun getSongById(songId: String): SongItem? {
        return _songs.value.firstOrNull { it.id == songId }
    }

    override suspend fun importAudioFiles(
        uris: List<Uri>,
        onProgress: (current: Int, total: Int, currentName: String) -> Unit
    ): ImportSummary {
        val results = uris.mapIndexed { index, uri ->
            val name = "Track_${index + 1}.mp3"
            onProgress(index + 1, uris.size, name)
            val newSong = SongItem(
                id = "mock-imported-$index",
                title = name.substringBeforeLast('.'),
                artist = "Imported Artist",
                album = "Imported Album",
                releaseYear = 1984,
                decade = RetroDecade.DECADE_80S,
                genre = "Synthpop",
                durationSeconds = 180,
                licenseApprovalStatus = "APPROVED_FOR_EVENT_USE"
            )
            ImportResultItem(
                filename = name,
                isSuccess = true,
                importedSong = newSong
            )
        }
        val current = _songs.value.toMutableList()
        current.addAll(results.mapNotNull { it.importedSong })
        _songs.value = current

        return ImportSummary(
            totalProcessed = uris.size,
            successfulCount = results.size,
            duplicateCount = 0,
            failedCount = 0,
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
    ): Boolean {
        val current = _songs.value.toMutableList()
        val index = current.indexOfFirst { it.id == songId }
        if (index != -1) {
            val item = current[index]
            current[index] = item.copy(
                title = title,
                artist = artist,
                album = album,
                releaseYear = year,
                genre = genre,
                decade = decade
            )
            _songs.value = current
            return true
        }
        return false
    }

    override suspend fun updateLicenseStatus(songId: String, newStatus: String): Boolean {
        val current = _songs.value.toMutableList()
        val index = current.indexOfFirst { it.id == songId }
        if (index != -1) {
            val item = current[index]
            current[index] = item.copy(licenseApprovalStatus = newStatus)
            _songs.value = current
            return true
        }
        return false
    }

    override suspend fun deleteSong(songId: String, isReferencedByVideos: Boolean): Boolean {
        val current = _songs.value.toMutableList()
        val index = current.indexOfFirst { it.id == songId }
        if (index != -1) {
            if (isReferencedByVideos) {
                // Soft retire
                current[index] = current[index].copy(
                    isAvailable = false,
                    licenseApprovalStatus = "NOT_APPROVED"
                )
            } else {
                current.removeAt(index)
            }
            _songs.value = current
            return true
        }
        return false
    }

    override fun getSongMetrics(): Flow<MusicStorageMetrics> {
        return _songs.map { songs ->
            val totalBytes = songs.sumOf { it.fileSizeBytes }
            val approvedCount = songs.count { it.isApprovedForGuestUse }
            val decadeMap = mutableMapOf<RetroDecade, Int>()
            RetroDecade.values().forEach { decadeMap[it] = 0 }
            songs.forEach { s -> decadeMap[s.decade] = (decadeMap[s.decade] ?: 0) + 1 }

            val genreMap = mutableMapOf<String, Int>()
            songs.forEach { s -> genreMap[s.genre] = (genreMap[s.genre] ?: 0) + 1 }

            MusicStorageMetrics(
                totalSongs = songs.size,
                approvedSongs = approvedCount,
                totalFileSizeBytes = totalBytes,
                songsByDecade = decadeMap,
                songsByGenre = genreMap
            )
        }
    }
}
