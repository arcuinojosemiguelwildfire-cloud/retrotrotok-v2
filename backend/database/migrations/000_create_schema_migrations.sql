-- Migration 000: Schema migration ledger table
-- Target: MySQL 8.0+ / MariaDB 10.6+ (InnoDB, utf8mb4)

CREATE TABLE IF NOT EXISTS `schema_migrations` (
    `version` VARCHAR(64) NOT NULL,
    `checksum_sha256` CHAR(64) NOT NULL,
    `applied_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
