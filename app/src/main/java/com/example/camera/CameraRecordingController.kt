package com.example.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor

sealed class RecordingResult {
    data class Success(val recordedFile: File, val durationMs: Long) : RecordingResult()
    data class Error(
        val message: String,
        val partialFile: File? = null,
        val errorCode: Int = VideoRecordEvent.Finalize.ERROR_UNKNOWN,
        val causeMessage: String? = null,
        val recordedBytes: Long = 0L,
        val recordedDurationNanos: Long = 0L
    ) : RecordingResult()
}

open class CameraRecordingController(private val context: Context) {

    companion object {
        private const val TAG = "CameraRecordingCtrl"

        /**
         * Orders actually-supported camera recording qualities for API 23 stability:
         * prefers HD when supported, then falls back through supported lower qualities (SD),
         * then remaining supported qualities. Never assumes SD or LOWEST is supported.
         */
        fun prioritizeSupportedQualities(supportedQualities: List<Quality>): List<Quality> {
            if (supportedQualities.isEmpty()) return emptyList()
            val preferredOrder = listOf(Quality.HD, Quality.SD, Quality.FHD, Quality.UHD)
            val ordered = mutableListOf<Quality>()
            for (candidate in preferredOrder) {
                if (supportedQualities.contains(candidate) && !ordered.contains(candidate)) {
                    ordered.add(candidate)
                }
            }
            for (extra in supportedQualities) {
                if (!ordered.contains(extra)) {
                    ordered.add(extra)
                }
            }
            return ordered
        }

        fun isQualityDowngraded(preferred: Quality, selected: Quality): Boolean {
            val rank = mapOf(
                Quality.UHD to 4,
                Quality.FHD to 3,
                Quality.HD to 2,
                Quality.SD to 1,
                Quality.LOWEST to 0
            )
            val preferredRank = rank[preferred] ?: return false
            val selectedRank = rank[selected] ?: return false
            return selectedRank < preferredRank
        }

        fun qualityLabel(quality: Quality?): String = when (quality) {
            Quality.UHD -> "UHD"
            Quality.FHD -> "FHD"
            Quality.HD -> "HD"
            Quality.SD -> "SD"
            Quality.LOWEST -> "LOWEST"
            Quality.HIGHEST -> "HIGHEST"
            null -> "UNKNOWN"
            else -> quality.toString()
        }

        fun finalizeErrorName(errorCode: Int): String = when (errorCode) {
            VideoRecordEvent.Finalize.ERROR_NONE -> "ERROR_NONE"
            VideoRecordEvent.Finalize.ERROR_UNKNOWN -> "ERROR_UNKNOWN"
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "ERROR_FILE_SIZE_LIMIT_REACHED"
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "ERROR_INSUFFICIENT_STORAGE"
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "ERROR_SOURCE_INACTIVE"
            VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS -> "ERROR_INVALID_OUTPUT_OPTIONS"
            VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "ERROR_ENCODING_FAILED"
            VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR -> "ERROR_RECORDER_ERROR"
            VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "ERROR_NO_VALID_DATA"
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED -> "ERROR_DURATION_LIMIT_REACHED"
            VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED -> "ERROR_RECORDING_GARBAGE_COLLECTED"
            else -> "ERROR_CODE_$errorCode"
        }

        fun sanitizeDiagnosticCause(cause: Throwable?): String? {
            if (cause == null) return null
            val rawMsg = cause.message
                ?.replace(Regex("(/[A-Za-z0-9._-]+)+"), "<path>")
                ?.trim()
                ?.take(140)
            return if (rawMsg.isNullOrEmpty()) {
                cause.javaClass.simpleName
            } else {
                "${cause.javaClass.simpleName}: $rawMsg"
            }
        }
    }

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    private val _isRecording = MutableStateFlow(false)
    open val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSeconds = MutableStateFlow(0)
    val recordingDurationSeconds: StateFlow<Int> = _recordingDurationSeconds.asStateFlow()

    private val _recordedBytes = MutableStateFlow(0L)
    val recordedBytes: StateFlow<Long> = _recordedBytes.asStateFlow()

    private val _isVideoCaptureBound = MutableStateFlow(false)
    open val isVideoCaptureBound: StateFlow<Boolean> = _isVideoCaptureBound.asStateFlow()

    private val _bindingErrorMessage = MutableStateFlow<String?>(null)
    open val bindingErrorMessage: StateFlow<String?> = _bindingErrorMessage.asStateFlow()

    private val _activeVideoQuality = MutableStateFlow<Quality?>(null)
    open val activeVideoQuality: StateFlow<Quality?> = _activeVideoQuality.asStateFlow()

    private val _requestedVideoQuality = MutableStateFlow<Quality>(Quality.HD)
    open val requestedVideoQuality: StateFlow<Quality> = _requestedVideoQuality.asStateFlow()

