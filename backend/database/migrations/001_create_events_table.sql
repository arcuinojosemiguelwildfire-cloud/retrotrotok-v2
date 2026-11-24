-- Migration 001: Events table with 300-video capacity tracking
-- Aligns with RetroTok Phase 7 EventModeStatus (NOT_STARTED, ACTIVE, PAUSED, CLOSING, ENDED)

CREATE TABLE IF NOT EXISTS `events` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `external_event_key` VARCHAR(64) NULL,
    `event_name` VARCHAR(160) NOT NULL,
    `event_description` TEXT NULL,
    `status` VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    `max_videos` INT UNSIGNED NOT NULL DEFAULT 300,
    `started_at` DATETIME NULL,
    `ended_at` DATETIME NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_events_external_event_key` (`external_event_key`),
    KEY `idx_events_status` (`status`),
    KEY `idx_events_created_at` (`created_at`),
    CONSTRAINT `chk_events_status` CHECK (`status` IN ('NOT_STARTED', 'ACTIVE', 'PAUSED', 'CLOSING', 'ENDED')),
    CONSTRAINT `chk_events_max_videos` CHECK (`max_videos` >= 1 AND `max_videos` <= 300)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
