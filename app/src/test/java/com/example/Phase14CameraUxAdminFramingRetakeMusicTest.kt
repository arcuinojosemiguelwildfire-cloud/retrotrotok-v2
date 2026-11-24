package com.example

import android.content.Context
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioPreviewManager
import com.example.camera.CameraBackendType
import com.example.camera.CameraCapabilities
import com.example.camera.CameraConnectionState
import com.example.camera.CameraDeviceInfo
import com.example.camera.CameraPreviewController
import com.example.camera.CameraProvider
import com.example.camera.CameraRecordingController
import com.example.camera.RecordingResult
import com.example.data.local.storage.MusicStorageManager
import com.example.data.mock.MockMusicCatalogRepository
import com.example.data.mock.MockVideoFeedRepository
import com.example.data.repository.LocalKioskSettingsRepository
import com.example.model.RetroDecade
import com.example.model.RetroEffectType
import com.example.model.SongItem
import com.example.ui.screens.admin.AdminDashboardScreen
import com.example.ui.screens.admin.AdminTab
import com.example.ui.screens.admin.AdminViewModel
import com.example.ui.screens.studio.DanceStudioScreen
import com.example.ui.screens.studio.StudioState
import com.example.ui.screens.studio.StudioUiState
import com.example.ui.screens.studio.StudioViewModel
import com.example.ui.theme.RetroTokTheme
import com.example.video.CameraFramingMode
import com.example.video.EffectShaderConfig
import com.example.video.MuxerResult
import com.example.video.SoundtrackSessionController
import com.example.video.VideoMuxerProcessor
import com.example.video.VideoStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase14CameraUxAdminFramingRetakeMusicTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var videoStorageManager: VideoStorageManager
    private lateinit var musicStorageManager: MusicStorageManager
    private lateinit var soundtrackController: SoundtrackSessionController
    private lateinit var audioFile: File
    private lateinit var testSong: SongItem

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

    private class SpyVideoMuxerProcessor(context: Context) : VideoMuxerProcessor(context) {
        val capturedFramingModes = mutableListOf<CameraFramingMode>()
        val capturedAudioFiles = mutableListOf<File?>()
        val capturedEffects = mutableListOf<RetroEffectType>()

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
            capturedFramingModes.add(framingMode)
            capturedAudioFiles.add(audioFile)
            capturedEffects.add(effect)
            outputFile.parentFile?.mkdirs()
            rawVideoFile.copyTo(outputFile, overwrite = true)
            onProgress(1.0f, "Done")
            return MuxerResult.Success(
                finalFile = outputFile,
                durationMs = durationMs,
                hasAudio = audioFile != null && audioFile.exists() && audioFile.length() > 0L,
                outputWidth = 720,
                outputHeight = 1280,
                effectAppliedPermanently = true
            )
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("retrotok_kiosk_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        videoStorageManager = VideoStorageManager(context)
        musicStorageManager = MusicStorageManager(context)
        soundtrackController = SoundtrackSessionController(context, musicStorageManager)

        videoStorageManager.clearTempStorage()
        videoStorageManager.publishedDir.listFiles()?.forEach { it.delete() }

        val relPath = "retrotok/music/phase14_test_track.mp3"
        audioFile = musicStorageManager.getAbsoluteFile(relPath).apply {
            parentFile?.mkdirs()
            // Minimal valid-length dummy MP3 header bytes so file exists & ExoPlayer accepts MediaItem
            writeBytes(ByteArray(1024) { idx -> if (idx == 0) 0xFF.toByte() else if (idx == 1) 0xFB.toByte() else 0x22 })
        }
        testSong = SongItem(
            id = "phase14-song-1",
            title = "Take On Me",
            artist = "Retro Star",
            album = "80s Hits",
            releaseYear = 1985,
            decade = RetroDecade.DECADE_80S,
            genre = "Synthpop",
            durationSeconds = 180,
            audioRelativePath = relPath
        )
    }

    @After
    fun tearDown() {
        soundtrackController.release()
        if (::audioFile.isInitialized && audioFile.exists()) {
            audioFile.delete()
        }
        videoStorageManager.clearTempStorage()
        videoStorageManager.publishedDir.listFiles()?.forEach { it.delete() }
        Dispatchers.resetMain()
    }

    @Test
    fun testFreshInstallAndLegacyPrefsMigrationDefaultToWideViewWithoutCorruptingData() = runTest {
        // 1. Fresh install: no preferences stored yet
        val freshRepo = LocalKioskSettingsRepository(context)
        val freshSettings = freshRepo.getSettings().first()
        assertTrue("Fresh install must default Wide View ON", freshSettings.isWideViewEnabled)
        assertEquals(CameraFramingMode.WIDE_FIT, freshSettings.cameraFramingMode)

        // 2. Existing installation upgrading: has legacy settings, missing camera_wide_view_enabled
        val prefs = context.getSharedPreferences("retrotok_kiosk_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .clear()
            .putString("event_name", "Existing Gala 2026")
            .putBoolean("allow_guest_deletion", true)
            .putInt("default_recording_duration", 60)
            .commit()

        val migratedRepo = LocalKioskSettingsRepository(context)
        val migrated = migratedRepo.getSettings().first()
        assertTrue("Upgrading install without framing key must default to Wide View ON", migrated.isWideViewEnabled)
        assertEquals(CameraFramingMode.WIDE_FIT, migrated.cameraFramingMode)
        assertEquals("Existing Gala 2026", migrated.eventName)
        assertTrue(migrated.allowGuestDeletion)
        assertEquals(60, migrated.defaultRecordingDurationSeconds)
    }

    @Test
    fun testAdminWideViewTogglePersistsAcrossRestartAndControlsUiSwitch() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val repo1 = LocalKioskSettingsRepository(context)
        val adminVm1 = AdminViewModel(
            settingsRepository = repo1,
            videoFeedRepository = MockVideoFeedRepository(),
            musicCatalogRepository = MockMusicCatalogRepository(),
            audioPreviewManager = AudioPreviewManager(context),
            storageManager = musicStorageManager,
            videoStorageManager = videoStorageManager
        )
        runCurrent()

        assertTrue(adminVm1.uiState.value.settings.isWideViewEnabled)
        assertEquals(CameraFramingMode.WIDE_FIT, adminVm1.uiState.value.settings.cameraFramingMode)

        // Render Admin Settings tab and verify "Wide View" switch defaults ON, then click OFF
        adminVm1.selectTab(AdminTab.KIOSK_SETTINGS)
        composeRule.setContent {
            val uiState = adminVm1.uiState.collectAsState().value
            RetroTokTheme {
                AdminDashboardScreen(
                    uiState = uiState,
                    onTabSelected = { adminVm1.selectTab(it) },
                    onDurationSelected = { adminVm1.updateDuration(it) },
                    onToggleGuestDeletion = { adminVm1.toggleGuestDeletion() },
                    onToggleKioskLock = { adminVm1.toggleKioskLock() },
                    onDeleteVideo = {},
                    onToggleFeatured = {},
                    onClearStorage = {},
                    onOpenHardwareDiagnostics = {},
                    onImportAudioFiles = {},
                    onDismissImportSummary = {},
                    onToggleSongLicense = {},
                    onToggleSongPreview = {},
                    onOpenEditSong = {},
                    onDismissEditSong = {},
                    onSaveSongMetadata = { _, _, _, _, _, _, _ -> },
                    onRequestDeleteSong = {},
                    onDismissDeleteSong = {},
                    onConfirmDeleteSong = {},
                    onExitAdmin = {},
                    onToggleWideView = { adminVm1.toggleWideView() }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNode(
            hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        ).performScrollToNode(hasTestTag("toggle_wide_view_switch"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("toggle_wide_view_switch")
            .assertIsDisplayed()
            .assertIsOn()
        composeRule.onNodeWithText("Wide View").assertIsDisplayed()
        composeRule.onNodeWithTag("toggle_wide_view_switch").performClick()
        runCurrent()
        composeRule.waitForIdle()

        assertFalse(adminVm1.uiState.value.settings.isWideViewEnabled)
        assertEquals(CameraFramingMode.PORTRAIT_CROP, adminVm1.uiState.value.settings.cameraFramingMode)
        composeRule.onNodeWithTag("toggle_wide_view_switch").assertIsOff()

        // Simulate app restart with new repository & ViewModel instance
        val repo2 = LocalKioskSettingsRepository(context)
        val restartedSettings = repo2.getSettings().first()
        assertFalse("Admin OFF setting must persist across restarts", restartedSettings.isWideViewEnabled)
        assertEquals(CameraFramingMode.PORTRAIT_CROP, restartedSettings.cameraFramingMode)

        // Toggle back ON and verify persistence across another restart
        repo2.toggleWideView(true)
        val repo3 = LocalKioskSettingsRepository(context)
        val restoredOn = repo3.getSettings().first()
        assertTrue(restoredOn.isWideViewEnabled)
        assertEquals(CameraFramingMode.WIDE_FIT, restoredOn.cameraFramingMode)
    }

    @Test
    fun testPreviewAndExportedVideoMatchAdminFramingAndMidRecordingChangeIsDeferredToNextSession() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val settingsRepo = LocalKioskSettingsRepository(context)
        val recordingController = FakeRecordingController(context)
        val spyMuxer = SpyVideoMuxerProcessor(context)
        val previewController = CameraPreviewController(context)
        val previewView = PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }

        val studioVm = StudioViewModel(
            cameraProvider = FakeCameraProvider(),
            recordingController = recordingController,
            soundtrackController = soundtrackController,
            storageManager = videoStorageManager,
            muxerProcessor = spyMuxer,
            settingsRepository = settingsRepo,
            recordingStartTimeoutMs = 5_000L
        )
        runCurrent()

        // Fresh default is WIDE_FIT -> PreviewView uses FIT_CENTER
        assertEquals(CameraFramingMode.WIDE_FIT, studioVm.uiState.value.cameraFramingMode)
        previewController.setFramingMode(previewView, studioVm.uiState.value.cameraFramingMode)
        assertEquals(PreviewView.ScaleType.FIT_CENTER, previewView.scaleType)

        // Check GLES transform geometry matches WIDE_FIT
        val wideGlTransform = EffectShaderConfig.computePortraitCropTransform(
            sourceWidth = 1280,
            sourceHeight = 720,
            rotationDegrees = 0,
            targetWidth = 720,
            targetHeight = 1280,
            isMirrored = true,
            framingMode = studioVm.uiState.value.cameraFramingMode
        )
        assertEquals(CameraFramingMode.WIDE_FIT, wideGlTransform.framingMode)

        // Start session while WIDE_FIT is active
        studioVm.selectSong(testSong)
        studioVm.selectEffect(RetroEffectType.VHS_RETRO)
        studioVm.startCountdownAndRecord()
        runCurrent()
        assertEquals(StudioState.COUNTDOWN, studioVm.uiState.value.studioState)

        // Admin flips Wide View OFF during active countdown & recording -> active session stays WIDE_FIT!
        settingsRepo.toggleWideView(false)
        runCurrent()
        assertEquals(CameraFramingMode.WIDE_FIT, studioVm.uiState.value.cameraFramingMode)

        advanceTimeBy(3100L)
        runCurrent()
        assertEquals(StudioState.INITIALIZING_CAMERA, studioVm.uiState.value.studioState)
        assertEquals(CameraFramingMode.WIDE_FIT, studioVm.uiState.value.cameraFramingMode)

        // Simulate VideoRecordEvent.Start -> enter RECORDING -> still locked to WIDE_FIT
        recordingController.lastOnEvent?.invoke(
            MockitoHelper.mockStartEvent(recordingController.lastOutputFile!!)
        )
        runCurrent()
        assertEquals(StudioState.RECORDING, studioVm.uiState.value.studioState)
        assertEquals(CameraFramingMode.WIDE_FIT, studioVm.uiState.value.cameraFramingMode)

        // Finish recording #1 -> exported MP4 must still use WIDE_FIT
        val rawFile1 = recordingController.lastOutputFile!!
        recordingController.activeSession = false
        recordingController.lastOnComplete?.invoke(RecordingResult.Success(rawFile1, 5000L))
        runCurrent()
        while (studioVm.uiState.value.studioState != StudioState.PREVIEW_READY) {
            Thread.sleep(10)
            runCurrent()
        }

        assertEquals(listOf(CameraFramingMode.WIDE_FIT), spyMuxer.capturedFramingModes)
        assertEquals(listOf(RetroEffectType.VHS_RETRO), spyMuxer.capturedEffects)

        // Next session applies the Admin change (PORTRAIT_CROP -> PreviewView.FILL_CENTER)
        studioVm.startNewGuestSession()
        runCurrent()
        assertEquals(CameraFramingMode.PORTRAIT_CROP, studioVm.uiState.value.cameraFramingMode)
        previewController.setFramingMode(previewView, studioVm.uiState.value.cameraFramingMode)
        assertEquals(PreviewView.ScaleType.FILL_CENTER, previewView.scaleType)

        // Record in next session -> exported MP4 uses PORTRAIT_CROP
        studioVm.selectSong(testSong)
        studioVm.startCountdownAndRecord()
        advanceTimeBy(3100L)
        runCurrent()
        val rawFile2 = recordingController.lastOutputFile!!
        recordingController.activeSession = false
        recordingController.lastOnComplete?.invoke(RecordingResult.Success(rawFile2, 5000L))
        runCurrent()
        while (studioVm.uiState.value.studioState != StudioState.PREVIEW_READY) {
            Thread.sleep(10)
            runCurrent()
        }
        assertEquals(listOf(CameraFramingMode.WIDE_FIT, CameraFramingMode.PORTRAIT_CROP), spyMuxer.capturedFramingModes)
    }

    @Test
    fun testGuestStudioScreenHidesFramingControlsAndAllDiagnosticTextAcrossStates() {
        val states = listOf(
            StudioState.READY,
            StudioState.COUNTDOWN,
            StudioState.INITIALIZING_CAMERA,
            StudioState.RECORDING,
            StudioState.PROCESSING,
            StudioState.ERROR
        )
        val stateFlow = MutableStateFlow(
            StudioUiState(
                selectedSong = testSong,
                studioState = StudioState.READY,
                cameraFramingMode = CameraFramingMode.WIDE_FIT,
                isVideoCaptureBound = true,
                activeVideoQualityLabel = "HD",
                requestedVideoQualityLabel = "HD",
                isQualityDowngraded = true,
                recordedSeconds = 5,
                maxDurationSeconds = 30,
                countdownNumber = 3
            )
        )

        composeRule.setContent {
            val uiState = stateFlow.collectAsState().value
            RetroTokTheme {
                DanceStudioScreen(
                    uiState = uiState,
                    cameraPreviewController = CameraPreviewController(context),
                    onSongPillClick = {},
                    onEffectSelect = {},
                    onStartRecording = {},
                    onStopRecording = {},
                    onToggleMirror = {},
                    onToggleGrid = {},
                    onCloseClick = {}
                )
            }
        }

        for (st in states) {
            stateFlow.value = stateFlow.value.copy(
                studioState = st,
                isVideoCaptureBound = st != StudioState.ERROR
            )
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("studio_framing_toggle").assertDoesNotExist()
            composeRule.onNodeWithTag("studio_framing_portrait_crop").assertDoesNotExist()
            composeRule.onNodeWithTag("studio_framing_wide_view").assertDoesNotExist()
            composeRule.onNodeWithText("Portrait Crop").assertDoesNotExist()
            composeRule.onNodeWithText("Wide View").assertDoesNotExist()

            composeRule.onNodeWithTag("studio_video_capture_status_badge").assertDoesNotExist()
            composeRule.onNodeWithTag("studio_camera_diagnostics_badge").assertDoesNotExist()
            composeRule.onNodeWithText("VideoCapture:", substring = true).assertDoesNotExist()
            composeRule.onNodeWithText("CAM ", substring = true).assertDoesNotExist()
        }

        // Countdown & timer indicators remain visible when in their active states
        stateFlow.value = stateFlow.value.copy(studioState = StudioState.COUNTDOWN, countdownNumber = 2)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("GET READY TO DANCE!").assertIsDisplayed()
        composeRule.onNodeWithText("2").assertIsDisplayed()

        stateFlow.value = stateFlow.value.copy(studioState = StudioState.RECORDING, recordedSeconds = 7, maxDurationSeconds = 30)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("00:07 / 00:30").assertIsDisplayed()
    }

    @Test
    fun testSoundtrackSessionControllerRePreparesCleanlyAcrossMultipleRetakesWithoutLeaks() {
        assertTrue(soundtrackController.prepareTrack(testSong))
        assertTrue(soundtrackController.isPrepared.value)
        val firstPlayer = soundtrackController.currentPlayerInstance()
        assertNotNull(firstPlayer)

        // Simulate 4 consecutive recording + stop + retake cycles
        for (cycle in 1..4) {
            soundtrackController.startPlaybackAtRecordStart()
            assertTrue("Cycle $cycle: playWhenReady must be requested", soundtrackController.isPlayWhenReadyRequested())
            assertEquals(1f, soundtrackController.currentVolume(), 0.001f)
            assertSame("Cycle $cycle must reuse the single ExoPlayer instance", firstPlayer, soundtrackController.currentPlayerInstance())

            soundtrackController.stopPlayback()
            assertFalse("Cycle $cycle: stopPlayback clears isPlaying", soundtrackController.isPlaying.value)
            assertFalse("Cycle $cycle: stopPlayback clears isPrepared because Media3 enters STATE_IDLE", soundtrackController.isPrepared.value)
            assertEquals(Player.STATE_IDLE, soundtrackController.currentPlaybackState())
            assertNotNull("Resolved audio file must stay preserved for muxer/retake", soundtrackController.getResolvedAudioFile())

            // Simulate Retake re-prepare
            assertTrue("Cycle $cycle: ensurePrepared must recover from STATE_IDLE", soundtrackController.ensurePrepared())
            assertTrue("Cycle $cycle: isPrepared must be true after retake prepare", soundtrackController.isPrepared.value)
            assertTrue("Cycle $cycle: player must leave STATE_IDLE after prepare", soundtrackController.currentPlaybackState() != Player.STATE_IDLE)
            assertSame("Cycle $cycle: zero duplicate players allocated", firstPlayer, soundtrackController.currentPlayerInstance())
        }

        soundtrackController.release()
        assertFalse(soundtrackController.hasActivePlayerInstance())
        assertNull(soundtrackController.getResolvedAudioFile())
        assertFalse(soundtrackController.isPrepared.value)
    }

    @Test
    fun testStudioViewModelRetakeFlowPreservesSongRestartsMusicAndMuxesAudioIntoFinalVideo() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val settingsRepo = LocalKioskSettingsRepository(context)
        val recordingController = FakeRecordingController(context)
        val spyMuxer = SpyVideoMuxerProcessor(context)
        val studioVm = StudioViewModel(
            cameraProvider = FakeCameraProvider(),
            recordingController = recordingController,
            soundtrackController = soundtrackController,
            storageManager = videoStorageManager,
            muxerProcessor = spyMuxer,
            settingsRepository = settingsRepo,
            recordingStartTimeoutMs = 5_000L
        )
        runCurrent()

        studioVm.selectSong(testSong)
        assertEquals(testSong.id, studioVm.uiState.value.selectedSong?.id)
        assertTrue(soundtrackController.isPrepared.value)
        val initialPlayer = soundtrackController.currentPlayerInstance()
        assertNotNull(initialPlayer)

        // Perform Recording #1 -> Stop -> Preview -> Retake -> Recording #2 -> Retake -> Recording #3
        for (attempt in 1..3) {
            studioVm.startCountdownAndRecord()
            advanceTimeBy(3100L)
            runCurrent()
            assertEquals(StudioState.INITIALIZING_CAMERA, studioVm.uiState.value.studioState)

            val rawFile = recordingController.lastOutputFile!!
            recordingController.lastOnEvent?.invoke(MockitoHelper.mockStartEvent(rawFile))
            runCurrent()
            assertEquals(StudioState.RECORDING, studioVm.uiState.value.studioState)
            assertTrue(
                "Attempt #$attempt: soundtrack must actively request playback at recording start",
                soundtrackController.isPlayWhenReadyRequested()
            )
            assertTrue(
                "Attempt #$attempt: player must NOT be stuck in STATE_IDLE",
                soundtrackController.currentPlaybackState() != Player.STATE_IDLE
            )
            assertSame("Attempt #$attempt must not leak duplicate players", initialPlayer, soundtrackController.currentPlayerInstance())

            studioVm.stopRecordingManually()
            runCurrent()
            assertEquals(StudioState.FINALIZING, studioVm.uiState.value.studioState)
            assertFalse(soundtrackController.isPlayWhenReadyRequested())

            recordingController.activeSession = false
            recordingController.lastOnComplete?.invoke(RecordingResult.Success(rawFile, 4000L))
            runCurrent()
            while (studioVm.uiState.value.studioState != StudioState.PREVIEW_READY) {
                Thread.sleep(10)
                runCurrent()
            }

            assertEquals(attempt, spyMuxer.capturedAudioFiles.size)
            assertEquals(audioFile.absolutePath, spyMuxer.capturedAudioFiles.last()?.absolutePath)

            if (attempt < 3) {
                // Guest taps Retake
                studioVm.resetStudio()
                runCurrent()
                assertEquals(StudioState.READY, studioVm.uiState.value.studioState)
                assertEquals(
                    "Selected song must be preserved after Retake #$attempt",
                    testSong.id,
                    studioVm.uiState.value.selectedSong?.id
                )
                assertTrue(
                    "Soundtrack must be re-prepared immediately after Retake #$attempt",
                    soundtrackController.isPrepared.value
                )
                assertTrue(soundtrackController.currentPlaybackState() != Player.STATE_IDLE)
            }
        }

        // Ending guest session releases player cleanly
        studioVm.startNewGuestSession()
        runCurrent()
        assertNull(studioVm.uiState.value.selectedSong)
        assertFalse(soundtrackController.hasActivePlayerInstance())
    }

    /**
     * Minimal helper to create a CameraX VideoRecordEvent.Start without requiring real MediaCodec capture.
     */
    private object MockitoHelper {
        fun mockStartEvent(outputFile: File): VideoRecordEvent.Start {
            val options = androidx.camera.video.FileOutputOptions.Builder(outputFile).build()
            val ctor = VideoRecordEvent.Start::class.java.declaredConstructors.first()
            ctor.isAccessible = true
            val params = ctor.parameterTypes
            val args = arrayOfNulls<Any>(params.size)
            for (i in params.indices) {
                args[i] = when {
                    androidx.camera.video.OutputOptions::class.java.isAssignableFrom(params[i]) -> options
                    androidx.camera.video.RecordingStats::class.java.isAssignableFrom(params[i]) -> createRecordingStats()
                    else -> null
                }
            }
            return ctor.newInstance(*args) as VideoRecordEvent.Start
        }

        private fun createRecordingStats(): androidx.camera.video.RecordingStats {
            val method = androidx.camera.video.RecordingStats::class.java.declaredMethods.firstOrNull {
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
                return method.invoke(null, 0L, 0L, audioStats) as androidx.camera.video.RecordingStats
            }
            throw IllegalStateException("Unable to construct RecordingStats")
        }
    }
}
