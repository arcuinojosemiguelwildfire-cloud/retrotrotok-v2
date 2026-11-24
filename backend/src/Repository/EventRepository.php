<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Repository;

use PDO;

final class EventRepository
{
    public function __construct(private readonly PDO $pdo)
    {
    }

    /**
     * @return array<string, mixed>|null
     */
    public function findById(int $eventId): ?array
    {
        $stmt = $this->pdo->prepare('SELECT * FROM events WHERE id = :id LIMIT 1');
        $stmt->execute([':id' => $eventId]);
        $row = $stmt->fetch();
        return is_array($row) ? $row : null;
    }

    /**
     * @return array<string, mixed>|null
     */
    public function findByExternalKey(string $externalKey): ?array
    {
        $stmt = $this->pdo->prepare('SELECT * FROM events WHERE external_event_key = :key LIMIT 1');
        $stmt->execute([':key' => $externalKey]);
        $row = $stmt->fetch();
        return is_array($row) ? $row : null;
    }

    /**
     * Locks the event row on MySQL/InnoDB during capacity checks.
     *
     * @return array<string, mixed>|null
     */
    public function findByIdForUpdate(int $eventId): ?array
    {
        $driver = (string) $this->pdo->getAttribute(PDO::ATTR_DRIVER_NAME);
        $sql = $driver === 'mysql'
            ? 'SELECT * FROM events WHERE id = :id LIMIT 1 FOR UPDATE'
            : 'SELECT * FROM events WHERE id = :id LIMIT 1';
        $stmt = $this->pdo->prepare($sql);
        $stmt->execute([':id' => $eventId]);
        $row = $stmt->fetch();
        return is_array($row) ? $row : null;
    }

    /**
     * @return array<string, mixed>
     */
    public function create(
        string $eventName,
        ?string $eventDescription,
        string $status,
        int $maxVideos,
        ?string $externalEventKey,
        ?string $startedAt,
        string $nowUtc
    ): array {
        $stmt = $this->pdo->prepare(
            'INSERT INTO events (external_event_key, event_name, event_description, status, max_videos, started_at, created_at, updated_at)
             VALUES (:external_key, :name, :desc, :status, :max_videos, :started_at, :created_at, :updated_at)'
        );
        $stmt->execute([
            ':external_key' => $externalEventKey,
            ':name' => $eventName,
            ':desc' => $eventDescription,
            ':status' => $status,
            ':max_videos' => $maxVideos,
            ':started_at' => $startedAt,
            ':created_at' => $nowUtc,
            ':updated_at' => $nowUtc,
        ]);

        $id = (int) $this->pdo->lastInsertId();
        return $this->findById($id) ?? [];
    }

    /**
     * @return array{active_count: int, available_count: int, pending_count: int, failed_count: int, total_uploaded_bytes: int}
     */
    public function getEventVideoMetrics(int $eventId): array
    {
        $stmt = $this->pdo->prepare(
            "SELECT
                SUM(CASE WHEN status IN ('PENDING_UPLOAD', 'UPLOADING', 'AVAILABLE', 'UPLOAD_FAILED') AND deleted_at IS NULL THEN 1 ELSE 0 END) AS active_count,
                SUM(CASE WHEN status = 'AVAILABLE' AND deleted_at IS NULL THEN 1 ELSE 0 END) AS available_count,
                SUM(CASE WHEN status IN ('PENDING_UPLOAD', 'UPLOADING') AND deleted_at IS NULL THEN 1 ELSE 0 END) AS pending_count,
                SUM(CASE WHEN status = 'UPLOAD_FAILED' AND deleted_at IS NULL THEN 1 ELSE 0 END) AS failed_count,
                COALESCE(SUM(CASE WHEN status = 'AVAILABLE' AND deleted_at IS NULL THEN file_size ELSE 0 END), 0) AS total_uploaded_bytes
             FROM videos
             WHERE event_id = :event_id"
        );
        $stmt->execute([':event_id' => $eventId]);
        $row = $stmt->fetch() ?: [];

        return [
            'active_count' => (int) ($row['active_count'] ?? 0),
            'available_count' => (int) ($row['available_count'] ?? 0),
            'pending_count' => (int) ($row['pending_count'] ?? 0),
            'failed_count' => (int) ($row['failed_count'] ?? 0),
            'total_uploaded_bytes' => (int) ($row['total_uploaded_bytes'] ?? 0),
        ];
    }

    /**
     * @return array<string, mixed>
     */
    public function updateStatus(
        int $eventId,
        string $status,
        ?string $startedAt,
        ?string $endedAt,
        string $nowUtc
    ): array {
        $stmt = $this->pdo->prepare(
            'UPDATE events
             SET status = :status,
                 started_at = :started_at,
                 ended_at = :ended_at,
                 updated_at = :updated_at
             WHERE id = :id'
        );
        $stmt->execute([
            ':status' => $status,
            ':started_at' => $startedAt,
            ':ended_at' => $endedAt,
            ':updated_at' => $nowUtc,
            ':id' => $eventId,
        ]);

        return $this->findById($eventId) ?? [];
    }
}
