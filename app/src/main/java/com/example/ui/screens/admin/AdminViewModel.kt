package com.example.ui.screens.admin

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioPreviewManager
import com.example.cloud.CloudUploadManager
import com.example.cloud.PairingActionResult
import com.example.data.local.storage.MusicStorageManager
import com.example.data.repository.ImportSummary
import com.example.data.repository.ImportVideoResult
import com.example.data.repository.KioskSettingsRepository
import com.example.data.repository.ModerationFilter
import com.example.data.repository.MusicCatalogRepository
import com.example.data.repository.MusicStorageMetrics
import com.example.data.repository.ReconciliationReport
import com.example.data.repository.VideoFeedRepository
import com.example.model.CloudPairingState
import com.example.model.CloudQueueCounts
import com.example.model.EventModeStatus
import com.example.model.EventSummarySnapshot
import com.example.model.FeedSortOrder
import com.example.model.KioskSettings
import com.example.model.RetroDecade
import com.example.model.SongItem
import com.example.model.VideoItem
import com.example.video.VideoStorageBreakdown
import com.example.video.VideoStorageManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AdminTab {
    DASHBOARD,
    MUSIC_MANAGER,
    VIDEO_MANAGER,
    KIOSK_SETTINGS,
    STORAGE_HEALTH
}

data class EventMonitoringMetrics(
    val publishedCount: Int = 0,
    val visibleCount: Int = 0,
    val hiddenCount: Int = 0,
    val featuredCount: Int = 0,
    val totalRecordingCount: Int = 0,
    val totalFavoritesCount: Int = 0,
    val totalLikesCount: Int = 0,
    val totalDurationSeconds: Int = 0,
    val totalEventStorageBytes: Long = 0L,
    val availableDeviceBytes: Long = 0L,
    val recoverableCount: Int = 0,
    val recentPublications: List<VideoItem> = emptyList()
)

data class AdminUiState(
    val enteredPin: String = "",
    val pinError: String? = null,
    val failedAttempts: Int = 0,
    val isLockedOut: Boolean = false,
    val lockoutSecondsRemaining: Int = 0,
    val isAuthenticated: Boolean = false,
    val activeTab: AdminTab = AdminTab.DASHBOARD,
    val settings: KioskSettings = KioskSettings(),
    val videos: List<VideoItem> = emptyList(),
    val filteredVideos: List<VideoItem> = emptyList(),
    val videoSearchQuery: String = "",
    val selectedModerationFilter: ModerationFilter = ModerationFilter.ALL,
    val moderationTargetVideo: VideoItem? = null,
    val previewingVideo: VideoItem? = null,
    val videoToDelete: VideoItem? = null,
    val pendingImportVideoUri: Uri? = null,
    val isImportingVideo: Boolean = false,
    val videoImportMessage: String? = null,
    val songs: List<SongItem> = emptyList(),
    val musicMetrics: MusicStorageMetrics = MusicStorageMetrics(0, 0, 0L, emptyMap(), emptyMap()),
    val storageBreakdown: VideoStorageBreakdown = VideoStorageBreakdown(),
    val eventMetrics: EventMonitoringMetrics = EventMonitoringMetrics(),
    val showEndEventConfirmation: Boolean = false,
    val showResetStatsConfirmation: Boolean = false,
    val pendingEndEventAfterActiveSession: Boolean = false,
    val isPerformingCriticalAction: Boolean = false,
    val eventActionMessage: String? = null,
    val reconciliationReport: ReconciliationReport? = null,
    val lastStorageActionMessage: String? = null,
    val isImportingAudio: Boolean = false,
    val importProgressText: String = "",
    val lastImportSummary: ImportSummary? = null,
    val previewingSongId: String? = null,
    val isPreviewPlaying: Boolean = false,
    val editingSong: SongItem? = null,
    val songToDelete: SongItem? = null,
    val isSongToDeleteReferenced: Boolean = false,
    val cloudPairingState: CloudPairingState = CloudPairingState(),
    val cloudQueueCounts: CloudQueueCounts = CloudQueueCounts(),
    val isCloudOnline: Boolean = false,
    val isCloudSyncing: Boolean = false,
    val isCloudActionBusy: Boolean = false,
    val cloudActionMessage: String? = null
)

