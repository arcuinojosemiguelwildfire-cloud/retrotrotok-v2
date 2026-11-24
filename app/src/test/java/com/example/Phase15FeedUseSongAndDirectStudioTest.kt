package com.example

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.camera.video.RecordingStats
import androidx.camera.video.VideoRecordEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioPreviewManager
import com.example.camera.CameraBackendType
import com.example.camera.CameraCapabilities
import com.example.camera.CameraConnectionState
import com.example.camera.CameraDeviceInfo
import com.example.camera.CameraProvider
import com.example.camera.CameraRecordingController
import com.example.camera.RecordingResult
import com.example.data.local.RetroTokDatabase
import com.example.data.local.entity.SongEntity
import com.example.data.local.entity.VideoEntity
import com.example.data.local.metadata.AudioMetadataExtractor
import com.example.data.local.storage.MusicStorageManager
import com.example.data.repository.ImportSummary
import com.example.data.repository.LocalKioskSettingsRepository
import com.example.data.repository.MusicCatalogRepository
import com.example.data.repository.MusicCatalogRepositoryImpl
import com.example.data.repository.MusicStorageMetrics
import com.example.data.repository.PublishVideoResult
import com.example.data.repository.VideoFeedRepositoryImpl
import com.example.model.RetroDecade
import com.example.model.RetroEffectType
import com.example.model.SongItem
import com.example.session.GuestSessionManager
import com.example.ui.navigation.Screen
import com.example.ui.screens.admin.AdminViewModel
import com.example.ui.screens.feed.FeedScreen
import com.example.ui.screens.feed.FeedViewModel
import com.example.ui.screens.preview.PreviewViewModel
import com.example.ui.screens.studio.StudioState
import com.example.ui.screens.studio.StudioViewModel
import com.example.ui.theme.RetroTokTheme
import com.example.video.CameraFramingMode
import com.example.video.MuxerResult
import com.example.video.SoundtrackSessionController
import com.example.video.VideoMuxerProcessor
import com.example.video.VideoStorageManager
import com.example.video.VideoThumbnailGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase15FeedUseSongAndDirectStudioTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: RetroTokDatabase
    private lateinit var musicStorageManager: MusicStorageManager
    private lateinit var videoStorageManager: VideoStorageManager
    private lateinit var musicCatalogRepository: MusicCatalogRepositoryImpl
    private lateinit var videoFeedRepository: VideoFeedRepositoryImpl
    private lateinit var settingsRepository: LocalKioskSettingsRepository
    private lateinit var soundtrackController: SoundtrackSessionController
    private val viewModelStore = ViewModelStore()
    private var vmCounter = 0

    private val testDispatcher = StandardTestDispatcher()

    private fun <T : ViewModel> trackVm(vm: T): T {
        viewModelStore.put("vm_${vmCounter++}", vm)
        return vm
    }

    private fun awaitCondition(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(20)
        }
        testDispatcher.scheduler.runCurrent()
        assertTrue("Timed out waiting for async condition", condition())
    }

    private class FakeCameraProvider : CameraProvider {
        private val _availableCameras = MutableStateFlow<List<CameraDeviceInfo>>(emptyList())
        override val availableCameras: StateFlow<List<CameraDeviceInfo>> = _availableCameras.asStateFlow()

        private val _activeCamera = MutableStateFlow<CameraDeviceInfo?>(null)
        override val activeCamera: StateFlow<CameraDeviceInfo?> = _activeCamera.asStateFlow()

        private val _connectionState = MutableStateFlow(CameraConnectionState.CONNECTED)
        override val connectionState: StateFlow<CameraConnectionState> = _connectionState.asStateFlow()

        private val _selectedBackend = MutableStateFlow(CameraBackendType.CAMERA2_INTERNAL)
        override val selectedBackend: StateFlow<CameraBackendType> = _selectedBackend.asStateFlow()

        private val _capabilities = MutableStateFlow(CameraCapabilities())
        override val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

        private val _errorMessage = MutableStateFlow<String?>(null)
        override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

        override fun initialize() {}
        override fun refreshDevices() {}
        override fun requestUsbPermission(device: android.hardware.usb.UsbDevice, onResult: (Boolean) -> Unit) {
            onResult(true)
        }
        override fun setDisconnectionCallback(onDisconnect: () -> Unit) {}
        override fun release() {}
    }

    private class FakeRecordingController(context: Context) : CameraRecordingController(context) {
        private val _fakeBound = MutableStateFlow(true)
        override val isVideoCaptureBound: StateFlow<Boolean> = _fakeBound.asStateFlow()

        private val _fakeRecording = MutableStateFlow(false)
        override val isRecording: StateFlow<Boolean> = _fakeRecording.asStateFlow()

        var activeSession = false
        var startCount = 0
        var lastOutputFile: File? = null
        var lastOnEvent: ((VideoRecordEvent) -> Unit)? = null
        var lastOnComplete: ((RecordingResult) -> Unit)? = null

        override fun hasActiveRecording(): Boolean = activeSession

        override fun startRecording(
            outputFile: File,
            onEvent: (VideoRecordEvent) -> Unit,
            onComplete: (RecordingResult) -> Unit
        ): Boolean {
            activeSession = true
            startCount++
            _fakeRecording.value = true
            lastOutputFile = outputFile
            lastOnEvent = onEvent
            lastOnComplete = onComplete
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(ByteArray(2048) { 0x33 })
            return true
        }

        override fun stopRecording() {
            activeSession = false
            _fakeRecording.value = false
        }

        override fun cancelRecording() {
            activeSession = false
            _fakeRecording.value = false
        }
    }

    private class FakeVideoMuxerProcessor(context: Context) : VideoMuxerProcessor(context) {
        var lastAudioFile: File? = null
        var muxCount = 0

        override suspend fun processAndMux(
            rawVideoFile: File,
            audioFile: File?,
            outputFile: File,
            durationMs: Long,
            effect: RetroEffectType,
            isMirrored: Boolean,
            framingMode: CameraFramingMode,
            onProgress: (Float, String) -> Unit
        ): MuxerResult {
            muxCount++
            lastAudioFile = audioFile
            outputFile.parentFile?.mkdirs()
            writeMinimalValidMp4(outputFile)
            onProgress(1.0f, "Done")
            return MuxerResult.Success(outputFile, durationMs, hasAudio = audioFile != null)
        }
    }

    private object TestVideoEvents {
        fun createStartEvent(outputFile: File): VideoRecordEvent.Start {
            val options = androidx.camera.video.FileOutputOptions.Builder(outputFile).build()
            val ctor = VideoRecordEvent.Start::class.java.declaredConstructors.first()
            ctor.isAccessible = true
            val params = ctor.parameterTypes
            val args = arrayOfNulls<Any>(params.size)
            for (i in params.indices) {
                args[i] = when {
                    androidx.camera.video.OutputOptions::class.java.isAssignableFrom(params[i]) -> options
                    RecordingStats::class.java.isAssignableFrom(params[i]) -> createRecordingStats()
                    else -> null
                }
            }
            return ctor.newInstance(*args) as VideoRecordEvent.Start
        }

        private fun createRecordingStats(): RecordingStats {
            val method = RecordingStats::class.java.declaredMethods.firstOrNull {
                it.name == "of" && it.parameterTypes.size == 3
            }
            if (method != null) {
                method.isAccessible = true
                val audioStatsCtor = androidx.camera.video.AudioStats::class.java.declaredMethods.first {
                    it.name == "of"
                }.apply { isAccessible = true }
                val audioArgs: Array<Any?> = audioStatsCtor.parameterTypes.map<Class<*>, Any?> { type ->
                    when (type) {
                        Int::class.javaPrimitiveType -> 0
                        Double::class.javaPrimitiveType -> 0.0
                        Long::class.javaPrimitiveType -> 0L
                        Float::class.javaPrimitiveType -> 0f
                        Boolean::class.javaPrimitiveType -> false
                        else -> null
                    }
                }.toTypedArray()
                val audioStats = audioStatsCtor.invoke(null, *audioArgs)
                return method.invoke(null, 0L, 0L, audioStats) as RecordingStats
            }
            throw IllegalStateException("Unable to construct RecordingStats")
        }
    }

    companion object {
        fun writeMinimalValidMp4(file: File) {
            file.parentFile?.mkdirs()
            val bytes = ByteArray(128)
            // ftyp box header
            bytes[3] = 20
            bytes[4] = 'f'.code.toByte()
            bytes[5] = 't'.code.toByte()
            bytes[6] = 'y'.code.toByte()
            bytes[7] = 'p'.code.toByte()
            bytes[8] = 'm'.code.toByte()
            bytes[9] = 'p'.code.toByte()
            bytes[10] = '4'.code.toByte()
            bytes[11] = '2'.code.toByte()
            file.writeBytes(bytes)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("retrotok_kiosk_settings", Context.MODE_PRIVATE).edit().clear().commit()
        com.example.di.AppContainer.init(context)

        db = Room.inMemoryDatabaseBuilder(context, RetroTokDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        musicStorageManager = MusicStorageManager(context)
        videoStorageManager = VideoStorageManager(context)
        musicCatalogRepository = MusicCatalogRepositoryImpl(
            context = context,
            songDao = db.songDao(),
            storageManager = musicStorageManager,
            metadataExtractor = AudioMetadataExtractor()
        )
        videoFeedRepository = VideoFeedRepositoryImpl(
            videoDao = db.videoDao(),
            storageManager = videoStorageManager,
            thumbnailGenerator = VideoThumbnailGenerator(context),
            videoInspector = FakeVideoMuxerProcessor(context)
        )
        settingsRepository = LocalKioskSettingsRepository(context)
        soundtrackController = SoundtrackSessionController(context, musicStorageManager)

        clearDirectory(musicStorageManager.audioDir)
        clearDirectory(videoStorageManager.publishedDir)
        clearDirectory(videoStorageManager.importedDir)
        videoStorageManager.clearTempStorage()
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        soundtrackController.release()
        clearDirectory(musicStorageManager.audioDir)
        clearDirectory(videoStorageManager.publishedDir)
        clearDirectory(videoStorageManager.importedDir)
        videoStorageManager.clearTempStorage()
        context.getSharedPreferences("retrotok_kiosk_settings", Context.MODE_PRIVATE).edit().clear().commit()
        db.close()
        Dispatchers.resetMain()
    }

    private fun clearDirectory(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
    }

    private suspend fun insertCatalogSong(
        id: String = "song-1984",
        title: String = "Neon Pulse",
        artist: String = "Synth Riders",
        album: String = "Midnight Arcade",
        year: Int = 1984,
        decade: String = "80s",
        genre: String = "Synthwave",
        approvalStatus: String = "APPROVED_FOR_EVENT_USE",
        isAvailable: Boolean = true,
        createAudioFile: Boolean = true,
        audioSizeBytes: Int = 2048
    ): SongEntity {
        val relPath = "retrotok/music/audio/$id.mp3"
        if (createAudioFile) {
            val file = musicStorageManager.getAbsoluteFile(relPath)
            file.parentFile?.mkdirs()
            file.writeBytes(ByteArray(audioSizeBytes) { 0x1A })
        }
        val entity = SongEntity(
            id = id,
            title = title,
            artist = artist,
            album = album,
            releaseYear = year,
            decade = decade,
            genre = genre,
            durationMs = 180_000L,
            audioFileName = "$id.mp3",
            audioRelativePath = relPath,
            artworkRelativePath = null,
            fileSizeBytes = audioSizeBytes.toLong(),
            sha256Hash = "hash-$id",
            isAvailable = isAvailable,
            licenseApprovalStatus = approvalStatus
        )
        db.songDao().insertSong(entity)
        return entity
    }

    private suspend fun insertFeedVideo(
        videoId: String = "vid-1",
        soundtrackId: String? = "song-1984",
        soundtrackTitle: String = "Neon Pulse",
        soundtrackArtist: String = "Synth Riders",
        publicationStatus: String = "PUBLISHED",
        nickname: String = "DiscoStar"
    ): VideoEntity {
        val relPath = "retrotok/videos/${if (publicationStatus == "IMPORTED") "imported" else "published"}/$videoId.mp4"
        writeMinimalValidMp4(videoStorageManager.getAbsoluteFile(relPath))
        val entity = VideoEntity(
            videoId = videoId,
            nickname = nickname,
            caption = "Dancing at the gala!",
            hashtags = "#RetroTok",
            localVideoPath = relPath,
            thumbnailPath = null,
            soundtrackTitle = soundtrackTitle,
            soundtrackArtist = soundtrackArtist,
            soundtrackId = soundtrackId,
            effectType = RetroEffectType.NEON_80S.name,
            duration = 30,
            resolutionWidth = 720,
            resolutionHeight = 1280,
            fileSize = 128L,
            createdAt = 10_000L,
            updatedAt = 10_000L,
            publicationStatus = publicationStatus
        )
        db.videoDao().insertVideo(entity)
        return entity
    }

    @Test
    fun test01_tappingFeedSongRetrievesCompleteCatalogRecordNotPartialFeedSong() = runTest(testDispatcher) {
        insertCatalogSong(
            id = "song-full-1",
            title = "Electric Dreams",
            artist = "Giorgio & Friends",
            album = "1984 Vault",
            year = 1984,
            decade = "80s",
            genre = "Italo Disco"
        )
        insertFeedVideo(
            videoId = "vid-full-1",
            soundtrackId = "song-full-1",
            soundtrackTitle = "Electric Dreams",
            soundtrackArtist = "Giorgio & Friends"
        )

        val feedVm = trackVm(
            FeedViewModel(
                repository = videoFeedRepository,
                settingsRepository = settingsRepository,
                musicCatalogRepository = musicCatalogRepository,
                musicStorageManager = musicStorageManager,
                ioDispatcher = testDispatcher
            )
        )
        awaitCondition { feedVm.uiState.value.videos.isNotEmpty() }

        val feedVideo = feedVm.uiState.value.videos.first()
        // Feed VideoItem.song is intentionally partial (missing audioRelativePath & album)
        assertEquals("song-full-1", feedVideo.song.id)
        assertEquals("", feedVideo.song.audioRelativePath)
        assertEquals("", feedVideo.song.album)

        var resolvedSong: SongItem? = null
        feedVm.onUseThisSongClicked(feedVideo) { resolvedSong = it }
        awaitCondition { resolvedSong != null && !feedVm.uiState.value.isResolvingFeedSong }

        assertNotNull(resolvedSong)
        assertEquals("song-full-1", resolvedSong!!.id)
        assertEquals("Electric Dreams", resolvedSong!!.title)
        assertEquals("Giorgio & Friends", resolvedSong!!.artist)
        assertEquals("1984 Vault", resolvedSong!!.album)
        assertEquals(1984, resolvedSong!!.releaseYear)
        assertEquals(RetroDecade.DECADE_80S, resolvedSong!!.decade)
        assertEquals("Italo Disco", resolvedSong!!.genre)
        assertEquals("retrotok/music/audio/song-full-1.mp3", resolvedSong!!.audioRelativePath)
        assertTrue(resolvedSong!!.isApprovedForGuestUse)
        assertTrue(musicStorageManager.getAbsoluteFile(resolvedSong!!.audioRelativePath).exists())
        assertNull(feedVm.uiState.value.songActionMessage)
    }

    @Test
    fun test02_validFeedSongOpensStudioDirectlyWithPreselectedSoundtrack() = runTest(testDispatcher) {
        insertCatalogSong(id = "song-2", title = "Funky Town", artist = "Lipps Crew")
        insertFeedVideo(
            videoId = "vid-2",
            soundtrackId = "song-2",
            soundtrackTitle = "Funky Town",
            soundtrackArtist = "Lipps Crew"
        )

        val feedVm = trackVm(
            FeedViewModel(
                repository = videoFeedRepository,
                settingsRepository = settingsRepository,
                musicCatalogRepository = musicCatalogRepository,
                musicStorageManager = musicStorageManager,
                ioDispatcher = testDispatcher
            )
        )
        val studioVm = trackVm(
            StudioViewModel(
                cameraProvider = FakeCameraProvider(),
                recordingController = FakeRecordingController(context),
                soundtrackController = soundtrackController,
                storageManager = videoStorageManager,
                muxerProcessor = FakeVideoMuxerProcessor(context),
                settingsRepository = settingsRepository
            )
        )
        val previewVm = trackVm(
            PreviewViewModel(
                videoFeedRepository = videoFeedRepository,
                videoStorageManager = videoStorageManager,
                settingsRepository = settingsRepository
            )
        )
        val adminVm = trackVm(
            AdminViewModel(
                settingsRepository = settingsRepository,
                videoFeedRepository = videoFeedRepository,
                musicCatalogRepository = musicCatalogRepository,
                audioPreviewManager = AudioPreviewManager(context),
                storageManager = musicStorageManager,
                videoStorageManager = videoStorageManager
            )
        )
        awaitCondition { feedVm.uiState.value.videos.isNotEmpty() }

        composeRule.setContent {
            RetroTokTheme {
                RetroTokKioskApp(
                    initialScreen = Screen.Feed,
                    feedViewModelOverride = feedVm,
                    studioViewModelOverride = studioVm,
                    previewViewModelOverride = previewVm,
                    adminViewModelOverride = adminVm,
                    skipStartupBackgroundSync = true
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("feed_use_song_action").assertIsDisplayed()
        composeRule.onNodeWithText("Use").assertIsDisplayed()
        composeRule.onNodeWithTag("feed_use_song_action").performClick()
        awaitCondition { !feedVm.uiState.value.isResolvingFeedSong && studioVm.uiState.value.selectedSong != null }
        advanceUntilIdle()
        composeRule.waitForIdle()

        // Proceeds directly to Dance Studio without "Before You Record" modal
        composeRule.onNodeWithTag("guest_privacy_notice_dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("dance_studio_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("studio_song_pill").assertIsDisplayed()
        composeRule.onNodeWithText("Funky Town - Lipps Crew").assertIsDisplayed()

        val selected = studioVm.uiState.value.selectedSong
        assertNotNull(selected)
        assertEquals("song-2", selected!!.id)
        assertEquals("retrotok/music/audio/song-2.mp3", selected.audioRelativePath)
        assertTrue(soundtrackController.hasActivePlayerInstance())
    }

    @Test
    fun test03_missingCatalogSongDoesNotOpenStudioAndShowsFriendlyMessage() = runTest(testDispatcher) {
        insertFeedVideo(
            videoId = "vid-missing-song",
            soundtrackId = "deleted-song-id",
            soundtrackTitle = "Ghost Track",
            soundtrackArtist = "Missing Artist"
        )

        val feedVm = trackVm(
            FeedViewModel(
                repository = videoFeedRepository,
                settingsRepository = settingsRepository,
                musicCatalogRepository = musicCatalogRepository,
                musicStorageManager = musicStorageManager,
                ioDispatcher = testDispatcher
            )
        )
        val studioVm = trackVm(
            StudioViewModel(
                cameraProvider = FakeCameraProvider(),
                recordingController = FakeRecordingController(context),
                soundtrackController = soundtrackController,
                storageManager = videoStorageManager,
                muxerProcessor = FakeVideoMuxerProcessor(context),
                settingsRepository = settingsRepository
            )
        )
        val previewVm = trackVm(PreviewViewModel(videoFeedRepository, videoStorageManager, settingsRepository))
        val adminVm = trackVm(
            AdminViewModel(
                settingsRepository,
                videoFeedRepository,
                musicCatalogRepository,
                AudioPreviewManager(context),
                musicStorageManager,
                videoStorageManager
            )
        )
        awaitCondition { feedVm.uiState.value.videos.isNotEmpty() }

        composeRule.setContent {
            RetroTokTheme {
                RetroTokKioskApp(
                    initialScreen = Screen.Feed,
                    feedViewModelOverride = feedVm,
                    studioViewModelOverride = studioVm,
                    previewViewModelOverride = previewVm,
                    adminViewModelOverride = adminVm,
                    skipStartupBackgroundSync = true
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("feed_use_song_action").performClick()
        awaitCondition { !feedVm.uiState.value.isResolvingFeedSong && feedVm.uiState.value.songActionMessage != null }
        advanceUntilIdle()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("dance_studio_screen").assertDoesNotExist()
        composeRule.onNodeWithTag("feed_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("feed_song_error_banner").assertIsDisplayed()
        assertNull(studioVm.uiState.value.selectedSong)
        assertTrue(feedVm.uiState.value.songActionMessage!!.contains("no longer available", ignoreCase = true))
    }

    @Test
    fun test04_unapprovedOrUnavailableSongIsRejectedWithoutEnteringStudio() = runTest(testDispatcher) {
        insertCatalogSong(
            id = "song-unapproved",
            title = "Pending Track",
            artist = "Artist A",
            approvalStatus = "UNREVIEWED",
            isAvailable = true
        )
        insertCatalogSong(
            id = "song-retired",
            title = "Retired Track",
            artist = "Artist B",
            approvalStatus = "APPROVED_FOR_EVENT_USE",
            isAvailable = false
        )
        insertFeedVideo(
            videoId = "vid-unapproved",
            soundtrackId = "song-unapproved",
            soundtrackTitle = "Pending Track",
            soundtrackArtist = "Artist A"
        )
        insertFeedVideo(
            videoId = "vid-retired",
            soundtrackId = "song-retired",
            soundtrackTitle = "Retired Track",
            soundtrackArtist = "Artist B"
        )

        val feedVm = trackVm(
            FeedViewModel(
                repository = videoFeedRepository,
                settingsRepository = settingsRepository,
                musicCatalogRepository = musicCatalogRepository,
                musicStorageManager = musicStorageManager,
                ioDispatcher = testDispatcher
            )
        )
        awaitCondition { feedVm.uiState.value.videos.size == 2 }

        val videosById = feedVm.uiState.value.videos.associateBy { it.id }
        var resolvedCount = 0

        feedVm.onUseThisSongClicked(videosById.getValue("vid-unapproved")) { resolvedCount++ }
        awaitCondition { !feedVm.uiState.value.isResolvingFeedSong && feedVm.uiState.value.songActionMessage != null }
        assertEquals(0, resolvedCount)
        assertTrue(feedVm.uiState.value.songActionMessage!!.contains("not approved", ignoreCase = true))

        feedVm.onUseThisSongClicked(videosById.getValue("vid-retired")) { resolvedCount++ }
        awaitCondition { !feedVm.uiState.value.isResolvingFeedSong && feedVm.uiState.value.songActionMessage != null }
        assertEquals(0, resolvedCount)
        assertTrue(feedVm.uiState.value.songActionMessage!!.contains("removed", ignoreCase = true))
    }

    @Test
    fun test05_missingOrEmptyAudioFileIsHandledSafely() = runTest(testDispatcher) {
        insertCatalogSong(
            id = "song-no-file",
            title = "No File Song",
            artist = "Artist C",
            createAudioFile = false
        )
        insertCatalogSong(
            id = "song-empty-file",
            title = "Zero Byte Song",
            artist = "Artist D",
            createAudioFile = true,
            audioSizeBytes = 0
        )
        insertFeedVideo(
            videoId = "vid-no-file",
            soundtrackId = "song-no-file",
            soundtrackTitle = "No File Song",
            soundtrackArtist = "Artist C"
        )
        insertFeedVideo(
            videoId = "vid-empty-file",
            soundtrackId = "song-empty-file",
            soundtrackTitle = "Zero Byte Song",
            soundtrackArtist = "Artist D"
        )

        val feedVm = trackVm(
            FeedViewModel(
                repository = videoFeedRepository,
                settingsRepository = settingsRepository,
                musicCatalogRepository = musicCatalogRepository,
                musicStorageManager = musicStorageManager,
                ioDispatcher = testDispatcher
            )
        )
        awaitCondition { feedVm.uiState.value.videos.size == 2 }

        val videosById = feedVm.uiState.value.videos.associateBy { it.id }
        var resolvedCount = 0

        feedVm.onUseThisSongClicked(videosById.getValue("vid-no-file")) { resolvedCount++ }
        awaitCondition { !feedVm.uiState.value.isResolvingFeedSong && feedVm.uiState.value.songActionMessage != null }
        assertEquals(0, resolvedCount)
        assertTrue(feedVm.uiState.value.songActionMessage!!.contains("missing or corrupted", ignoreCase = true))

        feedVm.onUseThisSongClicked(videosById.getValue("vid-empty-file")) { resolvedCount++ }
        awaitCondition { !feedVm.uiState.value.isResolvingFeedSong && feedVm.uiState.value.songActionMessage != null }
        assertEquals(0, resolvedCount)
        assertTrue(feedVm.uiState.value.songActionMessage!!.contains("missing or corrupted", ignoreCase = true))
    }

    @Test
    fun test06_importedVideosWithoutSoundtrackIdDoNotOfferUseThisSong() = runTest(testDispatcher) {
        insertFeedVideo(
            videoId = "imp-original-1",
            soundtrackId = null,
            soundtrackTitle = "Original Audio",
            soundtrackArtist = "ImportedGuest",
            publicationStatus = "IMPORTED"
        )

        val feedVm = trackVm(
            FeedViewModel(
                repository = videoFeedRepository,
                settingsRepository = settingsRepository,
                musicCatalogRepository = musicCatalogRepository,
                musicStorageManager = musicStorageManager,
                ioDispatcher = testDispatcher
            )
        )
        awaitCondition { feedVm.uiState.value.videos.isNotEmpty() }

        var resolvedCount = 0
        composeRule.setContent {
            RetroTokTheme {
                val state = feedVm.uiState.value
                FeedScreen(
                    uiState = state,
                    onPageChanged = {},
                    onLikeClick = {},
                    onFavoriteClick = {},
                    onDoubleTapLike = { _, _ -> },
                    onRemoveHeart = {},
                    onPlayPauseToggle = {},
                    onDanceStudioClick = {},
                    onAdminTrigger = {},
                    onUseSongClick = { video ->
                        feedVm.onUseThisSongClicked(video) { resolvedCount++ }
                    }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("feed_use_song_action").assertDoesNotExist()
        composeRule.onNodeWithText("Use").assertDoesNotExist()
        composeRule.onNodeWithTag("feed_song_label_readonly").assertIsDisplayed()
        composeRule.onNodeWithTag("feed_song_label_readonly").assertHasNoClickAction()
        composeRule.onNodeWithText("Original Audio - ImportedGuest").assertIsDisplayed()

        // Defensive check even if ViewModel method is invoked directly
        feedVm.onUseThisSongClicked(feedVm.uiState.value.videos.first()) { resolvedCount++ }
        advanceUntilIdle()
        assertEquals(0, resolvedCount)
        assertNotNull(feedVm.uiState.value.songActionMessage)
    }

    @Test
    fun test07_rapidRepeatedTapsDoNotCreateDuplicateRecordingSessions() = runTest(testDispatcher) {
        val audioRel = "retrotok/music/audio/song-rapid.mp3"
        musicStorageManager.getAbsoluteFile(audioRel).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(2048) { 0x2A })
        }
        val fullSong = SongItem(
            id = "song-rapid",
            title = "Rapid Beat",
            artist = "DJ Lock",
            album = "Vault",
            releaseYear = 1988,
            decade = RetroDecade.DECADE_80S,
            genre = "Pop",
            durationSeconds = 180,
            audioRelativePath = audioRel,
            licenseApprovalStatus = "APPROVED_FOR_EVENT_USE",
            isAvailable = true
        )
        val gate = CompletableDeferred<SongItem?>()
        val lookupCount = AtomicInteger(0)
        val slowCatalogRepo = object : MusicCatalogRepository {
            override fun getAllSongs(): Flow<List<SongItem>> = emptyFlow()
            override fun getApprovedSongs(): Flow<List<SongItem>> = emptyFlow()
            override fun getSongsByDecade(decade: RetroDecade): Flow<List<SongItem>> = emptyFlow()
            override fun getApprovedSongsByDecade(decade: RetroDecade): Flow<List<SongItem>> = emptyFlow()
            override fun searchSongs(query: String): Flow<List<SongItem>> = emptyFlow()
            override fun searchApprovedSongs(query: String): Flow<List<SongItem>> = emptyFlow()
            override suspend fun getSongById(songId: String): SongItem? {
                lookupCount.incrementAndGet()
                return gate.await()
            }
            override suspend fun importAudioFiles(uris: List<Uri>, onProgress: (Int, Int, String) -> Unit): ImportSummary =
                ImportSummary(0, 0, 0, 0, emptyList())
            override suspend fun updateSongMetadata(songId: String, title: String, artist: String, album: String, year: Int?, genre: String, decade: RetroDecade) = false
            override suspend fun updateLicenseStatus(songId: String, newStatus: String) = false
            override suspend fun deleteSong(songId: String, isReferencedByVideos: Boolean) = false
            override fun getSongMetrics(): Flow<MusicStorageMetrics> = emptyFlow()
        }

        insertFeedVideo(
            videoId = "vid-rapid",
            soundtrackId = "song-rapid",
            soundtrackTitle = "Rapid Beat",
            soundtrackArtist = "DJ Lock"
        )

        val feedVm = trackVm(
            FeedViewModel(
                repository = videoFeedRepository,
                settingsRepository = settingsRepository,
                musicCatalogRepository = slowCatalogRepo,
                musicStorageManager = musicStorageManager,
                ioDispatcher = testDispatcher
            )
        )
        awaitCondition { feedVm.uiState.value.videos.isNotEmpty() }

        val video = feedVm.uiState.value.videos.first()
        var sessionNavigations = 0

        // Simulate 5 rapid taps while catalog lookup is in-flight
        repeat(5) {
            feedVm.onUseThisSongClicked(video) { sessionNavigations++ }
        }
        runCurrent()
        assertTrue(feedVm.uiState.value.isResolvingFeedSong)
        assertEquals(1, lookupCount.get())

        gate.complete(fullSong)
        advanceUntilIdle()

        assertEquals(1, sessionNavigations)
        assertEquals(1, lookupCount.get())
        assertFalse(feedVm.uiState.value.isResolvingFeedSong)
    }

    @Test
    fun test08_and_test09_preselectedSongPersistsWhenRecordingStartsAndSurvivesRetake() = runTest(testDispatcher) {
        insertCatalogSong(id = "song-retake", title = "Take On Me", artist = "a-ha")
        insertFeedVideo(
            videoId = "vid-retake",
            soundtrackId = "song-retake",
            soundtrackTitle = "Take On Me",
            soundtrackArtist = "a-ha"
        )

        val recordingController = FakeRecordingController(context)
        val muxer = FakeVideoMuxerProcessor(context)
        val feedVm = trackVm(
            FeedViewModel(
                repository = videoFeedRepository,
                settingsRepository = settingsRepository,
                musicCatalogRepository = musicCatalogRepository,
                musicStorageManager = musicStorageManager,
                ioDispatcher = testDispatcher
            )
        )
        val studioVm = trackVm(
            StudioViewModel(
                cameraProvider = FakeCameraProvider(),
                recordingController = recordingController,
                soundtrackController = soundtrackController,
                storageManager = videoStorageManager,
                muxerProcessor = muxer,
                settingsRepository = settingsRepository
            )
        )
        val previewVm = trackVm(PreviewViewModel(videoFeedRepository, videoStorageManager, settingsRepository))
        val adminVm = trackVm(
            AdminViewModel(
                settingsRepository,
                videoFeedRepository,
                musicCatalogRepository,
                AudioPreviewManager(context),
                musicStorageManager,
                videoStorageManager
            )
        )
        awaitCondition { feedVm.uiState.value.videos.isNotEmpty() }

        composeRule.setContent {
            RetroTokTheme {
                RetroTokKioskApp(
                    initialScreen = Screen.Feed,
                    feedViewModelOverride = feedVm,
                    studioViewModelOverride = studioVm,
                    previewViewModelOverride = previewVm,
                    adminViewModelOverride = adminVm,
                    skipStartupBackgroundSync = true
                )
            }
        }
        composeRule.waitForIdle()

        // 1. Tap song from feed -> enters Studio directly with preselected song
        composeRule.onNodeWithTag("feed_use_song_action").performClick()
        awaitCondition { !feedVm.uiState.value.isResolvingFeedSong && studioVm.uiState.value.selectedSong != null }
        advanceUntilIdle()
        composeRule.waitForIdle()

        val selectedBeforeRecord = studioVm.uiState.value.selectedSong
        assertNotNull(selectedBeforeRecord)
        assertEquals("song-retake", selectedBeforeRecord!!.id)
        val initialPlayer = soundtrackController.currentPlayerInstance()
        assertNotNull(initialPlayer)

        // 2. Start recording take 1 -> countdown 3s -> VideoRecordEvent.Start
        studioVm.startCountdownAndRecord()
        testScheduler.advanceTimeBy(3001)
        runCurrent()
        assertEquals(StudioState.INITIALIZING_CAMERA, studioVm.uiState.value.studioState)
        val rawFile1 = recordingController.lastOutputFile!!
        recordingController.lastOnEvent?.invoke(TestVideoEvents.createStartEvent(rawFile1))
        runCurrent()

        assertEquals(StudioState.RECORDING, studioVm.uiState.value.studioState)
        assertEquals("song-retake", studioVm.uiState.value.selectedSong?.id)
        assertTrue(soundtrackController.isPlayWhenReadyRequested())
        assertEquals(1f, soundtrackController.currentVolume(), 0.01f)

        // 3. Stop and finalize take 1 -> navigates to Preview
        studioVm.stopRecordingManually()
        runCurrent()
        recordingController.lastOnComplete?.invoke(RecordingResult.Success(rawFile1, 4_000L))
        awaitCondition { studioVm.uiState.value.studioState == StudioState.PREVIEW_READY }
        advanceUntilIdle()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("video_preview_screen").assertIsDisplayed()
        assertEquals("song-retake", previewVm.uiState.value.song.id)
        assertNotNull(muxer.lastAudioFile)
        assertEquals(musicStorageManager.getAbsoluteFile("retrotok/music/audio/song-retake.mp3").absolutePath, muxer.lastAudioFile?.absolutePath)

        // 4. Retake from Preview -> returns to Studio with preselected song preserved & player re-prepared
        composeRule.onNodeWithTag("preview_retake_button_bottom").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("discard_confirm_button").performClick()
        advanceUntilIdle()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("dance_studio_screen").assertIsDisplayed()
        composeRule.onNodeWithText("Take On Me - a-ha").assertIsDisplayed()
        assertEquals("song-retake", studioVm.uiState.value.selectedSong?.id)
        assertEquals(StudioState.READY, studioVm.uiState.value.studioState)
        assertSame(initialPlayer, soundtrackController.currentPlayerInstance())
        assertNotEqualsIdleState(soundtrackController.currentPlaybackState())

        // 5. Record take 2 -> verify music still starts and plays cleanly
        studioVm.startCountdownAndRecord()
        testScheduler.advanceTimeBy(3001)
        runCurrent()
        val rawFile2 = recordingController.lastOutputFile!!
        recordingController.lastOnEvent?.invoke(TestVideoEvents.createStartEvent(rawFile2))
        runCurrent()
        assertEquals(StudioState.RECORDING, studioVm.uiState.value.studioState)
        assertTrue(soundtrackController.isPlayWhenReadyRequested())
        assertEquals("song-retake", studioVm.uiState.value.selectedSong?.id)
    }

    private fun assertNotEqualsIdleState(state: Int) {
        assertTrue("Expected player prepared (not STATE_IDLE), was $state", state != Player.STATE_IDLE)
    }

    @Test
    fun test10_and_test11_beforeYouRecordModalRemovedWhileDisclosuresAndConsentRemainIntact() = runTest(testDispatcher) {
        settingsRepository.updateFeedControls(
            showFeaturedSection = true,
            enableGuestLikes = true,
            enableGuestFavorites = true,
            enableDiscover = true,
            allowGuestDeletion = false,
            defaultFeedSort = com.example.model.FeedSortOrder.NEWEST,
            requireGuestNickname = true
        )

        val studioVm = trackVm(
            StudioViewModel(
                cameraProvider = FakeCameraProvider(),
                recordingController = FakeRecordingController(context),
                soundtrackController = soundtrackController,
                storageManager = videoStorageManager,
                muxerProcessor = FakeVideoMuxerProcessor(context),
                settingsRepository = settingsRepository
            )
        )
        val feedVm = trackVm(
            FeedViewModel(
                repository = videoFeedRepository,
                settingsRepository = settingsRepository,
                musicCatalogRepository = musicCatalogRepository,
                musicStorageManager = musicStorageManager,
                ioDispatcher = testDispatcher
            )
        )
        val previewVm = trackVm(PreviewViewModel(videoFeedRepository, videoStorageManager, settingsRepository))
        val adminVm = trackVm(
            AdminViewModel(
                settingsRepository,
                videoFeedRepository,
                musicCatalogRepository,
                AudioPreviewManager(context),
                musicStorageManager,
                videoStorageManager
            )
        )
        awaitCondition { adminVm.uiState.value.settings.requireGuestNickname }

        composeRule.setContent {
            RetroTokTheme {
                RetroTokKioskApp(
                    initialScreen = Screen.Welcome,
                    feedViewModelOverride = feedVm,
                    studioViewModelOverride = studioVm,
                    previewViewModelOverride = previewVm,
                    adminViewModelOverride = adminVm,
                    skipStartupBackgroundSync = true
                )
            }
        }
        composeRule.waitForIdle()

        // Welcome screen keeps its local-storage disclosure
        composeRule.onNodeWithText("100% Local Event Storage • No Internet or Cloud Uploads").assertIsDisplayed()

        // Tapping STEP UP & DANCE goes straight to Dance Studio with NO "Before You Record" modal
        composeRule.onNodeWithTag("welcome_start_button").performClick()
        advanceUntilIdle()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("guest_privacy_notice_dialog").assertDoesNotExist()
        composeRule.onNodeWithText("Before You Record").assertDoesNotExist()
        composeRule.onNodeWithTag("dance_studio_screen").assertIsDisplayed()

        // GuestSessionManager consent state still works intact
        val sessionManager = GuestSessionManager()
        assertFalse(sessionManager.currentSession.value.hasAcknowledgedPrivacyNotice)
        sessionManager.acknowledgePrivacyNotice()
        assertTrue(sessionManager.currentSession.value.hasAcknowledgedPrivacyNotice)

        // Preview publishing privacy disclosure & mandatory nickname consent remain intact
        val song = SongItem(
            id = "s-disclosure",
            title = "Stayin Alive",
            artist = "Bee Gees",
            album = "Saturday Night",
            releaseYear = 1977,
            decade = RetroDecade.DECADE_70S,
            genre = "Disco",
            durationSeconds = 30
        )
        val stubFile = File(videoStorageManager.tempDir, "preview_consent.mp4").also { writeMinimalValidMp4(it) }
        previewVm.initialize(stubFile.absolutePath, song, RetroEffectType.NONE, 30)

        // Attempt publish without required nickname -> blocked cleanly
        previewVm.requestPublish()
        assertFalse(previewVm.uiState.value.showPublishModal)
        assertNotNull(previewVm.uiState.value.nicknameError)

        previewVm.onNicknameChange("GalaGuest")
        previewVm.requestPublish()
        assertTrue(previewVm.uiState.value.showPublishModal)
    }

    @Test
    fun test12_normalRecordingFlowWithoutUseThisSongStillWorks() = runTest(testDispatcher) {
        insertCatalogSong(id = "song-manual", title = "Blue Monday", artist = "New Order")

        val recordingController = FakeRecordingController(context)
        val muxer = FakeVideoMuxerProcessor(context)
        val feedVm = trackVm(
            FeedViewModel(
                repository = videoFeedRepository,
                settingsRepository = settingsRepository,
                musicCatalogRepository = musicCatalogRepository,
                musicStorageManager = musicStorageManager,
                ioDispatcher = testDispatcher
            )
        )
        val studioVm = trackVm(
            StudioViewModel(
                cameraProvider = FakeCameraProvider(),
                recordingController = recordingController,
                soundtrackController = soundtrackController,
                storageManager = videoStorageManager,
                muxerProcessor = muxer,
                settingsRepository = settingsRepository
            )
        )
        val previewVm = trackVm(PreviewViewModel(videoFeedRepository, videoStorageManager, settingsRepository))
        val adminVm = trackVm(
            AdminViewModel(
                settingsRepository,
                videoFeedRepository,
                musicCatalogRepository,
                AudioPreviewManager(context),
                musicStorageManager,
                videoStorageManager
            )
        )
        awaitCondition { adminVm.uiState.value.songs.any { it.id == "song-manual" } }

        composeRule.setContent {
            RetroTokTheme {
                RetroTokKioskApp(
                    initialScreen = Screen.Welcome,
                    feedViewModelOverride = feedVm,
                    studioViewModelOverride = studioVm,
                    previewViewModelOverride = previewVm,
                    adminViewModelOverride = adminVm,
                    skipStartupBackgroundSync = true
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("welcome_start_button").performClick()
        advanceUntilIdle()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("dance_studio_screen").assertIsDisplayed()
        assertNull(studioVm.uiState.value.selectedSong)
        composeRule.onNodeWithText("Add sound").assertIsDisplayed()

        // Pick a song via Studio Song Picker
        composeRule.onNodeWithTag("studio_song_pill").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("song_picker_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("song_select_button_song-manual").performClick()
        advanceUntilIdle()
        composeRule.waitForIdle()

        assertEquals("song-manual", studioVm.uiState.value.selectedSong?.id)
        composeRule.onNodeWithText("Blue Monday - New Order").assertIsDisplayed()

        // Start & complete recording normally
        studioVm.startCountdownAndRecord()
        testScheduler.advanceTimeBy(3001)
        runCurrent()
        val rawFile = recordingController.lastOutputFile!!
        recordingController.lastOnEvent?.invoke(TestVideoEvents.createStartEvent(rawFile))
        runCurrent()
        assertTrue(soundtrackController.isPlayWhenReadyRequested())
        studioVm.stopRecordingManually()
        runCurrent()
        recordingController.lastOnComplete?.invoke(RecordingResult.Success(rawFile, 3_500L))
        awaitCondition { studioVm.uiState.value.studioState == StudioState.PREVIEW_READY }
        advanceUntilIdle()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("video_preview_screen").assertIsDisplayed()
        assertEquals("song-manual", previewVm.uiState.value.song.id)
    }

    @Test
    fun test13_feedLikePublishingAndPlaybackRemainIntactWithSoundtrackIdPreserved() = runTest(testDispatcher) {
        val songEntity = insertCatalogSong(id = "song-pub", title = "Vogue", artist = "Madonna")
        val composedFile = File(videoStorageManager.tempDir, "mux_pub_test.mp4").also {
            writeMinimalValidMp4(it)
        }
        val catalogSong = musicCatalogRepository.getSongById(songEntity.id)!!

        val pubResult = videoFeedRepository.publishRecordedVideo(
            sourceFile = composedFile,
            nickname = "Voguer",
            caption = "",
            hashtags = "",
            song = catalogSong,
            effect = RetroEffectType.VHS_RETRO,
            durationSeconds = 30
        )
        assertTrue(pubResult is PublishVideoResult.Success)
        val published = (pubResult as PublishVideoResult.Success).publishedVideo
        assertEquals("song-pub", published.song.id)

        val feedVm = trackVm(
            FeedViewModel(
                repository = videoFeedRepository,
                settingsRepository = settingsRepository,
                musicCatalogRepository = musicCatalogRepository,
                musicStorageManager = musicStorageManager,
                ioDispatcher = testDispatcher
            )
        )
        awaitCondition { feedVm.uiState.value.videos.isNotEmpty() }

        assertEquals(1, feedVm.uiState.value.videos.size)
        assertEquals(0, feedVm.uiState.value.videos.first().likeCount)

        feedVm.onToggleLike(published.id)
        awaitCondition { feedVm.uiState.value.videos.firstOrNull()?.isLiked == true }
        assertEquals(1, feedVm.uiState.value.videos.first().likeCount)
        assertTrue(feedVm.uiState.value.videos.first().isLiked)

        feedVm.onToggleFavorite(published.id)
        awaitCondition { feedVm.uiState.value.videos.firstOrNull()?.isFavorited == true }
        assertTrue(feedVm.uiState.value.videos.first().isFavorited)
        assertTrue(feedVm.uiState.value.videos.first().isFileHealthy)
    }
}
