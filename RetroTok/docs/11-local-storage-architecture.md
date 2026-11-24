# RetroTok — Local Storage & File Management Architecture

**Document ID:** RT-DOC-11  
**Version:** 1.0.0  
**Storage Target:** Internal Application-Private Storage (`context.filesDir`)  
**Android Security Model:** Scoped Storage (Zero Dangerous Runtime Storage Permissions Required)  

---

## 1. Directory Tree & Partitioning

All persistent media and temporary operational data are isolated within application-private directories. This guarantees that other installed applications cannot tamper with or inspect event media, and avoids requiring legacy runtime storage permissions (`READ_EXTERNAL_STORAGE`).

```
/data/data/com.aistudio.retrotok.kiosk/files/
│
├── music/                                   # Permanent imported music catalog
│   ├── {song_uuid}.mp3                      # Master audio tracks
│   └── artwork/                             # Extracted album artwork
│       └── {song_uuid}.webp                 # 120x120 downsampled WebP thumbnails
│
├── videos/
│   ├── published/                           # Event Feed finalized videos (H.264 MP4)
│   │   ├── vid_{video_uuid}.mp4             # 720x1280 @ 30fps with muxed audio
│   │   └── ...
│   │
│   ├── thumbnails/                          # Fast poster images for feed preloading
│   │   ├── thumb_{video_uuid}.webp          # 720x1280 WebP first-frame capture
│   │   └── ...
│   │
│   └── temp/                                # Staging & in-progress recordings
│       ├── raw_capture_{uuid}.mp4           # Direct camera encoder dump
│       ├── mux_staging_{uuid}.mp4           # Media3 Transformer working buffer
│       └── ...
│
└── diagnostics/                             # Local operational logs
    └── kiosk_events.log                     # Error and crash diagnostic logs
```

---

## 2. Retention Rules & Integrity Protections

| Directory | Persistence Guarantee | Cleanup Policy | Re-creatable? |
| :--- | :--- | :--- | :--- |
| `/music/` | **Permanent** | Explicit Admin Deletion only | No (Master file) |
| `/music/artwork/` | **Permanent** | Deleted automatically if song is deleted | Yes (from ID3) |
| `/videos/published/`| **Permanent** | Admin Deletion (or Guest Deletion if enabled) | No (Guest performance) |
| `/videos/thumbnails/`| **Cache** | Purged if storage critically low | Yes (from MP4) |
| `/videos/temp/` | **Ephemeral** | Cleared upon session reset, app launch, or retake | N/A (Temporary) |

### 2.1 The "Never-Delete" Invariant
> [!CRITICAL]
> Under no automated routine shall the application delete files from `/videos/published/` or `/music/`. Cache cleanup, low-storage handlers, and garbage collection sweeps must strictly constrain their target paths to `/videos/temp/` and non-essential thumbnail caches.

---

## 3. Storage Health & Capacity Monitoring Engine

### 3.1 Real-Time Storage Measurement
The storage manager queries Linux file system statistics via `StatFs`:

```kotlin
class StorageHealthMonitor(private val context: Context) {

    fun getStorageMetrics(): StorageMetrics {
        val path = context.filesDir
        val stat = StatFs(path.path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong

        val totalBytes = totalBlocks * blockSize
        val freeBytes = availableBlocks * blockSize

        val musicBytes = calculateDirectorySize(File(path, "music"))
        val videoBytes = calculateDirectorySize(File(path, "videos/published"))
        val tempBytes = calculateDirectorySize(File(path, "videos/temp"))

        return StorageMetrics(
            totalBytes = totalBytes,
            freeBytes = freeBytes,
            musicCatalogBytes = musicBytes,
            publishedVideosBytes = videoBytes,
            tempDraftBytes = tempBytes,
            estimatedRecordingsRemaining = (freeBytes / ESTIMATED_30S_VIDEO_BYTES).toInt()
        )
    }

    companion object {
        const val ESTIMATED_30S_VIDEO_BYTES = 25 * 1024 * 1024L // 25 MB average
    }
}
```

### 3.2 Automated Safeguards & Hard Thresholds

```
                          [Available Free Storage Space]
                                        |
           +----------------------------+----------------------------+
           |                                                         |
     [ > 2.0 GB ]                                             [ <= 2.0 GB ]
           |                                                         |
           v                                                         v
    [NORMAL STATE]                                        [LOW STORAGE WARNING]
    * All features active                                 * Amber badge in Admin Panel
    * Background recording enabled                        * Temp file auto-sweep runs
                                                                     |
                                                                     v
                                                              [ <= 500 MB ]
                                                                     |
                                                                     v
                                                          [HARD RECORDING BLOCK]
                                                          * Record button disabled
                                                          * Alert dialog on Studio tap
                                                          * Feed browsing unaffected
```

1. **Storage Warning Threshold (Free Space <= 2.0 GB):** 
   - An amber warning icon appears in the admin header.
   - The app triggers an automatic sweep of `/videos/temp/` to reclaim dangling draft files.
2. **Hard Block Threshold (Free Space <= 500 MB):** 
   - The Dance Studio disables the Record button to prevent corrupted video writes or out-of-space crashes.
   - An informative toast/dialog alerts the user: *"Kiosk storage is full. Please alert the event organizer."*
   - Playback and feed browsing remain 100% operational.

---

## 4. Temporary File Cleanup & Orphan Reconciliation

1. **Cold Boot Sweep:** During application initialization (`RetroTokApp.onCreate()`), any orphan files remaining in `/videos/temp/` (e.g., from an abrupt power unplug during an event) are safely purged before the UI loads.
2. **Atomic Publication:** When a video is published, it is moved from `/temp/` to `/published/` via `File.renameTo()` (an atomic filesystem operation on the same partition), ensuring zero risk of half-written files in the event feed.
