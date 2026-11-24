# RetroTok — System Architecture Specification

**Document ID:** RT-DOC-04  
**Version:** 1.0.0  
**Pattern:** Clean Architecture + MVVM + Unidirectional Data Flow (UDF)  
**Target Platform:** Android 16 (API Level 36) Native Kotlin / Jetpack Compose  

---

## 1. Architectural Principles & Overview

RetroTok adopts **Clean Architecture** combined with **Model-View-ViewModel (MVVM)** and **Unidirectional Data Flow (UDF)**. This guarantees strict separation between UI rendering, business rules, media processing pipelines, and local data persistence.

```
+-----------------------------------------------------------------------------------------------+
|                                      PRESENTATION LAYER                                       |
|  Jetpack Compose Screens  <--->  M3 Theming & Design System  <--->  Compose Navigation / State |
|                                              ^                                                |
|                                              | UI Events (Intent) & UI State (StateFlow)      |
|                                              v                                                |
|                                      VIEWMODEL LAYER                                          |
|      FeedViewModel        StudioViewModel        PreviewViewModel         AdminViewModel      |
+-----------------------------------------------------------------------------------------------+
                                               |
                                               v Executes UseCases
+-----------------------------------------------------------------------------------------------+
|                                        DOMAIN LAYER                                           |
|  [UseCases / Interactors]                                                                     |
|  * GetFeedVideosUseCase                  * RecordVideoUseCase                                 |
|  * ToggleVideoLikeUseCase                * ComposeVideoTrackUseCase                           |
|  * ImportLocalMusicUseCase               * PublishVideoToFeedUseCase                          |
|  * ValidateAdminPinUseCase               * CleanTempStorageUseCase                            |
+-----------------------------------------------------------------------------------------------+
                                               |
                                               v Interacts with Repositories
+-----------------------------------------------------------------------------------------------+
|                                         DATA LAYER                                            |
|   +-----------------------+  +-----------------------+  +--------------------------------+   |
|   |   VideoRepository     |  |    MusicRepository    |  |    KioskSettingsRepository     |   |
|   +-----------+-----------+  +-----------+-----------+  +---------------+----------------+   |
|               |                          |                              |                     |
|               +--------------------+     |     +------------------------+                     |
|                                    v     v     v                                              |
|   +-------------------------------+  +-----------------------------+  +--------------------+  |
|   | Room SQLite Database          |  | LocalStorageManager (Files) |  | DataStore (Prefs)  |  |
|   | * VideoEntity   * SongEntity  |  | * /videos/published/        |  | * Admin PIN Hash   |  |
|   | * EventEntity   * Likes       |  | * /music/ & /thumbnails/    |  | * Kiosk Configs    |  |
|   +-------------------------------+  +-----------------------------+  +--------------------+  |
+-----------------------------------------------------------------------------------------------+
                                               |
                                               v Interacts with Hardware & OS Subsystems
+-----------------------------------------------------------------------------------------------+
|                                    HARDWARE & ENGINE LAYER                                    |
|   +-----------------------+  +-----------------------+  +--------------------------------+   |
|   | ExoPlayerFeedManager  |  | CameraX / UVC Manager |  |   Media3 Transformer Engine    |   |
|   | (Dual-decoder pool)   |  | (Logitech USB Webcam) |  |   (GLSL Shaders + AAC/AVC Mux) |   |
|   +-----------------------+  +-----------------------+  +--------------------------------+   |
|                                                                                               |
|   Android 16 Core: SurfaceView | MediaCodec | USB Host API | AudioTrack | LockTaskMode        |
+-----------------------------------------------------------------------------------------------+
```

---

## 2. Layer Decomposition

### 2.1 Presentation Layer (Jetpack Compose)
- **Declarative UI:** All views are built using pure Jetpack Compose functions adhering strictly to Material 3 principles and custom RetroTok styling tokens.
- **State Hoisting & UDF:** Composable functions do not hold mutable business state. They receive immutable UI State dataclasses and emit UI event lambdas to their corresponding ViewModels.
- **Screen Navigation:** Managed via Jetpack Navigation Compose with type-safe screen destinations. Sub-screens include back-handling logic to gracefully return to the primary feed.

