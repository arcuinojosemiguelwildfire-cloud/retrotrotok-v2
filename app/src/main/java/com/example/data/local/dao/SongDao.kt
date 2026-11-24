package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.SongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    @Query("SELECT * FROM songs WHERE isAvailable = 1 ORDER BY decade ASC, title ASC")
    fun getAllSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isAvailable = 1 AND licenseApprovalStatus = 'APPROVED_FOR_EVENT_USE' ORDER BY decade ASC, title ASC")
    fun getApprovedSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isAvailable = 1 AND decade = :decade ORDER BY title ASC")
    fun getSongsByDecade(decade: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isAvailable = 1 AND licenseApprovalStatus = 'APPROVED_FOR_EVENT_USE' AND decade = :decade ORDER BY title ASC")
    fun getApprovedSongsByDecade(decade: String): Flow<List<SongEntity>>

    @Query("""
        SELECT * FROM songs 
        WHERE title LIKE '%' || :query || '%' 
           OR artist LIKE '%' || :query || '%' 
           OR album LIKE '%' || :query || '%' 
           OR genre LIKE '%' || :query || '%'
        ORDER BY title ASC
    """)
    fun searchSongs(query: String): Flow<List<SongEntity>>

    @Query("""
        SELECT * FROM songs 
        WHERE isAvailable = 1 
          AND licenseApprovalStatus = 'APPROVED_FOR_EVENT_USE'
          AND (title LIKE '%' || :query || '%' 
               OR artist LIKE '%' || :query || '%' 
               OR album LIKE '%' || :query || '%' 
               OR genre LIKE '%' || :query || '%')
        ORDER BY title ASC
    """)
    fun searchApprovedSongs(query: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :id LIMIT 1")
    suspend fun getSongById(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE sha256Hash = :hash LIMIT 1")
    suspend fun getSongByHash(hash: String): SongEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSong(song: SongEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SongEntity>)

    @Update
    suspend fun updateSong(song: SongEntity)

    @Delete
    suspend fun deleteSong(song: SongEntity)

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteSongById(id: String): Int

    @Query("UPDATE songs SET isAvailable = 0, licenseApprovalStatus = 'NOT_APPROVED', updatedAt = :timestamp WHERE id = :id")
    suspend fun softDeleteSong(id: String, timestamp: Long = System.currentTimeMillis()): Int

    @Query("SELECT COUNT(*) FROM songs WHERE isAvailable = 1")
    fun getSongCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM songs WHERE isAvailable = 1 AND licenseApprovalStatus = 'APPROVED_FOR_EVENT_USE'")
    fun getApprovedSongCount(): Flow<Int>

    @Query("SELECT SUM(fileSizeBytes) FROM songs WHERE isAvailable = 1")
    fun getTotalFileSizeBytes(): Flow<Long?>
}
