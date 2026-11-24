# RetroTok — Functional Requirements Specification

**Document ID:** RT-DOC-02  
**Version:** 1.0.0  
**Status:** Approved Specification  
**Architecture Classification:** Local-First Android 16 Kiosk System  

---

## 1. Functional Requirements Overview & Traceability Matrix

| Requirement ID | Module | Title | Priority |
| :--- | :--- | :--- | :--- |
| **FR-FEED-01** | For You Feed | Full-Screen 9:16 Vertical Video Playback | Critical |
| **FR-FEED-02** | For You Feed | Vertical Swipe Navigation & Auto-Transition | Critical |
| **FR-FEED-03** | For You Feed | Synchronized Audio Track Playback | Critical |
| **FR-FEED-04** | For You Feed | Like and Favorite Toggle Persistence | High |
| **FR-FEED-05** | For You Feed | Video Overlay Information Display | High |
| **FR-FEED-06** | For You Feed | Empty State and First-Run Onboarding | Medium |
| **FR-MUSIC-01** | Music Library | Local Audio File Import via Android SAF | Critical |
| **FR-MUSIC-02** | Music Library | File Format Validation & Audio Integrity | High |
| **FR-MUSIC-03** | Music Library | Duplicate Detection by Checksum & Metadata | High |
| **FR-MUSIC-04** | Music Library | Metadata Extraction & Manual Editing | High |
| **FR-MUSIC-05** | Music Library | Decade & Genre Categorization | High |
| **FR-MUSIC-06** | Music Library | In-Kiosk Song Picker & 15s Audio Preview | High |
| **FR-STUDIO-01** | Dance Studio | External Webcam Live Viewfinder Display | Critical |
| **FR-STUDIO-02** | Dance Studio | Song & Retro Effect Selection Flow | Critical |
| **FR-STUDIO-03** | Dance Studio | 3-Second Audio-Visual Countdown | High |
| **FR-STUDIO-04** | Dance Studio | Timed Video Capture (15s, 30s, 60s) | Critical |
| **FR-STUDIO-05** | Dance Studio | Manual Stop & Emergency Abort | Medium |
| **FR-COMP-01** | Composition | Audio Track Muxing & Webcam Mic Muting | Critical |
| **FR-COMP-02** | Composition | Hardware-Accelerated Video Encoding | Critical |
| **FR-COMP-03** | Composition | Retro Video Filter Post-Processing | High |
| **FR-COMP-04** | Composition | Composition Error Handling & Raw Preservation | High |
| **FR-PUB-01** | Publishing | Video Preview Loop & Retake Flow | Critical |
| **FR-PUB-02** | Publishing | "ADD TO EVENT FEED" Action & Local Storage | Critical |
| **FR-PUB-03** | Publishing | Optional Guest Nickname Input | High |
| **FR-PUB-04** | Publishing | Guest Privacy Disclaimer Modal | High |
| **FR-PUB-05** | Publishing | Immediate Feed Injection & Auto-Focus | High |
| **FR-SESS-01** | Session Mgmt | Ephemeral Guest Session Lifecycle | High |
| **FR-SESS-02** | Session Mgmt | Idle Inactivity Timeout Auto-Reset | High |
| **FR-SESS-03** | Session Mgmt | Temporary File Garbage Collection | Medium |
| **FR-ADMIN-01**| Administration | PIN-Protected Authentication & Lockout | Critical |
| **FR-ADMIN-02**| Administration | Event Configuration Settings | High |
| **FR-ADMIN-03**| Administration | Music Catalog Management | High |
| **FR-ADMIN-04**| Administration | Video Moderation & Deletion | High |
| **FR-ADMIN-05**| Administration | Guest Deletion Permission Toggle | High |
| **FR-ADMIN-06**| Administration | Storage & Retention Monitor | High |
| **FR-ADMIN-07**| Administration | Recording Parameter Configuration | High |
| **FR-ADMIN-08**| Administration | Kiosk Security & Lock Task Toggle | High |

---

## 2. Detailed Module Specifications

### 2.1 For You Feed (FR-FEED)

#### FR-FEED-01: Full-Screen 9:16 Vertical Video Playback
- **Description:** The kiosk primary screen must render vertical videos in full-screen 9:16 aspect ratio at the native 720 × 1280 resolution with zero letterboxing when media matches native aspect ratio.
- **Preconditions:** At least one published video exists in the local database.
- **Main Flow:**
  1. The app initializes into `FeedScreen`.
  2. The topmost video in the playlist starts playing automatically from frame 0.
  3. Video loops infinitely until the user swipes to another video.
- **Alternative Flow:** If the video was imported with a non-9:16 aspect ratio, the player applies `ContentScale.Crop` centered to fill the display without black bars.

