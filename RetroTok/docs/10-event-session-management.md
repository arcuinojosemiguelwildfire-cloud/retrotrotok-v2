# RetroTok — Event Session & Guest Lifecycle Specification

**Document ID:** RT-DOC-10  
**Version:** 1.0.0  
**User Model:** Anonymous Ephemeral Sessions  
**Security Boundary:** Complete On-Device Session Isolation  

---

## 1. Guest Session Architecture

Unlike standard consumer apps, RetroTok runs in a public or semi-public event kiosk environment where dozens or hundreds of different individuals interact with the same physical hardware across an evening.

To ensure privacy, prevent identity mix-ups, and eliminate friction, RetroTok uses an **Ephemeral Session Model**:

```
+-----------------------------------------------------------------------------------------+
|                               GUEST SESSION LIFECYCLE                                   |
+-----------------------------------------------------------------------------------------+
                                             |
                                  [Guest Approaches Kiosk]
                                             |
                                             v
                              [Initiate Ephemeral Session]
                              (Generate Ephemeral Session UUID)
                                             |
                     +-----------------------+-----------------------+
                     |                                               |
         [Browsing Video Feed]                           [Taps "DANCE STUDIO"]
         * Browse vertical videos                        * Selects Song & Effect
         * Like / Favorite toggle                        * 3s Countdown & Recording
         * Ephemeral like state                          * Video Preview Screen
                     |                                               |
                     |                                               v
                     |                                  [Decision: Keep or Cancel]
                     |                                      /                 \
                     |                        [Taps "ADD TO FEED"]         [Taps "Cancel"]
                     |                                 |                          |
                     v                                 v                          v
             [Inactivity Timeout]           [Persist Video & Like]         [Delete /temp/ Draft]
                     |                                 |                          |
                     +---------------------------------+--------------------------+
                                                       |
                                                       v
                                            [Session Reset Trigger]
                                         * Clear in-memory nickname
                                         * Flush draft files in /temp/
                                         * Return to For You Feed
```

---

## 2. Session Lifecycle & State Management

### 2.1 Session Initialization
- A new session is automatically instantiated whenever a user touches the screen while on the Feed or launches the Dance Studio.
- The session is assigned an ephemeral runtime UUID: `sessionId = UUID.randomUUID().toString()`.
- The session retains in-memory state:
  - `activeSessionId: String`
  - `selectedSong: SongEntity?`
  - `selectedEffect: RetroEffectType`
  - `currentRecordingDuration: Int`
  - `enteredNickname: String`
  - `sessionLikeTracker: MutableSet<String>` (prevents repeated like spam on the same video within one session).

### 2.2 Optional Guest Nickname Specification
- Guests are invited, but never forced, to provide a handle or nickname for the video overlay.
- **Validation Rules:**
  1. Maximum length: 20 characters.
  2. Input characters allowed: Alphanumeric (`A-Z`, `a-z`, `0-9`), underscores, hyphens, spaces, and standard emojis.
  3. Sanitization: Leading/trailing whitespace trimmed, HTML/SQL escape characters stripped.
  4. Empty / Skipped Fallback: If left blank, the app assigns a randomized retro moniker:
     - `@RetroDancer`
     - `@DiscoVibe`
     - `@SynthwaveHero`
     - `@GrooveMaster`
     - `@PartyLegend`

---

## 3. Inactivity Timeout & Auto-Reset Engine

Because guests frequently walk away from kiosks mid-flow, an automated watchdog resets the application:

### 3.1 Timeout Parameters
- **Idle Threshold:** Configurable by administrator (default: **60 seconds**, range: 30–180s).
- **Active Watcher Screens:** `DanceStudioScreen`, `SongPickerSheet`, and `VideoPreviewScreen`.
- **Feed Screen Exception:** The `FeedScreen` does NOT reset to another screen on idle; it continues looping videos indefinitely.

### 3.2 Reset Execution Sequence
When the inactivity timer expires without user touch events:
1. Stop active video capture (if recording).
2. Cancel active Media3 Transformer composition jobs.
3. Delete all staging files matching `/temp/raw_*` and `/temp/composed_*`.
4. Clear the nickname input buffer.
5. Release camera viewfinder surfaces.
6. Pop the navigation backstack to the root `FeedScreen`.
7. Resume automatic video feed playback.

---

## 4. Guest Video Deletion Policy Specification

```
                                  [Admin Configures Kiosk]
                                             |
                                             v
                             [Guest Video Deletion Setting]
                                      /              \
                          [Option A: OFF]          [Option B: ON]
                            (DEFAULT)                    |
                                 |                       v
                                 |            [Guest Finishes Recording]
                                 |                       |
                                 |            [Video Published to Feed]
                                 |                       |
                                 |            [60s Active Session Window]
                                 |                       |
                                 |            [Guest Taps Delete Button]
                                 |                       |
                                 |            [Confirmation Dialog]
                                 |            "Delete your recording?"
                                 |                       |
                                 |            [Confirm] ---> Video Deleted
                                 v
                     [Guests CANNOT Delete Videos]
                     (Prevents accidental or malicious removal)
                     (Admin PIN Deletion Always Available)
```

### 4.1 Policy Rules & Guarantees
1. **Default Mode (OFF):** Guests have no deletion controls once a video is added to the feed. Only event administrators with the master PIN can remove videos. This protects guests from having their performances maliciously deleted by others.
2. **Permissive Mode (ON):** When enabled by the organizer, a guest can delete their video *during the active session* (on the Preview screen or via a temporary "Undo / Delete" action banner on the feed for 60 seconds immediately following publishing).
3. **Admin Master Override:** Event administrators can delete any video at any time through `AdminVideoScreen` regardless of the guest policy toggle.
