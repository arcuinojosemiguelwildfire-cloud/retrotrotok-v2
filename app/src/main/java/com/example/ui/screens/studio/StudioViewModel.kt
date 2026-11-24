package com.example.ui.screens.studio

import androidx.camera.video.VideoRecordEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.camera.CameraBackendType
import com.example.camera.CameraConnectionState
import com.example.camera.CameraProvider
import com.example.camera.CameraRecordingController
import com.example.camera.RecordingResult
import com.example.data.repository.KioskSettingsRepository
import com.example.model.EventModeStatus
import com.example.model.RetroEffectType
import com.example.model.SongItem
import com.example.video.CameraFramingMode
import com.example.video.MuxerResult
import com.example.video.SoundtrackSessionController
import com.example.video.TempRecordingMetadata
import com.example.video.VideoMuxerProcessor
import com.example.video.VideoStorageManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * High-integrity recording state machine for RetroTok dance recording.
 */
enum class StudioState {
    IDLE,
    INITIALIZING_CAMERA,
    READY,
    COUNTDOWN,
    RECORDING,
    FINALIZING,
    PROCESSING,
    PREVIEW_READY,
    ERROR
}

sealed class StudioNavigationEvent {
    data class NavigateToPreview(
        val videoFilePath: String,
        val song: SongItem,
        val effect: RetroEffectType,
        val durationSeconds: Int
    ) : StudioNavigationEvent()
    object NavigateBack : StudioNavigationEvent()
}

data class StudioUiState(
    val selectedSong: SongItem? = null,
    val selectedEffect: RetroEffectType = RetroEffectType.NONE,
    val studioState: StudioState = StudioState.READY,
    val countdownNumber: Int = 3,
    val recordedSeconds: Int = 0,
    val maxDurationSeconds: Int = 30,
    val isMirrored: Boolean = true,
    val showGrid: Boolean = false,
    val cameraFramingMode: CameraFramingMode = CameraFramingMode.WIDE_FIT,
    val isWebcamReady: Boolean = true,
    val isCameraDisconnected: Boolean = false,
    val isVideoCaptureBound: Boolean = false,
    val activeVideoQualityLabel: String? = null,
    val requestedVideoQualityLabel: String = "HD",
    val isQualityDowngraded: Boolean = false,
    val negotiatedVideoResolution: String = "Pending",
    val cameraBindingError: String? = null,
    val hasRecordingStarted: Boolean = false,
    val recordedBytes: Long = 0L,
    val cameraBindRetryCounter: Int = 0,
    val cameraStatusMessage: String? = null,
    val eventModeStatus: EventModeStatus = EventModeStatus.ACTIVE,
    val missingSongNotice: Boolean = false,
    val errorMessage: String? = null,
    val activeBackend: CameraBackendType = CameraBackendType.DEV_PREVIEW,
    val lastMuxedVideoPath: String? = null,
    val processingProgress: Float = 0f,
    val processingStageLabel: String = ""
)

