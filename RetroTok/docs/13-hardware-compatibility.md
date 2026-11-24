# RetroTok — Hardware Compatibility & Integration Analysis

**Document ID:** RT-DOC-13  
**Version:** 1.0.0  
**Target Hardware:** Vertical Kiosk (720 × 1280) + External Logitech USB Webcam  
**Operating System:** Android 16 (API Level 36)  

---

## 1. Hardware Reality & Unresolved Assumptions

> [!IMPORTANT]
> **Architectural Precaution:** The specific Logitech webcam model has not yet been identified by the kiosk hardware vendor. Furthermore, Android 16 system implementations vary widely across commercial kiosk motherboards (e.g., Rockchip, MediaTek, Qualcomm, Intel x86 AOSP). 
> 
> Therefore, this document establishes a **strict verification framework** that assumes zero guaranteed hardware behavior until confirmed on physical hardware.

---

## 2. External Logitech Webcam Compatibility Matrix

Logitech manufactures dozens of webcam models spanning two decades. Their internal controllers, video stream formats, and power profiles differ significantly:

| Logitech Model Series | Typical Output Formats | Native Resolution | UVC Compliance | Android Camera2 HAL Support | Risk Level / Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Logitech C920 / C920s** | MJPEG, YUY2 (Uncompressed) | 1080p @ 30fps / 720p @ 30fps | UVC 1.1 / 1.5 | High (if HAL enabled) | **Recommended Baseline.** Ubiquitous, stable autofocus, good low-light performance. |
| **Logitech C922 Pro** | MJPEG, YUY2 | 1080p @ 30fps / 720p @ 60fps | UVC 1.5 | High | Excellent low-light gain in party venues. 60fps smooth capture. |
| **Logitech C270 / C310** | MJPEG, YUY2 | 720p @ 30fps | UVC 1.1 | Medium | Budget option; fixed focus (requires guest to stand at fixed distance ~1m). |
| **Logitech Brio 4K / 500** | MJPEG, NV12, YUY2 | 4K / 1080p / 720p | UVC 1.5 | High | Premium wide field-of-view (90°). Requires USB 3.0 Type-C bus. |
| **Logitech StreamCam** | MJPEG, NV12 | 1080p @ 60fps | UVC 1.5 | Medium | Native hardware portrait rotation support via USB-C. |

---

## 3. Android 16 USB Camera Integration Paths

### 3.1 Path A: Camera2 / CameraX External Camera (Preferred)
In standard Google-certified Android devices (Android 9.0+), the operating system provides an external camera HAL service that translates USB Video Class (UVC) streams into standard Camera2 devices:

- **System Feature Check:** `context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_EXTERNAL)`
- **Camera Characteristics:**
  ```kotlin
  val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
  val externalCameraId = cameraManager.cameraIdList.firstOrNull { id ->
      val chars = cameraManager.getCameraCharacteristics(id)
      val facing = chars.get(CameraCharacteristics.LENS_FACING)
      facing == CameraCharacteristics.LENS_FACING_EXTERNAL
  }
  ```
- **Advantages:** Direct integration with Jetpack CameraX `Preview` and `VideoCapture<Recorder>`, zero custom C++ / JNI maintenance.

### 3.2 Path B: USB Host API & Native UVC Driver (Essential Fallback)
Many commercial Android kiosk motherboards (e.g., custom Rockchip RK3588 or Allwinner A50 boards) omit the Google external camera HAL to cut licensing costs. On these boards, `cameraManager.cameraIdList` returns empty even when the Logitech webcam is plugged in.

- **Detection:** The app monitors the USB bus directly:
  ```kotlin
  val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
  val logitechDevice = usbManager.deviceList.values.firstOrNull { device ->
      device.vendorId == 0x046d // Logitech Inc. Vendor ID
  }
  ```
- **Fallback Implementation:** RetroTok includes the lightweight native UVC bridge (`libuvccamera` JNI wrapper) to stream webcam frames directly into an `android.view.Surface` via native V4L2 (Video for Linux 2) ioctl calls if Path A fails to detect a camera.

---

## 4. Critical Electrical & Power Delivery Constraints

> [!CAUTION]
> **Power Brownout Hazard:** The #1 failure mode of USB webcams on Android tablets and kiosk boards is **insufficient USB bus power**.

1. **Power Consumption:** When capturing 720p video at 30 FPS with active autofocus and auto-exposure motors, a Logitech C920 draws between **450mA and 750mA at 5V**.
2. **The Failure Mode:** If connected directly to a tablet's unpowered OTG adapter, the tablet's battery circuit cannot sustain the 750mA draw. The webcam momentarily loses power, drops its USB enumeration, and disconnects mid-recording, crashing the video capture session.
3. **Mandatory Hardware Specification:**
   - The kiosk must utilize a **powered USB-C hub** or an **externally powered OTG Y-cable** delivering dedicated 5V / 2.0A power directly to the USB bus.

---

## 5. Kiosk Display (720 × 1280 Vertical Touchscreen)

1. **Aspect Ratio:** 9:16 portrait. Native resolution matches standard HD video.
2. **Touch Digitizer:** Capacitive multi-touch (minimum 5 concurrent touch points).
3. **Touch Latency:** Must not exceed 50ms report rate.
4. **Orientation Lock:** Hardcoded in `AndroidManifest.xml`:
   ```xml
   android:screenOrientation="portrait"
   android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"
   ```

---

## 6. Hardware Verification Checklist (Pre-Deployment)

Field engineers must execute this checklist on the physical kiosk prior to event deployment:

- [ ] Connect Logitech webcam via powered USB hub.
- [ ] Launch RetroTok hardware diagnostic screen.
- [ ] Confirm webcam enumerates under Path A (CameraX) or Path B (Native UVC).
- [ ] Verify steady 30 FPS viewfinder rendering for 5 continuous minutes.
- [ ] Test 30-second video recording and verify output MP4 has no dropped frames.
- [ ] Verify audio playback through kiosk external speakers is loud and clear.
- [ ] Test touchscreen responsiveness across all 4 screen corners and center.
- [ ] Unplug webcam during preview, reconnect, and confirm auto-recovery without crash.
- [ ] Confirm power supply maintains > 4.95V on USB bus during active recording.
