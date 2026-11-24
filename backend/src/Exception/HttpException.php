<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Exception;

use RuntimeException;

class HttpException extends RuntimeException
{
    public readonly int $statusCode;
    public readonly string $errorCode;
    /** @var array<string, mixed> */
    public readonly array $details;
    /** @var array<string, string> */
    public readonly array $headers;

    /**
     * @param array<string, mixed> $details
     * @param array<string, string> $headers
     */
    public function __construct(
        int $statusCode,
        string $errorCode,
        string $message,
        array $details = [],
        array $headers = []
    ) {
        parent::__construct($message, $statusCode);
        $this->statusCode = $statusCode;
        $this->errorCode = $errorCode;
        $this->details = $details;
        $this->headers = $headers;
    }
}
