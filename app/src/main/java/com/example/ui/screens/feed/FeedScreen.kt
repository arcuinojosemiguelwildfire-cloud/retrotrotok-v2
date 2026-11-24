package com.example.ui.screens.feed

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import java.io.File
import com.example.cloud.QrCodeGenerator
import com.example.model.CloudUploadStatus
import com.example.model.EventModeStatus
import com.example.model.VideoItem
import com.example.ui.components.RetroActionColumn
import com.example.ui.components.RetroMarqueeTicker
import com.example.ui.components.RetroPillButton
import com.example.ui.components.RetroScanlinesOverlay
import com.example.ui.components.TikTokBottomNavigation
import com.example.ui.components.VideoQrDialog
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.PhosphorGreen
import com.example.ui.theme.RetroGold
import com.example.ui.theme.RetroOnyx
import com.example.ui.theme.RetroSurfaceElevated
import com.example.ui.theme.ScrimVignette
import com.example.ui.theme.SynthPurple
import com.example.ui.theme.TikTokBlack
import com.example.ui.theme.TikTokDark
import com.example.ui.theme.TikTokGray
import com.example.ui.theme.TikTokRed
import com.example.ui.theme.TikTokWhite
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TopBarScrim
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeedScreen(
    uiState: FeedUiState,
    onPageChanged: (Int) -> Unit,
    onLikeClick: (String) -> Unit,
    onFavoriteClick: (String) -> Unit,
    onDoubleTapLike: (Offset, String) -> Unit,
    onRemoveHeart: (Long) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onDanceStudioClick: () -> Unit,
    onAdminTrigger: () -> Unit,
    onFilterTabSelected: (FeedFilterTab) -> Unit = {},
    onDiscoverMusicClick: () -> Unit = {},
    onQrClick: (String) -> Unit = {},
    onDismissQrDialog: () -> Unit = {},
    onUseSongClick: (VideoItem) -> Unit = {},
    onDismissSongActionMessage: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val activeQrVideo = uiState.activeQrVideo

    BackHandler(enabled = activeQrVideo != null) {
        // Keep guest on QR dialog until they explicitly tap Done
    }
    BackHandler(enabled = activeQrVideo == null && uiState.activeTab != FeedFilterTab.FOR_YOU) {
        onFilterTabSelected(FeedFilterTab.FOR_YOU)
    }

    if (uiState.isLoading) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(TikTokBlack),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = TikTokRed)
        }
        return
    }

    if (uiState.videos.isEmpty()) {
        val isFavoritesEmpty = uiState.activeTab == FeedFilterTab.FAVORITES
        val isFeaturedEmpty = uiState.activeTab == FeedFilterTab.FEATURED
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(TikTokBlack)
                .testTag("feed_empty_state")
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp)
            ) {
                Icon(
                    imageVector = if (isFavoritesEmpty) Icons.Default.Bookmark else Icons.Default.Videocam,
                    contentDescription = null,
                    tint = TikTokRed,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = when {
                        isFavoritesEmpty -> "No favorites yet"
                        isFeaturedEmpty -> "No featured videos yet"
                        else -> "No event videos yet"
                    },
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = TikTokWhite,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when {
                        isFavoritesEmpty -> "Tap the bookmark icon on any video in For You to save it here."
                        isFeaturedEmpty -> "Event organizers can feature standout dance takes from the Admin Console."
                        else -> "Be the first to step into the Dance Studio and record a clip."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = TikTokGray,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                if (isFavoritesEmpty || isFeaturedEmpty) {
                    RetroPillButton(
                        text = "Back to For You",
                        onClick = { onFilterTabSelected(FeedFilterTab.FOR_YOU) },
                        testTag = "empty_back_to_for_you"
                    )
                } else {
                    RetroPillButton(
                        text = if (uiState.isAcceptingGuestRecordings) "Start First Recording" else "Recording Paused",
                        onClick = onDanceStudioClick,
                        enabled = uiState.isAcceptingGuestRecordings,
                        testTag = "empty_start_recording"
                    )
                }
            }

            TikTokBottomNavigation(
                selectedTab = if (isFavoritesEmpty) "favorites" else "home",
                onTabSelected = { tab ->
                    when (tab) {
                        "home" -> onFilterTabSelected(FeedFilterTab.FOR_YOU)
                        "discover" -> onDiscoverMusicClick()
                        "favorites" -> onFilterTabSelected(FeedFilterTab.FAVORITES)
                    }
                },
                onCreateClick = onDanceStudioClick,
                showDiscoverTab = uiState.enableDiscover,
                showFavoritesTab = uiState.enableGuestFavorites,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .testTag("feed_bottom_nav")
            )
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = uiState.currentIndex,
        pageCount = { uiState.videos.size }
    )

    LaunchedEffect(uiState.currentIndex, uiState.videos.size) {
        val target = uiState.currentIndex.coerceIn(0, (uiState.videos.size - 1).coerceAtLeast(0))
        if (uiState.videos.isNotEmpty() && pagerState.currentPage != target) {
            pagerState.scrollToPage(target)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            onPageChanged(page)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TikTokBlack)
            .testTag("feed_screen")
    ) {
        // Full-Screen Vertical Pager with beyondViewportPageCount = 0 to release inactive ExoPlayers on API 23
        VerticalPager(
            state = pagerState,
            beyondViewportPageCount = 0,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val video = uiState.videos[page]
            VideoFeedPage(
                video = video,
                isPlaying = uiState.isPlaying && page == pagerState.currentPage && activeQrVideo == null,
                onDoubleTap = { offset -> onDoubleTapLike(offset, video.id) },
                onSingleTap = onPlayPauseToggle
            )
        }

        // Active page video overlays
        val currentVideo = uiState.videos.getOrNull(pagerState.currentPage) ?: uiState.videos.first()

        // Top Header: Discreet Admin Trigger & Event Pill
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xCC000000), Color.Transparent)
                    )
                )
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Discreet Long-Press Admin Trigger on Logo
                Box(
                    modifier = Modifier
                        .combinedClickable(
                            onClick = {},
                            onLongClick = onAdminTrigger
                        )
                        .testTag("feed_logo_admin_trigger")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "RetroTok",
                            fontWeight = FontWeight.Black,
                            fontSize = 20.sp,
                            color = TikTokWhite,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(TikTokDark)
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "EVENT",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = TikTokGray
                            )
                        }
                    }
                }

                // TikTok-style Feed Tabs: "Featured" (optional) | "For You"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val isFeatured = uiState.activeTab == FeedFilterTab.FEATURED
                    val isForYou = uiState.activeTab == FeedFilterTab.FOR_YOU
                    if (uiState.showFeaturedSection) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable { onFilterTabSelected(FeedFilterTab.FEATURED) }
                                .padding(vertical = 4.dp)
                                .testTag("feed_tab_featured")
                        ) {
                            Text(
                                text = "Featured",
                                fontSize = if (isFeatured) 16.sp else 15.sp,
                                fontWeight = if (isFeatured) FontWeight.Bold else FontWeight.Normal,
                                color = if (isFeatured) TikTokWhite else TikTokGray
                            )
                            if (isFeatured) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Box(
                                    modifier = Modifier
                                        .width(24.dp)
                                        .height(2.dp)
                                        .background(TikTokWhite)
                                )
                            }
                        }
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { onFilterTabSelected(FeedFilterTab.FOR_YOU) }
                            .padding(vertical = 4.dp)
                            .testTag("feed_tab_for_you")
                    ) {
                        Text(
                            text = "For You",
                            fontSize = if (isForYou) 16.sp else 15.sp,
                            fontWeight = if (isForYou) FontWeight.Bold else FontWeight.Normal,
                            color = if (isForYou) TikTokWhite else TikTokGray
                        )
                        if (isForYou) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .width(24.dp)
                                    .height(2.dp)
                                    .background(TikTokWhite)
                            )
                        }
                    }
                }

                // Event Mode / Event Name Pill
                val statusColor = when (uiState.eventModeStatus) {
                    EventModeStatus.ACTIVE -> PhosphorGreen
                    EventModeStatus.PAUSED -> RetroGold
                    EventModeStatus.ENDED -> TikTokRed
                    EventModeStatus.NOT_STARTED -> TikTokGray
                }
                val statusText = when (uiState.eventModeStatus) {
                    EventModeStatus.ACTIVE -> uiState.eventName.take(14)
                    EventModeStatus.PAUSED -> "PAUSED"
                    EventModeStatus.ENDED -> "ENDED"
                    EventModeStatus.NOT_STARTED -> "STANDBY"
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x66161616))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TikTokWhite
                    )
                }
            }
        }

        // Right-Side Vertical Action Column (above bottom nav)
        val currentUploadStatus = currentVideo.cloudUploadSummary?.status ?: CloudUploadStatus.LOCAL_ONLY
        val currentQrReady = currentUploadStatus == CloudUploadStatus.UPLOADED &&
            QrCodeGenerator.isValidViewingUrl(currentVideo.cloudUploadSummary?.viewingUrl)
        val currentQrUploading = currentUploadStatus == CloudUploadStatus.QUEUED ||
            currentUploadStatus == CloudUploadStatus.REQUESTING_UPLOAD ||
            currentUploadStatus == CloudUploadStatus.UPLOADING ||
            currentUploadStatus == CloudUploadStatus.VERIFYING
        RetroActionColumn(
            likeCount = currentVideo.likeCount,
            favoriteCount = currentVideo.favoriteCount,
            isLiked = currentVideo.isLiked,
            isFavorited = currentVideo.isFavorited,
            onLikeClick = { onLikeClick(currentVideo.id) },
            onFavoriteClick = { onFavoriteClick(currentVideo.id) },
            onVinylClick = onDanceStudioClick,
            showLikeButton = uiState.enableGuestLikes,
            showFavoriteButton = uiState.enableGuestFavorites,
            onQrClick = { onQrClick(currentVideo.id) },
            showQrButton = true,
            isQrReady = currentQrReady,
            isQrUploading = currentQrUploading,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 68.dp)
        )

        // Bottom Metadata Overlay (creator, caption, sound) above bottom nav
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xCC000000))
                    )
                )
                .padding(start = 16.dp, end = 88.dp, bottom = 68.dp, top = 24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Featured Badge if applicable
                if (currentVideo.isFeatured) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(RetroGold.copy(alpha = 0.2f))
                            .border(1.dp, RetroGold, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = RetroGold,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "FEATURED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = RetroGold
                        )
                    }
                }

                // Nickname, Effect Tag & Honest Upload Status Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "@${currentVideo.creatorNickname}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TikTokWhite
                        )
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF292929))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = currentVideo.effect.title,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = TikTokGray
                        )
                    }
                    val uploadStatus = currentVideo.cloudUploadSummary?.status ?: CloudUploadStatus.LOCAL_ONLY
                    val (uploadColor, uploadLabel) = when (uploadStatus) {
                        CloudUploadStatus.UPLOADED -> PhosphorGreen to "Uploaded"
                        CloudUploadStatus.REQUESTING_UPLOAD,
                        CloudUploadStatus.UPLOADING,
                        CloudUploadStatus.VERIFYING -> ElectricCyan to "Uploading..."
                        CloudUploadStatus.QUEUED -> RetroGold to "Waiting for internet"
                        CloudUploadStatus.RETRY_PENDING -> RetroGold to "Retry scheduled"
                        CloudUploadStatus.FAILED -> TikTokRed to "Sync failed"
                        CloudUploadStatus.LOCAL_ONLY -> TikTokGray to "Saved locally"
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(uploadColor.copy(alpha = 0.16f))
                            .border(0.75.dp, uploadColor.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                            .clickable { onQrClick(currentVideo.id) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("feed_upload_status_badge")
                    ) {
                        Text(
                            text = uploadLabel,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = uploadColor
                        )
                    }
                }

                // Caption & Hashtags
                Text(
                    text = currentVideo.caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TikTokWhite,
                    maxLines = 2
                )

                if (currentVideo.hashtags.isNotBlank() &&
                    !currentVideo.caption.contains(currentVideo.hashtags.trim(), ignoreCase = true)
                ) {
                    Text(
                        text = currentVideo.hashtags,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = ElectricCyan,
                        maxLines = 1
                    )
                }

                // Sound Marquee Ticker (tappable only when video has a catalog soundtrackId)
                val canUseFeedSong = currentVideo.song.id.isNotBlank()
                RetroMarqueeTicker(
                    song = currentVideo.song,
                    onClick = if (canUseFeedSong) {
                        { onUseSongClick(currentVideo) }
                    } else {
                        null
                    },
                    enabled = !uiState.isResolvingFeedSong,
                    isLoading = uiState.isResolvingFeedSong,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(
                            if (canUseFeedSong) "feed_use_song_action" else "feed_song_label_readonly"
                        )
                )
            }
        }

        // Minimal TikTok Bottom Navigation Bar
        TikTokBottomNavigation(
            selectedTab = if (uiState.activeTab == FeedFilterTab.FAVORITES) "favorites" else "home",
            onTabSelected = { tab ->
                when (tab) {
                    "home" -> onFilterTabSelected(FeedFilterTab.FOR_YOU)
                    "discover" -> onDiscoverMusicClick()
                    "favorites" -> onFilterTabSelected(FeedFilterTab.FAVORITES)
                }
            },
            onCreateClick = onDanceStudioClick,
            showDiscoverTab = uiState.enableDiscover,
            showFavoritesTab = uiState.enableGuestFavorites,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .testTag("feed_bottom_nav")
        )

        // Double-Tap Floating Hearts Animation Layer
        uiState.floatingHearts.forEach { heart ->
            AnimatedHeartBurst(
                offset = heart.offset,
                onComplete = { onRemoveHeart(heart.id) }
            )
        }

        // User-friendly notice when a feed video's soundtrack is unavailable, unapproved, or missing
        val songNotice = uiState.songActionMessage
        if (songNotice != null) {
            LaunchedEffect(songNotice) {
                delay(4500L)
                onDismissSongActionMessage()
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 74.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xEB221015))
                    .border(1.dp, TikTokRed.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("feed_song_error_banner")
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = RetroGold,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = songNotice,
                    color = TikTokWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismissSongActionMessage,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("feed_song_error_dismiss")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss soundtrack notice",
                        tint = TikTokWhite,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Guest Share QR Code Modal
        if (activeQrVideo != null) {
            VideoQrDialog(
                video = activeQrVideo,
                onDismiss = onDismissQrDialog
            )
        }
    }
}

