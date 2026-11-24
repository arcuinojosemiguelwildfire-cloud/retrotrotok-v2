<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Storage;

use RetroTok\Cloud\Security\StorageKeySanitizer;
use RetroTok\Cloud\Support\ClockInterface;

/**
 * TEST-ONLY in-memory mock implementation of ObjectStorageInterface.
 * Must NEVER be bound in production entry points; exists strictly for unit/integration tests
 * where real Cloudflare R2 credentials and network access are unavailable.
 */
final class LocalMockObjectStorageAdapter implements ObjectStorageInterface
{
    /** @var array<string, ObjectMetadata> */
    private array $objects = [];
    public bool $failDeletes = false;

    public function __construct(
        private readonly ClockInterface $clock,
        private readonly string $mockBaseUrl = 'https://mock-r2.local.invalid'
    ) {
    }

    public function isConfigured(): bool
    {
        return true;
    }

    public function seedUploadedObject(string $objectKey, int $sizeBytes, string $mimeType = 'video/mp4'): void
    {
        $safeKey = StorageKeySanitizer::validateObjectKey($objectKey);
        $this->objects[$safeKey] = new ObjectMetadata(
            objectKey: $safeKey,
            contentLength: $sizeBytes,
            contentType: $mimeType,
            etag: md5($safeKey . ':' . $sizeBytes),
            lastModified: $this->clock->now()->format(\DateTimeInterface::RFC7231)
        );
    }

    public function createUploadAuthorization(
        string $objectKey,
        string $contentType,
        int $contentLengthBytes,
        int $ttlSeconds = 900
    ): UploadAuthorization {
        $safeKey = StorageKeySanitizer::validateObjectKey($objectKey);
        $now = $this->clock->now();
        $expires = $now->modify("+{$ttlSeconds} seconds")->format(\DateTimeInterface::ATOM);

        return new UploadAuthorization(
            objectKey: $safeKey,
            uploadUrl: $this->mockBaseUrl . '/upload/' . rawurlencode($safeKey) . '?expires=' . rawurlencode($expires),
            method: 'PUT',
            requiredHeaders: [
                'Content-Type' => $contentType,
                'Content-Length' => (string) $contentLengthBytes,
            ],
            expiresAtIso: $expires,
            expectedSizeBytes: $contentLengthBytes
        );
    }

    public function verifyUploadedObject(
        string $objectKey,
        int $expectedSizeBytes,
        string $expectedMimeType
    ): ObjectVerificationResult {
        $meta = $this->checkObjectMetadata($objectKey);
        if ($meta === null) {
            return ObjectVerificationResult::fail('R2_OBJECT_MISSING', 'Mock object missing.');
        }
        if ($meta->contentLength !== $expectedSizeBytes) {
            return ObjectVerificationResult::fail('R2_SIZE_MISMATCH', 'Mock size mismatch.', $meta);
        }
        if (strtolower($meta->contentType) !== strtolower($expectedMimeType)) {
            return ObjectVerificationResult::fail('R2_MIME_MISMATCH', 'Mock MIME mismatch.', $meta);
        }
        return ObjectVerificationResult::ok($meta);
    }

    public function generatePlaybackUrl(
        string $objectKey,
        int $ttlSeconds = 1800,
        ?string $downloadFilename = null
    ): string {
        $safeKey = StorageKeySanitizer::validateObjectKey($objectKey);
        $exp = $this->clock->now()->getTimestamp() + $ttlSeconds;
        $query = "exp={$exp}";
        if ($downloadFilename !== null && $downloadFilename !== '') {
            $query .= '&download=' . rawurlencode($downloadFilename);
        }
        return $this->mockBaseUrl . '/play/' . rawurlencode($safeKey) . '?' . $query;
    }

    public function deleteObject(string $objectKey): bool
    {
        if ($this->failDeletes) {
            return false;
        }
        $safeKey = StorageKeySanitizer::validateObjectKey($objectKey);
        unset($this->objects[$safeKey]);
        return true;
    }

    public function checkObjectMetadata(string $objectKey): ?ObjectMetadata
    {
        $safeKey = StorageKeySanitizer::validateObjectKey($objectKey);
        return $this->objects[$safeKey] ?? null;
    }

    public function listObjectsByPrefix(string $prefix, int $maxKeys = 1000): array
    {
        $matched = [];
        foreach (array_keys($this->objects) as $key) {
            if (str_starts_with($key, $prefix)) {
                $matched[] = $key;
                if (count($matched) >= $maxKeys) {
                    break;
                }
            }
        }
        sort($matched, SORT_STRING);
        return $matched;
    }
}
