package com.example.ui.screens.admin

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.data.repository.ModerationFilter
import com.example.data.repository.ReconciliationReport
import com.example.model.FeedSortOrder
import com.example.model.RetroDecade
import com.example.model.SongItem
import com.example.model.VideoItem
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.PhosphorGreen
import com.example.ui.theme.RetroGold
import com.example.ui.theme.RetroOnyx
import com.example.ui.theme.RetroSurface
import com.example.ui.theme.RetroSurfaceBorder
import com.example.ui.theme.RetroSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.video.VideoStorageBreakdown
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AdminDashboardScreen(
    uiState: AdminUiState,
    onTabSelected: (AdminTab) -> Unit,
    onDurationSelected: (Int) -> Unit,
    onToggleGuestDeletion: () -> Unit,
    onToggleKioskLock: () -> Unit,
    onDeleteVideo: (String) -> Unit,
    onToggleFeatured: (String) -> Unit,
    onClearStorage: () -> Unit,
    onOpenHardwareDiagnostics: () -> Unit,
    onImportAudioFiles: (List<Uri>) -> Unit,
    onDismissImportSummary: () -> Unit,
    onToggleSongLicense: (SongItem) -> Unit,
    onToggleSongPreview: (SongItem) -> Unit,
    onOpenEditSong: (SongItem) -> Unit,
    onDismissEditSong: () -> Unit,
    onSaveSongMetadata: (String, String, String, String, Int?, String, RetroDecade) -> Unit,
    onRequestDeleteSong: (SongItem) -> Unit,
    onDismissDeleteSong: () -> Unit,
    onConfirmDeleteSong: () -> Unit,
    onExitAdmin: () -> Unit,
    modifier: Modifier = Modifier,
    onVideoSearchQueryChange: (String) -> Unit = {},
    onOpenVideoPreview: (VideoItem) -> Unit = {},
    onDismissVideoPreview: () -> Unit = {},
    onRequestDeleteVideo: (VideoItem) -> Unit = { onDeleteVideo(it.id) },
    onDismissDeleteVideo: () -> Unit = {},
    onConfirmDeleteVideo: () -> Unit = {},
    onVideoUriSelectedForImport: (Uri) -> Unit = {},
    onDismissImportVideoDialog: () -> Unit = {},
    onConfirmImportVideo: (String, String, String) -> Unit = { _, _, _ -> },
    onDismissVideoImportMessage: () -> Unit = {},
    onRunReconciliation: () -> Unit = {},
    onPurgeOrphanedThumbnails: () -> Unit = {},
    onDismissStorageActionMessage: () -> Unit = {},
    isDeviceOwner: Boolean = false,
    isStudioBusy: Boolean = false,
    onUpdateEventDetails: (String, String) -> Unit = { _, _ -> },
    onStartEvent: (String, String) -> Unit = { _, _ -> },
    onPauseEvent: () -> Unit = {},
    onResumeEvent: () -> Unit = {},
    onRequestEndEvent: () -> Unit = {},
    onDismissEndEvent: () -> Unit = {},
    onConfirmEndEvent: () -> Unit = {},
    onRequestResetEventStats: () -> Unit = {},
    onDismissResetEventStats: () -> Unit = {},
    onConfirmResetEventStats: () -> Unit = {},
    onDismissEventActionMessage: () -> Unit = {},
    onModerationFilterSelected: (ModerationFilter) -> Unit = {},
    onOpenModerationDialog: (VideoItem) -> Unit = {},
    onDismissModerationDialog: () -> Unit = {},
    onConfirmModeration: (String, Boolean, String?) -> Unit = { _, _, _ -> },
    onUpdateFeedControls: (Boolean, Boolean, Boolean, Boolean, Boolean, FeedSortOrder, Boolean) -> Unit = { _, _, _, _, _, _, _ -> },
    onPairCloudDevice: (String, String, String) -> Unit = { _, _, _ -> },
    onTestCloudHeartbeat: () -> Unit = {},
    onUnpairCloudDevice: () -> Unit = {},
    onSyncPendingUploads: () -> Unit = {},
    onRetryVideoUpload: (String) -> Unit = {},
    onDismissCloudActionMessage: () -> Unit = {},
    onToggleWideView: () -> Unit = {}
) {
    BackHandler {
        onExitAdmin()
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onImportAudioFiles(uris)
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onVideoUriSelectedForImport(uri)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("admin_dashboard_screen"),
        color = RetroOnyx
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onExitAdmin,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(RetroSurfaceElevated)
                            .testTag("admin_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Exit Admin",
                            tint = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "ADMIN CONSOLE",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                            color = NeonMagenta
                        )
                        Text(
                            text = "Music Library, Kiosk & Content Moderation",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                // Lock Task Mode / Screen Pinning Fallback status pill
                val lockLabel = when {
                    !uiState.settings.isKioskLockEnabled -> "UNPINNED"
                    isDeviceOwner -> "LOCK TASK"
                    else -> "SCREEN PIN"
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (uiState.settings.isKioskLockEnabled) PhosphorGreen.copy(alpha = 0.2f) else RetroSurfaceElevated)
                        .border(1.dp, if (uiState.settings.isKioskLockEnabled) PhosphorGreen else RetroSurfaceBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .testTag("admin_lock_status_pill")
                ) {
                    Text(
                        text = lockLabel,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (uiState.settings.isKioskLockEnabled) PhosphorGreen else TextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Navigation Tabs
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val tabs = listOf(
                    AdminTab.DASHBOARD to "Overview",
                    AdminTab.MUSIC_MANAGER to "Music (${uiState.songs.size})",
                    AdminTab.VIDEO_MANAGER to "Videos (${uiState.videos.size})",
                    AdminTab.KIOSK_SETTINGS to "Settings",
                    AdminTab.STORAGE_HEALTH to "Storage"
                )
                items(tabs) { (tab, title) ->
                    val isSelected = uiState.activeTab == tab
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) NeonMagenta else RetroSurfaceElevated)
                            .clickable { onTabSelected(tab) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .testTag("admin_tab_${tab.name}")
                    ) {
                        Text(
                            text = title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (isSelected) TextPrimary else TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tab Content
            when (uiState.activeTab) {
                AdminTab.DASHBOARD -> DashboardOverview(
                    uiState = uiState,
                    onOpenDiagnostics = onOpenHardwareDiagnostics,
                    onOpenMusic = { onTabSelected(AdminTab.MUSIC_MANAGER) },
                    onOpenCloudSettings = { onTabSelected(AdminTab.KIOSK_SETTINGS) },
                    onSyncPendingUploads = onSyncPendingUploads,
                    onSaveEventDetails = onUpdateEventDetails,
                    onStartEvent = onStartEvent,
                    onPauseEvent = onPauseEvent,
                    onResumeEvent = onResumeEvent,
                    onRequestEndEvent = onRequestEndEvent,
                    onRequestResetStats = onRequestResetEventStats,
                    onDismissEventActionMessage = onDismissEventActionMessage
                )
                AdminTab.MUSIC_MANAGER -> MusicManagerView(
                    songs = uiState.songs,
                    metrics = uiState.musicMetrics,
                    isImporting = uiState.isImportingAudio,
                    importProgressText = uiState.importProgressText,
                    previewingSongId = uiState.previewingSongId,
                    isPreviewPlaying = uiState.isPreviewPlaying,
                    onImportClick = {
                        audioPickerLauncher.launch(
                            arrayOf("audio/mpeg", "audio/mp4", "audio/aac", "audio/x-m4a", "audio/*")
                        )
                    },
                    onTogglePreview = onToggleSongPreview,
                    onToggleLicense = onToggleSongLicense,
                    onEditClick = onOpenEditSong,
                    onDeleteClick = onRequestDeleteSong
                )
                AdminTab.VIDEO_MANAGER -> VideoManagerView(
                    videos = uiState.filteredVideos,
                    totalCount = uiState.videos.size,
                    searchQuery = uiState.videoSearchQuery,
                    selectedFilter = uiState.selectedModerationFilter,
                    isImportingVideo = uiState.isImportingVideo,
                    videoImportMessage = uiState.videoImportMessage,
                    isCloudPaired = uiState.cloudPairingState.isPairedAndAuthorized,
                    onSearchQueryChange = onVideoSearchQueryChange,
                    onFilterSelected = onModerationFilterSelected,
                    onImportVideoClick = {
                        videoPickerLauncher.launch(arrayOf("video/mp4", "video/*"))
                    },
                    onReconcileClick = onRunReconciliation,
                    onDismissImportMessage = onDismissVideoImportMessage,
                    onPreviewVideo = onOpenVideoPreview,
                    onModerateVideo = onOpenModerationDialog,
                    onDeleteVideo = onRequestDeleteVideo,
                    onToggleFeatured = onToggleFeatured,
                    onRetryVideoUpload = onRetryVideoUpload
                )
                AdminTab.KIOSK_SETTINGS -> KioskSettingsView(
                    settings = uiState.settings,
                    uiState = uiState,
                    isDeviceOwner = isDeviceOwner,
                    onDurationSelected = onDurationSelected,
                    onToggleGuestDeletion = onToggleGuestDeletion,
                    onToggleKioskLock = onToggleKioskLock,
                    onToggleWideView = onToggleWideView,
                    onUpdateFeedControls = onUpdateFeedControls,
                    onPairCloudDevice = onPairCloudDevice,
                    onTestCloudHeartbeat = onTestCloudHeartbeat,
                    onSyncPendingUploads = onSyncPendingUploads,
                    onUnpairCloudDevice = onUnpairCloudDevice,
                    onDismissCloudActionMessage = onDismissCloudActionMessage
                )
                AdminTab.STORAGE_HEALTH -> StorageHealthView(
                    settings = uiState.settings,
                    breakdown = uiState.storageBreakdown,
                    reconciliationReport = uiState.reconciliationReport,
                    lastActionMessage = uiState.lastStorageActionMessage,
                    onClearStorage = onClearStorage,
                    onPurgeOrphanedThumbnails = onPurgeOrphanedThumbnails,
                    onRunReconciliation = onRunReconciliation,
                    onDismissActionMessage = onDismissStorageActionMessage
                )
            }
        }

        // Import Progress Modal
        if (uiState.isImportingAudio) {
            AlertDialog(
                onDismissRequest = {},
                containerColor = RetroSurface,
                title = {
                    Text(text = "IMPORTING MUSIC", fontWeight = FontWeight.Black, color = TextPrimary)
                },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                    ) {
                        CircularProgressIndicator(color = NeonMagenta, modifier = Modifier.size(44.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = uiState.importProgressText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = "Extracting ID3 metadata & checking duplicates...",
                            fontSize = 11.sp,
                            color = TextMuted,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                },
                confirmButton = {}
            )
        }

        // Import Summary Report Modal
        uiState.lastImportSummary?.let { summary ->
            AlertDialog(
                onDismissRequest = onDismissImportSummary,
                containerColor = RetroSurface,
                title = {
                    Text(
                        text = "IMPORT COMPLETED",
                        fontWeight = FontWeight.Black,
                        color = PhosphorGreen
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Processed: ${summary.totalProcessed} file(s)",
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(text = "✅ Successfully imported: ${summary.successfulCount}", color = PhosphorGreen)
                        if (summary.duplicateCount > 0) {
                            Text(text = "⚠️ Duplicate files skipped: ${summary.duplicateCount}", color = RetroGold)
                        }
                        if (summary.failedCount > 0) {
                            Text(text = "❌ Failed to read: ${summary.failedCount}", color = Color.Red)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = onDismissImportSummary,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("DONE", color = TextPrimary)
                    }
                }
            )
        }

        // Edit Song Modal
        uiState.editingSong?.let { song ->
            EditSongDialog(
                song = song,
                onDismiss = onDismissEditSong,
                onSave = { title, artist, album, year, genre, decade ->
                    onSaveSongMetadata(song.id, title, artist, album, year, genre, decade)
                }
            )
        }

        // Delete Song Confirmation Modal
        uiState.songToDelete?.let { song ->
            val isReferenced = uiState.isSongToDeleteReferenced
            AlertDialog(
                onDismissRequest = onDismissDeleteSong,
                containerColor = RetroSurface,
                title = {
                    Text(
                        text = if (isReferenced) "RETIRE REFERENCED SONG?" else "PERMANENTLY DELETE SONG?",
                        fontWeight = FontWeight.Black,
                        color = TextPrimary
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (isReferenced) {
                                "Track \"${song.title}\" is referenced by one or more published videos in the event feed.\n\nDeleting this track will remove it from the kiosk song catalog and guest selection, but its local audio file will be safely preserved so existing videos retain their soundtrack."
                            } else {
                                "Are you sure you want to delete \"${song.title}\"? This track is not referenced by any videos and will be permanently removed along with its audio file from kiosk storage."
                            },
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        if (isReferenced) {
                            Text(
                                text = "Safe Retention Policy Active: Published videos will remain intact.",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = PhosphorGreen
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = onConfirmDeleteSong,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isReferenced) NeonMagenta else Color.Red
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (isReferenced) "RETIRE FROM CATALOG" else "PERMANENTLY DELETE",
                            color = TextPrimary
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissDeleteSong) {
                        Text("CANCEL", color = TextSecondary)
                    }
                }
            )
        }

        // Video Preview Modal
        uiState.previewingVideo?.let { video ->
            AdminVideoPreviewDialog(
                video = video,
                onDismiss = onDismissVideoPreview
            )
        }

        // Import Video Metadata Modal (SAF)
        if (uiState.pendingImportVideoUri != null) {
            ImportVideoMetadataDialog(
                onDismiss = onDismissImportVideoDialog,
                onConfirm = onConfirmImportVideo
            )
        }

        // Importing Video Progress Modal
        if (uiState.isImportingVideo) {
            AlertDialog(
                onDismissRequest = {},
                containerColor = RetroSurface,
                title = {
                    Text(text = "IMPORTING MP4 VIDEO", fontWeight = FontWeight.Black, color = TextPrimary)
                },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                    ) {
                        CircularProgressIndicator(color = NeonMagenta, modifier = Modifier.size(44.dp))
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Copying video, validating H.264/AAC codecs & generating thumbnail...",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                },
                confirmButton = {}
            )
        }

        // Delete Video Confirmation Modal
        uiState.videoToDelete?.let { video ->
            AlertDialog(
                onDismissRequest = onDismissDeleteVideo,
                containerColor = RetroSurface,
                title = {
                    Text(
                        text = "DELETE VIDEO RECORDING?",
                        fontWeight = FontWeight.Black,
                        color = TextPrimary
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Delete \"@${video.creatorNickname}\" (${video.song.title}) from the event feed?",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "This removes the Room database entry, the local MP4 file, and its thumbnail image. Original music catalog audio files will NOT be touched.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = onConfirmDeleteVideo,
                        enabled = !uiState.isPerformingCriticalAction,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("admin_confirm_delete_video_button")
                    ) {
                        Text("DELETE VIDEO", color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissDeleteVideo) {
                        Text("CANCEL", color = TextSecondary)
                    }
                }
            )
        }

        // Video Content Moderation Modal (Hide with reason / Restore)
        uiState.moderationTargetVideo?.let { target ->
            ModerationVideoDialog(
                video = target,
                onDismiss = onDismissModerationDialog,
                onConfirmModeration = { hide, reason ->
                    onConfirmModeration(target.id, hide, reason)
                }
            )
        }

        // End Event Confirmation Modal
        if (uiState.showEndEventConfirmation) {
            EndEventConfirmationDialog(
                isStudioBusy = isStudioBusy,
                onDismiss = onDismissEndEvent,
                onConfirm = onConfirmEndEvent
            )
        }

        // Reset Event Statistics Confirmation Modal
        if (uiState.showResetStatsConfirmation) {
            ResetEventStatsConfirmationDialog(
                onDismiss = onDismissResetEventStats,
                onConfirm = onConfirmResetEventStats
            )
        }
    }
}

@Composable
private fun DashboardOverview(
    uiState: AdminUiState,
    onOpenDiagnostics: () -> Unit,
    onOpenMusic: () -> Unit,
    onOpenCloudSettings: () -> Unit,
    onSyncPendingUploads: () -> Unit,
    onSaveEventDetails: (String, String) -> Unit,
    onStartEvent: (String, String) -> Unit,
    onPauseEvent: () -> Unit,
    onResumeEvent: () -> Unit,
    onRequestEndEvent: () -> Unit,
    onRequestResetStats: () -> Unit,
    onDismissEventActionMessage: () -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            CloudSyncOverviewCard(
                pairingState = uiState.cloudPairingState,
                queueCounts = uiState.cloudQueueCounts,
                isOnline = uiState.isCloudOnline,
                isSyncing = uiState.isCloudSyncing,
                onSyncPendingClick = onSyncPendingUploads,
                onConfigureClick = onOpenCloudSettings
            )
        }

        item {
            EventOperationsCard(
                settings = uiState.settings,
                metrics = uiState.eventMetrics,
                isBusy = uiState.isPerformingCriticalAction,
                actionMessage = uiState.eventActionMessage,
                onSaveDetails = onSaveEventDetails,
                onStartEvent = onStartEvent,
                onPauseEvent = onPauseEvent,
                onResumeEvent = onResumeEvent,
                onRequestEndEvent = onRequestEndEvent,
                onRequestResetStats = onRequestResetStats,
                onDismissMessage = onDismissEventActionMessage
            )
        }

        item {
            EventMonitoringPanel(
                metrics = uiState.eventMetrics,
                settings = uiState.settings
            )
        }

        // Quick Stats Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    title = "VIDEOS RECORDED",
                    value = uiState.videos.size.toString(),
                    icon = Icons.Default.Videocam,
                    color = NeonMagenta,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "MUSIC CATALOG",
                    value = uiState.songs.size.toString(),
                    icon = Icons.Default.MusicNote,
                    color = RetroGold,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Music Manager Shortcut Tile
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, RetroGold.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                    .clickable(onClick = onOpenMusic)
                    .testTag("admin_open_music_card")
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(RetroGold.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.MusicNote, contentDescription = null, tint = RetroGold)
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "MANAGE MUSIC CATALOG",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "Import MP3/AAC via SAF, view decade taxonomy, edit ID3 tags",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    Text(
                        text = "MANAGE",
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        color = RetroGold
                    )
                }
            }
        }

        // Hardware Diagnostics Tile
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                    .clickable(onClick = onOpenDiagnostics)
                    .testTag("admin_open_diagnostics_button")
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(ElectricCyan.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.Usb, contentDescription = null, tint = ElectricCyan)
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "LOGITECH WEBCAM DIAGNOSTICS",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "Validate 8 hardware tests: UVC, Camera2 HAL, resolution, power",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    Text(
                        text = "TEST",
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        color = ElectricCyan
                    )
                }
            }
        }
    }
}

