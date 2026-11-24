# RetroTok — Video Recording & Composition Pipeline Specification

**Document ID:** RT-DOC-09  
**Version:** 1.0.0  
**Video Resolution Target:** 720 × 1280 (Portrait 9:16) @ 30 FPS  
**Video Codec:** H.264 / AVC (`video/avc`), AAC-LC Audio (`audio/mp4a-latm`)  
**Processing Engine:** AndroidX Media3 Transformer + Custom OpenGL ES Shaders (`GlEffect`)  

---

## 1. Video Capture Pipeline & Camera Architecture

### 1.1 Hardware Reality & Dual-Path Architecture
The kiosk employs an external Logitech USB webcam mounted vertically at eye level. In Android 16, USB cameras behave differently depending on the device's system Board Support Package (BSP) and USB Camera HAL implementation.

To guarantee 100% operational success across varied kiosk hardware, RetroTok implements a **Dual-Path Camera Architecture**:

```
                                  [Logitech USB Webcam Plugged In]
                                                 |
                                                 v
                                  [Check Device HAL Capabilities]
                                                 |
                     +---------------------------+---------------------------+
                     |                                                       |
        [Camera2 Supports EXTERNAL]                              [Camera2 Lacks EXTERNAL]
                     |                                                       |
                     v                                                       v
         +-----------------------+                               +-----------------------+
         |     PATH A (PRIMARY)  |                               |    PATH B (FALLBACK)  |
         | CameraX / Camera2 API |                               | Android USB Host API  |
         | LENS_FACING_EXTERNAL  |                               | Native UVC / JNI      |
         | Standard PreviewView  |                               | libuvccamera Surface  |
         +-----------------------+                               +-----------------------+
                     |                                                       |
                     +---------------------------+---------------------------+
                                                 |
                                                 v
                                   [WebcamCaptureEngine Interface]
                                   (Provides Frame Stream & VideoCapture)
```

1. **Path A (Standard Android CameraX):** When the Android 16 kiosk OS implements the standard external camera HAL (`PackageManager.FEATURE_CAMERA_EXTERNAL`), CameraX binds seamlessly to `CameraSelector.LENS_FACING_EXTERNAL`. This is the preferred, lowest-overhead path.
2. **Path B (Android USB Host Mode & UVC JNI):** If the kiosk hardware board runs an AOSP build without the external camera HAL, RetroTok communicates directly with the Logitech webcam over the Android USB Host API (`UsbManager`), utilizing a native Linux UVC (USB Video Class) driver (`libuvccamera`) to stream frames directly into an OpenGL surface.

---

## 2. Recording State Machine & Lifecycle

```
[ IDLE ]
   |
   | User selects song & taps "RECORD"
   v
[ COUNTDOWN ] -------------------> Display 3..2..1 on live preview
   |                               Audio beep on each tick
   | Timer expires (3000ms)
   v
[ RECORDING ] -------------------> 1. Trigger CameraX VideoCapture.start()
   |                               2. Trigger ExoPlayer audio playback
   |                               3. Animate 360-degree circular progress bar
   |                               4. Increment blinking REC 00:xx timecode
   | Duration reached (30s) OR User taps "STOP"
   v
[ STOPPING ] --------------------> 1. VideoCapture.stop()
   |                               2. ExoPlayer.pause()
   | Raw MP4 written to /temp/raw_{uuid}.mp4
   v
[ COMPOSING ] -------------------> 1. Launch Media3 Transformer
   |                               2. Replace audio track with selected music
   |                               3. Apply selected retro GLSL shader
   | Composition finishes (< 10s)
   v
[ PREVIEW_READY ] ---------------> Loop muxed video in VideoPreviewScreen
   |
   +----> User taps "RETAKE" --------> Delete /temp/ files -> Return to [ IDLE ]
   |
   +----> User taps "ADD TO FEED" ---> Move to /published/ -> Inject into Feed
```

---

## 3. Audio / Video Synchronization Engine

A common pitfall in kiosk recording is audio-video drift caused by hardware buffer latencies between the camera start command and speaker playback start.

### 3.1 Latency Compensation Strategy
1. **Camera Pre-Warming:** The camera recording session is armed during the 3-second countdown (`prepareRecording()`), ensuring hardware encoder surfaces and video pipelines are running hot before frame 0.
2. **Zero-Timestamp Synchronizer:**
   - At countdown expiration, the audio player (`ExoPlayer`) is started with an explicit timestamp reference: `val recordStartTimeNs = System.nanoTime()`.
   - When the first video frame arrives, its presentation timestamp (PTS) is recorded.
   - During the Media3 Transformer composition stage, the audio track is trimmed with an exact offset corresponding to `(firstVideoFramePts - recordStartTimeNs)` to ensure perfect lip-sync and dance beat alignment.
3. **Webcam Microphone Muting:** The audio track from the Logitech webcam microphone contains room echo, crowd noise, and loudspeaker feedback. During composition, the webcam audio track is completely stripped, replacing it with the pristine 320kbps stereo soundtrack from the local music file.

---

## 4. Media3 Transformer Composition Engine

RetroTok utilizes **AndroidX Media3 Transformer** (`androidx.media3:media3-transformer`) for high-throughput, hardware-accelerated video composition.

