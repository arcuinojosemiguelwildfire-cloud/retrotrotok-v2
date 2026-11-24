# RetroTok — Screen Specifications & Navigation Flow

**Document ID:** RT-DOC-06  
**Version:** 1.0.0  
**Resolution Baseline:** 720 × 1280 (Portrait, Vertical Touchscreen Kiosk)  
**UI Framework:** Jetpack Compose (Material 3 + RetroTok Custom Theme)  

---

## 1. Screen Navigation State Machine & Backstack

```
                               +-----------------------------+
                               |     APP START / SPLASH      |
                               +--------------+--------------+
                                              |
                                              v
      +--------------------------------> [ FEED SCREEN ] <--------------------------------+
      |                                  (For You Feed)                                    |
      |                                        |                                           |
      |                 +----------------------+----------------------+                    |
      |                 |                                             |                    |
      |                 | [Tap "DANCE STUDIO" FAB]                    | [Long-press Logo]  |
      |                 v                                             v                    |
      |       [ SONG PICKER SHEET ]                         [ ADMIN PIN DIALOG ]           |
      |       (70s, 80s, 90s, 00-01)                        (4-6 Digit Keypad)             |
      |                 |                                             |                    |
      |                 | [Select Track]                              | [Valid PIN]        |
      |                 v                                             v                    |
      |       [ DANCE STUDIO SCREEN ]                       [ ADMIN DASHBOARD ]            |
      |       (Webcam + Effect + Record)                     (8 Module Grid)               |
      |                 |                                       |     |     |              |
      |                 | [Record Finished]                     v     v     v              |
      |                 v                                     Admin Sub-Screens            |
      |       [ VIDEO PREVIEW SCREEN ]                        (Music, Video, Settings)     |
      |       (Loop Play + Mux + Effects)                             |                    |
      |            |         |                                        +--------------------+
      |   [Retake] |         | [Tap "ADD TO EVENT FEED"]
      |            v         v
      |   (Discard Raw)  [ PUBLISHING MODAL ]
      |                      |
      |                      | [Saved to Storage & Room]
      +----------------------+
```

---

## 2. Comprehensive Screen Specifications

### 2.1 Screen 1: For You Feed (`FeedScreen`)
- **Primary Function:** Continuous, full-screen vertical short-video feed playing event recordings and admin-imported media.
- **Layout Architecture (720 × 1280):**

```
+-------------------------------------------------------------+ 0,0
| [LOGO] RetroTok          #Acme80sParty      [ADMIN GESTURE] | 0 - 64dp
|-------------------------------------------------------------|
|                                                             |
|                                                     +-----+ |
|                                                     |AVTR | | 320dp
|                                                     +-----+ |
|                                                             |
|                   FULL-SCREEN VIDEO PLAYBACK                |
|                    (ExoPlayer SurfaceView)                  |
|                        Aspect 9:16                          |
|                                                     +-----+ |
|                                                     | [♥] | | 540dp (Like)
|                                                     | 142 | |
|                                                     +-----+ |
|                                                             |
|                                                     +-----+ |
|                                                     | [★] | | 660dp (Fav)
|                                                     | 38  | |
|                                                     +-----+ |
|                                                             |
|                                                     +-----+ |
|                                                     | [O] | | 780dp (Vinyl)
|                                                     +-----+ |
|                                                             |
| @DiscoDancer99                                              |
| 1983 • Synthpop Classic [VHS Effect]                        |
| [♪ Sweet Dreams (Are Made of This) - Eurythmics           ] | 1140dp
|-------------------------------------------------------------|
|                [ + DANCE STUDIO (RECORD) ]                  | 1200 - 1280dp
+-------------------------------------------------------------+ 720,1280
```

- **Interactive Elements & Touch Targets:**
  1. **VerticalPager:** Full-screen touch handler. Vertical drag/swipe navigates adjacent videos. Double-tap triggers Like action with floating heart burst at touch coordinates.
  2. **Top-Left Logo (`RetroTok`):** Touch target 80dp × 48dp. Discreet 3-second long-press triggers `AdminPinDialog`.
  3. **Right Action Column:**
     - *Like Button:* 64dp × 64dp touch target. Displays animated heart icon + counter text.
     - *Favorite Button:* 64dp × 64dp touch target. Displays animated star icon + counter text.
     - *Spinning Vinyl Icon:* 56dp × 56dp disc rotating continuously at 33 RPM when music is playing.
  4. **Bottom Metadata Overlay:**
     - Creator Nickname: Text typography `headlineSmall` (bold white).
     - Song Marquee: Horizontal auto-scrolling text ticker with musical note icon.
  5. **Floating Bottom Navigation Bar:**
     - Centered Pill CTA: **"DANCE STUDIO"** with neon recording dot. Height: 64dp, Width: 280dp.

---