@Composable
private fun MusicManagerView(
    songs: List<SongItem>,
    metrics: com.example.data.repository.MusicStorageMetrics,
    isImporting: Boolean,
    importProgressText: String,
    previewingSongId: String?,
    isPreviewPlaying: Boolean,
    onImportClick: () -> Unit,
    onTogglePreview: (SongItem) -> Unit,
    onToggleLicense: (SongItem) -> Unit,
    onEditClick: (SongItem) -> Unit,
    onDeleteClick: (SongItem) -> Unit
) {
    val totalMb = metrics.totalFileSizeBytes / (1024 * 1024)

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // Library Overview Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "LOCAL MUSIC STORAGE",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = PhosphorGreen
                            )
                            Text(
                                text = "${metrics.totalSongs} Tracks • $totalMb MB on Disk",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                        }

                        Button(
                            onClick = onImportClick,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.testTag("admin_import_music_button")
                        ) {
                            Icon(imageVector = Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "IMPORT (SAF)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Decade Distribution Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DecadeCountPill("70s", metrics.songsByDecade[RetroDecade.DECADE_70S] ?: 0, RetroGold)
                        DecadeCountPill("80s", metrics.songsByDecade[RetroDecade.DECADE_80S] ?: 0, NeonMagenta)
                        DecadeCountPill("90s", metrics.songsByDecade[RetroDecade.DECADE_90S] ?: 0, ElectricCyan)
                        DecadeCountPill("00s", metrics.songsByDecade[RetroDecade.DECADE_00S] ?: 0, PhosphorGreen)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Approved for Guests: ${metrics.approvedSongs} / ${metrics.totalSongs} • Only approved tracks appear in guest picker",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }
        }

        // Empty State when no music has been imported yet
        if (songs.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = RetroGold,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "NO LOCAL TRACKS IMPORTED",
                            fontWeight = FontWeight.Black,
                            fontSize = 16.sp,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Plug in a USB flash drive with MP3 or AAC files and tap 'IMPORT (SAF)' above to add tracks to the kiosk music catalog.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }

        // Song Items List
        items(songs) { song ->
            val isPlayingThis = previewingSongId == song.id && isPreviewPlaying
            val isApproved = song.licenseApprovalStatus == "APPROVED_FOR_EVENT_USE"
            val isUnreviewed = song.licenseApprovalStatus == "UNREVIEWED"
            val badgeColor = when {
                isApproved -> PhosphorGreen
                isUnreviewed -> RetroGold
                else -> Color.Red
            }
            val badgeText = when {
                isApproved -> "APPROVED"
                isUnreviewed -> "UNREVIEWED"
                else -> "REJECTED"
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Play/Pause Preview Button
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(if (isPlayingThis) NeonMagenta else RetroSurface)
                                .border(1.dp, if (isPlayingThis) NeonMagenta else RetroSurfaceBorder, CircleShape)
                                .clickable { onTogglePreview(song) }
                                .testTag("admin_preview_song_${song.id}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlayingThis) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = if (isPlayingThis) RetroOnyx else ElectricCyan,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Title & Artist
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = song.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = TextPrimary,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(RetroSurfaceBorder)
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = song.releaseYear?.toString() ?: "N/A",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextSecondary
                                    )
                                }
                            }
                            Text(
                                text = "${song.artist} • ${song.album}",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                maxLines = 1
                            )
                        }

                        // License Status Pill
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(badgeColor.copy(alpha = 0.2f))
                                .border(1.dp, badgeColor, RoundedCornerShape(10.dp))
                                .clickable { onToggleLicense(song) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("admin_toggle_license_${song.id}")
                        ) {
                            Text(
                                text = badgeText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Secondary Action Row (Edit, Delete, Duration)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${song.durationSeconds}s • ${song.genre} • ${song.decade.label}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TextMuted
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(
                                onClick = { onEditClick(song) },
                                modifier = Modifier.size(32.dp).testTag("admin_edit_song_${song.id}")
                            ) {
                                Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit", tint = ElectricCyan, modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = { onDeleteClick(song) },
                                modifier = Modifier.size(32.dp).testTag("admin_delete_song_${song.id}")
                            ) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DecadeCountPill(label: String, count: Int, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = "$label: $count",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun EditSongDialog(
    song: SongItem,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Int?, String, RetroDecade) -> Unit
) {
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist) }
    var album by remember { mutableStateOf(song.album) }
    var yearStr by remember { mutableStateOf(song.releaseYear?.toString() ?: "") }
    var genre by remember { mutableStateOf(song.genre) }
    var selectedDecade by remember { mutableStateOf(song.decade) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RetroSurface,
        title = {
            Text(text = "EDIT SONG METADATA", fontWeight = FontWeight.Black, color = TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary)
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary)
                )
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("Album") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = yearStr,
                        onValueChange = {
                            yearStr = it
                            val y = it.toIntOrNull()
                            if (y != null) {
                                selectedDecade = when (y) {
                                    in 1970..1979 -> RetroDecade.DECADE_70S
                                    in 1980..1989 -> RetroDecade.DECADE_80S
                                    in 1990..1999 -> RetroDecade.DECADE_90S
                                    in 2000..2009 -> RetroDecade.DECADE_00S
                                    else -> RetroDecade.DECADE_UNKNOWN
                                }
                            }
                        },
                        label = { Text("Year (e.g. 1984)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary)
                    )
                    OutlinedTextField(
                        value = genre,
                        onValueChange = { genre = it },
                        label = { Text("Genre") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary)
                    )
                }

                Text(text = "Decade Era:", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val decades = listOf(
                        RetroDecade.DECADE_70S to "70s",
                        RetroDecade.DECADE_80S to "80s",
                        RetroDecade.DECADE_90S to "90s",
                        RetroDecade.DECADE_00S to "00s",
                        RetroDecade.DECADE_UNKNOWN to "Other"
                    )
                    decades.forEach { (decade, label) ->
                        val isSelected = selectedDecade == decade
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) NeonMagenta else RetroSurfaceElevated)
                                .border(1.dp, if (isSelected) NeonMagenta else RetroSurfaceBorder, RoundedCornerShape(8.dp))
                                .clickable { selectedDecade = decade }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) RetroOnyx else TextPrimary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val year = yearStr.toIntOrNull()
                    onSave(title, artist, album, year, genre, selectedDecade)
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("SAVE METADATA", color = TextPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = value, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black), color = TextPrimary)
            Text(text = title, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextMuted)
        }
    }
}

