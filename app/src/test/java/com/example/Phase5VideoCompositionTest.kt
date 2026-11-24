package com.example

import android.content.Context
import android.media.MediaFormat
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.camera.CameraProviderImpl
import com.example.camera.CameraRecordingController
import com.example.data.local.storage.MusicStorageManager
import com.example.model.RetroDecade
import com.example.model.RetroEffectType
import com.example.model.SongItem
import com.example.ui.screens.studio.StudioState
import com.example.ui.screens.studio.StudioViewModel
import com.example.video.DeviceMediaCapabilities
import com.example.video.EffectShaderConfig
import com.example.video.MuxerResult
import com.example.video.SoundtrackSessionController
import com.example.video.TempRecordingMetadata
import com.example.video.VideoMuxerProcessor
import com.example.video.VideoStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class Phase5VideoCompositionTest {

    private lateinit var context: Context
    private lateinit var videoStorageManager: VideoStorageManager
    private lateinit var musicStorageManager: MusicStorageManager
    private lateinit var cameraRecordingController: CameraRecordingController
    private lateinit var videoMuxerProcessor: VideoMuxerProcessor
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        videoStorageManager = VideoStorageManager(context)
        musicStorageManager = MusicStorageManager(context)
        cameraRecordingController = CameraRecordingController(context)
        videoMuxerProcessor = VideoMuxerProcessor(context)
        videoStorageManager.clearTempStorage()
    }

    @After
    fun tearDown() {
        videoStorageManager.clearTempStorage()
        Dispatchers.resetMain()
    }

    @Test
    fun testAdaptivePortraitResolutionSelection() {
        // Preferred 720x1280 selected when hardware supports full HD portrait
        val fullHd = DeviceMediaCapabilities.selectAdaptivePortraitResolution(
            maxSupportedWidth = 1088,
            maxSupportedHeight = 1920,
            widthAlignment = 16,
            heightAlignment = 16
        )
        assertEquals(Pair(720, 1280), fullHd)

        // Adapts cleanly down to 544x960 when height cap is 1080 (common on API 23 SoCs)
        val constrainedHeight = DeviceMediaCapabilities.selectAdaptivePortraitResolution(
            maxSupportedWidth = 1920,
            maxSupportedHeight = 1080,
            widthAlignment = 16,
            heightAlignment = 16
        )
        assertEquals(Pair(544, 960), constrainedHeight)
        assertTrue(constrainedHeight.second > constrainedHeight.first)

        // Adapts further down on low-capability devices while preserving alignment
        val lowCap = DeviceMediaCapabilities.selectAdaptivePortraitResolution(
            maxSupportedWidth = 500,
            maxSupportedHeight = 860,
            widthAlignment = 16,
            heightAlignment = 16
        )
        assertEquals(0, lowCap.first % 16)
        assertEquals(0, lowCap.second % 16)
        assertTrue(lowCap.second > lowCap.first)
    }

    @Test
    fun testAllRetroEffectsHaveDistinctShaderSpecsAndCleanOriginal() {
        val allSpecs = RetroEffectType.values().map { EffectShaderConfig.getSpec(it) }
        val modeIds = allSpecs.map { it.shaderModeId }.toSet()
        assertEquals("Every RetroEffectType must map to a unique shader mode", RetroEffectType.values().size, modeIds.size)

        val original = EffectShaderConfig.getSpec(RetroEffectType.NONE)
        assertEquals(EffectShaderConfig.MODE_ORIGINAL, original.shaderModeId)
        assertEquals(1.0f, original.contrast, 0.001f)
        assertEquals(1.0f, original.saturation, 0.001f)
        assertEquals(0.0f, original.grainStrength, 0.001f)
        assertEquals(0.0f, original.chromaShift, 0.001f)
        assertEquals(Color.Transparent, original.previewTintColor)

        val noir = EffectShaderConfig.getSpec(RetroEffectType.BW_NOIR)
        assertEquals(0.0f, noir.saturation, 0.001f)
        assertTrue(noir.contrast > 1.0f)

        val vhs = EffectShaderConfig.getSpec(RetroEffectType.VHS_RETRO)
        assertTrue(vhs.chromaShift > 0f)
        assertTrue(vhs.trackingStrength > 0f)
        assertTrue(vhs.grainStrength > 0f)
    }

    @Test
    fun testPortraitCenterCropTransformPreventsStretching() {
        // 16:9 landscape webcam (1280x720) -> 9:16 portrait output (720x1280)
        val landscapeCrop = EffectShaderConfig.computePortraitCropTransform(
            sourceWidth = 1280,
            sourceHeight = 720,
            rotationDegrees = 0,
            targetWidth = 720,
            targetHeight = 1280,
            isMirrored = true
        )
        assertEquals(1280, landscapeCrop.effectiveSourceWidth)
        assertEquals(720, landscapeCrop.effectiveSourceHeight)
        assertTrue("Landscape input into portrait must crop X (< 1.0)", landscapeCrop.uvScaleX < 0.4f)
        assertEquals(1.0f, landscapeCrop.uvScaleY, 0.001f)
        assertTrue(landscapeCrop.needsMirroring)

        // Rotated 90deg 1280x720 input becomes 720x1280 effective portrait -> no crop needed
        val rotatedPortrait = EffectShaderConfig.computePortraitCropTransform(
            sourceWidth = 1280,
            sourceHeight = 720,
            rotationDegrees = 90,
            targetWidth = 720,
            targetHeight = 1280,
            isMirrored = false
        )
        assertEquals(720, rotatedPortrait.effectiveSourceWidth)
        assertEquals(1280, rotatedPortrait.effectiveSourceHeight)
        assertEquals(1.0f, rotatedPortrait.uvScaleX, 0.01f)
        assertEquals(1.0f, rotatedPortrait.uvScaleY, 0.01f)
        assertTrue(EffectShaderConfig.isAlreadyPortrait9x16(1280, 720, 90))
        assertFalse(EffectShaderConfig.isAlreadyPortrait9x16(1280, 720, 0))
    }

    @Test
    fun testAudioMimeMuxerCompatibilityCheck() {
        assertTrue(DeviceMediaCapabilities.isMimeMediaMuxerCompatibleAudio(MediaFormat.MIMETYPE_AUDIO_AAC))
        assertFalse(DeviceMediaCapabilities.isMimeMediaMuxerCompatibleAudio(MediaFormat.MIMETYPE_AUDIO_MPEG))
        assertFalse(DeviceMediaCapabilities.isMimeMediaMuxerCompatibleAudio(null))
    }

    @Test
    fun testNonDestructiveRecoverySessionsAndCleanup() = runTest {
        val recId = "rec_recover_phase5"
        val rawFile = videoStorageManager.createTempRawVideoFile(recId)
        val muxFile = videoStorageManager.createTempMuxedVideoFile(recId)
        val stagingFile = File(videoStorageManager.tempDir, "comp_vid_staging.mp4")

        cameraRecordingController.createSimulatedVideoFile(rawFile, 5)
        Thread.sleep(20)
        cameraRecordingController.createSimulatedVideoFile(muxFile, 5)
        stagingFile.writeBytes(ByteArray(4096))

        videoStorageManager.saveTempRecordingMetadata(
            TempRecordingMetadata(
                recordingId = recId,
                videoFileName = muxFile.name,
                songId = "song-80s",
                songTitle = "Take On Me",
                songArtist = "a-ha",
                effectName = RetroEffectType.NEON_80S.name,
                durationSeconds = 15,
                createdAtMs = System.currentTimeMillis(),
                stage = "PREVIEW_READY"
            )
        )

        val publishedFile = File(videoStorageManager.publishedDir, "pub_keep_safe.mp4").apply {
            writeBytes(ByteArray(4096))
        }

        val sessions = videoStorageManager.findRecoverableTempSessions()
        assertEquals("Should deduplicate raw+mux for same recordingId and ignore comp_vid_", 1, sessions.size)
        val recovered = sessions.first()
        assertEquals(muxFile.name, recovered.file.name)
        assertEquals(RetroEffectType.NEON_80S, recovered.effectType)
        assertEquals("Take On Me", recovered.displayTitle)
        assertNotNull(recovered.metadata)

        // Discarding session cleans up mux, raw, and metadata without touching published/
        videoStorageManager.discardRecoverableSession(recovered)
        assertFalse(muxFile.exists())
        assertFalse(rawFile.exists())
        assertTrue(publishedFile.exists())
        publishedFile.delete()
        stagingFile.delete()
    }

    @Test
    fun testMuxerProcessorPreservesFallbackAndStudioTracksProgress() = runTest {
        val rawFile = videoStorageManager.createTempRawVideoFile("rec_phase5_stub")
        val outFile = videoStorageManager.createTempMuxedVideoFile("rec_phase5_stub")
        cameraRecordingController.createSimulatedVideoFile(rawFile, 5)

        val progressUpdates = mutableListOf<Float>()
        val result = videoMuxerProcessor.processAndMux(
            rawVideoFile = rawFile,
            audioFile = null,
            outputFile = outFile,
            durationMs = 5000L,
            effect = RetroEffectType.VHS_RETRO,
            isMirrored = true,
            onProgress = { progress, _ -> progressUpdates.add(progress) }
        )

        assertTrue("Stub recording should cleanly produce Success fallback file", result is MuxerResult.Success)
        assertTrue(outFile.exists())
        assertTrue(progressUpdates.isNotEmpty())
        assertEquals(1.0f, progressUpdates.last(), 0.001f)

        // Verify StudioViewModel initializes with clean progress state
        val studioVm = StudioViewModel(
            cameraProvider = CameraProviderImpl(context),
            recordingController = cameraRecordingController,
            soundtrackController = SoundtrackSessionController(context, musicStorageManager),
            storageManager = videoStorageManager,
            muxerProcessor = videoMuxerProcessor
        )
        assertEquals(StudioState.READY, studioVm.uiState.value.studioState)
        assertEquals(0f, studioVm.uiState.value.processingProgress, 0.001f)
    }
}