```kotlin
class VideoCompositionEngine(private val context: Context) {

    suspend fun composeVideo(
        rawVideoUri: Uri,
        musicFileUri: Uri,
        durationMs: Long,
        selectedEffect: RetroEffectType,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.Default) {
        val glEffect = when (selectedEffect) {
            RetroEffectType.VHS -> VhsRetroGlEffect()
            RetroEffectType.NEON_80S -> Neon80sGlEffect()
            RetroEffectType.VINTAGE_70S -> Vintage70sGlEffect()
            RetroEffectType.FILM_90S -> Film90sGlEffect()
            RetroEffectType.BW_NOIR -> BwNoirGlEffect()
            RetroEffectType.NONE -> null
        }

        val videoEffects = listOfNotNull(glEffect)

        // Build edited video item with visual effects
        val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(rawVideoUri))
            .setEffects(Effects(emptyList(), videoEffects))
            .setRemoveAudio(true) // Mute webcam mic
            .build()

        // Build music item trimmed to recording duration
        val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(musicFileUri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(0)
                    .setEndPositionMs(durationMs)
                    .build()
            )
            .build()

        val composition = Composition.Builder(
            EditedMediaItemSequence(videoItem),
            EditedMediaItemSequence(audioItem)
        ).build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .build()

        // Execute transformation with coroutine suspension
        executeTransformation(transformer, composition, outputFile)
    }
}
```

---

## 5. Retro Video Effects Specifications (GLSL Shaders)

RetroTok implements 5 authentic retro filters via custom OpenGL ES 2.0 fragment shaders conforming to the `GlShaderProgram` interface:

### 5.1 Effect 1: "VHS Retro"
- **Shader Pipeline:**
  1. *Chromatic Aberration:* Splits color channels horizontally (`vec2 offset = vec2(0.006 * sin(time * 5.0), 0.0)`). Red shifted left, Cyan shifted right.
  2. *Tracking Line Jitter:* Modulates Y-coordinates with high-frequency noise near bottom 10% of frame.
  3. *Analog Scanlines:* Multiplies luminance by `0.9 + 0.1 * sin(uv.y * 720.0 * 3.14159)`.
  4. *CRT Phosphor Bleed:* Slight horizontal color blur on bright pixels.

### 5.2 Effect 2: "80s Neon"
- **Shader Pipeline:**
  1. *Vibrance Boost:* Enhances saturation by 1.45x.
  2. *Dual-Tone Color Grade:* Maps darks to deep midnight indigo (`#1A0033`) and highlights to electric magenta (`#FF2A85`) and cyan (`#00F0FF`).
  3. *Glow Bloom:* High-pass threshold filter blurred and composited additively over the original frame.

### 5.3 Effect 3: "70s Vintage"
- **Shader Pipeline:**
  1. *Warm Sepia Tint:* Matrix transformation boosting red/amber channels and softening blues.
  2. *Golden Hour Vignette:* Radial darkening towards corners: `1.0 - smoothstep(0.4, 0.9, length(uv - 0.5))`.
  3. *Soft Contrast Compression:* Lifts black levels to simulate faded vintage film print.

### 5.4 Effect 4: "90s Camcorder"
- **Shader Pipeline:**
  1. *Film Grain:* Dynamic procedural noise generator based on pseudo-random hash of `uv + fract(time)`.
  2. *Color Quantization:* Subtle posterization to simulate early digital CCD sensors.
  3. *Timestamp Overlay:* Blended monospace timestamp in bottom corner.

### 5.5 Effect 5: "Black & White Noir"
- **Shader Pipeline:**
  1. *Luminance Conversion:* `dot(color.rgb, vec3(0.299, 0.587, 0.114))`.
  2. *S-Curve Contrast:* Sharpens midtone contrast to simulate high-silver black-and-white stock.

---

## 6. Error Handling & Raw Video Preservation Guarantee

> [!CRITICAL]
> **Guest Effort Protection:** If an unexpected hardware encoder crash or shader compilation failure occurs during post-processing, **the guest's raw video recording is never deleted**.

1. **Automatic Fallback Pipeline:**
   - If `Transformer` fails during shader rendering, the system immediately re-attempts transformation using a **Fast Mux Fallback** (combining raw MP4 + audio with zero video transcoding).
2. **Admin Diagnostic Flag:** If even the fallback fails, the video record is marked `FAILED` in SQLite, retaining `rawRecordingPath` on disk for admin inspection, while gracefully prompting the guest: *"We encountered an issue preparing your effect. Would you like to retry or save the original?"*.

---

## 7. Phase 3 Production Implementation Status

- **minSdk:** Changed to API Level 23 (Android 6.0+) per specification.
- **Dual-Path Camera Architecture:** Implemented via `CameraProvider`, `CameraDetector`, `CameraPreviewController`, `CameraRecordingController`.
- **Microphone Isolation:** Audio recording during webcam video capture is intentionally omitted at `prepareRecording()` level. Microphone noise is completely excluded.
- **Soundtrack Synchronization:** `SoundtrackSessionController` triggers local audio playback synchronously at recording start (not countdown). `VideoMuxerProcessor` extracts video track and audio track from local file using `MediaExtractor` + `MediaMuxer`, trimming audio to video duration.
- **Storage Lifecycle:** `VideoStorageManager` enforces application-private separation: `temp/`, `imported/`, and `published/`. Unpublished takes are safely cleaned or deleted upon explicit guest Retake. Published takes are atomically moved to `published/`.
- **Video Preview:** `VideoPreviewScreen` leverages AndroidX Media3 ExoPlayer (`PlayerView`) to loop the actual recorded MP4 with digital soundtrack.

