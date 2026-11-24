package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "videos",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["isFavorite"]),
        Index(value = ["isFeatured"]),
        Index(value = ["likeCount"]),
        Index(value = ["publicationStatus"]),
        Index(value = ["soundtrackId"]),
        Index(value = ["moderationStatus"])
    ]
)
data class VideoEntity(
    @PrimaryKey
    val videoId: String,
    val nickname: String,
    val caption: String,
    val hashtags: String,
    val localVideoPath: String,
    val thumbnailPath: String? = null,
    val soundtrackTitle: String,
    val soundtrackArtist: String,
    val soundtrackId: String? = null,
    val effectType: String,
    val duration: Int,
    val resolutionWidth: Int,
    val resolutionHeight: Int,
    val fileSize: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val isFavorite: Boolean = false,
    val likeCount: Int = 0,
    val isLiked: Boolean = false,
    val isFeatured: Boolean = false,
    val publicationStatus: String = "PUBLISHED", // PUBLISHED, IMPORTED, MISSING_FILE, PENDING_RECOVERY
    @ColumnInfo(defaultValue = "VISIBLE")
    val moderationStatus: String = "VISIBLE",    // VISIBLE, HIDDEN
    val moderatedAt: Long? = null,
    val moderationReason: String? = null
)
