<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Storage;

final class ObjectMetadata
{
    public function __construct(
        public readonly string $objectKey,
        public readonly int $contentLength,
        public readonly string $contentType,
        public readonly ?string $etag = null,
        public readonly ?string $lastModified = null
    ) {
    }
}
