<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Storage;

/**
 * Provider-independent private object storage abstraction.
 * Implementations must never expose public bucket listing or credentials.
 */
interface ObjectStorageInterface
{
    public function isConfigured(): bool;

    public function createUploadAuthorization(
        string $objectKey,
        string $contentType,
        int $contentLengthBytes,
        int $ttlSeconds = 900
    ): UploadAuthorization;

    public function verifyUploadedObject(
        string $objectKey,
        int $expectedSizeBytes,
        string $expectedMimeType
    ): ObjectVerificationResult;

    public function generatePlaybackUrl(
        string $objectKey,
        int $ttlSeconds = 1800,
        ?string $downloadFilename = null
    ): string;

    public function deleteObject(string $objectKey): bool;

    public function checkObjectMetadata(string $objectKey): ?ObjectMetadata;

    /**
     * Admin-only prefix scan for reconciliation of orphaned R2 objects.
     * Must never be exposed on public endpoints.
     *
     * @return list<string>
     */
    public function listObjectsByPrefix(string $prefix, int $maxKeys = 1000): array;
}
