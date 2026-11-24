package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.SongDao
import com.example.data.local.dao.UploadQueueDao
import com.example.data.local.dao.VideoDao
import com.example.data.local.entity.SongEntity
import com.example.data.local.entity.UploadQueueEntity
import com.example.data.local.entity.VideoEntity

@Database(
    entities = [SongEntity::class, VideoEntity::class, UploadQueueEntity::class],
    version = 4,
    exportSchema = false
)
abstract class RetroTokDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao
    abstract fun videoDao(): VideoDao
    abstract fun uploadQueueDao(): UploadQueueDao

    companion object {
        @Volatile
        private var INSTANCE: RetroTokDatabase? = null

        /**
         * Preserves existing `songs` table while introducing the persistent `videos` table
         * and its query indices for API 23+ SQLite compatibility.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `videos` (
                        `videoId` TEXT NOT NULL,
                        `nickname` TEXT NOT NULL,
                        `caption` TEXT NOT NULL,
                        `hashtags` TEXT NOT NULL,
                        `localVideoPath` TEXT NOT NULL,
                        `thumbnailPath` TEXT,
                        `soundtrackTitle` TEXT NOT NULL,
                        `soundtrackArtist` TEXT NOT NULL,
                        `soundtrackId` TEXT,
                        `effectType` TEXT NOT NULL,
                        `duration` INTEGER NOT NULL,
                        `resolutionWidth` INTEGER NOT NULL,
                        `resolutionHeight` INTEGER NOT NULL,
                        `fileSize` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `isFavorite` INTEGER NOT NULL DEFAULT 0,
                        `likeCount` INTEGER NOT NULL DEFAULT 0,
                        `isLiked` INTEGER NOT NULL DEFAULT 0,
                        `isFeatured` INTEGER NOT NULL DEFAULT 0,
                        `publicationStatus` TEXT NOT NULL DEFAULT 'PUBLISHED',
                        PRIMARY KEY(`videoId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_createdAt` ON `videos` (`createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_isFavorite` ON `videos` (`isFavorite`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_isFeatured` ON `videos` (`isFeatured`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_likeCount` ON `videos` (`likeCount`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_publicationStatus` ON `videos` (`publicationStatus`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_soundtrackId` ON `videos` (`soundtrackId`)")
            }
        }

        /**
         * Adds content moderation fields (`moderationStatus`, `moderatedAt`, `moderationReason`)
         * to existing `videos` rows without losing any published records or metadata.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `videos` ADD COLUMN `moderationStatus` TEXT NOT NULL DEFAULT 'VISIBLE'")
                db.execSQL("ALTER TABLE `videos` ADD COLUMN `moderatedAt` INTEGER")
                db.execSQL("ALTER TABLE `videos` ADD COLUMN `moderationReason` TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_moderationStatus` ON `videos` (`moderationStatus`)")
            }
        }

        /**
         * Adds the persistent `upload_queue` table for offline-first cloud upload tracking,
         * preserving all existing `songs` and `videos` rows intact.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `upload_queue` (
                        `videoId` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `eventId` INTEGER,
                        `cloudVideoId` INTEGER,
                        `sha256Checksum` TEXT,
                        `storageKey` TEXT,
                        `qrToken` TEXT,
                        `viewingUrl` TEXT,
                        `attemptCount` INTEGER NOT NULL,
                        `lastAttemptAtMs` INTEGER,
                        `nextRetryAtMs` INTEGER,
                        `uploadedAt` TEXT,
                        `expiresAt` TEXT,
                        `lastErrorCode` TEXT,
                        `lastErrorMessage` TEXT,
                        `createdAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`videoId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_queue_status` ON `upload_queue` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_queue_eventId` ON `upload_queue` (`eventId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_queue_cloudVideoId` ON `upload_queue` (`cloudVideoId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_queue_nextRetryAtMs` ON `upload_queue` (`nextRetryAtMs`)")
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        fun getDatabase(context: Context): RetroTokDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RetroTokDatabase::class.java,
                    "retrotok_kiosk.db"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
