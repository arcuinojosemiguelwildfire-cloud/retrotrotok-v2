<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Repository;

use DateTimeImmutable;
use PDO;

final class RateLimitRepository
{
    public function __construct(private readonly PDO $pdo)
    {
    }

    /**
     * @return array{count: int, retry_after_seconds: int}
     */
    public function incrementAndCheck(string $bucketKey, DateTimeImmutable $now, int $windowSeconds): array
    {
        $nowStr = $now->format('Y-m-d H:i:s');
        $expiresAt = $now->modify("+{$windowSeconds} seconds");
        $expiresStr = $expiresAt->format('Y-m-d H:i:s');
        $driver = (string) $this->pdo->getAttribute(PDO::ATTR_DRIVER_NAME);

        if ($driver === 'mysql') {
            $upsert = $this->pdo->prepare(
                'INSERT INTO rate_limits (bucket_key, request_count, window_expires_at, updated_at)
                 VALUES (:key, 1, :expires_at, :now_utc)
                 ON DUPLICATE KEY UPDATE
                    request_count = IF(window_expires_at <= :now_check, 1, request_count + 1),
                    window_expires_at = IF(window_expires_at <= :now_check_exp, :expires_at_upd, window_expires_at),
                    updated_at = :now_upd'
            );
            $upsert->execute([
                ':key' => $bucketKey,
                ':expires_at' => $expiresStr,
                ':now_utc' => $nowStr,
                ':now_check' => $nowStr,
                ':now_check_exp' => $nowStr,
                ':expires_at_upd' => $expiresStr,
                ':now_upd' => $nowStr,
            ]);
        } else {
            $upsert = $this->pdo->prepare(
                'INSERT INTO rate_limits (bucket_key, request_count, window_expires_at, updated_at)
                 VALUES (:key, 1, :expires_at, :now_utc)
                 ON CONFLICT(bucket_key) DO UPDATE SET
                    request_count = CASE WHEN rate_limits.window_expires_at <= :now_check THEN 1 ELSE rate_limits.request_count + 1 END,
                    window_expires_at = CASE WHEN rate_limits.window_expires_at <= :now_check_exp THEN :expires_at_upd ELSE rate_limits.window_expires_at END,
                    updated_at = :now_upd'
            );
            $upsert->execute([
                ':key' => $bucketKey,
                ':expires_at' => $expiresStr,
                ':now_utc' => $nowStr,
                ':now_check' => $nowStr,
                ':now_check_exp' => $nowStr,
                ':expires_at_upd' => $expiresStr,
                ':now_upd' => $nowStr,
            ]);
        }

        $stmt = $this->pdo->prepare('SELECT request_count, window_expires_at FROM rate_limits WHERE bucket_key = :key LIMIT 1');
        $stmt->execute([':key' => $bucketKey]);
        $row = $stmt->fetch() ?: ['request_count' => 1, 'window_expires_at' => $expiresStr];

        $windowEnd = DateTimeImmutable::createFromFormat('Y-m-d H:i:s', (string) $row['window_expires_at'], $now->getTimezone())
            ?: $expiresAt;
        $retryAfter = max(1, $windowEnd->getTimestamp() - $now->getTimestamp());

        return ['count' => (int) $row['request_count'], 'retry_after_seconds' => $retryAfter];
    }
}
