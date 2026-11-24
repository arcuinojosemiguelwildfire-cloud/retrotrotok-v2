package com.example.model

import com.example.camera.CameraBackendType
import com.example.camera.CameraConnectionState

enum class ValidationStatus {
    PENDING,
    RUNNING,
    PASSED,
    FAILED,
    WARNING
}

data class HardwareValidationCheck(
    val id: String,
    val title: String,
    val description: String,
    val status: ValidationStatus,
    val diagnosticDetails: String = "",
    val isHardwareDependent: Boolean = true
)

data class HardwareDiagnosticState(
    val isWebcamConnected: Boolean = false,
    val detectedDeviceName: String = "Checking camera hardware...",
    val boundCameraSummary: String = "Not bound yet",
    val cameraHardwareLevel: String = "UNKNOWN",
    val connectionState: CameraConnectionState = CameraConnectionState.NOT_TESTED,
    val selectedBackend: CameraBackendType = CameraBackendType.DEV_PREVIEW,
    val isUsbHostPresent: Boolean = false,
    val isExternalCameraHalPresent: Boolean = false,
    val usbUvcCaptureSupported: Boolean = false,
    val usbDevicesCount: Int = 0,
    val activeResolution: String = "Not bound",
    val previewResolution: String = "Not bound",
    val videoResolution: String = "Not bound",
    val focusCapabilitySummary: String = "Pending CameraX bind",
    val activeFps: Int = 30,
    val encoderMime: String = "video/avc",
    val isHardwareEncoder: Boolean = true,
    val checks: List<HardwareValidationCheck> = emptyList(),
    val isRunningDiagnostics: Boolean = false,
    val lastTestTimestamp: Long = 0L
)
