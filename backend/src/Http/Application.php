<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Http;

use PDO;
use RetroTok\Cloud\Auth\AuthMiddleware;
use RetroTok\Cloud\Config\Config;
use RetroTok\Cloud\Controller\AdminVideoController;
use RetroTok\Cloud\Controller\DeviceController;
use RetroTok\Cloud\Controller\EventController;
use RetroTok\Cloud\Controller\HealthController;
use RetroTok\Cloud\Controller\PublicVideoController;
use RetroTok\Cloud\Controller\VideoController;
use RetroTok\Cloud\Database\ConnectionFactory;
use RetroTok\Cloud\Exception\ForbiddenException;
use RetroTok\Cloud\Exception\HttpException;
use RetroTok\Cloud\Repository\DeviceRepository;
use RetroTok\Cloud\Repository\EventRepository;
use RetroTok\Cloud\Repository\RateLimitRepository;
use RetroTok\Cloud\Repository\VideoAccessLogRepository;
use RetroTok\Cloud\Repository\VideoRepository;
use RetroTok\Cloud\Repository\VideoUploadRepository;
use RetroTok\Cloud\Security\RateLimiter;
use RetroTok\Cloud\Security\TokenService;
use RetroTok\Cloud\Security\UploadValidator;
use RetroTok\Cloud\Service\AdminVideoService;
use RetroTok\Cloud\Service\DeviceService;
use RetroTok\Cloud\Service\EventService;
use RetroTok\Cloud\Service\PublicVideoService;
use RetroTok\Cloud\Service\RetentionService;
use RetroTok\Cloud\Service\VideoUploadService;
use RetroTok\Cloud\Storage\CloudflareR2Adapter;
use RetroTok\Cloud\Storage\ObjectStorageInterface;
use RetroTok\Cloud\Storage\SigV4Signer;
use RetroTok\Cloud\Support\ClockInterface;
use RetroTok\Cloud\Support\SystemClock;
use Throwable;

/**
 * HTTP application composition root and safe exception boundary.
 */
final class Application
{
    private Router $router;

    public function __construct(
        private readonly Config $config,
        private readonly PDO $pdo,
        private readonly ObjectStorageInterface $storage,
        private readonly ClockInterface $clock
    ) {
        $this->router = new Router();
        $this->registerRoutes();
    }

    public static function bootFromEnvironment(Config $config): self
    {
        $clock = new SystemClock();
        $pdo = ConnectionFactory::create($config);
        $storage = new CloudflareR2Adapter($config, new SigV4Signer(), $clock);
        return new self($config, $pdo, $storage, $clock);
    }

    public function handle(Request $request): Response
    {
        try {
            if ($this->config->requireHttps && !$request->isHttps && $request->path !== '/api/v1/health') {
                throw new ForbiddenException('HTTPS is required for all RetroTok API and QR requests.', 'HTTPS_REQUIRED');
            }

            return $this->router->dispatch($request);
        } catch (HttpException $e) {
            return Response::json($e->statusCode, [
                'ok' => false,
                'error' => array_filter([
                    'code' => $e->errorCode,
                    'message' => $e->getMessage(),
                    'details' => $e->details !== [] ? $e->details : null,
                ], static fn ($v) => $v !== null),
            ], $e->headers);
        } catch (Throwable $e) {
            error_log('[RetroTok] Unhandled error: ' . $e->getMessage());
            $msg = $this->config->appDebug ? $e->getMessage() : 'An unexpected internal server error occurred.';
            return Response::json(500, [
                'ok' => false,
                'error' => [
                    'code' => 'INTERNAL_SERVER_ERROR',
                    'message' => $msg,
                ],
            ]);
        }
    }

    private function registerRoutes(): void
    {
        $eventRepo = new EventRepository($this->pdo);
        $deviceRepo = new DeviceRepository($this->pdo);
        $videoRepo = new VideoRepository($this->pdo);
        $uploadRepo = new VideoUploadRepository($this->pdo);
        $accessLogRepo = new VideoAccessLogRepository($this->pdo);
        $rateLimitRepo = new RateLimitRepository($this->pdo);

        $tokenService = TokenService::fromConfig($this->config);
        $rateLimiter = new RateLimiter($rateLimitRepo, $this->clock);
        $validator = new UploadValidator($this->config);
        $authMiddleware = new AuthMiddleware($this->config, $deviceRepo, $tokenService);

        $eventService = new EventService($eventRepo, $this->config, $this->clock);
        $deviceService = new DeviceService($this->config, $deviceRepo, $tokenService, $this->clock);
        $videoService = new VideoUploadService(
            $this->pdo,
            $eventRepo,
            $videoRepo,
            $uploadRepo,
            $validator,
            $tokenService,
            $this->storage,
            $this->config,
            $this->clock
        );
        $publicService = new PublicVideoService(
            $videoRepo,
            $accessLogRepo,
            $tokenService,
            $rateLimiter,
            $this->storage,
            $this->config,
            $this->clock
        );
        $adminService = new AdminVideoService($videoRepo, $this->storage, $this->clock);
        $retentionService = new RetentionService($videoRepo, $this->storage, $this->clock);

        $healthController = new HealthController($this->config, $this->storage);
        $deviceController = new DeviceController($deviceService, $authMiddleware, $rateLimiter, $this->config);
        $eventController = new EventController($eventService, $authMiddleware);
        $videoController = new VideoController($videoService, $authMiddleware);
        $publicController = new PublicVideoController($publicService);
        $adminController = new AdminVideoController($adminService, $retentionService, $authMiddleware);

        $this->router->add('GET', '/api/v1/health', [$healthController, 'check']);

        $this->router->add('POST', '/api/v1/devices/pair', [$deviceController, 'pair']);
        $this->router->add('POST', '/api/v1/devices/heartbeat', [$deviceController, 'heartbeat']);

        $this->router->add('POST', '/api/v1/events', [$eventController, 'create']);
        $this->router->add('GET', '/api/v1/events/{eventId}', [$eventController, 'show']);
        $this->router->add('POST', '/api/v1/events/{eventId}/status', [$eventController, 'updateStatus']);

        $this->router->add('POST', '/api/v1/events/{eventId}/videos', [$videoController, 'initiate']);
        $this->router->add('POST', '/api/v1/videos/{videoId}/complete', [$videoController, 'complete']);
        $this->router->add('GET', '/api/v1/events/{eventId}/videos', [$videoController, 'listByEvent']);
        $this->router->add('GET', '/api/v1/videos/{videoId}/status', [$videoController, 'status']);

        $this->router->add('GET', '/watch/{token}', [$publicController, 'view']);
        $this->router->add('GET', '/v/{token}', [$publicController, 'view']);

        $this->router->add('POST', '/api/v1/admin/videos/{videoId}/delete', [$adminController, 'delete']);
        $this->router->add('GET', '/api/v1/admin/events/{eventId}/reconcile', [$adminController, 'reconcileEvent']);
    }
}
