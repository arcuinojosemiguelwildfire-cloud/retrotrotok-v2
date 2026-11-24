<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Tests\Support;

use PDO;

/**
 * Creates an in-memory SQLite PDO schema mirroring MySQL migrations 000–004
 * for fast unit/integration tests without a running MySQL server.
 */
final class TestDbHelper
{
    public static function createInMemoryPdo(): PDO
    {
        $pdo = new PDO('sqlite::memory:', null, null, [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES => false,
        ]);
        $pdo->exec('PRAGMA foreign_keys = ON;');

        $pdo->exec(<<<SQL
CREATE TABLE schema_migrations (
    version TEXT PRIMARY KEY,
    checksum_sha256 TEXT NOT NULL,
    applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    external_event_key TEXT NULL UNIQUE,
    event_name TEXT NOT NULL,
    event_description TEXT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('NOT_STARTED', 'ACTIVE', 'PAUSED', 'CLOSING', 'ENDED')),
    max_videos INTEGER NOT NULL DEFAULT 300 CHECK (max_videos >= 1 AND max_videos <= 300),
    started_at TEXT NULL,
    ended_at TEXT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE kiosk_devices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_identifier TEXT NOT NULL UNIQUE,
    device_name TEXT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    app_version TEXT NULL,
    android_sdk_int INTEGER NULL,
    last_seen_at TEXT NULL,
    revoked_at TEXT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE videos (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE RESTRICT ON UPDATE CASCADE,
    kiosk_device_id INTEGER NULL REFERENCES kiosk_devices(id) ON DELETE SET NULL ON UPDATE CASCADE,
    kiosk_video_id TEXT NOT NULL,
    nickname TEXT NOT NULL DEFAULT 'RetroDancer',
    caption TEXT NOT NULL DEFAULT '',
    soundtrack_title TEXT NOT NULL DEFAULT '',
    soundtrack_artist TEXT NOT NULL DEFAULT '',
    effect_type TEXT NOT NULL DEFAULT 'NONE',
    access_token_hash TEXT NULL UNIQUE,
    token_rotated_at TEXT NULL,
    storage_key TEXT NOT NULL,
    thumbnail_key TEXT NULL,
    file_size INTEGER NOT NULL CHECK (file_size > 0),
    duration_seconds INTEGER NOT NULL CHECK (duration_seconds >= 1 AND duration_seconds <= 65),
    resolution_width INTEGER NULL,
    resolution_height INTEGER NULL,
    mime_type TEXT NOT NULL DEFAULT 'video/mp4',
    checksum TEXT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING_UPLOAD' CHECK (status IN ('PENDING_UPLOAD', 'UPLOADING', 'AVAILABLE', 'UPLOAD_FAILED', 'EXPIRED', 'DELETED')),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    uploaded_at TEXT NULL,
    expires_at TEXT NULL,
    deleted_at TEXT NULL,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (event_id, kiosk_video_id)
);

CREATE TABLE video_uploads (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    video_id INTEGER NOT NULL REFERENCES videos(id) ON DELETE CASCADE ON UPDATE CASCADE,
    idempotency_key TEXT NULL,
    upload_attempt INTEGER NOT NULL DEFAULT 1,
    upload_status TEXT NOT NULL DEFAULT 'INITIATED' CHECK (upload_status IN ('INITIATED', 'UPLOADING', 'VERIFIED', 'FAILED', 'EXPIRED_URL', 'CANCELLED')),
    expected_size INTEGER NOT NULL,
    uploaded_size INTEGER NOT NULL DEFAULT 0,
    presigned_expires_at TEXT NULL,
    last_error_code TEXT NULL,
    last_error TEXT NULL,
    started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TEXT NULL,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (video_id, upload_attempt)
);

CREATE TABLE video_access_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    video_id INTEGER NOT NULL REFERENCES videos(id) ON DELETE CASCADE ON UPDATE CASCADE,
    accessed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    access_type TEXT NOT NULL CHECK (access_type IN ('PAGE_VIEW', 'PLAYBACK', 'DOWNLOAD', 'EXPIRED_ATTEMPT', 'REVOKED_ATTEMPT'))
);

CREATE TABLE rate_limits (
    bucket_key TEXT PRIMARY KEY,
    request_count INTEGER NOT NULL DEFAULT 1,
    window_expires_at TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
SQL);

        return $pdo;
    }
}