### 2.2 Screen 2: Song Picker Bottom Sheet (`SongPickerSheet`)
- **Primary Function:** Fast, tactile browsing of the nostalgic music library before entering the recording session.
- **Components:**
  1. **Header:** Title *"Choose Your Soundtrack"*, Close Button (56dp touch target).
  2. **Decade Filter Tabs (Horizontal Scroll / Row):**
     - `70s Classics` | `80s Hits` | `90s Favorites` | `2000–2001` | `All Songs`
     - Height: 48dp pill chips with neon selection indicators.
  3. **Search Bar:** Outlined text field with clear button for filtering by song title or artist.
  4. **Song List (`LazyColumn`):**
     - Row Height: 76dp.
     - Left: 56dp × 56dp album art or retro cassette thumbnail.
     - Center: Song Title (bold, 16sp), Artist Name + Release Year (14sp).
     - Right: Play/Pause Preview button (56dp target) + "Select" button (64dp target).
  5. **Audio Preview Rule:** Tapping preview plays the audio locally through `ExoPlayer` for up to 15 seconds. Selecting the song passes the track to `DanceStudioScreen`.

---

### 2.3 Screen 3: Dance Studio Recording Screen (`DanceStudioScreen`)
- **Primary Function:** Full-screen live camera viewfinder from the external Logitech USB webcam, effect selection, and recording trigger.
- **Layout Architecture (720 × 1280):**

```
+-------------------------------------------------------------+
| [X] Cancel         [♪ Stayin' Alive - Bee Gees]    [FLIP CAM]| 0 - 64dp
|-------------------------------------------------------------|
|                                                             |
|                                                             |
|                                                             |
|                   LIVE WEBCAM VIEWFINDER                    |
|                   (CameraX PreviewView)                     |
|                                                             |
|                    [ 3 ... 2 ... 1 ]                        | 600dp (Countdown)
|                                                             |
|                                                             |
|                                                             |
|   [VHS Retro]  [80s Neon]  [*70s Vintage*]  [90s Film]     | 1040dp (Carousel)
|-------------------------------------------------------------|
|                       REC 00:18 / 00:30                     | 1120dp
|                              ( O )                          | 1160 - 1260dp
|                      [ BIG RECORD BUTTON ]                  | (88dp Target)
+-------------------------------------------------------------+
```

- **Key Interactive Components:**
  1. **Top Bar:** Back/Cancel button, active selected song pill (tapping re-opens picker), optional camera flip / mirror toggle.
  2. **Retro Effect Carousel:** Horizontal snapping row of circular filter presets. Selecting an effect applies visual feedback instantly.
  3. **Countdown Overlay:** High-contrast retro digital numbers (120sp) with neon drop shadow during the 3-second lead-in.
  4. **Record / Stop Button:** 
     - Idle State: 88dp circular red button with pulsing white border.
     - Recording State: Circular animated progress ring tracking elapsed time (0 to 30s) around a square Stop icon.
     - Timecode: Blinking neon-red badge: `REC 00:14 / 00:30`.

---

### 2.4 Screen 4: Video Preview & Confirmation (`VideoPreviewScreen`)
- **Primary Function:** Review the recorded video with applied music soundtrack and retro shader, enter optional nickname, and confirm publishing.
- **Components:**
  1. **Looping Video Viewport:** Automatically loops the finalized muxed video.
  2. **Top Controls:** "Retake" button (discards temporary file and returns to studio).
  3. **Bottom Card:**
     - Nickname Input Field: *"Your Nickname (optional)"* (single-line, 20-character limit).
     - Effect Switcher: Quick chips to re-render with an alternate filter if desired.
     - Primary Action Button: **"ADD TO EVENT FEED"** (Full width, 64dp height, neon gradient fill `#FF2A85` to `#7928CA`).

---

### 2.5 Screen 5: Publishing Modal (`PublishingModal`)
- **Primary Function:** Transparent overlay displaying local save progress, privacy disclaimer, and celebration.
- **Copy:**
  - *"Saving your video to RetroTok..."*
  - *"Your video will be saved on this kiosk and added to the RetroTok event feed. It will not be uploaded or shared online."*
- **Success State:** Displays checkmark animation, confetti bursts, and transitions immediately to the top of the `FeedScreen`.

---

### 2.6 Screen 6: Admin PIN Dialog (`AdminPinDialog`)
- **Primary Function:** Authenticates organizers and event staff to access the management panel.
- **Layout:**
  - 4 or 6 masked PIN circles (`* * * *`).
  - Large numeric keypad (0–9, Clear, Backspace) with minimum 64dp touch targets.
  - Rate-limit banner if locked out: *"Too many failed attempts. Try again in 48s."*

---

### 2.7 Screens 7–10: Admin Console Sub-Screens
- **`AdminDashboardScreen`:** Grid of 8 management tiles with status summaries (e.g., *Music Catalog: 42 Songs*, *Event Videos: 68*, *Storage: 14.2 GB Free*).
- **`AdminMusicScreen`:** Audio catalog list, "Import Songs" button triggering Android SAF document picker, song edit modal.
- **`AdminVideoScreen`:** Thumbnail grid of all guest recordings, playback preview dialog, "Delete Video" button, "Feature Video" toggle.
- **`AdminSettingsScreen`:** Recording duration picker (15s, 30s, 60s), Guest Deletion toggle (`ON / OFF`), storage cache vacuum button, Lock Task Mode toggle.
