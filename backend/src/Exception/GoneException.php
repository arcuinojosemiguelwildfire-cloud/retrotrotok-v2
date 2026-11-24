<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Exception;

final class GoneException extends HttpException
{
    public function __construct(string $message = 'Resource has expired.', string $errorCode = 'VIDEO_EXPIRED')
    {
        parent::__construct(410, $errorCode, $message);
    }
}
