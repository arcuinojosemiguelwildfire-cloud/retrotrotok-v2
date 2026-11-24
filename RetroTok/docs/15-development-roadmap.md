# RetroTok — Development Roadmap & Implementation Plan

**Document ID:** RT-DOC-15  
**Version:** 1.0.0  
**Methodology:** Incremental, Test-Driven Local-First Engineering  
**Total Phases:** 10 (Phases 0 through 9)  

---

## 1. Master Development Phases & Dependency Graph

```
[ Phase 0: Discovery & Architecture ]  <--- (COMPLETED)
                  |
                  v
[ Phase 1: Android Foundation & Design System ]
                  |
                  +--------------------------------+
                  |                                |
                  v                                v
[ Phase 2: Local Music Engine ]       [ Phase 3: TikTok Video Feed ]
                  |                                |
                  +----------------+---------------+
                                   |
                                   v
             [ Phase 4: Webcam Recording & Dance Studio ]
                                   |
                                   v
             [ Phase 5: Video Composition & Retro Shaders ]
                                   |
                                   v
             [ Phase 6: Local Publishing & Guest Sessions ]
                                   |
                                   v
             [ Phase 7: PIN-Protected Admin Console ]
                                   |
                                   v
             [ Phase 8: Performance, Security & Kiosk Lockdown ]
                                   |
                                   v
             [ Phase 9: Physical Hardware Soak & Deployment ]
```

---

## 2. Detailed Phase Breakdown

### Phase 0: Requirements, Architecture & Discovery (CURRENT)
- **Status:** **100% COMPLETE**
- **Deliverables:** Comprehensive technical specifications RT-DOC-01 through RT-DOC-15 + Master README.
- **Exit Criteria:** All architectural unknowns documented, zero assumed hardware capabilities, dual-path camera plan defined, technical risk register established.

### Phase 1: Android Foundation, M3 Theming & Navigation Skeleton
- **Scope:**
  - Setup core Gradle build scripts, Version Catalog dependencies, and package structure.
  - Implement `RetroTokTheme`, dark palette tokens (`#0B0813`, `#FF2A85`, `#00F0FF`), typography, and kiosk components.
  - Build Jetpack Navigation Compose graph with backstack management.
  - Establish base UI scaffold and container layouts locked to 720 × 1280 portrait orientation.
- **Exit Criteria:** App compiles, runs in portrait mode, and navigates between placeholder screens.

### Phase 2: Local Music Management Engine
- **Scope:**
  - Room database entities: `SongEntity`, `GenreEntity`, `PlaylistEntity`, and DAOs.
  - Android Storage Access Framework (SAF) audio file picker.
  - ID3v2 / MP4 Atom metadata parsing and embedded album artwork extraction.
  - SHA-256 audio stream duplicate detection.
  - Automatic decade categorization (`70s`, `80s`, `90s`, `2000–2001`).
  - `SongPickerSheet` UI with 15-second audio preview player.
- **Exit Criteria:** Successfully import MP3/AAC files, display by decade, play previews, and prevent duplicate imports.

### Phase 3: TikTok-Style Vertical Video Feed
- **Scope:**
  - Room entities: `VideoEntity`, `VideoLikeEntity`.
  - Compose `VerticalPager` full-screen video feed at 720 × 1280.
  - Dual `ExoPlayer` instance recycling pool for zero-latency swipe transitions.
  - Audio track synchronization and auto-switch on swipe.
  - Right-side vertical action column: Like heart burst animation, Favorite star, rotating vinyl disc.
  - Bottom metadata overlay with animated marquee ticker.
  - Ephemeral like and favorite database persistence.
- **Exit Criteria:** Feed plays bundled sample videos smoothly with vertical swipe, active audio, and interactive like counters.

### Phase 4: Logitech USB Webcam Capture & Dance Studio
- **Scope:**
  - Dual-path camera engine (`WebcamCaptureEngine`): CameraX external camera HAL (Path A) + native UVC USB Host fallback (Path B).
  - Live webcam preview with horizontal mirroring toggle.
  - Studio top bar displaying selected song and effect pill.
  - 3-second audio-visual countdown (3..2..1..DANCE!).
  - Synchronized video capture and local music audio playback.
  - Neon circular progress timer and blinking `REC` timecode.
- **Exit Criteria:** Live video preview renders from external USB webcam; 30-second recording captures raw MP4 and saves to `/temp/`.

### Phase 5: Video Composition & Retro GLSL Shaders
- **Scope:**
  - Integration of AndroidX Media3 Transformer (`media3-transformer`).
  - Audio track replacement (muting webcam mic, muxing selected music file).
  - Implementation of 5 custom OpenGL ES 2.0 fragment shaders via Media3 `GlEffect`:
    1. *VHS Retro* (chromatic aberration, tracking jitter, scanlines)
    2. *80s Neon* (vibrance boost, dual-tone magenta/cyan, bloom)
    3. *70s Vintage* (warm sepia tint, vignette, soft black levels)
    4. *90s Camcorder* (film grain noise, timestamp watermark)
    5. *Black & White Noir* (high-contrast monochrome)
  - Fast-path fallback for low-end GPU hardware.
