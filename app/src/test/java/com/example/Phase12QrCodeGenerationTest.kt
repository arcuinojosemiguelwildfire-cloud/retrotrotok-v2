package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.cloud.QrCodeGenerationResult
import com.example.cloud.QrCodeGenerator
import com.example.data.mock.MockDataProvider
import com.example.data.repository.VideoFeedRepository
import com.example.data.repository.VideoSearchSort
import com.example.model.CloudUploadStatus
import com.example.model.CloudUploadSummary
import com.example.model.VideoItem
import com.example.ui.components.VideoQrDialog
import com.example.ui.screens.feed.FeedScreen
import com.example.ui.screens.feed.FeedViewModel
import com.example.ui.screens.preview.PreviewViewModel
import com.example.ui.theme.RetroTokTheme
import com.example.video.VideoStorageManager
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
class Phase12QrCodeGenerationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private val testDispatcher = UnconfinedTestDispatcher()

    private class FakeReactiveVideoFeedRepository(
        initialVideos: List<VideoItem>
    ) : VideoFeedRepository {
        private val feedFlow = MutableStateFlow(initialVideos)

        fun emitVideos(videos: List<VideoItem>) {
            feedFlow.value = videos
        }

        override fun getFeedVideos(): Flow<List<VideoItem>> = feedFlow.asStateFlow()

        override fun getAllAdminVideos(): Flow<List<VideoItem>> = feedFlow.asStateFlow()

        override fun searchVideos(query: String, sort: VideoSearchSort): Flow<List<VideoItem>> {
            return feedFlow.map { list ->
                val q = query.trim()
                if (q.isEmpty()) list else list.filter {
                    it.creatorNickname.contains(q, ignoreCase = true) ||
                        it.caption.contains(q, ignoreCase = true)
                }
            }
        }

        override suspend fun toggleLike(videoId: String): VideoItem? = null
        override suspend fun ensureLiked(videoId: String): VideoItem? = null
        override suspend fun toggleFavorite(videoId: String): VideoItem? = null

        override suspend fun publishVideo(newVideo: VideoItem) {
            feedFlow.value = listOf(newVideo) + feedFlow.value
        }

        override suspend fun deleteVideo(videoId: String) {
            feedFlow.value = feedFlow.value.filterNot { it.id == videoId }
        }

        override suspend fun toggleFeatured(videoId: String) = Unit
    }

    private fun sampleVideo(
        id: String = "vid-1",
        nickname: String = "NeonDancer",
        createdAt: Long = 10_000L,
        summary: CloudUploadSummary? = null
    ): VideoItem {
        return VideoItem(
            id = id,
            creatorNickname = nickname,
            caption = "Grooving #RetroTok",
            song = MockDataProvider.mockSongs.first(),
            effect = com.example.model.RetroEffectType.VHS_RETRO,
            durationSeconds = 30,
            likeCount = 5,
            favoriteCount = 1,
            isFeatured = false,
            localVideoPath = null,
            createdAt = createdAt,
            cloudUploadSummary = summary
        )
    }

    private fun decodeQrBitmap(bitmap: Bitmap): String {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        return QRCodeReader().decode(binaryBitmap).text
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    @Config(sdk = [23])
    fun testQrGeneratorValidViewingUrlEncodesExactUrlWithWhiteQuietZoneOnApi23() {
        val viewingUrl = "https://wildfirexstream.com/retrotok/watch/tok_AbC123_XYZ-9876543210abcdef"
        assertTrue(QrCodeGenerator.isValidViewingUrl(viewingUrl))

        val result = QrCodeGenerator.generate(viewingUrl, sizePx = 512)
        assertTrue("Expected QR generation success on API 23", result is QrCodeGenerationResult.Success)
        val success = result as QrCodeGenerationResult.Success
        assertEquals(512, success.bitmap.width)
        assertEquals(512, success.bitmap.height)
        assertEquals(viewingUrl, success.encodedUrl)

        // Corners must belong to the white quiet zone
        assertEquals(Color.WHITE, success.bitmap.getPixel(0, 0))
        assertEquals(Color.WHITE, success.bitmap.getPixel(511, 0))
        assertEquals(Color.WHITE, success.bitmap.getPixel(0, 511))
        assertEquals(Color.WHITE, success.bitmap.getPixel(511, 511))

        // Must contain dark modules for high contrast and decode to the exact URL
        var hasBlackModule = false
        for (y in 0 until success.bitmap.height step 4) {
            for (x in 0 until success.bitmap.width step 4) {
                if (success.bitmap.getPixel(x, y) == Color.BLACK) {
                    hasBlackModule = true
                    break
                }
            }
            if (hasBlackModule) break
        }
        assertTrue("Generated QR bitmap must contain high-contrast black modules", hasBlackModule)
        assertEquals(viewingUrl, decodeQrBitmap(success.bitmap))
    }

    @Test
    fun testQrGeneratorRejectsBlankAndMalformedUrlsGracefully() {
        val invalidInputs = listOf(
            null,
            "",
            "   ",
            "not-a-url",
            "/retrotok/watch/only-path",
            "ftp://wildfirexstream.com/retrotok/watch/abc",
            "https://",
            "javascript:alert(1)",
            "https://wildfirexstream.com/retrotok/watch/has space"
        )

        invalidInputs.forEach { raw ->
            assertFalse("Expected invalid URL for '$raw'", QrCodeGenerator.isValidViewingUrl(raw))
            val result = QrCodeGenerator.generate(raw)
            assertTrue("Expected InvalidUrl result for '$raw'", result is QrCodeGenerationResult.InvalidUrl)
            assertNull("Expected null bitmap for '$raw'", QrCodeGenerator.generateQrBitmap(raw))
        }
    }

    @Test
    fun testFeedViewModelAutomaticallyPresentsQrOnUploadingToUploadedTransitionAndDoneDismisses() {
        val existingVideo = sampleVideo(id = "vid-old", nickname = "EarlierGuest", createdAt = 1_000L)
        val newVideoId = "vid-new-published"
        val viewingUrl = "https://wildfirexstream.com/retrotok/watch/liveToken_256bit_abc123"
        val uploadingVideo = sampleVideo(
            id = newVideoId,
            nickname = "DiscoStar",
            createdAt = 20_000L,
            summary = CloudUploadSummary(
                videoId = newVideoId,
                status = CloudUploadStatus.UPLOADING,
                cloudVideoId = 42L,
                eventId = 7L
            )
        )

        val repo = FakeReactiveVideoFeedRepository(listOf(existingVideo))
        val feedViewModel = FeedViewModel(repository = repo)

        // Publish finishes locally first and returns to Feed while upload is still running
        feedViewModel.onVideoPublished(videoId = newVideoId, expectCloudQr = true)
        repo.emitVideos(listOf(uploadingVideo, existingVideo))

        val duringUpload = feedViewModel.uiState.value
        assertEquals(newVideoId, duringUpload.lastPublishedVideoId)
        assertEquals(0, duringUpload.currentIndex)
        assertEquals(newVideoId, duringUpload.pendingAutoQrVideoId)
        assertNull("QR dialog should not auto-open before viewingUrl arrives", duringUpload.qrDialogVideoId)

        // Background upload finishes and Room emits UPLOADED + viewingUrl
        val uploadedVideo = uploadingVideo.copy(
            cloudUploadSummary = CloudUploadSummary(
                videoId = newVideoId,
                status = CloudUploadStatus.UPLOADED,
                cloudVideoId = 42L,
                eventId = 7L,
                qrToken = "liveToken_256bit_abc123",
                viewingUrl = viewingUrl
            )
        )
        repo.emitVideos(listOf(uploadedVideo, existingVideo))

        val afterUpload = feedViewModel.uiState.value
        assertEquals(newVideoId, afterUpload.qrDialogVideoId)
        assertNull(afterUpload.pendingAutoQrVideoId)
        assertEquals(viewingUrl, afterUpload.activeQrVideo?.cloudUploadSummary?.viewingUrl)

        // Render FeedScreen bound to FeedViewModel and verify QR displays and Done dismisses
        composeRule.setContent {
            RetroTokTheme {
                FeedScreen(
                    uiState = feedViewModel.uiState.value,
                    onPageChanged = { feedViewModel.onPageChanged(it) },
                    onLikeClick = {},
                    onFavoriteClick = {},
                    onDoubleTapLike = { _, _ -> },
                    onRemoveHeart = {},
                    onPlayPauseToggle = {},
                    onDanceStudioClick = {},
                    onAdminTrigger = {},
                    onQrClick = { feedViewModel.openQrDialog(it) },
                    onDismissQrDialog = { feedViewModel.dismissQrDialog() }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("video_qr_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("qr_dialog_bitmap").assertIsDisplayed()
        composeRule.onNodeWithTag("qr_dialog_done_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertNull("Tapping Done must dismiss QR dialog", feedViewModel.uiState.value.qrDialogVideoId)
    }

    @Test
    fun testVideoQrDialogReactiveComposeTransitionAndDoneButton() {
        val viewingUrl = "https://wildfirexstream.com/retrotok/watch/reactiveWatchToken_0123456789ab"
        val currentVideo = androidx.compose.runtime.mutableStateOf(
            sampleVideo(
                id = "vid-reactive",
                summary = CloudUploadSummary(
                    videoId = "vid-reactive",
                    status = CloudUploadStatus.QUEUED
                )
            )
        )
        var dismissed = false

        composeRule.setContent {
            RetroTokTheme {
                VideoQrDialog(
                    video = currentVideo.value,
                    onDismiss = { dismissed = true }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("qr_dialog_progress").assertIsDisplayed()
        composeRule.onNodeWithText("Waiting for Internet").assertIsDisplayed()

        composeRule.runOnIdle {
            currentVideo.value = currentVideo.value.copy(
                cloudUploadSummary = CloudUploadSummary(
                    videoId = "vid-reactive",
                    status = CloudUploadStatus.UPLOADING
                )
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("qr_dialog_progress").assertIsDisplayed()
        composeRule.onNodeWithText("Preparing Share Link...").assertIsDisplayed()

        composeRule.runOnIdle {
            currentVideo.value = currentVideo.value.copy(
                cloudUploadSummary = CloudUploadSummary(
                    videoId = "vid-reactive",
                    status = CloudUploadStatus.UPLOADED,
                    viewingUrl = viewingUrl
                )
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("qr_dialog_bitmap").assertIsDisplayed()
        composeRule.onNodeWithTag("qr_dialog_done_button").performClick()
        composeRule.waitForIdle()
        assertTrue("Done button must invoke onDismiss", dismissed)
    }

    @Test
    fun testVideoQrDialogHandlesUploadedWithoutViewingUrl() {
        val video = sampleVideo(
            id = "vid-missing-url",
            summary = CloudUploadSummary(
                videoId = "vid-missing-url",
                status = CloudUploadStatus.UPLOADED,
                viewingUrl = null
            )
        )

        composeRule.setContent {
            RetroTokTheme {
                VideoQrDialog(video = video, onDismiss = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("qr_dialog_unavailable_message").assertIsDisplayed()
        composeRule.onNodeWithText("Watch Link Unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("qr_dialog_done_button").assertIsDisplayed()
    }

    @Test
    fun testVideoQrDialogHandlesFailedRetryPendingAndLocalOnlyStates() {
        val currentVideo = androidx.compose.runtime.mutableStateOf(
            sampleVideo(
                id = "vid-states",
                summary = CloudUploadSummary(
                    videoId = "vid-states",
                    status = CloudUploadStatus.FAILED,
                    lastErrorMessage = "Network timeout"
                )
            )
        )

        composeRule.setContent {
            RetroTokTheme {
                VideoQrDialog(video = currentVideo.value, onDismiss = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("qr_dialog_failed").assertIsDisplayed()
        composeRule.onNodeWithText("Cloud Upload Failed").assertIsDisplayed()

        composeRule.runOnIdle {
            currentVideo.value = currentVideo.value.copy(
                cloudUploadSummary = CloudUploadSummary(
                    videoId = "vid-states",
                    status = CloudUploadStatus.RETRY_PENDING,
                    lastErrorMessage = "Retry in 15s"
                )
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("qr_dialog_retry_pending").assertIsDisplayed()
        composeRule.onNodeWithText("Upload Retry Scheduled").assertIsDisplayed()

        composeRule.runOnIdle {
            currentVideo.value = currentVideo.value.copy(
                cloudUploadSummary = CloudUploadSummary(
                    videoId = "vid-states",
                    status = CloudUploadStatus.LOCAL_ONLY
                )
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("qr_dialog_local_only").assertIsDisplayed()
        composeRule.onNodeWithText("Saved Locally Only").assertIsDisplayed()
    }

    @Test
    fun testFeedScreenQrButtonOpensDialogForCurrentVideo() {
        val video = sampleVideo(
            id = "vid-feed-btn",
            summary = CloudUploadSummary(
                videoId = "vid-feed-btn",
                status = CloudUploadStatus.UPLOADED,
                viewingUrl = "https://wildfirexstream.com/retrotok/watch/feedBtnToken_abcdefgh1234"
            )
        )
        val repo = FakeReactiveVideoFeedRepository(listOf(video))
        val feedViewModel = FeedViewModel(repository = repo)
        var clickedVideoId: String? = null

        composeRule.setContent {
            RetroTokTheme {
                FeedScreen(
                    uiState = feedViewModel.uiState.value,
                    onPageChanged = {},
                    onLikeClick = {},
                    onFavoriteClick = {},
                    onDoubleTapLike = { _, _ -> },
                    onRemoveHeart = {},
                    onPlayPauseToggle = {},
                    onDanceStudioClick = {},
                    onAdminTrigger = {},
                    onQrClick = { clickedVideoId = it }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("action_qr_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals("vid-feed-btn", clickedVideoId)
    }

    @Test
    fun testPreviewConfirmPublishNotifiesPublishedVideoIdWithoutBlocking() {
        val repo = FakeReactiveVideoFeedRepository(emptyList())
        val storage = VideoStorageManager(context)
        val previewViewModel = PreviewViewModel(
            videoFeedRepository = repo,
            videoStorageManager = storage
        )
        previewViewModel.initialize(
            videoFilePath = "/nonexistent/mock_preview.mp4",
            song = MockDataProvider.mockSongs.first(),
            effect = com.example.model.RetroEffectType.NONE,
            durationSeconds = 15
        )

        var publishedId: String? = null
        var onSuccessCalled = false
        previewViewModel.confirmPublish(
            onPublishedVideo = { id, _ -> publishedId = id },
            onSuccess = { onSuccessCalled = true }
        )

        assertTrue(onSuccessCalled)
        assertNotNull(publishedId)
    }
}
