package com.example.camera

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface CameraProvider {
    val availableCameras: StateFlow<List<CameraDeviceInfo>>
    val activeCamera: StateFlow<CameraDeviceInfo?>
    val connectionState: StateFlow<CameraConnectionState>
    val selectedBackend: StateFlow<CameraBackendType>
    val capabilities: StateFlow<CameraCapabilities>
    val errorMessage: StateFlow<String?>

    fun initialize()
    fun refreshDevices()
    fun requestUsbPermission(device: UsbDevice, onResult: (Boolean) -> Unit)
    fun setDisconnectionCallback(onDisconnect: () -> Unit)
    fun release()
}

class CameraProviderImpl(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) : CameraProvider {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.retrotok.USB_PERMISSION"
    }

    private val detector = CameraDetector(context)
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val _availableCameras = MutableStateFlow<List<CameraDeviceInfo>>(emptyList())
    override val availableCameras: StateFlow<List<CameraDeviceInfo>> = _availableCameras.asStateFlow()

    private val _activeCamera = MutableStateFlow<CameraDeviceInfo?>(null)
    override val activeCamera: StateFlow<CameraDeviceInfo?> = _activeCamera.asStateFlow()

    private val _connectionState = MutableStateFlow(CameraConnectionState.NOT_TESTED)
    override val connectionState: StateFlow<CameraConnectionState> = _connectionState.asStateFlow()

    private val _selectedBackend = MutableStateFlow(CameraBackendType.DEV_PREVIEW)
    override val selectedBackend: StateFlow<CameraBackendType> = _selectedBackend.asStateFlow()

    private val _capabilities = MutableStateFlow(CameraCapabilities())
    override val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var onDisconnectListener: (() -> Unit)? = null
    private var usbPermissionCallback: ((Boolean) -> Unit)? = null
    private var isReceiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    refreshDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detachedDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (detachedDevice != null) {
                        handleDeviceDetached(detachedDevice)
                    }
                }
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        usbPermissionCallback?.invoke(granted)
                        usbPermissionCallback = null
                        refreshDevices()
                    }
                }
            }
        }
    }

    override fun initialize() {
        registerUsbReceiver()
        refreshDevices()
    }

    override fun refreshDevices() {
        scope.launch {
            _connectionState.update { CameraConnectionState.CONNECTING }
            try {
                val usbDevices = detector.detectUsbDevices()
                val camera2Devices = detector.detectCamera2Devices()
                val allDevices = usbDevices + camera2Devices
                _availableCameras.update { allDevices }

                val (bestBackend, active) = detector.selectBestBackend()
                _selectedBackend.update { bestBackend }
                _activeCamera.update { active }

                val caps = detector.queryEncoderCapabilities()
                _capabilities.update { caps }

                if (active != null) {
                    when (bestBackend) {
                        CameraBackendType.NATIVE_USB_UVC -> {
                            _connectionState.update { CameraConnectionState.UNSUPPORTED }
                            _errorMessage.update {
                                "USB UVC webcam detected (${active.name}), but native UVC capture is not implemented and no Camera2 HAL camera is available."
                            }
                        }
                        CameraBackendType.DEV_PREVIEW -> {
                            _connectionState.update { CameraConnectionState.CONNECTED }
                            _errorMessage.update { null }
                        }
                        else -> {
                            _connectionState.update { CameraConnectionState.CONNECTED }
                            _errorMessage.update { null }
                        }
                    }
                } else {
                    _connectionState.update { CameraConnectionState.DISCONNECTED }
                    _errorMessage.update { "No Camera2 capture device detected." }
                }
            } catch (e: Exception) {
                _connectionState.update { CameraConnectionState.ERROR }
                _errorMessage.update { "Camera initialization failed: ${e.localizedMessage}" }
            }
        }
    }

    override fun requestUsbPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        val manager = usbManager ?: run {
            onResult(false)
            return
        }

        if (manager.hasPermission(device)) {
            onResult(true)
            return
        }

        usbPermissionCallback = onResult
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            flags
        )
        manager.requestPermission(device, permissionIntent)
    }

    override fun setDisconnectionCallback(onDisconnect: () -> Unit) {
        this.onDisconnectListener = onDisconnect
    }

    private fun handleDeviceDetached(device: UsbDevice) {
        val current = _activeCamera.value
        if (current?.vendorId == device.vendorId && current.productId == device.productId) {
            _connectionState.update { CameraConnectionState.DISCONNECTED }
            _errorMessage.update { "Webcam ${device.productName ?: "USB Device"} was unplugged." }
            onDisconnectListener?.invoke()
            refreshDevices()
        }
    }

    private fun registerUsbReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(usbReceiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    private fun unregisterUsbReceiver() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (e: Exception) {
                // Ignore
            }
            isReceiverRegistered = false
        }
    }

    override fun release() {
        unregisterUsbReceiver()
        _connectionState.update { CameraConnectionState.DISCONNECTED }
    }
}
