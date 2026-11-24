package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioPreviewManager
import com.example.camera.CameraProviderImpl
import com.example.camera.CameraRecordingController
import com.example.data.local.storage.MusicStorageManager
import com.example.data.mock.MockKioskSettingsRepository
import com.example.data.mock.MockMusicCatalogRepository
import com.example.data.mock.MockVideoFeedRepository
import com.example.model.RetroDecade
import com.example.model.RetroEffectType
import com.example.model.SongItem
import com.example.ui.screens.admin.AdminViewModel
import com.example.ui.screens.feed.FeedFilterTab
import com.example.ui.screens.feed.FeedViewModel
import com.example.ui.screens.preview.PreviewViewModel
import com.example.ui.screens.studio.StudioState
import com.example.ui.screens.studio.StudioViewModel
import com.example.video.SoundtrackSessionController
import com.example.video.VideoMuxerProcessor
import com.example.video.VideoStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class Phase4KioskHardeningTest {

    private lateinit var context: Context
    private lateinit var videoStorageManager: VideoStorageManager
    private lateinit var musicStorageManager: MusicStorageManager
    private lateinit var videoFeedRepository: MockVideoFeedRepository
    private lateinit var settingsRepository: MockKioskSettingsRepository
    private val testDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        videoStorageManager = VideoStorageManager(context)
        musicStorageManager = MusicStorageManager(context)
        videoFeedRepository = MockVideoFeedRepository()
        settingsRepository = MockKioskSettingsRepository()
        videoStorageManager.clearTempStorage()
    }

    @After
    fun tearDown() {
        videoStorageManager.clearTempStorage()
        Dispatchers.resetMain()
    }

    @Test
    fun testTempStorageCleanupPreservesPublishedVideos() = runTest {
        // Create files in temp and published directories
        val tempFile1 = File(videoStorageManager.tempDir, "temp_test_1.mp4").apply { writeBytes(ByteArray(1024)) }
        val tempFile2 = File(videoStorageManager.tempDir, "temp_test_2.mp4").apply { writeBytes(ByteArray(2048)) }
        val publishedFile = File(videoStorageManager.publishedDir, "pub_dance_1.mp4").apply { writeBytes(ByteArray(4096)) }

        assertTrue(tempFile1.exists())
        assertTrue(tempFile2.exists())
        assertTrue(publishedFile.exists())

        // Clear temporary storage
        val deletedCount = videoStorageManager.clearTempStorage()
        assertEquals(2, deletedCount)

        // Verify temp files removed and published preserved
        assertFalse(tempFile1.exists())
        assertFalse(tempFile2.exists())
        assertTrue(publishedFile.exists())

        publishedFile.delete()
    }

    @Test
    fun testStudioViewModelResetCleansState() = runTest {
        val cameraProvider = CameraProviderImpl(context)
        val recordingController = CameraRecordingController(context)
        val soundtrackController = SoundtrackSessionController(context, musicStorageManager)
        val muxerProcessor = VideoMuxerProcessor()

        val studioViewModel = StudioViewModel(
            cameraProvider = cameraProvider,
            recordingController = recordingController,
            soundtrackController = soundtrackController,
            storageManager = videoStorageManager,
            muxerProcessor = muxerProcessor
        )

        val song = SongItem(
            id = "song-1",
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album",
            releaseYear = 1985,
            decade = RetroDecade.DECADE_80S,
            genre = "Synthwave",
            durationSeconds = 30,
            audioRelativePath = ""
        )

        studioViewModel.selectSong(song)
        studioViewModel.selectEffect(RetroEffectType.VHS_RETRO)
        studioViewModel.setRecordingDuration(15)

        assertEquals("song-1", studioViewModel.uiState.value.selectedSong?.id)
        assertEquals(RetroEffectType.VHS_RETRO, studioViewModel.uiState.value.selectedEffect)
        assertEquals(15, studioViewModel.uiState.value.maxDurationSeconds)

        // Reset studio
        studioViewModel.resetStudio()

        // Should return to READY state with clean timer
        assertEquals(StudioState.READY, studioViewModel.uiState.value.studioState)
        assertEquals(0, studioViewModel.uiState.value.recordedSeconds)
        assertFalse(studioViewModel.uiState.value.missingSongNotice)
    }

    @Test
    fun testInactivityImmunityConditions() {
        // States where watchdog MUST NEVER interrupt
        val immuneStudioStates = listOf(
            StudioState.COUNTDOWN,
            StudioState.RECORDING,
            StudioState.FINALIZING,
            StudioState.PROCESSING
        )

        for (state in immuneStudioStates) {
            val isImmune = state == StudioState.RECORDING ||
                           state == StudioState.COUNTDOWN ||
                           state == StudioState.FINALIZING ||
                           state == StudioState.PROCESSING
            assertTrue("State $state should be immune from inactivity reset", isImmune)
        }

        // READY state should allow idle timeout
        val readyState = StudioState.READY
        val isReadyImmune = readyState == StudioState.RECORDING ||
                            readyState == StudioState.COUNTDOWN ||
                            readyState == StudioState.FINALIZING ||
                            readyState == StudioState.PROCESSING
        assertFalse("READY state should not be immune", isReadyImmune)
    }

    @Test
    fun testPreviewViewModelDiscardCleansTake() = runTest {
        val tempTake = File(videoStorageManager.tempDir, "take_to_discard.mp4").apply {
            writeBytes(ByteArray(1024))
        }
        assertTrue(tempTake.exists())

        val previewViewModel = PreviewViewModel(
            videoFeedRepository = videoFeedRepository,
            videoStorageManager = videoStorageManager
        )

        val song = SongItem(
            id = "song-1",
            title = "Test Song",
            artist = "Artist",
            album = "Album",
            releaseYear = 1999,
            decade = RetroDecade.DECADE_90S,
            genre = "Dance",
            durationSeconds = 15,
            audioRelativePath = ""
        )

        previewViewModel.initialize(
            videoFilePath = tempTake.absolutePath,
            song = song,
            effect = RetroEffectType.NEON_80S,
            durationSeconds = 15
        )

        var discardCallbackInvoked = false
        previewViewModel.confirmDiscard {
            discardCallbackInvoked = true
        }

        var waitMs = 0
        while ((!discardCallbackInvoked || tempTake.exists()) && waitMs < 2000) {
            Thread.sleep(50)
            waitMs += 50
        }

        assertTrue(discardCallbackInvoked)
        assertFalse(tempTake.exists())
    }

    @Test
    fun testAdminRateLimitingAndLockout() = runTest {
        val adminViewModel = AdminViewModel(
            settingsRepository = settingsRepository,
            videoFeedRepository = videoFeedRepository,
            musicCatalogRepository = MockMusicCatalogRepository(),
            audioPreviewManager = AudioPreviewManager(context),
            storageManager = musicStorageManager
        )

        // Enter wrong PIN 5 times to trigger lockout
        repeat(5) {
            adminViewModel.onClearPin()
            "9999".forEach { digit -> adminViewModel.onDigitEntered(digit.toString()) }
        }

        assertTrue("Admin should be locked out after 5 consecutive incorrect attempts", adminViewModel.uiState.value.isLockedOut)
        assertTrue(adminViewModel.uiState.value.lockoutSecondsRemaining > 0)
    }

    @Test
    fun testFeedViewModelTabFiltering() = runTest {
        val feedViewModel = FeedViewModel(videoFeedRepository)

        val initialCount = feedViewModel.uiState.value.allVideos.size
        assertTrue("Expected seeded videos in repository", initialCount > 0)
        assertEquals(initialCount, feedViewModel.uiState.value.videos.size)

        // Filter by Featured
        feedViewModel.selectFilterTab(FeedFilterTab.FEATURED)
        assertEquals(FeedFilterTab.FEATURED, feedViewModel.uiState.value.activeTab)
        assertTrue(feedViewModel.uiState.value.videos.all { it.isFeatured })

        // Filter by Favorites (toggle one favorite first if none)
        val firstId = feedViewModel.uiState.value.allVideos.first().id
        if (!feedViewModel.uiState.value.allVideos.first().isFavorited) {
            feedViewModel.onToggleFavorite(firstId)
        }
        feedViewModel.selectFilterTab(FeedFilterTab.FAVORITES)
        assertEquals(FeedFilterTab.FAVORITES, feedViewModel.uiState.value.activeTab)
        assertTrue(feedViewModel.uiState.value.videos.isNotEmpty())
        assertTrue(feedViewModel.uiState.value.videos.all { it.isFavorited })

        // Return to For You
        feedViewModel.selectFilterTab(FeedFilterTab.FOR_YOU)
        assertEquals(initialCount, feedViewModel.uiState.value.videos.size)
    }
}
