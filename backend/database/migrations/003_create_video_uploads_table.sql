-- Migration 003: Video upload attempts & recovery session tracking

CREATE TABLE IF NOT EXISTS `video_uploads` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `video_id` BIGINT UNSIGNED NOT NULL,
    `idempotency_key` VARCHAR(128) NULL,
    `upload_attempt` INT UNSIGNED NOT NULL DEFAULT 1,
    `upload_status` VARCHAR(32) NOT NULL DEFAULT 'INITIATED',
    `expected_size` BIGINT UNSIGNED NOT NULL,
    `uploaded_size` BIGINT UNSIGNED NOT NULL DEFAULT 0,
    `presigned_expires_at` DATETIME NULL,
    `last_error_code` VARCHAR(64) NULL,
    `last_error` VARCHAR(512) NULL,
    `started_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `completed_at` DATETIME NULL,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_video_uploads_video_attempt` (`video_id`, `upload_attempt`),
    KEY `idx_video_uploads_video_id` (`video_id`),
    KEY `idx_video_uploads_status` (`upload_status`),
    KEY `idx_video_uploads_idempotency` (`idempotency_key`),
    CONSTRAINT `fk_video_uploads_video_id`
        FOREIGN KEY (`video_id`) REFERENCES `videos` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `chk_video_uploads_status`
        CHECK (`upload_status` IN ('INITIATED', 'UPLOADING', 'VERIFIED', 'FAILED', 'EXPIRED_URL', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
