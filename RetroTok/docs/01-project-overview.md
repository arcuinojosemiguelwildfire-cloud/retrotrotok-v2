# RetroTok — Project Overview & Architecture Blueprint

**Document ID:** RT-DOC-01  
**Version:** 1.0.0 (Phase 0 Architecture & Discovery)  
**Status:** Approved Specification  
**Target Platform:** Android 16 (API Level 36) Vertical Kiosk  
**Primary Language & Framework:** Kotlin, Jetpack Compose  
**Backend Mode:** 100% Local-First / Zero Cloud Dependency  

---

## 1. Executive Summary

**RetroTok** is a specialized, TikTok-inspired nostalgic music and short-form video kiosk application built specifically for a vertical Android 16 touchscreen kiosk fitted with an external Logitech USB webcam. 

Designed for physical live events—such as company anniversaries, retro-themed parties, high school/college reunions, corporate milestone galas, and milestone birthdays—RetroTok delivers the familiar, high-engagement vertical video browsing experience of modern social media platforms while transporting event guests across musical eras spanning the **1970s, 1980s, 1990s, and 2000–2001**.

Guests step up to the kiosk, browse nostalgic music tracks, record themselves dancing or performing in front of the kiosk webcam with synchronized music playback and retro video filters, preview their recording, and publish it directly to the local **Event Feed** with a single tap. Other attendees can subsequently watch, like, and favorite these recordings in a continuous, full-screen vertical video stream.

---

## 2. Core Value Proposition & Concept

Modern social platforms have perfected short-form vertical video engagement, but rely entirely on cloud infrastructure, user accounts, algorithmic feeds, and mobile handsets. RetroTok translates this interaction paradigm into an on-premise, turnkey entertainment station:

1. **Instant, Zero-Friction Participation:** No guest registration, no account creation, no password entry, and no personal data collection. Guests interact immediately with intuitive touch gestures.
2. **Nostalgia-Driven Engagement:** Curated decade categories (**70s Disco/Rock, 80s Synth/Pop, 90s Eurodance/Grunge/Boybands, 2000–2001 Millennium Pop**) evoke shared generational memories across attendees born between 1960 and 2001.
3. **Shared Event Community:** Videos recorded at the party become part of the party's shared digital artifact, looping on the kiosk for attendees to enjoy throughout the night.
4. **Complete Privacy & Isolation:** Operating 100% locally with zero internet access, guests can express themselves freely without fear of their videos leaking to external social networks or cloud servers.

---

## 3. Non-Negotiable Architectural Decisions

The following architectural decisions form the foundation of RetroTok and must not be altered without explicit technical justification and stakeholder review:

| Architectural Area | Decision | Strategic Justification |
| :--- | :--- | :--- |
| **Connectivity** | **100% Offline / Local-First** | Event venues often feature congested or nonexistent Wi-Fi. The kiosk must function flawlessly without any external network dependency. |
| **Backend & Cloud** | **Zero Cloud Infrastructure** | No Laravel, Firebase, AWS, Supabase, or external database. Eliminates hosting fees, latency, connectivity failures, and privacy liabilities. |
| **Hardware Form Factor** | **Vertical Touchscreen Kiosk (720 × 1280)** | Optimized for full-screen portrait 9:16 vertical video interaction, matching modern smartphone video ergonomics at kiosk scale. |
| **Camera Hardware** | **External Logitech USB Webcam** | High-quality wide-angle video capture mounted at kiosk eye-level, interfacing via USB Host mode / UVC. |
| **User Identity** | **Ephemeral Guest Sessions** | No guest login or accounts. Optional guest nickname per recording. Complete session isolation and reset after each video publishing cycle. |
| **Administration** | **Single PIN-Protected Admin Account** | Centralized organizer administration for event configuration, music management, video moderation, and kiosk settings. |
| **Publishing Action** | **"ADD TO EVENT FEED"** | Never describe actions as "uploading". All storage and presentation occur strictly within local device memory. |
| **Video Persistence** | **Permanent Local App Storage** | Published recordings persist across guest sessions, app restarts, and kiosk reboots until explicitly deleted by an administrator. |
| **Guest Deletion** | **Configurable Admin Setting (Default: OFF)** | Prevents malicious or accidental deletion of other attendees' videos while providing an admin override for event moderation. |

---

## 4. High-Level User Journey

```
+---------------------------------------------------------------------------------+
|                               RETROTOK KIOSK FLOW                                |
+---------------------------------------------------------------------------------+
                                         |
                                         v
                         +-------------------------------+
                         |      FOR YOU EVENT FEED       |
                         |   (Full-screen 9:16 Video)    |
                         |  Swipe Up / Down, Like, Fav   |
                         +---------------+---------------+
                                         |
                                         | [Tap "DANCE STUDIO" FAB]
                                         v
                         +-------------------------------+
                         |      MUSIC SELECTION          |
                         |  70s / 80s / 90s / 00-01      |
                         |  Preview & Select Soundtrack  |
                         +---------------+---------------+
                                         |
                                         | [Select Song & Effect]
                                         v
                         +-------------------------------+
                         |         DANCE STUDIO          |
                         |   Live Webcam Viewfinder      |
                         |   3-Second Visual Countdown   |
                         |   Record (15s / 30s / 60s)    |
                         +---------------+---------------+
                                         |
                                         | [Record Complete]
                                         v
                         +-------------------------------+
                         |     PREVIEW & COMPOSITION     |
                         |  Media3 Transformer Pipeline  |
                         |  Music Mux + Retro GlEffect   |
                         |  Retake vs Add to Event Feed  |
                         +---------------+---------------+
                                         |
                                         | [Tap "ADD TO EVENT FEED"]
                                         v
                         +-------------------------------+
                         |     CONFIRMATION & RESET      |
                         |  Save to Published Directory  |
                         |  Update Room Database         |
                         |  Reset Ephemeral Session      |
                         |  Return to Feed (New Video)   |
                         +-------------------------------+
```

---

## 5. Scope Boundaries

### In Scope (MVP)
- Full-screen vertical 9:16 video feed with vertical swipe gestures (`VerticalPager`).
- Video playback synchronized with local music audio track.
- On-device like and favorite counters persisted in local database.
- Curated local music library supporting MP3 and AAC/M4A imports with ID3 metadata.
- Decade filtering (1970s, 1980s, 1990s, 2000–2001) and genre indexing.
- Webcam recording integration supporting 720p 9:16 capture.
- Configurable recording duration (15s, 30s default, 60s).
- AndroidX Media3 Transformer audio-video muxing (muting webcam mic, binding selected song track).
- Selectable retro video effects (VHS, 80s Neon, 70s Vintage, 90s Film, B&W Noir).
- Ephemeral guest session management with automatic timeout reset.
- PIN-protected administrator console with 8 configuration modules.
- Storage health monitoring with low-space safeguards.
- Kiosk mode enforcement (Android Lock Task Mode).

### Out of Scope (Explicitly Excluded)
- Cloud databases, remote sync, or online video streaming.
- User registration, phone/email login, or social authentication.
- Comments, direct messaging, friend lists, or user profile pages.
- Social network sharing (Instagram, TikTok, YouTube exports).
- External USB mass-storage bulk export (reserved for post-MVP Phase 10).
- Real-time generative AI filters or cloud-based LLM integrations.
- Monetization, advertising banners, or in-app purchases.
