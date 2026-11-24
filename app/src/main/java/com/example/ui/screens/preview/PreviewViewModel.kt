package com.example.ui.screens.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloud.CloudUploadManager
import com.example.data.mock.MockDataProvider
import com.example.data.repository.KioskSettingsRepository
import com.example.data.repository.PublishVideoResult
import com.example.data.repository.VideoFeedRepository
import com.example.data.repository.VideoFeedRepositoryImpl
import com.example.model.RetroEffectType
import com.example.model.SongItem
import com.example.model.VideoItem
import com.example.video.VideoStorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class PreviewUiState(
    val videoFilePath: String = "",
    val song: SongItem = MockDataProvider.mockSongs[0],
    val effect: RetroEffectType = RetroEffectType.NONE,
    val durationSeconds: Int = 30,
    val guestNickname: String = "",
    val requireNickname: Boolean = false,
    val allowGuestDeletion: Boolean = false,
    val nicknameError: String? = null,
    val showPublishModal: Boolean = false,
    val showDiscardModal: Boolean = false,
    val isPublishing: Boolean = false,
    val publishSuccessMessage: String? = null,
    val publishErrorMessage: String? = null,
    val isCloudBackupEnabled: Boolean = false
)

class PreviewViewModel(
    private val videoFeedRepository: VideoFeedRepository,
    private val videoStorageManager: VideoStorageManager,
    private val settingsRepository: KioskSettingsRepository? = null,
    private val cloudUploadManager: CloudUploadManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(PreviewUiState())
    val uiState: StateFlow<PreviewUiState> = _uiState.asStateFlow()

    init {
        settingsRepository?.let { repo ->
            viewModelScope.launch {
                repo.getSettings().collect { settings ->
                    _uiState.update {
                        it.copy(
                            requireNickname = settings.requireGuestNickname,
                            allowGuestDeletion = settings.allowGuestDeletion
                        )
                    }
                }
            }
        }
        cloudUploadManager?.let { cloud ->
            viewModelScope.launch {
                cloud.pairingState.collect { pairing ->
                    _uiState.update {
                        it.copy(isCloudBackupEnabled = pairing.isPairedAndAuthorized)
                    }
                }
            }
        }
    }

    fun initialize(videoFilePath: String, song: SongItem, effect: RetroEffectType, durationSeconds: Int) {
        _uiState.update {
            it.copy(
                videoFilePath = videoFilePath,
                song = song,
                effect = effect,
                durationSeconds = durationSeconds,
                guestNickname = "",
                nicknameError = null,
                showPublishModal = false,
                showDiscardModal = false,
                isPublishing = false,
                publishSuccessMessage = null,
                publishErrorMessage = null
            )
        }
    }

    fun clearSessionState() {
        _uiState.update {
            it.copy(
                videoFilePath = "",
                guestNickname = "",
                nicknameError = null,
                showPublishModal = false,
                showDiscardModal = false,
                isPublishing = false,
                publishSuccessMessage = null,
                publishErrorMessage = null
            )
        }
    }

    fun onNicknameChange(name: String) {
        if (name.length <= 20) {
            _uiState.update { it.copy(guestNickname = name, nicknameError = null) }
        }
    }

    fun onEffectChange(newEffect: RetroEffectType) {
        _uiState.update { it.copy(effect = newEffect) }
    }

    fun requestPublish() {
        val state = _uiState.value
        if (state.requireNickname && state.guestNickname.trim().isBlank()) {
            _uiState.update {
                it.copy(
                    nicknameError = "Please enter your nickname or dance crew before publishing.",
                    showPublishModal = false
                )
            }
            return
        }
        _uiState.update { it.copy(showPublishModal = true, nicknameError = null, publishErrorMessage = null) }
    }

    fun dismissPublishModal() {
        _uiState.update { it.copy(showPublishModal = false, publishErrorMessage = null) }
    }

    fun confirmPublish(
        onPublishedVideo: (videoId: String, isCloudEnabled: Boolean) -> Unit = { _, _ -> },
        onSuccess: () -> Unit
    ) {
        val currentState = _uiState.value
        if (currentState.requireNickname && currentState.guestNickname.trim().isBlank()) {
            _uiState.update {
                it.copy(
                    isPublishing = false,
                    showPublishModal = false,
                    nicknameError = "Please enter your nickname or dance crew before publishing."
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isPublishing = true, publishErrorMessage = null) }

            val sourceFile = File(_uiState.value.videoFilePath)
            val nickname = _uiState.value.guestNickname.trim().ifBlank { "RetroDancer" }
            val song = _uiState.value.song
            val hashtags = "#RetroTok #${song.releaseYear ?: "Kiosk"}"
            val caption = "Dancing to ${song.title}! $hashtags"

            if (videoFeedRepository is VideoFeedRepositoryImpl) {
                when (val result = videoFeedRepository.publishRecordedVideo(
                    sourceFile = sourceFile,
                    nickname = nickname,
                    caption = caption,
                    hashtags = hashtags,
                    song = song,
                    effect = _uiState.value.effect,
                    durationSeconds = _uiState.value.durationSeconds
                )) {
                    is PublishVideoResult.Success -> {
                        cloudUploadManager?.onVideoPublishedAsync(result.publishedVideo.id)
                        val isCloudEnabled = _uiState.value.isCloudBackupEnabled
                        _uiState.update {
                            it.copy(
                                isPublishing = false,
                                showPublishModal = false,
                                publishSuccessMessage = "Video saved locally to Event Feed!"
                            )
                        }
                        onPublishedVideo(result.publishedVideo.id, isCloudEnabled)
                        onSuccess()
                    }
                    is PublishVideoResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isPublishing = false,
                                publishErrorMessage = result.message
                            )
                        }
                    }
                }
            } else {
                // Fallback path for MockVideoFeedRepository tests
                val videoId = "vid-recorded-${UUID.randomUUID()}"
                val publishedRelativePath = if (sourceFile.exists()) {
                    videoStorageManager.promoteToPublished(sourceFile, videoId)
                } else {
                    null
                }
                val newVideo = VideoItem(
                    id = videoId,
                    creatorNickname = nickname,
                    caption = caption,
                    song = song,
                    effect = _uiState.value.effect,
                    durationSeconds = _uiState.value.durationSeconds,
                    likeCount = 1,
                    favoriteCount = 0,
                    isFeatured = false,
                    isLiked = true,
                    mockGradientColors = listOf(0xFF28003A, 0xFFFF2A85, 0xFF00F0FF),
                    localVideoPath = publishedRelativePath,
                    hashtags = hashtags
                )
                videoFeedRepository.publishVideo(newVideo)
                val isCloudEnabled = _uiState.value.isCloudBackupEnabled
                _uiState.update {
                    it.copy(
                        isPublishing = false,
                        showPublishModal = false,
                        publishSuccessMessage = "Video published to Event Feed!"
                    )
                }
                onPublishedVideo(newVideo.id, isCloudEnabled)
                onSuccess()
            }
        }
    }

    fun requestDiscard() {
        _uiState.update { it.copy(showDiscardModal = true) }
    }

    fun dismissDiscardModal() {
        _uiState.update { it.copy(showDiscardModal = false) }
    }

    fun confirmDiscard(onDiscarded: () -> Unit) {
        viewModelScope.launch {
            val sourceFile = File(_uiState.value.videoFilePath)
            if (sourceFile.exists()) {
                videoStorageManager.deleteTempFile(sourceFile)
            }
            _uiState.update { it.copy(showDiscardModal = false) }
            onDiscarded()
        }
    }
}