class AdminViewModel(
    private val settingsRepository: KioskSettingsRepository,
    private val videoFeedRepository: VideoFeedRepository,
    private val musicCatalogRepository: MusicCatalogRepository,
    private val audioPreviewManager: AudioPreviewManager,
    private val storageManager: MusicStorageManager,
    private val videoStorageManager: VideoStorageManager? = null,
    private val cloudUploadManager: CloudUploadManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminUiState())
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    private var lockoutJob: Job? = null

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            settingsRepository.getSettings().collect { s ->
                _uiState.update { state ->
                    val next = state.copy(settings = s)
                    next.copy(eventMetrics = buildEventMetrics(next.videos, next.storageBreakdown, s))
                }
            }
        }
        viewModelScope.launch {
            videoFeedRepository.getAllAdminVideos().collect { v ->
                _uiState.update { state ->
                    val filtered = filterVideos(v, state.videoSearchQuery, state.selectedModerationFilter)
                    state.copy(
                        videos = v,
                        filteredVideos = filtered,
                        eventMetrics = buildEventMetrics(v, state.storageBreakdown, state.settings)
                    )
                }
                refreshStorageBreakdown()
            }
        }
        viewModelScope.launch {
            musicCatalogRepository.getAllSongs().collect { s ->
                _uiState.update { it.copy(songs = s) }
            }
        }
        viewModelScope.launch {
            musicCatalogRepository.getSongMetrics().collect { m ->
                _uiState.update { it.copy(musicMetrics = m) }
                refreshStorageBreakdown(musicBytesOverride = m.totalFileSizeBytes)
            }
        }
        viewModelScope.launch {
            audioPreviewManager.playbackState.collect { playback ->
                _uiState.update {
                    it.copy(
                        previewingSongId = playback.activeSongId,
                        isPreviewPlaying = playback.isPlaying
                    )
                }
            }
        }
        cloudUploadManager?.let { cloud ->
            viewModelScope.launch {
                cloud.pairingState.collect { pairing ->
                    _uiState.update { it.copy(cloudPairingState = pairing) }
                }
            }
            viewModelScope.launch {
                cloud.queueCounts.collect { counts ->
                    _uiState.update { it.copy(cloudQueueCounts = counts) }
                }
            }
            viewModelScope.launch {
                cloud.isOnline.collect { online ->
                    _uiState.update { it.copy(isCloudOnline = online) }
                }
            }
            viewModelScope.launch {
                cloud.isSyncing.collect { syncing ->
                    _uiState.update { it.copy(isCloudSyncing = syncing) }
                }
            }
        }
    }

    private fun buildEventMetrics(
        videos: List<VideoItem>,
        breakdown: VideoStorageBreakdown,
        settings: KioskSettings
    ): EventMonitoringMetrics {
        val resetAt = settings.eventStatsResetAtMs
        val relevantVideos = if (resetAt > 0L) {
            videos.filter { it.createdAt >= resetAt }
        } else {
            videos
        }
        val visible = relevantVideos.count { !it.isHidden }
        val hidden = relevantVideos.count { it.isHidden }
        val featured = relevantVideos.count { it.isFeatured }
        val likes = relevantVideos.sumOf { it.likeCount }
        val favorites = relevantVideos.sumOf { it.favoriteCount }
        val durationSec = relevantVideos.sumOf { it.durationSeconds }
        val eventStorage = if (breakdown.totalAppBytes > 0L) {
            breakdown.publishedBytes + breakdown.importedBytes + breakdown.thumbnailBytes + breakdown.recoverableBytes
        } else {
            relevantVideos.sumOf { it.fileSizeBytes }
        }
        return EventMonitoringMetrics(
            publishedCount = relevantVideos.size,
            visibleCount = visible,
            hiddenCount = hidden,
            featuredCount = featured,
            totalRecordingCount = relevantVideos.size + breakdown.recoverableCount,
            totalFavoritesCount = favorites,
            totalLikesCount = likes,
            totalDurationSeconds = durationSec,
            totalEventStorageBytes = eventStorage,
            availableDeviceBytes = breakdown.availableDeviceBytes,
            recoverableCount = breakdown.recoverableCount,
            recentPublications = relevantVideos.sortedByDescending { it.createdAt }.take(5)
        )
    }

    private fun filterVideos(
        videos: List<VideoItem>,
        query: String,
        filter: ModerationFilter = ModerationFilter.ALL
    ): List<VideoItem> {
        val q = query.trim().lowercase()
        return videos.filter { video ->
            val matchesFilter = when (filter) {
                ModerationFilter.ALL -> true
                ModerationFilter.VISIBLE -> !video.isHidden
                ModerationFilter.HIDDEN -> video.isHidden
                ModerationFilter.FEATURED -> video.isFeatured
                ModerationFilter.MISSING_FILE -> !video.isFileHealthy
            }
            val matchesQuery = q.isBlank() ||
                video.creatorNickname.lowercase().contains(q) ||
                video.caption.lowercase().contains(q) ||
                video.hashtags.lowercase().contains(q) ||
                video.song.title.lowercase().contains(q) ||
                video.song.artist.lowercase().contains(q)
            matchesFilter && matchesQuery
        }
    }

    fun onDigitEntered(digit: String) {
        if (_uiState.value.isLockedOut) return
        if (_uiState.value.enteredPin.length < 4) {
            val updated = _uiState.value.enteredPin + digit
            _uiState.update { it.copy(enteredPin = updated, pinError = null) }
            if (updated.length == 4) {
                verifyPin(updated)
            }
        }
    }

    fun onBackspace() {
        if (_uiState.value.enteredPin.isNotEmpty()) {
            _uiState.update { it.copy(enteredPin = it.enteredPin.dropLast(1), pinError = null) }
        }
    }

    fun onClearPin() {
        _uiState.update { it.copy(enteredPin = "", pinError = null) }
    }

    private fun verifyPin(pin: String) {
        viewModelScope.launch {
            val isValid = settingsRepository.validateAdminPin(pin)
            if (isValid) {
                _uiState.update {
                    it.copy(
                        isAuthenticated = true,
                        enteredPin = "",
                        pinError = null,
                        failedAttempts = 0
                    )
                }
                refreshStorageAndReconcile()
            } else {
                val newFailed = _uiState.value.failedAttempts + 1
                if (newFailed >= 5) {
                    triggerLockout(60)
                } else {
                    _uiState.update {
                        it.copy(
                            enteredPin = "",
                            failedAttempts = newFailed,
                            pinError = "Incorrect PIN (${5 - newFailed} attempts left)"
                        )
                    }
                }
            }
        }
    }

    private fun triggerLockout(seconds: Int) {
        lockoutJob?.cancel()
        lockoutJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLockedOut = true,
                    lockoutSecondsRemaining = seconds,
                    enteredPin = "",
                    pinError = "Too many failed attempts. Locked out for $seconds s."
                )
            }
            for (sec in seconds downTo 1) {
                delay(1000)
                _uiState.update { it.copy(lockoutSecondsRemaining = sec - 1) }
            }
            _uiState.update {
                it.copy(
                    isLockedOut = false,
                    failedAttempts = 0,
                    pinError = null
                )
            }
        }
    }

    fun selectTab(tab: AdminTab) {
        _uiState.update { it.copy(activeTab = tab) }
        if (tab == AdminTab.STORAGE_HEALTH || tab == AdminTab.VIDEO_MANAGER || tab == AdminTab.DASHBOARD) {
            refreshStorageBreakdown()
        }
    }

    fun updateVideoSearchQuery(query: String) {
        _uiState.update { state ->
            state.copy(
                videoSearchQuery = query,
                filteredVideos = filterVideos(state.videos, query, state.selectedModerationFilter)
            )
        }
    }

    fun updateModerationFilter(filter: ModerationFilter) {
        _uiState.update { state ->
            state.copy(
                selectedModerationFilter = filter,
                filteredVideos = filterVideos(state.videos, state.videoSearchQuery, filter)
            )
        }
    }

    fun openModerationDialog(video: VideoItem) {
        _uiState.update { it.copy(moderationTargetVideo = video) }
    }

    fun dismissModerationDialog() {
        _uiState.update { it.copy(moderationTargetVideo = null) }
    }

    fun moderateVideo(videoId: String, hide: Boolean, reason: String?) {
        if (_uiState.value.isPerformingCriticalAction) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingCriticalAction = true) }
            try {
                videoFeedRepository.moderateVideo(videoId, hide, reason)
                _uiState.update {
                    it.copy(
                        moderationTargetVideo = null,
                        eventActionMessage = if (hide) "Video hidden from guest feed (files preserved)." else "Video restored to guest feed."
                    )
                }
            } finally {
                _uiState.update { it.copy(isPerformingCriticalAction = false) }
            }
        }
    }

    fun openVideoPreview(video: VideoItem) {
        _uiState.update { it.copy(previewingVideo = video) }
    }

    fun dismissVideoPreview() {
        _uiState.update { it.copy(previewingVideo = null) }
    }

    fun requestDeleteVideo(video: VideoItem) {
        _uiState.update { it.copy(videoToDelete = video) }
    }

    fun dismissDeleteVideo() {
        _uiState.update { it.copy(videoToDelete = null) }
    }

    fun confirmDeleteVideo() {
        val video = _uiState.value.videoToDelete ?: return
        if (_uiState.value.isPerformingCriticalAction) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingCriticalAction = true) }
            try {
                videoFeedRepository.deleteVideo(video.id)
                _uiState.update { it.copy(videoToDelete = null) }
                refreshStorageBreakdown()
            } finally {
                _uiState.update { it.copy(isPerformingCriticalAction = false) }
            }
        }
    }

    fun onVideoUriSelectedForImport(uri: Uri) {
        _uiState.update { it.copy(pendingImportVideoUri = uri, videoImportMessage = null) }
    }

    fun dismissImportVideoDialog() {
        _uiState.update { it.copy(pendingImportVideoUri = null) }
    }

    fun confirmImportVideo(nickname: String, caption: String, hashtags: String) {
        val uri = _uiState.value.pendingImportVideoUri ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    pendingImportVideoUri = null,
                    isImportingVideo = true,
                    videoImportMessage = null
                )
            }
            when (val result = videoFeedRepository.importLocalVideo(uri, nickname, caption, hashtags)) {
                is ImportVideoResult.Success -> {
                    cloudUploadManager?.onVideoPublishedAsync(result.importedVideo.id)
                    _uiState.update {
                        it.copy(
                            isImportingVideo = false,
                            videoImportMessage = "Imported @${result.importedVideo.creatorNickname} (${result.importedVideo.durationSeconds}s) into local feed."
                        )
                    }
                    refreshStorageBreakdown()
                }
                is ImportVideoResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isImportingVideo = false,
                            videoImportMessage = "Import failed: ${result.message}"
                        )
                    }
                }
            }
        }
    }

    fun dismissVideoImportMessage() {
        _uiState.update { it.copy(videoImportMessage = null) }
    }

    fun refreshStorageBreakdown(musicBytesOverride: Long? = null) {
        val vsm = videoStorageManager ?: return
        val musicBytes = musicBytesOverride ?: _uiState.value.musicMetrics.totalFileSizeBytes
        viewModelScope.launch {
            val breakdown = vsm.computeStorageBreakdown(musicBytes)
            _uiState.update { state ->
                state.copy(
                    storageBreakdown = breakdown,
                    eventMetrics = buildEventMetrics(state.videos, breakdown, state.settings)
                )
            }
        }
    }

    fun refreshStorageAndReconcile() {
        viewModelScope.launch {
            val report = videoFeedRepository.reconcileStorageAndDatabase()
            _uiState.update { it.copy(reconciliationReport = report) }
            refreshStorageBreakdown()
        }
    }

    fun runStorageReconciliation() {
        viewModelScope.launch {
            val report = videoFeedRepository.reconcileStorageAndDatabase()
            _uiState.update {
                it.copy(
                    reconciliationReport = report,
                    lastStorageActionMessage = "Reconciliation complete: ${report.recoveredOrphanFiles} recovered, ${report.flaggedMissingRecords} missing flagged, ${report.regeneratedThumbnails} thumbnails rebuilt."
                )
            }
            refreshStorageBreakdown()
        }
    }

    fun purgeOrphanedThumbnails() {
        viewModelScope.launch {
            val removed = videoFeedRepository.purgeOrphanedThumbnails()
            _uiState.update {
                it.copy(
                    lastStorageActionMessage = "Removed $removed orphaned thumbnail file(s). Published videos preserved."
                )
            }
            refreshStorageAndReconcile()
        }
    }

    fun dismissStorageActionMessage() {
        _uiState.update { it.copy(lastStorageActionMessage = null) }
    }

    fun importAudioFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImportingAudio = true,
                    importProgressText = "Preparing ${uris.size} audio file(s)..."
                )
            }

            val summary = musicCatalogRepository.importAudioFiles(uris) { current, total, name ->
                _uiState.update {
                    it.copy(
                        importProgressText = "Importing ($current/$total): $name"
                    )
                }
            }

            _uiState.update {
                it.copy(
                    isImportingAudio = false,
                    importProgressText = "",
                    lastImportSummary = summary
                )
            }
            refreshStorageBreakdown()
        }
    }

    fun dismissImportSummary() {
        _uiState.update { it.copy(lastImportSummary = null) }
    }

    fun toggleSongLicense(song: SongItem) {
        viewModelScope.launch {
            val newStatus = when (song.licenseApprovalStatus) {
                "UNREVIEWED" -> "APPROVED_FOR_EVENT_USE"
                "APPROVED_FOR_EVENT_USE" -> "NOT_APPROVED"
                else -> "APPROVED_FOR_EVENT_USE"
            }
            musicCatalogRepository.updateLicenseStatus(song.id, newStatus)
        }
    }

    fun toggleSongPreview(song: SongItem) {
        if (song.audioRelativePath.isBlank()) return
        val audioFile = storageManager.getAbsoluteFile(song.audioRelativePath)
        audioPreviewManager.togglePlaySong(song, audioFile)
    }

    fun openEditSongModal(song: SongItem) {
        _uiState.update { it.copy(editingSong = song) }
    }

    fun dismissEditSongModal() {
        _uiState.update { it.copy(editingSong = null) }
    }

    fun saveSongMetadata(
        songId: String,
        title: String,
        artist: String,
        album: String,
        year: Int?,
        genre: String,
        decade: RetroDecade
    ) {
        viewModelScope.launch {
            musicCatalogRepository.updateSongMetadata(
                songId = songId,
                title = title,
                artist = artist,
                album = album,
                year = year,
                genre = genre,
                decade = decade
            )
            _uiState.update { it.copy(editingSong = null) }
        }
    }

    fun requestDeleteSong(song: SongItem) {
        val isReferenced = _uiState.value.videos.any { it.song.id == song.id }
        _uiState.update { it.copy(songToDelete = song, isSongToDeleteReferenced = isReferenced) }
    }

    fun dismissDeleteSongModal() {
        _uiState.update { it.copy(songToDelete = null, isSongToDeleteReferenced = false) }
    }

    fun confirmDeleteSong() {
        val song = _uiState.value.songToDelete ?: return
        val isReferenced = _uiState.value.isSongToDeleteReferenced
        viewModelScope.launch {
            if (_uiState.value.previewingSongId == song.id) {
                audioPreviewManager.stop()
            }
            musicCatalogRepository.deleteSong(song.id, isReferencedByVideos = isReferenced)
            _uiState.update { it.copy(songToDelete = null, isSongToDeleteReferenced = false) }
            refreshStorageBreakdown()
        }
    }

    fun updateEventDetails(name: String, description: String) {
        viewModelScope.launch {
            settingsRepository.updateEventDetails(name, description)
            _uiState.update { it.copy(eventActionMessage = "Event details saved locally.") }
        }
    }

    fun startEvent(name: String = _uiState.value.settings.eventName, description: String = _uiState.value.settings.eventDescription) {
        if (_uiState.value.isPerformingCriticalAction) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingCriticalAction = true) }
            try {
                settingsRepository.startEvent(name, description)
                _uiState.update {
                    it.copy(
                        pendingEndEventAfterActiveSession = false,
                        eventActionMessage = "Event started! Guest recording is now active."
                    )
                }
            } finally {
                _uiState.update { it.copy(isPerformingCriticalAction = false) }
            }
        }
    }

    fun pauseEvent() {
        if (_uiState.value.isPerformingCriticalAction) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingCriticalAction = true) }
            try {
                settingsRepository.pauseEvent()
                _uiState.update { it.copy(eventActionMessage = "Guest access paused. Existing videos remain preserved.") }
            } finally {
                _uiState.update { it.copy(isPerformingCriticalAction = false) }
            }
        }
    }

    fun resumeEvent() {
        if (_uiState.value.isPerformingCriticalAction) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingCriticalAction = true) }
            try {
                settingsRepository.resumeEvent()
                _uiState.update { it.copy(eventActionMessage = "Guest access resumed.") }
            } finally {
                _uiState.update { it.copy(isPerformingCriticalAction = false) }
            }
        }
    }

    fun requestEndEvent() {
        _uiState.update { it.copy(showEndEventConfirmation = true) }
    }

    fun dismissEndEvent() {
        _uiState.update { it.copy(showEndEventConfirmation = false) }
    }

    fun confirmEndEvent(isStudioBusy: Boolean = false, onReadyToReturnToWelcome: () -> Unit = {}) {
        if (_uiState.value.isPerformingCriticalAction) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingCriticalAction = true, showEndEventConfirmation = false) }
            try {
                val state = _uiState.value
                val metrics = state.eventMetrics
                val summary = EventSummarySnapshot(
                    eventName = state.settings.eventName,
                    eventDescription = state.settings.eventDescription,
                    startedAtMs = state.settings.eventStartedAtMs,
                    endedAtMs = System.currentTimeMillis(),
                    publishedVideoCount = metrics.publishedCount,
                    totalRecordingCount = metrics.totalRecordingCount,
                    totalLikesCount = metrics.totalLikesCount,
                    totalFavoritesCount = metrics.totalFavoritesCount,
                    totalStorageBytes = metrics.totalEventStorageBytes
                )
                settingsRepository.endEvent(summary)
                if (isStudioBusy) {
                    _uiState.update {
                        it.copy(
                            pendingEndEventAfterActiveSession = true,
                            eventActionMessage = "New guest sessions stopped. Waiting for active recording/processing to finish safely."
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            pendingEndEventAfterActiveSession = false,
                            eventActionMessage = "Event ended and summary saved locally. All videos preserved."
                        )
                    }
                    onReadyToReturnToWelcome()
                }
            } finally {
                _uiState.update { it.copy(isPerformingCriticalAction = false) }
            }
        }
    }

    fun clearPendingEndEvent() {
        _uiState.update { it.copy(pendingEndEventAfterActiveSession = false) }
    }

    fun requestResetEventStats() {
        _uiState.update { it.copy(showResetStatsConfirmation = true) }
    }

    fun dismissResetEventStats() {
        _uiState.update { it.copy(showResetStatsConfirmation = false) }
    }

    fun confirmResetEventStats() {
        if (_uiState.value.isPerformingCriticalAction) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingCriticalAction = true, showResetStatsConfirmation = false) }
            try {
                settingsRepository.resetEventStatistics()
                _uiState.update {
                    it.copy(eventActionMessage = "Event statistics summary reset. Published MP4s and Room videos preserved.")
                }
            } finally {
                _uiState.update { it.copy(isPerformingCriticalAction = false) }
            }
        }
    }

    fun dismissEventActionMessage() {
        _uiState.update { it.copy(eventActionMessage = null) }
    }

    fun updateFeedControls(
        showFeaturedSection: Boolean = _uiState.value.settings.showFeaturedSection,
        enableGuestLikes: Boolean = _uiState.value.settings.enableGuestLikes,
        enableGuestFavorites: Boolean = _uiState.value.settings.enableGuestFavorites,
        enableDiscover: Boolean = _uiState.value.settings.enableDiscover,
        allowGuestDeletion: Boolean = _uiState.value.settings.allowGuestDeletion,
        defaultFeedSort: FeedSortOrder = _uiState.value.settings.defaultFeedSort,
        requireGuestNickname: Boolean = _uiState.value.settings.requireGuestNickname
    ) {
        viewModelScope.launch {
            settingsRepository.updateFeedControls(
                showFeaturedSection = showFeaturedSection,
                enableGuestLikes = enableGuestLikes,
                enableGuestFavorites = enableGuestFavorites,
                enableDiscover = enableDiscover,
                allowGuestDeletion = allowGuestDeletion,
                defaultFeedSort = defaultFeedSort,
                requireGuestNickname = requireGuestNickname
            )
        }
    }

    fun updateDuration(durationSeconds: Int) {
        viewModelScope.launch {
            settingsRepository.updateRecordingDuration(durationSeconds)
        }
    }

    fun toggleGuestDeletion() {
        viewModelScope.launch {
            settingsRepository.toggleGuestDeletion(!_uiState.value.settings.allowGuestDeletion)
        }
    }

    fun toggleKioskLock() {
        viewModelScope.launch {
            settingsRepository.toggleKioskLock(!_uiState.value.settings.isKioskLockEnabled)
        }
    }

    fun toggleWideView() {
        viewModelScope.launch {
            settingsRepository.toggleWideView(!_uiState.value.settings.isWideViewEnabled)
        }
    }

    fun deleteVideo(videoId: String) {
        viewModelScope.launch {
            videoFeedRepository.deleteVideo(videoId)
            refreshStorageBreakdown()
        }
    }

    fun toggleFeaturedVideo(videoId: String) {
        viewModelScope.launch {
            videoFeedRepository.toggleFeatured(videoId)
        }
    }

    fun clearTempStorage() {
        if (_uiState.value.isPerformingCriticalAction) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingCriticalAction = true) }
            try {
                settingsRepository.clearTempCache()
                storageManager.clearTempStaging()
                val purgedCount = videoStorageManager?.clearTransientStagingOnly() ?: 0
                _uiState.update {
                    it.copy(
                        lastStorageActionMessage = "Cleared $purgedCount transient staging file(s). Recoverable takes and published videos preserved."
                    )
                }
                refreshStorageBreakdown()
            } finally {
                _uiState.update { it.copy(isPerformingCriticalAction = false) }
            }
        }
    }

    fun pairCloudDevice(pairingCode: String, eventIdRaw: String, deviceName: String) {
        val cloud = cloudUploadManager ?: return
        val eventId = eventIdRaw.trim().toLongOrNull() ?: 0L
        if (eventId <= 0L) {
            _uiState.update { it.copy(cloudActionMessage = "Please enter a valid numeric Event ID.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isCloudActionBusy = true, cloudActionMessage = null) }
            when (val res = cloud.pairAndVerifyEvent(pairingCode, eventId, deviceName)) {
                is PairingActionResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isCloudActionBusy = false,
                            cloudActionMessage = "Paired with Event #${res.pairingState.pairedEventId}: ${res.pairingState.pairedEventName ?: "Verified"}"
                        )
                    }
                }
                is PairingActionResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isCloudActionBusy = false,
                            cloudActionMessage = res.message
                        )
                    }
                }
            }
        }
    }

    fun testCloudHeartbeat() {
        val cloud = cloudUploadManager ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isCloudActionBusy = true, cloudActionMessage = null) }
            when (val res = cloud.sendHeartbeatAndRefreshEvent()) {
                is PairingActionResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isCloudActionBusy = false,
                            cloudActionMessage = "Heartbeat verified • Event status: ${res.pairingState.pairedEventStatus ?: "OK"}"
                        )
                    }
                }
                is PairingActionResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isCloudActionBusy = false,
                            cloudActionMessage = res.message
                        )
                    }
                }
            }
        }
    }

    fun unpairCloudDevice() {
        cloudUploadManager?.unpairDevice()
        _uiState.update {
            it.copy(cloudActionMessage = "Device unpaired cleanly. All local videos remain intact.")
        }
    }

    fun syncPendingUploads() {
        val cloud = cloudUploadManager ?: return
        cloud.triggerSyncAllAsync()
        _uiState.update {
            it.copy(cloudActionMessage = "Cloud upload sync started for eligible pending videos.")
        }
    }

    fun retryVideoUpload(videoId: String) {
        cloudUploadManager?.retrySingleVideoAsync(videoId)
    }

    fun dismissCloudActionMessage() {
        _uiState.update { it.copy(cloudActionMessage = null) }
    }

    fun logout() {
        audioPreviewManager.stop()
        _uiState.update {
            it.copy(
                isAuthenticated = false,
                enteredPin = "",
                activeTab = AdminTab.DASHBOARD,
                previewingVideo = null,
                videoToDelete = null,
                moderationTargetVideo = null,
                pendingImportVideoUri = null,
                showEndEventConfirmation = false,
                showResetStatsConfirmation = false,
                cloudActionMessage = null
            )
        }
    }
}
