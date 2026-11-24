package com.example.model

data class VideoItem(
    val id: String,
    val creatorNickname: String,
    val caption: String,
    val song: SongItem,
    val effect: RetroEffectType,
    val durationSeconds: Int,
    val likeCount: Int,
    val favoriteCount: Int,
    val isLiked: Boolean = false,
    val isFavorited: Boolean = false,
    val isFeatured: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val mockGradientColors: List<Long> = listOf(0xFF2E0854, 0xFF120024),
    val localVideoPath: String? = null,
    val hashtags: String = "#RetroTok",
    val thumbnailPath: String? = null,
    val resolutionWidth: Int = 720,
    val resolutionHeight: Int = 1280,
    val fileSizeBytes: Long = 0L,
    val updatedAt: Long = createdAt,
    val publicationStatus: String = "PUBLISHED",
    val isFileHealthy: Boolean = true,
    val moderationStatus: String = "VISIBLE",
    val moderatedAt: Long? = null,
    val moderationReason: String? = null,
    val cloudUploadSummary: CloudUploadSummary? = null
) {
    val isHidden: Boolean
        get() = moderationStatus == "HIDDEN"
}
