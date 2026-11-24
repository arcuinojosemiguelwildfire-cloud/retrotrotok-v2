package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["sha256Hash"], unique = true),
        Index(value = ["decade"]),
        Index(value = ["genre"]),
        Index(value = ["licenseApprovalStatus"]),
        Index(value = ["title", "artist"])
    ]
)
data class SongEntity(
    @PrimaryKey
    val id: String,                         // Stable UUID
    val title: String,                      // Cleaned song title
    val artist: String,                     // Artist / Band name
    val album: String,                      // Album name
    val releaseYear: Int?,                  // e.g. 1983
    val decade: String,                     // "70s", "80s", "90s", "00s", "UNKNOWN"
    val genre: String,                      // e.g. "Synthpop", "Disco"
    val durationMs: Long,                   // Duration in milliseconds
    val audioFileName: String,              // Original or sanitized file name
    val audioRelativePath: String,          // Relative path: "retrotok/music/audio/{id}.mp3"
    val artworkRelativePath: String?,       // Relative path: "retrotok/music/artwork/{id}.webp"
    val fileSizeBytes: Long,                // File size on disk in bytes
    val sha256Hash: String,                 // Content hash for duplicate prevention
    val importedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val isAvailable: Boolean = true,
    val licenseApprovalStatus: String = "UNREVIEWED" // "UNREVIEWED", "APPROVED_FOR_EVENT_USE", "NOT_APPROVED"
)
