<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Storage;

final class UploadAuthorization
{
    /**
     * @param array<string, string> $requiredHeaders
     */
    public function __construct(
        public readonly string $objectKey,
        public readonly string $uploadUrl,
        public readonly string $method,
        public readonly array $requiredHeaders,
        public readonly string $expiresAtIso,
        public readonly int $expectedSizeBytes
    ) {
    }

    /**
     * @return array<string, mixed>
     */
    public function toArray(): array
    {
        return [
            'object_key' => $this->objectKey,
            'upload_url' => $this->uploadUrl,
            'method' => $this->method,
            'upload_headers' => $this->requiredHeaders,
            'upload_expires_at' => $this->expiresAtIso,
            'expected_file_size' => $this->expectedSizeBytes,
        ];
    }
}
