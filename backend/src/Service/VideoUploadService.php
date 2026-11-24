<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Service;

use PDO;
use RetroTok\Cloud\Auth\AuthContext;
use RetroTok\Cloud\Config\Config;
use RetroTok\Cloud\Exception\ConflictException;
use RetroTok\Cloud\Exception\NotFoundException;
use RetroTok\Cloud\Exception\ValidationException;
use RetroTok\Cloud\Repository\EventRepository;
use RetroTok\Cloud\Repository\VideoRepository;
use RetroTok\Cloud\Repository\VideoUploadRepository;
use RetroTok\Cloud\Security\StorageKeySanitizer;
use RetroTok\Cloud\Security\TokenService;
use RetroTok\Cloud\Security\UploadValidator;
use RetroTok\Cloud\Storage\ObjectStorageInterface;
use RetroTok\Cloud\Support\ClockInterface;
use Throwable;

final class VideoUploadService
{
    public function __construct(
        private readonly PDO $pdo,
        private readonly EventRepository $events,
        private readonly VideoRepository $videos,
        private readonly VideoUploadRepository $uploads,
        private readonly UploadValidator $validator,
        private readonly TokenService $tokens,
        private readonly ObjectStorageInterface $storage,
        private readonly Config $config,
        private readonly ClockInterface $clock
    ) {
    }

