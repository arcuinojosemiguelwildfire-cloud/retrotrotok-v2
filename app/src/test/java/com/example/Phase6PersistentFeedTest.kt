package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioPreviewManager
import com.example.data.local.RetroTokDatabase
import com.example.data.local.entity.VideoEntity
import com.example.data.local.storage.MusicStorageManager
import com.example.data.mock.MockKioskSettingsRepository
import com.example.data.mock.MockMusicCatalogRepository
import com.example.data.repository.PublishVideoResult
import com.example.data.repository.VideoFeedRepositoryImpl
import com.example.data.repository.VideoSearchSort
import com.example.model.RetroDecade
import com.example.model.RetroEffectType
import com.example.model.SongItem
import com.example.ui.screens.admin.AdminViewModel
import com.example.video.TempRecordingMetadata
import com.example.video.VideoMuxerProcessor
import com.example.video.VideoStorageManager
import com.example.video.VideoThumbnailGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase6PersistentFeedTest {

    private lateinit var context: Context
    private lateinit var db: RetroTokDatabase
    private lateinit var videoStorageManager: VideoStorageManager
    private lateinit var musicStorageManager: MusicStorageManager
    private lateinit var thumbnailGenerator: VideoThumbnailGenerator
    private lateinit var videoMuxerProcessor: VideoMuxerProcessor
    private lateinit var repository: VideoFeedRepositoryImpl
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, RetroTokDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        videoStorageManager = VideoStorageManager(context)
        musicStorageManager = MusicStorageManager(context)
        thumbnailGenerator = VideoThumbnailGenerator(context)
        videoMuxerProcessor = VideoMuxerProcessor(context)
        repository = VideoFeedRepositoryImpl(
            videoDao = db.videoDao(),
            storageManager = videoStorageManager,
            thumbnailGenerator = thumbnailGenerator,
            videoInspector = videoMuxerProcessor
        )
        clearDirectoryContents(videoStorageManager.publishedDir)
        clearDirectoryContents(videoStorageManager.importedDir)
        clearDirectoryContents(videoStorageManager.thumbnailsDir)
        videoStorageManager.clearTempStorage()
    }

    @After
    fun tearDown() {
        clearDirectoryContents(videoStorageManager.publishedDir)
        clearDirectoryContents(videoStorageManager.importedDir)
        clearDirectoryContents(videoStorageManager.thumbnailsDir)
        videoStorageManager.clearTempStorage()
        db.close()
        Dispatchers.resetMain()
    }

    private fun clearDirectoryContents(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun writeValidMp4Stub(file: File, sizeBytes: Int = 256) {
        file.parentFile?.mkdirs()
        val bytes = ByteArray(sizeBytes)
        bytes[0] = 0x00
        bytes[1] = 0x00
        bytes[2] = 0x00
        bytes[3] = 0x18
        bytes[4] = 'f'.code.toByte()
        bytes[5] = 't'.code.toByte()
        bytes[6] = 'y'.code.toByte()
        bytes[7] = 'p'.code.toByte()
        bytes[8] = 'i'.code.toByte()
        bytes[9] = 's'.code.toByte()
        bytes[10] = 'o'.code.toByte()
        bytes[11] = 'm'.code.toByte()
        file.writeBytes(bytes)
    }

    private fun sampleSong(id: String = "song-80s-1", title: String = "Synthwave Fever"): SongItem {
        return SongItem(
            id = id,
            title = title,
            artist = "Neon Pulse",
            album = "Arcade Nights",
            releaseYear = 1984,
            decade = RetroDecade.DECADE_80S,
            genre = "Synthpop",
            durationSeconds = 180
        )
    }

    @Test
    fun testRoomMigration1To2PreservesSongsAndCreatesVideos() = runTest {
        val dbName = "migration_test_kiosk.db"
        context.deleteDatabase(dbName)

        // Create a v1 SQLite DB containing only the legacy `songs` table + 1 row
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `songs` (
                                `id` TEXT NOT NULL,
                                `title` TEXT NOT NULL,
                                `artist` TEXT NOT NULL,
                                `album` TEXT NOT NULL,
                                `releaseYear` INTEGER,
                                `decade` TEXT NOT NULL,
                                `genre` TEXT NOT NULL,
                                `durationMs` INTEGER NOT NULL,
                                `audioFileName` TEXT NOT NULL,
                                `audioRelativePath` TEXT NOT NULL,
                                `artworkRelativePath` TEXT,
                                `fileSizeBytes` INTEGER NOT NULL,
                                `sha256Hash` TEXT NOT NULL,
                                `importedAt` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL,
                                `isFavorite` INTEGER NOT NULL,
                                `isAvailable` INTEGER NOT NULL,
                                `licenseApprovalStatus` TEXT NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_songs_sha256Hash` ON `songs` (`sha256Hash`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_decade` ON `songs` (`decade`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_genre` ON `songs` (`genre`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_licenseApprovalStatus` ON `songs` (`licenseApprovalStatus`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_title_artist` ON `songs` (`title`, `artist`)")
                        db.execSQL(
                            """
                            INSERT INTO `songs` VALUES (
                                'legacy-song-1', 'Take On Me', 'a-ha', 'Hunting High and Low',
                                1985, 'DECADE_80S', 'Synthpop', 225000, 'takeonme.mp3',
                                'retrotok/music/audio/legacy-song-1.mp3', NULL, 4096, 'hash123',
                                1000, 1000, 0, 1, 'APPROVED_FOR_EVENT_USE'
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.close()

        // Open Room v2 database using MIGRATION_1_2
        val migratedDb = Room.databaseBuilder(context, RetroTokDatabase::class.java, dbName)
            .addMigrations(*RetroTokDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val preservedSongs = migratedDb.songDao().getAllSongs().first()
        assertEquals(1, preservedSongs.size)
        assertEquals("Take On Me", preservedSongs.first().title)

        // Verify `videos` table works after migration
        migratedDb.videoDao().insertVideo(
            VideoEntity(
                videoId = "vid-migrated-1",
                nickname = "KioskStar",
                caption = "Migrated test video",
                hashtags = "#RetroTok",
                localVideoPath = "retrotok/videos/published/pub_vid-migrated-1.mp4",
                soundtrackTitle = "Take On Me",
                soundtrackArtist = "a-ha",
                soundtrackId = "legacy-song-1",
                effectType = RetroEffectType.VHS_RETRO.name,
                duration = 30,
                resolutionWidth = 720,
                resolutionHeight = 1280,
                fileSize = 256L,
                createdAt = 5000L,
                updatedAt = 5000L
            )
        )
        val videos = migratedDb.videoDao().getPublishedVideos().first()
        assertEquals(1, videos.size)
        assertEquals("KioskStar", videos.first().nickname)

        migratedDb.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun testPublishTransactionPersistsAcrossSimulatedRestartNewestFirst() = runTest {
        val dbName = "restart_persist_test.db"
        context.deleteDatabase(dbName)

        val dbSession1 = Room.databaseBuilder(context, RetroTokDatabase::class.java, dbName)
            .addMigrations(*RetroTokDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        val repoSession1 = VideoFeedRepositoryImpl(
            videoDao = dbSession1.videoDao(),
            storageManager = videoStorageManager,
            thumbnailGenerator = thumbnailGenerator,
            videoInspector = videoMuxerProcessor
        )

        val recId = videoStorageManager.generateRecordingId()
        val rawFile = videoStorageManager.createTempRawVideoFile(recId)
        val muxFile = videoStorageManager.createTempMuxedVideoFile(recId)
        writeValidMp4Stub(rawFile)
        writeValidMp4Stub(muxFile, sizeBytes = 512)

        val result = repoSession1.publishRecordedVideo(
            sourceFile = muxFile,
            nickname = "DiscoQueen",
            caption = "Dancing under neon lights",
            hashtags = "#80s #Neon",
            song = sampleSong(),
            effect = RetroEffectType.NEON_80S,
            durationSeconds = 30
        )
        assertTrue(result is PublishVideoResult.Success)
        val published = (result as PublishVideoResult.Success).publishedVideo

        // Temp raw/mux artifacts must be cleaned up while published MP4 exists on disk
        assertFalse(rawFile.exists())
        assertFalse(muxFile.exists())
        val pubFile = videoStorageManager.getAbsoluteFile(published.localVideoPath!!)
        assertTrue(pubFile.exists())
        assertEquals(512L, pubFile.length())
        dbSession1.close()

        // Simulate app restart by reopening DB & creating fresh repository
        val dbSession2 = Room.databaseBuilder(context, RetroTokDatabase::class.java, dbName)
            .addMigrations(*RetroTokDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        val repoSession2 = VideoFeedRepositoryImpl(
            videoDao = dbSession2.videoDao(),
            storageManager = videoStorageManager,
            thumbnailGenerator = thumbnailGenerator,
            videoInspector = videoMuxerProcessor
        )

        val reloadedFeed = repoSession2.getFeedVideos().first()
        assertEquals(1, reloadedFeed.size)
        assertEquals(published.id, reloadedFeed.first().id)
        assertEquals("DiscoQueen", reloadedFeed.first().creatorNickname)
        assertTrue(reloadedFeed.first().isFileHealthy)

        dbSession2.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun testPersistentLikesEnsureLikedAndFavoritesPreventDuplicateIncrements() = runTest {
        val pubFile = File(videoStorageManager.publishedDir, "pub_like_test.mp4")
        writeValidMp4Stub(pubFile)

        db.videoDao().insertVideo(
            VideoEntity(
                videoId = "vid-like-1",
                nickname = "VibeCheck",
                caption = "Tap test",
                hashtags = "#RetroTok",
                localVideoPath = "retrotok/videos/published/${pubFile.name}",
                soundtrackTitle = "Synthwave Fever",
                soundtrackArtist = "Neon Pulse",
                effectType = RetroEffectType.VHS_RETRO.name,
                duration = 15,
                resolutionWidth = 720,
                resolutionHeight = 1280,
                fileSize = 256L,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )

        // Rapid double-taps call ensureLiked multiple times -> only +1 total
        val firstTap = repository.ensureLiked("vid-like-1")
        val secondTap = repository.ensureLiked("vid-like-1")
        val thirdTap = repository.ensureLiked("vid-like-1")
        assertNotNull(firstTap)
        assertTrue(firstTap!!.isLiked)
        assertEquals(1, firstTap.likeCount)
        assertEquals(1, secondTap!!.likeCount)
        assertEquals(1, thirdTap!!.likeCount)

        // Explicit heart button toggle unlikes cleanly (1 -> 0) and back (0 -> 1)
        val unliked = repository.toggleLike("vid-like-1")
        assertFalse(unliked!!.isLiked)
        assertEquals(0, unliked.likeCount)

        // Favorite toggle persists
        val fav = repository.toggleFavorite("vid-like-1")
        assertTrue(fav!!.isFavorited)
        val feedItem = repository.getFeedVideos().first().first()
        assertTrue(feedItem.isFavorited)
    }

    @Test
    fun testLocalVideoSearchAndSortFilters() = runTest {
        val f1 = File(videoStorageManager.publishedDir, "pub_s1.mp4").also { writeValidMp4Stub(it) }
        val f2 = File(videoStorageManager.publishedDir, "pub_s2.mp4").also { writeValidMp4Stub(it) }
        val f3 = File(videoStorageManager.publishedDir, "pub_s3.mp4").also { writeValidMp4Stub(it) }

        db.videoDao().insertVideo(
            VideoEntity(
                videoId = "s1",
                nickname = "AlphaDancer",
                caption = "Moonwalk groove",
                hashtags = "#80s #Moonwalk",
                localVideoPath = "retrotok/videos/published/${f1.name}",
                soundtrackTitle = "Billie Jean",
                soundtrackArtist = "MJ",
                effectType = RetroEffectType.FILM_90S.name,
                duration = 30,
                resolutionWidth = 720,
                resolutionHeight = 1280,
                fileSize = 256L,
                createdAt = 1000L,
                updatedAt = 1000L,
                likeCount = 5,
                isFavorite = false
            )
        )
        db.videoDao().insertVideo(
            VideoEntity(
                videoId = "s2",
                nickname = "BetaPop",
                caption = "Arcade rush",
                hashtags = "#Synth #Neon",
                localVideoPath = "retrotok/videos/published/${f2.name}",
                soundtrackTitle = "Take On Me",
                soundtrackArtist = "a-ha",
                effectType = RetroEffectType.VHS_RETRO.name,
                duration = 15,
                resolutionWidth = 720,
                resolutionHeight = 1280,
                fileSize = 256L,
                createdAt = 3000L,
                updatedAt = 3000L,
                likeCount = 25,
                isFavorite = true
            )
        )
        db.videoDao().insertVideo(
            VideoEntity(
                videoId = "s3",
                nickname = "GammaSpin",
                caption = "Disco spin",
                hashtags = "#70s #Disco",
                localVideoPath = "retrotok/videos/published/${f3.name}",
                soundtrackTitle = "Stayin Alive",
                soundtrackArtist = "Bee Gees",
                effectType = RetroEffectType.VINTAGE_70S.name,
                duration = 60,
                resolutionWidth = 720,
                resolutionHeight = 1280,
                fileSize = 256L,
                createdAt = 2000L,
                updatedAt = 2000L,
                likeCount = 12,
                isFavorite = false
            )
        )

        val newest = repository.searchVideos("", VideoSearchSort.NEWEST).first()
        assertEquals(listOf("s2", "s3", "s1"), newest.map { it.id })

        val mostLiked = repository.searchVideos("", VideoSearchSort.MOST_LIKED).first()
        assertEquals(listOf("s2", "s3", "s1"), mostLiked.map { it.id })

        val favorites = repository.searchVideos("", VideoSearchSort.FAVORITES).first()
        assertEquals(listOf("s2"), favorites.map { it.id })

        val byHashtag = repository.searchVideos("Moonwalk", VideoSearchSort.NEWEST).first()
        assertEquals(listOf("s1"), byHashtag.map { it.id })

        val bySoundtrack = repository.searchVideos("Bee Gees", VideoSearchSort.SOUNDTRACK).first()
        assertEquals(listOf("s3"), bySoundtrack.map { it.id })
    }

    @Test
    fun testReconciliationRecoversOrphansAndFlagsMissingWithoutDeletingValidFiles() = runTest {
        // 1. Orphan MP4 on disk without a Room record
        val orphanFile = File(videoStorageManager.publishedDir, "pub_orphan_999.mp4")
        writeValidMp4Stub(orphanFile, sizeBytes = 384)

        // 2. DB record whose local MP4 went missing
        db.videoDao().insertVideo(
            VideoEntity(
                videoId = "vid-missing-1",
                nickname = "GhostUser",
                caption = "Missing file entry",
                hashtags = "#RetroTok",
                localVideoPath = "retrotok/videos/published/does_not_exist.mp4",
                soundtrackTitle = "Neon Track",
                soundtrackArtist = "Synth",
                effectType = RetroEffectType.NONE.name,
                duration = 30,
                resolutionWidth = 720,
                resolutionHeight = 1280,
                fileSize = 300L,
                createdAt = 2000L,
                updatedAt = 2000L
            )
        )

        val report = repository.reconcileStorageAndDatabase()
        assertEquals(1, report.recoveredOrphanFiles)
        assertEquals(1, report.flaggedMissingRecords)
        assertTrue(orphanFile.exists())

        val adminVideos = repository.getAllAdminVideos().first()
        assertEquals(2, adminVideos.size)
        val recovered = adminVideos.firstOrNull { it.id == "orphan_999" }
        assertNotNull(recovered)
        assertTrue(recovered!!.isFileHealthy)

        val missing = adminVideos.firstOrNull { it.id == "vid-missing-1" }
        assertNotNull(missing)
        assertEquals("MISSING_FILE", missing!!.publicationStatus)
        assertFalse(missing.isFileHealthy)

        // Public feed excludes MISSING_FILE records
        val publicFeed = repository.getFeedVideos().first()
        assertEquals(1, publicFeed.size)
        assertEquals("orphan_999", publicFeed.first().id)
    }

    @Test
    fun testSafeDeleteRemovesVideoAndThumbnailPreservingMusicAndOtherVideos() = runTest {
        val vid1File = File(videoStorageManager.publishedDir, "pub_del1.mp4").also { writeValidMp4Stub(it) }
        val vid2File = File(videoStorageManager.publishedDir, "pub_keep2.mp4").also { writeValidMp4Stub(it) }
        val thumb1File = File(videoStorageManager.thumbnailsDir, "thumb_del1.jpg").also { it.writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val musicFile = File(context.filesDir, "retrotok/music/audio/song-1.mp3").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(5, 6, 7, 8))
        }

        db.videoDao().insertVideo(
            VideoEntity(
                videoId = "del1",
                nickname = "DeleteMe",
                caption = "To delete",
                hashtags = "#RetroTok",
                localVideoPath = "retrotok/videos/published/${vid1File.name}",
                thumbnailPath = "retrotok/videos/thumbnails/${thumb1File.name}",
                soundtrackTitle = "Track 1",
                soundtrackArtist = "Artist 1",
                soundtrackId = "song-1",
                effectType = RetroEffectType.NONE.name,
                duration = 30,
                resolutionWidth = 720,
                resolutionHeight = 1280,
                fileSize = 256L,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )
        db.videoDao().insertVideo(
            VideoEntity(
                videoId = "keep2",
                nickname = "KeepMe",
                caption = "Stay in feed",
                hashtags = "#RetroTok",
                localVideoPath = "retrotok/videos/published/${vid2File.name}",
                soundtrackTitle = "Track 1",
                soundtrackArtist = "Artist 1",
                soundtrackId = "song-1",
                effectType = RetroEffectType.NONE.name,
                duration = 30,
                resolutionWidth = 720,
                resolutionHeight = 1280,
                fileSize = 256L,
                createdAt = 2000L,
                updatedAt = 2000L
            )
        )

        repository.deleteVideo("del1")

        assertNull(db.videoDao().getVideoById("del1"))
        assertFalse(vid1File.exists())
        assertFalse(thumb1File.exists())

        // Unrelated video & original music catalog file remain completely intact
        assertNotNull(db.videoDao().getVideoById("keep2"))
        assertTrue(vid2File.exists())
        assertTrue(musicFile.exists())

        musicFile.delete()
    }

    @Test
    fun testStorageBreakdownAndSafeTransientCleanupPreservesRecoverableTakes() = runTest {
        val pubFile = File(videoStorageManager.publishedDir, "pub_stat1.mp4").also { writeValidMp4Stub(it, 400) }
        val impFile = File(videoStorageManager.importedDir, "imp_stat1.mp4").also { writeValidMp4Stub(it, 300) }
        val thumbFile = File(videoStorageManager.thumbnailsDir, "thumb_stat1.jpg").also { it.writeBytes(ByteArray(120)) }
        val stagingFile = File(videoStorageManager.tempDir, "comp_vid_staging.mp4").also { it.writeBytes(ByteArray(200)) }
        val recId = "rec_keep_1"
        val recoverableRaw = videoStorageManager.createTempRawVideoFile(recId).also { writeValidMp4Stub(it, 1500) }
        videoStorageManager.saveTempRecordingMetadata(
            TempRecordingMetadata(
                recordingId = recId,
                videoFileName = recoverableRaw.name,
                songId = "song-1",
                songTitle = "Save Me",
                songArtist = "Retro",
                effectName = RetroEffectType.VHS_RETRO.name,
                durationSeconds = 30,
                createdAtMs = 1000L,
                stage = "RAW"
            )
        )

        val adminVm = AdminViewModel(
            settingsRepository = MockKioskSettingsRepository(),
            videoFeedRepository = repository,
            musicCatalogRepository = MockMusicCatalogRepository(),
            audioPreviewManager = AudioPreviewManager(context),
            storageManager = musicStorageManager,
            videoStorageManager = videoStorageManager
        )

        val breakdownBefore = videoStorageManager.computeStorageBreakdown(musicBytes = 1024L)
        assertEquals(400L, breakdownBefore.publishedBytes)
        assertEquals(300L, breakdownBefore.importedBytes)
        assertEquals(120L, breakdownBefore.thumbnailBytes)
        assertEquals(200L, breakdownBefore.tempStagingBytes)
        assertEquals(1, breakdownBefore.recoverableCount)
        assertEquals(1024L, breakdownBefore.musicBytes)

        // Safe cleanup clears transient staging files ONLY, preserving recoverable takes and published/imported videos
        adminVm.clearTempStorage()
        val deadline = System.currentTimeMillis() + 5000L
        while (adminVm.uiState.value.lastStorageActionMessage == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertNotNull(adminVm.uiState.value.lastStorageActionMessage)
        assertFalse(stagingFile.exists())
        assertTrue(recoverableRaw.exists())
        assertTrue(pubFile.exists())
        assertTrue(impFile.exists())
        assertTrue(thumbFile.exists())
    }
}
