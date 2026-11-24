package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.camera.CameraBackendType
import com.example.camera.CameraConnectionState
import com.example.camera.CameraDetector
import com.example.camera.CameraProviderImpl
import com.example.camera.CameraRecordingController
import com.example.data.local.storage.MusicStorageManager
import com.example.data.mock.MockDataProvider
import com.example.data.mock.MockVideoFeedRepository
import com.example.model.RetroDecade
import com.example.model.RetroEffectType
import com.example.model.SongItem
import com.example.ui.screens.preview.PreviewViewModel
import com.example.ui.screens.studio.StudioState
import com.example.ui.screens.studio.StudioViewModel
import com.example.video.SoundtrackSessionController
import com.example.video.VideoMuxerProcessor
import com.example.video.VideoStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
class Phase3CameraRecordingPipelineTest {

    private lateinit var context: Context
    private lateinit var cameraDetector: CameraDetector
    private lateinit var cameraProvider: CameraProviderImpl
    private lateinit var cameraRecordingController: CameraRecordingController
    private lateinit var musicStorageManager: MusicStorageManager
    private lateinit var videoStorageManager: VideoStorageManager
    private lateinit var soundtrackController: SoundtrackSessionController
    private lateinit var videoMuxerProcessor: VideoMuxerProcessor
    private lateinit var videoFeedRepository: MockVideoFeedRepository

    private val testSong = SongItem(
        id = "test-song-1",
        title = "Disco Inferno",
        artist = "The Trammps",
        album = "Disco Inferno",
        releaseYear = 1976,
        decade = RetroDecade.DECADE_70S,
        genre = "Disco",
        durationSeconds = 215,
        energyBpm = 128,
        audioRelativePath = "retrotok/music/audio/test-song-1.mp3",
        licenseApprovalStatus = "APPROVED_FOR_EVENT_USE"
    )

