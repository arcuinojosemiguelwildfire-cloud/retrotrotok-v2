package com.example

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.di.AppContainer
import com.example.model.RetroDecade
import com.example.model.SongItem
import com.example.ui.navigation.Screen
import com.example.ui.screens.admin.AdminDashboardScreen
import com.example.ui.screens.admin.AdminPinDialog
import com.example.ui.screens.admin.AdminViewModel
import com.example.ui.screens.feed.DiscoverVideosSheet
import com.example.ui.screens.feed.FeedScreen
import com.example.ui.screens.feed.FeedViewModel
import com.example.ui.screens.hardware.HardwareValidationScreen
import com.example.ui.screens.hardware.HardwareValidationViewModel
import com.example.ui.screens.music.SongPickerSheet
import com.example.ui.screens.preview.PreviewViewModel
import com.example.ui.screens.preview.VideoPreviewScreen
import com.example.ui.screens.studio.DanceStudioScreen
import com.example.ui.screens.studio.StudioNavigationEvent
import com.example.ui.screens.studio.StudioState
import com.example.ui.screens.studio.StudioViewModel
import com.example.ui.screens.welcome.WelcomeScreen
import com.example.ui.theme.RetroTokTheme
import com.example.video.RecoverableRecording
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContainer.init(this)
        // Kiosk display persistence: prevent screen from turning off
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Do NOT delete temp recordings unconditionally at startup; inspect & offer safe recovery
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val isDeviceOwner = try {
            dpm?.isDeviceOwnerApp(packageName) == true
        } catch (_: Exception) {
            false
        }
        enableEdgeToEdge()
        setContent {
            RetroTokTheme {
                RetroTokKioskApp(
                    isDeviceOwner = isDeviceOwner,
                    onToggleKioskLock = { enable ->
                        try {
                            if (enable) startLockTask() else stopLockTask()
                        } catch (_: Exception) {
                            // Ignored if lock task / screen pinning cannot be activated
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun RetroTokKioskApp(
    isDeviceOwner: Boolean = false,
    onToggleKioskLock: (Boolean) -> Unit = {},
    initialScreen: Screen = Screen.Welcome,
    feedViewModelOverride: FeedViewModel? = null,
    studioViewModelOverride: StudioViewModel? = null,
    previewViewModelOverride: PreviewViewModel? = null,
    adminViewModelOverride: AdminViewModel? = null,
    skipStartupBackgroundSync: Boolean = false
) {
    val feedViewModel = remember(feedViewModelOverride) {
        feedViewModelOverride ?: FeedViewModel(
            repository = AppContainer.videoFeedRepository,
            settingsRepository = AppContainer.kioskSettingsRepository,
            musicCatalogRepository = AppContainer.musicCatalogRepository,
            musicStorageManager = AppContainer.musicStorageManager
        )
    }
    val studioViewModel = remember(studioViewModelOverride) {
        studioViewModelOverride ?: StudioViewModel(
            cameraProvider = AppContainer.cameraProvider,
            recordingController = AppContainer.cameraRecordingController,
            soundtrackController = AppContainer.soundtrackSessionController,
            storageManager = AppContainer.videoStorageManager,
            muxerProcessor = AppContainer.videoMuxerProcessor,
            settingsRepository = AppContainer.kioskSettingsRepository
        )
    }
    val previewViewModel = remember(previewViewModelOverride) {
        previewViewModelOverride ?: PreviewViewModel(
            videoFeedRepository = AppContainer.videoFeedRepository,
            videoStorageManager = AppContainer.videoStorageManager,
            settingsRepository = AppContainer.kioskSettingsRepository,
            cloudUploadManager = AppContainer.cloudUploadManager
        )
    }
    val adminViewModel = remember(adminViewModelOverride) {
        adminViewModelOverride ?: AdminViewModel(
            settingsRepository = AppContainer.kioskSettingsRepository,
            videoFeedRepository = AppContainer.videoFeedRepository,
            musicCatalogRepository = AppContainer.musicCatalogRepository,
            audioPreviewManager = AppContainer.audioPreviewManager,
            storageManager = AppContainer.musicStorageManager,
            videoStorageManager = AppContainer.videoStorageManager,
            cloudUploadManager = AppContainer.cloudUploadManager
        )
    }
    val hardwareValidationViewModel = remember {
        HardwareValidationViewModel(
            cameraDetector = AppContainer.cameraDetector,
            cameraProvider = AppContainer.cameraProvider,
            cameraPreviewController = AppContainer.cameraPreviewController
        )
    }

    // Kiosk Recovery & Restart Safety: always restore to safe Welcome state without restoring Admin UI or active recording
    var currentScreen by remember { mutableStateOf<Screen>(initialScreen) }
    var showAdminPinDialog by remember { mutableStateOf(false) }
    var showDiscoverVideosSheet by remember { mutableStateOf(false) }
    var showSongPickerSheet by remember { mutableStateOf(false) }
    var showEventPausedNotice by remember { mutableStateOf(false) }
    var showInactivityWarning by remember { mutableStateOf(false) }
    var inactivityCountdown by remember { mutableIntStateOf(15) }
    var recoverableSession by remember { mutableStateOf<RecoverableRecording?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // State collections
    val feedUiState by feedViewModel.uiState.collectAsStateWithLifecycle()
    val studioUiState by studioViewModel.uiState.collectAsStateWithLifecycle()
    val previewUiState by previewViewModel.uiState.collectAsStateWithLifecycle()
    val adminUiState by adminViewModel.uiState.collectAsStateWithLifecycle()
    val hardwareUiState by hardwareValidationViewModel.uiState.collectAsStateWithLifecycle()

    fun endGuestSessionAndReturnTo(destination: Screen) {
        AppContainer.guestSessionManager.endCurrentSession()
        studioViewModel.startNewGuestSession()
        previewViewModel.clearSessionState()
        currentScreen = destination
    }

    fun requestEnterDanceStudio(preselectedSong: SongItem? = null) {
        if (currentScreen == Screen.DanceStudio) {
            return
        }
        if (!adminUiState.settings.isAcceptingGuestRecordings) {
            showEventPausedNotice = true
            return
        }
        feedViewModel.clearPendingAutoQr()
        studioViewModel.startNewGuestSession()
        preselectedSong?.let { studioViewModel.selectSong(it) }
        currentScreen = Screen.DanceStudio
    }

    // Inspect temp/ for unfinished recordings and resume interrupted cloud upload queue cleanly
    LaunchedEffect(skipStartupBackgroundSync) {
        if (!skipStartupBackgroundSync) {
            val sessions = AppContainer.videoStorageManager.findRecoverableTempSessions()
            if (sessions.isNotEmpty()) {
                recoverableSession = sessions.first()
            }
            AppContainer.cloudUploadManager.launchBackgroundSync(forceRetryDelayReset = false)
        }
    }

    // Enforce persisted Lock Task state automatically at startup when provisioned as Device Owner
    LaunchedEffect(isDeviceOwner, adminUiState.settings.isKioskLockEnabled) {
        if (isDeviceOwner) {
            onToggleKioskLock(adminUiState.settings.isKioskLockEnabled)
        }
    }

    // Listen for studio recording completion to navigate to Preview
    LaunchedEffect(studioViewModel) {
        studioViewModel.navEvents.collect { event ->
            when (event) {
                is StudioNavigationEvent.NavigateToPreview -> {
                    previewViewModel.initialize(
                        videoFilePath = event.videoFilePath,
                        song = event.song,
                        effect = event.effect,
                        durationSeconds = event.durationSeconds
                    )
                    currentScreen = Screen.VideoPreview
                }
                StudioNavigationEvent.NavigateBack -> {
                    endGuestSessionAndReturnTo(Screen.Welcome)
                }
            }
        }
    }

    // React to successful admin PIN verification
    LaunchedEffect(adminUiState.isAuthenticated) {
        if (adminUiState.isAuthenticated) {
            feedViewModel.clearPendingAutoQr()
            showAdminPinDialog = false
            currentScreen = Screen.AdminDashboard
        }
    }

    // Inactivity Watchdog: NEVER interrupts active recording, finalizing, or video processing
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val isRecordingOrProcessing = studioUiState.studioState == StudioState.RECORDING ||
                                  studioUiState.studioState == StudioState.INITIALIZING_CAMERA ||
                                  studioUiState.studioState == StudioState.COUNTDOWN ||
                                  studioUiState.studioState == StudioState.FINALIZING ||
                                  studioUiState.studioState == StudioState.PROCESSING ||
                                  previewUiState.isPublishing

    // If Admin ended the event while recording/processing was running, safely transition to Welcome once processing finishes
    LaunchedEffect(adminUiState.pendingEndEventAfterActiveSession, isRecordingOrProcessing) {
        if (adminUiState.pendingEndEventAfterActiveSession && !isRecordingOrProcessing) {
            adminViewModel.clearPendingEndEvent()
            adminViewModel.logout()
            endGuestSessionAndReturnTo(Screen.Welcome)
        }
    }

    // Guest Inactivity Watchdog with 15-second countdown warning
    LaunchedEffect(lastInteractionTime, currentScreen, isRecordingOrProcessing) {
        showInactivityWarning = false
        if (!isRecordingOrProcessing &&
            currentScreen != Screen.Feed &&
            currentScreen != Screen.Welcome &&
            currentScreen != Screen.AdminDashboard &&
            currentScreen != Screen.HardwareValidation
        ) {
            val totalTimeout = adminUiState.settings.idleTimeoutSeconds
            val warningDelaySeconds = (totalTimeout - 15).coerceAtLeast(5)
            delay(warningDelaySeconds * 1000L)

            // Trigger 15-second warning countdown before reset
            showInactivityWarning = true
            inactivityCountdown = 15
            while (inactivityCountdown > 0) {
                delay(1000L)
                inactivityCountdown--
            }

            // Time expired: isolate fresh guest session & return safely WITHOUT deleting unpublished takes in temp/
            showInactivityWarning = false
            endGuestSessionAndReturnTo(Screen.Welcome)
        }
    }

    // Admin Auto-Logout Watchdog: Auto-logout after 5 minutes of idle time
    LaunchedEffect(lastInteractionTime, currentScreen) {
        if (currentScreen == Screen.AdminDashboard || currentScreen == Screen.HardwareValidation) {
            delay(300_000L) // 5 minutes
            adminViewModel.logout()
            currentScreen = Screen.Welcome
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastInteractionTime = System.currentTimeMillis()
                    }
                }
            }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (currentScreen) {
                Screen.Welcome -> {
                    WelcomeScreen(
                        settings = adminUiState.settings,
                        onExploreFeedClick = { currentScreen = Screen.Feed },
                        onStartRecordingClick = { requestEnterDanceStudio() },
                        onAdminClick = { showAdminPinDialog = true }
                    )
                }

                Screen.Feed -> {
                    BackHandler(enabled = feedUiState.qrDialogVideoId == null) {
                        feedViewModel.clearPendingAutoQr()
                        currentScreen = Screen.Welcome
                    }
                    FeedScreen(
                        uiState = feedUiState,
                        onPageChanged = { feedViewModel.onPageChanged(it) },
                        onLikeClick = { feedViewModel.onToggleLike(it) },
                        onFavoriteClick = { feedViewModel.onToggleFavorite(it) },
                        onDoubleTapLike = { offset, id -> feedViewModel.onDoubleTapLike(offset, id) },
                        onRemoveHeart = { feedViewModel.removeFloatingHeart(it) },
                        onPlayPauseToggle = { feedViewModel.togglePlayPause() },
                        onDanceStudioClick = { requestEnterDanceStudio() },
                        onAdminTrigger = { showAdminPinDialog = true },
                        onFilterTabSelected = { feedViewModel.selectFilterTab(it) },
                        onDiscoverMusicClick = {
                            if (feedUiState.enableDiscover) {
                                showDiscoverVideosSheet = true
                            }
                        },
                        onQrClick = { feedViewModel.openQrDialog(it) },
                        onDismissQrDialog = { feedViewModel.dismissQrDialog() },
                        onUseSongClick = { video ->
                            feedViewModel.onUseThisSongClicked(video) { resolvedSong ->
                                if (currentScreen == Screen.Feed) {
                                    requestEnterDanceStudio(preselectedSong = resolvedSong)
                                }
                            }
                        },
                        onDismissSongActionMessage = { feedViewModel.dismissSongActionMessage() }
                    )
                }

                Screen.DanceStudio -> {
                    DanceStudioScreen(
                        uiState = studioUiState,
                        cameraPreviewController = AppContainer.cameraPreviewController,
                        cameraRecordingController = AppContainer.cameraRecordingController,
                        onSongPillClick = { showSongPickerSheet = true },
                        onEffectSelect = { studioViewModel.selectEffect(it) },
                        onDurationSelect = { studioViewModel.setRecordingDuration(it) },
                        onStartRecording = { studioViewModel.startCountdownAndRecord() },
                        onStopRecording = { studioViewModel.stopRecordingManually() },
                        onToggleMirror = { studioViewModel.toggleMirror() },
                        onToggleGrid = { studioViewModel.toggleGrid() },
                        onRetryCamera = { studioViewModel.retryCameraConnection() },
                        onCloseClick = {
                            endGuestSessionAndReturnTo(Screen.Welcome)
                        }
                    )
                }

                Screen.VideoPreview -> {
                    VideoPreviewScreen(
                        uiState = previewUiState,
                        onNicknameChange = {
                            previewViewModel.onNicknameChange(it)
                            AppContainer.guestSessionManager.updateNickname(it)
                        },
                        onEffectChange = { previewViewModel.onEffectChange(it) },
                        onAddToFeedClick = { previewViewModel.requestPublish() },
                        onConfirmPublish = {
                            previewViewModel.confirmPublish(
                                onPublishedVideo = { publishedId, isCloudEnabled ->
                                    feedViewModel.onVideoPublished(
                                        videoId = publishedId,
                                        expectCloudQr = isCloudEnabled
                                    )
                                },
                                onSuccess = {
                                    endGuestSessionAndReturnTo(Screen.Feed)
                                }
                            )
                        },
                        onDismissPublishModal = { previewViewModel.dismissPublishModal() },
                        onRetakeClick = { previewViewModel.requestDiscard() },
                        onConfirmDiscard = {
                            previewViewModel.confirmDiscard {
                                studioViewModel.resetStudio()
                                currentScreen = Screen.DanceStudio
                            }
                        },
                        onDismissDiscardModal = { previewViewModel.dismissDiscardModal() }
                    )
                }

                Screen.AdminDashboard -> {
                    AdminDashboardScreen(
                        uiState = adminUiState,
                        onTabSelected = { adminViewModel.selectTab(it) },
                        onDurationSelected = { adminViewModel.updateDuration(it) },
                        onToggleGuestDeletion = { adminViewModel.toggleGuestDeletion() },
                        onToggleKioskLock = {
                            adminViewModel.toggleKioskLock()
                            onToggleKioskLock(!adminUiState.settings.isKioskLockEnabled)
                        },
                        onDeleteVideo = { adminViewModel.deleteVideo(it) },
                        onToggleFeatured = { adminViewModel.toggleFeaturedVideo(it) },
                        onClearStorage = { adminViewModel.clearTempStorage() },
                        onOpenHardwareDiagnostics = { currentScreen = Screen.HardwareValidation },
                        onImportAudioFiles = { adminViewModel.importAudioFiles(it) },
                        onDismissImportSummary = { adminViewModel.dismissImportSummary() },
                        onToggleSongLicense = { adminViewModel.toggleSongLicense(it) },
                        onToggleSongPreview = { adminViewModel.toggleSongPreview(it) },
                        onOpenEditSong = { adminViewModel.openEditSongModal(it) },
                        onDismissEditSong = { adminViewModel.dismissEditSongModal() },
                        onSaveSongMetadata = { id, t, a, al, y, g, d ->
                            adminViewModel.saveSongMetadata(id, t, a, al, y, g, d)
                        },
                        onRequestDeleteSong = { adminViewModel.requestDeleteSong(it) },
                        onDismissDeleteSong = { adminViewModel.dismissDeleteSongModal() },
                        onConfirmDeleteSong = { adminViewModel.confirmDeleteSong() },
                        onExitAdmin = {
                            adminViewModel.logout()
                            currentScreen = Screen.Welcome
                        },
                        onVideoSearchQueryChange = { adminViewModel.updateVideoSearchQuery(it) },
                        onOpenVideoPreview = { adminViewModel.openVideoPreview(it) },
                        onDismissVideoPreview = { adminViewModel.dismissVideoPreview() },
                        onRequestDeleteVideo = { adminViewModel.requestDeleteVideo(it) },
                        onDismissDeleteVideo = { adminViewModel.dismissDeleteVideo() },
                        onConfirmDeleteVideo = { adminViewModel.confirmDeleteVideo() },
                        onVideoUriSelectedForImport = { adminViewModel.onVideoUriSelectedForImport(it) },
                        onDismissImportVideoDialog = { adminViewModel.dismissImportVideoDialog() },
                        onConfirmImportVideo = { nick, cap, tags ->
                            adminViewModel.confirmImportVideo(nick, cap, tags)
                        },
                        onDismissVideoImportMessage = { adminViewModel.dismissVideoImportMessage() },
                        onRunReconciliation = { adminViewModel.runStorageReconciliation() },
                        onPurgeOrphanedThumbnails = { adminViewModel.purgeOrphanedThumbnails() },
                        onDismissStorageActionMessage = { adminViewModel.dismissStorageActionMessage() },
                        isDeviceOwner = isDeviceOwner,
                        isStudioBusy = isRecordingOrProcessing,
                        onUpdateEventDetails = { name, desc -> adminViewModel.updateEventDetails(name, desc) },
                        onStartEvent = { name, desc -> adminViewModel.startEvent(name, desc) },
                        onPauseEvent = { adminViewModel.pauseEvent() },
                        onResumeEvent = { adminViewModel.resumeEvent() },
                        onRequestEndEvent = { adminViewModel.requestEndEvent() },
                        onDismissEndEvent = { adminViewModel.dismissEndEvent() },
                        onConfirmEndEvent = {
                            adminViewModel.confirmEndEvent(
                                isStudioBusy = isRecordingOrProcessing,
                                onReadyToReturnToWelcome = {
                                    adminViewModel.logout()
                                    endGuestSessionAndReturnTo(Screen.Welcome)
                                }
                            )
                        },
                        onRequestResetEventStats = { adminViewModel.requestResetEventStats() },
                        onDismissResetEventStats = { adminViewModel.dismissResetEventStats() },
                        onConfirmResetEventStats = { adminViewModel.confirmResetEventStats() },
                        onDismissEventActionMessage = { adminViewModel.dismissEventActionMessage() },
                        onModerationFilterSelected = { adminViewModel.updateModerationFilter(it) },
                        onOpenModerationDialog = { adminViewModel.openModerationDialog(it) },
                        onDismissModerationDialog = { adminViewModel.dismissModerationDialog() },
                        onConfirmModeration = { id, hide, reason ->
                            adminViewModel.moderateVideo(id, hide, reason)
                        },
                        onUpdateFeedControls = { featured, likes, favs, discover, deletion, sort, reqNick ->
                            adminViewModel.updateFeedControls(
                                showFeaturedSection = featured,
                                enableGuestLikes = likes,
                                enableGuestFavorites = favs,
                                enableDiscover = discover,
                                allowGuestDeletion = deletion,
                                defaultFeedSort = sort,
                                requireGuestNickname = reqNick
                            )
                        },
                        onPairCloudDevice = { code, eventIdRaw, devName ->
                            adminViewModel.pairCloudDevice(code, eventIdRaw, devName)
                        },
                        onTestCloudHeartbeat = { adminViewModel.testCloudHeartbeat() },
                        onUnpairCloudDevice = { adminViewModel.unpairCloudDevice() },
                        onSyncPendingUploads = { adminViewModel.syncPendingUploads() },
                        onRetryVideoUpload = { adminViewModel.retryVideoUpload(it) },
                        onDismissCloudActionMessage = { adminViewModel.dismissCloudActionMessage() },
                        onToggleWideView = { adminViewModel.toggleWideView() }
                    )
                }

                Screen.HardwareValidation -> {
                    HardwareValidationScreen(
                        uiState = hardwareUiState,
                        onRunDiagnostics = { hardwareValidationViewModel.runAllDiagnostics() },
                        onBackClick = { currentScreen = Screen.AdminDashboard }
                    )
                }

                Screen.AdminPin, Screen.SongPicker -> {
                    // Handled as overlays
                }
            }

            // Local Video Discovery & Search Overlay
            if (showDiscoverVideosSheet) {
                DiscoverVideosSheet(
                    query = feedUiState.searchQuery,
                    activeSort = feedUiState.searchSort,
                    results = feedUiState.discoveryResults,
                    onQueryChange = { feedViewModel.updateSearchQuery(it) },
                    onSortSelected = { feedViewModel.updateSearchSort(it) },
                    onVideoSelected = { video ->
                        feedViewModel.jumpToVideoInFeed(video.id)
                        showDiscoverVideosSheet = false
                    },
                    onBrowseSoundsClick = {
                        showDiscoverVideosSheet = false
                        showSongPickerSheet = true
                    },
                    onCloseClick = { showDiscoverVideosSheet = false }
                )
            }

            // Song Picker Overlay (Connected to production MusicCatalogRepository)
            if (showSongPickerSheet) {
                SongPickerSheet(
                    songs = adminUiState.songs,
                    selectedSong = studioUiState.selectedSong,
                    audioPreviewManager = AppContainer.audioPreviewManager,
                    storageManager = AppContainer.musicStorageManager,
                    onSongSelected = { song ->
                        showSongPickerSheet = false
                        if (currentScreen == Screen.Feed) {
                            requestEnterDanceStudio(preselectedSong = song)
                        } else {
                            studioViewModel.selectSong(song)
                        }
                    },
                    onCloseClick = { showSongPickerSheet = false }
                )
            }

            // Event Paused / Ended Notice Modal if guest taps record button while Event Mode restricts recordings
            if (showEventPausedNotice) {
                AlertDialog(
                    onDismissRequest = { showEventPausedNotice = false },
                    containerColor = Color(0xFF161616),
                    title = {
                        Text(
                            text = "Guest Recording Unavailable",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    },
                    text = {
                        Text(
                            text = "New guest recording sessions are currently paused or ended by the event organizer. You can continue watching videos in the Event Feed.",
                            color = Color(0xFFB3B3B3),
                            fontSize = 14.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = { showEventPausedNotice = false },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFE2C55))
                        ) {
                            Text("OK", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                )
            }

            // Admin PIN Keypad Modal
            if (showAdminPinDialog) {
                AdminPinDialog(
                    enteredPin = adminUiState.enteredPin,
                    pinError = adminUiState.pinError,
                    isLockedOut = adminUiState.isLockedOut,
                    lockoutSeconds = adminUiState.lockoutSecondsRemaining,
                    onDigitClick = { adminViewModel.onDigitEntered(it) },
                    onBackspace = { adminViewModel.onBackspace() },
                    onClear = { adminViewModel.onClearPin() },
                    onDismiss = {
                        adminViewModel.onClearPin()
                        showAdminPinDialog = false
                    }
                )
            }

            // Kiosk Inactivity Warning Dialog with 15-second countdown
            if (showInactivityWarning) {
                InactivityWarningDialog(
                    countdownSeconds = inactivityCountdown,
                    onContinueClick = {
                        lastInteractionTime = System.currentTimeMillis()
                        showInactivityWarning = false
                    }
                )
            }

            // Explicit Recovery Prompt for unfinished temporary recordings
            recoverableSession?.let { session ->
                RecoverableRecordingDialog(
                    session = session,
                    onRecoverClick = {
                        val meta = session.metadata
                        val matchedSong = adminUiState.songs.firstOrNull { it.id == meta?.songId }
                            ?: adminUiState.songs.firstOrNull()
                            ?: SongItem(
                                id = meta?.songId?.ifBlank { "recovered-track" } ?: "recovered-track",
                                title = meta?.songTitle?.ifBlank { session.displayTitle } ?: session.displayTitle,
                                artist = meta?.songArtist?.ifBlank { "Event Soundtrack" } ?: "Event Soundtrack",
                                album = "Recovered Event Session",
                                releaseYear = null,
                                decade = RetroDecade.DECADE_UNKNOWN,
                                genre = "Retro",
                                durationSeconds = (meta?.durationSeconds ?: 30).coerceAtLeast(1),
                                audioRelativePath = ""
                            )
                        previewViewModel.initialize(
                            videoFilePath = session.file.absolutePath,
                            song = matchedSong,
                            effect = session.effectType,
                            durationSeconds = meta?.durationSeconds ?: 30
                        )
                        currentScreen = Screen.VideoPreview
                        recoverableSession = null
                    },
                    onDiscardClick = {
                        coroutineScope.launch {
                            AppContainer.videoStorageManager.discardRecoverableSession(session)
                        }
                        recoverableSession = null
                    }
                )
            }
        }
    }
}

@Composable
fun RecoverableRecordingDialog(
    session: RecoverableRecording,
    onRecoverClick: () -> Unit,
    onDiscardClick: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = Color(0xFF161616),
        title = {
            Text(
                text = "Unfinished Recording Found",
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column {
                Text(
                    text = "An unpublished take (${session.displayTitle}) was preserved in temporary storage.",
                    color = Color(0xFFB3B3B3),
                    fontSize = 14.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onRecoverClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFE2C55))
            ) {
                Text("Recover & Preview", fontWeight = FontWeight.Bold, color = Color.White)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDiscardClick,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Discard", color = Color(0xFFB3B3B3))
            }
        }
    )
}

@Composable
fun InactivityWarningDialog(
    countdownSeconds: Int,
    onContinueClick: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onContinueClick,
        containerColor = Color(0xFF161616),
        title = {
            Text(
                text = "Are you still dancing?",
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Touch anywhere to continue, or this screen will reset to the main feed in:",
                    color = Color(0xFFB3B3B3),
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "${countdownSeconds}s",
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFFE2C55)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onContinueClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFE2C55))
            ) {
                Text("Keep Dancing", fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    )
}
