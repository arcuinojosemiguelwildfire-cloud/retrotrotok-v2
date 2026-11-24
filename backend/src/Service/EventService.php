<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Service;

use RetroTok\Cloud\Config\Config;
use RetroTok\Cloud\Exception\ConflictException;
use RetroTok\Cloud\Exception\NotFoundException;
use RetroTok\Cloud\Exception\ValidationException;
use RetroTok\Cloud\Repository\EventRepository;
use RetroTok\Cloud\Security\StorageKeySanitizer;
use RetroTok\Cloud\Support\ClockInterface;

final class EventService
{
    private const ALLOWED_CREATE_STATUSES = ['NOT_STARTED', 'ACTIVE', 'PAUSED'];

    public function __construct(
        private readonly EventRepository $events,
        private readonly Config $config,
        private readonly ClockInterface $clock
    ) {
    }

    /**
     * @param array<string, mixed> $payload
     * @return array{created: bool, event: array<string, mixed>}
     */
    public function createEvent(array $payload): array
    {
        $rawName = trim((string) ($payload['event_name'] ?? ''));
        if ($rawName === '') {
            throw new ValidationException('event_name is required.', 'MISSING_EVENT_NAME');
        }
        $eventName = mb_substr($rawName, 0, 160, 'UTF-8');

        $rawDesc = isset($payload['event_description']) ? trim((string) $payload['event_description']) : null;
        $eventDescription = ($rawDesc !== null && $rawDesc !== '') ? mb_substr($rawDesc, 0, 2000, 'UTF-8') : null;

        $status = strtoupper(trim((string) ($payload['status'] ?? 'ACTIVE')));
        if (!in_array($status, self::ALLOWED_CREATE_STATUSES, true)) {
            throw new ValidationException(
                'status must be one of: ' . implode(', ', self::ALLOWED_CREATE_STATUSES),
                'INVALID_EVENT_STATUS'
            );
        }

        $maxVideos = $this->config->eventMaxVideos;
        if (isset($payload['max_videos'])) {
            $requestedMax = filter_var($payload['max_videos'], FILTER_VALIDATE_INT);
            if ($requestedMax === false || $requestedMax < 1 || $requestedMax > 300) {
                throw new ValidationException('max_videos must be between 1 and 300.', 'INVALID_EVENT_CAPACITY');
            }
            $maxVideos = $requestedMax;
        }

        $externalKey = null;
        if (isset($payload['external_event_key']) && trim((string) $payload['external_event_key']) !== '') {
            $externalKey = StorageKeySanitizer::sanitizeIdentifier(
                (string) $payload['external_event_key'],
                'external_event_key'
            );
            $existing = $this->events->findByExternalKey($externalKey);
            if ($existing !== null) {
                return [
                    'created' => false,
                    'event' => $this->formatEventWithMetrics($existing),
                ];
            }
        }

        $nowUtc = $this->clock->now()->format('Y-m-d H:i:s');
        $startedAt = $status === 'ACTIVE' ? $nowUtc : null;

        $created = $this->events->create(
            eventName: $eventName,
            eventDescription: $eventDescription,
            status: $status,
            maxVideos: $maxVideos,
            externalEventKey: $externalKey,
            startedAt: $startedAt,
            nowUtc: $nowUtc
        );

        return [
            'created' => true,
            'event' => $this->formatEventWithMetrics($created),
        ];
    }

    /** @var array<string, list<string>> */
    private const ALLOWED_TRANSITIONS = [
        'NOT_STARTED' => ['NOT_STARTED', 'ACTIVE', 'PAUSED', 'CLOSING', 'ENDED'],
        'ACTIVE' => ['ACTIVE', 'PAUSED', 'CLOSING', 'ENDED'],
        'PAUSED' => ['PAUSED', 'ACTIVE', 'CLOSING', 'ENDED'],
        'CLOSING' => ['CLOSING', 'ENDED'],
        'ENDED' => ['ENDED'],
    ];

    /**
     * @return array<string, mixed>
     */
    public function getEvent(int $eventId): array
    {
        if ($eventId <= 0) {
            throw new ValidationException('Invalid eventId.', 'INVALID_EVENT_ID');
        }
        $event = $this->events->findById($eventId);
        if ($event === null) {
            throw new NotFoundException('Event not found.', 'EVENT_NOT_FOUND');
        }

        return $this->formatEventWithMetrics($event);
    }

    /**
     * Transitions event lifecycle status along NOT_STARTED -> ACTIVE -> PAUSED -> CLOSING -> ENDED.
     * Preserves all pending/in-flight uploads when moving to PAUSED, CLOSING, or ENDED.
     *
     * @return array<string, mixed>
     */
    public function transitionStatus(int $eventId, string $rawTargetStatus): array
    {
        if ($eventId <= 0) {
            throw new ValidationException('Invalid eventId.', 'INVALID_EVENT_ID');
        }

        $targetStatus = strtoupper(trim($rawTargetStatus));
        if (!array_key_exists($targetStatus, self::ALLOWED_TRANSITIONS)) {
            throw new ValidationException(
                'status must be one of: ' . implode(', ', array_keys(self::ALLOWED_TRANSITIONS)),
                'INVALID_EVENT_STATUS'
            );
        }

        $event = $this->events->findById($eventId);
        if ($event === null) {
            throw new NotFoundException('Event not found.', 'EVENT_NOT_FOUND');
        }

        $currentStatus = (string) $event['status'];
        $allowedNext = self::ALLOWED_TRANSITIONS[$currentStatus] ?? [];
        if (!in_array($targetStatus, $allowedNext, true)) {
            throw new ConflictException(
                "Invalid event lifecycle transition from {$currentStatus} to {$targetStatus}.",
                'INVALID_EVENT_TRANSITION',
                ['current_status' => $currentStatus, 'target_status' => $targetStatus]
            );
        }

        $nowUtc = $this->clock->now()->format('Y-m-d H:i:s');
        $startedAt = $event['started_at'];
        if ($targetStatus === 'ACTIVE' && $startedAt === null) {
            $startedAt = $nowUtc;
        }
        $endedAt = $event['ended_at'];
        if ($targetStatus === 'ENDED' && $endedAt === null) {
            $endedAt = $nowUtc;
        }

        $updated = $this->events->updateStatus($eventId, $targetStatus, $startedAt, $endedAt, $nowUtc);
        return $this->formatEventWithMetrics($updated);
    }

    /**
     * @param array<string, mixed> $event
     * @return array<string, mixed>
     */
    private function formatEventWithMetrics(array $event): array
    {
        $eventId = (int) $event['id'];
        $maxVideos = (int) $event['max_videos'];
        $metrics = $this->events->getEventVideoMetrics($eventId);

        return [
            'id' => $eventId,
            'external_event_key' => $event['external_event_key'],
            'event_name' => (string) $event['event_name'],
            'event_description' => $event['event_description'],
            'status' => (string) $event['status'],
            'max_videos' => $maxVideos,
            'active_video_count' => $metrics['active_count'],
            'available_video_count' => $metrics['available_count'],
            'pending_upload_count' => $metrics['pending_count'],
            'failed_upload_count' => $metrics['failed_count'],
            'remaining_capacity' => max(0, $maxVideos - $metrics['active_count']),
            'total_uploaded_bytes' => $metrics['total_uploaded_bytes'],
            'started_at' => $event['started_at'],
            'ended_at' => $event['ended_at'],
            'created_at' => $event['created_at'],
        ];
    }
}