/**
 * Individual Page playing recorded local MP4 video via Media3 ExoPlayer,
 * displaying local portrait thumbnail while buffering, and showing a graceful
 * offline fallback if a local MP4 file is missing or corrupted.
 */
@Composable
fun VideoFeedPage(
    video: VideoItem,
    isPlaying: Boolean,
    onDoubleTap: (Offset) -> Unit,
    onSingleTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val localVideoFile = remember(video.localVideoPath) {
        video.localVideoPath?.takeIf { it.isNotBlank() }?.let { path ->
            val candidate = if (path.startsWith("/")) File(path) else File(context.filesDir, path)
            if (candidate.exists() && candidate.length() > 32L) candidate else null
        }
    }
    val localThumbFile = remember(video.thumbnailPath) {
        video.thumbnailPath?.takeIf { it.isNotBlank() }?.let { path ->
            val candidate = if (path.startsWith("/")) File(path) else File(context.filesDir, path)
            if (candidate.exists() && candidate.length() > 0L) candidate else null
        }
    }

    var isFirstFrameReady by remember(video.id, localVideoFile?.absolutePath) { mutableStateOf(false) }
    var playbackError by remember(video.id, localVideoFile?.absolutePath) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TikTokBlack)
            .pointerInput(video.id) {
                detectTapGestures(
                    onDoubleTap = { offset -> onDoubleTap(offset) },
                    onTap = { onSingleTap() }
                )
            }
    ) {
        if (localVideoFile != null && !playbackError) {
            val exoPlayer = remember(localVideoFile.absolutePath) {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(localVideoFile)))
                    repeatMode = Player.REPEAT_MODE_ALL
                    prepare()
                }
            }

            DisposableEffect(exoPlayer) {
                val listener = object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        isFirstFrameReady = true
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        playbackError = true
                    }
                }
                exoPlayer.addListener(listener)
                onDispose {
                    exoPlayer.removeListener(listener)
                    exoPlayer.release()
                }
            }

            LaunchedEffect(isPlaying, exoPlayer) {
                if (isPlaying) {
                    exoPlayer.play()
                } else {
                    exoPlayer.pause()
                }
            }

            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        player = exoPlayer
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Display pre-generated portrait thumbnail smoothly while ExoPlayer renders frame 1
            if (!isFirstFrameReady && localThumbFile != null) {
                AsyncImage(
                    model = localThumbFile,
                    contentDescription = "@${video.creatorNickname} thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            if (localThumbFile != null) {
                AsyncImage(
                    model = localThumbFile,
                    contentDescription = "@${video.creatorNickname} thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.35f)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(TikTokDark)
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = TikTokGray,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (playbackError) "Unable to play local video" else "Video file unavailable",
                    color = TikTokWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Check Admin Storage Console to reconcile or remove missing media.",
                    color = TikTokGray,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (!isPlaying && localVideoFile != null && !playbackError) {
            // Paused Indicator Icon
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color(0x88000000))
                    .align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Paused",
                    tint = TikTokWhite,
                    modifier = Modifier.size(44.dp)
                )
            }
        }
    }
}

/**
 * Animated heart scaling up and fading out at touch coordinates.
 */
@Composable
fun AnimatedHeartBurst(
    offset: Offset,
    onComplete: () -> Unit
) {
    val scale = remember { Animatable(0.4f) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        scale.animateTo(1.6f, animationSpec = tween(500, easing = FastOutSlowInEasing))
        alpha.animateTo(0f, animationSpec = tween(200, easing = LinearEasing))
        onComplete()
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(offset.x.toInt() - 48, offset.y.toInt() - 48) }
            .scale(scale.value)
            .alpha(alpha.value)
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = NeonMagenta,
            modifier = Modifier.size(96.dp)
        )
    }
}
