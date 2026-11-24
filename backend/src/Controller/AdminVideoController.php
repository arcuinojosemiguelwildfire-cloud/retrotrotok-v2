<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Controller;

use RetroTok\Cloud\Auth\AuthMiddleware;
use RetroTok\Cloud\Exception\ValidationException;
use RetroTok\Cloud\Http\Request;
use RetroTok\Cloud\Http\Response;
use RetroTok\Cloud\Service\AdminVideoService;
use RetroTok\Cloud\Service\RetentionService;

final class AdminVideoController
{
    public function __construct(
        private readonly AdminVideoService $adminVideos,
        private readonly RetentionService $retention,
        private readonly AuthMiddleware $auth
    ) {
    }

    /**
     * POST /api/v1/admin/videos/{videoId}/delete
     *
     * @param array<string, string> $params
     */
    public function delete(Request $request, array $params): Response
    {
        $this->auth->requireAdmin($request);
        $videoId = (int) ($params['videoId'] ?? 0);
        $data = $this->adminVideos->deleteVideo($videoId);

        return Response::json(200, [
            'ok' => true,
            'data' => $data,
        ]);
    }

    /**
     * GET /api/v1/admin/events/{eventId}/reconcile
     *
     * @param array<string, string> $params
     */
    public function reconcileEvent(Request $request, array $params): Response
    {
        $this->auth->requireAdmin($request);
        $eventId = (int) ($params['eventId'] ?? 0);
        if ($eventId <= 0) {
            throw new ValidationException('Invalid eventId.', 'INVALID_EVENT_ID');
        }

        $report = $this->retention->reconcileEventStorage($eventId);
        return Response::json(200, [
            'ok' => true,
            'data' => $report,
        ]);
    }
}
