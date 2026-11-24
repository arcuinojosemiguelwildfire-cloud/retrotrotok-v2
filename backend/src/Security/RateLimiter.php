<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Security;

use RetroTok\Cloud\Exception\RateLimitedException;
use RetroTok\Cloud\Repository\RateLimitRepository;
use RetroTok\Cloud\Support\ClockInterface;

/**
 * Enforces request rate limits for public QR token endpoints and device pairing.
 */
final class RateLimiter
{
    public function __construct(
        private readonly RateLimitRepository $repository,
        private readonly ClockInterface $clock
    ) {
    }

    public function assertWithinLimit(string $scope, string $subject, int $maxRequests, int $windowSeconds): void
    {
        $bucketKey = hash('sha256', $scope . ':' . $subject);
        $now = $this->clock->now();
        $result = $this->repository->incrementAndCheck($bucketKey, $now, $windowSeconds);

        if ($result['count'] > $maxRequests) {
            throw new RateLimitedException($result['retry_after_seconds']);
        }
    }
}
