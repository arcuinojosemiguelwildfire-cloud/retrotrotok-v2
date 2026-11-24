<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Repository;

use PDO;

final class VideoRepository
{
    public function __construct(private readonly PDO $pdo)
    {
    }

    /**
     * @return array<string, mixed>|null
     */
    public function findById(int $videoId): ?array
    {
        $stmt = $this->pdo->prepare('SELECT * FROM videos WHERE id = :id LIMIT 1');
        $stmt->execute([':id' => $videoId]);
        $row = $stmt->fetch();
        return is_array($row) ? $row : null;
    }

    /**
     * @return array<string, mixed>|null
     */
    public function findByEventAndKioskVideoId(int $eventId, string $kioskVideoId): ?array
    {
        $stmt = $this->pdo->prepare(
            'SELECT * FROM videos WHERE event_id = :event_id AND kiosk_video_id = :kiosk_video_id LIMIT 1'
        );
        $stmt->execute([
            ':event_id' => $eventId,
            ':kiosk_video_id' => $kioskVideoId,
        ]);
        $row = $stmt->fetch();
        return is_array($row) ? $row : null;
    }

    /**
     * @return array<string, mixed>|null
     */
    public function findByTokenHash(string $tokenHash): ?array
    {
        $stmt = $this->pdo->prepare(
            'SELECT v.*, e.event_name
             FROM videos v
             INNER JOIN events e ON e.id = v.event_id
             WHERE v.access_token_hash = :token_hash
             LIMIT 1'
        );
        $stmt->execute([':token_hash' => $tokenHash]);
        $row = $stmt->fetch();
        return is_array($row) ? $row : null;
    }

    public function countCapacityConsumingVideos(int $eventId): int
    {
        $stmt = $this->pdo->prepare(
            "SELECT COUNT(*) FROM videos
             WHERE event_id = :event_id
               AND deleted_at IS NULL
               AND status IN ('PENDING_UPLOAD', 'UPLOADING', 'AVAILABLE', 'UPLOAD_FAILED')"
        );
        $stmt->execute([':event_id' => $eventId]);
        return (int) $stmt->fetchColumn();
    }

    /**
     * @param array<string, mixed> $data
     * @return array<string, mixed>
     */
    public function createPendingVideo(array $data): array
    {
        $stmt = $this->pdo->prepare(
            'INSERT INTO videos (
                event_id, kiosk_device_id, kiosk_video_id, nickname, caption,
                soundtrack_title, soundtrack_artist, effect_type,
                storage_key, thumbnail_key, file_size, duration_seconds,
                resolution_width, resolution_height, mime_type, checksum,
                status, created_at, updated_at
            ) VALUES (
                :event_id, :kiosk_device_id, :kiosk_video_id, :nickname, :caption,
                :soundtrack_title, :soundtrack_artist, :effect_type,
                :storage_key, :thumbnail_key, :file_size, :duration_seconds,
                :resolution_width, :resolution_height, :mime_type, :checksum,
                :status, :created_at, :updated_at
            )'
        );
        $stmt->execute([
            ':event_id' => $data['event_id'],
            ':kiosk_device_id' => $data['kiosk_device_id'],
            ':kiosk_video_id' => $data['kiosk_video_id'],
            ':nickname' => $data['nickname'],
            ':caption' => $data['caption'],
            ':soundtrack_title' => $data['soundtrack_title'],
            ':soundtrack_artist' => $data['soundtrack_artist'],
            ':effect_type' => $data['effect_type'],
            ':storage_key' => $data['storage_key'],
            ':thumbnail_key' => $data['thumbnail_key'],
            ':file_size' => $data['file_size'],
            ':duration_seconds' => $data['duration_seconds'],
            ':resolution_width' => $data['resolution_width'],
            ':resolution_height' => $data['resolution_height'],
            ':mime_type' => $data['mime_type'],
            ':checksum' => $data['checksum'],
            ':status' => 'PENDING_UPLOAD',
            ':created_at' => $data['now_utc'],
            ':updated_at' => $data['now_utc'],
        ]);

