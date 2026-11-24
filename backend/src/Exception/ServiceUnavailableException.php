<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Exception;

final class ServiceUnavailableException extends HttpException
{
    public function __construct(string $message = 'Service temporarily unavailable.', string $errorCode = 'SERVICE_UNAVAILABLE')
    {
        parent::__construct(503, $errorCode, $message);
    }
}
