<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Exception;

final class ConflictException extends HttpException
{
    /** @param array<string, mixed> $details */
    public function __construct(string $message, string $errorCode = 'CONFLICT', array $details = [])
    {
        parent::__construct(409, $errorCode, $message, $details);
    }
}
