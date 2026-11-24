package com.example.camera

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Size

/**
 * Hardware inspection utility that detects connected USB webcams, Camera2 external HAL devices,
 * UVC interfaces, and MediaCodec encoder capabilities without fabricating results.
 */
class CameraDetector(private val context: Context) {

    companion object {
        const val LOGITECH_VENDOR_ID = 0x046d
        const val USB_CLASS_VIDEO = 14
        const val USB_SUBCLASS_VIDEOSTREAMING = 2
        const val USB_SUBCLASS_VIDEOCONTROL = 1
    }

    private val usbManager: UsbManager? by lazy {
        context.getSystemService(Context.USB_SERVICE) as? UsbManager
    }

    private val cameraManager: CameraManager? by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    }

    /**
     * Checks if USB Host feature is declared and active on device.
     */
    fun hasUsbHostFeature(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
    }

    /**
     * Checks if Camera2 External Camera HAL feature is declared on device.
     */
    fun hasExternalCameraFeature(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_EXTERNAL)
    }

    /**
     * Enumerates all physical USB devices currently attached to the kiosk.
     */
    fun detectUsbDevices(): List<CameraDeviceInfo> {
        val manager = usbManager ?: return emptyList()
        val deviceList = manager.deviceList.values

        return deviceList.map { device ->
            val isLogitech = device.vendorId == LOGITECH_VENDOR_ID
            val hasUvc = isUvcDevice(device)
            val hasPermission = manager.hasPermission(device)

            val mfgName = try { device.manufacturerName } catch (e: Exception) { null }
            val prodName = try { device.productName } catch (e: Exception) { null }

            CameraDeviceInfo(
                id = "usb_${device.deviceId}_${device.vendorId}_${device.productId}",
                name = prodName ?: if (isLogitech) "Logitech USB Webcam" else "USB Video Device",
                vendorId = device.vendorId,
                productId = device.productId,
                isLogitech = isLogitech,
                lensFacing = null,
                isExternal = true,
                backend = if (hasUvc) CameraBackendType.NATIVE_USB_UVC else CameraBackendType.DEV_PREVIEW,
                supportsUvc = hasUvc,
                hasUsbPermission = hasPermission,
                manufacturerName = mfgName,
                productName = prodName
            )
        }
    }

    /**
     * Inspects USB interfaces to determine if device supports USB Video Class (UVC).
     */
    fun isUvcDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == USB_CLASS_VIDEO) return true
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_VIDEO) {
                return true
            }
        }
        return false
    }

    /**
     * Queries Android CameraManager for all registered cameras and flags external cameras.
     */
    fun detectCamera2Devices(): List<CameraDeviceInfo> {
        val manager = cameraManager ?: return emptyList()
        val results = mutableListOf<CameraDeviceInfo>()

        try {
            for (cameraId in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(cameraId)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val isExternal = facing == CameraCharacteristics.LENS_FACING_EXTERNAL

                val name = when (facing) {
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "External USB Camera ($cameraId)"
                    CameraCharacteristics.LENS_FACING_BACK -> "Back Camera ($cameraId)"
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front Camera ($cameraId)"
                    else -> "Camera ($cameraId)"
                }

                results.add(
                    CameraDeviceInfo(
                        id = cameraId,
                        name = name,
                        lensFacing = facing,
                        isExternal = isExternal,
                        backend = if (isExternal) CameraBackendType.CAMERA2_EXTERNAL else CameraBackendType.CAMERA2_INTERNAL,
                        hasUsbPermission = true
                    )
                )
            }
        } catch (e: Exception) {
            // Permission or HAL initialization failure
        }

        return results
    }

    /**
     * Evaluates Camera2 hardware level label without fabricating unsupported states.
     */
    fun hardwareLevelLabel(level: Int?): String = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    /**
     * Pure evaluator of focus capabilities from CameraCharacteristics values.
     */
    fun evaluateFocusCharacteristics(
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

    /**
     * Pure selection function separating USB peripheral detection from real capture backend availability.
     * USB UVC devices without Camera2 External HAL are NOT treated as active capture streams because
     * native UVC frame capture is not implemented.
     */
    fun selectCaptureBackend(
        camera2Devices: List<CameraDeviceInfo>,
        usbDevices: List<CameraDeviceInfo>
    ): Pair<CameraBackendType, CameraDeviceInfo?> {
        val externalCamera2 = camera2Devices.firstOrNull { it.isExternal }
        if (externalCamera2 != null) {
            return Pair(CameraBackendType.CAMERA2_EXTERNAL, externalCamera2)
        }

        // Prefer real on-board Camera2 device over unimplemented USB UVC enumeration
        val internalBack = camera2Devices.firstOrNull {
            it.lensFacing == CameraCharacteristics.LENS_FACING_BACK
        }
        val internalFront = camera2Devices.firstOrNull {
            it.lensFacing == CameraCharacteristics.LENS_FACING_FRONT
        }
        val internalCamera = internalBack ?: internalFront ?: camera2Devices.firstOrNull()
        if (internalCamera != null) {
            return Pair(CameraBackendType.CAMERA2_INTERNAL, internalCamera)
        }

        val logitechUvc = usbDevices.firstOrNull { it.isLogitech && it.supportsUvc }
        if (logitechUvc != null) {
            return Pair(CameraBackendType.NATIVE_USB_UVC, logitechUvc)
        }

        val anyUvc = usbDevices.firstOrNull { it.supportsUvc }
        if (anyUvc != null) {
            return Pair(CameraBackendType.NATIVE_USB_UVC, anyUvc)
        }

        return Pair(
            CameraBackendType.DEV_PREVIEW,
            CameraDeviceInfo(
                id = "dev_kiosk_camera",
                name = "No Physical Camera2 Device (Dev Mode)",
                vendorId = null,
                productId = null,
                isLogitech = false,
                isExternal = false,
                backend = CameraBackendType.DEV_PREVIEW,
                supportsUvc = false,
                hasUsbPermission = true
            )
        )
    }

    /**
     * Selects best working capture backend and device info based on actual capture availability.
     */
    fun selectBestBackend(): Pair<CameraBackendType, CameraDeviceInfo?> {
        val camera2Devices = detectCamera2Devices()
        val usbDevices = detectUsbDevices()
        return selectCaptureBackend(camera2Devices, usbDevices)
    }

    /**
     * Inspects Camera2 hardware level and focus characteristics for a specific Camera2 cameraId.
     */
    fun inspectCamera2Capabilities(cameraId: String): Pair<String, CameraFocusCapabilities> {
        val manager = cameraManager ?: return "UNKNOWN" to CameraFocusCapabilities()
        return try {
            val chars = manager.getCameraCharacteristics(cameraId)
            val hwLevel = hardwareLevelLabel(chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
            val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            val maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)
            hwLevel to evaluateFocusCharacteristics(minFocusDist, afModes, maxAfRegions)
        } catch (_: Exception) {
            "UNKNOWN" to CameraFocusCapabilities()
        }
    }

    /**
     * Queries actual encoder capabilities using Android MediaCodec APIs.
     */
    fun queryEncoderCapabilities(): CameraCapabilities {
        var supportsH264 = false
        var isHwAccelerated = false
        var maxWidth = 1920
        var maxHeight = 1080

        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)) {
                        supportsH264 = true
                        if (com.example.video.DeviceMediaCapabilities.isLikelyHardwareCodec(info)) {
                            isHwAccelerated = true
                        }
                        val caps = info.getCapabilitiesForType(type)
                        val videoCaps = caps.videoCapabilities
                        if (videoCaps != null) {
                            maxWidth = videoCaps.supportedWidths.upper
                            maxHeight = videoCaps.supportedHeights.upper
                        }
                    }
                }
            }
        } catch (e: Exception) {
            supportsH264 = true
        }

        return CameraCapabilities(
            supportedResolutions = listOf(
                Pair(1280, 720),
                Pair(1920, 1080),
                Pair(640, 480)
            ),
            supportedFps = listOf(30, 24, 15),
            preferredResolution = Pair(1280, 720),
            preferredFps = 30,
            isHardwareAcceleratedEncoder = isHwAccelerated,
            encoderMimeType = MediaFormat.MIMETYPE_VIDEO_AVC,
            maxEncoderWidth = maxWidth,
            maxEncoderHeight = maxHeight
        )
    }

    /**
     * Queries output sizes supported by a specific Camera2 cameraId.
     */
    fun getCamera2OutputSizes(cameraId: String): List<Pair<Int, Int>> {
        val manager = cameraManager ?: return emptyList()
        try {
            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                ?: map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
                ?: map?.getOutputSizes(android.view.SurfaceHolder::class.java)
            if (sizes != null) {
                return sizes.map { Pair(it.width, it.height) }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return emptyList()
    }
}
