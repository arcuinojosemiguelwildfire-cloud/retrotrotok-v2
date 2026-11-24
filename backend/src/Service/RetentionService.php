<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Service;

use RetroTok\Cloud\Exception\ServiceUnavailableException;
use RetroTok\Cloud\Repository\VideoRepository;
use RetroTok\Cloud\Storage\ObjectStorageInterface;
use RetroTok\Cloud\Support\ClockInterface;
use Throwable;

final class RetentionService
{
    public function __construct(
        private readonly VideoRepository $videos,
        private readonly ObjectStorageInterface $storage,
        private readonly ClockInterface $clock
    ) {
    }

    /**
     * Idempotent retention job: deletes expired R2 objects first, verifies deletion/absence,
     * and only then marks DB EXPIRED. Protects in-progress uploads (expires_at IS NULL / status != AVAILABLE).
     *
     * @return array{scanned: int, expired: int, failed: int, errors: list<string>}
     */
    public function cleanupExpiredVideos(int $batchLimit = 100): array
    {
        $nowUtc = $this->clock->now()->format('Y-m-d H:i:s');
        $candidates = $this->videos->findExpiredCandidates($nowUtc, $batchLimit);

        $expired = 0;
        $failed = 0;
        $errors = [];

        foreach ($candidates as $video) {
            $videoId = (int) $video['id'];
            try {
                if (!$this->storage->deleteObject((string) $video['storage_key'])) {
                    throw new ServiceUnavailableException('Object deletion could not be verified.', 'R2_DELETE_FAILED');
                }
                if (!empty($video['thumbnail_key'])) {
                    if (!$this->storage->deleteObject((string) $video['thumbnail_key'])) {
                        throw new ServiceUnavailableException('Thumbnail deletion could not be verified.', 'R2_DELETE_FAILED');
                    }
                }
                $this->videos->markExpired($videoId, $nowUtc);
                $expired++;
            } catch (Throwable $e) {
                $failed++;
                $errors[] = "video_id={$videoId}: " . $e->getMessage();
            }
        }

        return [
            'scanned' => count($candidates),
            'expired' => $expired,
            'failed' => $failed,
            'errors' => $errors,
        ];
    }

    /**
     * Detects orphaned R2 objects (present in R2 without an active DB video record)
     * and missing R2 objects (marked AVAILABLE in DB but absent in R2).
     * Never automatically destroys in-progress or recoverable records.
     *
     * @return array{
     *   event_id: int,
     *   available_db_count: int,
     *   in_progress_protected_count: int,
     *   missing_r2_objects: list<array{cloud_video_id: int, kiosk_video_id: string, storage_key: string}>,
     *   orphaned_r2_objects: list<string>
     * }
     */
    public function reconcileEventStorage(int $eventId): array
    {
        $dbVideos = $this->videos->findAllByEvent($eventId);
        $r2Keys = $this->storage->listObjectsByPrefix("events/{$eventId}/");
        $r2KeySet = array_flip($r2Keys);

        $knownActiveKeys = [];
        $availableCount = 0;
        $inProgressCount = 0;
        $missingR2Objects = [];

        foreach ($dbVideos as $video) {
            $status = (string) $video['status'];
            $storageKey = (string) $video['storage_key'];
            $thumbKey = $video['thumbnail_key'] !== null ? (string) $video['thumbnail_key'] : null;

            if (in_array($status, ['PENDING_UPLOAD', 'UPLOADING', 'UPLOAD_FAILED'], true)) {
                $inProgressCount++;
                $knownActiveKeys[$storageKey] = true;
                if ($thumbKey !== null) {
                    $knownActiveKeys[$thumbKey] = true;
                }
                continue;
            }

            if ($status === 'AVAILABLE' && $video['deleted_at'] === null) {
                $availableCount++;
                $knownActiveKeys[$storageKey] = true;
                if ($thumbKey !== null) {
                    $knownActiveKeys[$thumbKey] = true;
                }
                if (!isset($r2KeySet[$storageKey]) && $this->storage->checkObjectMetadata($storageKey) === null) {
                    $missingR2Objects[] = [
                        'cloud_video_id' => (int) $video['id'],
                        'kiosk_video_id' => (string) $video['kiosk_video_id'],
                        'storage_key' => $storageKey,
                    ];
                }
            }
        }

        $orphanedR2Objects = [];
        foreach ($r2Keys as $key) {
            if (!isset($knownActiveKeys[$key])) {
                $orphanedR2Objects[] = $key;
            }
        }

        return [
            'event_id' => $eventId,
            'available_db_count' => $availableCount,
            'in_progress_protected_count' => $inProgressCount,
            'missing_r2_objects' => $missingR2Objects,
            'orphaned_r2_objects' => $orphanedR2Objects,
        ];
    }
}
