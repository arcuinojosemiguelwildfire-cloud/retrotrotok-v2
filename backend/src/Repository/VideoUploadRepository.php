<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Repository;

use PDO;

final class VideoUploadRepository
{
    public function __construct(private readonly PDO $pdo)
    {
    }

    /**
     * @return array<string, mixed>|null
     */
    public function findLatestByVideoId(int $videoId): ?array
    {
        $stmt = $this->pdo->prepare(
            'SELECT * FROM video_uploads WHERE video_id = :video_id ORDER BY upload_attempt DESC LIMIT 1'
        );
        $stmt->execute([':video_id' => $videoId]);
        $row = $stmt->fetch();
        return is_array($row) ? $row : null;
    }

    /**
     * @return array<string, mixed>
     */
    public function createAttempt(
        int $videoId,
        ?string $idempotencyKey,
        int $expectedSize,
        string $presignedExpiresAtUtc,
        string $nowUtc
    ): array {
        $stmt = $this->pdo->prepare('SELECT COALESCE(MAX(upload_attempt), 0) FROM video_uploads WHERE video_id = :video_id');
        $stmt->execute([':video_id' => $videoId]);
        $nextAttempt = ((int) $stmt->fetchColumn()) + 1;

        $insert = $this->pdo->prepare(
            "INSERT INTO video_uploads (
                video_id, idempotency_key, upload_attempt, upload_status,
                expected_size, uploaded_size, presigned_expires_at, started_at, updated_at
            ) VALUES (
                :video_id, :idempotency_key, :attempt, 'INITIATED',
                :expected_size, 0, :presigned_expires_at, :started_at, :updated_at
            )"
        );
        $insert->execute([
            ':video_id' => $videoId,
            ':idempotency_key' => $idempotencyKey,
            ':attempt' => $nextAttempt,
            ':expected_size' => $expectedSize,
            ':presigned_expires_at' => $presignedExpiresAtUtc,
            ':started_at' => $nowUtc,
            ':updated_at' => $nowUtc,
        ]);

        return $this->findLatestByVideoId($videoId) ?? [];
    }

    public function markLatestVerified(int $videoId, int $uploadedSize, string $nowUtc): void
    {
        $latest = $this->findLatestByVideoId($videoId);
        if ($latest === null) {
            return;
        }
        $stmt = $this->pdo->prepare(
            "UPDATE video_uploads
             SET upload_status = 'VERIFIED',
                 uploaded_size = :uploaded_size,
                 last_error_code = NULL,
                 last_error = NULL,
                 completed_at = :completed_at,
                 updated_at = :updated_at
             WHERE id = :id"
        );
        $stmt->execute([
            ':uploaded_size' => $uploadedSize,
            ':completed_at' => $nowUtc,
            ':updated_at' => $nowUtc,
            ':id' => (int) $latest['id'],
        ]);
    }

    public function markLatestFailed(int $videoId, string $errorCode, string $errorMessage, string $nowUtc): void
    {
        $latest = $this->findLatestByVideoId($videoId);
        if ($latest === null) {
            return;
        }
        $stmt = $this->pdo->prepare(
            "UPDATE video_uploads
             SET upload_status = 'FAILED',
                 last_error_code = :error_code,
                 last_error = :error_message,
                 updated_at = :updated_at
             WHERE id = :id"
        );
        $stmt->execute([
            ':error_code' => mb_substr($errorCode, 0, 64, 'UTF-8'),
            ':error_message' => mb_substr($errorMessage, 0, 512, 'UTF-8'),
            ':updated_at' => $nowUtc,
            ':id' => (int) $latest['id'],
        ]);
    }
}
