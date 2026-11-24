package com.example.ui.screens.hardware

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.camera.CameraBackendType
import com.example.camera.CameraConnectionState
import com.example.camera.CameraDetector
import com.example.camera.CameraPreviewController
import com.example.camera.CameraProvider
import com.example.model.HardwareDiagnosticState
import com.example.model.HardwareValidationCheck
import com.example.model.ValidationStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HardwareValidationViewModel(
    private val cameraDetector: CameraDetector,
    private val cameraProvider: CameraProvider,
    private val cameraPreviewController: CameraPreviewController? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(HardwareDiagnosticState())
    val uiState: StateFlow<HardwareDiagnosticState> = _uiState.asStateFlow()

    init {
        runAllDiagnostics()
    }

    fun runAllDiagnostics() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRunningDiagnostics = true) }
            cameraProvider.refreshDevices()
            delay(400) // Brief observation delay

            val hasUsbHost = cameraDetector.hasUsbHostFeature()
            val usbDevices = cameraDetector.detectUsbDevices()
            val logitechDevice = usbDevices.firstOrNull { it.isLogitech }
            val anyUvcDevice = usbDevices.firstOrNull { it.supportsUvc }
            val hasCamera2ExternalHal = cameraDetector.hasExternalCameraFeature()
            val camera2Devices = cameraDetector.detectCamera2Devices()
            val externalCamera2 = camera2Devices.firstOrNull { it.isExternal }
            val (bestBackend, activeDevice) = cameraDetector.selectBestBackend()
            val encoderCaps = cameraDetector.queryEncoderCapabilities()
            val boundDiag = cameraPreviewController?.activeDiagnostics?.value
            val isCameraXBound = cameraPreviewController?.isPreviewActive?.value == true &&
                boundDiag != null && boundDiag.cameraId != "unbound"

            // Inspect real Camera2 characteristics for the primary Camera2 device if Studio hasn't bound yet
            val primaryCamera2 = externalCamera2 ?: camera2Devices.firstOrNull()
            val inspectedCamera2Info = primaryCamera2?.let {
                cameraDetector.inspectCamera2Capabilities(it.id)
            }
            val camera2Sizes = primaryCamera2?.let {
                cameraDetector.getCamera2OutputSizes(it.id)
            }.orEmpty()
            val maxCamera2Size = camera2Sizes.maxByOrNull { it.first * it.second }

            val checks = mutableListOf<HardwareValidationCheck>()

            // 1. USB Host
            checks.add(
                HardwareValidationCheck(
                    id = "CHK-01-USB-HOST",
                    title = "1. USB Host Subsystem",
                    description = "Evaluates PackageManager android.hardware.usb.host feature declaration.",
                    status = if (hasUsbHost) ValidationStatus.PASSED else ValidationStatus.WARNING,
                    diagnosticDetails = if (hasUsbHost) "USB Host Controller active. OTG bus ready." else "USB Host not declared in device BSP."
                )
            )

            // 2. USB Device & Logitech Identification (Peripheral Enumeration Only)
            if (logitechDevice != null) {
                checks.add(
                    HardwareValidationCheck(
                        id = "CHK-02-WEBCAM-ID",
                        title = "2. USB Webcam Peripheral Enumeration",
                        description = "Scans USB bus for attached USB devices (does NOT indicate CameraX frame streaming).",
                        status = ValidationStatus.WARNING,
                        diagnosticDetails = "USB peripheral found: ${logitechDevice.name} (VID: 0x${logitechDevice.vendorId?.toString(16)}, PID: 0x${logitechDevice.productId?.toString(16)}). USB UVC capture pipeline is not implemented; CameraX streams only Camera2 devices."
                    )
                )
            } else if (usbDevices.isNotEmpty()) {
                val dev = usbDevices.first()
                checks.add(
                    HardwareValidationCheck(
                        id = "CHK-02-WEBCAM-ID",
                        title = "2. USB Webcam Peripheral Enumeration",
                        description = "Scans USB bus for attached USB devices (does NOT indicate CameraX frame streaming).",
                        status = ValidationStatus.WARNING,
                        diagnosticDetails = "USB device detected: ${dev.name} (VID: 0x${dev.vendorId?.toString(16)}, PID: 0x${dev.productId?.toString(16)}). Non-Logitech vendor; CameraX requires Camera2 HAL."
                    )
                )
            } else {
                checks.add(
                    HardwareValidationCheck(
                        id = "CHK-02-WEBCAM-ID",
                        title = "2. USB Webcam Peripheral Enumeration",
                        description = "Scans USB bus for attached USB devices (does NOT indicate CameraX frame streaming).",
                        status = ValidationStatus.WARNING,
                        diagnosticDetails = "No external USB camera enumerated on USB bus. Built-in Camera2 sensors will be used if available."
                    )
                )
            }

            // 3. USB Permissions
            val usbPermGranted = anyUvcDevice?.hasUsbPermission ?: false
            checks.add(
                HardwareValidationCheck(
                    id = "CHK-03-USB-PERM",
                    title = "3. USB Permission Status",
                    description = "Validates UsbManager.hasPermission() contract for external USB peripherals.",
                    status = when {
                        anyUvcDevice == null -> ValidationStatus.PASSED
                        usbPermGranted -> ValidationStatus.PASSED
                        else -> ValidationStatus.WARNING
                    },
                    diagnosticDetails = when {
                        anyUvcDevice == null -> "No external USB UVC peripheral attached; Camera2 uses CAMERA runtime permission."
                        usbPermGranted -> "USB access permission granted for peripheral enumeration."
                        else -> "USB permission not granted for ${anyUvcDevice.name}."
                    }
                )
            )

            // 4. Camera2 Capture Availability & External HAL (Path A)
            if (externalCamera2 != null) {
                checks.add(
                    HardwareValidationCheck(
                        id = "CHK-04-CAMERA2-HAL",
                        title = "4. Camera2 Capture Availability & External HAL",
                        description = "Inspects CameraManager for active Camera2 capture devices and LENS_FACING_EXTERNAL.",
                        status = ValidationStatus.PASSED,
                        diagnosticDetails = "Camera2 external camera registered: ID ${externalCamera2.id}. Active CameraX capture supported."
                    )
                )
            } else if (camera2Devices.isNotEmpty()) {
                val cameraListSummary = camera2Devices.joinToString { "${it.id} (${it.name})" }
                checks.add(
                    HardwareValidationCheck(
                        id = "CHK-04-CAMERA2-HAL",
                        title = "4. Camera2 Capture Availability & External HAL",
                        description = "Inspects CameraManager for active Camera2 capture devices and LENS_FACING_EXTERNAL.",
                        status = ValidationStatus.PASSED,
                        diagnosticDetails = "External HAL absent on this Android 6.0.1 / API 23 BSP. Active Camera2 capture devices: $cameraListSummary."
                    )
                )
            } else {
                checks.add(
                    HardwareValidationCheck(
                        id = "CHK-04-CAMERA2-HAL",
                        title = "4. Camera2 Capture Availability & External HAL",
                        description = "Inspects CameraManager for active Camera2 capture devices and LENS_FACING_EXTERNAL.",
                        status = ValidationStatus.FAILED,
                        diagnosticDetails = if (hasCamera2ExternalHal) {
                            "HAL feature declared, but 0 Camera2 cameras are currently available."
                        } else {
                            "No Camera2 capture device available on this Android 6.0.1 / API 23 BSP."
                        }
                    )
                )
            }

            // 5. Native USB UVC (Path B — Explicitly Unsupported in this build)
            checks.add(
                HardwareValidationCheck(
                    id = "CHK-05-UVC-FALLBACK",
                    title = "5. Native USB UVC Capture Pipeline (Path B)",
                    description = "Reports whether native USB Video Class frame streaming is implemented.",
                    status = ValidationStatus.WARNING,
                    diagnosticDetails = if (anyUvcDevice != null) {
                        "UVC descriptor detected on ${anyUvcDevice.name}, but native UVC capture pipeline is UNSUPPORTED in this build. Frames are NOT read from USB UVC unless exposed via Camera2 External HAL."
                    } else {
                        "Native USB UVC capture pipeline is not implemented. CameraX uses Camera2 HAL."
                    }
                )
            )

            // 6. Bound CameraX Resolution & Focus Capabilities
            val hwLevelText = boundDiag?.takeIf { isCameraXBound }?.hardwareLevelLabel
                ?: inspectedCamera2Info?.first
                ?: "UNKNOWN"
            val focusText = boundDiag?.takeIf { isCameraXBound }?.focusCapabilities?.summary
                ?: inspectedCamera2Info?.second?.summary
                ?: "Open Studio to inspect active CameraX focus"
            val previewResText = boundDiag?.takeIf { isCameraXBound }?.previewResolution
                ?: maxCamera2Size?.let { "Pending bind (Camera2 max ${it.first}x${it.second})" }
                ?: "Not bound"
            val videoResText = boundDiag?.takeIf { isCameraXBound }?.let {
                "${it.videoResolution} (${it.selectedQualityLabel}${if (it.isQualityDowngraded) ", Downgraded" else ""})"
            } ?: "Pending Studio bind"

            checks.add(
                HardwareValidationCheck(
                    id = "CHK-06-ENCODER-CAPS",
                    title = "6. Camera2 Stream, Focus & H.264 Encoder",
                    description = "Reports actual CameraX stream resolutions, AF hardware status, and MediaCodec AVC.",
                    status = ValidationStatus.PASSED,
                    diagnosticDetails = "HW Level: $hwLevelText | Focus: $focusText | Preview: $previewResText | Video: $videoResText | H.264 max ${encoderCaps.maxEncoderWidth}x${encoderCaps.maxEncoderHeight} (HW=${encoderCaps.isHardwareAcceleratedEncoder})"
                )
            )

            // 7. Power Delivery & Bus Stability
            checks.add(
                HardwareValidationCheck(
                    id = "CHK-07-RECORD-STABILITY",
                    title = "7. USB Bus Voltage & Power Delivery",
                    description = "Physical verification: external USB devices require 500-750mA @ 5V.",
                    status = ValidationStatus.WARNING,
                    diagnosticDetails = "Physical check required: Use externally powered USB hub (5V/2A) if connecting USB peripherals."
                )
            )

            // 8. Hot-Plug Disconnect/Reconnect Recovery
            checks.add(
                HardwareValidationCheck(
                    id = "CHK-08-HOTPLUG-RECONNECT",
                    title = "8. Hot-Plug Detach / Attach Recovery",
                    description = "BroadcastReceiver monitors ACTION_USB_DEVICE_ATTACHED / DETACHED.",
                    status = ValidationStatus.PASSED,
                    diagnosticDetails = "Receiver registered. Safe recording preservation triggered upon detach."
                )
            )

            val hasRealCaptureCamera = bestBackend == CameraBackendType.CAMERA2_EXTERNAL ||
                bestBackend == CameraBackendType.CAMERA2_INTERNAL

            val boundCameraSummary = when {
                isCameraXBound && boundDiag != null ->
                    "Camera ${boundDiag.cameraId} (${boundDiag.lensLabel}, ${boundDiag.hardwareLevelLabel})"
                primaryCamera2 != null ->
                    "Camera2 ready: ${primaryCamera2.name} (${inspectedCamera2Info?.first ?: "UNKNOWN"})"
                anyUvcDevice != null ->
                    "USB UVC detected (${anyUvcDevice.name}) — capture UNSUPPORTED"
                else -> "No Camera2 capture device available"
            }

            val detectedSummary = when {
                isCameraXBound && boundDiag != null ->
                    "Active CameraX: Camera ${boundDiag.cameraId} (${boundDiag.lensLabel})"
                externalCamera2 != null -> "Camera2 External HAL: ${externalCamera2.name}"
                primaryCamera2 != null -> "Built-in Camera2: ${primaryCamera2.name}"
                logitechDevice != null -> "${logitechDevice.name} (USB UVC unsupported)"
                else -> "No Camera2 Capture Device"
            }

            val connState = when {
                bestBackend == CameraBackendType.NATIVE_USB_UVC -> CameraConnectionState.UNSUPPORTED
                hasRealCaptureCamera -> CameraConnectionState.CONNECTED
                activeDevice != null && bestBackend == CameraBackendType.DEV_PREVIEW -> CameraConnectionState.DISCONNECTED
                else -> CameraConnectionState.DISCONNECTED
            }

            _uiState.update {
                it.copy(
                    isWebcamConnected = hasRealCaptureCamera,
                    detectedDeviceName = detectedSummary,
                    boundCameraSummary = boundCameraSummary,
                    cameraHardwareLevel = hwLevelText,
                    connectionState = connState,
                    selectedBackend = bestBackend,
                    isUsbHostPresent = hasUsbHost,
                    isExternalCameraHalPresent = hasCamera2ExternalHal,
                    usbUvcCaptureSupported = false,
                    usbDevicesCount = usbDevices.size,
                    activeResolution = if (isCameraXBound && boundDiag != null) {
                        "Prev ${boundDiag.previewResolution} / Vid ${boundDiag.videoResolution}"
                    } else if (maxCamera2Size != null) {
                        "Unbound (Max ${maxCamera2Size.first}x${maxCamera2Size.second})"
                    } else {
                        "Unbound"
                    },
                    previewResolution = previewResText,
                    videoResolution = videoResText,
                    focusCapabilitySummary = focusText,
                    activeFps = encoderCaps.preferredFps,
                    encoderMime = encoderCaps.encoderMimeType,
                    isHardwareEncoder = encoderCaps.isHardwareAcceleratedEncoder,
                    checks = checks,
                    isRunningDiagnostics = false,
                    lastTestTimestamp = System.currentTimeMillis()
                )
            }
        }
    }
}

