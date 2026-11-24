<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Controller;

use RetroTok\Cloud\Auth\AuthMiddleware;
use RetroTok\Cloud\Config\Config;
use RetroTok\Cloud\Http\Request;
use RetroTok\Cloud\Http\Response;
use RetroTok\Cloud\Security\RateLimiter;
use RetroTok\Cloud\Service\DeviceService;

final class DeviceController
{
    public function __construct(
        private readonly DeviceService $devices,
        private readonly AuthMiddleware $auth,
        private readonly RateLimiter $rateLimiter,
        private readonly Config $config
    ) {
    }

    public function pair(Request $request): Response
    {
        $this->rateLimiter->assertWithinLimit(
            scope: 'device_pair',
            subject: $request->clientIp,
            maxRequests: $this->config->rateLimitPairMaxRequests,
            windowSeconds: $this->config->rateLimitPairWindowSeconds
        );

        $data = $this->devices->pairDevice($request->body);
        return Response::json(201, [
            'ok' => true,
            'data' => $data,
        ]);
    }

    public function heartbeat(Request $request): Response
    {
        $ctx = $this->auth->requireDevice($request);
        $data = $this->devices->recordHeartbeat((int) $ctx->deviceId, $request->body);
        return Response::json(200, [
            'ok' => true,
            'data' => $data,
        ]);
    }
}
