package com.example.ui.screens.feed

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloud.QrCodeGenerator
import com.example.data.local.storage.MusicStorageManager
import com.example.data.repository.KioskSettingsRepository
import com.example.data.repository.MusicCatalogRepository
import com.example.data.repository.VideoFeedRepository
import com.example.data.repository.VideoSearchSort
import com.example.model.CloudUploadStatus
import com.example.model.EventModeStatus
import com.example.model.FeedSortOrder
import com.example.model.SongItem
import com.example.model.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FloatingHeart(
    val id: Long,
    val offset: Offset
)

enum class FeedFilterTab {
    FOR_YOU,
    FEATURED,
    FAVORITES
}

data class FeedUiState(
    val videos: List<VideoItem> = emptyList(),
    val allVideos: List<VideoItem> = emptyList(),
    val activeTab: FeedFilterTab = FeedFilterTab.FOR_YOU,
    val currentIndex: Int = 0,
    val isPlaying: Boolean = true,
    val isLoading: Boolean = false,
    val floatingHearts: List<FloatingHeart> = emptyList(),
    val isMuted: Boolean = false,
    val searchQuery: String = "",
    val searchSort: VideoSearchSort = VideoSearchSort.NEWEST,
    val discoveryResults: List<VideoItem> = emptyList(),
    val showFeaturedSection: Boolean = true,
    val enableGuestLikes: Boolean = true,
    val enableGuestFavorites: Boolean = true,
    val enableDiscover: Boolean = true,
    val defaultFeedSort: FeedSortOrder = FeedSortOrder.NEWEST,
    val eventName: String = "Acme 40th Anniversary Gala",
    val eventModeStatus: EventModeStatus = EventModeStatus.ACTIVE,
    val isAcceptingGuestRecordings: Boolean = true,
    val qrDialogVideoId: String? = null,
    val lastPublishedVideoId: String? = null,
    val pendingAutoQrVideoId: String? = null,
    val isResolvingFeedSong: Boolean = false,
    val songActionMessage: String? = null
) {
    val activeQrVideo: VideoItem?
        get() = qrDialogVideoId?.let { targetId ->
            videos.firstOrNull { it.id == targetId } ?: allVideos.firstOrNull { it.id == targetId }
        }
}

private sealed class FeedSongResolutionResult {
    data class Ready(val song: SongItem) : FeedSongResolutionResult()
    data class Unavailable(val reason: String) : FeedSongResolutionResult()
}

