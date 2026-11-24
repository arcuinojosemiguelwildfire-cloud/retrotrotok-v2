<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Exception;

final class NotFoundException extends HttpException
{
    public function __construct(string $message = 'Resource not found.', string $errorCode = 'NOT_FOUND')
    {
        parent::__construct(404, $errorCode, $message);
    }
}
