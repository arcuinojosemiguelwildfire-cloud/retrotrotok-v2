package com.example.data.mock

import com.example.data.repository.VideoFeedRepository
import com.example.data.repository.VideoSearchSort
import com.example.model.VideoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

class MockVideoFeedRepository : VideoFeedRepository {

    private val _videos = MutableStateFlow<List<VideoItem>>(MockDataProvider.mockVideos)

    override fun getFeedVideos(): Flow<List<VideoItem>> = _videos.map { list ->
        list.filter { !it.isHidden }
    }

    override fun getAllAdminVideos(): Flow<List<VideoItem>> = _videos.asStateFlow()

    override fun searchVideos(query: String, sort: VideoSearchSort): Flow<List<VideoItem>> = _videos.map { list ->
        val q = query.trim().lowercase()
        list.filter { video ->
            !video.isHidden && (
                q.isBlank() ||
                    video.creatorNickname.lowercase().contains(q) ||
                    video.caption.lowercase().contains(q) ||
                    video.hashtags.lowercase().contains(q) ||
                    video.song.title.lowercase().contains(q) ||
                    video.song.artist.lowercase().contains(q)
                )
        }.let { filtered ->
            when (sort) {
                VideoSearchSort.NEWEST -> filtered.sortedByDescending { it.createdAt }
                VideoSearchSort.MOST_LIKED -> filtered.sortedByDescending { it.likeCount }
                VideoSearchSort.FAVORITES -> filtered.filter { it.isFavorited }
                VideoSearchSort.SOUNDTRACK -> filtered.sortedBy { it.song.title }
            }
        }
    }

    override suspend fun toggleLike(videoId: String): VideoItem? {
        val currentList = _videos.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == videoId }
        if (index != -1) {
            val item = currentList[index]
            val newIsLiked = !item.isLiked
            val newCount = if (newIsLiked) item.likeCount + 1 else (item.likeCount - 1).coerceAtLeast(0)
            val updated = item.copy(isLiked = newIsLiked, likeCount = newCount)
            currentList[index] = updated
            _videos.value = currentList
            return updated
        }
        return null
    }

    override suspend fun ensureLiked(videoId: String): VideoItem? {
        val currentList = _videos.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == videoId }
        if (index != -1) {
            val item = currentList[index]
            if (item.isLiked) return item
            val updated = item.copy(isLiked = true, likeCount = item.likeCount + 1)
            currentList[index] = updated
            _videos.value = currentList
            return updated
        }
        return null
    }

    override suspend fun toggleFavorite(videoId: String): VideoItem? {
        val currentList = _videos.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == videoId }
        if (index != -1) {
            val item = currentList[index]
            val newIsFav = !item.isFavorited
            val newCount = if (newIsFav) item.favoriteCount + 1 else (item.favoriteCount - 1).coerceAtLeast(0)
            val updated = item.copy(isFavorited = newIsFav, favoriteCount = newCount)
            currentList[index] = updated
            _videos.value = currentList
            return updated
        }
        return null
    }

    override suspend fun publishVideo(newVideo: VideoItem) {
        val currentList = _videos.value.toMutableList()
        // Newly added video is placed right at the top of the feed
        currentList.add(0, newVideo)
        _videos.value = currentList
    }

    override suspend fun deleteVideo(videoId: String) {
        val currentList = _videos.value.toMutableList()
        currentList.removeAll { it.id == videoId }
        _videos.value = currentList
    }

    override suspend fun toggleFeatured(videoId: String) {
        val currentList = _videos.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == videoId }
        if (index != -1) {
            val item = currentList[index]
            currentList[index] = item.copy(isFeatured = !item.isFeatured)
            _videos.value = currentList
        }
    }

    override suspend fun moderateVideo(videoId: String, hide: Boolean, reason: String?) {
        val currentList = _videos.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == videoId }
        if (index != -1) {
            val item = currentList[index]
            currentList[index] = item.copy(
                moderationStatus = if (hide) "HIDDEN" else "VISIBLE",
                moderatedAt = if (hide) System.currentTimeMillis() else null,
                moderationReason = if (hide) reason?.takeIf { it.isNotBlank() } ?: "Hidden by event organizer" else null
            )
            _videos.value = currentList
        }
    }
}
