package com.example

import android.content.Context
import androidx.camera.video.Quality
import androidx.camera.video.VideoRecordEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.camera.CameraBackendType
import com.example.camera.CameraCapabilities
import com.example.camera.CameraConnectionState
import com.example.camera.CameraDeviceInfo
import com.example.camera.CameraProvider
import com.example.camera.CameraRecordingController
import com.example.camera.RecordingResult
import com.example.data.local.storage.MusicStorageManager
import com.example.model.RetroDecade
import com.example.model.SongItem
import com.example.ui.screens.studio.DanceStudioScreen
import com.example.ui.screens.studio.StudioState
import com.example.ui.screens.studio.StudioUiState
import com.example.ui.screens.studio.StudioViewModel
import com.example.ui.theme.RetroTokTheme
import com.example.video.SoundtrackSessionController
import com.example.video.TempRecordingMetadata
import com.example.video.VideoMuxerProcessor
import com.example.video.VideoStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
class Phase9CameraRecordingFixTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var videoStorageManager: VideoStorageManager
    private lateinit var musicStorageManager: MusicStorageManager
    private lateinit var soundtrackController: SoundtrackSessionController
    private lateinit var videoMuxerProcessor: VideoMuxerProcessor

    private val testSong = SongItem(
        id = "phase9-song-1",
        title = "Electric Boogie",
        artist = "Retro Kiosk",
        album = "Neon Hits",
        releaseYear = 1983,
        decade = RetroDecade.DECADE_80S,
        genre = "Electro",
        durationSeconds = 180,
        audioRelativePath = ""
    )

    private class TestCameraProvider : CameraProvider {
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

    private class FakeBoundRecordingController(context: Context) : CameraRecordingController(context) {
        private val _fakeBound = MutableStateFlow(true)
        override val isVideoCaptureBound: StateFlow<Boolean> = _fakeBound.asStateFlow()

        private val _fakeBindError = MutableStateFlow<String?>(null)
        override val bindingErrorMessage: StateFlow<String?> = _fakeBindError.asStateFlow()

        private val _fakeRecording = MutableStateFlow(false)
        override val isRecording: StateFlow<Boolean> = _fakeRecording.asStateFlow()

        var startCalls: Int = 0
        var cancelCalls: Int = 0
        var stopCalls: Int = 0
        var lastOutputFile: File? = null
        var lastOnEvent: ((VideoRecordEvent) -> Unit)? = null
        var lastOnComplete: ((RecordingResult) -> Unit)? = null
        var activeSessionFlag: Boolean = false

        override fun hasActiveRecording(): Boolean = activeSessionFlag

        override fun startRecording(
            outputFile: File,
            onEvent: (VideoRecordEvent) -> Unit,
            onComplete: (RecordingResult) -> Unit
        ): Boolean {
            if (!_fakeBound.value) {
                onComplete(RecordingResult.Error("VideoCapture is not bound."))
                return false
            }
            if (activeSessionFlag) {
                return false
            }
            startCalls++
            activeSessionFlag = true
            // Intentionally do NOT set _fakeRecording = true until VideoRecordEvent.Start arrives
            lastOutputFile = outputFile
            lastOnEvent = onEvent
            lastOnComplete = onComplete
            outputFile.parentFile?.mkdirs()
            outputFile.createNewFile() // 0-byte initial file before frames arrive
            return true
        }

        override fun stopRecording() {
            stopCalls++
            activeSessionFlag = false
            _fakeRecording.value = false
        }

        override fun cancelRecording() {
            cancelCalls++
            activeSessionFlag = false
            _fakeRecording.value = false
        }

        fun setBound(bound: Boolean, error: String? = null) {
            _fakeBound.value = bound
            _fakeBindError.value = error
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        videoStorageManager = VideoStorageManager(context)
        musicStorageManager = MusicStorageManager(context)
        soundtrackController = SoundtrackSessionController(context, musicStorageManager)
        videoMuxerProcessor = VideoMuxerProcessor(context)
        videoStorageManager.clearTempStorage()
        videoStorageManager.publishedDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        videoStorageManager.clearTempStorage()
        videoStorageManager.publishedDir.listFiles()?.forEach { it.delete() }
        Dispatchers.resetMain()
    }

    @Test
    fun testPrioritizeSupportedQualitiesPrefersHdThenSdAndDoesNotAssumeUnsupported() {
        // Prefers HD -> SD -> FHD when supported
        val ordered = CameraRecordingController.prioritizeSupportedQualities(
            listOf(Quality.FHD, Quality.SD, Quality.HD)
        )
        assertEquals(listOf(Quality.HD, Quality.SD, Quality.FHD), ordered)

        // Never assumes SD or LOWEST if camera does not report them
        val hdOnly = CameraRecordingController.prioritizeSupportedQualities(listOf(Quality.HD))
        assertEquals(listOf(Quality.HD), hdOnly)
        assertFalse(hdOnly.contains(Quality.SD))
        assertFalse(hdOnly.contains(Quality.LOWEST))

        // Empty list returns empty
        assertTrue(CameraRecordingController.prioritizeSupportedQualities(emptyList()).isEmpty())
    }

    @Test
    fun testControllerRefusesStartRecordingWhenVideoCaptureUnbound() {
        val controller = CameraRecordingController(context)
        val rawFile = videoStorageManager.createTempRawVideoFile("unbound_test")

        var callbackResult: RecordingResult? = null
        val started = controller.startRecording(
            outputFile = rawFile,
            onEvent = {},
            onComplete = { callbackResult = it }
        )

        assertFalse("startRecording must return false when VideoCapture is unbound", started)
        assertFalse(controller.isRecording.value)
        assertFalse(controller.isVideoCaptureBound.value)
        assertTrue(callbackResult is RecordingResult.Error)
        assertFalse("Zero-byte temp file must not be created when refused", rawFile.exists())
    }

    @Test
    fun testControllerBindingFailureAndClearResetStateCleanly() {
        val controller = CameraRecordingController(context)
        controller.markVideoCaptureBindFailed("Preview-only camera cannot record")

        assertFalse(controller.isVideoCaptureBound.value)
        assertEquals("Preview-only camera cannot record", controller.bindingErrorMessage.value)
        assertNull(controller.activeVideoQuality.value)

        controller.clearBoundVideoCapture()
        assertFalse(controller.isVideoCaptureBound.value)
        assertNull(controller.bindingErrorMessage.value)
        assertNull(controller.activeVideoQuality.value)
    }

    @Test
    fun testStudioViewModelBlocksRecordingWhenVideoCaptureIsUnbound() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val recordingController = CameraRecordingController(context)
        recordingController.markVideoCaptureBindFailed("Camera does not support VideoCapture.")

        val studioVm = StudioViewModel(
            cameraProvider = TestCameraProvider(),
            recordingController = recordingController,
            soundtrackController = soundtrackController,
            storageManager = videoStorageManager,
            muxerProcessor = videoMuxerProcessor
        )
        runCurrent()

        studioVm.selectSong(testSong)
        studioVm.startCountdownAndRecord()
        runCurrent()

        assertEquals(StudioState.READY, studioVm.uiState.value.studioState)
        assertFalse(studioVm.uiState.value.isVideoCaptureBound)
        assertEquals("Camera does not support VideoCapture.", studioVm.uiState.value.errorMessage)
    }

    @Test
    fun testStudioViewModelWaitsForStartEventTimesOutAndCleansUpZeroByteOutput() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val fakeRecordingController = FakeBoundRecordingController(context)
        val studioVm = StudioViewModel(
            cameraProvider = TestCameraProvider(),
            recordingController = fakeRecordingController,
            soundtrackController = soundtrackController,
            storageManager = videoStorageManager,
            muxerProcessor = videoMuxerProcessor,
            recordingStartTimeoutMs = 500L
        )
        runCurrent()

        studioVm.selectSong(testSong)
        studioVm.startCountdownAndRecord()
        runCurrent()
        assertEquals(StudioState.COUNTDOWN, studioVm.uiState.value.studioState)

        // Duplicate press during countdown must not start another session
        studioVm.startCountdownAndRecord()
        runCurrent()

        // Advance past 3-second countdown
        advanceTimeBy(3100L)
        runCurrent()

        // Must be in INITIALIZING_CAMERA waiting for VideoRecordEvent.Start (NOT RECORDING)
        assertEquals(StudioState.INITIALIZING_CAMERA, studioVm.uiState.value.studioState)
        assertFalse(studioVm.uiState.value.hasRecordingStarted)
        assertEquals(1, fakeRecordingController.startCalls)
        val createdRawFile = fakeRecordingController.lastOutputFile
        assertNotNull(createdRawFile)
        assertTrue(createdRawFile!!.exists())
        assertEquals(0L, createdRawFile.length())

        // Advance past 500ms startup timeout without emitting VideoRecordEvent.Start
        advanceTimeBy(600L)
        runCurrent()

        val deadline = System.currentTimeMillis() + 2000L
        while (studioVm.uiState.value.studioState == StudioState.INITIALIZING_CAMERA &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(10)
            runCurrent()
        }

        assertEquals(StudioState.ERROR, studioVm.uiState.value.studioState)
        assertFalse(studioVm.uiState.value.hasRecordingStarted)
        assertTrue(fakeRecordingController.cancelCalls >= 1)
        assertNotNull(studioVm.uiState.value.errorMessage)
        // Zero-byte temp file and its metadata must be cleaned up
        assertFalse("Zero-byte temp file must be deleted after startup timeout", createdRawFile.exists())
        assertTrue(videoStorageManager.findRecoverableTempSessions().isEmpty())
    }

    @Test
    fun testStudioViewModelHandlesNoValidDataErrorAndCleansUpZeroByteFile() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val fakeRecordingController = FakeBoundRecordingController(context)
        val studioVm = StudioViewModel(
            cameraProvider = TestCameraProvider(),
            recordingController = fakeRecordingController,
            soundtrackController = soundtrackController,
            storageManager = videoStorageManager,
            muxerProcessor = videoMuxerProcessor,
            recordingStartTimeoutMs = 5_000L
        )
        runCurrent()

        studioVm.selectSong(testSong)
        studioVm.startCountdownAndRecord()
        advanceTimeBy(3100L)
        runCurrent()

        assertEquals(StudioState.INITIALIZING_CAMERA, studioVm.uiState.value.studioState)
        val rawFile = fakeRecordingController.lastOutputFile!!
        assertTrue(rawFile.exists() && rawFile.length() == 0L)

        // Simulate CameraX Finalize error code 8 (ERROR_NO_VALID_DATA)
        rawFile.delete()
        fakeRecordingController.activeSessionFlag = false
        fakeRecordingController.lastOnComplete?.invoke(
            RecordingResult.Error(
                message = "Recording finalized with error code: 8 (No valid data)",
                partialFile = null,
                errorCode = VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA,
                causeMessage = "No valid data"
            )
        )
        runCurrent()

        val deadline = System.currentTimeMillis() + 2000L
        while (studioVm.uiState.value.studioState == StudioState.INITIALIZING_CAMERA &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(10)
            runCurrent()
        }

        assertEquals(StudioState.ERROR, studioVm.uiState.value.studioState)
        assertTrue(studioVm.uiState.value.errorMessage?.contains("error code: 8") == true)
        assertFalse(rawFile.exists())
        assertTrue(videoStorageManager.findRecoverableTempSessions().isEmpty())
    }

    @Test
    fun testCleanupFailedTempRecordingDeletesZeroByteButPreservesNonEmptyAndPublished() = runTest {
        val zeroRecId = "rec_zero_bytes"
        val zeroFile = videoStorageManager.createTempRawVideoFile(zeroRecId).apply { createNewFile() }
        videoStorageManager.saveTempRecordingMetadata(
            TempRecordingMetadata(
                recordingId = zeroRecId,
                videoFileName = zeroFile.name,
                songId = testSong.id,
                songTitle = testSong.title,
                songArtist = testSong.artist,
                effectName = "NONE",
                durationSeconds = 30,
                createdAtMs = 1000L,
                stage = "RAW"
            )
        )

        val nonEmptyRecId = "rec_partial_bytes"
        val nonEmptyFile = videoStorageManager.createTempRawVideoFile(nonEmptyRecId).apply {
            writeBytes(ByteArray(1024) { 0x2A })
        }
        videoStorageManager.saveTempRecordingMetadata(
            TempRecordingMetadata(
                recordingId = nonEmptyRecId,
                videoFileName = nonEmptyFile.name,
                songId = testSong.id,
                songTitle = testSong.title,
                songArtist = testSong.artist,
                effectName = "NONE",
                durationSeconds = 30,
                createdAtMs = 2000L,
                stage = "RAW"
            )
        )

        val publishedFile = File(videoStorageManager.publishedDir, "pub_keep_me.mp4").apply {
            writeBytes(ByteArray(512) { 0x11 })
        }

        val deletedZero = videoStorageManager.cleanupFailedTempRecording(zeroFile, zeroRecId)
        assertTrue(deletedZero)
        assertFalse(zeroFile.exists())
        assertFalse(File(videoStorageManager.tempDir, "meta_${zeroRecId}.properties").exists())

        val deletedNonEmpty = videoStorageManager.cleanupFailedTempRecording(nonEmptyFile, nonEmptyRecId)
        assertFalse("Non-empty recoverable raw file must be preserved", deletedNonEmpty)
        assertTrue(nonEmptyFile.exists())
        assertTrue(File(videoStorageManager.tempDir, "meta_${nonEmptyRecId}.properties").exists())

        // Attempt to call cleanup on a published file must be refused cleanly
        val deletedPublished = videoStorageManager.cleanupFailedTempRecording(publishedFile, null)
        assertFalse(deletedPublished)
        assertTrue(publishedFile.exists())
    }

    @Test
    fun testDanceStudioScreenShowsBindingErrorAndBlocksStartWhenVideoCaptureUnbound() {
        var startClicks = 0
        var retryClicks = 0

        composeRule.setContent {
            RetroTokTheme {
                DanceStudioScreen(
                    uiState = StudioUiState(
                        selectedSong = testSong,
                        studioState = StudioState.READY,
                        isCameraDisconnected = false,
                        isVideoCaptureBound = false,
                        cameraBindingError = "Camera preview is active, but VideoCapture failed to bind.",
                        errorMessage = "Camera preview is active, but VideoCapture failed to bind."
                    ),
                    onSongPillClick = {},
                    onEffectSelect = {},
                    onStartRecording = { startClicks++ },
                    onStopRecording = {},
                    onToggleMirror = {},
                    onToggleGrid = {},
                    onCloseClick = {},
                    onRetryCamera = { retryClicks++ }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("studio_video_capture_status_badge").assertDoesNotExist()
        composeRule.onNodeWithTag("studio_camera_diagnostics_badge").assertDoesNotExist()
        composeRule.onNodeWithText("VideoCapture: Unbound").assertDoesNotExist()
        composeRule.onNodeWithTag("studio_error_banner").assertIsDisplayed()
        composeRule.onNodeWithText("VideoCapture failed to bind", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("studio_retry_camera_button").assertIsDisplayed()

        // Clicking retry notifies caller
        composeRule.onNodeWithTag("studio_retry_camera_button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, retryClicks)

        // Clicking record button when VideoCapture is unbound must NOT invoke onStartRecording
        composeRule.onNodeWithTag("studio_record_button").performClick()
        composeRule.waitForIdle()
        assertEquals(0, startClicks)
    }

    @Test
    fun testDanceStudioScreenHidesDiagnosticBadgesWhenBound() {
        composeRule.setContent {
            RetroTokTheme {
                DanceStudioScreen(
                    uiState = StudioUiState(
                        selectedSong = testSong,
                        studioState = StudioState.READY,
                        isCameraDisconnected = false,
                        isVideoCaptureBound = true,
                        activeVideoQualityLabel = "HD"
                    ),
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
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("studio_video_capture_status_badge").assertDoesNotExist()
        composeRule.onNodeWithTag("studio_camera_diagnostics_badge").assertDoesNotExist()
        composeRule.onNodeWithText("VideoCapture: Bound • HD").assertDoesNotExist()
    }

    @Test
    fun testDiagnosticErrorFormattingAndPathSanitization() {
        assertEquals(
            "ERROR_NO_VALID_DATA",
            CameraRecordingController.finalizeErrorName(VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA)
        )
        val sanitized = CameraRecordingController.sanitizeDiagnosticCause(
            IllegalStateException("Failed writing /data/user/0/com.example/files/raw_123.mp4 due to codec")
        )
        assertNotNull(sanitized)
        assertFalse("Internal file paths must be redacted", sanitized!!.contains("/data/user"))
        assertTrue(sanitized.contains("<path>"))
        assertTrue(sanitized.contains("IllegalStateException"))
    }
}
