package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.VideoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {

    @Query("""
        SELECT * FROM videos 
        WHERE publicationStatus IN ('PUBLISHED', 'IMPORTED')
          AND moderationStatus = 'VISIBLE'
        ORDER BY createdAt DESC
    """)
    fun getPublishedVideos(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos ORDER BY createdAt DESC")
    fun getAllVideos(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos ORDER BY createdAt DESC")
    suspend fun getAllVideosOnce(): List<VideoEntity>

    @Query("""
        SELECT * FROM videos 
        WHERE publicationStatus IN ('PUBLISHED', 'IMPORTED')
          AND moderationStatus = 'VISIBLE'
          AND isFavorite = 1
        ORDER BY createdAt DESC
    """)
    fun getFavoriteVideos(): Flow<List<VideoEntity>>

    @Query("""
        SELECT * FROM videos 
        WHERE publicationStatus IN ('PUBLISHED', 'IMPORTED')
          AND moderationStatus = 'VISIBLE'
          AND isFeatured = 1
        ORDER BY createdAt DESC
    """)
    fun getFeaturedVideos(): Flow<List<VideoEntity>>

    @Query("""
        SELECT * FROM videos 
        WHERE publicationStatus IN ('PUBLISHED', 'IMPORTED')
          AND moderationStatus = 'VISIBLE'
        ORDER BY createdAt DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getPublishedVideosPaged(limit: Int, offset: Int): List<VideoEntity>

    @Query("""
        SELECT * FROM videos
        WHERE publicationStatus IN ('PUBLISHED', 'IMPORTED')
          AND moderationStatus = 'VISIBLE'
          AND (nickname LIKE '%' || :query || '%'
               OR caption LIKE '%' || :query || '%'
               OR hashtags LIKE '%' || :query || '%'
               OR soundtrackTitle LIKE '%' || :query || '%'
               OR soundtrackArtist LIKE '%' || :query || '%')
        ORDER BY createdAt DESC
    """)
    fun searchPublishedVideos(query: String): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE videoId = :videoId LIMIT 1")
    suspend fun getVideoById(videoId: String): VideoEntity?

    @Query("SELECT * FROM videos WHERE localVideoPath = :relativePath LIMIT 1")
    suspend fun getVideoByPath(relativePath: String): VideoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideo(video: VideoEntity)

    @Update
    suspend fun updateVideo(video: VideoEntity)

    @Query("""
        UPDATE videos 
        SET isLiked = :isLiked, likeCount = :likeCount, updatedAt = :updatedAt
        WHERE videoId = :videoId
    """)
    suspend fun updateLikeState(
        videoId: String,
        isLiked: Boolean,
        likeCount: Int,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE videos 
        SET isFavorite = :isFavorite, updatedAt = :updatedAt
        WHERE videoId = :videoId
    """)
    suspend fun updateFavoriteState(
        videoId: String,
        isFavorite: Boolean,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE videos 
        SET isFeatured = :isFeatured, updatedAt = :updatedAt
        WHERE videoId = :videoId
    """)
    suspend fun updateFeaturedState(
        videoId: String,
        isFeatured: Boolean,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE videos
        SET moderationStatus = :moderationStatus,
            moderatedAt = :moderatedAt,
            moderationReason = :moderationReason,
            updatedAt = :updatedAt
        WHERE videoId = :videoId
    """)
    suspend fun updateModerationState(
        videoId: String,
        moderationStatus: String,
        moderatedAt: Long?,
        moderationReason: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE videos 
        SET thumbnailPath = :thumbnailPath, updatedAt = :updatedAt
        WHERE videoId = :videoId
    """)
    suspend fun updateThumbnailPath(
        videoId: String,
        thumbnailPath: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE videos 
        SET publicationStatus = :status, updatedAt = :updatedAt
        WHERE videoId = :videoId
    """)
    suspend fun updatePublicationStatus(
        videoId: String,
        status: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM videos WHERE videoId = :videoId")
    suspend fun deleteVideoById(videoId: String): Int

    @Query("""
        SELECT COUNT(*) FROM videos 
        WHERE publicationStatus IN ('PUBLISHED', 'IMPORTED')
          AND moderationStatus = 'VISIBLE'
    """)
    fun getPublishedCount(): Flow<Int>

    @Query("SELECT SUM(fileSize) FROM videos WHERE publicationStatus IN ('PUBLISHED', 'IMPORTED')")
    fun getTotalVideoSizeBytes(): Flow<Long?>
}
