# RetroTok — Local Database & Persistence Design

**Document ID:** RT-DOC-05  
**Version:** 1.0.0  
**Storage Engine:** Room 2.7.0 (SQLite) + Jetpack DataStore (Preferences)  
**Schema Version:** 1  

---

## 1. Persistence Architecture Overview

All structured application data in RetroTok is managed strictly on-device using a dual persistence approach:
1. **Room Database (`RetroTokDatabase`):** Relational metadata for music tracks, video recordings, guest reactions, and event sessions.
2. **Jetpack DataStore (`KioskPreferences`):** Key-value preferences for kiosk runtime settings, admin PIN hashes, and hardware configuration flags.

---

## 2. Entity-Relationship Diagram (ERD)

```
+--------------------+              +--------------------+
|    EventEntity     | 1          * |    VideoEntity     |
|--------------------|<-------------|--------------------|
| id (PK, UUID)      |              | id (PK, UUID)      |
| name               |              | eventId (FK)       |
| theme              |              | songId (FK, null)  |
| startDate          |              | videoFilePath      |
| adminPinHash       |              | thumbnailPath      |
| isActive           |              | rawRecordingPath   |
+--------------------+              | creatorNickname    |
                                    | durationMs         |
+--------------------+              | width, height, fps |
|     SongEntity     | 1          * | appliedEffect      |
|--------------------|<-------------| processingStatus   |
| id (PK, UUID)      |              | failureReason      |
| title              |              | likeCount          |
| artist             |              | favoriteCount      |
| album              |              | isFeatured         |
| releaseYear        |              | isGuestDeletable   |
| decade             |              | createdAt          |
| genre              |              +---------+----------+
| durationMs         |                        |
| filePath           |                        | 1
| artworkPath        |                        |
| audioHash          |                        | *
| isLicensed         |              +---------v----------+
+--------------------+              |  VideoLikeEntity   |
         | 1                        |--------------------|
         |                          | id (PK, UUID)      |
         | *                        | videoId (FK)       |
+--------v-----------+              | sessionId          |
| PlaylistSongCrossRef|             | createdAt          |
+--------------------+              +--------------------+
         | *
         | 1
+--------v-----------+
|   PlaylistEntity   |
|--------------------|
| id (PK, UUID)      |
| name, description  |
| createdAt          |
+--------------------+
```

---

## 3. Entity Specifications (Kotlin)

### 3.1 `VideoEntity`
Manages metadata, file locations, and processing lifecycle for both guest recordings and admin-imported media.

```kotlin
package com.aistudio.retrotok.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class VideoProcessingStatus {
    RECORDING,    // Capture in progress on webcam
    PROCESSING,   // Media3 Transformer audio mux and shader rendering
    READY,        // Transformed and ready for preview
    PUBLISHED,    // Approved by guest and visible on For You Feed
    FAILED        // Transformation or I/O error occurred
}

@Entity(
    tableName = "videos",
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["eventId"]),
        Index(value = ["songId"]),
        Index(value = ["processingStatus"]),
        Index(value = ["createdAt"]),
        Index(value = ["isFeatured"])
    ]
)
data class VideoEntity(
    @PrimaryKey val id: String,                    // Stable UUID string
    val eventId: String,                           // Active event reference
    val songId: String?,                           // Attached song (nullable for imported clips)
    val videoFilePath: String,                     // Absolute path to finalized MP4 in /published/
    val thumbnailPath: String,                     // Absolute path to WebP thumbnail
    val rawRecordingPath: String?,                 // Path to unedited webcam capture (for retry)
    val creatorNickname: String,                   // Guest nickname or default "@RetroDancer"
    val durationMs: Long,                          // Video duration in milliseconds
    val width: Int = 720,                          // Pixel width
    val height: Int = 1280,                        // Pixel height
    val fps: Int = 30,                             // Target frame rate
    val appliedEffect: String,                     // "VHS", "NEON_80S", "VINTAGE_70S", "FILM_90S", "NONE"
    val processingStatus: VideoProcessingStatus,   // RECORDING, PROCESSING, READY, PUBLISHED, FAILED
    val failureReason: String? = null,             // Error message if processing failed
    val likeCount: Int = 0,                        // Total likes accrued on kiosk
    val favoriteCount: Int = 0,                    // Total favorites accrued on kiosk
    val isFeatured: Boolean = false,               // Admin pinned video
    val isGuestDeletable: Boolean = false,         // Subject to guest deletion policy
    val createdAt: Long = System.currentTimeMillis()
)
```

### 3.2 `SongEntity`
Maintains the catalog of imported nostalgic tracks.