#### FR-FEED-02: Vertical Swipe Navigation & Auto-Transition
- **Description:** Guests navigate through videos using intuitive vertical flick or drag gestures implemented via Compose `VerticalPager`.
- **Preconditions:** Two or more videos exist in the feed.
- **Main Flow:**
  1. Guest swipes upward: current video unloads/pauses, next video smoothly snaps into place and begins playback within 200ms.
  2. Guest swipes downward: previous video loads and resumes playback.
  3. ExoPlayer pre-caches the adjacent video (n+1) to ensure instantaneous playback upon swipe.

#### FR-FEED-03: Synchronized Audio Track Playback
- **Description:** When a video plays, its attached nostalgic soundtrack plays through the kiosk speakers.
- **Rules:**
  1. Audio must start strictly in sync with the first video frame (latency < 50ms).
  2. Swiping to another video immediately cross-fades/cuts the prior audio and triggers the next track.
  3. Browsing an empty feed or paused video must produce absolute silence (no ambient background music).

#### FR-FEED-04: Like and Favorite Toggle Persistence
- **Description:** Guests can tap the Heart (Like) and Star (Favorite) icons in the right-side action column.
- **Behavior:**
  1. Tapping Like triggers an animated heart explosion and increments the local database counter.
  2. State is persisted in `VideoLikeEntity` and `VideoEntity.likeCount`.
  3. Tapping Favorite saves the video to the guest's session favorites and increments `VideoEntity.favoriteCount`.
  4. Double-tapping anywhere on the video screen triggers the Like action with an on-screen floating heart burst.

#### FR-FEED-05: Video Overlay Information Display
- **Description:** The bottom-left of the display renders:
  1. Creator Nickname (e.g., `@DiscoQueen99` or `@RetroDancer`).
  2. Event Hashtag / Caption (e.g., `#AcmeAnniversary #80sNight`).
  3. Rotating Vinyl / Cassette Tape icon with scrolling marquee: `[Decade Badge] Song Title - Artist`.
  4. Retro effect tag (e.g., `[VHS Retro]`).

---

### 2.2 Local Music Management (FR-MUSIC)

#### FR-MUSIC-01: Local Audio File Import
- **Description:** The administrator imports audio files from device storage or an attached USB drive using Android's Storage Access Framework (`ActivityResultContracts.OpenMultipleDocuments`).
- **Supported Formats:** MPEG Audio Layer III (`.mp3`), Advanced Audio Coding (`.m4a`, `.aac`).
- **Validation:** Files are inspected for standard MIME types (`audio/mpeg`, `audio/mp4`, `audio/aac`).

#### FR-MUSIC-02 & FR-MUSIC-03: Validation and Duplicate Detection
- **Integrity Check:** The app opens the audio stream with `MediaMetadataRetriever` to verify duration > 5000ms and valid audio track headers.
- **Duplicate Prevention:**
  1. Calculate SHA-256 hash of the audio stream.
  2. Compare against existing `audioHash` column in `SongEntity`.
  3. If matched, the file is rejected with a clear dialog: *"Song already exists in catalog: [Title] - [Artist]"*.

#### FR-MUSIC-04 & FR-MUSIC-05: Metadata Extraction & Decade Mapping
- **Automatic Extraction:** Extract ID3v2 title, artist, album, release year, genre, and embedded APIC artwork.
- **Decade Assignment Rules:**
  - 1970–1979: Classified as `DECADE_70S` ("70s Classics")
  - 1980–1989: Classified as `DECADE_80S` ("80s Hits")
  - 1990–1999: Classified as `DECADE_90S` ("90s Favorites")
  - 2000–2001: Classified as `DECADE_00S` ("2000–2001 Hits")
  - Missing/Invalid Year: Assigned to `DECADE_UNKNOWN` and flagged for manual admin correction.
- **Manual Edit Modal:** Admins can manually edit Title, Artist, Decade, Genre, and replace artwork.

---

### 2.3 Dance Studio & Video Recording (FR-STUDIO)

#### FR-STUDIO-01: External Webcam Live Viewfinder
- **Description:** The Dance Studio renders a continuous 60fps/30fps live video preview from the external Logitech USB webcam using Android CameraX / UVC integration.
- **Mirroring:** By default, front-facing webcam preview is horizontally mirrored so guest movements feel natural.

#### FR-STUDIO-02: Song & Retro Effect Selection
- **Workflow:**
  1. Guest opens the Dance Studio.
  2. A sliding drawer or bottom sheet displays the curated music library organized by decade tabs.
  3. Guest can tap any track to hear a 15-second preview.
  4. Guest selects a retro effect from the visual effect carousel:
     - *VHS Retro* (Tracking lines & timestamp)
     - *80s Neon* (Cyan/Magenta glow)
     - *70s Vintage* (Warm sepia golden hour)
     - *90s Camcorder* (Film grain & date stamp)
     - *Black & White Noir* (Classic silver screen)
     - *Clean Modern* (No filter)

