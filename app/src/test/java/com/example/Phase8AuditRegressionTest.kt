package com.example

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioPreviewManager
import com.example.camera.CameraDetector
import com.example.camera.CameraProviderImpl
import com.example.data.local.storage.MusicStorageManager
import com.example.data.mock.MockMusicCatalogRepository
import com.example.data.mock.MockVideoFeedRepository
import com.example.data.repository.LocalKioskSettingsRepository
import com.example.model.RetroDecade
import com.example.model.RetroEffectType
import com.example.model.SongItem
import com.example.ui.screens.admin.AdminPinDialog
import com.example.ui.screens.admin.AdminViewModel
import com.example.ui.screens.hardware.HardwareValidationViewModel
import com.example.ui.theme.RetroTokTheme
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase8AuditRegressionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var videoStorageManager: VideoStorageManager
    private lateinit var musicStorageManager: MusicStorageManager
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        videoStorageManager = VideoStorageManager(context)
        musicStorageManager = MusicStorageManager(context)
        videoStorageManager.clearTempStorage()
        context.getSharedPreferences("retrotok_kiosk_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        videoStorageManager.clearTempStorage()
        Dispatchers.resetMain()
    }

    @Test
    fun testManifestSecurityAndOfflineLeastPrivilegeConstraints() {
        val pm = context.packageManager
        val pkgInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val appInfo = pkgInfo.applicationInfo!!

        assertEquals(23, appInfo.minSdkVersion)
        assertEquals(
            "allowBackup must be disabled for offline kiosk privacy",
            0,
            appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP
        )

        val permissions = pkgInfo.requestedPermissions?.toSet() ?: emptySet()
        assertTrue("CAMERA permission is required", Manifest.permission.CAMERA in permissions)
        assertFalse("Unused RECORD_AUDIO permission must be removed", Manifest.permission.RECORD_AUDIO in permissions)
        assertFalse("Unused MODIFY_AUDIO_SETTINGS permission must be removed", "android.permission.MODIFY_AUDIO_SETTINGS" in permissions)
        assertTrue("INTERNET permission is required for Phase 11 cloud sync", Manifest.permission.INTERNET in permissions)
        assertTrue("ACCESS_NETWORK_STATE permission is required for connectivity monitoring", Manifest.permission.ACCESS_NETWORK_STATE in permissions)
    }

    @Test
    fun testVideoMuxerTimestampNormalizationRebasesAndEnforcesMonotonicity() {
        // Negative priming PTS -> clamped and rebased to 0
        val (base1, pts1) = VideoMuxerProcessor.normalizeSampleTimestampUs(-50_000L, null, null)
        assertEquals(0L, base1)
        assertEquals(0L, pts1)

        // Non-zero initial camera PTS (e.g. 180ms) -> rebased to 0 so video matches 0ms audio
        val (base2, pts2) = VideoMuxerProcessor.normalizeSampleTimestampUs(180_000L, null, null)
        assertEquals(180_000L, base2)
        assertEquals(0L, pts2)

        // Duplicate PTS -> bumped monotonically by 1us
        val (_, pts3) = VideoMuxerProcessor.normalizeSampleTimestampUs(180_000L, base2, pts2)
        assertEquals(1L, pts3)

        // Out-of-order jitter PTS -> still monotonically increasing
        val (_, pts4) = VideoMuxerProcessor.normalizeSampleTimestampUs(179_500L, base2, pts3)
        assertEquals(2L, pts4)

        // Normal advancing PTS -> preserved relative to base
        val (_, pts5) = VideoMuxerProcessor.normalizeSampleTimestampUs(213_333L, base2, pts4)
        assertEquals(33_333L, pts5)
    }

    @Test
    fun testAdminPinDialogDoesNotExposeDefaultPinInUi() {
        composeRule.setContent {
            RetroTokTheme {
                AdminPinDialog(
                    enteredPin = "",
                    pinError = null,
                    isLockedOut = false,
                    lockoutSeconds = 0,
                    onDigitClick = {},
                    onBackspace = {},
                    onClear = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()

        val leakedPinNodes = composeRule
            .onAllNodesWithText("1234", substring = true)
            .fetchSemanticsNodes()
        assertEquals("Guest-accessible PIN dialog must never display default PIN", 0, leakedPinNodes.size)
    }

    @Test
    fun testLocalKioskSettingsHashesAdminPinNeverPlaintext() = runTest {
        val repo = LocalKioskSettingsRepository(context)
        assertTrue(repo.validateAdminPin("1234"))
        assertFalse(repo.validateAdminPin("0000"))

        val prefs = context.getSharedPreferences("retrotok_kiosk_settings", Context.MODE_PRIVATE)
        val storedHash = prefs.getString("admin_pin_sha256", null)
        assertNotNull(storedHash)
        assertEquals(64, storedHash!!.length)
        assertFalse(storedHash.contains("1234"))

        val adminVm = AdminViewModel(
            settingsRepository = repo,
            videoFeedRepository = MockVideoFeedRepository(),
            musicCatalogRepository = MockMusicCatalogRepository(),
            audioPreviewManager = AudioPreviewManager(context),
            storageManager = musicStorageManager,
            videoStorageManager = videoStorageManager
        )
        repeat(5) {
            adminVm.onClearPin()
            "9876".forEach { d -> adminVm.onDigitEntered(d.toString()) }
        }
        assertTrue(adminVm.uiState.value.isLockedOut)
    }

    @Test
    fun testRecoverableTakePreservationAndMuxFallback() = runTest {
        val rawFile = videoStorageManager.createTempRawVideoFile("rec_phase8_take").apply {
            writeBytes(ByteArray(2048) { 0x42 })
        }
        val transientCompFile = File(videoStorageManager.tempDir, "comp_vid_phase8.mp4").apply {
            writeBytes(ByteArray(2048) { 0x11 })
        }

        videoStorageManager.saveTempRecordingMetadata(
            TempRecordingMetadata(
                recordingId = "rec_phase8_take",
                videoFileName = rawFile.name,
                songId = "deleted-song-id",
                songTitle = "Missing Track",
                songArtist = "Retro Artist",
                effectName = RetroEffectType.NEON_80S.name,
                durationSeconds = 15,
                createdAtMs = System.currentTimeMillis(),
                stage = "RAW"
            )
        )

        val purged = videoStorageManager.clearTransientStagingOnly()
        assertEquals(1, purged)
        assertFalse(transientCompFile.exists())
        assertTrue(rawFile.exists())

        val sessions = videoStorageManager.findRecoverableTempSessions()
        assertEquals(1, sessions.size)
        assertEquals("Missing Track", sessions.first().displayTitle)
        assertEquals(RetroEffectType.NEON_80S, sessions.first().effectType)

        // Verify VideoMuxerProcessor preserves non-codec stub via safe fallback without throwing
        val outFile = videoStorageManager.createTempMuxedVideoFile("rec_phase8_take")
        val muxResult = VideoMuxerProcessor(context).processAndMux(
            rawVideoFile = rawFile,
            audioFile = null,
            outputFile = outFile,
            durationMs = 15_000L,
            effect = RetroEffectType.NEON_80S
        )
        assertTrue(muxResult is com.example.video.MuxerResult.Success)
        assertTrue(outFile.exists() && outFile.length() > 0L)
    }

    @Test
    fun testAudioPreviewAndSoundtrackControllersHandleMissingFilesSafely() {
        val missingSong = SongItem(
            id = "missing-song",
            title = "Missing Track",
            artist = "None",
            album = "None",
            releaseYear = 1984,
            decade = RetroDecade.DECADE_80S,
            genre = "Synth",
            durationSeconds = 30,
            audioRelativePath = "retrotok/music/audio/does_not_exist.mp3"
        )

        val previewManager = AudioPreviewManager(context)
        previewManager.togglePlaySong(missingSong, File(context.filesDir, missingSong.audioRelativePath))
        assertFalse(previewManager.playbackState.value.isPlaying)
        assertNotNull(previewManager.playbackState.value.errorMessage)
        previewManager.release()

        val soundtrackController = SoundtrackSessionController(context, musicStorageManager)
        val prepared = soundtrackController.prepareTrack(missingSong)
        assertFalse(prepared)
        assertFalse(soundtrackController.isPrepared.value)
        soundtrackController.release()
    }

    @Test
    fun testHardwareDiagnosticsUsesApi23BspLabel() = runTest {
        val vm = HardwareValidationViewModel(
            cameraDetector = CameraDetector(context),
            cameraProvider = CameraProviderImpl(context)
        )
        // Advance past the 400ms diagnostic sweep delay
        var waited = 0
        while (vm.uiState.value.checks.isEmpty() && waited < 2000) {
            Thread.sleep(50)
            waited += 50
        }
        val details = vm.uiState.value.checks.joinToString("\n") { it.diagnosticDetails }
        assertFalse("Must not contain stale Android 16 BSP label", details.contains("Android 16"))
    }
}
