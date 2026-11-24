package com.example

import android.content.Context
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.camera.CameraBackendType
import com.example.camera.CameraCapabilities
import com.example.camera.CameraConnectionState
import com.example.camera.CameraDeviceInfo
import com.example.camera.CameraPreviewController
import com.example.camera.CameraProvider
import com.example.camera.CameraRecordingController
import com.example.camera.RecordingResult
import com.example.data.local.storage.MusicStorageManager
import com.example.data.mock.MockKioskSettingsRepository
import com.example.model.RetroDecade
import com.example.model.RetroEffectType
import com.example.model.SongItem
import com.example.ui.screens.studio.DanceStudioScreen
import com.example.ui.screens.studio.StudioState
import com.example.ui.screens.studio.StudioUiState
import com.example.ui.screens.studio.StudioViewModel
import com.example.ui.theme.RetroTokTheme
import com.example.video.CameraFramingMode
import com.example.video.EffectShaderConfig
import com.example.video.MuxerResult
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase13CameraFramingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var videoStorageManager: VideoStorageManager
    private lateinit var musicStorageManager: MusicStorageManager
    private lateinit var soundtrackController: SoundtrackSessionController

    private val testSong = SongItem(
        id = "phase13-song-1",
        title = "Billie Jean",
        artist = "Retro Kiosk",
        album = "80s Classics",
        releaseYear = 1982,
        decade = RetroDecade.DECADE_80S,
        genre = "Pop",
        durationSeconds = 210,
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

    private class FakeRecordingController(context: Context) : CameraRecordingController(context) {
        private val _fakeBound = MutableStateFlow(true)
        override val isVideoCaptureBound: StateFlow<Boolean> = _fakeBound.asStateFlow()

        private val _fakeRecording = MutableStateFlow(false)
        override val isRecording: StateFlow<Boolean> = _fakeRecording.asStateFlow()

        var activeSession = false
        var lastOutputFile: File? = null
        var lastOnComplete: ((RecordingResult) -> Unit)? = null

        override fun hasActiveRecording(): Boolean = activeSession

        override fun startRecording(
            outputFile: File,
            onEvent: (VideoRecordEvent) -> Unit,
            onComplete: (RecordingResult) -> Unit
        ): Boolean {
            activeSession = true
            lastOutputFile = outputFile
            lastOnComplete = onComplete
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(ByteArray(2048) { 0x42 })
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
        var capturedFramingMode: CameraFramingMode? = null
        var processCalls: Int = 0

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
            processCalls++
            capturedFramingMode = framingMode
            outputFile.parentFile?.mkdirs()
            rawVideoFile.copyTo(outputFile, overwrite = true)
            onProgress(1.0f, "Done")
            return MuxerResult.Success(
                finalFile = outputFile,
                durationMs = durationMs,
                hasAudio = false,
                outputWidth = 720,
                outputHeight = 1280,
                effectAppliedPermanently = true
            )
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        videoStorageManager = VideoStorageManager(context)
        musicStorageManager = MusicStorageManager(context)
        soundtrackController = SoundtrackSessionController(context, musicStorageManager)
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
    fun testPortraitCropRemainsDefaultAndPreservesCenterCropGeometry() {
        val crop16x9 = EffectShaderConfig.computePortraitCropTransform(
            sourceWidth = 1280,
            sourceHeight = 720,
            rotationDegrees = 0,
            targetWidth = 720,
            targetHeight = 1280,
            isMirrored = true
        )
        assertEquals(CameraFramingMode.PORTRAIT_CROP, crop16x9.framingMode)
        assertEquals(0, crop16x9.viewportX)
        assertEquals(0, crop16x9.viewportY)
        assertEquals(720, crop16x9.viewportWidth)
        assertEquals(1280, crop16x9.viewportHeight)
        assertEquals(0.3164f, crop16x9.uvScaleX, 0.001f)
        assertEquals(1.0f, crop16x9.uvScaleY, 0.001f)

        val crop4x3 = EffectShaderConfig.computePortraitCropTransform(
            sourceWidth = 640,
            sourceHeight = 480,
            rotationDegrees = 0,
            targetWidth = 720,
            targetHeight = 1280,
            isMirrored = false,
            framingMode = CameraFramingMode.PORTRAIT_CROP
        )
        assertEquals(0, crop4x3.viewportX)
        assertEquals(0, crop4x3.viewportY)
        assertEquals(720, crop4x3.viewportWidth)
        assertEquals(1280, crop4x3.viewportHeight)
        assertEquals(0.4219f, crop4x3.uvScaleX, 0.001f)
        assertEquals(1.0f, crop4x3.uvScaleY, 0.001f)
    }

    @Test
    fun testWideFitLandscape16x9And4x3ProduceUncroppedCenteredViewports() {
        val wide16x9 = EffectShaderConfig.computePortraitCropTransform(
            sourceWidth = 1280,
            sourceHeight = 720,
            rotationDegrees = 0,
            targetWidth = 720,
            targetHeight = 1280,
            isMirrored = true,
            framingMode = CameraFramingMode.WIDE_FIT
        )
        assertEquals(CameraFramingMode.WIDE_FIT, wide16x9.framingMode)
        assertEquals(1.0f, wide16x9.uvScaleX, 0.0001f)
        assertEquals(1.0f, wide16x9.uvScaleY, 0.0001f)
        assertEquals(0, wide16x9.viewportX)
        assertEquals(437, wide16x9.viewportY)
        assertEquals(720, wide16x9.viewportWidth)
        assertEquals(405, wide16x9.viewportHeight)
        assertTrue(wide16x9.needsMirroring)

        val wide4x3 = EffectShaderConfig.computePortraitCropTransform(
            sourceWidth = 640,
            sourceHeight = 480,
            rotationDegrees = 180,
            targetWidth = 720,
            targetHeight = 1280,
            isMirrored = false,
            framingMode = CameraFramingMode.WIDE_FIT
        )
        assertEquals(1.0f, wide4x3.uvScaleX, 0.0001f)
        assertEquals(1.0f, wide4x3.uvScaleY, 0.0001f)
        assertEquals(0, wide4x3.viewportX)
        assertEquals(370, wide4x3.viewportY)
        assertEquals(720, wide4x3.viewportWidth)
        assertEquals(540, wide4x3.viewportHeight)
        assertEquals(180, wide4x3.rotationDegrees)
    }

    @Test
    fun testWideFitRotatedInputsHandle90And270WithoutStretching() {
        val rotated90_16x9 = EffectShaderConfig.computePortraitCropTransform(
            sourceWidth = 1280,
            sourceHeight = 720,
            rotationDegrees = 90,
            targetWidth = 720,
            targetHeight = 1280,
            isMirrored = false,
            framingMode = CameraFramingMode.WIDE_FIT
        )
        assertEquals(720, rotated90_16x9.effectiveSourceWidth)
        assertEquals(1280, rotated90_16x9.effectiveSourceHeight)
        assertEquals(1.0f, rotated90_16x9.uvScaleX, 0.0001f)
        assertEquals(1.0f, rotated90_16x9.uvScaleY, 0.0001f)
        assertEquals(0, rotated90_16x9.viewportX)
        assertEquals(0, rotated90_16x9.viewportY)
        assertEquals(720, rotated90_16x9.viewportWidth)
        assertEquals(1280, rotated90_16x9.viewportHeight)

        val rotated270_4x3 = EffectShaderConfig.computePortraitCropTransform(
            sourceWidth = 640,
            sourceHeight = 480,
            rotationDegrees = 270,
            targetWidth = 720,
            targetHeight = 1280,
            isMirrored = true,
            framingMode = CameraFramingMode.WIDE_FIT
        )
        assertEquals(480, rotated270_4x3.effectiveSourceWidth)
        assertEquals(640, rotated270_4x3.effectiveSourceHeight)
        assertEquals(1.0f, rotated270_4x3.uvScaleX, 0.0001f)
        assertEquals(1.0f, rotated270_4x3.uvScaleY, 0.0001f)
        assertEquals(0, rotated270_4x3.viewportX)
        assertEquals(160, rotated270_4x3.viewportY)
        assertEquals(720, rotated270_4x3.viewportWidth)
        assertEquals(960, rotated270_4x3.viewportHeight)

        // Aspect ratio invariance across all rotations and mirroring combinations
        val rotations = listOf(0, 90, 180, 270)
        val dimensions = listOf(1280 to 720, 640 to 480)
        for ((w, h) in dimensions) {
            for (rot in rotations) {
                for (mirror in listOf(false, true)) {
                    val t = EffectShaderConfig.computePortraitCropTransform(
                        sourceWidth = w,
                        sourceHeight = h,
                        rotationDegrees = rot,
                        targetWidth = 720,
                        targetHeight = 1280,
                        isMirrored = mirror,
                        framingMode = CameraFramingMode.WIDE_FIT
                    )
                    val expectedAspect = t.effectiveSourceWidth.toFloat() / t.effectiveSourceHeight.toFloat()
                    val viewportAspect = t.viewportWidth.toFloat() / t.viewportHeight.toFloat()
                    assertTrue(
                        "Aspect distortion for ${w}x${h}@$rot: expected=$expectedAspect, actual=$viewportAspect",
                        abs(expectedAspect - viewportAspect) <= 0.01f
                    )
                    assertTrue(t.viewportX >= 0 && t.viewportX + t.viewportWidth <= 720)
                    assertTrue(t.viewportY >= 0 && t.viewportY + t.viewportHeight <= 1280)
                }
            }
        }
    }

    @Test
    fun testStudioViewModelFramingFollowsAdminSettingLocksMidRecordingAndForwardsToMuxer() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val settingsRepo = MockKioskSettingsRepository()
        val recordingController = FakeRecordingController(context)
        val spyMuxer = SpyVideoMuxerProcessor(context)
        val studioVm = StudioViewModel(
            cameraProvider = TestCameraProvider(),
            recordingController = recordingController,
            soundtrackController = soundtrackController,
            storageManager = videoStorageManager,
            muxerProcessor = spyMuxer,
            settingsRepository = settingsRepo,
            recordingStartTimeoutMs = 5_000L
        )
        runCurrent()

        // Default is WIDE_FIT
        assertEquals(CameraFramingMode.WIDE_FIT, studioVm.uiState.value.cameraFramingMode)

        // Admin disables Wide View -> PORTRAIT_CROP applies immediately in READY
        settingsRepo.toggleWideView(false)
        runCurrent()
        assertEquals(CameraFramingMode.PORTRAIT_CROP, studioVm.uiState.value.cameraFramingMode)

        studioVm.selectSong(testSong)
        studioVm.startCountdownAndRecord()
        runCurrent()
        assertEquals(StudioState.COUNTDOWN, studioVm.uiState.value.studioState)

        // Admin toggles Wide View ON mid-countdown -> ignored for active session
        settingsRepo.toggleWideView(true)
        runCurrent()
        assertEquals(CameraFramingMode.PORTRAIT_CROP, studioVm.uiState.value.cameraFramingMode)

        // Advance past countdown into INITIALIZING_CAMERA -> still locked to PORTRAIT_CROP
        advanceTimeBy(3100L)
        runCurrent()
        assertEquals(StudioState.INITIALIZING_CAMERA, studioVm.uiState.value.studioState)
        assertEquals(CameraFramingMode.PORTRAIT_CROP, studioVm.uiState.value.cameraFramingMode)

        val rawFile = recordingController.lastOutputFile
        assertNotNull(rawFile)

        // Complete capture -> invokes muxer with active session's PORTRAIT_CROP
        recordingController.activeSession = false
        recordingController.lastOnComplete?.invoke(
            RecordingResult.Success(recordedFile = rawFile!!, durationMs = 4000L)
        )
        runCurrent()

        val deadline = System.currentTimeMillis() + 3000L
        while (studioVm.uiState.value.studioState != StudioState.PREVIEW_READY &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(10)
            runCurrent()
        }

        assertEquals(StudioState.PREVIEW_READY, studioVm.uiState.value.studioState)
        assertEquals(1, spyMuxer.processCalls)
        assertEquals(CameraFramingMode.PORTRAIT_CROP, spyMuxer.capturedFramingMode)

        // Metadata persisted PORTRAIT_CROP for active session
        val recoverable = videoStorageManager.findRecoverableTempSessions()
        assertEquals(1, recoverable.size)
        assertEquals("PORTRAIT_CROP", recoverable.first().metadata?.framingModeName)
        assertEquals(CameraFramingMode.PORTRAIT_CROP, recoverable.first().framingMode)

        // Next guest session picks up Admin's updated WIDE_FIT setting
        studioVm.startNewGuestSession()
        assertEquals(CameraFramingMode.WIDE_FIT, studioVm.uiState.value.cameraFramingMode)
    }

    @Test
    fun testLegacyTempMetadataWithoutFramingDefaultsToWideFit() = runTest {
        val recId = "rec_legacy_no_framing"
        val muxFile = videoStorageManager.createTempMuxedVideoFile(recId).apply {
            writeBytes(ByteArray(2048) { 0x11 })
        }
        val legacyProps = Properties().apply {
            setProperty("recordingId", recId)
            setProperty("videoFileName", muxFile.name)
            setProperty("songId", testSong.id)
            setProperty("songTitle", testSong.title)
            setProperty("songArtist", testSong.artist)
            setProperty("effectName", RetroEffectType.NONE.name)
            setProperty("durationSeconds", "15")
            setProperty("createdAtMs", "123456")
            setProperty("stage", "PREVIEW_READY")
            // framingModeName intentionally omitted
        }
        FileOutputStream(File(videoStorageManager.tempDir, "meta_${recId}.properties")).use { fos ->
            legacyProps.store(fos, "Legacy test metadata")
        }

        val sessions = videoStorageManager.findRecoverableTempSessions()
        assertEquals(1, sessions.size)
        assertEquals(CameraFramingMode.WIDE_FIT, sessions.first().framingMode)
        assertEquals(CameraFramingMode.WIDE_FIT.name, sessions.first().metadata?.framingModeName)
    }

    @Test
    fun testCameraPreviewControllerSwitchesScaleTypeWithoutChangingImplementationModeOrMirroring() {
        val controller = CameraPreviewController(context)
        val previewView = PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }

        controller.setMirrored(previewView, true)
        controller.setFramingMode(previewView, CameraFramingMode.WIDE_FIT)
        assertEquals(PreviewView.ScaleType.FIT_CENTER, previewView.scaleType)
        assertEquals(PreviewView.ImplementationMode.COMPATIBLE, previewView.implementationMode)
        assertEquals(-1f, previewView.scaleX, 0.001f)

        controller.setFramingMode(previewView, CameraFramingMode.PORTRAIT_CROP)
        assertEquals(PreviewView.ScaleType.FILL_CENTER, previewView.scaleType)
        assertEquals(PreviewView.ImplementationMode.COMPATIBLE, previewView.implementationMode)
        assertEquals(-1f, previewView.scaleX, 0.001f)
    }

    @Test
    fun testDanceStudioScreenOmitsGuestFramingSelectorAndDiagnosticBadges() {
        composeRule.setContent {
            RetroTokTheme {
                DanceStudioScreen(
                    uiState = StudioUiState(
                        selectedSong = testSong,
                        studioState = StudioState.READY,
                        cameraFramingMode = CameraFramingMode.WIDE_FIT,
                        isVideoCaptureBound = true
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

        composeRule.onNodeWithTag("studio_framing_toggle").assertDoesNotExist()
        composeRule.onNodeWithTag("studio_framing_portrait_crop").assertDoesNotExist()
        composeRule.onNodeWithTag("studio_framing_wide_view").assertDoesNotExist()
        composeRule.onNodeWithText("Portrait Crop").assertDoesNotExist()
        composeRule.onNodeWithText("Wide View").assertDoesNotExist()
        composeRule.onNodeWithTag("studio_video_capture_status_badge").assertDoesNotExist()
        composeRule.onNodeWithTag("studio_camera_diagnostics_badge").assertDoesNotExist()
        composeRule.onNodeWithTag("studio_record_button").assertIsDisplayed()
    }
}