@Composable
private fun VideoManagerView(
    videos: List<VideoItem>,
    totalCount: Int,
    searchQuery: String,
    selectedFilter: ModerationFilter,
    isImportingVideo: Boolean,
    videoImportMessage: String?,
    isCloudPaired: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onFilterSelected: (ModerationFilter) -> Unit,
    onImportVideoClick: () -> Unit,
    onReconcileClick: () -> Unit,
    onDismissImportMessage: () -> Unit,
    onPreviewVideo: (VideoItem) -> Unit,
    onModerateVideo: (VideoItem) -> Unit,
    onDeleteVideo: (VideoItem) -> Unit,
    onToggleFeatured: (String) -> Unit,
    onRetryVideoUpload: (String) -> Unit
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.US) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "PERSISTED EVENT VIDEOS",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = ElectricCyan
                            )
                            Text(
                                text = "${videos.size} shown of $totalCount in Room DB",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onReconcileClick,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("admin_reconcile_videos_button")
                            ) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("SYNC", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ElectricCyan)
                            }
                            Button(
                                onClick = onImportVideoClick,
                                enabled = !isImportingVideo,
                                colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("admin_import_video_button")
                            ) {
                                Icon(imageVector = Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("IMPORT MP4", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        placeholder = {
                            Text("Filter by nickname, caption, #tag, or song...", fontSize = 12.sp, color = TextMuted)
                        },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = TextSecondary)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchQueryChange("") }) {
                                    Icon(imageVector = Icons.Default.Close, contentDescription = "Clear", tint = TextSecondary)
                                }
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = NeonMagenta,
                            unfocusedBorderColor = RetroSurfaceBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_video_search_input")
                    )

                    // Moderation Status Filter Chips
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(ModerationFilter.values()) { filter ->
                            val isSelected = selectedFilter == filter
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) ElectricCyan else RetroSurface)
                                    .border(1.dp, if (isSelected) ElectricCyan else RetroSurfaceBorder, RoundedCornerShape(12.dp))
                                    .clickable { onFilterSelected(filter) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                                    .testTag("moderation_filter_${filter.name}")
                            ) {
                                Text(
                                    text = filter.label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.Black else TextSecondary
                                )
                            }
                        }
                    }

                    if (videoImportMessage != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(RetroSurface)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = videoImportMessage,
                                fontSize = 12.sp,
                                color = if (videoImportMessage.startsWith("Import failed")) Color.Red else PhosphorGreen,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = onDismissImportMessage, modifier = Modifier.size(24.dp)) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Dismiss", tint = TextSecondary, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }

        if (videos.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(imageVector = Icons.Default.Videocam, contentDescription = null, tint = TextMuted, modifier = Modifier.size(44.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (totalCount == 0) "NO RECORDED OR IMPORTED VIDEOS" else "NO MATCHING VIDEOS",
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (totalCount == 0) "Guest recordings or SAF-imported MP4 clips will appear here." else "Try clearing the search or moderation filter.",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        items(videos, key = { it.id }) { video ->
            val thumbFile = remember(video.thumbnailPath) {
                video.thumbnailPath?.takeIf { it.isNotBlank() }?.let { path ->
                    val f = if (path.startsWith("/")) File(path) else File(context.filesDir, path)
                    if (f.exists() && f.length() > 0L) f else null
                }
            }
            val sizeLabel = remember(video.fileSizeBytes) { formatStorageBytes(video.fileSizeBytes) }
            val dateLabel = remember(video.createdAt) { dateFormat.format(Date(video.createdAt)) }
            val moderatedLabel = remember(video.moderatedAt) {
                video.moderatedAt?.let { dateFormat.format(Date(it)) }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.testTag("admin_video_card_${video.id}")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Thumbnail preview tile
                    Box(
                        modifier = Modifier
                            .width(54.dp)
                            .height(82.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(RetroSurface)
                            .border(1.dp, if (video.isFileHealthy) RetroSurfaceBorder else Color.Red, RoundedCornerShape(10.dp))
                            .clickable { onPreviewVideo(video) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (thumbFile != null) {
                            AsyncImage(
                                model = thumbFile,
                                contentDescription = "Video thumbnail",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Preview Video",
                            tint = Color.White,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(0x88000000))
                                .padding(3.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "@${video.creatorNickname}",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (video.isHidden) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(RetroGold.copy(alpha = 0.22f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(text = "HIDDEN", fontSize = 9.sp, fontWeight = FontWeight.Black, color = RetroGold)
                                }
                            }
                            if (video.isFeatured) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(RetroGold.copy(alpha = 0.2f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(text = "FEATURED", fontSize = 9.sp, fontWeight = FontWeight.Black, color = RetroGold)
                                }
                            }
                            if (video.publicationStatus == "IMPORTED") {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(ElectricCyan.copy(alpha = 0.2f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(text = "IMPORTED", fontSize = 9.sp, fontWeight = FontWeight.Black, color = ElectricCyan)
                                }
                            }
                            if (!video.isFileHealthy) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.Red.copy(alpha = 0.2f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(text = "MISSING FILE", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color.Red)
                                }
                            }
                        }

                        Text(
                            text = video.caption,
                            fontSize = 12.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${video.song.title} • ${video.effect.title}",
                            fontSize = 11.sp,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${video.durationSeconds}s • $sizeLabel • ${video.likeCount} likes • $dateLabel",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = TextMuted
                        )
                        if (video.isHidden) {
                            Text(
                                text = "Hidden ${moderatedLabel ?: ""}: ${video.moderationReason ?: "No reason logged"}",
                                fontSize = 10.sp,
                                color = RetroGold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        AdminVideoCloudStatusBadge(
                            summary = video.cloudUploadSummary,
                            isPaired = isCloudPaired,
                            onRetryUpload = { onRetryVideoUpload(video.id) },
                            videoId = video.id
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = { onPreviewVideo(video) },
                            modifier = Modifier.size(34.dp).testTag("admin_preview_video_${video.id}")
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Preview", tint = ElectricCyan, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = { onToggleFeatured(video.id) },
                            modifier = Modifier.size(34.dp).testTag("admin_feature_video_${video.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Feature",
                                tint = if (video.isFeatured) RetroGold else TextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = { onModerateVideo(video) },
                            modifier = Modifier.size(34.dp).testTag("admin_moderate_video_${video.id}")
                        ) {
                            Icon(
                                imageVector = if (video.isHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (video.isHidden) "Restore to Feed" else "Hide from Feed",
                                tint = if (video.isHidden) PhosphorGreen else RetroGold,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { onDeleteVideo(video) },
                            modifier = Modifier.size(34.dp).testTag("admin_delete_video_${video.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = Color.Red,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminVideoPreviewDialog(
    video: VideoItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val videoFile = remember(video.localVideoPath) {
        video.localVideoPath?.takeIf { it.isNotBlank() }?.let { path ->
            val file = if (path.startsWith("/")) File(path) else File(context.filesDir, path)
            if (file.exists() && file.length() > 32L) file else null
        }
    }
    var playbackError by remember(video.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RetroSurface,
        title = {
            Text(
                text = "@${video.creatorNickname}",
                fontWeight = FontWeight.Black,
                color = TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(9f / 14f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(RetroOnyx),
                    contentAlignment = Alignment.Center
                ) {
                    if (videoFile != null && !playbackError) {
                        val exoPlayer = remember(videoFile.absolutePath) {
                            ExoPlayer.Builder(context).build().apply {
                                setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
                                repeatMode = Player.REPEAT_MODE_ALL
                                playWhenReady = true
                                prepare()
                            }
                        }

                        DisposableEffect(exoPlayer) {
                            val listener = object : Player.Listener {
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

                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = true
                                    player = exoPlayer
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                            Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (playbackError) "Codec / playback error for this MP4." else "Local video file is missing or corrupt.",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                Text(text = video.caption, color = TextPrimary, fontSize = 13.sp)
                Text(
                    text = "${video.song.title} • ${video.durationSeconds}s • ${video.resolutionWidth}x${video.resolutionHeight} • ${formatStorageBytes(video.fileSizeBytes)}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("CLOSE", color = TextPrimary)
            }
        }
    )
}

@Composable
private fun ImportVideoMetadataDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var nickname by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    var hashtags by remember { mutableStateOf("#RetroTok #EventHighlight") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RetroSurface,
        title = {
            Text(text = "IMPORT MP4 DETAILS", fontWeight = FontWeight.Black, color = TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Only H.264/AVC MP4 files with AAC/MP3 audio are accepted for API 23 playback.",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { if (it.length <= 24) nickname = it },
                    label = { Text("Creator Nickname") },
                    placeholder = { Text("EventGuest") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                    modifier = Modifier.fillMaxWidth().testTag("import_video_nickname_input")
                )
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    label = { Text("Caption") },
                    placeholder = { Text("Shared dance highlight") },
                    maxLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                    modifier = Modifier.fillMaxWidth().testTag("import_video_caption_input")
                )
                OutlinedTextField(
                    value = hashtags,
                    onValueChange = { hashtags = it },
                    label = { Text("Hashtags") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                    modifier = Modifier.fillMaxWidth().testTag("import_video_hashtags_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(nickname, caption, hashtags) },
                colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("confirm_import_video_button")
            ) {
                Text("IMPORT TO FEED", color = TextPrimary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun KioskSettingsView(
    settings: com.example.model.KioskSettings,
    uiState: AdminUiState,
    isDeviceOwner: Boolean,
    onDurationSelected: (Int) -> Unit,
    onToggleGuestDeletion: () -> Unit,
    onToggleKioskLock: () -> Unit,
    onToggleWideView: () -> Unit,
    onUpdateFeedControls: (Boolean, Boolean, Boolean, Boolean, Boolean, FeedSortOrder, Boolean) -> Unit,
    onPairCloudDevice: (String, String, String) -> Unit,
    onTestCloudHeartbeat: () -> Unit,
    onSyncPendingUploads: () -> Unit,
    onUnpairCloudDevice: () -> Unit,
    onDismissCloudActionMessage: () -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            CloudSyncPairingCard(
                pairingState = uiState.cloudPairingState,
                queueCounts = uiState.cloudQueueCounts,
                isOnline = uiState.isCloudOnline,
                isSyncing = uiState.isCloudSyncing,
                isBusy = uiState.isCloudActionBusy,
                actionMessage = uiState.cloudActionMessage,
                onPairDevice = onPairCloudDevice,
                onTestHeartbeat = onTestCloudHeartbeat,
                onSyncPending = onSyncPendingUploads,
                onUnpairDevice = onUnpairCloudDevice,
                onDismissActionMessage = onDismissCloudActionMessage
            )
        }

        item {
            FeedControlsSettingsSection(
                settings = settings,
                onUpdateFeedControls = onUpdateFeedControls
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "RECORDING DURATION",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonMagenta
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(15, 30, 60).forEach { sec ->
                            val isSelected = settings.defaultRecordingDurationSeconds == sec
                            Button(
                                onClick = { onDurationSelected(sec) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) NeonMagenta else RetroSurface
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("duration_option_$sec")
                            ) {
                                Text(text = "${sec}s")
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Guest Video Deletion",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = if (settings.allowGuestDeletion) "ON (Guests may delete their own take)" else "OFF (Only Admin can delete videos)",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = settings.allowGuestDeletion,
                            onCheckedChange = { onToggleGuestDeletion() },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonMagenta),
                            modifier = Modifier.testTag("toggle_guest_deletion_switch")
                        )
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Wide View",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = if (settings.isWideViewEnabled) {
                                    "ON (Full webcam frame preserved for preview and recording)"
                                } else {
                                    "OFF (Portrait 9:16 center crop)"
                                },
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = settings.isWideViewEnabled,
                            onCheckedChange = { onToggleWideView() },
                            colors = SwitchDefaults.colors(checkedThumbColor = ElectricCyan),
                            modifier = Modifier.testTag("toggle_wide_view_switch")
                        )
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isDeviceOwner) "Lock Task Mode (Device Owner)" else "Screen Pinning Fallback",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = if (isDeviceOwner) {
                                    "Device Owner active — full Lock Task mode suppresses Home/Recents/Notifications."
                                } else {
                                    "Standard app mode (not Device Owner) — uses Android Screen Pinning fallback; users can unpin via system combo unless provisioned via DPM."
                                },
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = settings.isKioskLockEnabled,
                            onCheckedChange = { onToggleKioskLock() },
                            colors = SwitchDefaults.colors(checkedThumbColor = PhosphorGreen),
                            modifier = Modifier.testTag("toggle_kiosk_lock_switch")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageHealthView(
    settings: com.example.model.KioskSettings,
    breakdown: VideoStorageBreakdown,
    reconciliationReport: ReconciliationReport?,
    lastActionMessage: String?,
    onClearStorage: () -> Unit,
    onPurgeOrphanedThumbnails: () -> Unit,
    onRunReconciliation: () -> Unit,
    onDismissActionMessage: () -> Unit
) {
    val totalBytes = if (breakdown.totalDeviceBytes > 0L) {
        breakdown.totalDeviceBytes
    } else {
        settings.totalStorageMb.toLong() * 1024L * 1024L
    }
    val freeBytes = if (breakdown.totalDeviceBytes > 0L) {
        breakdown.availableDeviceBytes
    } else {
        settings.freeStorageMb.toLong() * 1024L * 1024L
    }
    val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L)
    val usedRatio = if (totalBytes > 0L) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        if (breakdown.isRecordingBlocked || breakdown.isLowStorageWarning) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (breakdown.isRecordingBlocked) Color(0xFF4A0D12) else Color(0xFF3E2D07)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (breakdown.isRecordingBlocked) Color.Red else RetroGold
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (breakdown.isRecordingBlocked) {
                                "CRITICAL LOW STORAGE (< 100 MB free): New guest recordings are safely blocked until space is freed."
                            } else {
                                "LOW STORAGE WARNING (< 500 MB free): Consider deleting unused videos or purging staging cache."
                            },
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        if (lastActionMessage != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = lastActionMessage,
                            color = PhosphorGreen,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismissActionMessage, modifier = Modifier.size(28.dp)) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Dismiss", tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "DEVICE STORAGE (STATFS)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = PhosphorGreen
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { usedRatio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = if (breakdown.isRecordingBlocked) Color.Red else PhosphorGreen,
                        trackColor = RetroSurfaceBorder
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Used: ${formatStorageBytes(usedBytes)}", color = TextSecondary, fontSize = 12.sp)
                        Text(text = "Free: ${formatStorageBytes(freeBytes)}", color = PhosphorGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "RETROTOK LOCAL BREAKDOWN (${formatStorageBytes(breakdown.totalAppBytes)})",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan
                    )
                    StorageBreakdownRow("Published Videos (${breakdown.publishedCount})", breakdown.publishedBytes, NeonMagenta)
                    StorageBreakdownRow("Imported Videos (${breakdown.importedCount})", breakdown.importedBytes, ElectricCyan)
                    StorageBreakdownRow("Music Catalog", breakdown.musicBytes, RetroGold)
                    StorageBreakdownRow("Thumbnails (${breakdown.thumbnailCount})", breakdown.thumbnailBytes, TextSecondary)
                    StorageBreakdownRow("Staging Temp Cache", breakdown.tempStagingBytes, PhosphorGreen)
                    StorageBreakdownRow("Recoverable Takes (${breakdown.recoverableCount} protected)", breakdown.recoverableBytes, RetroGold)
                }
            }
        }

        if (reconciliationReport != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "DB / STORAGE RECONCILIATION",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = RetroGold
                        )
                        Text(text = "Recovered orphan MP4s: ${reconciliationReport.recoveredOrphanFiles}", fontSize = 12.sp, color = TextPrimary)
                        Text(text = "Missing-file records flagged: ${reconciliationReport.flaggedMissingRecords}", fontSize = 12.sp, color = TextSecondary)
                        Text(text = "Thumbnails regenerated: ${reconciliationReport.regeneratedThumbnails}", fontSize = 12.sp, color = TextSecondary)
                        Text(text = "Orphaned thumbnails on disk: ${reconciliationReport.orphanedThumbnailsCount}", fontSize = 12.sp, color = TextSecondary)
                    }
                }
            }
        }

        item {
            Button(
                onClick = onClearStorage,
                colors = ButtonDefaults.buttonColors(containerColor = RetroSurfaceElevated),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("admin_clear_storage_button")
            ) {
                Icon(imageVector = Icons.Default.CleaningServices, contentDescription = null, tint = ElectricCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "PURGE STAGING CACHE (SAFE)", color = TextPrimary, fontWeight = FontWeight.Bold)
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onPurgeOrphanedThumbnails,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).testTag("admin_purge_thumbs_button")
                ) {
                    Text("CLEAN THUMBS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                OutlinedButton(
                    onClick = onRunReconciliation,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).testTag("admin_reconcile_storage_button")
                ) {
                    Text("RECONCILE DB", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ElectricCyan)
                }
            }
        }
    }
}

@Composable
private fun StorageBreakdownRow(label: String, bytes: Long, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, color = TextSecondary)
        Text(
            text = formatStorageBytes(bytes),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = accent
        )
    }
}

private fun formatStorageBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        else -> String.format(Locale.US, "%.0f KB", kb)
    }
}
