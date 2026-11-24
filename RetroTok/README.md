# RetroTok — Nostalgic Music & Short-Video Kiosk Platform

```
  ██████╗ ███████╗████████╗██████╗  ██████╗ ████████╗ ██████╗ ██╗  ██╗
  ██╔══██╗██╔════╝╚══██╔══╝██╔══██╗██╔═══██╗╚══██╔══╝██╔═══██╗██║ ██╔╝
  ██████╔╝█████╗     ██║   ██████╔╝██║   ██║   ██║   ██║   ██║█████╔╝ 
  ██╔══██╗██╔══╝     ██║   ██╔══██╗██║   ██║   ██║   ██║   ██║██╔═██╗ 
  ██║  ██║███████╗   ██║   ██║  ██║╚██████╔╝   ██║   ╚██████╔╝██║  ██╗
  ╚═╝  ╚═╝╚══════╝   ╚═╝   ╚═╝  ╚═╝ ╚═════╝    ╚═╝    ╚═════╝ ╚═╝  ╚═╝
  ---------------------------------------------------------------------
  A TikTok-Inspired Nostalgic Music Video Kiosk for Android 16 Touchscreens
```

## 1. Executive Summary

**RetroTok** is a specialized, 100% offline, TikTok-inspired nostalgic music and short-form video application designed specifically for vertical **Android 16 touchscreen kiosks** (720 × 1280 resolution) paired with an external **Logitech USB webcam**.

Engineered for physical live events—including company anniversaries, retro-themed parties, high school/college reunions, milestone birthdays, and corporate celebrations—RetroTok allows event guests to:
1. **Browse a full-screen vertical video feed** displaying nostalgic music and dance performances recorded throughout the event.
2. **Select curated soundtracks** spanning the **1970s, 1980s, 1990s, and 2000–2001**.
3. **Record themselves dancing** in front of the kiosk with an external Logitech USB webcam.
4. **Apply selectable retro video effects** (*VHS Retro*, *80s Neon*, *70s Vintage*, *90s Camcorder*, *Black & White Noir*).
5. **Preview their recording** with synchronized music audio.
6. **Publish to the local Event Feed** with a single tap on **"ADD TO EVENT FEED"**.
7. **Like and favorite** fellow guests' performances in a seamless, touch-first kiosk interface.

---

## 2. Master Phase 0 Documentation Index

All architectural specifications, database schemas, hardware investigations, and implementation blueprints are documented in the `docs/` folder:

| Document | Title | Core Focus |
| :--- | :--- | :--- |
| [**01-project-overview.md**](docs/01-project-overview.md) | Project Overview | Vision, scope boundaries, core value proposition, high-level journey. |
| [**02-functional-requirements.md**](docs/02-functional-requirements.md) | Functional Requirements | Detailed specifications for Feed, Studio, Music, Composition, Admin, and Sessions. |
| [**03-non-functional-requirements.md**](docs/03-non-functional-requirements.md) | Non-Functional Requirements | Performance metrics, 12h uptime stability, touch ergonomics, storage budgeting. |
| [**04-system-architecture.md**](docs/04-system-architecture.md) | System Architecture | Clean Architecture + MVVM + UDF, component breakdown, concurrency model. |
| [**05-database-design.md**](docs/05-database-design.md) | Database Design | Room 2.7.0 ERD, Kotlin entity schemas (`VideoEntity`, `SongEntity`), DataStore keys. |
| [**06-screen-specifications.md**](docs/06-screen-specifications.md) | Screen Specifications | Detailed layouts, ASCII wireframes, navigation state machine for all 10 screens. |
| [**07-ui-ux-design-system.md**](docs/07-ui-ux-design-system.md) | UI/UX Design System | Neo-Retro Synthwave palette, typography, kiosk touch targets, animation curves. |
| [**08-music-management.md**](docs/08-music-management.md) | Music Management | SAF file import, ID3 parser, duplicate detection, decade categorization (70s-00s). |
| [**09-video-recording-pipeline.md**](docs/09-video-recording-pipeline.md) | Video Recording Pipeline | Dual-path camera capture, A/V sync, Media3 Transformer engine, GLSL retro shaders. |
| [**10-event-session-management.md**](docs/10-event-session-management.md) | Event Session Management | Ephemeral guest sessions, inactivity watchdog auto-reset, guest deletion policy. |
| [**11-local-storage-architecture.md**](docs/11-local-storage-architecture.md) | Local Storage Architecture | Private storage tree, atomic file writes, storage safeguards (2GB alert, 500MB block). |
| [**12-security-and-privacy.md**](docs/12-security-and-privacy.md) | Security & Privacy | PBKDF2 PIN hashing, brute-force lockout, Android Device Owner Lock Task Mode. |
| [**13-hardware-compatibility.md**](docs/13-hardware-compatibility.md) | Hardware Compatibility | Logitech webcam matrix (C920, C922, etc.), UVC vs CameraX HAL, USB power brownouts. |
| [**14-testing-and-acceptance.md**](docs/14-testing-and-acceptance.md) | Testing & Acceptance | 8 Critical User Journeys (CUJ-01 to CUJ-08), Robolectric tests, soak test checklist. |
| [**15-development-roadmap.md**](docs/15-development-roadmap.md) | Development Roadmap | 10-phase implementation plan (Phases 0–9), risk register, Phase 0 exit criteria. |