    /**
     * Initiates or idempotently refreshes an upload session for a kiosk video.
     * Enforces active event state, 300-video capacity, and R2 pre-signed upload boundaries.
     *
     * @param array<string, mixed> $payload
     * @return array{http_status: int, data: array<string, mixed>}
     */
    public function initiateUpload(
        int $eventId,
        array $payload,
        AuthContext $auth,
        ?string $idempotencyKey = null
    ): array {
        if ($eventId <= 0) {
            throw new ValidationException('Invalid eventId.', 'INVALID_EVENT_ID');
        }

        $clean = $this->validator->validateInitiatePayload($payload);
        $now = $this->clock->now();
        $nowUtc = $now->format('Y-m-d H:i:s');

        $this->pdo->beginTransaction();
        try {
            $event = $this->events->findByIdForUpdate($eventId);
            if ($event === null) {
                throw new NotFoundException('Event not found.', 'EVENT_NOT_FOUND');
            }

            $existing = $this->videos->findByEventAndKioskVideoId($eventId, $clean['kiosk_video_id']);
            if ($existing !== null) {
                $existingStatus = (string) $existing['status'];
                if ($existingStatus === 'AVAILABLE') {
                    $this->pdo->commit();
                    return [
                        'http_status' => 200,
                        'data' => [
                            'cloud_video_id' => (int) $existing['id'],
                            'event_id' => $eventId,
                            'kiosk_video_id' => (string) $existing['kiosk_video_id'],
                            'status' => 'AVAILABLE',
                            'already_available' => true,
                            'uploaded_at' => $existing['uploaded_at'],
                            'expires_at' => $existing['expires_at'],
                        ],
                    ];
                }

                if (in_array($existingStatus, ['EXPIRED', 'DELETED'], true)) {
                    throw new ConflictException(
                        'This video identifier has already been expired or deleted.',
                        'VIDEO_CLOSED'
                    );
                }

                $authz = $this->storage->createUploadAuthorization(
                    objectKey: (string) $existing['storage_key'],
                    contentType: (string) $existing['mime_type'],
                    contentLengthBytes: (int) $existing['file_size'],
                    ttlSeconds: $this->config->uploadUrlTtlSeconds
                );

                $presignExpiresUtc = $now->modify("+{$this->config->uploadUrlTtlSeconds} seconds")->format('Y-m-d H:i:s');
                $attempt = $this->uploads->createAttempt(
                    videoId: (int) $existing['id'],
                    idempotencyKey: $idempotencyKey,
                    expectedSize: (int) $existing['file_size'],
                    presignedExpiresAtUtc: $presignExpiresUtc,
                    nowUtc: $nowUtc
                );

                $this->pdo->commit();
                return [
                    'http_status' => 200,
                    'data' => [
                        'cloud_video_id' => (int) $existing['id'],
                        'event_id' => $eventId,
                        'kiosk_video_id' => (string) $existing['kiosk_video_id'],
                        'status' => $existingStatus,
                        'idempotent_replay' => true,
                        'upload_attempt' => (int) $attempt['upload_attempt'],
                        'storage_key' => $authz->objectKey,
                        'upload_url' => $authz->uploadUrl,
                        'upload_headers' => $authz->requiredHeaders,
                        'upload_expires_at' => $authz->expiresAtIso,
                        'expected_file_size' => $authz->expectedSizeBytes,
                    ],
                ];
            }

            if ((string) $event['status'] !== 'ACTIVE') {
                throw new ConflictException(
                    'New upload initialization is allowed only while event is ACTIVE.',
                    'EVENT_NOT_ACTIVE',
                    ['event_status' => (string) $event['status']]
                );
            }

            // Enforce strict event video capacity (default 300)
            $maxVideos = min(300, (int) $event['max_videos']);
            $currentActive = $this->videos->countCapacityConsumingVideos($eventId);
            if ($currentActive >= $maxVideos) {
                throw new ConflictException(
                    "Event has reached its maximum capacity of {$maxVideos} videos.",
                    'EVENT_CAPACITY_REACHED',
                    ['max_videos' => $maxVideos, 'active_video_count' => $currentActive]
                );
            }

            $storageKey = StorageKeySanitizer::buildVideoObjectKey($eventId, $clean['kiosk_video_id']);
            $thumbnailKey = StorageKeySanitizer::buildThumbnailObjectKey($eventId, $clean['kiosk_video_id']);

            // Authorize with object storage BEFORE finalizing DB insert so unconfigured R2 leaves zero phantom rows.
            $authz = $this->storage->createUploadAuthorization(
                objectKey: $storageKey,
                contentType: $clean['mime_type'],
                contentLengthBytes: $clean['file_size'],
                ttlSeconds: $this->config->uploadUrlTtlSeconds
            );

            $video = $this->videos->createPendingVideo([
                'event_id' => $eventId,
                'kiosk_device_id' => $auth->deviceId,
                'kiosk_video_id' => $clean['kiosk_video_id'],
                'nickname' => $clean['nickname'],
                'caption' => $clean['caption'],
                'soundtrack_title' => $clean['soundtrack_title'],
                'soundtrack_artist' => $clean['soundtrack_artist'],
                'effect_type' => $clean['effect_type'],
                'storage_key' => $storageKey,
                'thumbnail_key' => $thumbnailKey,
                'file_size' => $clean['file_size'],
                'duration_seconds' => $clean['duration_seconds'],
                'resolution_width' => $clean['width'],
                'resolution_height' => $clean['height'],
                'mime_type' => $clean['mime_type'],
                'checksum' => $clean['checksum'],
                'now_utc' => $nowUtc,
            ]);

            $presignExpiresUtc = $now->modify("+{$this->config->uploadUrlTtlSeconds} seconds")->format('Y-m-d H:i:s');
            $attempt = $this->uploads->createAttempt(
                videoId: (int) $video['id'],
                idempotencyKey: $idempotencyKey,
                expectedSize: $clean['file_size'],
                presignedExpiresAtUtc: $presignExpiresUtc,
                nowUtc: $nowUtc
            );

            $this->pdo->commit();
            return [
                'http_status' => 201,
                'data' => [
                    'cloud_video_id' => (int) $video['id'],
                    'event_id' => $eventId,
                    'kiosk_video_id' => (string) $video['kiosk_video_id'],
                    'status' => 'PENDING_UPLOAD',
                    'idempotent_replay' => false,
                    'upload_attempt' => (int) $attempt['upload_attempt'],
                    'storage_key' => $authz->objectKey,
                    'upload_url' => $authz->uploadUrl,
                    'upload_headers' => $authz->requiredHeaders,
                    'upload_expires_at' => $authz->expiresAtIso,
                    'expected_file_size' => $authz->expectedSizeBytes,
                ],
            ];
        } catch (Throwable $e) {
            if ($this->pdo->inTransaction()) {
                $this->pdo->rollBack();
            }
            throw $e;
        }
    }

