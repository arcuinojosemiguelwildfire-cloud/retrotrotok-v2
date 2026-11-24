<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Controller;

use RetroTok\Cloud\Auth\AuthMiddleware;
use RetroTok\Cloud\Http\Request;
use RetroTok\Cloud\Http\Response;
use RetroTok\Cloud\Service\EventService;

final class EventController
{
    public function __construct(
        private readonly EventService $events,
        private readonly AuthMiddleware $auth
    ) {
    }

    public function create(Request $request): Response
    {
        $this->auth->requireAdmin($request);
        $result = $this->events->createEvent($request->body);
        $status = $result['created'] ? 201 : 200;

        return Response::json($status, [
            'ok' => true,
            'idempotent_replay' => !$result['created'],
            'data' => $result['event'],
        ]);
    }

    /**
     * @param array<string, string> $params
     */
    public function show(Request $request, array $params): Response
    {
        $this->auth->requireDeviceOrAdmin($request);
        $eventId = (int) ($params['eventId'] ?? 0);
        $event = $this->events->getEvent($eventId);

        return Response::json(200, [
            'ok' => true,
            'data' => $event,
        ]);
    }

    /**
     * POST /api/v1/events/{eventId}/status
     *
     * @param array<string, string> $params
     */
    public function updateStatus(Request $request, array $params): Response
    {
        $this->auth->requireAdmin($request);
        $eventId = (int) ($params['eventId'] ?? 0);
        $targetStatus = (string) ($request->body['status'] ?? '');
        $updated = $this->events->transitionStatus($eventId, $targetStatus);

        return Response::json(200, [
            'ok' => true,
            'data' => $updated,
        ]);
    }
}
