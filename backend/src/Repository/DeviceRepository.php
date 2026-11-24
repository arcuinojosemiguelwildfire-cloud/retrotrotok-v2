<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Repository;

use PDO;

final class DeviceRepository
{
    public function __construct(private readonly PDO $pdo)
    {
    }

    /**
     * @return array<string, mixed>|null
     */
    public function findActiveByTokenHash(string $tokenHash): ?array
    {
        $stmt = $this->pdo->prepare(
            'SELECT * FROM kiosk_devices WHERE token_hash = :token_hash AND revoked_at IS NULL LIMIT 1'
        );
        $stmt->execute([':token_hash' => $tokenHash]);
        $row = $stmt->fetch();
        return is_array($row) ? $row : null;
    }

    /**
     * @return array<string, mixed>|null
     */
    public function findByIdentifier(string $deviceIdentifier): ?array
    {
        $stmt = $this->pdo->prepare('SELECT * FROM kiosk_devices WHERE device_identifier = :id LIMIT 1');
        $stmt->execute([':id' => $deviceIdentifier]);
        $row = $stmt->fetch();
        return is_array($row) ? $row : null;
    }

    /**
     * @return array<string, mixed>
     */
    public function upsertPairedDevice(
        string $deviceIdentifier,
        string $deviceName,
        string $tokenHash,
        ?string $appVersion,
        ?int $androidSdkInt,
        string $nowUtc
    ): array {
        $existing = $this->findByIdentifier($deviceIdentifier);
        if ($existing !== null) {
            $stmt = $this->pdo->prepare(
                'UPDATE kiosk_devices
                 SET device_name = :name,
                     token_hash = :token_hash,
                     app_version = :app_version,
                     android_sdk_int = :sdk_int,
                     last_seen_at = :last_seen_at,
                     revoked_at = NULL,
                     updated_at = :updated_at
                 WHERE id = :id'
            );
            $stmt->execute([
                ':name' => $deviceName,
                ':token_hash' => $tokenHash,
                ':app_version' => $appVersion,
                ':sdk_int' => $androidSdkInt,
                ':last_seen_at' => $nowUtc,
                ':updated_at' => $nowUtc,
                ':id' => (int) $existing['id'],
            ]);
            return $this->findByIdentifier($deviceIdentifier) ?? [];
        }

        $stmt = $this->pdo->prepare(
            'INSERT INTO kiosk_devices
             (device_identifier, device_name, token_hash, app_version, android_sdk_int, last_seen_at, created_at, updated_at)
             VALUES (:identifier, :name, :token_hash, :app_version, :sdk_int, :last_seen_at, :created_at, :updated_at)'
        );
        $stmt->execute([
            ':identifier' => $deviceIdentifier,
            ':name' => $deviceName,
            ':token_hash' => $tokenHash,
            ':app_version' => $appVersion,
            ':sdk_int' => $androidSdkInt,
            ':last_seen_at' => $nowUtc,
            ':created_at' => $nowUtc,
            ':updated_at' => $nowUtc,
        ]);

        return $this->findByIdentifier($deviceIdentifier) ?? [];
    }

    public function touchHeartbeat(int $deviceId, ?string $appVersion, string $nowUtc): void
    {
        $stmt = $this->pdo->prepare(
            'UPDATE kiosk_devices
             SET last_seen_at = :last_seen_at,
                 app_version = COALESCE(:app_version, app_version),
                 updated_at = :updated_at
             WHERE id = :id'
        );
        $stmt->execute([
            ':last_seen_at' => $nowUtc,
            ':app_version' => $appVersion,
            ':updated_at' => $nowUtc,
            ':id' => $deviceId,
        ]);
    }
}
