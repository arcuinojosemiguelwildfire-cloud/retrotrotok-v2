<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Exception;

final class UnauthorizedException extends HttpException
{
    public function __construct(string $message = 'Authentication required.', string $errorCode = 'UNAUTHORIZED')
    {
        parent::__construct(401, $errorCode, $message);
    }
}