    private val _isQualityDowngraded = MutableStateFlow(false)
    open val isQualityDowngraded: StateFlow<Boolean> = _isQualityDowngraded.asStateFlow()

    private val _negotiatedVideoResolution = MutableStateFlow("Pending")
    open val negotiatedVideoResolution: StateFlow<String> = _negotiatedVideoResolution.asStateFlow()

    open fun hasActiveRecording(): Boolean = activeRecording != null

    /**
     * Queries supported SDR video recording qualities for the target camera via
     * Recorder.getVideoCapabilities(cameraInfo) without assuming SD or LOWEST availability.
     */
    fun querySupportedVideoQualities(cameraInfo: CameraInfo): List<Quality> {
        return try {
            val capabilities = Recorder.getVideoCapabilities(cameraInfo)
            val rawSupported = capabilities.getSupportedQualities(DynamicRange.SDR)
            val prioritized = prioritizeSupportedQualities(rawSupported)
            Log.i(
                TAG,
                "Camera supported SDR qualities=${rawSupported.joinToString { qualityLabel(it) }}, " +
                    "prioritized=${prioritized.joinToString { qualityLabel(it) }}"
            )
            prioritized
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query Recorder video capabilities: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Creates a fresh VideoCapture use case for a specific verified quality.
     * Does not mark VideoCapture as bound until lifecycle binding actually succeeds.
     */
    fun createFreshVideoCapture(quality: Quality): VideoCapture<Recorder> {
        clearBoundVideoCapture(clearError = false)
        val qualitySelector = QualitySelector.from(quality)
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        return VideoCapture.withOutput(recorder)
    }

    /**
     * Creates a fresh VideoCapture use case with quality fallback constrained only to
     * explicitly verified qualities.
     */
    fun prepareVideoCapture(preferredQuality: Quality = Quality.HD): VideoCapture<Recorder> {
        clearBoundVideoCapture(clearError = false)
        val qualitySelector = QualitySelector.from(
            preferredQuality,
            FallbackStrategy.lowerQualityOrHigherThan(preferredQuality)
        )
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        return VideoCapture.withOutput(recorder)
    }

    fun markVideoCaptureBound(
        capture: VideoCapture<Recorder>,
        selectedQuality: Quality?,
        preferredQuality: Quality = Quality.HD
    ) {
        videoCapture = capture
        _isVideoCaptureBound.value = true
        _requestedVideoQuality.value = preferredQuality
        _activeVideoQuality.value = selectedQuality
        val downgraded = selectedQuality != null && isQualityDowngraded(preferredQuality, selectedQuality)
        _isQualityDowngraded.value = downgraded
        _bindingErrorMessage.value = null
        updateNegotiatedResolution(capture)
        if (downgraded) {
            Log.w(
                TAG,
                "VideoCapture quality downgraded: requested=${qualityLabel(preferredQuality)}, " +
                    "selected=${qualityLabel(selectedQuality)}, resolution=${_negotiatedVideoResolution.value}"
            )
        } else {
            Log.i(
                TAG,
                "VideoCapture bound cleanly: quality=${qualityLabel(selectedQuality)}, " +
                    "resolution=${_negotiatedVideoResolution.value}"
            )
        }
    }

    /**
     * Inspects actual negotiated VideoCapture resolutionInfo after bind / stream initialization.
     */
    fun updateNegotiatedResolution(capture: VideoCapture<Recorder>? = videoCapture): Pair<String, Int>? {
        val resInfo = capture?.resolutionInfo ?: return null
        val formatted = "${resInfo.resolution.width}x${resInfo.resolution.height}"
        _negotiatedVideoResolution.value = formatted
        Log.i(
            TAG,
            "VideoCapture negotiated stream: resolution=$formatted, rotation=${resInfo.rotationDegrees}deg, " +
                "cropRect=${resInfo.cropRect}, quality=${qualityLabel(_activeVideoQuality.value)}"
        )
        return formatted to resInfo.rotationDegrees
    }

    fun markVideoCaptureBindFailed(reason: String) {
        videoCapture = null
        _isVideoCaptureBound.value = false
        _activeVideoQuality.value = null
        _isQualityDowngraded.value = false
        _negotiatedVideoResolution.value = "Unbound"
        _bindingErrorMessage.value = reason
        Log.e(TAG, "VideoCapture binding failed: $reason")
    }

    fun clearBoundVideoCapture(clearError: Boolean = true) {
        videoCapture = null
        _isVideoCaptureBound.value = false
        _activeVideoQuality.value = null
        _isQualityDowngraded.value = false
        _negotiatedVideoResolution.value = "Pending"
        if (clearError) {
            _bindingErrorMessage.value = null
        }
    }

    /**
     * Prepares and binds a fresh VideoCapture use case using verified camera capabilities.
     */
    fun bindRecordingUseCase(
        lifecycleOwner: LifecycleOwner,
        cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
        onError: (String) -> Unit = {}
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                clearBoundVideoCapture(clearError = true)

                val externalSelector = try {
                    CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL)
                        .build()
                } catch (_: Exception) {
                    null
                }

                val selector = when {
                    externalSelector != null && provider.hasCamera(externalSelector) -> externalSelector
                    provider.hasCamera(cameraSelector) -> cameraSelector
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> null
                }

                if (selector == null) {
                    val msg = "No camera device available for video recording."
                    markVideoCaptureBindFailed(msg)
                    onError(msg)
                    return@addListener
                }

                val cameraInfo = provider.getCameraInfo(selector)
                val supportedQualities = querySupportedVideoQualities(cameraInfo)
                if (supportedQualities.isEmpty()) {
                    val msg = "Selected camera does not report any supported video recording quality."
                    markVideoCaptureBindFailed(msg)
                    onError(msg)
                    return@addListener
                }

                var bound = false
                var lastBindError: Throwable? = null
                for (quality in supportedQualities) {
                    val candidateCapture = createFreshVideoCapture(quality)
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, selector, candidateCapture)
                        markVideoCaptureBound(candidateCapture, quality)
                        bound = true
                        break
                    } catch (e: Exception) {
                        lastBindError = e
                        Log.w(TAG, "VideoCapture bind failed for quality=${qualityLabel(quality)}: ${e.message}")
                    }
                }

