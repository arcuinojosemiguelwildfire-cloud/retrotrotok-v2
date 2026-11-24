-- Migration 002: Kiosk devices (revocable device auth) and Videos metadata table
-- Never stores MP4 binary data; stores object storage keys and SHA-256 QR token hashes only.

CREATE TABLE IF NOT EXISTS `kiosk_devices` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `device_identifier` VARCHAR(128) NOT NULL,
    `device_name` VARCHAR(120) NOT NULL,
    `token_hash` CHAR(64) NOT NULL,
    `app_version` VARCHAR(32) NULL,
    `android_sdk_int` INT UNSIGNED NULL,
    `last_seen_at` DATETIME NULL,
    `revoked_at` DATETIME NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_kiosk_devices_identifier` (`device_identifier`),
    UNIQUE KEY `uq_kiosk_devices_token_hash` (`token_hash`),
    KEY `idx_kiosk_devices_revoked_at` (`revoked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `videos` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `event_id` BIGINT UNSIGNED NOT NULL,
    `kiosk_device_id` BIGINT UNSIGNED NULL,
    `kiosk_video_id` VARCHAR(64) NOT NULL,
    `nickname` VARCHAR(80) NOT NULL DEFAULT 'RetroDancer',
    `caption` VARCHAR(280) NOT NULL DEFAULT '',
    `soundtrack_title` VARCHAR(160) NOT NULL DEFAULT '',
    `soundtrack_artist` VARCHAR(160) NOT NULL DEFAULT '',
    `effect_type` VARCHAR(48) NOT NULL DEFAULT 'NONE',
    `access_token_hash` CHAR(64) NULL,
    `token_rotated_at` DATETIME NULL,
    `storage_key` VARCHAR(255) NOT NULL,
    `thumbnail_key` VARCHAR(255) NULL,
    `file_size` BIGINT UNSIGNED NOT NULL,
    `duration_seconds` INT UNSIGNED NOT NULL,
    `resolution_width` INT UNSIGNED NULL,
    `resolution_height` INT UNSIGNED NULL,
    `mime_type` VARCHAR(64) NOT NULL DEFAULT 'video/mp4',
    `checksum` CHAR(64) NULL,
    `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING_UPLOAD',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `uploaded_at` DATETIME NULL,
    `expires_at` DATETIME NULL,
    `deleted_at` DATETIME NULL,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_videos_event_kiosk_video` (`event_id`, `kiosk_video_id`),
    UNIQUE KEY `uq_videos_access_token_hash` (`access_token_hash`),
    KEY `idx_videos_event_id` (`event_id`),
    KEY `idx_videos_kiosk_device_id` (`kiosk_device_id`),
    KEY `idx_videos_status` (`status`),
    KEY `idx_videos_uploaded_at` (`uploaded_at`),
    KEY `idx_videos_expires_at` (`expires_at`),
    KEY `idx_videos_deleted_at` (`deleted_at`),
    KEY `idx_videos_kiosk_video_id` (`kiosk_video_id`),
    KEY `idx_videos_event_status_created` (`event_id`, `status`, `created_at`),
    CONSTRAINT `fk_videos_event_id`
        FOREIGN KEY (`event_id`) REFERENCES `events` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_videos_kiosk_device_id`
        FOREIGN KEY (`kiosk_device_id`) REFERENCES `kiosk_devices` (`id`)
        ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT `chk_videos_status`
        CHECK (`status` IN ('PENDING_UPLOAD', 'UPLOADING', 'AVAILABLE', 'UPLOAD_FAILED', 'EXPIRED', 'DELETED')),
    CONSTRAINT `chk_videos_file_size`
        CHECK (`file_size` > 0),
    CONSTRAINT `chk_videos_duration`
        CHECK (`duration_seconds` >= 1 AND `duration_seconds` <= 65)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
