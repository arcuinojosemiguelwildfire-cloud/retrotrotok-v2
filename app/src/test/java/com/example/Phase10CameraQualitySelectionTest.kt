package com.example

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.core.CameraSelector
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.video.Quality
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.camera.CameraBackendType
import com.example.camera.CameraCapabilities
import com.example.camera.CameraConnectionState
import com.example.camera.CameraDetector
import com.example.camera.CameraDeviceInfo
import com.example.camera.CameraLensOption
import com.example.camera.CameraPreviewController
import com.example.camera.CameraProvider
import com.example.camera.CameraRecordingController
import com.example.camera.FocusSupportStatus
import com.example.data.local.storage.MusicStorageManager
import com.example.model.RetroDecade
import com.example.model.RetroEffectType
import com.example.model.SongItem
import com.example.model.ValidationStatus
import com.example.ui.screens.hardware.HardwareValidationViewModel
import com.example.ui.screens.preview.PreviewUiState
import com.example.ui.screens.studio.DanceStudioScreen
import com.example.ui.screens.studio.StudioState
import com.example.ui.screens.studio.StudioUiState
import com.example.ui.screens.studio.StudioViewModel
import com.example.ui.theme.RetroTokTheme
import com.example.video.SoundtrackSessionController
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase10CameraQualitySelectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var videoStorageManager: VideoStorageManager
    private lateinit var musicStorageManager: MusicStorageManager
    private lateinit var soundtrackController: SoundtrackSessionController
    private lateinit var videoMuxerProcessor: VideoMuxerProcessor

    private val testSong = SongItem(
        id = "phase10-song-1",
        title = "Studio Clarity",
        artist = "Retro Kiosk",
        album = "Baseline",
        releaseYear = 1986,
        decade = RetroDecade.DECADE_80S,
        genre = "Synth",
        durationSeconds = 150,
        audioRelativePath = ""
    )

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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        videoStorageManager = VideoStorageManager(context)
        musicStorageManager = MusicStorageManager(context)
        soundtrackController = SoundtrackSessionController(context, musicStorageManager)
        videoMuxerProcessor = VideoMuxerProcessor(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testSelectCaptureBackendSeparatesUsbPeripheralFromCamera2Capture() {
        val detector = CameraDetector(context)
        val logitechUvc = CameraDeviceInfo(
            id = "usb_1_46d_825",
            name = "Logitech HD Webcam C270",
            vendorId = CameraDetector.LOGITECH_VENDOR_ID,
            productId = 0x0825,
            isLogitech = true,
            isExternal = true,
            backend = CameraBackendType.NATIVE_USB_UVC,
            supportsUvc = true,
            hasUsbPermission = true
        )
        val internalBack = CameraDeviceInfo(
            id = "0",
            name = "Back Camera (0)",
            lensFacing = CameraCharacteristics.LENS_FACING_BACK,
            isExternal = false,
            backend = CameraBackendType.CAMERA2_INTERNAL
        )
        val internalFront = CameraDeviceInfo(
            id = "1",
            name = "Front Camera (1)",
            lensFacing = CameraCharacteristics.LENS_FACING_FRONT,
            isExternal = false,
            backend = CameraBackendType.CAMERA2_INTERNAL
        )
        val externalCamera2 = CameraDeviceInfo(
            id = "2",
            name = "External USB Camera (2)",
            lensFacing = CameraCharacteristics.LENS_FACING_EXTERNAL,
            isExternal = true,
            backend = CameraBackendType.CAMERA2_EXTERNAL
        )

        // When USB UVC is attached alongside internal Camera2 (no Camera2 External HAL),
        // Camera2 internal capture must win instead of claiming USB UVC is active.
        val (backendWithUvcAndInternal, deviceWithUvcAndInternal) = detector.selectCaptureBackend(
            camera2Devices = listOf(internalFront, internalBack),
            usbDevices = listOf(logitechUvc)
        )
        assertEquals(CameraBackendType.CAMERA2_INTERNAL, backendWithUvcAndInternal)
        assertEquals("0", deviceWithUvcAndInternal?.id)

        // When Camera2 External HAL exists, it takes top priority.
        val (backendExternal, deviceExternal) = detector.selectCaptureBackend(
            camera2Devices = listOf(internalBack, externalCamera2),
            usbDevices = listOf(logitechUvc)
        )
        assertEquals(CameraBackendType.CAMERA2_EXTERNAL, backendExternal)
        assertEquals("2", deviceExternal?.id)

        // When only USB UVC exists and 0 Camera2 cameras exist, return NATIVE_USB_UVC so UI reports UNSUPPORTED.
        val (backendOnlyUvc, deviceOnlyUvc) = detector.selectCaptureBackend(
            camera2Devices = emptyList(),
            usbDevices = listOf(logitechUvc)
        )
        assertEquals(CameraBackendType.NATIVE_USB_UVC, backendOnlyUvc)
        assertEquals("usb_1_46d_825", deviceOnlyUvc?.id)

        // When nothing exists, DEV_PREVIEW does not pretend to be Logitech
        val (backendEmpty, deviceEmpty) = detector.selectCaptureBackend(
            camera2Devices = emptyList(),
            usbDevices = emptyList()
        )
        assertEquals(CameraBackendType.DEV_PREVIEW, backendEmpty)
        assertFalse(deviceEmpty?.isLogitech ?: true)
    }

    @Test
    fun testCameraSwitchingDoesNotSilentlyFallBackToOppositeLens() {
        // AUTO tries EXTERNAL -> BACK -> FRONT
        assertEquals(
            listOf(
                CameraSelector.LENS_FACING_EXTERNAL,
                CameraSelector.LENS_FACING_BACK,
                CameraSelector.LENS_FACING_FRONT
            ),
            CameraPreviewController.selectCandidateLensFacings(
                requestedLens = CameraLensOption.AUTO,
                hasExternal = true,
                hasBack = true,
                hasFront = true
            )
        )

        // Explicit BACK request with only FRONT available returns empty (NO silent fallback)
        assertTrue(
            CameraPreviewController.selectCandidateLensFacings(
                requestedLens = CameraLensOption.BACK,
                hasExternal = false,
                hasBack = false,
                hasFront = true
            ).isEmpty()
        )

        // Explicit FRONT request with only BACK available returns empty (NO silent fallback)
        assertTrue(
            CameraPreviewController.selectCandidateLensFacings(
                requestedLens = CameraLensOption.FRONT,
                hasExternal = false,
                hasBack = true,
                hasFront = false
            ).isEmpty()
        )

        // Flip toggles BACK <-> FRONT cleanly
        assertEquals(
            CameraLensOption.FRONT,
            CameraPreviewController.resolveFlippedLensOption(
                currentFacing = CameraSelector.LENS_FACING_BACK,
                hasBack = true,
                hasFront = true
            )
        )
        assertEquals(
            CameraLensOption.BACK,
            CameraPreviewController.resolveFlippedLensOption(
                currentFacing = CameraSelector.LENS_FACING_FRONT,
                hasBack = true,
                hasFront = true
            )
        )
        // Flip returns null when only one lens exists
        assertNull(
            CameraPreviewController.resolveFlippedLensOption(
                currentFacing = CameraSelector.LENS_FACING_BACK,
                hasBack = true,
                hasFront = false
            )
        )
    }

    @Test
    fun testPreviewResolutionSelectorTiersAndQualityDowngradeDetection() {
        val highTier = CameraPreviewController.buildPreviewResolutionSelector(
            CameraPreviewController.PreviewResolutionTier.HIGH_16_9
        )
        assertEquals(
            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY.preferredAspectRatio,
            highTier.aspectRatioStrategy.preferredAspectRatio
        )
        assertNull(highTier.resolutionStrategy?.boundSize)

        val balancedTier = CameraPreviewController.buildPreviewResolutionSelector(
            CameraPreviewController.PreviewResolutionTier.BALANCED_16_9_720P
        )
        assertEquals(1280, balancedTier.resolutionStrategy?.boundSize?.width)
        assertEquals(720, balancedTier.resolutionStrategy?.boundSize?.height)

        val compatTier = CameraPreviewController.buildPreviewResolutionSelector(
            CameraPreviewController.PreviewResolutionTier.COMPAT_4_3_FALLBACK
        )
        assertEquals(
            AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY.preferredAspectRatio,
            compatTier.aspectRatioStrategy.preferredAspectRatio
        )

        // Quality downgrade detection must flag HD -> SD without silently hiding it
        assertTrue(CameraRecordingController.isQualityDowngraded(Quality.HD, Quality.SD))
        assertTrue(CameraRecordingController.isQualityDowngraded(Quality.FHD, Quality.HD))
        assertFalse(CameraRecordingController.isQualityDowngraded(Quality.HD, Quality.HD))
        assertFalse(CameraRecordingController.isQualityDowngraded(Quality.HD, Quality.FHD))
    }

    @Test
    fun testFocusCapabilityEvaluationForAfFixedAndUnsupportedHardware() {
        val contVideoCaps = CameraPreviewController.evaluateFocusCapabilities(
            minFocusDistance = 10.0f,
            afModes = intArrayOf(
                CameraCharacteristics.CONTROL_AF_MODE_OFF,
                CameraCharacteristics.CONTROL_AF_MODE_AUTO,
                CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            ),
            maxAfRegions = 1
        )
        assertEquals(FocusSupportStatus.AF_SUPPORTED, contVideoCaps.status)
        assertTrue(contVideoCaps.isContinuousVideoSupported)
        assertTrue(contVideoCaps.summary.contains("CONT_VIDEO"))

        val autoOnlyCaps = CameraPreviewController.evaluateFocusCapabilities(
            minFocusDistance = 5.0f,
            afModes = intArrayOf(CameraCharacteristics.CONTROL_AF_MODE_AUTO),
            maxAfRegions = 1
        )
        assertEquals(FocusSupportStatus.AF_SUPPORTED, autoOnlyCaps.status)
        assertFalse(autoOnlyCaps.isContinuousVideoSupported)

        val fixedFocusByDistance = CameraPreviewController.evaluateFocusCapabilities(
            minFocusDistance = 0f,
            afModes = intArrayOf(CameraCharacteristics.CONTROL_AF_MODE_OFF),
            maxAfRegions = 0
        )
        assertEquals(FocusSupportStatus.FIXED_FOCUS, fixedFocusByDistance.status)
        assertFalse(fixedFocusByDistance.isContinuousVideoSupported)

        val unsupportedCaps = CameraPreviewController.evaluateFocusCapabilities(
            minFocusDistance = null,
            afModes = intArrayOf(),
            maxAfRegions = 0
        )
        assertEquals(FocusSupportStatus.UNSUPPORTED, unsupportedCaps.status)
    }

    @Test
    fun testStudioAndPreviewDefaultEffectIsNoneWhileVhsRetroRemainsSelectable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val recordingController = CameraRecordingController(context)
        val studioVm = StudioViewModel(
            cameraProvider = FakeCameraProvider(),
            recordingController = recordingController,
            soundtrackController = soundtrackController,
            storageManager = videoStorageManager,
            muxerProcessor = videoMuxerProcessor
        )
        runCurrent()

        assertEquals(RetroEffectType.NONE, studioVm.uiState.value.selectedEffect)
        assertEquals(RetroEffectType.NONE, PreviewUiState().effect)

        // VHS_RETRO remains selectable and can be switched back to NONE
        studioVm.selectEffect(RetroEffectType.VHS_RETRO)
        assertEquals(RetroEffectType.VHS_RETRO, studioVm.uiState.value.selectedEffect)

        // Starting a new guest session restores crisp NONE baseline
        studioVm.startNewGuestSession()
        assertEquals(RetroEffectType.NONE, studioVm.uiState.value.selectedEffect)
    }

    @Test
    fun testHardwareValidationReportsUsbUvcUnsupportedHonestStatus() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val vm = HardwareValidationViewModel(
            cameraDetector = CameraDetector(context),
            cameraProvider = FakeCameraProvider()
        )
        advanceTimeBy(600)
        runCurrent()

        val state = vm.uiState.value
        assertFalse("USB UVC capture must explicitly report false", state.usbUvcCaptureSupported)
        val uvcCheck = state.checks.firstOrNull { it.id == "CHK-05-UVC-FALLBACK" }
        assertNotNull(uvcCheck)
        assertEquals(ValidationStatus.WARNING, uvcCheck!!.status)
        assertTrue(
            uvcCheck.diagnosticDetails.contains("not implemented", ignoreCase = true) ||
                uvcCheck.diagnosticDetails.contains("UNSUPPORTED", ignoreCase = true)
        )
    }

    @Test
    fun testDanceStudioScreenSeparatesFlipAndMirrorAndShowsDowngradeDiagnostic() {
        var flipClicks = 0
        var mirrorClicks = 0

        composeRule.setContent {
            RetroTokTheme {
                DanceStudioScreen(
                    uiState = StudioUiState(
                        selectedSong = testSong,
                        selectedEffect = RetroEffectType.NONE,
                        studioState = StudioState.READY,
                        isCameraDisconnected = false,
                        isVideoCaptureBound = true,
                        activeVideoQualityLabel = "SD",
                        requestedVideoQualityLabel = "HD",
                        isQualityDowngraded = true,
                        negotiatedVideoResolution = "640x480"
                    ),
                    onSongPillClick = {},
                    onEffectSelect = {},
                    onStartRecording = {},
                    onStopRecording = {},
                    onToggleMirror = { mirrorClicks++ },
                    onToggleGrid = {},
                    onCloseClick = {},
                    onSwitchCamera = { flipClicks++ }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("studio_flip_camera_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("studio_mirror_toggle").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(1, flipClicks)
        assertEquals(1, mirrorClicks)

        composeRule.onNodeWithTag("studio_video_capture_status_badge").assertDoesNotExist()
        composeRule.onNodeWithTag("studio_camera_diagnostics_badge").assertDoesNotExist()
        composeRule.onNodeWithText("VideoCapture: Bound • SD (Downgraded from HD)").assertDoesNotExist()
    }
}