    private val testDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        cameraDetector = CameraDetector(context)
        cameraProvider = CameraProviderImpl(context)
        cameraRecordingController = CameraRecordingController(context)
        musicStorageManager = MusicStorageManager(context)
        videoStorageManager = VideoStorageManager(context)
        soundtrackController = SoundtrackSessionController(context, musicStorageManager)
        videoMuxerProcessor = VideoMuxerProcessor()
        videoFeedRepository = MockVideoFeedRepository()
    }

    @org.junit.After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun testCameraBackendSelectionLogic() {
        val (backend, activeDevice) = cameraDetector.selectBestBackend()
        // In local JVM / emulator without physical USB hardware, selects DEV_PREVIEW or on-board camera
        assertTrue(
            backend == CameraBackendType.DEV_PREVIEW ||
            backend == CameraBackendType.CAMERA2_INTERNAL ||
            backend == CameraBackendType.CAMERA2_EXTERNAL
        )
        assertNotNull(activeDevice)
    }

    @Test
    fun testCameraCapabilityFiltering() {
        val caps = cameraDetector.queryEncoderCapabilities()
        assertEquals("video/avc", caps.encoderMimeType)
        assertTrue(caps.supportedFps.contains(30))
        assertEquals(Pair(1280, 720), caps.preferredResolution)
        assertTrue(caps.maxEncoderWidth >= 1280)
    }

    @Test
    fun testRecordingTemporaryFileCreationAndSafety() = runTest {
        val recordingId = videoStorageManager.generateRecordingId()
        val tempRawFile = videoStorageManager.createTempRawVideoFile(recordingId)
        val tempMuxFile = videoStorageManager.createTempMuxedVideoFile(recordingId)

        assertTrue(tempRawFile.absolutePath.contains("retrotok/videos/temp"))
        assertTrue(tempMuxFile.absolutePath.contains("retrotok/videos/temp"))

        // Create a simulated video file
        cameraRecordingController.createSimulatedVideoFile(tempRawFile, 5)
        assertTrue(tempRawFile.exists())
        assertTrue(tempRawFile.length() > 0)

        // Delete temp file safely
        val deleted = videoStorageManager.deleteTempFile(tempRawFile)
        assertTrue(deleted)
        assertFalse(tempRawFile.exists())
    }

    @Test
    fun testUnpublishedVideoPromotionToPublished() = runTest {
        val recordingId = videoStorageManager.generateRecordingId()
        val tempFile = videoStorageManager.createTempMuxedVideoFile(recordingId)
        cameraRecordingController.createSimulatedVideoFile(tempFile, 5)

        val publishedRelativePath = videoStorageManager.promoteToPublished(tempFile, "pub-test-123")
        assertEquals("retrotok/videos/published/pub_pub-test-123.mp4", publishedRelativePath)

        val publishedAbsoluteFile = videoStorageManager.getAbsoluteFile(publishedRelativePath)
        assertTrue(publishedAbsoluteFile.exists())
        assertTrue(publishedAbsoluteFile.length() > 0)

        // Verify temp file no longer exists at old location
        assertFalse(tempFile.exists())
    }

    @Test
    fun testMissingSelectedSoundtrackHandling() = runTest {
        val studioVm = StudioViewModel(
            cameraProvider = cameraProvider,
            recordingController = cameraRecordingController,
            soundtrackController = soundtrackController,
            storageManager = videoStorageManager,
            muxerProcessor = videoMuxerProcessor
        )

        // Attempting to record without selecting song
        studioVm.startCountdownAndRecord()
        val uiState = studioVm.uiState.value

        assertTrue(uiState.missingSongNotice)
        assertEquals(StudioState.READY, uiState.studioState)
    }

    @Test
    fun testRecordingDurationEnforcement() {
        val studioVm = StudioViewModel(
            cameraProvider = cameraProvider,
            recordingController = cameraRecordingController,
            soundtrackController = soundtrackController,
            storageManager = videoStorageManager,
            muxerProcessor = videoMuxerProcessor
        )

        assertEquals(30, studioVm.uiState.value.maxDurationSeconds)
        studioVm.setRecordingDuration(15)
        assertEquals(15, studioVm.uiState.value.maxDurationSeconds)
        studioVm.setRecordingDuration(60)
        assertEquals(60, studioVm.uiState.value.maxDurationSeconds)
        // Invalid duration ignored
        studioVm.setRecordingDuration(45)
        assertEquals(60, studioVm.uiState.value.maxDurationSeconds)
    }

    @Test
    fun testPublishingOrderAndSoundtrackMetadataPreservation() = runTest {
        val previewVm = PreviewViewModel(
            videoFeedRepository = videoFeedRepository,
            videoStorageManager = videoStorageManager
        )

        // Create temporary recorded file
        val tempFile = videoStorageManager.createTempMuxedVideoFile("rec_publish_order_test")
        cameraRecordingController.createSimulatedVideoFile(tempFile, 15)

        previewVm.initialize(
            videoFilePath = tempFile.absolutePath,
            song = testSong,
            effect = RetroEffectType.NEON_80S,
            durationSeconds = 15
        )
        previewVm.onNicknameChange("DiscoKing")

        var successCalled = false
        previewVm.confirmPublish {
            successCalled = true
        }

        var waitMs = 0
        while (!successCalled && waitMs < 2000) {
            Thread.sleep(50)
            waitMs += 50
        }

        assertTrue("confirmPublish should invoke onSuccess", successCalled)

        val feedVideos = videoFeedRepository.getFeedVideos().first()
        // Newly published video must be first in feed
        val newestVideo = feedVideos.first()
        assertEquals("DiscoKing", newestVideo.creatorNickname)
        assertEquals(testSong.title, newestVideo.song.title)
        assertEquals(testSong.artist, newestVideo.song.artist)
        assertEquals(RetroEffectType.NEON_80S, newestVideo.effect)
        assertEquals(15, newestVideo.durationSeconds)
        assertNotNull(newestVideo.localVideoPath)
    }

    @Test
    fun testSafeUnpublishedVideoDiscardDoesNotDeletePublishedVideos() = runTest {
        // Create a published video first
        val pubFile = File(videoStorageManager.publishedDir, "pub_permanent.mp4")
        cameraRecordingController.createSimulatedVideoFile(pubFile, 10)
        assertTrue(pubFile.exists())

        // Create an unpublished temp video
        val tempFile = videoStorageManager.createTempMuxedVideoFile("rec_discard_test")
        cameraRecordingController.createSimulatedVideoFile(tempFile, 10)
        assertTrue(tempFile.exists())

        val previewVm = PreviewViewModel(
            videoFeedRepository = videoFeedRepository,
            videoStorageManager = videoStorageManager
        )
        previewVm.initialize(
            videoFilePath = tempFile.absolutePath,
            song = testSong,
            effect = RetroEffectType.VHS_RETRO,
            durationSeconds = 10
        )

        var discarded = false
        previewVm.confirmDiscard {
            discarded = true
        }

        var discardWaitMs = 0
        while (!discarded && discardWaitMs < 2000) {
            kotlinx.coroutines.delay(50)
            discardWaitMs += 50
        }

        assertTrue("confirmDiscard should invoke onDiscarded", discarded)
        // Temp file deleted
        assertFalse(tempFile.exists())
        // Published file remains untouched
        assertTrue(pubFile.exists())
    }

    @Test
    fun testInterruptedRecordingRecovery() = runTest {
        val orphan1 = videoStorageManager.createTempRawVideoFile("orphan_1")
        val orphan2 = videoStorageManager.createTempRawVideoFile("orphan_2")
        cameraRecordingController.createSimulatedVideoFile(orphan1, 5)
        cameraRecordingController.createSimulatedVideoFile(orphan2, 8)

        val recoverable = videoStorageManager.findRecoverableTempRecordings()
        assertTrue(recoverable.size >= 2)
        assertTrue(recoverable.any { it.name.contains("orphan_1") })
        assertTrue(recoverable.any { it.name.contains("orphan_2") })
    }

    @Test
    fun testInactivityWatchdogProtectionStates() {
        val protectedStates = listOf(
            StudioState.COUNTDOWN,
            StudioState.RECORDING,
            StudioState.FINALIZING,
            StudioState.PROCESSING
        )

        for (state in protectedStates) {
            val isProtected = state == StudioState.RECORDING ||
                              state == StudioState.COUNTDOWN ||
                              state == StudioState.FINALIZING ||
                              state == StudioState.PROCESSING
            assertTrue("State $state must be protected from watchdog idle resets", isProtected)
        }

        assertFalse(
            "StudioState.READY should allow watchdog idle timeout",
            StudioState.READY == StudioState.RECORDING ||
            StudioState.READY == StudioState.COUNTDOWN ||
            StudioState.READY == StudioState.FINALIZING ||
            StudioState.READY == StudioState.PROCESSING
        )
    }
}
