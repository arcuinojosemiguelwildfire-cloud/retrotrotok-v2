<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Exception;

final class RateLimitedException extends HttpException
{
    public function __construct(int $retryAfterSeconds)
    {
        parent::__construct(
            429,
            'RATE_LIMIT_EXCEEDED',
            'Too many requests. Please wait before retrying.',
            ['retry_after_seconds' => $retryAfterSeconds],
            ['Retry-After' => (string) max(1, $retryAfterSeconds)]
        );
    }
}
