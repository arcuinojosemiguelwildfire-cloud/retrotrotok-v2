<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Controller;

use RetroTok\Cloud\Auth\AuthMiddleware;
use RetroTok\Cloud\Http\Request;
use RetroTok\Cloud\Http\Response;
use RetroTok\Cloud\Service\VideoUploadService;

final class VideoController
{
    public function __construct(
        private readonly VideoUploadService $videos,
        private readonly AuthMiddleware $auth
    ) {
    }

    /**
     * POST /api/v1/events/{eventId}/videos
     *
     * @param array<string, string> $params
     */
    public function initiate(Request $request, array $params): Response
    {
        $authCtx = $this->auth->requireDeviceOrAdmin($request);
        $eventId = (int) ($params['eventId'] ?? 0);
        $idempotencyKey = $request->header('idempotency-key');

        $result = $this->videos->initiateUpload(
            eventId: $eventId,
            payload: $request->body,
            auth: $authCtx,
            idempotencyKey: $idempotencyKey
        );

        return Response::json($result['http_status'], [
            'ok' => true,
            'data' => $result['data'],
        ]);
    }

    /**
     * POST /api/v1/videos/{videoId}/complete
     *
     * @param array<string, string> $params
     */
    public function complete(Request $request, array $params): Response
    {
        $this->auth->requireDeviceOrAdmin($request);
        $videoId = (int) ($params['videoId'] ?? 0);
        $data = $this->videos->completeUpload($videoId);

        return Response::json(200, [
            'ok' => true,
            'data' => $data,
        ]);
    }

    /**
     * GET /api/v1/events/{eventId}/videos
     *
     * @param array<string, string> $params
     */
    public function listByEvent(Request $request, array $params): Response
    {
        $this->auth->requireDeviceOrAdmin($request);
        $eventId = (int) ($params['eventId'] ?? 0);
        $cursor = isset($request->query['cursor']) && ctype_digit($request->query['cursor'])
            ? (int) $request->query['cursor']
            : null;
        $limit = isset($request->query['limit']) && ctype_digit($request->query['limit'])
            ? (int) $request->query['limit']
            : 20;

        $data = $this->videos->listEventVideos($eventId, $cursor, $limit);
        return Response::json(200, [
            'ok' => true,
            'data' => $data,
        ]);
    }

    /**
     * GET /api/v1/videos/{videoId}/status
     *
     * @param array<string, string> $params
     */
    public function status(Request $request, array $params): Response
    {
        $this->auth->requireDeviceOrAdmin($request);
        $videoId = (int) ($params['videoId'] ?? 0);
        $data = $this->videos->getStatus($videoId);

        return Response::json(200, [
            'ok' => true,
            'data' => $data,
        ]);
    }
}
