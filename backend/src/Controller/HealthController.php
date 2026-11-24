<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Controller;

use RetroTok\Cloud\Config\Config;
use RetroTok\Cloud\Http\Request;
use RetroTok\Cloud\Http\Response;
use RetroTok\Cloud\Storage\ObjectStorageInterface;

final class HealthController
{
    public function __construct(
        private readonly Config $config,
        private readonly ObjectStorageInterface $storage
    ) {
    }

    public function check(Request $request): Response
    {
        return Response::json(200, [
            'ok' => true,
            'data' => [
                'service' => 'retrotok-cloud-backend',
                'php_version' => PHP_VERSION,
                'environment' => $this->config->appEnv,
                'database_configured' => $this->config->isDatabaseConfigured(),
                'object_storage_configured' => $this->storage->isConfigured(),
                'admin_auth_configured' => $this->config->hasValidAdminTokenConfigured(),
            ],
        ]);
    }
}