    /**
     * Verifies uploaded object in R2 via HeadObject, enforces size/MIME match,
     * generates 256-bit QR token (storing only SHA-256 hash), and sets 30-day expiration.
     *
     * @return array<string, mixed>
     */
    public function completeUpload(int $videoId): array
    {
        if ($videoId <= 0) {
            throw new ValidationException('Invalid videoId.', 'INVALID_VIDEO_ID');
        }

        $video = $this->videos->findById($videoId);
        if ($video === null) {
            throw new NotFoundException('Video record not found.', 'VIDEO_NOT_FOUND');
        }

        $status = (string) $video['status'];
        if (in_array($status, ['DELETED', 'EXPIRED'], true)) {
            throw new ConflictException('Cannot complete upload for an expired or deleted video.', 'VIDEO_CLOSED');
        }

        if ($status === 'AVAILABLE') {
            return [
                'cloud_video_id' => $videoId,
                'event_id' => (int) $video['event_id'],
                'kiosk_video_id' => (string) $video['kiosk_video_id'],
                'status' => 'AVAILABLE',
                'already_completed' => true,
                'uploaded_at' => $video['uploaded_at'],
                'expires_at' => $video['expires_at'],
            ];
        }

        $now = $this->clock->now();
        $nowUtc = $now->format('Y-m-d H:i:s');

        $verification = $this->storage->verifyUploadedObject(
            objectKey: (string) $video['storage_key'],
            expectedSizeBytes: (int) $video['file_size'],
            expectedMimeType: (string) $video['mime_type']
        );

        if (!$verification->verified) {
            $errCode = $verification->failureCode ?? 'R2_VERIFICATION_FAILED';
            $errMsg = $verification->failureMessage ?? 'Uploaded object verification failed.';
            $this->videos->markUploadFailed($videoId, $nowUtc);
            $this->uploads->markLatestFailed($videoId, $errCode, $errMsg, $nowUtc);

            throw new ConflictException($errMsg, $errCode, [
                'cloud_video_id' => $videoId,
                'status' => 'UPLOAD_FAILED',
            ]);
        }

        $rawToken = $this->tokens->generateToken(32);
        $tokenHash = $this->tokens->hashQrToken($rawToken);
        $expiresAtUtc = $now->modify("+{$this->config->videoRetentionDays} days")->format('Y-m-d H:i:s');

        $this->pdo->beginTransaction();
        try {
            $this->videos->markAvailable($videoId, $tokenHash, $nowUtc, $expiresAtUtc);
            $this->uploads->markLatestVerified($videoId, (int) $video['file_size'], $nowUtc);
            $this->pdo->commit();
        } catch (Throwable $e) {
            if ($this->pdo->inTransaction()) {
                $this->pdo->rollBack();
            }
            throw $e;
        }

        $viewingBase = $this->config->appUrl;
        $urlPath = Config::normalizeBasePath((string) (parse_url($viewingBase, PHP_URL_PATH) ?: ''));
        if ($urlPath === '' && $this->config->appBasePath !== '') {
            $viewingBase .= $this->config->appBasePath;
        }

        return [
            'cloud_video_id' => $videoId,
            'event_id' => (int) $video['event_id'],
            'kiosk_video_id' => (string) $video['kiosk_video_id'],
            'status' => 'AVAILABLE',
            'already_completed' => false,
            'qr_token' => $rawToken,
            'viewing_url' => $viewingBase . '/watch/' . $rawToken,
            'uploaded_at' => $nowUtc,
            'expires_at' => $expiresAtUtc,
        ];
    }

