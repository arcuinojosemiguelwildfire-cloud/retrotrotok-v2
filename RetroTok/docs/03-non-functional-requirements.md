# RetroTok — Non-Functional Requirements Specification

**Document ID:** RT-DOC-03  
**Version:** 1.0.0  
**Target Environment:** 24/7 Vertical Touchscreen Kiosk (Android 16, 720 × 1280)  

---

## 1. Performance & Responsiveness Requirements

| Requirement ID | Metric | Target Threshold | Maximum Acceptable Limit | Verification Method |
| :--- | :--- | :--- | :--- | :--- |
| **NFR-PERF-01** | Cold Application Launch | < 2.5 seconds | 4.0 seconds | Android Macrobenchmark |
| **NFR-PERF-02** | Warm Application Launch | < 800 milliseconds | 1.5 seconds | Macrobenchmark |
| **NFR-PERF-03** | Feed Swipe Transition Latency | < 150 milliseconds | 250 milliseconds | Frame timing analysis |
| **NFR-PERF-04** | Video Playback Start (on swipe) | < 200 milliseconds | 350 milliseconds | ExoPlayer `onIsPlayingChanged` |
| **NFR-PERF-05** | Video Feed Frame Rate | 60 FPS (display rate) | 55 FPS | Choreographer dropped frame tracker |
| **NFR-PERF-06** | Webcam Viewfinder Frame Rate | 30 FPS steady | 25 FPS | CameraX analyzer frame stats |
| **NFR-PERF-07** | Video Composition Duration (30s clip)| <= 10.0 seconds (1/3x)| <= 20.0 seconds | Media3 Transformer listener |
| **NFR-PERF-08** | Database Query Latency | < 15 milliseconds | 50 milliseconds | Room SQL log query profiling |
| **NFR-PERF-09** | Touch Interaction Latency | < 50 milliseconds | 100 milliseconds | MotionEvent to Composition time |

---

## 2. Kiosk Reliability & Long-Duration Operational Stability

Because RetroTok will be installed in commercial kiosks operating throughout extended 8- to 14-hour events, operational stability is paramount:

### 2.1 Continuous Kiosk Operation (MTBF)
- **Target MTBF:** Minimum 72 hours continuous operation without process crash, Application Not Responding (ANR), or memory thrashing.
- **Leak Prevention:** Every ExoPlayer and CameraX instance must be strictly lifecycle-managed. The `VerticalPager` must recycle video player surfaces rather than creating an unbounded pool of hardware decoder instances. Max active decoders: 2 (current video + pre-buffered adjacent video).

### 2.2 Memory Management Budget
- **Maximum App Heap Memory:** <= 256 MB on a standard 3GB/4GB Android 16 device.
- **Bitmap Management:** All album artwork and video thumbnails must be downsampled to exact UI dimensions (`120x120` for list items, `720x1280` for poster art) using Coil with an LRU memory cache capped at 48 MB.
- **Native Memory Hygiene:** Video buffers generated during Media3 transformation must be allocated via direct byte buffers and released immediately upon pipeline completion.

### 2.3 Thermal & Power Throttling Mitigation
- When the kiosk is in an idle state (no touch interactions for > 3 minutes), the feed will loop current videos at standard decoding levels without executing unnecessary background tasks.
- Background tasks (e.g., thumbnail generation, database vacuums) must execute on low-priority background threads (`Dispatchers.IO` / `Process.THREAD_PRIORITY_BACKGROUND`).

---

## 3. Hardware Interfacing & Fault Tolerance

### 3.1 External USB Webcam Resilience
- **Hotplug Recovery:** If the external Logitech USB webcam is momentarily unplugged or reset by a voltage dip, the app must not crash. The camera pipeline must intercept `UsbManager.ACTION_USB_DEVICE_DETACHED` and `ACTION_USB_DEVICE_ATTACHED`, displaying a friendly *"Camera reconnecting..."* overlay and automatically re-binding the camera session once re-enumerated.
- **Power Brownout Mitigation:** The kiosk hardware design must specify a powered USB hub or powered OTG Y-cable. If USB bus voltage drops, the app must report a hardware warning rather than failing silently.

### 3.2 Display Form Factor (720 × 1280 Portrait)
- All UI layouts are fixed strictly to portrait orientation (`android:screenOrientation="portrait"`).
- Density-independent pixels (`dp`) and scale-independent pixels (`sp`) are anchored to a baseline `720 x 1280` (approx. 320 dpi / `xhdpi`).
- Letterboxing and horizontal panning are strictly prohibited for core UI screens.

---

## 4. Kiosk Usability & Ergonomics (Touchscreen)

### 4.1 Touch Target Sizing
- While Android Material Design 3 mandates a minimum 48dp × 48dp touch target, kiosk touchscreens require larger targets to accommodate diverse finger sizes and ambient venue movement:
  - **Primary CTA Buttons (e.g., "ADD TO EVENT FEED", "START RECORDING"):** Minimum height 64dp, full-width or minimum 280dp.
  - **Secondary Action Buttons (e.g., Retake, Select Song):** Minimum 56dp height.
  - **Vertical Feed Action Icons (Like, Favorite):** Minimum touch zone 64dp × 64dp.
  - **List Items (Song Picker):** Minimum row height 72dp.

### 4.2 Lighting & High-Contrast Visual Ergonomics
- The kiosk will operate in dim or dynamic party environments (strobe lights, disco balls, neon ambiance).
- The UI palette uses deep onyx backgrounds (`#0B0813`) paired with ultra-high contrast text (`#FFFFFF`, `#F3E8FF`) and vivid accents (`#FF2A85`, `#00F0FF`).
- Contrast ratio between text and backgrounds must exceed **7:1** (WCAG AAA level) across all interactive elements.

---

## 5. Storage Space Budgeting & Health Monitoring

| Storage Directory | Purpose | Estimated Size per Item | Retention Policy | Quota / Safeguard |
| :--- | :--- | :--- | :--- | :--- |
| `/music/` | MP3 / AAC Master Audio | 3–8 MB per song | Permanent until admin delete | 5 GB max catalog |
| `/music/artwork/` | Album Artwork (WebP) | 50–150 KB per image | Permanent with song | 100 MB max |
| `/videos/published/` | Finalized H.264 MP4s | 15–35 MB per 30s video | Permanent until admin delete | Storage dependent |
| `/videos/thumbnails/` | Feed Poster Thumbnails | 60–120 KB per video | Re-generatable cache | 200 MB max |
| `/videos/temp/` | Raw capture & mux staging | 20–40 MB per attempt | Deleted after publish or session reset | Auto-cleared |

### 5.1 Storage Safeguards
- **Low Storage Threshold Warning:** When free internal storage drops below **2.0 GB**, the admin header displays a persistent warning badge.
- **Critical Storage Hard Block:** When free internal storage drops below **500 MB**, the Dance Studio record button is disabled, displaying: *"Kiosk storage is full. Please alert the event organizer."*

---

## 6. Security, Privacy & Integrity

1. **Zero External Data Exfiltration:** The application contains zero network permissions in production or, at minimum, strictly binds to offline local loops. No telemetry, crashlytics, or analytics network calls.
2. **Kiosk Enclosure Integrity:** Android Lock Task Mode (`startLockTask()`) prevents guests from reaching Android Settings, the home screen, or notification shade.
3. **Session Reset Guarantee:** When a guest finishes recording or walks away (idle timeout), all temporary draft state, captured frames, and typed nicknames are cleared.