                if (!bound) {
                    val msg = "Unable to bind VideoCapture (${lastBindError?.message ?: "unsupported camera configuration"})."
                    markVideoCaptureBindFailed(msg)
                    onError(msg)
                }
            } catch (e: Exception) {
                val msg = "VideoCapture binding error: ${e.message ?: "unknown error"}"
                markVideoCaptureBindFailed(msg)
                onError(msg)
            }
        }, mainExecutor)
    }

    /**
     * Starts recording to a target MP4 file only when VideoCapture is actively bound and idle.
     * Note: Audio capture from camera microphone is intentionally omitted to avoid recording
     * room reverberation. The digital soundtrack will be muxed post-capture.
     */
    open fun startRecording(
        outputFile: File,
        onEvent: (VideoRecordEvent) -> Unit,
        onComplete: (RecordingResult) -> Unit
    ): Boolean {
        if (activeRecording != null || _isRecording.value) {
            Log.w(TAG, "Refusing startRecording(): another recording session is already active.")
            onComplete(
                RecordingResult.Error(
                    message = "A recording session is already in progress."
                )
            )
            return false
        }

        val capture = videoCapture
        if (capture == null || !_isVideoCaptureBound.value) {
            val reason = _bindingErrorMessage.value
                ?: "Camera video recording is unavailable because VideoCapture is not bound."
            Log.e(TAG, "Refusing startRecording(): $reason")
            _isRecording.value = false
            onComplete(RecordingResult.Error(message = reason))
            return false
        }

        outputFile.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
            }
        }
        if (outputFile.exists() && outputFile.length() == 0L) {
            outputFile.delete()
        }

        _recordingDurationSeconds.value = 0
        _recordedBytes.value = 0L

        val outputOptions = FileOutputOptions.Builder(outputFile).build()

        val selectedQualityLabel = qualityLabel(_activeVideoQuality.value)
        var loggedFirstStatus = false
        Log.i(
            TAG,
            "Starting VideoCapture recording (quality=$selectedQualityLabel, outputFile=${outputFile.name})"
        )

        // Prepare recording WITHOUT calling withAudioEnabled() -> webcam mic stays muted
        val recording = capture.output
            .prepareRecording(context, outputOptions)
            .start(mainExecutor) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        _isRecording.value = true
                        _recordingDurationSeconds.value = 0
                        _recordedBytes.value = 0L
                        Log.i(
                            TAG,
                            "VideoRecordEvent.Start received (quality=$selectedQualityLabel)"
                        )
                    }
                    is VideoRecordEvent.Status -> {
                        val stats = recordEvent.recordingStats
                        val durationNanos = stats.recordedDurationNanos
                        val numBytes = stats.numBytesRecorded
                        val seconds = (durationNanos / 1_000_000_000L).toInt()
                        _recordingDurationSeconds.value = seconds
                        _recordedBytes.value = numBytes
                        if (!loggedFirstStatus && (numBytes > 0L || durationNanos > 0L)) {
                            loggedFirstStatus = true
                            Log.i(
                                TAG,
                                "VideoRecordEvent.Status active frames: quality=$selectedQualityLabel, " +
                                    "durationNs=$durationNanos, numBytes=$numBytes"
                            )
                        } else {
                            Log.d(
                                TAG,
                                "VideoRecordEvent.Status durationNs=$durationNanos numBytes=$numBytes"
                            )
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        _isRecording.value = false
                        activeRecording = null

                        val stats = recordEvent.recordingStats
                        val finalBytes = stats.numBytesRecorded
                        val finalDurationNanos = stats.recordedDurationNanos
                        val finalDurationMs = finalDurationNanos / 1_000_000L
                        val fileExists = outputFile.exists()
                        val fileSize = if (fileExists) outputFile.length() else 0L
                        val cause = recordEvent.cause

                        _recordedBytes.value = finalBytes

                        if (recordEvent.hasError()) {
                            val errorCode = recordEvent.error
                            val errorName = finalizeErrorName(errorCode)
                            val causeSummary = sanitizeDiagnosticCause(cause)
                            Log.e(
                                TAG,
                                "VideoRecordEvent.Finalize failed: errorCode=$errorCode ($errorName), " +
                                    "quality=$selectedQualityLabel, cause=$causeSummary, " +
                                    "numBytesRecorded=$finalBytes, recordedDurationNanos=$finalDurationNanos, " +
                                    "outputFile=${outputFile.name}, fileExists=$fileExists, fileSize=$fileSize",
                                cause
                            )

                            // Clean up zero-byte files while preserving non-empty files for investigation/recovery
                            val recoverablePartial = when {
                                fileExists && fileSize == 0L -> {
                                    outputFile.delete()
                                    null
                                }
                                fileExists && fileSize > 0L && errorCode != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> {
                                    outputFile
                                }
                                else -> null
                            }

                            val causePart = causeSummary?.let { " • cause=$it" } ?: ""
                            onComplete(
                                RecordingResult.Error(
                                    message = "Recording finalized with error code: $errorCode ($errorName) • " +
                                        "quality=$selectedQualityLabel, bytes=$finalBytes, duration=${finalDurationMs}ms$causePart",
                                    partialFile = recoverablePartial,
                                    errorCode = errorCode,
                                    causeMessage = causeSummary,
                                    recordedBytes = finalBytes,
                                    recordedDurationNanos = finalDurationNanos
                                )
                            )
                        } else {
                            Log.i(
                                TAG,
                                "VideoRecordEvent.Finalize succeeded: quality=$selectedQualityLabel, " +
                                    "numBytesRecorded=$finalBytes, recordedDurationNanos=$finalDurationNanos, " +
                                    "outputFile=${outputFile.name}, fileExists=$fileExists, fileSize=$fileSize"
                            )
                            if (!fileExists || fileSize == 0L) {
                                if (fileExists) outputFile.delete()
                                onComplete(
                                    RecordingResult.Error(
                                        message = "Recording finalized without valid MP4 output (quality=$selectedQualityLabel, 0 bytes).",
                                        partialFile = null,
                                        errorCode = VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA,
                                        recordedBytes = finalBytes,
                                        recordedDurationNanos = finalDurationNanos
                                    )
                                )
                            } else {
                                onComplete(RecordingResult.Success(outputFile, finalDurationMs))
                            }
                        }
                    }
                }
                onEvent(recordEvent)
            }

        activeRecording = recording
        return true
    }

    /**
     * Stops the active video recording safely.
     */
    open fun stopRecording() {
        val current = activeRecording
        if (current == null) {
            _isRecording.value = false
            return
        }
        try {
            current.stop()
            activeRecording = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping active recording", e)
        }
        _isRecording.value = false
    }

    /**
     * Cancels the active recording and discards output.
     */
    open fun cancelRecording() {
        val current = activeRecording
        activeRecording = null
        if (current != null) {
            try {
                current.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling active recording", e)
            }
        }
        _isRecording.value = false
    }

    /**
     * Creates a fallback simulated MP4 file in development/testing mode when no physical camera is present.
     */
    suspend fun createSimulatedVideoFile(outputFile: File, durationSeconds: Int): File = withContext(Dispatchers.IO) {
        outputFile.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
            }
        }
        // Minimal valid MP4 header structure or placeholder bytes for development
        FileOutputStream(outputFile).use { fos ->
            val simulatedHeader = byteArrayOf(
                0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, // ftyp box
                0x69, 0x73, 0x6F, 0x6D, 0x00, 0x00, 0x02, 0x00,
                0x69, 0x73, 0x6F, 0x6D, 0x69, 0x73, 0x6F, 0x32,
                0x61, 0x76, 0x63, 0x31, 0x6D, 0x70, 0x34, 0x31,
                0x00, 0x00, 0x00, 0x08, 0x66, 0x72, 0x65, 0x65  // free box
            )
            fos.write(simulatedHeader)
            // Write padding bytes representing 30fps frames for the duration
            val frameBytes = ByteArray(1024) { 0x55.toByte() }
            for (i in 0 until (durationSeconds * 30)) {
                fos.write(frameBytes)
            }
            fos.flush()
        }
        outputFile
    }
}
