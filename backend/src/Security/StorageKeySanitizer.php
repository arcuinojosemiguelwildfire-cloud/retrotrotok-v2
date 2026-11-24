<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Security;

use RetroTok\Cloud\Exception\ValidationException;

/**
 * Builds canonical Cloudflare R2 object keys and strictly prevents path traversal.
 */
final class StorageKeySanitizer
{
    public static function sanitizeIdentifier(string $raw, string $fieldName = 'identifier'): string
    {
        $trimmed = trim($raw);
        if ($trimmed === '' || strlen($trimmed) > 64) {
            throw new ValidationException("Invalid {$fieldName} length.", 'INVALID_IDENTIFIER');
        }

        if (
            str_contains($trimmed, '..')
            || str_contains($trimmed, '/')
            || str_contains($trimmed, '\\')
            || str_contains($trimmed, "\0")
            || str_contains(strtolower($trimmed), '%2e')
        ) {
            throw new ValidationException("Unsafe path characters detected in {$fieldName}.", 'PATH_TRAVERSAL_REJECTED');
        }

        if (preg_match('/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/', $trimmed) !== 1) {
            throw new ValidationException("{$fieldName} contains disallowed characters.", 'INVALID_IDENTIFIER');
        }

        return $trimmed;
    }

    public static function validateObjectKey(string $objectKey): string
    {
        if ($objectKey === '' || strlen($objectKey) > 255) {
            throw new ValidationException('Invalid object key length.', 'INVALID_OBJECT_KEY');
        }
        if (
            str_starts_with($objectKey, '/')
            || str_contains($objectKey, '..')
            || str_contains($objectKey, '\\')
            || str_contains($objectKey, "\0")
            || str_contains(strtolower($objectKey), '%2e')
            || preg_match('/[\x00-\x1F\x7F]/', $objectKey) === 1
        ) {
            throw new ValidationException('Unsafe object key rejected.', 'PATH_TRAVERSAL_REJECTED');
        }
        if (preg_match('#^events/[1-9][0-9]*/(videos|thumbs)/[A-Za-z0-9][A-Za-z0-9_-]{0,63}\.(mp4|jpg)$#', $objectKey) !== 1) {
            throw new ValidationException('Object key does not match canonical R2 layout.', 'INVALID_OBJECT_KEY');
        }
        return $objectKey;
    }

    public static function buildVideoObjectKey(int $eventId, string $kioskVideoId): string
    {
        if ($eventId <= 0) {
            throw new ValidationException('Event ID must be positive.', 'INVALID_EVENT_ID');
        }
        $safeVideoId = self::sanitizeIdentifier($kioskVideoId, 'kiosk_video_id');
        return self::validateObjectKey(sprintf('events/%d/videos/%s.mp4', $eventId, $safeVideoId));
    }

    public static function buildThumbnailObjectKey(int $eventId, string $kioskVideoId): string
    {
        if ($eventId <= 0) {
            throw new ValidationException('Event ID must be positive.', 'INVALID_EVENT_ID');
        }
        $safeVideoId = self::sanitizeIdentifier($kioskVideoId, 'kiosk_video_id');
        return self::validateObjectKey(sprintf('events/%d/thumbs/%s.jpg', $eventId, $safeVideoId));
    }
}
