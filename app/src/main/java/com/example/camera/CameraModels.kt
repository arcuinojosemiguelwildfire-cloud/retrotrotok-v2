package com.example.camera

/**
 * Camera backend implementations supported by RetroTok.
 */
enum class CameraBackendType {
    /**
     * Standard Android Camera2 / CameraX external camera HAL (Preferred Path A).
     */
    CAMERA2_EXTERNAL,

    /**
     * Standard Android Camera2 / CameraX on-board camera (fallback if external HAL not available).
     */
    CAMERA2_INTERNAL,

    /**
     * Native USB Video Class (UVC) via Android USB Host API (Fallback Path B).
     */
    NATIVE_USB_UVC,

    /**
     * Development preview mode when physical webcam is not attached (e.g. emulator/testing).
     */
    DEV_PREVIEW
}

/**
 * Connection states for camera hardware.
 */
enum class CameraConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    PERMISSION_REQUIRED,
    UNSUPPORTED,
    ERROR,
    NOT_TESTED
}

/**
 * Information describing a detected physical or system camera.
 */
data class CameraDeviceInfo(
    val id: String,
    val name: String,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val isLogitech: Boolean = false,
    val lensFacing: Int? = null,
    val isExternal: Boolean = false,
    val backend: CameraBackendType = CameraBackendType.DEV_PREVIEW,
    val supportsUvc: Boolean = false,
    val hasUsbPermission: Boolean = true,
    val manufacturerName: String? = null,
    val productName: String? = null
)

/**
 * Hardware capture and encoding capabilities queried from CameraCharacteristics & MediaCodecList.
 */
data class CameraCapabilities(
    val supportedResolutions: List<Pair<Int, Int>> = listOf(
        Pair(1280, 720),
        Pair(1920, 1080),
        Pair(640, 480)
    ),
    val supportedFps: List<Int> = listOf(30, 24, 15),
    val preferredResolution: Pair<Int, Int> = Pair(1280, 720),
    val preferredFps: Int = 30,
    val isHardwareAcceleratedEncoder: Boolean = true,
    val encoderMimeType: String = "video/avc",
    val maxEncoderWidth: Int = 1920,
    val maxEncoderHeight: Int = 1080
)

/**
 * Recording configuration for Dance Studio sessions.
 */
data class RecordingConfig(
    val durationSeconds: Int = 30,
    val targetWidth: Int = 720,
    val targetHeight: Int = 1280,
    val targetFps: Int = 30,
    val isMirrored: Boolean = true,
    val recordAudioFromMic: Boolean = false // Crucial: mute webcam mic by default
)

/**
 * Target CameraX lens option for Dance Studio preview & recording.
 */
enum class CameraLensOption {
    AUTO,
    BACK,
    FRONT
}

/**
 * Hardware autofocus capability classification for the active CameraX device.
 */
enum class FocusSupportStatus {
    UNKNOWN,
    AF_SUPPORTED,
    FIXED_FOCUS,
    UNSUPPORTED
}

/**
 * Inspected autofocus & focus-metering hardware capabilities.
 */
data class CameraFocusCapabilities(
    val status: FocusSupportStatus = FocusSupportStatus.UNKNOWN,
    val minFocusDistance: Float? = null,
    val afModes: List<Int> = emptyList(),
    val maxAfRegions: Int = 0,
    val isContinuousVideoSupported: Boolean = false,
    val isFocusMeteringSupported: Boolean? = null,
    val summary: String = "Pending"
)

/**
 * Live diagnostics reported by CameraPreviewController & CameraRecordingController
 * after CameraX binding and stream initialization.
 */
data class BoundCameraDiagnostics(
    val cameraId: String = "unbound",
    val lensFacing: Int? = null,
    val lensLabel: String = "Unbound",
    val hardwareLevel: Int? = null,
    val hardwareLevelLabel: String = "UNKNOWN",
    val previewResolution: String = "Pending",
    val previewRotationDegrees: Int = 0,
    val previewSelectorTier: String = "Pending",
    val videoResolution: String = "Pending",
    val videoRotationDegrees: Int = 0,
    val selectedQualityLabel: String = "UNKNOWN",
    val requestedQualityLabel: String = "HD",
    val isQualityDowngraded: Boolean = false,
    val focusCapabilities: CameraFocusCapabilities = CameraFocusCapabilities(),
    val hasBackCamera: Boolean = false,
    val hasFrontCamera: Boolean = false,
    val hasExternalCamera2: Boolean = false,
    val lastFocusEventMessage: String? = null
)