---

## 3. Core Architectural Pillars

```
+-----------------------------------------------------------------------------------------+
|                                    RETROTOK PILLARS                                     |
+-----------------------------------------------------------------------------------------+
| 1. ZERO CLOUD DEPENDENCY    | 100% on-device local storage, Room SQLite, zero network    |
| 2. TIKTOK-INSPIRED FEED     | 9:16 vertical video, vertical swipe, right action column  |
| 3. NOSTALGIC MUSIC CORE     | Curated tracks: 70s Disco, 80s Synth, 90s Pop, 2000-2001  |
| 4. DUAL-PATH CAMERA ENGINE  | CameraX External Camera HAL + Native UVC JNI Fallback     |
| 5. MEDIA3 GLSL TRANSFORMER  | Hardware-accelerated retro video effects & audio mux      |
| 6. KIOSK APPLIANCE INTEGRITY| Android Lock Task Mode, PIN-protected admin, auto-reset   |
+-----------------------------------------------------------------------------------------+
```

---

## 4. Technology Stack Summary

| Subsystem | Selected Technology | Version / Specification |
| :--- | :--- | :--- |
| **Operating System** | Android 16 | API Level 36 |
| **Language** | Kotlin | 2.2.10 |
| **UI Framework** | Jetpack Compose / Material 3 | Compose BOM 2024.09.00 |
| **Architecture** | Clean Architecture + MVVM + UDF | Kotlin Coroutines & StateFlow |
| **Local Relational DB** | Room Database | 2.7.0 (SQLite) |
| **Key-Value Storage** | Jetpack DataStore Preferences | 1.1.7 |
| **Video Playback** | AndroidX Media3 ExoPlayer | 1.5.0 (Dual-player pool) |
| **Video Composition** | AndroidX Media3 Transformer | 1.5.0 (GLSL `GlEffect` shaders) |
| **Camera Capture** | CameraX + Native UVC Bridge | 1.5.0 / `libuvccamera` |
| **Image Loading** | Coil Compose | 2.7.0 |
| **Unit & E2E Testing** | JUnit 4, Robolectric, Compose Test | Robolectric 4.16.1 |

---

## 5. Hardware Requirements

- **Display:** Vertical portrait touchscreen (native resolution 720 × 1280, 60Hz capacitive multi-touch).
- **Camera:** External Logitech USB Webcam (Logitech C920, C922, C270, Brio, or StreamCam).
- **USB Interface:** Powered USB hub or powered OTG Y-cable (mandatory 5V / 2.0A dedicated power supply to prevent bus brownouts).
- **Audio Output:** Integrated kiosk stereo speakers or 3.5mm line out to external sound system.