class FeedViewModel(
    private val repository: VideoFeedRepository,
    private val settingsRepository: KioskSettingsRepository? = null,
    private val musicCatalogRepository: MusicCatalogRepository? = null,
    private val musicStorageManager: MusicStorageManager? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState(isLoading = true))
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var useSongJob: Job? = null
    private var rawFeedVideos: List<VideoItem> = emptyList()
    private var pendingFocusVideoId: String? = null

    init {
        observeSettings()
        reconcileAndLoad()
    }

    private fun observeSettings() {
        val repo = settingsRepository ?: return
        viewModelScope.launch {
            repo.getSettings().collect { settings ->
                _uiState.update { state ->
                    val nextTab = when {
                        state.activeTab == FeedFilterTab.FEATURED && !settings.showFeaturedSection -> FeedFilterTab.FOR_YOU
                        state.activeTab == FeedFilterTab.FAVORITES && !settings.enableGuestFavorites -> FeedFilterTab.FOR_YOU
                        else -> state.activeTab
                    }
                    val sortedAll = applySort(rawFeedVideos, settings.defaultFeedSort)
                    val filtered = applyFilter(sortedAll, nextTab)
                    state.copy(
                        allVideos = sortedAll,
                        videos = filtered,
                        activeTab = nextTab,
                        currentIndex = state.currentIndex.coerceIn(0, (filtered.size - 1).coerceAtLeast(0)),
                        showFeaturedSection = settings.showFeaturedSection,
                        enableGuestLikes = settings.enableGuestLikes,
                        enableGuestFavorites = settings.enableGuestFavorites,
                        enableDiscover = settings.enableDiscover,
                        defaultFeedSort = settings.defaultFeedSort,
                        eventName = settings.eventName,
                        eventModeStatus = settings.eventModeStatus,
                        isAcceptingGuestRecordings = settings.isAcceptingGuestRecordings
                    )
                }
            }
        }
    }

    private fun reconcileAndLoad() {
        viewModelScope.launch {
            try {
                repository.reconcileStorageAndDatabase()
            } catch (_: Exception) {
            }
        }
        viewModelScope.launch {
            repository.getFeedVideos().collect { videoList ->
                rawFeedVideos = videoList
                _uiState.update { state ->
                    val sortedAll = applySort(videoList, state.defaultFeedSort)
                    val focusId = pendingFocusVideoId
                    val resolvedTab = if (focusId != null && sortedAll.any { it.id == focusId }) {
                        FeedFilterTab.FOR_YOU
                    } else {
                        state.activeTab
                    }
                    val filtered = applyFilter(sortedAll, resolvedTab)
                    val focusedIndex = if (focusId != null) {
                        val found = filtered.indexOfFirst { it.id == focusId }
                        if (found >= 0) {
                            pendingFocusVideoId = null
                            found
                        } else {
                            state.currentIndex.coerceIn(0, (filtered.size - 1).coerceAtLeast(0))
                        }
                    } else {
                        state.currentIndex.coerceIn(0, (filtered.size - 1).coerceAtLeast(0))
                    }

                    var nextQrDialogId = state.qrDialogVideoId
                    var nextPendingAutoQrId = state.pendingAutoQrVideoId
                    if (nextPendingAutoQrId != null) {
                        val autoTarget = sortedAll.firstOrNull { it.id == nextPendingAutoQrId }
                        val summary = autoTarget?.cloudUploadSummary
                        if (summary != null) {
                            when {
                                summary.status == CloudUploadStatus.UPLOADED &&
                                    QrCodeGenerator.isValidViewingUrl(summary.viewingUrl) -> {
                                    nextQrDialogId = nextPendingAutoQrId
                                    nextPendingAutoQrId = null
                                }
                                summary.status == CloudUploadStatus.FAILED -> {
                                    nextPendingAutoQrId = null
                                }
                            }
                        }
                    }

                    state.copy(
                        allVideos = sortedAll,
                        videos = filtered,
                        activeTab = resolvedTab,
                        isLoading = false,
                        currentIndex = focusedIndex,
                        qrDialogVideoId = nextQrDialogId,
                        pendingAutoQrVideoId = nextPendingAutoQrId
                    )
                }
            }
        }
        observeSearch("", VideoSearchSort.NEWEST)
    }

    private fun observeSearch(query: String, sort: VideoSearchSort) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            repository.searchVideos(query, sort).collect { results ->
                _uiState.update { it.copy(discoveryResults = results) }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        observeSearch(query, _uiState.value.searchSort)
    }

    fun updateSearchSort(sort: VideoSearchSort) {
        _uiState.update { it.copy(searchSort = sort) }
        observeSearch(_uiState.value.searchQuery, sort)
    }

    fun jumpToVideoInFeed(videoId: String) {
        _uiState.update { state ->
            val all = state.allVideos
            val idx = all.indexOfFirst { it.id == videoId }.coerceAtLeast(0)
            state.copy(
                activeTab = FeedFilterTab.FOR_YOU,
                videos = all,
                currentIndex = idx,
                isPlaying = true,
                songActionMessage = null
            )
        }
    }

    private fun applySort(videos: List<VideoItem>, sortOrder: FeedSortOrder): List<VideoItem> {
        return when (sortOrder) {
            FeedSortOrder.NEWEST -> videos.sortedByDescending { it.createdAt }
            FeedSortOrder.MOST_LIKED -> videos.sortedWith(
                compareByDescending<VideoItem> { it.likeCount }.thenByDescending { it.createdAt }
            )
            FeedSortOrder.FEATURED_FIRST -> videos.sortedWith(
                compareByDescending<VideoItem> { it.isFeatured }.thenByDescending { it.createdAt }
            )
        }
    }

    private fun applyFilter(videos: List<VideoItem>, tab: FeedFilterTab): List<VideoItem> {
        return when (tab) {
            FeedFilterTab.FOR_YOU -> videos
            FeedFilterTab.FEATURED -> videos.filter { it.isFeatured }
            FeedFilterTab.FAVORITES -> videos.filter { it.isFavorited }
        }
    }

    fun selectFilterTab(tab: FeedFilterTab) {
        val stateNow = _uiState.value
        val resolvedTab = when {
            tab == FeedFilterTab.FEATURED && !stateNow.showFeaturedSection -> FeedFilterTab.FOR_YOU
            tab == FeedFilterTab.FAVORITES && !stateNow.enableGuestFavorites -> FeedFilterTab.FOR_YOU
            else -> tab
        }
        _uiState.update { state ->
            val filtered = applyFilter(state.allVideos, resolvedTab)
            state.copy(
                activeTab = resolvedTab,
                videos = filtered,
                currentIndex = 0,
                isPlaying = true,
                songActionMessage = null
            )
        }
    }

    fun onPageChanged(newIndex: Int) {
        _uiState.update {
            it.copy(
                currentIndex = newIndex,
                isPlaying = true,
                songActionMessage = null
            )
        }
    }

    fun onToggleLike(videoId: String) {
        if (!_uiState.value.enableGuestLikes) return
        viewModelScope.launch {
            repository.toggleLike(videoId)
        }
    }

    fun onToggleFavorite(videoId: String) {
        if (!_uiState.value.enableGuestFavorites) return
        viewModelScope.launch {
            repository.toggleFavorite(videoId)
        }
    }

    fun onDoubleTapLike(offset: Offset, videoId: String) {
        if (!_uiState.value.enableGuestLikes) return
        val heart = FloatingHeart(id = System.currentTimeMillis(), offset = offset)
        _uiState.update {
            it.copy(floatingHearts = it.floatingHearts + heart)
        }
        viewModelScope.launch {
            repository.ensureLiked(videoId)
        }
    }

    fun removeFloatingHeart(heartId: Long) {
        _uiState.update {
            it.copy(floatingHearts = it.floatingHearts.filterNot { heart -> heart.id == heartId })
        }
    }

    fun togglePlayPause() {
        _uiState.update { it.copy(isPlaying = !it.isPlaying) }
    }

    fun onVideoPublished(videoId: String, expectCloudQr: Boolean) {
        _uiState.update { state ->
            val sortedAll = applySort(rawFeedVideos, state.defaultFeedSort)
            val filtered = applyFilter(sortedAll, FeedFilterTab.FOR_YOU)
            val foundIndex = filtered.indexOfFirst { it.id == videoId }
            val resolvedIndex = if (foundIndex >= 0) {
                pendingFocusVideoId = null
                foundIndex
            } else {
                pendingFocusVideoId = videoId
                0
            }
            val publishedVideo = sortedAll.firstOrNull { it.id == videoId }
            val readyImmediate = expectCloudQr &&
                publishedVideo?.cloudUploadSummary?.status == CloudUploadStatus.UPLOADED &&
                QrCodeGenerator.isValidViewingUrl(publishedVideo.cloudUploadSummary.viewingUrl)

            state.copy(
                activeTab = FeedFilterTab.FOR_YOU,
                allVideos = sortedAll,
                videos = filtered,
                currentIndex = resolvedIndex,
                isPlaying = !readyImmediate,
                lastPublishedVideoId = videoId,
                qrDialogVideoId = if (readyImmediate) videoId else null,
                pendingAutoQrVideoId = if (expectCloudQr && !readyImmediate) videoId else null
            )
        }
    }

    fun openQrDialog(videoId: String) {
        _uiState.update {
            it.copy(
                qrDialogVideoId = videoId,
                pendingAutoQrVideoId = null
            )
        }
    }

    fun dismissQrDialog() {
        _uiState.update {
            it.copy(
                qrDialogVideoId = null,
                pendingAutoQrVideoId = null
            )
        }
    }

    fun clearPendingAutoQr() {
        pendingFocusVideoId = null
        _uiState.update {
            it.copy(
                qrDialogVideoId = null,
                pendingAutoQrVideoId = null,
                songActionMessage = null
            )
        }
    }

    fun dismissSongActionMessage() {
        _uiState.update { it.copy(songActionMessage = null) }
    }

    /**
     * Resolves and validates a feed video's soundtrack against the Room song catalog and local
     * audio storage before entering Dance Studio. Rejects missing, unapproved, retired, or
     * broken/empty audio files and debounces rapid repeated taps.
     */
    fun onUseThisSongClicked(
        video: VideoItem,
        onSongResolved: (SongItem) -> Unit
    ) {
        if (_uiState.value.isResolvingFeedSong || useSongJob?.isActive == true) {
            return
        }

        val soundtrackId = video.song.id.trim()
        if (soundtrackId.isBlank()) {
            _uiState.update {
                it.copy(songActionMessage = "This video uses original audio and cannot be reused as a catalog soundtrack.")
            }
            return
        }

        val catalogRepo = musicCatalogRepository
        val storageMgr = musicStorageManager
        if (catalogRepo == null || storageMgr == null) {
            _uiState.update {
                it.copy(songActionMessage = "Music catalog is currently unavailable. Please try again.")
            }
            return
        }

        _uiState.update {
            it.copy(
                isResolvingFeedSong = true,
                songActionMessage = null
            )
        }

        useSongJob = viewModelScope.launch {
            try {
                val validation = withContext(ioDispatcher) {
                    val catalogSong = catalogRepo.getSongById(soundtrackId)
                        ?: return@withContext FeedSongResolutionResult.Unavailable(
                            "This soundtrack is no longer available in the kiosk music catalog."
                        )

                    if (!catalogSong.isAvailable) {
                        return@withContext FeedSongResolutionResult.Unavailable(
                            "\"${catalogSong.title}\" has been removed from the event catalog."
                        )
                    }

                    if (!catalogSong.isApprovedForGuestUse) {
                        return@withContext FeedSongResolutionResult.Unavailable(
                            "\"${catalogSong.title}\" is not approved for guest recording."
                        )
                    }

                    if (catalogSong.audioRelativePath.isBlank()) {
                        return@withContext FeedSongResolutionResult.Unavailable(
                            "The audio file for \"${catalogSong.title}\" is unavailable."
                        )
                    }

                    val audioFile = storageMgr.getAbsoluteFile(catalogSong.audioRelativePath)
                    if (!audioFile.exists() || !audioFile.isFile || audioFile.length() <= 0L) {
                        return@withContext FeedSongResolutionResult.Unavailable(
                            "The audio file for \"${catalogSong.title}\" is missing or corrupted on this kiosk."
                        )
                    }

                    FeedSongResolutionResult.Ready(catalogSong)
                }

                when (validation) {
                    is FeedSongResolutionResult.Ready -> {
                        onSongResolved(validation.song)
                        _uiState.update {
                            it.copy(
                                isResolvingFeedSong = false,
                                songActionMessage = null
                            )
                        }
                    }
                    is FeedSongResolutionResult.Unavailable -> {
                        _uiState.update {
                            it.copy(
                                isResolvingFeedSong = false,
                                songActionMessage = validation.reason
                            )
                        }
                    }
                }
            } catch (_: CancellationException) {
                _uiState.update { it.copy(isResolvingFeedSong = false) }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        isResolvingFeedSong = false,
                        songActionMessage = "Could not load this soundtrack. Please choose another song."
                    )
                }
            }
        }
    }
}
