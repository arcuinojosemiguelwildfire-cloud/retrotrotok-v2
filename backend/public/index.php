<?php

declare(strict_types=1);

use RetroTok\Cloud\Config\Config;
use RetroTok\Cloud\Config\EnvLoader;
use RetroTok\Cloud\Exception\HttpException;
use RetroTok\Cloud\Http\Application;
use RetroTok\Cloud\Http\Request;
use RetroTok\Cloud\Http\Response;
use RetroTok\Cloud\Support\Autoloader;

$resolveBackendDir = static function (): ?string {
    $configured = null;
    if (defined('RETROTOK_BACKEND_DIR')) {
        $value = constant('RETROTOK_BACKEND_DIR');
        if (is_string($value)) {
            $configured = $value;
        }
    } elseif (isset($_SERVER['RETROTOK_BACKEND_DIR']) && is_string($_SERVER['RETROTOK_BACKEND_DIR'])) {
        $configured = $_SERVER['RETROTOK_BACKEND_DIR'];
    } elseif (isset($_SERVER['REDIRECT_RETROTOK_BACKEND_DIR']) && is_string($_SERVER['REDIRECT_RETROTOK_BACKEND_DIR'])) {
        $configured = $_SERVER['REDIRECT_RETROTOK_BACKEND_DIR'];
    } elseif (isset($_ENV['RETROTOK_BACKEND_DIR']) && is_string($_ENV['RETROTOK_BACKEND_DIR'])) {
        $configured = $_ENV['RETROTOK_BACKEND_DIR'];
    } else {
        $envDir = getenv('RETROTOK_BACKEND_DIR');
        if (is_string($envDir)) {
            $configured = $envDir;
        }
    }

    if ($configured !== null) {
        $trimmed = rtrim(trim($configured), "/\\");
        if ($trimmed !== '') {
            if (str_contains($trimmed, "\0")) {
                return null;
            }
            return is_dir($trimmed) && is_file($trimmed . '/src/Support/Autoloader.php') ? $trimmed : null;
        }
    }

    $candidates = [
        dirname(__DIR__),
        dirname(__DIR__, 2) . '/retrotok_app',
    ];
    foreach ($candidates as $candidate) {
        if (is_dir($candidate) && is_file($candidate . '/src/Support/Autoloader.php')) {
            return $candidate;
        }
    }

    return null;
};

$baseDir = $resolveBackendDir();
if ($baseDir === null) {
    error_log('[RetroTok Bootstrap] Invalid or missing backend core directory.');
    http_response_code(500);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode([
        'ok' => false,
        'error' => [
            'code' => 'INTERNAL_SERVER_ERROR',
            'message' => 'Service unavailable.',
        ],
    ], JSON_UNESCAPED_SLASHES);
    exit(1);
}

require_once $baseDir . '/src/Support/Autoloader.php';
Autoloader::register($baseDir);

EnvLoader::load($baseDir . '/.env');
$config = new Config();

if (!$config->appDebug) {
    ini_set('display_errors', '0');
    ini_set('display_startup_errors', '0');
}

try {
    $request = Request::fromGlobals($config->appBasePath, $config->trustCloudflareProxy);
    if ($request->path === '/api/v1/health') {
        // Health endpoint degrades gracefully even before DB credentials are configured.
        $storage = new \RetroTok\Cloud\Storage\CloudflareR2Adapter(
            $config,
            new \RetroTok\Cloud\Storage\SigV4Signer(),
            new \RetroTok\Cloud\Support\SystemClock()
        );
        (new \RetroTok\Cloud\Controller\HealthController($config, $storage))->check($request)->emit();
        exit(0);
    }

    $app = Application::bootFromEnvironment($config);
    $app->handle($request)->emit();
} catch (HttpException $e) {
    Response::json($e->statusCode, [
        'ok' => false,
        'error' => [
            'code' => $e->errorCode,
            'message' => $e->getMessage(),
        ],
    ], $e->headers)->emit();
} catch (Throwable $e) {
    error_log('[RetroTok Bootstrap] ' . $e->getMessage());
    Response::json(500, [
        'ok' => false,
        'error' => [
            'code' => 'INTERNAL_SERVER_ERROR',
            'message' => $config->appDebug ? $e->getMessage() : 'Service unavailable.',
        ],
    ])->emit();
}
