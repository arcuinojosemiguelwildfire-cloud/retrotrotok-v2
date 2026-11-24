<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Security;

use RetroTok\Cloud\Config\Config;
use RetroTok\Cloud\Exception\ValidationException;

/**
 * Validates incoming video upload initiation metadata before issuing R2 authorization.
 */
final class UploadValidator
{
    public const MIN_VIDEO_BYTES = 1024;

    public function __construct(private readonly Config $config)
    {
    }

    /**
     * @param array<string, mixed> $payload
     * @return array{
     *   kiosk_video_id: string,
     *   file_size: int,
     *   duration_seconds: int,
     *   mime_type: string,
     *   width: int|null,
     *   height: int|null,
     *   checksum: string|null,
     *   nickname: string,
     *   caption: string,
     *   soundtrack_title: string,
     *   soundtrack_artist: string,
     *   effect_type: string
     * }
     */
    public function validateInitiatePayload(array $payload): array
    {
        $rawVideoId = (string) ($payload['kiosk_video_id'] ?? '');
        $kioskVideoId = StorageKeySanitizer::sanitizeIdentifier($rawVideoId, 'kiosk_video_id');

        $fileSize = filter_var($payload['file_size'] ?? null, FILTER_VALIDATE_INT);
        if ($fileSize === false || $fileSize < self::MIN_VIDEO_BYTES) {
            throw new ValidationException(
                'file_size must be at least ' . self::MIN_VIDEO_BYTES . ' bytes.',
                'INVALID_FILE_SIZE'
            );
        }
        if ($fileSize > $this->config->maxVideoUploadBytes) {
            throw new ValidationException(
                'file_size exceeds maximum allowed upload limit (' . $this->config->maxVideoUploadBytes . ' bytes).',
                'FILE_TOO_LARGE',
                ['max_allowed_bytes' => $this->config->maxVideoUploadBytes]
            );
        }

        $duration = filter_var($payload['duration_seconds'] ?? null, FILTER_VALIDATE_INT);
        if ($duration === false || $duration < 1 || $duration > $this->config->maxVideoDurationSeconds) {
            throw new ValidationException(
                'duration_seconds must be between 1 and ' . $this->config->maxVideoDurationSeconds . '.',
                'INVALID_DURATION'
            );
        }

        $mimeType = strtolower(trim((string) ($payload['mime_type'] ?? '')));
        if ($mimeType === '' || !in_array($mimeType, $this->config->allowedVideoMimeTypes, true)) {
            throw new ValidationException(
                'Unsupported mime_type. Allowed: ' . implode(', ', $this->config->allowedVideoMimeTypes),
                'UNSUPPORTED_MIME_TYPE'
            );
        }

        $width = isset($payload['width']) ? filter_var($payload['width'], FILTER_VALIDATE_INT) : null;
        if ($width === false || ($width !== null && ($width < 120 || $width > 3840))) {
            throw new ValidationException('Invalid video width.', 'INVALID_DIMENSIONS');
        }

        $height = isset($payload['height']) ? filter_var($payload['height'], FILTER_VALIDATE_INT) : null;
        if ($height === false || ($height !== null && ($height < 120 || $height > 3840))) {
            throw new ValidationException('Invalid video height.', 'INVALID_DIMENSIONS');
        }

        $checksum = null;
        if (isset($payload['checksum']) && $payload['checksum'] !== null && trim((string) $payload['checksum']) !== '') {
            $rawChecksum = strtolower(trim((string) $payload['checksum']));
            if (preg_match('/^[a-f0-9]{64}$/', $rawChecksum) !== 1) {
                throw new ValidationException('checksum must be a valid 64-char SHA-256 hex string.', 'INVALID_CHECKSUM');
            }
            $checksum = $rawChecksum;
        }

        $nickname = $this->cleanText((string) ($payload['nickname'] ?? 'RetroDancer'), 80, 'RetroDancer');
        $caption = $this->cleanText((string) ($payload['caption'] ?? ''), 280, '');
        $soundtrackTitle = $this->cleanText((string) ($payload['soundtrack_title'] ?? ''), 160, '');
        $soundtrackArtist = $this->cleanText((string) ($payload['soundtrack_artist'] ?? ''), 160, '');
        $effectType = $this->cleanText((string) ($payload['effect_type'] ?? 'NONE'), 48, 'NONE');

        return [
            'kiosk_video_id' => $kioskVideoId,
            'file_size' => $fileSize,
            'duration_seconds' => $duration,
            'mime_type' => $mimeType,
            'width' => $width,
            'height' => $height,
            'checksum' => $checksum,
            'nickname' => $nickname,
            'caption' => $caption,
            'soundtrack_title' => $soundtrackTitle,
            'soundtrack_artist' => $soundtrackArtist,
            'effect_type' => $effectType,
        ];
    }

    private function cleanText(string $value, int $maxLength, string $fallback): string
    {
        $stripped = preg_replace('/[\x00-\x1F\x7F]/u', ' ', trim($value)) ?? '';
        $clean = trim($stripped);
        if ($clean === '') {
            return $fallback;
        }
        return mb_substr($clean, 0, $maxLength, 'UTF-8');
    }
}
