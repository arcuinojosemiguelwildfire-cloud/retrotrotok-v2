<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Exception;

final class ValidationException extends HttpException
{
    /** @param array<string, mixed> $details */
    public function __construct(string $message, string $errorCode = 'VALIDATION_ERROR', array $details = [])
    {
        parent::__construct(422, $errorCode, $message, $details);
    }
}
