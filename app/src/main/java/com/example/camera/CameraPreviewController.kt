package com.example.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.example.video.CameraFramingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class CameraPreviewController(private val context: Context) {

    enum class PreviewResolutionTier(val label: String) {
        HIGH_16_9("16:9 Highest"),
        BALANCED_16_9_720P("16:9 720p"),
        COMPAT_4_3_FALLBACK("4:3 Compat")
    }

    companion object {
        private const val TAG = "CameraPreviewCtrl"

        /**
         * Builds CameraX ResolutionSelector tiers for Preview so high-resolution 16:9 preview
         * is preferred first, while preserving safe fallbacks for API 23 LEGACY/LIMITED HALs.
         */
        fun buildPreviewResolutionSelector(tier: PreviewResolutionTier): ResolutionSelector {
            return when (tier) {
                PreviewResolutionTier.HIGH_16_9 -> ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()

                PreviewResolutionTier.BALANCED_16_9_720P -> ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()

                PreviewResolutionTier.COMPAT_4_3_FALLBACK -> ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            }
        }

        /**
         * Determines candidate CameraSelector lens facing order.
         * Explicit BACK or FRONT requests NEVER silently fall back to the opposite lens.
         */
        fun selectCandidateLensFacings(
            requestedLens: CameraLensOption,
            hasExternal: Boolean,
            hasBack: Boolean,
            hasFront: Boolean
        ): List<Int> {
            return when (requestedLens) {
                CameraLensOption.AUTO -> buildList {
                    if (hasExternal) add(CameraSelector.LENS_FACING_EXTERNAL)
                    if (hasBack) add(CameraSelector.LENS_FACING_BACK)
                    if (hasFront) add(CameraSelector.LENS_FACING_FRONT)
                }
                CameraLensOption.BACK -> if (hasBack) listOf(CameraSelector.LENS_FACING_BACK) else emptyList()
                CameraLensOption.FRONT -> if (hasFront) listOf(CameraSelector.LENS_FACING_FRONT) else emptyList()
            }
        }

        /**
         * Computes the target lens when toggling between BACK and FRONT cameras.
         * Returns null when the target opposite lens is not available.
         */
        fun resolveFlippedLensOption(
            currentFacing: Int?,
            hasBack: Boolean,
            hasFront: Boolean
        ): CameraLensOption? {
            return when (currentFacing) {
                CameraSelector.LENS_FACING_FRONT -> if (hasBack) CameraLensOption.BACK else null
                CameraSelector.LENS_FACING_BACK -> if (hasFront) CameraLensOption.FRONT else null
                else -> when {
                    hasFront -> CameraLensOption.FRONT
                    hasBack -> CameraLensOption.BACK
                    else -> null
                }
            }
        }

        fun lensFacingLabel(lensFacing: Int?): String = when (lensFacing) {
            CameraSelector.LENS_FACING_BACK -> "BACK"
            CameraSelector.LENS_FACING_FRONT -> "FRONT"
            CameraSelector.LENS_FACING_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }

        fun hardwareLevelLabel(level: Int?): String = when (level) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }

        fun evaluateFocusCapabilities(
            minFocusDistance: Float?,
            afModes: IntArray?,
            maxAfRegions: Int?
        ): CameraFocusCapabilities {
            val modesList = afModes?.toList() ?: emptyList()
            val regions = maxAfRegions ?: 0
            val continuousVideo = modesList.contains(CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            val hasActiveAfMode = modesList.any { it != CameraCharacteristics.CONTROL_AF_MODE_OFF }

            return when {
                minFocusDistance == 0f || (modesList.isNotEmpty() && !hasActiveAfMode) -> {
                    CameraFocusCapabilities(
                        status = FocusSupportStatus.FIXED_FOCUS,
                        minFocusDistance = minFocusDistance,
                        afModes = modesList,
                        maxAfRegions = regions,
                        isContinuousVideoSupported = false,
                        summary = "Fixed Focus (minDist=${minFocusDistance ?: "n/a"}, afRegions=$regions)"
                    )
                }
                hasActiveAfMode -> {
                    CameraFocusCapabilities(
                        status = FocusSupportStatus.AF_SUPPORTED,
                        minFocusDistance = minFocusDistance,
                        afModes = modesList,
                        maxAfRegions = regions,
                        isContinuousVideoSupported = continuousVideo,
                        summary = if (continuousVideo) {
                            "AF Supported (CONT_VIDEO, regions=$regions)"
                        } else {
                            "AF Supported (modes=${modesList.size}, regions=$regions)"
                        }
                    )
                }
                else -> {
                    CameraFocusCapabilities(
                        status = FocusSupportStatus.UNSUPPORTED,
                        minFocusDistance = minFocusDistance,
                        afModes = modesList,
                        maxAfRegions = regions,
                        isContinuousVideoSupported = false,
                        summary = "AF Unsupported (no AF modes reported)"
                    )
                }
            }
        }
    }

    private data class SelectorBindResult(
        val previewBound: Boolean,
        val videoCaptureBound: Boolean,
        val cameraId: String = "unknown",
        val lensFacing: Int? = null,
        val hardwareLevel: Int? = null,
        val focusCapabilities: CameraFocusCapabilities = CameraFocusCapabilities(),
        val previewTier: PreviewResolutionTier = PreviewResolutionTier.HIGH_16_9,
        val selectedQuality: Quality? = null,
        val errorMessage: String? = null
    )

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var boundCamera: Camera? = null
    private var boundRecordingController: CameraRecordingController? = null
    private var observedPreviewView: PreviewView? = null
    private var streamStateObserver: Observer<PreviewView.StreamState>? = null
    private var centerAfTriggeredForSession = false

    private val _isPreviewActive = MutableStateFlow(false)
    val isPreviewActive: StateFlow<Boolean> = _isPreviewActive.asStateFlow()

    private val _isVideoCaptureBound = MutableStateFlow(false)
    val isVideoCaptureBound: StateFlow<Boolean> = _isVideoCaptureBound.asStateFlow()

    private val _bindingError = MutableStateFlow<String?>(null)
    val bindingError: StateFlow<String?> = _bindingError.asStateFlow()

    private val _previewResolution = MutableStateFlow("Pending")
    val previewResolution: StateFlow<String> = _previewResolution.asStateFlow()

    private val _isFallbackMode = MutableStateFlow(false)
    val isFallbackMode: StateFlow<Boolean> = _isFallbackMode.asStateFlow()

    private val _activeLensOption = MutableStateFlow(CameraLensOption.AUTO)
    val activeLensOption: StateFlow<CameraLensOption> = _activeLensOption.asStateFlow()

    private val _activeDiagnostics = MutableStateFlow(BoundCameraDiagnostics())
    val activeDiagnostics: StateFlow<BoundCameraDiagnostics> = _activeDiagnostics.asStateFlow()

    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    /**
     * Binds CameraX Preview (and optional VideoCapture) use cases to the provided PreviewView and LifecycleOwner.
     * Explicit requestedLens (BACK or FRONT) will never silently fall back to the opposite lens.
     */
    fun bindPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        isMirrored: Boolean = true,
        framingMode: CameraFramingMode = CameraFramingMode.WIDE_FIT,
        recordingController: CameraRecordingController? = null,
        requestedLens: CameraLensOption = _activeLensOption.value,
        onError: (String) -> Unit = {}
    ) {
        Log.i(
            TAG,
            "bindPreview started (requestedLens=$requestedLens, framingMode=$framingMode, withRecordingController=${recordingController != null}, isMirrored=$isMirrored)"
        )
        val previousLensFacing = _activeDiagnostics.value.lensFacing
        val hadBoundSession = _isPreviewActive.value && (recordingController == null || _isVideoCaptureBound.value)

        boundRecordingController = recordingController
        _isPreviewActive.value = false
        _isVideoCaptureBound.value = false
        _bindingError.value = null
        centerAfTriggeredForSession = false
        detachStreamStateObserver()
        recordingController?.clearBoundVideoCapture(clearError = true)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                provider.unbindAll()
                boundCamera = null
                previewUseCase = null

                // Keep COMPATIBLE (TextureView) for API 23 mirroring reliability and map ScaleType from framingMode
                previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                setFramingMode(previewView, framingMode)
                previewView.scaleX = if (isMirrored) -1f else 1f

                val externalSelector = buildSelectorForLensFacing(CameraSelector.LENS_FACING_EXTERNAL)
                val hasExternal = externalSelector != null && safeHasCamera(provider, externalSelector)
                val hasBack = safeHasCamera(provider, CameraSelector.DEFAULT_BACK_CAMERA)
                val hasFront = safeHasCamera(provider, CameraSelector.DEFAULT_FRONT_CAMERA)

                Log.i(TAG, "Available CameraX lenses: external=$hasExternal, back=$hasBack, front=$hasFront")

                val candidateLensFacings = selectCandidateLensFacings(
                    requestedLens = requestedLens,
                    hasExternal = hasExternal,
                    hasBack = hasBack,
                    hasFront = hasFront
                )

                if (candidateLensFacings.isEmpty()) {
                    val requestedLabel = requestedLens.name
                    val msg = if (requestedLens == CameraLensOption.AUTO) {
                        "No physical Camera2/CameraX camera device is available."
                    } else {
                        "Requested $requestedLabel camera is unavailable on this device."
                    }
                    Log.w(TAG, msg)
                    // If an explicit switch failed, restore previous working camera if available
                    if (requestedLens != CameraLensOption.AUTO && hadBoundSession && previousLensFacing != null) {
                        val restoreOption = lensOptionFromFacing(previousLensFacing)
                        if (restoreOption != requestedLens) {
                            Log.w(TAG, "Restoring previous working camera lens: $restoreOption")
                            _activeLensOption.value = restoreOption
                            bindPreview(lifecycleOwner, previewView, isMirrored, framingMode, recordingController, restoreOption) { }
                        }
                    }
                    _isPreviewActive.value = false
                    _isVideoCaptureBound.value = false
                    _bindingError.value = msg
                    _activeDiagnostics.value = _activeDiagnostics.value.copy(
                        hasBackCamera = hasBack,
                        hasFrontCamera = hasFront,
                        hasExternalCamera2 = hasExternal
                    )
                    recordingController?.markVideoCaptureBindFailed(msg)
                    onError(msg)
                    return@addListener
                }

                var result = SelectorBindResult(previewBound = false, videoCaptureBound = false)
                var chosenLensFacing: Int? = null

                for (lensFacing in candidateLensFacings) {
                    val selector = buildSelectorForLensFacing(lensFacing) ?: continue
                    Log.i(TAG, "Trying CameraX selector lensFacing=${lensFacingLabel(lensFacing)}")
                    val attempt = bindSelectorUseCases(
                        provider = provider,
                        lifecycleOwner = lifecycleOwner,
                        previewView = previewView,
                        selector = selector,
                        lensFacing = lensFacing,
                        recordingController = recordingController
                    )
                    result = attempt
                    chosenLensFacing = lensFacing

                    val satisfied = attempt.previewBound && (recordingController == null || attempt.videoCaptureBound)
                    if (satisfied) {
                        break
                    }

                    // Explicit lens request must NOT silently switch to another camera
                    if (requestedLens != CameraLensOption.AUTO) {
                        break
                    }
                }

                if (requestedLens != CameraLensOption.AUTO &&
                    (!result.previewBound || (recordingController != null && !result.videoCaptureBound)) &&
                    hadBoundSession &&
                    previousLensFacing != null
                ) {
                    val restoreOption = lensOptionFromFacing(previousLensFacing)
                    val switchErr = result.errorMessage ?: "Unable to switch to ${requestedLens.name} camera."
                    Log.w(TAG, "Explicit camera switch failed ($switchErr); restoring $restoreOption")
                    _activeLensOption.value = restoreOption
                    bindPreview(lifecycleOwner, previewView, isMirrored, framingMode, recordingController, restoreOption) { }
                    _bindingError.value = switchErr
                    onError(switchErr)
                    return@addListener
                }

                _isPreviewActive.value = result.previewBound
                _isVideoCaptureBound.value = result.videoCaptureBound
                _isFallbackMode.value = chosenLensFacing != CameraSelector.LENS_FACING_EXTERNAL
                if (chosenLensFacing != null && result.previewBound) {
                    _activeLensOption.value = lensOptionFromFacing(chosenLensFacing)
                }

                val initialPreviewRes = previewUseCase?.resolutionInfo?.let {
                    "${it.resolution.width}x${it.resolution.height}"
                } ?: "Pending"
                val initialPreviewRot = previewUseCase?.resolutionInfo?.rotationDegrees ?: 0
                if (initialPreviewRes != "Pending") {
                    _previewResolution.value = initialPreviewRes
                } else {
                    _previewResolution.value = "Pending"
                }

                val initialVideoInfo = recordingController?.updateNegotiatedResolution()
                val qualityText = CameraRecordingController.qualityLabel(recordingController?.activeVideoQuality?.value)
                val requestedQualityText = CameraRecordingController.qualityLabel(recordingController?.requestedVideoQuality?.value)
                val isDowngraded = recordingController?.isQualityDowngraded?.value ?: false

                _activeDiagnostics.value = BoundCameraDiagnostics(
                    cameraId = result.cameraId,
                    lensFacing = result.lensFacing,
                    lensLabel = lensFacingLabel(result.lensFacing),
                    hardwareLevel = result.hardwareLevel,
                    hardwareLevelLabel = hardwareLevelLabel(result.hardwareLevel),
                    previewResolution = initialPreviewRes,
                    previewRotationDegrees = initialPreviewRot,
                    previewSelectorTier = result.previewTier.label,
                    videoResolution = initialVideoInfo?.first ?: "Pending",
                    videoRotationDegrees = initialVideoInfo?.second ?: 0,
                    selectedQualityLabel = qualityText,
                    requestedQualityLabel = requestedQualityText,
                    isQualityDowngraded = isDowngraded,
                    focusCapabilities = result.focusCapabilities,
                    hasBackCamera = hasBack,
                    hasFrontCamera = hasFront,
                    hasExternalCamera2 = hasExternal,
                    lastFocusEventMessage = null
                )

                Log.i(
                    TAG,
                    "bindPreview completed: cameraId=${result.cameraId}, lens=${lensFacingLabel(result.lensFacing)}, " +
                        "hwLevel=${hardwareLevelLabel(result.hardwareLevel)}, previewTier=${result.previewTier.label}, " +
                        "previewRes=$initialPreviewRes, videoQuality=$qualityText, videoRes=${initialVideoInfo?.first ?: "Pending"}, " +
                        "downgraded=$isDowngraded, focus=${result.focusCapabilities.summary}"
                )

                if (result.previewBound) {
                    attachStreamStateObserver(lifecycleOwner, previewView, recordingController)
                }

                if (recordingController != null && !result.videoCaptureBound) {
                    val errMsg = result.errorMessage
                        ?: "VideoCapture could not be bound on this device camera."
                    _bindingError.value = errMsg
                    recordingController.markVideoCaptureBindFailed(errMsg)
                    onError(errMsg)
                } else if (!result.previewBound) {
                    val errMsg = result.errorMessage ?: "No physical camera device could be bound."
                    _bindingError.value = errMsg
                    onError(errMsg)
                } else {
                    _bindingError.value = null
                }
            } catch (e: Exception) {
                _isPreviewActive.value = false
                _isVideoCaptureBound.value = false
                _isFallbackMode.value = true
                val errMsg = "Preview initialization error: ${e.localizedMessage ?: e.message ?: "unknown error"}"
                _bindingError.value = errMsg
                recordingController?.markVideoCaptureBindFailed(errMsg)
                Log.e(TAG, "Failed to initialize CameraX preview", e)
                onError(errMsg)
            }
        }, mainExecutor)
    }

    /**
     * Switches between available BACK and FRONT cameras without altering mirroring or falling back silently.
     */
    fun switchCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        isMirrored: Boolean,
        framingMode: CameraFramingMode = CameraFramingMode.WIDE_FIT,
        recordingController: CameraRecordingController? = boundRecordingController,
        onSwitched: (CameraLensOption) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val diag = _activeDiagnostics.value
        val targetOption = resolveFlippedLensOption(
            currentFacing = diag.lensFacing,
            hasBack = diag.hasBackCamera,
            hasFront = diag.hasFrontCamera
        )
        if (targetOption == null) {
            val msg = "Cannot switch camera: only ${diag.lensLabel} camera is available on this device."
            Log.w(TAG, msg)
            _bindingError.value = msg
            onError(msg)
            return
        }
        Log.i(TAG, "Switching camera from ${diag.lensLabel} to $targetOption (preserving isMirrored=$isMirrored, framingMode=$framingMode)")
        _activeLensOption.value = targetOption
        onSwitched(targetOption)
        bindPreview(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            isMirrored = isMirrored,
            framingMode = framingMode,
            recordingController = recordingController,
            requestedLens = targetOption,
            onError = onError
        )
    }

    /**
     * Triggers center autofocus if focus metering is supported by the active camera.
     * Logs and records unsupported/fixed-focus hardware cleanly without claiming success.
     */
    fun triggerCenterAutofocus(previewView: PreviewView? = null) {
        val camera = boundCamera ?: return
        val point = if (previewView != null && previewView.width > 0 && previewView.height > 0) {
            previewView.meteringPointFactory.createPoint(previewView.width / 2f, previewView.height / 2f)
        } else {
            SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
        }
        executeFocusAndMetering(camera, point, sourceLabel = "center-auto")
    }

    /**
     * Triggers tap-to-focus using PreviewView.meteringPointFactory, guarded by isFocusMeteringSupported.
     */
    fun triggerTapToFocus(previewView: PreviewView, x: Float, y: Float): Boolean {
        val camera = boundCamera ?: return false
        val point = previewView.meteringPointFactory.createPoint(x, y)
        return executeFocusAndMetering(camera, point, sourceLabel = "tap(${x.toInt()},${y.toInt()})")
    }

    private fun executeFocusAndMetering(
        camera: Camera,
        point: androidx.camera.core.MeteringPoint,
        sourceLabel: String
    ): Boolean {
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        )
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()

        val currentDiag = _activeDiagnostics.value
        val isFixedFocus = currentDiag.focusCapabilities.status == FocusSupportStatus.FIXED_FOCUS
        val supportedByCameraX = try {
            camera.cameraInfo.isFocusMeteringSupported(action)
        } catch (e: Exception) {
            Log.w(TAG, "isFocusMeteringSupported check failed: ${e.message}")
            false
        }

        if (isFixedFocus || !supportedByCameraX) {
            val reason = if (isFixedFocus) {
                "Fixed-focus lens (AF metering unavailable)"
            } else {
                "Focus metering unsupported by HAL"
            }
            Log.i(
                TAG,
                "Autofocus ($sourceLabel) skipped for cameraId=${currentDiag.cameraId}: " +
                    "isFixedFocus=$isFixedFocus, isFocusMeteringSupported=$supportedByCameraX"
            )
            _activeDiagnostics.value = currentDiag.copy(
                focusCapabilities = currentDiag.focusCapabilities.copy(
                    isFocusMeteringSupported = supportedByCameraX
                ),
                lastFocusEventMessage = reason
            )
            return false
        }

        Log.i(TAG, "Starting focus & metering ($sourceLabel) on cameraId=${currentDiag.cameraId}")
        val future = camera.cameraControl.startFocusAndMetering(action)
        future.addListener({
            try {
                val result = future.get()
                val outcome = if (result.isFocusSuccessful) {
                    "AF locked ($sourceLabel)"
                } else {
                    "AF did not converge ($sourceLabel)"
                }
                Log.i(TAG, "FocusMeteringResult ($sourceLabel): isFocusSuccessful=${result.isFocusSuccessful}")
                _activeDiagnostics.value = _activeDiagnostics.value.copy(
                    focusCapabilities = _activeDiagnostics.value.focusCapabilities.copy(
                        isFocusMeteringSupported = true
                    ),
                    lastFocusEventMessage = outcome
                )
            } catch (e: Exception) {
                Log.w(TAG, "Focus metering ($sourceLabel) interrupted/failed: ${e.message}")
                _activeDiagnostics.value = _activeDiagnostics.value.copy(
                    lastFocusEventMessage = "AF interrupted"
                )
            }
        }, mainExecutor)
        return true
    }

    private fun attachStreamStateObserver(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        recordingController: CameraRecordingController?
    ) {
        detachStreamStateObserver()
        val observer = Observer<PreviewView.StreamState> { state ->
            if (state == PreviewView.StreamState.STREAMING) {
                val resInfo = previewUseCase?.resolutionInfo
                val previewResStr = resInfo?.let { "${it.resolution.width}x${it.resolution.height}" }
                    ?: _previewResolution.value
                val previewRot = resInfo?.rotationDegrees ?: 0
                _previewResolution.value = previewResStr

                val videoInfo = recordingController?.updateNegotiatedResolution()
                val videoResStr = videoInfo?.first ?: _activeDiagnostics.value.videoResolution
                val videoRot = videoInfo?.second ?: _activeDiagnostics.value.videoRotationDegrees

                val diag = _activeDiagnostics.value.copy(
                    previewResolution = previewResStr,
                    previewRotationDegrees = previewRot,
                    videoResolution = videoResStr,
                    videoRotationDegrees = videoRot
                )
                _activeDiagnostics.value = diag

                Log.i(
                    TAG,
                    "STREAMING initialized: cameraId=${diag.cameraId} (${diag.lensLabel}/${diag.hardwareLevelLabel}), " +
                        "preview=$previewResStr (rot=${previewRot}deg, tier=${diag.previewSelectorTier}), " +
                        "video=$videoResStr (rot=${videoRot}deg, quality=${diag.selectedQualityLabel}, downgraded=${diag.isQualityDowngraded}), " +
                        "previewView=${previewView.width}x${previewView.height}, implMode=${previewView.implementationMode}, " +
                        "scaleType=${previewView.scaleType}, mirrorScaleX=${previewView.scaleX}, focus=${diag.focusCapabilities.summary}"
                )

                if (!centerAfTriggeredForSession) {
                    centerAfTriggeredForSession = true
                    triggerCenterAutofocus(previewView)
                }
            }
        }
        observedPreviewView = previewView
        streamStateObserver = observer
        previewView.previewStreamState.observe(lifecycleOwner, observer)
    }

    private fun detachStreamStateObserver() {
        val view = observedPreviewView
        val obs = streamStateObserver
        if (view != null && obs != null) {
            try {
                view.previewStreamState.removeObserver(obs)
            } catch (_: Exception) {}
        }
        observedPreviewView = null
        streamStateObserver = null
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun inspectCamera2Info(cameraInfo: CameraInfo): Triple<String, Int?, CameraFocusCapabilities> {
        return try {
            val c2Info = Camera2CameraInfo.from(cameraInfo)
            val cameraId = c2Info.cameraId
            val hwLevel = c2Info.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            val minFocusDistance = c2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            val afModes = c2Info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            val maxAfRegions = c2Info.getCameraCharacteristic(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)
            val focusCaps = evaluateFocusCapabilities(minFocusDistance, afModes, maxAfRegions)
            Triple(cameraId, hwLevel, focusCaps)
        } catch (e: Exception) {
            Log.w(TAG, "Camera2CameraInfo inspection failed: ${e.message}")
            Triple("unknown", null, CameraFocusCapabilities())
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildPreviewUseCase(
        tier: PreviewResolutionTier,
        focusCaps: CameraFocusCapabilities,
        previewView: PreviewView
    ): Preview {
        val builder = Preview.Builder()
            .setResolutionSelector(buildPreviewResolutionSelector(tier))

        if (focusCaps.isContinuousVideoSupported) {
            try {
                Camera2Interop.Extender(builder)
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    )
            } catch (e: Exception) {
                Log.w(TAG, "Unable to set CONTROL_AF_MODE_CONTINUOUS_VIDEO: ${e.message}")
            }
        }

        return builder.build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
    }

    private fun bindSelectorUseCases(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        selector: CameraSelector,
        lensFacing: Int,
        recordingController: CameraRecordingController?
    ): SelectorBindResult {
        val cameraInfo = try {
            provider.getCameraInfo(selector)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to inspect CameraInfo for selector: ${e.message}", e)
            null
        }

        val (cameraId, hwLevel, focusCaps) = if (cameraInfo != null) {
            inspectCamera2Info(cameraInfo)
        } else {
            Triple("unknown", null, CameraFocusCapabilities())
        }
        Log.i(
            TAG,
            "Inspecting cameraId=$cameraId, lens=${lensFacingLabel(lensFacing)}, " +
                "hwLevel=${hardwareLevelLabel(hwLevel)}, focus=${focusCaps.summary}"
        )

        if (recordingController == null) {
            for (tier in PreviewResolutionTier.entries) {
                val preview = buildPreviewUseCase(tier, focusCaps, previewView)
                try {
                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview)
                    previewUseCase = preview
                    boundCamera = camera
                    return SelectorBindResult(
                        previewBound = true,
                        videoCaptureBound = false,
                        cameraId = cameraId,
                        lensFacing = lensFacing,
                        hardwareLevel = hwLevel,
                        focusCapabilities = focusCaps,
                        previewTier = tier
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Preview-only bind failed at tier=${tier.label}: ${e.message}")
                }
            }
            return SelectorBindResult(
                previewBound = false,
                videoCaptureBound = false,
                cameraId = cameraId,
                lensFacing = lensFacing,
                hardwareLevel = hwLevel,
                focusCapabilities = focusCaps,
                errorMessage = "Preview bind failed on camera $cameraId."
            )
        }

        val supportedQualities: List<Quality> = if (cameraInfo != null) {
            recordingController.querySupportedVideoQualities(cameraInfo)
        } else {
            emptyList()
        }

        if (supportedQualities.isEmpty()) {
            var previewOnly = false
            var boundTier = PreviewResolutionTier.COMPAT_4_3_FALLBACK
            for (tier in PreviewResolutionTier.entries) {
                val preview = buildPreviewUseCase(tier, focusCaps, previewView)
                try {
                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview)
                    previewUseCase = preview
                    boundCamera = camera
                    previewOnly = true
                    boundTier = tier
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Preview-only bind failed when no video quality supported (tier=${tier.label}): ${e.message}")
                }
            }
            return SelectorBindResult(
                previewBound = previewOnly,
                videoCaptureBound = false,
                cameraId = cameraId,
                lensFacing = lensFacing,
                hardwareLevel = hwLevel,
                focusCapabilities = focusCaps,
                previewTier = boundTier,
                errorMessage = "Camera $cameraId does not report any supported video recording quality."
            )
        }

        val preferredQuality = supportedQualities.firstOrNull() ?: Quality.HD
        var lastBindError: Throwable? = null

        // Preserve VideoCapture quality by trying all Preview ResolutionSelector tiers before lowering video quality
        for (quality in supportedQualities) {
            val qualityName = CameraRecordingController.qualityLabel(quality)
            for (tier in PreviewResolutionTier.entries) {
                val preview = buildPreviewUseCase(tier, focusCaps, previewView)
                val freshCapture = recordingController.createFreshVideoCapture(quality)
                try {
                    Log.i(
                        TAG,
                        "Binding Preview(${tier.label})+VideoCapture($qualityName) on cameraId=$cameraId"
                    )
                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, freshCapture)
                    previewUseCase = preview
                    boundCamera = camera
                    ensureMinimumZoomBaseline(camera)
                    recordingController.markVideoCaptureBound(
                        capture = freshCapture,
                        selectedQuality = quality,
                        preferredQuality = if (supportedQualities.contains(Quality.HD)) Quality.HD else preferredQuality
                    )
                    Log.i(
                        TAG,
                        "Bound Preview(${tier.label})+VideoCapture($qualityName) on cameraId=$cameraId"
                    )
                    return SelectorBindResult(
                        previewBound = true,
                        videoCaptureBound = true,
                        cameraId = cameraId,
                        lensFacing = lensFacing,
                        hardwareLevel = hwLevel,
                        focusCapabilities = focusCaps,
                        previewTier = tier,
                        selectedQuality = quality
                    )
                } catch (e: Exception) {
                    lastBindError = e
                    Log.w(
                        TAG,
                        "Concurrent Preview(${tier.label})+VideoCapture($qualityName) failed on cameraId=$cameraId: ${e.message}"
                    )
                    try {
                        provider.unbindAll()
                    } catch (_: Exception) {}
                }
            }
        }

        // VideoCapture failed for all supported qualities; bind Preview only for visibility but report failure
        var previewOnly = false
        var boundTier = PreviewResolutionTier.COMPAT_4_3_FALLBACK
        for (tier in PreviewResolutionTier.entries) {
            val preview = buildPreviewUseCase(tier, focusCaps, previewView)
            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview)
                previewUseCase = preview
                boundCamera = camera
                previewOnly = true
                boundTier = tier
                break
            } catch (e: Exception) {
                Log.w(TAG, "Preview-only fallback bind failed (tier=${tier.label}): ${e.message}")
            }
        }
        Log.w(TAG, "Preview-only fallback state on cameraId=$cameraId: previewBound=$previewOnly, videoCaptureBound=false")

        val sanitizedCause = CameraRecordingController.sanitizeDiagnosticCause(lastBindError)
            ?: "unsupported stream combination"
        val reason = "Camera $cameraId preview is active, but video recording (VideoCapture) could not bind ($sanitizedCause)."
        return SelectorBindResult(
            previewBound = previewOnly,
            videoCaptureBound = false,
            cameraId = cameraId,
            lensFacing = lensFacing,
            hardwareLevel = hwLevel,
            focusCapabilities = focusCaps,
            previewTier = boundTier,
            errorMessage = reason
        )
    }

    private fun buildSelectorForLensFacing(lensFacing: Int): CameraSelector? {
        return when (lensFacing) {
            CameraSelector.LENS_FACING_BACK -> CameraSelector.DEFAULT_BACK_CAMERA
            CameraSelector.LENS_FACING_FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> try {
                CameraSelector.Builder().requireLensFacing(lensFacing).build()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun lensOptionFromFacing(lensFacing: Int?): CameraLensOption = when (lensFacing) {
        CameraSelector.LENS_FACING_BACK -> CameraLensOption.BACK
        CameraSelector.LENS_FACING_FRONT -> CameraLensOption.FRONT
        else -> CameraLensOption.AUTO
    }

    private fun safeHasCamera(provider: ProcessCameraProvider, selector: CameraSelector): Boolean {
        return try {
            provider.hasCamera(selector)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Updates the preview mirroring dynamically without switching physical cameras.
     */
    fun setMirrored(previewView: PreviewView, isMirrored: Boolean) {
        previewView.scaleX = if (isMirrored) -1f else 1f
    }

    /**
     * Updates preview scaling between Portrait Crop (FILL_CENTER) and Wide View (FIT_CENTER)
     * without rebinding CameraX Preview or VideoCapture use cases.
     */
    fun setFramingMode(previewView: PreviewView, framingMode: CameraFramingMode) {
        val targetScaleType = when (framingMode) {
            CameraFramingMode.PORTRAIT_CROP -> PreviewView.ScaleType.FILL_CENTER
            CameraFramingMode.WIDE_FIT -> PreviewView.ScaleType.FIT_CENTER
        }
        if (previewView.scaleType != targetScaleType) {
            previewView.scaleType = targetScaleType
            Log.i(TAG, "Updated PreviewView.scaleType=$targetScaleType for framingMode=$framingMode")
        }
    }

    private fun ensureMinimumZoomBaseline(camera: Camera) {
        try {
            val zoomState = camera.cameraInfo.zoomState.value
            if (zoomState != null) {
                Log.i(
                    TAG,
                    "Camera zoomState baseline: ratio=${zoomState.zoomRatio}, " +
                        "min=${zoomState.minZoomRatio}, max=${zoomState.maxZoomRatio}"
                )
                if (zoomState.zoomRatio > zoomState.minZoomRatio + 0.01f) {
                    camera.cameraControl.setLinearZoom(0f)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Zoom baseline check skipped: ${e.message}")
        }
    }

    /**
     * Unbinds the camera preview and VideoCapture use cases and resets stale references.
     */
    fun unbind() {
        Log.i(TAG, "unbind() clearing Preview, Camera, and VideoCapture references")
        detachStreamStateObserver()
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding camera preview", e)
        } finally {
            previewUseCase = null
            boundCamera = null
            centerAfTriggeredForSession = false
            _isPreviewActive.value = false
            _isVideoCaptureBound.value = false
            _bindingError.value = null
            _previewResolution.value = "Pending"
            _activeDiagnostics.value = BoundCameraDiagnostics()
            boundRecordingController?.clearBoundVideoCapture(clearError = true)
            boundRecordingController = null
        }
    }
}
