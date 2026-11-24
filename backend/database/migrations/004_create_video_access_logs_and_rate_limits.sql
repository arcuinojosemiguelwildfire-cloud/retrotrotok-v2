-- Migration 004: QR access audit logs and public rate-limiting ledger
-- Stores zero guest personal info (no names, emails, or raw QR tokens).

CREATE TABLE IF NOT EXISTS `video_access_logs` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `video_id` BIGINT UNSIGNED NOT NULL,
    `accessed_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `access_type` VARCHAR(32) NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_video_access_logs_video_id` (`video_id`),
    KEY `idx_video_access_logs_accessed_at` (`accessed_at`),
    CONSTRAINT `fk_video_access_logs_video_id`
        FOREIGN KEY (`video_id`) REFERENCES `videos` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `chk_video_access_type`
        CHECK (`access_type` IN ('PAGE_VIEW', 'PLAYBACK', 'DOWNLOAD', 'EXPIRED_ATTEMPT', 'REVOKED_ATTEMPT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `rate_limits` (
    `bucket_key` CHAR(64) NOT NULL,
    `request_count` INT UNSIGNED NOT NULL DEFAULT 1,
    `window_expires_at` DATETIME NOT NULL,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`bucket_key`),
    KEY `idx_rate_limits_window_expires_at` (`window_expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
