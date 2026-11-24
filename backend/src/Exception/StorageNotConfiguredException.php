<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Exception;

final class StorageNotConfiguredException extends HttpException
{
    public function __construct(
        string $message = 'Cloudflare R2 object storage credentials and bucket are not verified or configured yet.'
    ) {
        parent::__construct(503, 'OBJECT_STORAGE_NOT_CONFIGURED', $message);
    }
}
