# RetroTok Cloud API v1 — Contract & Behavior Specification

Base URL (production): `https://your-domain.example.com`

All `/api/v1/*` routes return JSON with security headers (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store`). Production mode rejects plain HTTP requests (`403 HTTPS_REQUIRED`, except `/api/v1/health` pre-check).

---

## Authentication Rules

1. **Admin Bearer Auth (`Authorization: Bearer <ADMIN_API_TOKEN>`)**
   - Required for: `POST /api/v1/events`, `POST /api/v1/admin/videos/{videoId}/delete`.
   - Verified in constant time (`hash_equals`) against SHA-256 of `ADMIN_API_TOKEN`. Fails closed (`503 ADMIN_AUTH_NOT_CONFIGURED`) if unset or placeholder.
2. **Kiosk Device Auth (`Authorization: Bearer <DEVICE_TOKEN>`)**
   - Obtained via `POST /api/v1/devices/pair` using out-of-band `DEVICE_PAIRING_SECRET`.
   - Backend stores only `SHA-256(device_token)` in `kiosk_devices.token_hash` and checks `revoked_at IS NULL` on every request.
   - Required (or Admin token) for: `GET /api/v1/events/{eventId}`, `POST /api/v1/events/{eventId}/videos`, `POST /api/v1/videos/{videoId}/complete`, `GET /api/v1/events/{eventId}/videos`, `GET /api/v1/videos/{videoId}/status`.
3. **Public QR Bearer Token (`GET /v/{token}`)**
   - Minimum 256-bit CSPRNG URL-safe token (`random_bytes(32)`).
   - Stored only as SHA-256 (with optional server pepper) in `videos.access_token_hash`.
   - Rate-limited by client IP (`RATE_LIMIT_QR_MAX_REQUESTS` per `RATE_LIMIT_QR_WINDOW_SECONDS`).

---

## Standard Error Envelope

```json
{
  "ok": false,
  "error": {
    "code": "EVENT_CAPACITY_REACHED",
    "message": "Event has reached its maximum capacity of 300 videos.",
    "details": {
      "max_videos": 300,
      "active_video_count": 300
    }
  }
}
```

Common HTTP status codes:
- `401 Unauthorized` (`MISSING_BEARER_TOKEN`, `INVALID_ADMIN_TOKEN`, `INVALID_DEVICE_TOKEN`, `INVALID_PAIRING_CODE`)
- `403 Forbidden` (`HTTPS_REQUIRED`)
- `404 Not Found` (`EVENT_NOT_FOUND`, `VIDEO_NOT_FOUND`, `VIDEO_UNAVAILABLE`, `ENDPOINT_NOT_FOUND`)
- `409 Conflict` (`EVENT_NOT_ACTIVE`, `EVENT_CAPACITY_REACHED`, `VIDEO_CLOSED`, `R2_OBJECT_MISSING`, `R2_SIZE_MISMATCH`, `R2_MIME_MISMATCH`)
- `410 Gone` (`VIDEO_EXPIRED`, `VIDEO_REVOKED`)
- `422 Unprocessable Entity` (`VALIDATION_ERROR`, `INVALID_JSON`, `INVALID_FILE_SIZE`, `FILE_TOO_LARGE`, `INVALID_DURATION`, `UNSUPPORTED_MIME_TYPE`, `PATH_TRAVERSAL_REJECTED`)
- `429 Too Many Requests` (`RATE_LIMIT_EXCEEDED`, includes `Retry-After` header)
- `503 Service Unavailable` (`OBJECT_STORAGE_NOT_CONFIGURED`, `DATABASE_NOT_CONFIGURED`, `DATABASE_CONNECTION_FAILED`)

---

## Endpoints

### 1. `POST /api/v1/events`
- **Auth:** Admin Bearer token
- **Request JSON:**
```json
{
  "event_name": "Acme 40th Anniversary Gala",
  "event_description": "80s & 90s Booth",
  "status": "ACTIVE",
  "max_videos": 300,
  "external_event_key": "evt_2026_acme_01"
}
```
- **Validation:**
  - `event_name` required (1..160 chars)
  - `status` optional: `NOT_STARTED | ACTIVE | PAUSED` (default `ACTIVE`)
  - `max_videos` optional: integer `1..300` (default `300`)
  - `external_event_key` optional identifier (`[A-Za-z0-9_-]{1,64}`)
- **Idempotency:** If `external_event_key` already exists, returns `200 OK` with `"idempotent_replay": true` and existing event record. New events return `201 Created`.

---

### 2. `GET /api/v1/events/{eventId}`
- **Auth:** Device or Admin Bearer token
- **Response (`200 OK`):**
```json
{
  "ok": true,
  "data": {
    "id": 1,
    "external_event_key": "evt_2026_acme_01",
    "event_name": "Acme 40th Anniversary Gala",
    "event_description": "80s & 90s Booth",
    "status": "ACTIVE",
    "max_videos": 300,
    "active_video_count": 42,
    "available_video_count": 40,
    "pending_upload_count": 2,
    "failed_upload_count": 0,
    "remaining_capacity": 258,
    "total_uploaded_bytes": 450120000,
    "started_at": "2026-11-18 17:00:00",
    "ended_at": null,
    "created_at": "2026-11-18 17:00:00"
  }
}
```

---

### 2b. `POST /api/v1/events/{eventId}/status`
Transitions an event along the Phase 7 lifecycle:
`NOT_STARTED → ACTIVE → PAUSED → CLOSING → ENDED`

- **Auth:** Admin Bearer token
- **Request JSON:**
```json
{
  "status": "CLOSING"
}
```
- **Allowed Transitions:**
  - `NOT_STARTED` → `ACTIVE | PAUSED | CLOSING | ENDED`
  - `ACTIVE` → `PAUSED | CLOSING | ENDED`
  - `PAUSED` → `ACTIVE | CLOSING | ENDED`
  - `CLOSING` → `ENDED`
  - `ENDED` → `ENDED` (idempotent; reopening backwards returns `409 INVALID_EVENT_TRANSITION`)
- **Behavior During `CLOSING` and `ENDED`:**
  - New `kiosk_video_id` upload initializations are rejected (`409 EVENT_NOT_ACTIVE`).
  - Existing `PENDING_UPLOAD`, `UPLOADING`, and `UPLOAD_FAILED` sessions can still refresh expired presigned PUT URLs via `POST /api/v1/events/{eventId}/videos` and finalize via `POST /api/v1/videos/{videoId}/complete`.

---

### 3. `POST /api/v1/events/{eventId}/videos`
Initiates or refreshes a direct-to-R2 presigned PUT upload session.
- **Auth:** Device or Admin Bearer token
- **Optional Header:** `Idempotency-Key`
- **Request JSON:**
```json
{
  "kiosk_video_id": "vid-9a4e12bc8f01",
  "file_size": 11534336,
  "duration_seconds": 30,
  "mime_type": "video/mp4",
  "width": 720,
  "height": 1280,
  "checksum": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "nickname": "RetroDancer",
  "caption": "Dancing to Take On Me!",
  "soundtrack_title": "Take On Me",
  "soundtrack_artist": "a-ha",
  "effect_type": "VHS_RETRO"
}
```
- **Validation & Preconditions:**
  - New `kiosk_video_id` records require `event.status == "ACTIVE"` (`409 EVENT_NOT_ACTIVE` otherwise).
  - Existing pending/failed `kiosk_video_id` sessions may refresh expired presigned PUT URLs even in `PAUSED`, `CLOSING`, or `ENDED` states.
  - `kiosk_video_id` sanitized against path traversal (`[A-Za-z0-9][A-Za-z0-9_-]{0,63}`).
  - `file_size` between `1024` and `MAX_VIDEO_UPLOAD_BYTES` (default `104857600` = 100 MiB).
  - `duration_seconds` between `1` and `65`.
  - `mime_type` allowlisted (`video/mp4`).
  - Capacity lock (`SELECT ... FOR UPDATE` on event): active non-deleted/non-expired videos `< max_videos` (`<= 300`).
  - Calls `ObjectStorageInterface::createUploadAuthorization()`. Fails closed with `503 OBJECT_STORAGE_NOT_CONFIGURED` if R2 credentials are unset/placeholder, rolling back transaction cleanly.
- **Idempotency:**
  - Replaying same `(eventId, kiosk_video_id)` while `PENDING_UPLOAD`, `UPLOADING`, or `UPLOAD_FAILED` does **not** allocate another capacity slot or duplicate `videos` row; it returns `200 OK` with a fresh short-lived presigned PUT URL and increments `video_uploads.upload_attempt`.
  - Replaying while `AVAILABLE` returns `200 OK` (`"already_available": true`).
  - Replaying while `EXPIRED` or `DELETED` returns `409 VIDEO_CLOSED`.
- **Response (`201 Created` / `200 OK`):**
```json
{
  "ok": true,
  "data": {
    "cloud_video_id": 101,
    "event_id": 1,
    "kiosk_video_id": "vid-9a4e12bc8f01",
    "status": "PENDING_UPLOAD",
    "idempotent_replay": false,
    "upload_attempt": 1,
    "storage_key": "events/1/videos/vid-9a4e12bc8f01.mp4",
    "upload_url": "https://...",
    "upload_headers": {
      "Content-Type": "video/mp4",
      "Content-Length": "11534336"
    },
    "upload_expires_at": "2026-11-18T17:25:00+00:00",
    "expected_file_size": 11534336
  }
}
```

---

### 4. `POST /api/v1/videos/{videoId}/complete`
Verifies the uploaded R2 object via `HeadObject` (existence, byte size, MIME type), mints a 256-bit QR token (storing only its SHA-256 hash), sets `expires_at = uploaded_at + 30 days`, and marks the video `AVAILABLE`.
- **Auth:** Device or Admin Bearer token
- **Idempotency:**
  - First successful verification marks `AVAILABLE` and returns `qr_token` + `viewing_url` (`"already_completed": false`).
  - Subsequent retries return `200 OK` (`"already_completed": true`, `status`, `uploaded_at`, `expires_at`) without minting/overwriting the active QR token.
  - Missing object / size mismatch / MIME mismatch marks `UPLOAD_FAILED` and returns `409 Conflict`.

---

### 5. `GET /api/v1/events/{eventId}/videos?cursor={id}&limit={1..50}`
Returns cursor-paginated `AVAILABLE`, non-deleted, non-expired videos strictly belonging to `{eventId}`, sorted newest first (`id DESC`).
- **Auth:** Device or Admin Bearer token
- **Response (`200 OK`):** Contains `videos[]` (with short-lived signed `playback_url` when storage is configured, or `null` when unconfigured) and `next_cursor`.

---

### 6. `GET /api/v1/videos/{videoId}/status`
Reconciles current video status and latest `video_uploads` attempt record after kiosk restart or network drop.
- **Auth:** Device or Admin Bearer token

---

### 7. `GET /v/{token}`
Public guest QR endpoint. Supports HTML5 mobile viewing page (`text/html`) or JSON (`Accept: application/json` / `?format=json`).
- **Auth:** Bearer possession of 256-bit QR token (no login required).
- **Security:** Rate-limited (`429`), constant-time hash verification, zero R2 secrets or database IDs exposed, `410 VIDEO_REVOKED` after deletion, `410 VIDEO_EXPIRED` after 30-day retention, `503 OBJECT_STORAGE_NOT_CONFIGURED` if R2 is unverified.

---

### 8. `POST /api/v1/admin/videos/{videoId}/delete`
- **Auth:** Admin Bearer token
- **Behavior:** Deletes MP4 and thumbnail objects via `ObjectStorageInterface::deleteObject()`. Only if R2 deletion succeeds (`200`/`204`) or absence is verified (`404`) does MySQL mark `DELETED` and clear `access_token_hash`. If R2 deletion fails (`503 R2_DELETE_FAILED`), the DB record stays intact for retry.
- **Idempotency:** Calling delete on an already `DELETED` video returns `200 OK` with `"already_deleted": true`.

---

### 9. `GET /api/v1/admin/events/{eventId}/reconcile`
Admin-only storage reconciliation check comparing active MySQL records against R2 objects under `events/{eventId}/`.
- **Auth:** Admin Bearer token
- **Response (`200 OK`):** Reports `available_db_count`, `in_progress_protected_count`, `missing_r2_objects[]`, and `orphaned_r2_objects[]` without deleting recoverable or in-progress records.

---

### Supporting Auth & Health Endpoints
- `POST /api/v1/devices/pair` — Rate-limited exchange of `DEVICE_PAIRING_SECRET` + `device_identifier` for a revocable 256-bit `device_token`.
- `POST /api/v1/devices/heartbeat` — Authenticated kiosk device registration check (`last_seen_at`).
- `GET /api/v1/health` — Non-sensitive configuration readiness flags (`database_configured`, `object_storage_configured`, `admin_auth_configured`).