        $id = (int) $this->pdo->lastInsertId();
        return $this->findById($id) ?? [];
    }

    public function markAvailable(
        int $videoId,
        string $accessTokenHash,
        string $uploadedAtUtc,
        string $expiresAtUtc
    ): void {
        $stmt = $this->pdo->prepare(
            "UPDATE videos
             SET status = 'AVAILABLE',
                 access_token_hash = :token_hash,
                 token_rotated_at = :token_rotated_at,
                 uploaded_at = :uploaded_at,
                 expires_at = :expires_at,
                 updated_at = :updated_at
             WHERE id = :id"
        );
        $stmt->execute([
            ':token_hash' => $accessTokenHash,
            ':token_rotated_at' => $uploadedAtUtc,
            ':uploaded_at' => $uploadedAtUtc,
            ':expires_at' => $expiresAtUtc,
            ':updated_at' => $uploadedAtUtc,
            ':id' => $videoId,
        ]);
    }

    public function markUploadFailed(int $videoId, string $nowUtc): void
    {
        $stmt = $this->pdo->prepare(
            "UPDATE videos SET status = 'UPLOAD_FAILED', updated_at = :now_utc
             WHERE id = :id AND status IN ('PENDING_UPLOAD', 'UPLOADING', 'UPLOAD_FAILED')"
        );
        $stmt->execute([
            ':now_utc' => $nowUtc,
            ':id' => $videoId,
        ]);
    }

    public function markDeleted(int $videoId, string $nowUtc): void
    {
        $stmt = $this->pdo->prepare(
            "UPDATE videos
             SET status = 'DELETED',
                 access_token_hash = NULL,
                 deleted_at = :deleted_at,
                 updated_at = :updated_at
             WHERE id = :id"
        );
        $stmt->execute([
            ':deleted_at' => $nowUtc,
            ':updated_at' => $nowUtc,
            ':id' => $videoId,
        ]);
    }

    public function markExpired(int $videoId, string $nowUtc): void
    {
        $stmt = $this->pdo->prepare(
            "UPDATE videos
             SET status = 'EXPIRED',
                 access_token_hash = NULL,
                 deleted_at = COALESCE(deleted_at, :deleted_at),
                 updated_at = :updated_at
             WHERE id = :id"
        );
        $stmt->execute([
            ':deleted_at' => $nowUtc,
            ':updated_at' => $nowUtc,
            ':id' => $videoId,
        ]);
    }

    /**
     * Cursor-paginated list of AVAILABLE non-expired videos strictly scoped to $eventId.
     *
     * @return list<array<string, mixed>>
     */
    public function listAvailableByEventCursor(int $eventId, ?int $cursorId, int $limit, string $nowUtc): array
    {
        $limit = max(1, min(50, $limit));
        if ($cursorId !== null && $cursorId > 0) {
            $stmt = $this->pdo->prepare(
                "SELECT * FROM videos
                 WHERE event_id = :event_id
                   AND status = 'AVAILABLE'
                   AND deleted_at IS NULL
                   AND (expires_at IS NULL OR expires_at > :now_utc)
                   AND id < :cursor_id
                 ORDER BY id DESC
                 LIMIT {$limit}"
            );
            $stmt->execute([
                ':event_id' => $eventId,
                ':now_utc' => $nowUtc,
                ':cursor_id' => $cursorId,
            ]);
        } else {
            $stmt = $this->pdo->prepare(
                "SELECT * FROM videos
                 WHERE event_id = :event_id
                   AND status = 'AVAILABLE'
                   AND deleted_at IS NULL
                   AND (expires_at IS NULL OR expires_at > :now_utc)
                 ORDER BY id DESC
                 LIMIT {$limit}"
            );
            $stmt->execute([
                ':event_id' => $eventId,
                ':now_utc' => $nowUtc,
            ]);
        }

        return $stmt->fetchAll() ?: [];
    }

    /**
     * @return list<array<string, mixed>>
     */
    public function findExpiredCandidates(string $nowUtc, int $limit = 100): array
    {
        $limit = max(1, min(500, $limit));
        $stmt = $this->pdo->prepare(
            "SELECT * FROM videos
             WHERE status = 'AVAILABLE'
               AND deleted_at IS NULL
               AND expires_at IS NOT NULL
               AND expires_at <= :now_utc
             ORDER BY expires_at ASC
             LIMIT {$limit}"
        );
        $stmt->execute([':now_utc' => $nowUtc]);
        return $stmt->fetchAll() ?: [];
    }

    /**
     * Returns all non-deleted videos for an event for retention and R2 reconciliation checks.
     *
     * @return list<array<string, mixed>>
     */
    public function findAllByEvent(int $eventId): array
    {
        $stmt = $this->pdo->prepare('SELECT * FROM videos WHERE event_id = :event_id ORDER BY id ASC');
        $stmt->execute([':event_id' => $eventId]);
        return $stmt->fetchAll() ?: [];
    }
}