class StudioViewModel(
    private val cameraProvider: CameraProvider,
    private val recordingController: CameraRecordingController,
    private val soundtrackController: SoundtrackSessionController,
    private val storageManager: VideoStorageManager,
    private val muxerProcessor: VideoMuxerProcessor,
    private val settingsRepository: KioskSettingsRepository? = null,
    private val recordingStartTimeoutMs: Long = DEFAULT_RECORDING_START_TIMEOUT_MS
) : ViewModel() {

    companion object {
        const val DEFAULT_RECORDING_START_TIMEOUT_MS = 5_000L
    }

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private val _navEvents = MutableSharedFlow<StudioNavigationEvent>()
    val navEvents: SharedFlow<StudioNavigationEvent> = _navEvents.asSharedFlow()

    private var countdownJob: Job? = null
    private var recordingJob: Job? = null
    private var recordingStartupTimeoutJob: Job? = null

    private var currentRecordingId: String? = null
    private var currentRawFile: File? = null
    private var currentMuxedFile: File? = null
    private var actualRecordStartNs: Long = 0L
    private var configuredFramingMode: CameraFramingMode = CameraFramingMode.WIDE_FIT

    init {
        observeCameraState()
        observeRecordingControllerState()
        observeSettings()
        cameraProvider.setDisconnectionCallback {
            handleCameraDisconnection()
        }
    }

    private fun observeSettings() {
        val repo = settingsRepository ?: return
        viewModelScope.launch {
            repo.getSettings().collect { settings ->
                configuredFramingMode = settings.cameraFramingMode
                _uiState.update { state ->
                    val isBetweenSessions = state.studioState == StudioState.READY || state.studioState == StudioState.IDLE
                    val updatedMaxDuration = if (isBetweenSessions) {
                        settings.defaultRecordingDurationSeconds
                    } else {
                        state.maxDurationSeconds
                    }
                    val updatedFramingMode = if (isBetweenSessions) {
                        settings.cameraFramingMode
                    } else {
                        state.cameraFramingMode
                    }
                    state.copy(
                        eventModeStatus = settings.eventModeStatus,
                        maxDurationSeconds = updatedMaxDuration,
                        cameraFramingMode = updatedFramingMode
                    )
                }
            }
        }
    }

    private fun observeRecordingControllerState() {
        viewModelScope.launch {
            recordingController.isVideoCaptureBound.collect { bound ->
                _uiState.update { current ->
                    val clearedError = if (bound && current.errorMessage == current.cameraBindingError) {
                        null
                    } else {
                        current.errorMessage
                    }
                    val currentQuality = recordingController.activeVideoQuality.value
                    current.copy(
                        isVideoCaptureBound = bound,
                        activeVideoQualityLabel = if (bound && currentQuality != null) {
                            CameraRecordingController.qualityLabel(currentQuality)
                        } else if (!bound) {
                            null
                        } else {
                            current.activeVideoQualityLabel
                        },
                        cameraBindingError = if (bound) null else current.cameraBindingError,
                        errorMessage = clearedError
                    )
                }
            }
        }
        viewModelScope.launch {
            recordingController.activeVideoQuality.collect { quality ->
                _uiState.update { current ->
                    current.copy(
                        activeVideoQualityLabel = quality?.let { CameraRecordingController.qualityLabel(it) },
                        requestedVideoQualityLabel = CameraRecordingController.qualityLabel(recordingController.requestedVideoQuality.value),
                        isQualityDowngraded = recordingController.isQualityDowngraded.value
                    )
                }
            }
        }
        viewModelScope.launch {
            recordingController.negotiatedVideoResolution.collect { resolution ->
                _uiState.update { current ->
                    current.copy(
                        negotiatedVideoResolution = resolution,
                        isQualityDowngraded = recordingController.isQualityDowngraded.value,
                        requestedVideoQualityLabel = CameraRecordingController.qualityLabel(recordingController.requestedVideoQuality.value)
                    )
                }
            }
        }
        viewModelScope.launch {
            recordingController.bindingErrorMessage.collect { bindError ->
                if (bindError != null) {
                    _uiState.update { current ->
                        current.copy(
                            isVideoCaptureBound = false,
                            activeVideoQualityLabel = null,
                            isQualityDowngraded = false,
                            cameraBindingError = bindError,
                            errorMessage = bindError
                        )
                    }
                }
            }
        }
    }

    private fun observeCameraState() {
        viewModelScope.launch {
            cameraProvider.connectionState.collect { state ->
                val isReady = state == CameraConnectionState.CONNECTED || state == CameraConnectionState.NOT_TESTED
                val isDisconnected = state == CameraConnectionState.DISCONNECTED || state == CameraConnectionState.ERROR
                val backend = cameraProvider.selectedBackend.value
                val providerMsg = cameraProvider.errorMessage.value
                _uiState.update { current ->
                    val recoveredFromDisconnect = !isDisconnected && current.isCameraDisconnected && current.studioState == StudioState.ERROR
                    current.copy(
                        isWebcamReady = isReady,
                        isCameraDisconnected = isDisconnected,
                        cameraStatusMessage = if (isDisconnected) {
                            providerMsg ?: "USB webcam or camera unavailable. Reconnect the camera and tap Retry."
                        } else {
                            null
                        },
                        activeBackend = backend,
                        studioState = if (recoveredFromDisconnect) StudioState.READY else current.studioState,
                        errorMessage = if (recoveredFromDisconnect) null else current.errorMessage
                    )
                }
                if (isDisconnected && (
                        _uiState.value.studioState == StudioState.RECORDING ||
                            _uiState.value.studioState == StudioState.COUNTDOWN ||
                            _uiState.value.studioState == StudioState.INITIALIZING_CAMERA
                        )
                ) {
                    handleCameraDisconnection()
                }
            }
        }
    }

    fun retryCameraConnection() {
        _uiState.update { current ->
            val resetState = if (current.studioState == StudioState.ERROR) StudioState.READY else current.studioState
            current.copy(
                studioState = resetState,
                errorMessage = null,
                cameraBindingError = null,
                cameraStatusMessage = "Checking camera connection...",
                cameraBindRetryCounter = current.cameraBindRetryCounter + 1
            )
        }
        cameraProvider.refreshDevices()
    }

    fun selectSong(song: SongItem) {
        if (_uiState.value.studioState == StudioState.READY || _uiState.value.studioState == StudioState.IDLE) {
            _uiState.update { it.copy(selectedSong = song, missingSongNotice = false) }
            soundtrackController.prepareTrack(song)
        }
    }

    fun selectEffect(effect: RetroEffectType) {
        if (_uiState.value.studioState == StudioState.READY || _uiState.value.studioState == StudioState.IDLE) {
            _uiState.update { it.copy(selectedEffect = effect) }
        }
    }

    fun setRecordingDuration(seconds: Int) {
        if (seconds in listOf(15, 30, 60)) {
            _uiState.update { it.copy(maxDurationSeconds = seconds) }
        }
    }

    fun toggleMirror() {
        _uiState.update { it.copy(isMirrored = !it.isMirrored) }
    }

    fun toggleGrid() {
        _uiState.update { it.copy(showGrid = !it.showGrid) }
    }

    fun startCountdownAndRecord() {
        val currentState = _uiState.value.studioState
        if (currentState != StudioState.READY && currentState != StudioState.IDLE) return

        // Prevent duplicate or overlapping recording sessions
        if (countdownJob?.isActive == true ||
            recordingStartupTimeoutJob?.isActive == true ||
            recordingJob?.isActive == true ||
            recordingController.hasActiveRecording() ||
            recordingController.isRecording.value
        ) {
            return
        }

        if (_uiState.value.eventModeStatus != EventModeStatus.ACTIVE) {
            _uiState.update {
                it.copy(
                    errorMessage = "Event recording is currently paused or ended by the organizer."
                )
            }
            return
        }

        if (_uiState.value.isCameraDisconnected) {
            _uiState.update {
                it.copy(
                    errorMessage = "Camera is disconnected. Reconnect the camera and tap Retry."
                )
            }
            return
        }

        if (_uiState.value.selectedSong == null) {
            _uiState.update { it.copy(missingSongNotice = true) }
            return
        }

        if (!recordingController.isVideoCaptureBound.value) {
            _uiState.update {
                it.copy(
                    errorMessage = it.cameraBindingError
                        ?: "Camera video capture is not ready. Tap Retry to re-bind the camera before recording."
                )
            }
            return
        }

        if (!storageManager.hasSufficientSpaceForRecording()) {
            _uiState.update {
                it.copy(
                    errorMessage = "Low device storage (< 100 MB free). Ask an administrator to free space before recording."
                )
            }
            return
        }

        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    studioState = StudioState.COUNTDOWN,
                    countdownNumber = 3,
                    missingSongNotice = false,
                    errorMessage = null
                )
            }
            delay(1000)
            _uiState.update { it.copy(countdownNumber = 2) }
            delay(1000)
            _uiState.update { it.copy(countdownNumber = 1) }
            delay(1000)

            if (_uiState.value.isCameraDisconnected || !recordingController.isVideoCaptureBound.value) {
                _uiState.update {
                    it.copy(
                        studioState = StudioState.READY,
                        errorMessage = it.cameraBindingError
                            ?: "Camera video capture became unavailable during countdown."
                    )
                }
                return@launch
            }

            // Start actual recording; soundtrack and recording timer wait for VideoRecordEvent.Start
            startRecording()
        }
    }

    private fun startRecording() {
        val song = _uiState.value.selectedSong ?: return
        if (recordingController.hasActiveRecording() || recordingController.isRecording.value) {
            return
        }

        val recordingId = storageManager.generateRecordingId()
        currentRecordingId = recordingId
        val rawFile = storageManager.createTempRawVideoFile(recordingId)
        currentRawFile = rawFile
        actualRecordStartNs = System.nanoTime()

        _uiState.update {
            it.copy(
                studioState = StudioState.INITIALIZING_CAMERA,
                hasRecordingStarted = false,
                recordedSeconds = 0,
                recordedBytes = 0L,
                processingProgress = 0f,
                processingStageLabel = "Starting camera recording..."
            )
        }

        // Persist lightweight recovery metadata so sudden power loss / restart preserves take info
        viewModelScope.launch {
            storageManager.saveTempRecordingMetadata(
                TempRecordingMetadata(
                    recordingId = recordingId,
                    videoFileName = rawFile.name,
                    songId = song.id,
                    songTitle = song.title,
                    songArtist = song.artist,
                    effectName = _uiState.value.selectedEffect.name,
                    durationSeconds = _uiState.value.maxDurationSeconds,
                    createdAtMs = System.currentTimeMillis(),
                    stage = "RAW",
                    framingModeName = _uiState.value.cameraFramingMode.name
                )
            )
        }

        recordingStartupTimeoutJob?.cancel()
        recordingStartupTimeoutJob = viewModelScope.launch {
            delay(recordingStartTimeoutMs)
            if (_uiState.value.studioState == StudioState.INITIALIZING_CAMERA && !_uiState.value.hasRecordingStarted) {
                recordingController.cancelRecording()
                soundtrackController.stopPlayback()
                storageManager.cleanupFailedTempRecording(currentRawFile, currentRecordingId)
                _uiState.update {
                    it.copy(
                        studioState = StudioState.ERROR,
                        hasRecordingStarted = false,
                        errorMessage = "Recording failed to start: camera did not emit VideoRecordEvent.Start within ${recordingStartTimeoutMs / 1000}s."
                    )
                }
            }
        }

        // Ensure soundtrack player is warm before VideoRecordEvent.Start (handles cancel/timeout/retake recovery)
        soundtrackController.ensurePrepared(song)

        // Start hardware camera recording; only enter RECORDING state upon VideoRecordEvent.Start
        val initiated = recordingController.startRecording(
            outputFile = rawFile,
            onEvent = { event -> handleRecordEvent(event) },
            onComplete = { result -> handleRecordingFinished(result) }
        )

        if (!initiated) {
            recordingStartupTimeoutJob?.cancel()
        }
    }

    private fun handleRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                recordingStartupTimeoutJob?.cancel()
                if (_uiState.value.studioState == StudioState.ERROR ||
                    _uiState.value.studioState == StudioState.FINALIZING
                ) {
                    return
                }
                _uiState.update {
                    it.copy(
                        studioState = StudioState.RECORDING,
                        hasRecordingStarted = true,
                        recordedSeconds = 0,
                        recordedBytes = 0L,
                        processingStageLabel = ""
                    )
                }
                // Start speaker playback synchronously when CameraX confirms recording started
                soundtrackController.startPlaybackAtRecordStart()

                // Max-duration auto-stop guard (UI seconds come from VideoRecordEvent.Status)
                recordingJob?.cancel()
                recordingJob = viewModelScope.launch {
                    val totalSeconds = _uiState.value.maxDurationSeconds
                    delay(totalSeconds * 1000L)
                    stopRecordingManually()
                }
            }
            is VideoRecordEvent.Status -> {
                val stats = event.recordingStats
                val seconds = (stats.recordedDurationNanos / 1_000_000_000L).toInt().coerceAtLeast(0)
                _uiState.update {
                    it.copy(
                        recordedSeconds = seconds,
                        recordedBytes = stats.numBytesRecorded
                    )
                }
            }
            is VideoRecordEvent.Finalize -> {
                recordingStartupTimeoutJob?.cancel()
                recordingJob?.cancel()
            }
        }
    }

    fun stopRecordingManually() {
        val currentState = _uiState.value.studioState
        if (currentState == StudioState.INITIALIZING_CAMERA) {
            recordingStartupTimeoutJob?.cancel()
            recordingJob?.cancel()
            soundtrackController.stopPlayback()
            recordingController.cancelRecording()
            viewModelScope.launch {
                storageManager.cleanupFailedTempRecording(currentRawFile, currentRecordingId)
            }
            _uiState.update {
                it.copy(
                    studioState = StudioState.READY,
                    hasRecordingStarted = false,
                    recordedSeconds = 0,
                    recordedBytes = 0L,
                    processingStageLabel = ""
                )
            }
            return
        }

        if (currentState == StudioState.RECORDING) {
            recordingStartupTimeoutJob?.cancel()
            recordingJob?.cancel()
            _uiState.update {
                it.copy(
                    studioState = StudioState.FINALIZING,
                    processingProgress = 0.02f,
                    processingStageLabel = "Finalizing camera capture..."
                )
            }

            // Stop speaker audio playback immediately
            soundtrackController.stopPlayback()

            // Stop camera recording (CameraX emits VideoRecordEvent.Finalize asynchronously)
            recordingController.stopRecording()
        }
    }

    private fun handleRecordingFinished(result: RecordingResult) {
        recordingStartupTimeoutJob?.cancel()
        recordingJob?.cancel()
        viewModelScope.launch {
            when (result) {
                is RecordingResult.Success -> {
                    if (!result.recordedFile.exists() || result.recordedFile.length() == 0L) {
                        soundtrackController.stopPlayback()
                        storageManager.cleanupFailedTempRecording(result.recordedFile, currentRecordingId)
                        _uiState.update {
                            it.copy(
                                studioState = StudioState.ERROR,
                                hasRecordingStarted = false,
                                errorMessage = "Recording error: output MP4 file is empty (0 bytes)."
                            )
                        }
                    } else {
                        processVideoMuxing(result.recordedFile, result.durationMs)
                    }
                }
                is RecordingResult.Error -> {
                    soundtrackController.stopPlayback()
                    val partial = result.partialFile
                    if (partial != null && partial.exists() && partial.length() > 0L) {
                        // Attempt to salvage non-empty partial recording
                        processVideoMuxing(partial, _uiState.value.recordedSeconds * 1000L)
                    } else {
                        // Clean up 0-byte temp file and orphaned metadata while preserving any non-empty raw file
                        storageManager.cleanupFailedTempRecording(currentRawFile, currentRecordingId)
                        val formattedError = if (result.message.startsWith("Recording ")) {
                            result.message
                        } else {
                            "Recording error: ${result.message}"
                        }
                        _uiState.update {
                            it.copy(
                                studioState = StudioState.ERROR,
                                hasRecordingStarted = false,
                                errorMessage = formattedError
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun processVideoMuxing(rawVideoFile: File, recordedDurationMs: Long) {
        val song = _uiState.value.selectedSong ?: return
        val recordingId = currentRecordingId ?: storageManager.generateRecordingId()
        val muxedFile = storageManager.createTempMuxedVideoFile(recordingId)
        currentMuxedFile = muxedFile

        _uiState.update {
            it.copy(
                studioState = StudioState.PROCESSING,
                processingProgress = 0.05f,
                processingStageLabel = "Preparing video composition..."
            )
        }

        // Resolve original local audio file for digital soundtrack injection
        val audioFile = soundtrackController.getResolvedAudioFile()
        val durationMs = if (recordedDurationMs > 0) recordedDurationMs else (_uiState.value.recordedSeconds * 1000L).coerceAtLeast(3000L)

        val framingMode = _uiState.value.cameraFramingMode
        val muxResult = muxerProcessor.processAndMux(
            rawVideoFile = rawVideoFile,
            audioFile = audioFile,
            outputFile = muxedFile,
            durationMs = durationMs,
            effect = _uiState.value.selectedEffect,
            isMirrored = _uiState.value.isMirrored,
            framingMode = framingMode,
            onProgress = { progress, label ->
                _uiState.update {
                    it.copy(
                        processingProgress = progress.coerceIn(0f, 1f),
                        processingStageLabel = label
                    )
                }
            }
        )

        val finalVideoFile = when (muxResult) {
            is MuxerResult.Success -> muxResult.finalFile
            is MuxerResult.Error -> muxResult.fallbackFile ?: rawVideoFile
        }

        val finalDurationSec = ((durationMs / 1000L).toInt()).coerceAtLeast(
            _uiState.value.recordedSeconds.coerceAtLeast(5)
        )

        storageManager.saveTempRecordingMetadata(
            TempRecordingMetadata(
                recordingId = recordingId,
                videoFileName = finalVideoFile.name,
                songId = song.id,
                songTitle = song.title,
                songArtist = song.artist,
                effectName = _uiState.value.selectedEffect.name,
                durationSeconds = finalDurationSec,
                createdAtMs = System.currentTimeMillis(),
                stage = "PREVIEW_READY",
                framingModeName = framingMode.name
            )
        )

        _uiState.update {
            it.copy(
                studioState = StudioState.PREVIEW_READY,
                hasRecordingStarted = false,
                lastMuxedVideoPath = finalVideoFile.absolutePath,
                processingProgress = 1f,
                processingStageLabel = "Ready for preview"
            )
        }

        _navEvents.emit(
            StudioNavigationEvent.NavigateToPreview(
                videoFilePath = finalVideoFile.absolutePath,
                song = song,
                effect = _uiState.value.selectedEffect,
                durationSeconds = finalDurationSec
            )
        )
    }

    fun handleCameraDisconnection() {
        countdownJob?.cancel()
        recordingStartupTimeoutJob?.cancel()
        recordingJob?.cancel()
        val wasRecordingOrCountdown =
            _uiState.value.studioState == StudioState.RECORDING ||
                _uiState.value.studioState == StudioState.COUNTDOWN ||
                _uiState.value.studioState == StudioState.INITIALIZING_CAMERA
        if (wasRecordingOrCountdown) {
            soundtrackController.stopPlayback()
            recordingController.stopRecording()
            _uiState.update {
                it.copy(
                    studioState = StudioState.ERROR,
                    hasRecordingStarted = false,
                    isCameraDisconnected = true,
                    isWebcamReady = false,
                    cameraStatusMessage = "USB webcam disconnected during recording.",
                    errorMessage = "Webcam disconnected during active recording. Incomplete take preserved in temporary storage."
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    isCameraDisconnected = true,
                    isWebcamReady = false,
                    cameraStatusMessage = "USB webcam disconnected. Reconnect camera and tap Retry."
                )
            }
        }
    }

    /**
     * Clears guest-specific studio selections so a new guest session never inherits
     * another guest's song, effect, mirror/grid preference, or draft path.
     */
    fun startNewGuestSession() {
        countdownJob?.cancel()
        recordingStartupTimeoutJob?.cancel()
        recordingJob?.cancel()
        soundtrackController.release()
        recordingController.cancelRecording()
        currentRecordingId = null
        currentRawFile = null
        currentMuxedFile = null
        _uiState.update {
            it.copy(
                selectedSong = null,
                selectedEffect = RetroEffectType.NONE,
                studioState = StudioState.READY,
                recordedSeconds = 0,
                recordedBytes = 0L,
                hasRecordingStarted = false,
                countdownNumber = 3,
                isMirrored = true,
                showGrid = false,
                cameraFramingMode = configuredFramingMode,
                missingSongNotice = false,
                errorMessage = it.cameraBindingError,
                lastMuxedVideoPath = null,
                processingProgress = 0f,
                processingStageLabel = ""
            )
        }
    }

    fun resetStudio() {
        countdownJob?.cancel()
        recordingStartupTimeoutJob?.cancel()
        recordingJob?.cancel()
        soundtrackController.stopPlayback()
        recordingController.cancelRecording()
        val preservedSong = _uiState.value.selectedSong
        _uiState.update {
            it.copy(
                studioState = StudioState.READY,
                recordedSeconds = 0,
                recordedBytes = 0L,
                hasRecordingStarted = false,
                countdownNumber = 3,
                cameraFramingMode = configuredFramingMode,
                errorMessage = it.cameraBindingError,
                processingProgress = 0f,
                processingStageLabel = ""
            )
        }
        preservedSong?.let { soundtrackController.ensurePrepared(it) }
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
        recordingStartupTimeoutJob?.cancel()
        recordingJob?.cancel()
        soundtrackController.release()
        recordingController.cancelRecording()
    }
}
