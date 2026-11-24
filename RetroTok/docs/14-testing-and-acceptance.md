# RetroTok — Testing Strategy & Acceptance Criteria Specification

**Document ID:** RT-DOC-14  
**Version:** 1.0.0  
**Testing Frameworks:** JUnit 4, Robolectric, Jetpack Compose UI Test (`createComposeRule()`)  
**Deployment Target:** Commercial Touchscreen Kiosk Validation  

---

## 1. Multi-Tier Testing Strategy

RetroTok adopts a 4-tier testing hierarchy to validate functionality without requiring external cloud mocks:

```
+-----------------------------------------------------------------------------------------+
|                                    TESTING PYRAMID                                      |
+-----------------------------------------------------------------------------------------+
|  Tier 4: Physical Hardware Soak & Kiosk Soak Testing (12h Uptime, USB Disconnects)     |
+-----------------------------------------------------------------------------------------+
|  Tier 3: End-to-End Critical User Journey (CUJ) Tests (Robolectric + Compose Test Rules) |
+-----------------------------------------------------------------------------------------+
|  Tier 2: Component Integration Tests (Room DAOs, Media3 Transformer, SAF Parser)         |
+-----------------------------------------------------------------------------------------+
|  Tier 1: Fast Local Unit Tests (Domain UseCases, PIN Security, Decade Categorization)  |
+-----------------------------------------------------------------------------------------+
```

---

## 2. Critical User Journeys (CUJ) & Acceptance Criteria

### CUJ-01: Guest Browses For You Feed
- **Preconditions:** At least 3 published videos exist in the local database.
- **Action:** Guest approaches kiosk and swipes vertically up and down.
- **Expected Outcome:**
  - Videos transition smoothly within 200ms.
  - Current video plays in a continuous 9:16 vertical loop.
  - Attached soundtrack plays automatically through speakers.
  - Swiping to next video immediately cuts prior audio and starts the new track.
  - Creator nickname and song ticker display accurately in the overlay.

### CUJ-02: Guest Likes & Favorites Video
- **Preconditions:** `FeedScreen` is actively playing a video.
- **Action:** Guest taps the Heart icon or double-taps anywhere on the screen.
- **Expected Outcome:**
  - Heart icon explodes with an elastic bounce animation.
  - Local database counter increments by 1 immediately.
  - Floating translucent heart animates and fades at tap location.
  - Repeated double-taps in the same session do not inflate like counter abnormally.

### CUJ-03: Music Library Browsing & Selection
- **Preconditions:** Music catalog contains imported songs across multiple decades.
- **Action:** Guest taps "DANCE STUDIO", views decade tabs, taps "80s Hits", and taps preview on an 80s track.
- **Expected Outcome:**
  - `SongPickerSheet` opens smoothly from bottom.
  - Tapping "80s Hits" instantly filters the list to tracks released 1980–1989.
  - Tapping preview plays the audio locally for 15 seconds.
  - Tapping "Select" transfers the track to the studio recorder and closes the picker.

### CUJ-04: Webcam Dance Studio Recording
- **Preconditions:** External Logitech USB webcam is connected and live. Selected track is loaded.
- **Action:** Guest selects "VHS Retro" effect and taps the big circular Record button.
- **Expected Outcome:**
  - 3-second visual countdown ticks on screen (3... 2... 1... DANCE!) with audio synth beeps.
  - At zero, camera video capture starts and music plays synchronously.
  - Timecode increments (`REC 00:xx / 00:30`) with a pulsing neon red indicator.
  - Progress ring fills circularly over 30 seconds.
  - Recording stops automatically at 30 seconds and transitions to composition state.

### CUJ-05: Video Preview & Local Feed Publication
- **Preconditions:** Video composition finishes.
- **Action:** Guest watches the preview loop, enters nickname `"DiscoDave"`, and taps **"ADD TO EVENT FEED"**.
- **Expected Outcome:**
  - Video loops continuously with the selected retro effect and music track.
  - Tapping "ADD TO EVENT FEED" displays the confirmation modal with the clear privacy notice: *"Your video will be saved on this device and added to the RetroTok event feed. It will not be uploaded or shared online."*
  - Confirming saves the video to `/videos/published/` and updates Room status to `PUBLISHED`.
  - The kiosk automatically routes to the `FeedScreen`, positioning the new recording at the top of the feed and playing it immediately.

