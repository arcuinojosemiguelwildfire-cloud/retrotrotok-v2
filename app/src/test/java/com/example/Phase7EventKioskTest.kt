package com.example

import android.content.Context
import android.hardware.usb.UsbDevice
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioPreviewManager
import com.example.camera.CameraBackendType
import com.example.camera.CameraCapabilities
import com.example.camera.CameraConnectionState
import com.example.camera.CameraDeviceInfo
import com.example.camera.CameraProvider
import com.example.camera.CameraRecordingController
import com.example.data.local.RetroTokDatabase
import com.example.data.local.entity.VideoEntity
import com.example.data.local.storage.MusicStorageManager
import com.example.data.mock.MockMusicCatalogRepository
import com.example.data.repository.LocalKioskSettingsRepository
import com.example.data.repository.ModerationFilter
import com.example.data.repository.VideoFeedRepositoryImpl
import com.example.model.EventModeStatus
import com.example.model.EventSummarySnapshot
import com.example.model.FeedSortOrder
import com.example.model.RetroDecade
import com.example.model.RetroEffectType
import com.example.model.SongItem
import com.example.session.GuestSessionManager
import com.example.ui.screens.admin.AdminViewModel
import com.example.ui.screens.feed.FeedFilterTab
import com.example.ui.screens.feed.FeedViewModel
import com.example.ui.screens.preview.PreviewViewModel
import com.example.ui.screens.studio.StudioState
import com.example.ui.screens.studio.StudioViewModel
import com.example.video.SoundtrackSessionController
import com.example.video.TempRecordingMetadata
import com.example.video.VideoMuxerProcessor
import com.example.video.VideoStorageManager
import com.example.video.VideoThumbnailGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class Phase7EventKioskTest {

    private lateinit var context: Context
    private lateinit var db: RetroTokDatabase
    private lateinit var videoStorageManager: VideoStorageManager
    private lateinit var musicStorageManager: MusicStorageManager
    private lateinit var thumbnailGenerator: VideoThumbnailGenerator
    private lateinit var videoMuxerProcessor: VideoMuxerProcessor
    private lateinit var videoFeedRepository: VideoFeedRepositoryImpl
    private lateinit var settingsRepository: LocalKioskSettingsRepository
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("retrotok_kiosk_settings", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, RetroTokDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        videoStorageManager = VideoStorageManager(context)
        musicStorageManager = MusicStorageManager(context)
        thumbnailGenerator = VideoThumbnailGenerator(context)
        videoMuxerProcessor = VideoMuxerProcessor(context)
        videoFeedRepository = VideoFeedRepositoryImpl(
            videoDao = db.videoDao(),
            storageManager = videoStorageManager,
            thumbnailGenerator = thumbnailGenerator,
            videoInspector = videoMuxerProcessor
        )
        settingsRepository = LocalKioskSettingsRepository(context)
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
        context.getSharedPreferences("retrotok_kiosk_settings", Context.MODE_PRIVATE).edit().clear().commit()
        db.close()
        Dispatchers.resetMain()
    }

    private fun clearDirectoryContents(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun awaitUntil(timeoutMs: Long = 5000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
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

    private fun createStubVideoEntity(
        id: String,
        nickname: String = "RetroGuest",
        likes: Int = 0,
        isFeatured: Boolean = false,
        isFavorite: Boolean = false,
        createdAt: Long = 1000L,
        durationSec: Int = 30
    ): VideoEntity {
        val file = File(videoStorageManager.publishedDir, "pub_${id}.mp4")
        writeValidMp4Stub(file, sizeBytes = 512)
        return VideoEntity(
            videoId = id,
            nickname = nickname,
            caption = "Dancing $id",
            hashtags = "#RetroTok",
            localVideoPath = "retrotok/videos/published/${file.name}",
            soundtrackTitle = "Synthwave Fever",
            soundtrackArtist = "Neon Pulse",
            soundtrackId = "song-80s-1",
            effectType = RetroEffectType.VHS_RETRO.name,
            duration = durationSec,
            resolutionWidth = 720,
            resolutionHeight = 1280,
            fileSize = 512L,
            createdAt = createdAt,
            updatedAt = createdAt,
            isFavorite = isFavorite,
            likeCount = likes,
            isLiked = likes > 0,
            isFeatured = isFeatured,
            publicationStatus = "PUBLISHED"
        )
    }

    private class FakeCameraProvider : CameraProvider {
        private val _availableCameras = MutableStateFlow<List<CameraDeviceInfo>>(emptyList())
        override val availableCameras: StateFlow<List<CameraDeviceInfo>> = _availableCameras.asStateFlow()

        private val _activeCamera = MutableStateFlow<CameraDeviceInfo?>(null)
        override val activeCamera: StateFlow<CameraDeviceInfo?> = _activeCamera.asStateFlow()

        private val _connectionState = MutableStateFlow(CameraConnectionState.CONNECTED)
        override val connectionState: StateFlow<CameraConnectionState> = _connectionState.asStateFlow()

        private val _selectedBackend = MutableStateFlow(CameraBackendType.NATIVE_USB_UVC)
        override val selectedBackend: StateFlow<CameraBackendType> = _selectedBackend.asStateFlow()

        private val _capabilities = MutableStateFlow(CameraCapabilities())
        override val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

        private val _errorMessage = MutableStateFlow<String?>(null)
        override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

        private var onDisconnect: (() -> Unit)? = null
        var reconnectOnRefresh: Boolean = true

        override fun initialize() {}

        override fun refreshDevices() {
            if (reconnectOnRefresh) {
                _errorMessage.value = null
                _connectionState.value = CameraConnectionState.CONNECTED
            }
        }

        override fun requestUsbPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
            onResult(true)
        }

        override fun setDisconnectionCallback(onDisconnect: () -> Unit) {
            this.onDisconnect = onDisconnect
        }

        fun simulateDisconnect(reason: String = "Logitech USB webcam unplugged") {
            _errorMessage.value = reason
            _connectionState.value = CameraConnectionState.DISCONNECTED
            onDisconnect?.invoke()
        }

        fun simulateReconnect() {
            _errorMessage.value = null
            _connectionState.value = CameraConnectionState.CONNECTED
        }

        override fun release() {}
    }

    @Test
    fun testRoomMigration2To3PreservesVideosAndDefaultsModerationVisible() = runTest {
        val dbName = "migration_2_to_3_test.db"
        context.deleteDatabase(dbName)

        // Create a v2 SQLite DB (matching MIGRATION_1_2 schema before Phase 7 moderation columns)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
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
                                `isFavorite` INTEGER NOT NULL,
                                `likeCount` INTEGER NOT NULL,
                                `isLiked` INTEGER NOT NULL,
                                `isFeatured` INTEGER NOT NULL,
                                `publicationStatus` TEXT NOT NULL,
                                PRIMARY KEY(`videoId`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_createdAt` ON `videos` (`createdAt`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_isFeatured` ON `videos` (`isFeatured`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_isFavorite` ON `videos` (`isFavorite`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_likeCount` ON `videos` (`likeCount`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_publicationStatus` ON `videos` (`publicationStatus`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_soundtrackId` ON `videos` (`soundtrackId`)")

                        db.execSQL(
                            """
                            INSERT INTO `videos` VALUES (
                                'legacy-v2-video', 'DiscoFan', 'Legacy clip', '#80s',
                                'retrotok/videos/published/pub_legacy.mp4', NULL,
                                'Take On Me', 'a-ha', 'song-1', 'VHS_RETRO',
                                30, 720, 1280, 1024, 1000, 1000,
                                0, 7, 1, 1, 'PUBLISHED'
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.close()

        val migratedDb = Room.databaseBuilder(context, RetroTokDatabase::class.java, dbName)
            .addMigrations(*RetroTokDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val videos = migratedDb.videoDao().getAllVideosOnce()
        assertEquals(1, videos.size)
        val preserved = videos.first()
        assertEquals("legacy-v2-video", preserved.videoId)
        assertEquals("VISIBLE", preserved.moderationStatus)
        assertNull(preserved.moderatedAt)
        assertNull(preserved.moderationReason)

        migratedDb.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun testContentModerationHidesFromGuestFeedAndRestoresWithoutDeletingFiles() = runTest {
        val visibleEntity = createStubVideoEntity("vid-vis-1", nickname = "VisibleStar", isFeatured = true, isFavorite = true, createdAt = 2000L)
        val modEntity = createStubVideoEntity("vid-mod-2", nickname = "HiddenGuest", isFeatured = true, isFavorite = true, createdAt = 3000L)
        db.videoDao().insertVideo(visibleEntity)
        db.videoDao().insertVideo(modEntity)

        val modFile = videoStorageManager.getAbsoluteFile(modEntity.localVideoPath)
        assertTrue(modFile.exists())

        videoFeedRepository.moderateVideo("vid-mod-2", hide = true, reason = "Guest requested removal")

        // Guest queries exclude hidden videos
        val guestFeed = videoFeedRepository.getFeedVideos().first()
        assertEquals(listOf("vid-vis-1"), guestFeed.map { it.id })

        val searchResults = videoFeedRepository.searchVideos("HiddenGuest").first()
        assertTrue(searchResults.isEmpty())

        val featuredGuest = db.videoDao().getFeaturedVideos().first()
        assertEquals(listOf("vid-vis-1"), featuredGuest.map { it.videoId })

        val favGuest = db.videoDao().getFavoriteVideos().first()
        assertEquals(listOf("vid-vis-1"), favGuest.map { it.videoId })

        // Admin still sees hidden video with reason & timestamp, and MP4 on disk is untouched
        val adminVideos = videoFeedRepository.getAllAdminVideos().first()
        val hiddenRecord = adminVideos.firstOrNull { it.id == "vid-mod-2" }
        assertNotNull(hiddenRecord)
        assertTrue(hiddenRecord!!.isHidden)
        assertEquals("HIDDEN", hiddenRecord.moderationStatus)
        assertEquals("Guest requested removal", hiddenRecord.moderationReason)
        assertNotNull(hiddenRecord.moderatedAt)
        assertTrue(modFile.exists())

        // Restore video back to guest feed
        videoFeedRepository.moderateVideo("vid-mod-2", hide = false, reason = null)
        val restoredFeed = videoFeedRepository.getFeedVideos().first()
        assertEquals(listOf("vid-mod-2", "vid-vis-1"), restoredFeed.map { it.id })
    }

    @Test
    fun testEventModePersistenceAndResetStatisticsPreservesVideos() = runTest {
        val v1 = createStubVideoEntity("vid-ev-1", likes = 4, isFavorite = true, createdAt = 5000L)
        db.videoDao().insertVideo(v1)
        val v1File = videoStorageManager.getAbsoluteFile(v1.localVideoPath)

        settingsRepository.startEvent(name = "Spring Gala", description = "80s Neon Prom")
        var current = settingsRepository.getSettings().first()
        assertEquals(EventModeStatus.ACTIVE, current.eventModeStatus)
        assertEquals("Spring Gala", current.eventName)
        assertEquals("80s Neon Prom", current.eventDescription)
        assertNotNull(current.eventStartedAtMs)
        assertNull(current.eventEndedAtMs)
        assertTrue(current.isAcceptingGuestRecordings)

        settingsRepository.pauseEvent()
        current = settingsRepository.getSettings().first()
        assertEquals(EventModeStatus.PAUSED, current.eventModeStatus)
        assertFalse(current.isAcceptingGuestRecordings)

        settingsRepository.resumeEvent()
        current = settingsRepository.getSettings().first()
        assertEquals(EventModeStatus.ACTIVE, current.eventModeStatus)
        assertTrue(current.isAcceptingGuestRecordings)

        val summary = EventSummarySnapshot(
            eventName = "Spring Gala",
            eventDescription = "80s Neon Prom",
            startedAtMs = current.eventStartedAtMs,
            endedAtMs = System.currentTimeMillis(),
            publishedVideoCount = 1,
            totalRecordingCount = 1,
            totalLikesCount = 4,
            totalFavoritesCount = 1,
            totalStorageBytes = 512L
        )
        settingsRepository.endEvent(summary)

        // Simulate app restart with a fresh repository instance reading SharedPreferences
        val restartedRepo = LocalKioskSettingsRepository(context)
        val persisted = restartedRepo.getSettings().first()
        assertEquals(EventModeStatus.ENDED, persisted.eventModeStatus)
        assertEquals("Spring Gala", persisted.eventName)
        assertEquals("80s Neon Prom", persisted.eventDescription)
        assertNotNull(persisted.eventEndedAtMs)
        assertNotNull(persisted.lastSavedEventSummary)
        assertEquals(1, persisted.lastSavedEventSummary!!.publishedVideoCount)
        assertEquals(4, persisted.lastSavedEventSummary!!.totalLikesCount)

        // Reset Event Statistics clears summary & updates reset timestamp WITHOUT deleting videos
        restartedRepo.resetEventStatistics()
        val afterReset = restartedRepo.getSettings().first()
        assertNull(afterReset.lastSavedEventSummary)
        assertTrue(afterReset.eventStatsResetAtMs > 0L)
        assertTrue(v1File.exists())
        assertEquals(1, db.videoDao().getAllVideosOnce().size)
    }

    @Test
    fun testGuestSessionIsolationPrivacyNoticeAndRequiredNicknameValidation() = runTest {
        val sessionManager = GuestSessionManager()
        val session1 = sessionManager.currentSession.value
        assertFalse(session1.hasAcknowledgedPrivacyNotice)
        assertEquals("", session1.nickname)

        sessionManager.acknowledgePrivacyNotice()
        sessionManager.updateNickname("GuestOne")
        assertTrue(sessionManager.currentSession.value.hasAcknowledgedPrivacyNotice)
        assertEquals("GuestOne", sessionManager.currentSession.value.nickname)

        val session2 = sessionManager.startNewSession()
        assertNotEquals(session1.sessionId, session2.sessionId)
        assertFalse(session2.hasAcknowledgedPrivacyNotice)
        assertEquals("", session2.nickname)

        // StudioViewModel resets guest selection cleanly on startNewGuestSession()
        val fakeCamera = FakeCameraProvider()
        val studioVm = StudioViewModel(
            cameraProvider = fakeCamera,
            recordingController = CameraRecordingController(context),
            soundtrackController = SoundtrackSessionController(context, musicStorageManager),
            storageManager = videoStorageManager,
            muxerProcessor = videoMuxerProcessor,
            settingsRepository = settingsRepository
        )
        studioVm.selectSong(sampleSong())
        studioVm.selectEffect(RetroEffectType.NEON_80S)
        studioVm.toggleMirror()
        studioVm.toggleGrid()
        assertNotNull(studioVm.uiState.value.selectedSong)
        assertEquals(RetroEffectType.NEON_80S, studioVm.uiState.value.selectedEffect)
        assertFalse(studioVm.uiState.value.isMirrored)
        assertTrue(studioVm.uiState.value.showGrid)

        studioVm.startNewGuestSession()
        assertNull(studioVm.uiState.value.selectedSong)
        assertEquals(RetroEffectType.NONE, studioVm.uiState.value.selectedEffect)
        assertTrue(studioVm.uiState.value.isMirrored)
        assertFalse(studioVm.uiState.value.showGrid)
        assertEquals(StudioState.READY, studioVm.uiState.value.studioState)

        // PreviewViewModel enforces requireGuestNickname cleanly when enabled
        settingsRepository.updateFeedControls(
            showFeaturedSection = true,
            enableGuestLikes = true,
            enableGuestFavorites = true,
            enableDiscover = true,
            allowGuestDeletion = false,
            defaultFeedSort = FeedSortOrder.NEWEST,
            requireGuestNickname = true
        )
        val previewVm = PreviewViewModel(
            videoFeedRepository = videoFeedRepository,
            videoStorageManager = videoStorageManager,
            settingsRepository = settingsRepository
        )
        val stubMuxed = File(videoStorageManager.tempDir, "mux_nick_test.mp4").also { writeValidMp4Stub(it) }
        previewVm.initialize(stubMuxed.absolutePath, sampleSong(), RetroEffectType.VHS_RETRO, 30)

        assertTrue(previewVm.uiState.value.requireNickname)
        previewVm.requestPublish()
        assertFalse(previewVm.uiState.value.showPublishModal)
        assertNotNull(previewVm.uiState.value.nicknameError)

        previewVm.onNicknameChange("Moonwalker")
        assertNull(previewVm.uiState.value.nicknameError)
        previewVm.requestPublish()
        assertTrue(previewVm.uiState.value.showPublishModal)

        previewVm.clearSessionState()
        assertEquals("", previewVm.uiState.value.guestNickname)
        assertEquals("", previewVm.uiState.value.videoFilePath)
    }

    @Test
    fun testFeedControlsSortOrderFeatureTogglesAndLikeFavoriteGuards() = runTest {
        val vOldLow = createStubVideoEntity("v-old-low", likes = 2, isFeatured = false, isFavorite = false, createdAt = 1000L)
        val vMidHigh = createStubVideoEntity("v-mid-high", likes = 20, isFeatured = false, isFavorite = true, createdAt = 2000L)
        val vOldFeat = createStubVideoEntity("v-old-feat", likes = 5, isFeatured = true, isFavorite = false, createdAt = 1500L)
        val vNewest = createStubVideoEntity("v-newest", likes = 1, isFeatured = false, isFavorite = false, createdAt = 3000L)
        db.videoDao().insertVideo(vOldLow)
        db.videoDao().insertVideo(vMidHigh)
        db.videoDao().insertVideo(vOldFeat)
        db.videoDao().insertVideo(vNewest)

        val feedVm = FeedViewModel(videoFeedRepository, settingsRepository)

        // Wait for initial async Room Flow emission
        awaitUntil { feedVm.uiState.value.videos.size == 4 }
        // Default sort: NEWEST
        assertEquals(listOf("v-newest", "v-mid-high", "v-old-feat", "v-old-low"), feedVm.uiState.value.videos.map { it.id })

        // Switch default sort to MOST_LIKED
        settingsRepository.updateFeedControls(
            showFeaturedSection = true,
            enableGuestLikes = true,
            enableGuestFavorites = true,
            enableDiscover = true,
            allowGuestDeletion = false,
            defaultFeedSort = FeedSortOrder.MOST_LIKED,
            requireGuestNickname = false
        )
        awaitUntil { feedVm.uiState.value.videos.firstOrNull()?.id == "v-mid-high" }
        assertEquals(listOf("v-mid-high", "v-old-feat", "v-old-low", "v-newest"), feedVm.uiState.value.videos.map { it.id })

        // Switch default sort to FEATURED_FIRST
        settingsRepository.updateFeedControls(
            showFeaturedSection = true,
            enableGuestLikes = true,
            enableGuestFavorites = true,
            enableDiscover = true,
            allowGuestDeletion = false,
            defaultFeedSort = FeedSortOrder.FEATURED_FIRST,
            requireGuestNickname = false
        )
        awaitUntil { feedVm.uiState.value.videos.firstOrNull()?.id == "v-old-feat" }
        assertEquals("v-old-feat", feedVm.uiState.value.videos.first().id)

        // Select FEATURED tab, then disable showFeaturedSection -> auto-fallback to FOR_YOU
        feedVm.selectFilterTab(FeedFilterTab.FEATURED)
        assertEquals(FeedFilterTab.FEATURED, feedVm.uiState.value.activeTab)

        settingsRepository.updateFeedControls(
            showFeaturedSection = false,
            enableGuestLikes = false,
            enableGuestFavorites = false,
            enableDiscover = false,
            allowGuestDeletion = false,
            defaultFeedSort = FeedSortOrder.NEWEST,
            requireGuestNickname = false
        )
        assertEquals(FeedFilterTab.FOR_YOU, feedVm.uiState.value.activeTab)
        assertFalse(feedVm.uiState.value.showFeaturedSection)
        assertFalse(feedVm.uiState.value.enableDiscover)

        // Disabled Likes / Favorites ignore guest actions cleanly
        val likesBefore = db.videoDao().getVideoById("v-newest")!!.likeCount
        feedVm.onToggleLike("v-newest")
        assertEquals(likesBefore, db.videoDao().getVideoById("v-newest")!!.likeCount)

        val favBefore = db.videoDao().getVideoById("v-newest")!!.isFavorite
        feedVm.onToggleFavorite("v-newest")
        assertEquals(favBefore, db.videoDao().getVideoById("v-newest")!!.isFavorite)
    }

    @Test
    fun testAdminMonitoringMetricsModerationFiltersAndAuthBoundary() = runTest {
        val visible = createStubVideoEntity("adm-v1", likes = 10, isFeatured = true, isFavorite = true, createdAt = 1000L, durationSec = 30)
        val hidden = createStubVideoEntity("adm-v2", likes = 5, isFeatured = false, isFavorite = false, createdAt = 2000L, durationSec = 15)
            .copy(moderationStatus = "HIDDEN", moderationReason = "Duplicate take", moderatedAt = 2500L)
        db.videoDao().insertVideo(visible)
        db.videoDao().insertVideo(hidden)

        val adminVm = AdminViewModel(
            settingsRepository = settingsRepository,
            videoFeedRepository = videoFeedRepository,
            musicCatalogRepository = MockMusicCatalogRepository(),
            audioPreviewManager = AudioPreviewManager(context),
            storageManager = musicStorageManager,
            videoStorageManager = videoStorageManager
        )

        // Verify PIN lockout protection works (5 bad attempts -> locked out)
        repeat(5) {
            adminVm.onDigitEntered("9")
            adminVm.onDigitEntered("9")
            adminVm.onDigitEntered("9")
            adminVm.onDigitEntered("9")
        }
        assertTrue(adminVm.uiState.value.isLockedOut)
        assertFalse(adminVm.uiState.value.isAuthenticated)

        val metrics = adminVm.uiState.value.eventMetrics
        assertEquals(2, metrics.publishedCount)
        assertEquals(1, metrics.visibleCount)
        assertEquals(1, metrics.hiddenCount)
        assertEquals(1, metrics.featuredCount)
        assertEquals(15, metrics.totalLikesCount)
        assertEquals(1, metrics.totalFavoritesCount)
        assertEquals(45, metrics.totalDurationSeconds)

        // Filter chips in Video Manager
        adminVm.updateModerationFilter(ModerationFilter.HIDDEN)
        assertEquals(listOf("adm-v2"), adminVm.uiState.value.filteredVideos.map { it.id })

        adminVm.updateModerationFilter(ModerationFilter.VISIBLE)
        assertEquals(listOf("adm-v1"), adminVm.uiState.value.filteredVideos.map { it.id })

        adminVm.logout()
        assertFalse(adminVm.uiState.value.isAuthenticated)
        assertEquals(2, db.videoDao().getAllVideosOnce().size)
    }

    @Test
    fun testUsbWebcamDisconnectionBlocksRecordingAndRecoversOnReconnect() = runTest {
        val fakeCamera = FakeCameraProvider()
        val studioVm = StudioViewModel(
            cameraProvider = fakeCamera,
            recordingController = CameraRecordingController(context),
            soundtrackController = SoundtrackSessionController(context, musicStorageManager),
            storageManager = videoStorageManager,
            muxerProcessor = videoMuxerProcessor,
            settingsRepository = settingsRepository
        )
        studioVm.selectSong(sampleSong())
        assertTrue(studioVm.uiState.value.isWebcamReady)
        assertFalse(studioVm.uiState.value.isCameraDisconnected)

        // Simulate external Logitech USB webcam unplug
        fakeCamera.simulateDisconnect()
        assertTrue(studioVm.uiState.value.isCameraDisconnected)
        assertFalse(studioVm.uiState.value.isWebcamReady)

        // Attempting to record while disconnected is blocked safely without entering COUNTDOWN
        studioVm.startCountdownAndRecord()
        assertEquals(StudioState.READY, studioVm.uiState.value.studioState)
        assertNotNull(studioVm.uiState.value.errorMessage)

        // Reconnect USB webcam and retry
        fakeCamera.simulateReconnect()
        studioVm.retryCameraConnection()
        assertFalse(studioVm.uiState.value.isCameraDisconnected)
        assertTrue(studioVm.uiState.value.isWebcamReady)
    }

    @Test
    fun testInterruptedSessionRecoveryPreservesUnfinishedTakeAcrossTransientCleanup() = runTest {
        val recId = "rec_powerloss_777"
        val rawFile = videoStorageManager.createTempRawVideoFile(recId).also { writeValidMp4Stub(it, 1500) }
        val stagingFile = File(videoStorageManager.tempDir, "comp_vid_transient_garbage.mp4").also { it.writeBytes(ByteArray(128)) }

        videoStorageManager.saveTempRecordingMetadata(
            TempRecordingMetadata(
                recordingId = recId,
                videoFileName = rawFile.name,
                songId = "song-80s-1",
                songTitle = "Synthwave Fever",
                songArtist = "Neon Pulse",
                effectName = RetroEffectType.VHS_RETRO.name,
                durationSeconds = 30,
                createdAtMs = 9999L,
                stage = "RAW"
            )
        )

        val adminVm = AdminViewModel(
            settingsRepository = settingsRepository,
            videoFeedRepository = videoFeedRepository,
            musicCatalogRepository = MockMusicCatalogRepository(),
            audioPreviewManager = AudioPreviewManager(context),
            storageManager = musicStorageManager,
            videoStorageManager = videoStorageManager
        )

        // Transient staging cleanup removes only abandoned non-recoverable staging artifacts
        adminVm.clearTempStorage()
        val deadline = System.currentTimeMillis() + 5000L
        while (adminVm.uiState.value.lastStorageActionMessage == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertNotNull(adminVm.uiState.value.lastStorageActionMessage)
        assertFalse(stagingFile.exists())

        // Recoverable take + metadata survive transient cleanup and simulated logout/restart
        val recoveredSessions = videoStorageManager.findRecoverableTempSessions()
        assertEquals(1, recoveredSessions.size)
        assertEquals(recId, recoveredSessions.first().metadata?.recordingId)
        assertTrue(rawFile.exists())
    }
}
