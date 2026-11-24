<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Exception;

final class ForbiddenException extends HttpException
{
    public function __construct(string $message = 'Access forbidden.', string $errorCode = 'FORBIDDEN')
    {
        parent::__construct(403, $errorCode, $message);
    }
}
