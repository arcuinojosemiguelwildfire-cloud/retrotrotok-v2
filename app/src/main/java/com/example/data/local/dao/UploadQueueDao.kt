package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.UploadQueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadQueueDao {

    @Query("SELECT * FROM upload_queue ORDER BY updatedAtMs DESC")
    fun observeAll(): Flow<List<UploadQueueEntity>>

    @Query("SELECT * FROM upload_queue ORDER BY createdAtMs ASC")
    suspend fun getAllOnce(): List<UploadQueueEntity>

    @Query("SELECT * FROM upload_queue WHERE videoId = :videoId LIMIT 1")
    suspend fun getByVideoId(videoId: String): UploadQueueEntity?

    @Query(
        """
        SELECT * FROM upload_queue
        WHERE status IN ('QUEUED', 'REQUESTING_UPLOAD', 'UPLOADING', 'VERIFYING', 'RETRY_PENDING')
        ORDER BY createdAtMs ASC
        """
    )
    suspend fun getPendingAndInterrupted(): List<UploadQueueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: UploadQueueEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entry: UploadQueueEntity): Long

    @Query("DELETE FROM upload_queue WHERE videoId = :videoId")
    suspend fun deleteByVideoId(videoId: String): Int
}