- **Exit Criteria:** Raw video transforms with selected audio and retro effect in <= 12 seconds on kiosk hardware.

### Phase 6: Local Event Publishing & Guest Sessions
- **Scope:**
  - `VideoPreviewScreen` with seamless looping playback.
  - Retake / Discard workflow (purging temporary draft files).
  - Optional guest nickname input field (max 20 chars).
  - **"ADD TO EVENT FEED"** primary action button with clear local-only privacy modal.
  - Atomic file transfer from `/temp/` to `/published/`.
  - Automatic injection of newly published recording to the top of `FeedScreen`.
  - Ephemeral session reset and inactivity watchdog timer (60s).
- **Exit Criteria:** End-to-end recording-to-feed publication loop completes smoothly on-device.

### Phase 7: PIN-Protected Administrator Console
- **Scope:**
  - Obfuscated entry gesture (3-second long-press on RetroTok logo).
  - Salted PBKDF2 PIN verification keypad with 5-attempt rate-limiting lockout.
  - 8 Admin Modules:
    1. Event Settings (Name, theme, date)
    2. Music Manager (Import, edit metadata, delete)
    3. Video Manager (Grid view, play preview, toggle featured, delete)
    4. Storage Manager (Disk metrics via `StatFs`, temp cache cleanup)
    5. Recording Settings (15s/30s/60s duration, resolution)
    6. Video Effects Settings (Enable/disable filter presets)
    7. Guest Permissions (Toggle `Guest Video Deletion: ON/OFF`)
    8. App Settings (Change PIN, trigger Lock Task Mode)
- **Exit Criteria:** Admin functions fully operational, protected by PIN, and isolated from guest UI.

### Phase 8: Performance Optimization, Thermal Tuning & Kiosk Security
- **Scope:**
  - Memory leak profiling (leak-free continuous loop over 1,000 video transitions).
  - Thermal throttling mitigation during idle feed state.
  - Android Device Owner provisioning integration (`startLockTask()`).
  - Status bar, navigation bar, and power menu suppression.
  - Low-storage safeguards (2.0 GB warning, 500 MB hard block).
- **Exit Criteria:** App operates as an inescapable kiosk appliance with stable memory and thermals.

### Phase 9: Real-Device Kiosk Testing & Deployment
- **Scope:**
  - Execution of 12-hour continuous hardware soak test on target kiosk.
  - Physical testing across multiple Logitech webcam models (C920, C922, C270, Brio).
  - Audio acoustic calibration with kiosk loudspeakers.
  - Field deployment documentation, venue checklist, and organizer cheat-sheet.
- **Exit Criteria:** Final production APK signed and verified on physical event kiosk.

---

## 3. Technical Risk Register & Mitigation Matrix

| Risk ID | Technical Risk Description | Severity | Likelihood | Concrete Architectural Mitigation |
| :--- | :--- | :--- | :--- | :--- |
| **TR-01** | Kiosk OS lacks Camera2 external camera HAL | **High** | Medium | Dual-path camera architecture: implement native UVC USB Host API driver (`libuvccamera`) as automatic fallback. |
| **TR-02** | USB camera brownout due to unpowered OTG bus | **Critical**| High | Mandatory hardware specification requiring externally powered USB hub or 5V/2A powered Y-cable. |
| **TR-03** | Transformer composition takes too long on weak SoC | **High** | Medium | Limit kiosk resolution to 720p; implement Fast-Mux fallback without GL shaders if GPU cannot achieve target transformation time. |
| **TR-04** | Audio/video lip-sync drift during capture | **Medium** | Medium | Synchronize timestamps using nanoTime offset compensation and pre-warm camera encoder before countdown ends. |
| **TR-05** | Video decoder exhaustion / memory leak in feed | **High** | Low | Recycle a fixed pool of exactly 2 ExoPlayer instances in `VerticalPager` instead of instantiating new players per item. |
| **TR-06** | Guest walks away mid-recording leaving unfinished state | **Medium** | High | Automated 60-second inactivity watchdog resets to `FeedScreen` and purges `/temp/` directory. |

---

## 4. Phase 0 Exit Criteria Scorecard

- [x] **Project Scope Finalized:** 100% offline, zero cloud backend, portrait 720 × 1280 kiosk, external Logitech webcam, shared PIN admin.
- [x] **Technical Documentation Complete:** 15 comprehensive specifications (RT-DOC-01 to RT-DOC-15) created and indexed.
- [x] **Hardware Unknowns Addressed:** Dual-path camera capture strategy (CameraX HAL vs Native UVC) defined.
- [x] **Media Architecture Validated:** AndroidX Media3 Transformer with custom OpenGL ES retro shaders specified.
- [x] **Data & Storage Designed:** Room 2.7.0 database schema, DataStore preferences, and application-private directory structure defined.
- [x] **Kiosk Security Modeled:** PBKDF2 PIN hashing, rate-limiting, and Android Device Owner Lock Task Mode planned.
- [x] **Acceptance Criteria Established:** 8 Critical User Journeys (CUJ-01 to CUJ-08) documented with measurable verification criteria.
- [x] **Ready for Phase 1:** Architecture blueprint approved for implementation kickoff.
