package com.example.data.local.metadata

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ExtractedAudioMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val releaseYear: Int?,
    val decade: String,
    val genre: String,
    val durationMs: Long,
    val artworkBytes: ByteArray?
)

class AudioMetadataExtractor {

    suspend fun extract(file: File, fallbackFileName: String): ExtractedAudioMetadata = withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)

            val rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val rawAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val rawYear = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            val rawGenre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            val rawDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val embeddedArt = retriever.embeddedPicture

            val cleanedTitle = if (!rawTitle.isNullOrBlank()) {
                rawTitle.trim()
            } else {
                sanitizeFileNameToTitle(fallbackFileName)
            }

            val cleanedArtist = if (!rawArtist.isNullOrBlank()) {
                rawArtist.trim()
            } else {
                "Unknown Artist"
            }

            val cleanedAlbum = if (!rawAlbum.isNullOrBlank()) {
                rawAlbum.trim()
            } else {
                "Unknown Album"
            }

            val parsedYear = parseYear(rawYear)
            val decade = mapYearToDecade(parsedYear)

            val cleanedGenre = if (!rawGenre.isNullOrBlank()) {
                rawGenre.trim()
            } else {
                "Retro Music"
            }

            val durationMs = rawDuration?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

            ExtractedAudioMetadata(
                title = cleanedTitle,
                artist = cleanedArtist,
                album = cleanedAlbum,
                releaseYear = parsedYear,
                decade = decade,
                genre = cleanedGenre,
                durationMs = durationMs,
                artworkBytes = embeddedArt
            )
        } catch (e: Exception) {
            // Safe fallback if metadata retrieval throws
            ExtractedAudioMetadata(
                title = sanitizeFileNameToTitle(fallbackFileName),
                artist = "Unknown Artist",
                album = "Unknown Album",
                releaseYear = null,
                decade = "UNKNOWN",
                genre = "Retro Music",
                durationMs = 0L,
                artworkBytes = null
            )
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    private fun sanitizeFileNameToTitle(fileName: String): String {
        return fileName.substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .ifBlank { "Untitled Retro Track" }
    }

    private fun parseYear(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        // Extract 4-digit year e.g. 1983 or 1999-05-12
        val regex = Regex("""\b(19\d\d|20[0-2]\d)\b""")
        val match = regex.find(raw)
        return match?.value?.toIntOrNull()
    }

    fun mapYearToDecade(year: Int?): String {
        if (year == null) return "UNKNOWN"
        return when (year) {
            in 1970..1979 -> "70s"
            in 1980..1989 -> "80s"
            in 1990..1999 -> "90s"
            in 2000..2009 -> "00s"
            else -> "UNKNOWN"
        }
    }
}
