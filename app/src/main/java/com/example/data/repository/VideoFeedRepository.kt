package com.example.data.repository

import android.net.Uri
import com.example.model.RetroEffectType
import com.example.model.SongItem
import com.example.model.VideoItem
import kotlinx.coroutines.flow.Flow
import java.io.File

enum class VideoSearchSort {
    NEWEST,
    MOST_LIKED,
    FAVORITES,
    SOUNDTRACK
}

enum class ModerationFilter(val label: String) {
    ALL("All"),
    VISIBLE("Visible"),
    HIDDEN("Hidden"),
    FEATURED("Featured"),
    MISSING_FILE("Missing")
}

sealed class PublishVideoResult {
    data class Success(val publishedVideo: VideoItem) : PublishVideoResult()
    data class Error(val message: String) : PublishVideoResult()
}

sealed class ImportVideoResult {
    data class Success(val importedVideo: VideoItem) : ImportVideoResult()
    data class Error(val message: String) : ImportVideoResult()
}

data class ReconciliationReport(
    val recoveredOrphanFiles: Int = 0,
    val flaggedMissingRecords: Int = 0,
    val regeneratedThumbnails: Int = 0,
    val orphanedThumbnailsCount: Int = 0
)

interface VideoFeedRepository {
    fun getFeedVideos(): Flow<List<VideoItem>>
    fun getAllAdminVideos(): Flow<List<VideoItem>> = getFeedVideos()
    fun searchVideos(query: String, sort: VideoSearchSort = VideoSearchSort.NEWEST): Flow<List<VideoItem>> = getFeedVideos()
    suspend fun getFeedVideosPaged(limit: Int, offset: Int): List<VideoItem> = emptyList()

    suspend fun toggleLike(videoId: String): VideoItem?
    suspend fun ensureLiked(videoId: String): VideoItem? = toggleLike(videoId)
    suspend fun toggleFavorite(videoId: String): VideoItem?
    suspend fun publishVideo(newVideo: VideoItem)
    suspend fun deleteVideo(videoId: String)
    suspend fun toggleFeatured(videoId: String)
    suspend fun moderateVideo(videoId: String, hide: Boolean, reason: String? = null) {}

    suspend fun publishRecordedVideo(
        sourceFile: File,
        nickname: String,
        caption: String,
        hashtags: String,
        song: SongItem,
        effect: RetroEffectType,
        durationSeconds: Int
    ): PublishVideoResult {
        return PublishVideoResult.Error("Not supported in mock repository")
    }

    suspend fun importLocalVideo(
        uri: Uri,
        nickname: String,
        caption: String,
        hashtags: String
    ): ImportVideoResult {
        return ImportVideoResult.Error("Not supported in mock repository")
    }

    suspend fun reconcileStorageAndDatabase(): ReconciliationReport = ReconciliationReport()
    suspend fun purgeOrphanedThumbnails(): Int = 0
}
