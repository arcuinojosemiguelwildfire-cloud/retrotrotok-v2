<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Service;

use RetroTok\Cloud\Config\Config;
use RetroTok\Cloud\Exception\GoneException;
use RetroTok\Cloud\Exception\NotFoundException;
use RetroTok\Cloud\Repository\VideoAccessLogRepository;
use RetroTok\Cloud\Repository\VideoRepository;
use RetroTok\Cloud\Security\RateLimiter;
use RetroTok\Cloud\Security\TokenService;
use RetroTok\Cloud\Storage\ObjectStorageInterface;
use RetroTok\Cloud\Support\ClockInterface;

final class PublicVideoService
{
    public function __construct(
        private readonly VideoRepository $videos,
        private readonly VideoAccessLogRepository $accessLogs,
        private readonly TokenService $tokens,
        private readonly RateLimiter $rateLimiter,
        private readonly ObjectStorageInterface $storage,
        private readonly Config $config,
        private readonly ClockInterface $clock
    ) {
    }

    /**
     * Resolves a QR bearer token into short-lived signed playback & download URLs.
     * Never exposes internal video IDs, storage keys, or R2 secrets.
     *
     * @return array<string, mixed>
     */
    public function resolvePublicVideo(string $rawToken, string $clientIp): array
    {
        $this->rateLimiter->assertWithinLimit(
            scope: 'qr_view',
            subject: $clientIp,
            maxRequests: $this->config->rateLimitQrMaxRequests,
            windowSeconds: $this->config->rateLimitQrWindowSeconds
        );

        if (!$this->tokens->isValidQrTokenFormat($rawToken)) {
            throw new NotFoundException('Video link is invalid or unavailable.', 'VIDEO_UNAVAILABLE');
        }

        $candidateHash = $this->tokens->hashQrToken($rawToken);
        $video = $this->videos->findByTokenHash($candidateHash);
        if ($video === null) {
            throw new NotFoundException('Video link is invalid or unavailable.', 'VIDEO_UNAVAILABLE');
        }

        $storedHash = (string) ($video['access_token_hash'] ?? '');
        if (!$this->tokens->verifyConstantTime($storedHash, $candidateHash)) {
            throw new NotFoundException('Video link is invalid or unavailable.', 'VIDEO_UNAVAILABLE');
        }

        $now = $this->clock->now();
        $nowUtc = $now->format('Y-m-d H:i:s');
        $videoId = (int) $video['id'];
        $status = (string) $video['status'];

        if ($status === 'DELETED' || $video['deleted_at'] !== null) {
            $this->accessLogs->logAccess($videoId, 'REVOKED_ATTEMPT', $nowUtc);
            throw new GoneException('This video link has been removed or revoked.', 'VIDEO_REVOKED');
        }

        $expiresAt = (string) ($video['expires_at'] ?? '');
        if ($status === 'EXPIRED' || ($expiresAt !== '' && $expiresAt <= $nowUtc)) {
            $this->accessLogs->logAccess($videoId, 'EXPIRED_ATTEMPT', $nowUtc);
            throw new GoneException('This video link has expired after its 30-day retention window.', 'VIDEO_EXPIRED');
        }

        if ($status !== 'AVAILABLE') {
            throw new NotFoundException('Video is not available yet.', 'VIDEO_UNAVAILABLE');
        }

        $storageKey = (string) $video['storage_key'];
        $downloadFilename = 'retrotok-' . preg_replace('/[^A-Za-z0-9_-]/', '', (string) $video['kiosk_video_id']) . '.mp4';

        $playbackUrl = $this->storage->generatePlaybackUrl(
            objectKey: $storageKey,
            ttlSeconds: $this->config->playbackUrlTtlSeconds,
            downloadFilename: null
        );
        $downloadUrl = $this->storage->generatePlaybackUrl(
            objectKey: $storageKey,
            ttlSeconds: $this->config->playbackUrlTtlSeconds,
            downloadFilename: $downloadFilename
        );

        $this->accessLogs->logAccess($videoId, 'PAGE_VIEW', $nowUtc);

        return [
            'event_name' => (string) $video['event_name'],
            'soundtrack_title' => (string) $video['soundtrack_title'],
            'soundtrack_artist' => (string) $video['soundtrack_artist'],
            'duration_seconds' => (int) $video['duration_seconds'],
            'expires_at' => $expiresAt,
            'playback_url' => $playbackUrl,
            'download_url' => $downloadUrl,
            'url_ttl_seconds' => $this->config->playbackUrlTtlSeconds,
        ];
    }
}
