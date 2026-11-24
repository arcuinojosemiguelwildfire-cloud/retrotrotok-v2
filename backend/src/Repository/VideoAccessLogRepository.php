<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Repository;

use PDO;

final class VideoAccessLogRepository
{
    public function __construct(private readonly PDO $pdo)
    {
    }

    public function logAccess(int $videoId, string $accessType, string $nowUtc): void
    {
        $stmt = $this->pdo->prepare(
            'INSERT INTO video_access_logs (video_id, accessed_at, access_type) VALUES (:video_id, :accessed_at, :access_type)'
        );
        $stmt->execute([
            ':video_id' => $videoId,
            ':accessed_at' => $nowUtc,
            ':access_type' => $accessType,
        ]);
    }
}