```kotlin
package com.aistudio.retrotok.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RetroDecade {
    DECADE_70S,     // 1970–1979
    DECADE_80S,     // 1980–1989
    DECADE_90S,     // 1990–1999
    DECADE_00S,     // 2000–2001
    DECADE_UNKNOWN  // Fallback for unclassified audio
}

@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["audioHash"], unique = true),
        Index(value = ["decade"]),
        Index(value = ["genre"]),
        Index(value = ["title", "artist"])
    ]
)
data class SongEntity(
    @PrimaryKey val id: String,                    // Stable UUID
    val title: String,                             // Song title
    val artist: String,                            // Artist / Band name
    val album: String?,                            // Album name
    val releaseYear: Int?,                         // Release year (e.g., 1983)
    val decade: RetroDecade,                       // Normalized decade category
    val genre: String,                             // Disco, Synthpop, Rock, Pop, etc.
    val durationMs: Long,                          // Track duration
    val filePath: String,                          // Absolute path in application /music/ storage
    val artworkPath: String?,                      // Extracted album art path in /music/artwork/
    val audioHash: String,                         // SHA-256 hash of audio stream for duplicate check
    val isLicensed: Boolean = true,                // Organizer authorization confirmation flag
    val createdAt: Long = System.currentTimeMillis()
)
```

### 3.3 `EventEntity`
Captures active event parameters and kiosk branding.

```kotlin
package com.aistudio.retrotok.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val id: String,                    // Event UUID
    val name: String,                              // e.g., "Acme Corp 40th Anniversary"
    val theme: String,                             // e.g., "Back to the 80s"
    val startDate: Long,                           // Epoch timestamp
    val adminPinHash: String,                      // PBKDF2 hash of admin PIN
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
```

### 3.4 `VideoLikeEntity`
Tracks individual guest like interactions to prevent infinite like spam from a single session.

```kotlin
@Entity(
    tableName = "video_likes",
    indices = [Index(value = ["videoId", "sessionId"], unique = true)]
)
data class VideoLikeEntity(
    @PrimaryKey val id: String,
    val videoId: String,
    val sessionId: String,
    val createdAt: Long = System.currentTimeMillis()
)
```

---

## 4. Room Data Access Objects (DAOs)

```kotlin
package com.aistudio.retrotok.data.local.dao

import androidx.room.*
import com.aistudio.retrotok.data.local.entity.RetroDecade
import com.aistudio.retrotok.data.local.entity.SongEntity
import com.aistudio.retrotok.data.local.entity.VideoEntity
import com.aistudio.retrotok.data.local.entity.VideoProcessingStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {
    @Query("""
        SELECT * FROM videos 
        WHERE processingStatus = :status 
        ORDER BY isFeatured DESC, createdAt DESC
    """)
    fun getPublishedFeedVideos(status: VideoProcessingStatus = VideoProcessingStatus.PUBLISHED): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE id = :id LIMIT 1")
    suspend fun getVideoById(id: String): VideoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideo(video: VideoEntity)

    @Update
    suspend fun updateVideo(video: VideoEntity)

    @Query("UPDATE videos SET processingStatus = :newStatus WHERE id = :id")
    suspend fun updateProcessingStatus(id: String, newStatus: VideoProcessingStatus)

    @Query("UPDATE videos SET likeCount = likeCount + 1 WHERE id = :videoId")
    suspend fun incrementLikeCount(videoId: String)

    @Query("UPDATE videos SET favoriteCount = favoriteCount + 1 WHERE id = :videoId")
    suspend fun incrementFavoriteCount(videoId: String)

    @Delete
    suspend fun deleteVideo(video: VideoEntity)

    @Query("DELETE FROM videos WHERE id = :videoId")
    suspend fun deleteVideoById(videoId: String)

    @Query("SELECT COUNT(*) FROM videos WHERE processingStatus = 'PUBLISHED'")
    fun getPublishedVideoCount(): Flow<Int>
}

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY decade ASC, title ASC")
    fun getAllSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE decade = :decade ORDER BY title ASC")
    fun getSongsByDecade(decade: RetroDecade): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%'")
    fun searchSongs(query: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE audioHash = :hash LIMIT 1")
    suspend fun getSongByHash(hash: String): SongEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSong(song: SongEntity)

    @Update
    suspend fun updateSong(song: SongEntity)

    @Delete
    suspend fun deleteSong(song: SongEntity)
}
```

---

## 5. DataStore Preferences Schema (`KioskPreferences`)

| Preference Key | Type | Default Value | Description |
| :--- | :--- | :--- | :--- |
| `pref_admin_pin_hash` | String | PBKDF2("1234") | Salted PBKDF2 hash of master admin PIN |
| `pref_guest_deletion_enabled` | Boolean | `false` | When true, guests can delete their own video |
| `pref_recording_duration_sec` | Int | `30` | Default recording duration (15, 30, or 60) |
| `pref_countdown_duration_sec` | Int | `3` | Studio countdown timer (3 or 5) |
| `pref_kiosk_lock_enabled` | Boolean | `true` | Enforces Android Lock Task Mode |
| `pref_idle_timeout_sec` | Int | `60` | Auto-reset to feed after guest inactivity |
| `pref_camera_mirroring` | Boolean | `true` | Mirrors live webcam preview horizontally |
| `pref_low_storage_warning_shown` | Boolean | `false` | Tracks persistent storage notifications |
