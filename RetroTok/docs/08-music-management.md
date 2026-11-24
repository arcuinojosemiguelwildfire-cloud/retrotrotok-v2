# RetroTok — Music Management & Ingestion Architecture

**Document ID:** RT-DOC-08  
**Version:** 1.0.0  
**Supported Audio Formats:** MP3 (MPEG-1/2 Audio Layer III), AAC / M4A (MPEG-4 Audio)  
**File Storage:** Application-Private Storage (`context.filesDir/music/`)  

---

## 1. Music Pipeline Architecture Overview

The music catalog provides the nostalgic core of the RetroTok experience. Because RetroTok operates 100% offline with zero cloud streaming, all tracks are imported, indexed, validated, and stored directly within application-managed storage on the kiosk device.

```
+-----------------------------------------------------------------------------------------+
|                               MUSIC INGESTION PIPELINE                                  |
+-----------------------------------------------------------------------------------------+
                                             |
                                             v
                           [SAF Document Picker: USB / Storage]
                                             |
                                             v
                           [Audio File Validation & Decoding Check]
                           (Duration > 5s, Valid MIME, Sample Rate)
                                             |
                                             v
                           [SHA-256 Audio Bitstream Checksum]
                           /                                \
                 [Duplicate Detected]             [Unique Track Checksum]
                          |                                   |
                          v                                   v
                 [Reject & Notify Admin]          [ID3v2 / Atom Metadata Extraction]
                                                  (Title, Artist, Album, Year, Genre)
                                                              |
                                                              v
                                                  [Embedded Artwork Extraction]
                                                  (Convert APIC bytes -> WebP Thumbnail)
                                                              |
                                                              v
                                                  [Decade Categorization Engine]
                                                  (70s, 80s, 90s, 2000-2001)
                                                              |
                                                              v
                                                  [Copy Audio to App Storage]
                                                  (/files/music/{uuid}.mp3)
                                                              |
                                                              v
                                                  [Insert SongEntity into Room DB]
```

---

## 2. Ingestion & Validation Engine

### 2.1 File Picker & Stream Acquisition
- Ingestion is initiated exclusively by an authenticated administrator in `AdminMusicScreen`.
- The system launches the Android Storage Access Framework contract:
  ```kotlin
  val launcher = rememberLauncherForActivityResult(
      ActivityResultContracts.OpenMultipleDocuments()
  ) { uris ->
      viewModel.importAudioFiles(uris)
  }
  launcher.launch(arrayOf("audio/mpeg", "audio/mp4", "audio/aac", "audio/x-m4a"))
  ```

### 2.2 Audio Validation & Integrity Check
Before writing to permanent storage, the file stream is inspected:
1. **Header Inspection:** Open file descriptor with `MediaExtractor`. Verify audio track presence (`trackFormat.getString(MediaFormat.KEY_MIME).startsWith("audio/")`).
2. **Minimum Duration Rule:** Tracks with duration < 5.0 seconds are flagged as corrupted or invalid ringtones and rejected.
3. **Codec Compatibility:** Ensure sample rate is between 22,050 Hz and 48,000 Hz, with 1 or 2 channels (mono/stereo).

### 2.3 Duplicate Detection via Checksum
To prevent the same song from being imported multiple times under different filenames:
- Compute the SHA-256 hash of the raw audio bytes (excluding editable tag headers when possible, or full file hash).
- Query Room database: `songDao.getSongByHash(hash)`.
- If an entry exists, the import is skipped with an informational log: *"Skipped duplicate: [Title]"*.

---

## 3. Metadata Extraction & Decade Mapping

### 3.1 ID3 & MP4 Atom Parser
The engine uses Android's native `MediaMetadataRetriever` combined with specialized fallback tag readers:

```kotlin
class MusicMetadataExtractor(private val context: Context) {
    fun extract(uri: Uri): ExtractedSongMetadata {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        
        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            ?: getFallbackFileName(context, uri)
        val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            ?: "Unknown Retro Artist"
        val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
        val yearString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
        val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) 
            ?: "Retro Hits"
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() 
            ?: 0L
        val artworkBytes = retriever.embeddedPicture

        val year = parseYear(yearString)
        val decade = categorizeDecade(year)

        return ExtractedSongMetadata(
            title = title.trim(),
            artist = artist.trim(),
            album = album?.trim(),
            releaseYear = year,
            decade = decade,
            genre = genre.trim(),
            durationMs = duration,
            artworkBytes = artworkBytes
        )
    }
}
```

### 3.2 Decade Categorization Rules
The application strictly maps tracks to four target historical eras:

| Era Key | Year Range | Primary Genres & Cultural Focus | Default Badge Color |
| :--- | :--- | :--- | :--- |
| `DECADE_70S` | **1970 – 1979** | Disco Fever, Classic Rock, Funk, Soul, Motown | Amber Gold (`#FFB800`) |
| `DECADE_80S` | **1980 – 1989** | Synthpop, New Wave, Hair Metal, Post-Disco, 80s Pop | Hot Magenta (`#FF2A85`) |
| `DECADE_90S` | **1990 – 1999** | Eurodance, Grunge, Boybands, 90s R&B, Hip Hop | Electric Cyan (`#00F0FF`) |
| `DECADE_00S` | **2000 – 2001** | Y2K Pop, 2000s Dance, Nu-Metal, Millennium Anthems | Gloss Chrome (`#E2E8F0`) |
| `DECADE_UNKNOWN`| Other / Unset | Manually classified by administrator in Admin Console | Muted Slate (`#7E7399`) |

---

## 4. In-Kiosk Audio Preview & Lifecycle Management

1. **Dedicated Preview Controller:** A single, lightweight `ExoPlayer` instance is dedicated to song browsing in `SongPickerSheet`.
2. **Audio Leak Prevention:** 
   - Opening `SongPickerSheet` pauses the video feed and mutes feed audio.
   - Closing or dismissing `SongPickerSheet` immediately stops the preview player and releases audio focus.
   - Entering `DanceStudioScreen` stops the preview player and transfers the selected track URI to the studio recorder.
3. **15-Second Clip Loop:** When previewing a track, the player plays from an offset (default: 15.0s into the track to capture the chorus) for 15 seconds, smoothly looping to avoid dead air.

---

## 5. Music Licensing & Event Legal Framework

> [!CRITICAL]
> **Organizer Legal Notice:** Simply possessing an MP3/AAC audio file does not automatically confer public performance rights or synchronization rights for commercial video recordings.

### Required Compliance Workflow
1. **Private vs Public Event Classification:**
   - *Private Gatherings (Weddings, Birthdays, Family Reunions):* Typically exempt from public performance licensing under private domestic use doctrines in most jurisdictions.
   - *Corporate Events, Galas, & Commercial Venues:* Require valid public performance licenses from relevant Performing Rights Organizations (e.g., ASCAP, BMI, SESAC, PRS, GEMA, SACEM, FILSCAP).
2. **Kiosk In-App Acknowledgment:**
   - During the first launch and inside `AdminMusicScreen`, the administrator must check a mandatory authorization confirmation:
     *"I confirm that our organization holds the appropriate music licenses or venue authorizations for the music tracks imported into RetroTok."*
   - This flag is stored in `EventEntity.isLicensed`.
3. **No External Distribution:** Because recordings are saved strictly on the local kiosk and never published online, synchronization rights liabilities associated with internet streaming platforms do not apply.