    /**
     * @return array<string, mixed>
     */
    public function getStatus(int $videoId): array
    {
        if ($videoId <= 0) {
            throw new ValidationException('Invalid videoId.', 'INVALID_VIDEO_ID');
        }
        $video = $this->videos->findById($videoId);
        if ($video === null) {
            throw new NotFoundException('Video not found.', 'VIDEO_NOT_FOUND');
        }
        $latestUpload = $this->uploads->findLatestByVideoId($videoId);

        return [
            'cloud_video_id' => (int) $video['id'],
            'event_id' => (int) $video['event_id'],
            'kiosk_video_id' => (string) $video['kiosk_video_id'],
            'status' => (string) $video['status'],
            'file_size' => (int) $video['file_size'],
            'duration_seconds' => (int) $video['duration_seconds'],
            'mime_type' => (string) $video['mime_type'],
            'storage_key' => (string) $video['storage_key'],
            'uploaded_at' => $video['uploaded_at'],
            'expires_at' => $video['expires_at'],
            'deleted_at' => $video['deleted_at'],
            'latest_upload_attempt' => $latestUpload === null ? null : [
                'upload_attempt' => (int) $latestUpload['upload_attempt'],
                'upload_status' => (string) $latestUpload['upload_status'],
                'last_error_code' => $latestUpload['last_error_code'],
                'last_error' => $latestUpload['last_error'],
                'presigned_expires_at' => $latestUpload['presigned_expires_at'],
                'completed_at' => $latestUpload['completed_at'],
            ],
        ];
    }

    /**
     * Cursor-paginated event-scoped feed listing AVAILABLE videos newest-first.
     *
     * @return array<string, mixed>
     */
    public function listEventVideos(int $eventId, ?int $cursorId, int $limit): array
    {
        if ($eventId <= 0) {
            throw new ValidationException('Invalid eventId.', 'INVALID_EVENT_ID');
        }
        $event = $this->events->findById($eventId);
        if ($event === null) {
            throw new NotFoundException('Event not found.', 'EVENT_NOT_FOUND');
        }

        $safeLimit = max(1, min(50, $limit));
        $nowUtc = $this->clock->now()->format('Y-m-d H:i:s');
        $rows = $this->videos->listAvailableByEventCursor($eventId, $cursorId, $safeLimit + 1, $nowUtc);

        $hasMore = count($rows) > $safeLimit;
        if ($hasMore) {
            $rows = array_slice($rows, 0, $safeLimit);
        }

        $items = [];
        $nextCursor = null;
        $storageReady = $this->storage->isConfigured();

        foreach ($rows as $row) {
            $nextCursor = (int) $row['id'];
            $playbackUrl = $storageReady
                ? $this->storage->generatePlaybackUrl((string) $row['storage_key'], $this->config->playbackUrlTtlSeconds)
                : null;

            $items[] = [
                'cloud_video_id' => (int) $row['id'],
                'event_id' => (int) $row['event_id'],
                'kiosk_video_id' => (string) $row['kiosk_video_id'],
                'nickname' => (string) $row['nickname'],
                'caption' => (string) $row['caption'],
                'soundtrack_title' => (string) $row['soundtrack_title'],
                'soundtrack_artist' => (string) $row['soundtrack_artist'],
                'effect_type' => (string) $row['effect_type'],
                'duration_seconds' => (int) $row['duration_seconds'],
                'file_size' => (int) $row['file_size'],
                'resolution_width' => $row['resolution_width'] !== null ? (int) $row['resolution_width'] : null,
                'resolution_height' => $row['resolution_height'] !== null ? (int) $row['resolution_height'] : null,
                'status' => (string) $row['status'],
                'playback_url' => $playbackUrl,
                'uploaded_at' => $row['uploaded_at'],
                'expires_at' => $row['expires_at'],
            ];
        }

        return [
            'event_id' => $eventId,
            'storage_configured' => $storageReady,
            'videos' => $items,
            'next_cursor' => $hasMore ? $nextCursor : null,
        ];
    }
}