#### FR-STUDIO-03: 3-Second Audio-Visual Countdown
- **Description:** Tapping the circular red Record button triggers a dramatic 3-second countdown (3... 2... 1... DANCE!) with retro synth audio beeps. The camera preview remains active so guests can frame themselves.

#### FR-STUDIO-04: Synchronized Video & Audio Recording
- **Behavior:**
  1. At countdown zero, camera capture commences and the selected song begins playing immediately through speakers.
  2. A circular progress ring around the stop button fills up smoothly over the duration (default: 30 seconds).
  3. Elapsed time and retro timecode (`REC 00:14 / 00:30`) display in bright neon red.
  4. Recording automatically terminates upon reaching the configured duration, or when the guest taps the Stop button.

---

### 2.4 Video Composition Pipeline (FR-COMP)

#### FR-COMP-01: Audio Track Muxing & Microphone Muting
- **Requirement:** The external webcam microphone audio is muted or discarded by default. The selected local music file is trimmed and bound to the video stream starting from timestamp 00:00.
- **Tooling:** AndroidX Media3 Transformer (`Transformer.Builder`).

#### FR-COMP-02 & FR-COMP-03: Hardware Acceleration & Retro Shaders
- **Video Output:** MP4 container, H.264 video codec (`MediaFormat.MIMETYPE_VIDEO_AVC`), AAC-LC audio codec.
- **Effect Application:** Retro filters are rendered via custom OpenGL ES 2.0 / 3.0 fragment shaders implemented as Media3 `GlEffect` instances.
- **Fallback Rule:** If hardware encoder or GL shader fails during transformation, the raw video is preserved and muxed without shader processing so guest effort is never lost.

---

### 2.5 Local Publishing & Confirmation (FR-PUB)

#### FR-PUB-01: Video Preview & Retake Option
- **Description:** Immediately after processing (target: < 5 seconds), the finished video loops in preview mode with full audio and visual effects.
- **Controls:**
  - **Retake Video:** Discards the recording and resets the studio to camera preview.
  - **Change Effect:** Allows re-applying a different retro filter without re-recording.
  - **ADD TO EVENT FEED:** Primary prominent call-to-action button.

#### FR-PUB-02 & FR-PUB-03: "ADD TO EVENT FEED" & Nickname
- **Terminology Rule:** Under no circumstances shall the button or copy use the word "Upload".
- **Modal Display:** Tapping "ADD TO EVENT FEED" opens a confirmation dialog:
  - Optional text field: *"Enter your nickname (optional)"* (max 20 characters).
  - Clear notice: *"Your video will be saved on this kiosk and added to the RetroTok event feed. It will not be uploaded or shared online."*
  - Action buttons: `Confirm & Add` vs `Back`.

#### FR-PUB-04: Immediate Feed Injection & Auto-Focus
- **Post-Publish Behavior:**
  1. Video file is moved from `/temp/` to `/published/`.
  2. Database entry is updated with status `PUBLISHED`.
  3. UI transitions directly to `FeedScreen`.
  4. The newly created video is positioned at the top of the feed and begins playing immediately.
  5. A celebratory retro badge flashes: *"You're in the Feed!"*.

---

### 2.6 Administrator Management (FR-ADMIN)

#### FR-ADMIN-01: PIN-Protected Security Access
- **Entry Method:** Discreet long-press (3 seconds) on the top-left RetroTok logo opens the Admin PIN keypad dialog.
- **Security:** 4-to-6 digit numeric PIN. After 5 incorrect attempts, access is locked out for 60 seconds.

#### FR-ADMIN-02 to FR-ADMIN-08: Admin Console Modules
1. **Event Settings:** Edit event name, event date, subtitle/hashtag, and active theme.
2. **Music Manager:** View all imported songs, play preview, edit metadata, trigger SAF file picker, delete songs.
3. **Video Manager:** Grid view of all event videos, play, toggle "Featured" status, delete video with confirmation.
4. **Storage Manager:** Real-time gauge of internal storage (Used, Free, Video GB, Music GB), cleanup orphan temp files, low-space alert threshold.
5. **Recording Settings:** Select default recording duration (15s, 30s, 60s), resolution (720p default, 1080p if webcam supports), countdown duration (3s, 5s).
6. **Video Effects Settings:** Enable/disable specific retro filters.
7. **Guest Permissions:** Toggle `Guest Video Deletion: ON / OFF`.
8. **App Settings:** Change admin PIN, toggle Android Lock Task mode (Kiosk Pinning), relaunch kiosk service.
