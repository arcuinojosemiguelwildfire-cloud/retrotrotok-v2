# RetroTok — Project Documentation & Architecture Blueprint

Welcome to the **RetroTok** project repository.

Phase 0 (Requirements Discovery, Technical Architecture, and Hardware Analysis) has been completed.

The complete Phase 0 architectural package and specifications are located in [`/RetroTok/`](RetroTok/):

- **Master Project README:** [`/RetroTok/README.md`](RetroTok/README.md)
- **Technical Specifications:** [`/RetroTok/docs/`](RetroTok/docs/)
  1. [`01-project-overview.md`](RetroTok/docs/01-project-overview.md) — Executive summary, vision, scope, and non-negotiable architectural boundaries.
  2. [`02-functional-requirements.md`](RetroTok/docs/02-functional-requirements.md) — Complete functional requirements matrix (Feed, Studio, Music, Composition, Admin, Sessions).
  3. [`03-non-functional-requirements.md`](RetroTok/docs/03-non-functional-requirements.md) — Performance metrics, 12h uptime stability, touch ergonomics, storage budgeting.
  4. [`04-system-architecture.md`](RetroTok/docs/04-system-architecture.md) — Clean Architecture + MVVM + UDF, component breakdown, concurrency model.
  5. [`05-database-design.md`](RetroTok/docs/05-database-design.md) — Room 2.7.0 ERD, Kotlin entity schemas (`VideoEntity`, `SongEntity`), DataStore keys.
  6. [`06-screen-specifications.md`](RetroTok/docs/06-screen-specifications.md) — Detailed layouts, ASCII wireframes, navigation state machine for all 10 screens.
  7. [`07-ui-ux-design-system.md`](RetroTok/docs/07-ui-ux-design-system.md) — Neo-Retro Synthwave palette, typography, kiosk touch targets, animation curves.
  8. [`08-music-management.md`](RetroTok/docs/08-music-management.md) — SAF file import, ID3 parser, duplicate detection, decade categorization (70s-00s).
  9. [`09-video-recording-pipeline.md`](RetroTok/docs/09-video-recording-pipeline.md) — Dual-path camera capture, A/V sync, Media3 Transformer engine, GLSL retro shaders.
  10. [`10-event-session-management.md`](RetroTok/docs/10-event-session-management.md) — Ephemeral guest sessions, inactivity watchdog auto-reset, guest deletion policy.
  11. [`11-local-storage-architecture.md`](RetroTok/docs/11-local-storage-architecture.md) — Private storage tree, atomic file writes, storage safeguards (2GB alert, 500MB block).
  12. [`12-security-and-privacy.md`](RetroTok/docs/12-security-and-privacy.md) — PBKDF2 PIN hashing, brute-force lockout, Android Device Owner Lock Task Mode.
  13. [`13-hardware-compatibility.md`](RetroTok/docs/13-hardware-compatibility.md) — Logitech webcam matrix (C920, C922, etc.), UVC vs CameraX HAL, USB power brownouts.
  14. [`14-testing-and-acceptance.md`](RetroTok/docs/14-testing-and-acceptance.md) — 8 Critical User Journeys (CUJ-01 to CUJ-08), Robolectric tests, soak test checklist.
  15. [`15-development-roadmap.md`](RetroTok/docs/15-development-roadmap.md) — 10-phase implementation plan (Phases 0–9), risk register, Phase 0 exit criteria.
