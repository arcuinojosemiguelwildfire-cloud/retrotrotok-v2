<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Service;

use RetroTok\Cloud\Exception\NotFoundException;
use RetroTok\Cloud\Exception\ServiceUnavailableException;
use RetroTok\Cloud\Exception\ValidationException;
use RetroTok\Cloud\Repository\VideoRepository;
use RetroTok\Cloud\Storage\ObjectStorageInterface;
use RetroTok\Cloud\Support\ClockInterface;

final class AdminVideoService
{
    public function __construct(
        private readonly VideoRepository $videos,
        private readonly ObjectStorageInterface $storage,
        private readonly ClockInterface $clock
    ) {
    }

    /**
     * Idempotent admin deletion: deletes R2 MP4 & thumbnail objects before marking MySQL DELETED
     * and nullifying access_token_hash so QR access stops immediately.
     *
     * @return array<string, mixed>
     */
    public function deleteVideo(int $videoId): array
    {
        if ($videoId <= 0) {
            throw new ValidationException('Invalid videoId.', 'INVALID_VIDEO_ID');
        }

        $video = $this->videos->findById($videoId);
        if ($video === null) {
            throw new NotFoundException('Video record not found.', 'VIDEO_NOT_FOUND');
        }

        if ((string) $video['status'] === 'DELETED') {
            return [
                'cloud_video_id' => $videoId,
                'status' => 'DELETED',
                'already_deleted' => true,
                'deleted_at' => $video['deleted_at'],
            ];
        }

        if (!$this->storage->deleteObject((string) $video['storage_key'])) {
            throw new ServiceUnavailableException('Failed to delete video object from R2 storage.', 'R2_DELETE_FAILED');
        }
        if (!empty($video['thumbnail_key'])) {
            if (!$this->storage->deleteObject((string) $video['thumbnail_key'])) {
                throw new ServiceUnavailableException('Failed to delete thumbnail object from R2 storage.', 'R2_DELETE_FAILED');
            }
        }

        $nowUtc = $this->clock->now()->format('Y-m-d H:i:s');
        $this->videos->markDeleted($videoId, $nowUtc);

        return [
            'cloud_video_id' => $videoId,
            'status' => 'DELETED',
            'already_deleted' => false,
            'deleted_at' => $nowUtc,
        ];
    }
}