### CUJ-06: Guest Retake Flow
- **Preconditions:** Guest is on `VideoPreviewScreen`.
- **Action:** Guest is unsatisfied with their recording and taps "Retake".
- **Expected Outcome:**
  - A confirmation dialog asks: *"Discard this take and record again?"*.
  - Confirming purges the temporary files from `/videos/temp/`.
  - The UI returns to the live webcam viewfinder with the same song and effect pre-selected.

### CUJ-07: Administrator PIN Access & Music Ingestion
- **Preconditions:** Kiosk is on `FeedScreen`.
- **Action:** Organizer long-presses the top-left RetroTok logo for 3 seconds, enters PIN `1234`, and opens Music Manager.
- **Expected Outcome:**
  - Obfuscated PIN keypad appears with high-contrast numeric keys.
  - Entering correct PIN navigates to `AdminDashboardScreen`.
  - Admin opens Music Manager, taps "Import Songs", selects 3 MP3 files from USB.
  - The app validates files, extracts ID3 tags, detects no duplicates, copies audio to `/music/`, and inserts records into Room.

### CUJ-08: Admin Moderation & Storage Inspection
- **Preconditions:** Multiple guest recordings exist.
- **Action:** Admin opens Video Manager, selects an inappropriate video, and taps "Delete".
- **Expected Outcome:**
  - Preview dialog confirms video deletion.
  - Upon confirmation, the MP4 file in `/videos/published/` is permanently unlinked and deleted from disk.
  - The Room record is removed.
  - Feed no longer contains the deleted item.
  - Storage Manager reflects immediate reclamation of disk space.

---

## 3. Automated Robolectric Test Template (Sample)

```kotlin
@RunWith(RobolectricTestRunner::class)
class LocalPublishingPipelineTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var database: RetroTokDatabase
    private lateinit var videoDao: VideoDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RetroTokDatabase::class.java).build()
        videoDao = database.videoDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun publishingVideo_insertsPublishedEntity_andEmitsToFeed() = runTest {
        val testVideo = VideoEntity(
            id = "test-uuid-123",
            eventId = "event-active",
            songId = "song-80s-1",
            videoFilePath = "/data/data/test/files/videos/published/vid_test.mp4",
            thumbnailPath = "/data/data/test/files/videos/thumbnails/thumb_test.webp",
            rawRecordingPath = null,
            creatorNickname = "RetroDancer",
            durationMs = 30000L,
            appliedEffect = "VHS",
            processingStatus = VideoProcessingStatus.PUBLISHED
        )

        videoDao.insertVideo(testVideo)

        val feedVideos = videoDao.getPublishedFeedVideos().first()
        assertEquals(1, feedVideos.size)
        assertEquals("test-uuid-123", feedVideos[0].id)
        assertEquals("RetroDancer", feedVideos[0].creatorNickname)
    }
}
```

---

## 4. Hardware Soak & Acceptance Checklist

To pass Phase 0 acceptance, the kiosk deployment must fulfill the following verifiable criteria:

| Area | Acceptance Criterion | Target Metric | Pass/Fail |
| :--- | :--- | :--- | :--- |
| **Feed Responsiveness** | Swipe-to-play start latency | < 250 ms | [ ] |
| **Recording Sync** | Audio/video lip-sync offset | < 45 ms | [ ] |
| **Composition Speed** | 30s video transformation time | <= 12 seconds | [ ] |
| **Storage Hygiene** | Clean `/temp/` draft purge on reset | 100% files removed | [ ] |
| **Kiosk Soak** | Continuous looped feed playback | 12 hours zero crash | [ ] |
| **USB Hotplug** | Unplug/replug webcam during preview | Auto-rebind < 3s | [ ] |
| **Security** | Brute-force 5 failed PIN attempts | 60s hard lockout | [ ] |
| **Privacy Boundary** | Outbound network packet inspection | Zero bytes transmitted | [ ] |