### 2.2 ViewModel Layer
- ViewModels subclass `androidx.lifecycle.ViewModel`.
- Each ViewModel maintains a private `MutableStateFlow<UiState>` and exposes an immutable public `StateFlow<UiState>`.
- State is collected in Compose using `collectAsStateWithLifecycle()` to avoid resource waste when screens are off-screen or stopped.
- Asynchronous jobs are bounded to `viewModelScope`, ensuring automatic cancellation when a guest navigates away.

### 2.3 Domain Layer (UseCases)
- Single-responsibility interactors encapsulating specific business rules.
- Pure Kotlin classes without Android framework dependencies, making them trivially unit-testable.
- Examples:
  - `ComposeVideoTrackUseCase`: Orchestrates the raw video capture, local audio track trimming, GLSL shader selection, and delegates to the media engine.
  - `PublishVideoToFeedUseCase`: Updates database status, transfers files from staging to published directory, and broadcasts feed refresh.

### 2.4 Data Layer (Repositories & Data Sources)
- **Single Source of Truth:** Repositories abstract underlying storage mechanisms. The UI never interacts directly with SQLite or disk files.
- **Data Sources:**
  1. `RetroTokDatabase`: Room 2.7.0 database managing relational metadata for videos, songs, likes, and playlists.
  2. `LocalStorageManager`: Manages file I/O operations inside `context.filesDir` (creating folders, moving files, computing checksums, deleting temp drafts).
  3. `KioskPreferences`: Encrypted Jetpack DataStore managing admin PIN hashes, kiosk toggles, and duration defaults.

### 2.5 Media & Hardware Engine Layer
- **`ExoPlayerFeedManager`:** A specialized player controller that recycles two `ExoPlayer` instances to provide seamless 9:16 vertical video feed transitions without player recreation overhead.
- **`WebcamCaptureEngine`:** Interfaces with the external Logitech USB webcam via Android CameraX external camera API (or native UVC USB Host mode if the device lacks Camera2 external HAL).
- **`Media3TransformerEngine`:** Manages hardware-accelerated video transformation, applying OpenGL ES fragment shaders (VHS, Neon, Vintage) and remuxing audio tracks.

---

## 3. Asynchronous Concurrency & Threading Model

```
+-----------------------------------------------------------------------------------------+
|                                COROUTINE DISPATCHER BUDGET                              |
+-----------------------------------------------------------------------------------------+
| Dispatchers.Main.immediate | UI event handling, Compose recomposition, StateFlow updates|
| Dispatchers.Default        | GLSL shader computation, ID3 tag parsing, audio hashing    |
| Dispatchers.IO             | Room DB transactions, File disk I/O, SAF streaming         |
| Media3 Background Threads  | Hardware MediaCodec encoding/decoding, audio resampling    |
+-----------------------------------------------------------------------------------------+
```

1. **Main Thread Safety:** No disk reads, SQLite queries, or audio/video processing operations are permitted on `Dispatchers.Main`. All repository functions are marked `suspend` and explicitly switch dispatchers.
2. **Backpressure Handling:** Video feed scroll events are debounced to prevent rapid swipe thrashing from spawning dozens of unneeded media buffering jobs.
3. **Cancellation Safety:** When a user taps "Retake", the active composition job is cancelled via `coroutineContext.job.cancelChildren()`, immediately freeing codec and GPU buffers.

---

## 4. Lifecycle & Error Boundary Architecture

```
                                  [App Launch]
                                       |
                                       v
                              [Init Local Database]
                                       |
                                       v
                        [Storage Space Health Check]
                           /                       \
        [Storage >= 500MB]                           [Storage < 500MB]
                |                                            |
                v                                            v
     [Load Published Videos]                      [Show Storage Full Modal]
                |                                            |
                v                                            v
     [Start Feed Screen]                         [Admin Keypad Only]
```

- **Uncaught Exception Boundary:** Critical crashes in the camera or video player are caught at the ViewModel boundary, logging diagnostics to a local diagnostics file and safely routing the guest back to the Feed Screen rather than terminating the kiosk process.
