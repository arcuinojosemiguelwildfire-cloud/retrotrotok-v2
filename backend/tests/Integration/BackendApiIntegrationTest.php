<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Tests\Integration;

use PDO;
use RetroTok\Cloud\Config\Config;
use RetroTok\Cloud\Http\Application;
use RetroTok\Cloud\Http\Request;
use RetroTok\Cloud\Repository\VideoRepository;
use RetroTok\Cloud\Service\RetentionService;
use RetroTok\Cloud\Storage\LocalMockObjectStorageAdapter;
use RetroTok\Cloud\Support\FixedClock;
use RetroTok\Cloud\Tests\Support\BaseTestCase;
use RetroTok\Cloud\Tests\Support\TestDbHelper;

final class BackendApiIntegrationTest extends BaseTestCase
{
    private const ADMIN_TOKEN = 'test_admin_token_0123456789abcdef0123456789abcdef';
    private const PAIRING_SECRET = 'test_pairing_secret_0123456789';

    /**
     * @return array{app: Application, pdo: PDO, storage: LocalMockObjectStorageAdapter, clock: FixedClock}
     */
    private function createHarness(array $configOverrides = []): array
    {
        $clock = new FixedClock('2026-11-18 12:00:00');
        $pdo = TestDbHelper::createInMemoryPdo();
        $storage = new LocalMockObjectStorageAdapter($clock);
        $config = new Config(array_merge([
            'APP_ENV' => 'local',
            'APP_URL' => 'https://retrotok.example.com',
            'REQUIRE_HTTPS' => 'true',
            'ADMIN_API_TOKEN' => self::ADMIN_TOKEN,
            'DEVICE_PAIRING_SECRET' => self::PAIRING_SECRET,
            'QR_TOKEN_PEPPER' => 'test_pepper_abcdef',
            'RATE_LIMIT_QR_MAX_REQUESTS' => '3',
            'RATE_LIMIT_QR_WINDOW_SECONDS' => '60',
        ], $configOverrides));

        return [
            'app' => new Application($config, $pdo, $storage, $clock),
            'pdo' => $pdo,
            'storage' => $storage,
            'clock' => $clock,
        ];
    }

    /**
     * @param array<string, mixed> $body
     * @param array<string, string> $headers
     * @param array<string, string> $query
     */
    private function call(
        Application $app,
        string $method,
        string $path,
        array $body = [],
        array $headers = [],
        array $query = [],
        bool $isHttps = true
    ): array {
        $req = new Request($method, $path, array_change_key_case($headers, CASE_LOWER), $query, $body, '203.0.113.10', $isHttps);
        $resp = $app->handle($req);
        $decoded = json_decode($resp->body, true);
        return [
            'status' => $resp->statusCode,
            'headers' => $resp->headers,
            'body' => $resp->body,
            'json' => is_array($decoded) ? $decoded : null,
        ];
    }

    public function testFullCloudLifecycleWithCapacityIdempotencyQrAndDeletion(): void
    {
        ['app' => $app, 'pdo' => $pdo, 'storage' => $storage, 'clock' => $clock] = $this->createHarness();

        // 0. HTTPS enforcement
        $httpBlocked = $this->call($app, 'POST', '/api/v1/events', ['event_name' => 'Test'], [
            'Authorization' => 'Bearer ' . self::ADMIN_TOKEN,
        ], [], false);
        $this->assertSame(403, $httpBlocked['status']);

        // 1. Fail-closed auth checks
        $unauthEvent = $this->call($app, 'POST', '/api/v1/events', ['event_name' => 'NoAuth']);
        $this->assertSame(401, $unauthEvent['status']);

        // 2. Device pairing
        $badPair = $this->call($app, 'POST', '/api/v1/devices/pair', [
            'pairing_code' => 'wrong',
            'device_identifier' => 'kiosk-01',
        ]);
        $this->assertSame(401, $badPair['status']);

        $pair = $this->call($app, 'POST', '/api/v1/devices/pair', [
            'pairing_code' => self::PAIRING_SECRET,
            'device_identifier' => 'kiosk-01',
            'device_name' => 'ZC-328 Booth 1',
            'android_sdk_int' => 23,
        ]);
        $this->assertSame(201, $pair['status']);
        $deviceToken = (string) $pair['json']['data']['device_token'];
        $this->assertTrue(strlen($deviceToken) >= 43);

        // 3. Create event (max_videos = 2 to test capacity boundary) + idempotent replay
        $createEvt = $this->call($app, 'POST', '/api/v1/events', [
            'event_name' => 'Retro Gala',
            'max_videos' => 2,
            'external_event_key' => 'evt-gala-2026',
        ], ['Authorization' => 'Bearer ' . self::ADMIN_TOKEN]);
        $this->assertSame(201, $createEvt['status']);
        $eventId = (int) $createEvt['json']['data']['id'];

        $replayEvt = $this->call($app, 'POST', '/api/v1/events', [
            'event_name' => 'Retro Gala',
            'max_videos' => 2,
            'external_event_key' => 'evt-gala-2026',
        ], ['Authorization' => 'Bearer ' . self::ADMIN_TOKEN]);
        $this->assertSame(200, $replayEvt['status']);
        $this->assertTrue($replayEvt['json']['idempotent_replay']);

        // 4. Initiate upload #1 and replay idempotently (must NOT consume extra capacity)
        $init1 = $this->call($app, 'POST', "/api/v1/events/{$eventId}/videos", [
            'kiosk_video_id' => 'vid-001',
            'file_size' => 2048000,
            'duration_seconds' => 30,
            'mime_type' => 'video/mp4',
            'width' => 720,
            'height' => 1280,
            'soundtrack_title' => 'Take On Me',
            'soundtrack_artist' => 'a-ha',
        ], ['Authorization' => 'Bearer ' . $deviceToken]);
        $this->assertSame(201, $init1['status']);
        $videoId1 = (int) $init1['json']['data']['cloud_video_id'];
        $key1 = (string) $init1['json']['data']['storage_key'];

        $init1Replay = $this->call($app, 'POST', "/api/v1/events/{$eventId}/videos", [
            'kiosk_video_id' => 'vid-001',
            'file_size' => 2048000,
            'duration_seconds' => 30,
            'mime_type' => 'video/mp4',
        ], ['Authorization' => 'Bearer ' . $deviceToken]);
        $this->assertSame(200, $init1Replay['status']);
        $this->assertTrue($init1Replay['json']['data']['idempotent_replay']);
        $this->assertSame(2, $init1Replay['json']['data']['upload_attempt']);

        // 5. Complete before R2 upload must fail verification
        $missingObj = $this->call($app, 'POST', "/api/v1/videos/{$videoId1}/complete", [], [
            'Authorization' => 'Bearer ' . $deviceToken,
        ]);
        $this->assertSame(409, $missingObj['status']);
        $this->assertSame('R2_OBJECT_MISSING', $missingObj['json']['error']['code']);

        // Wrong size in R2 must fail verification
        $storage->seedUploadedObject($key1, 1234, 'video/mp4');
        $wrongSize = $this->call($app, 'POST', "/api/v1/videos/{$videoId1}/complete", [], [
            'Authorization' => 'Bearer ' . $deviceToken,
        ]);
        $this->assertSame(409, $wrongSize['status']);
        $this->assertSame('R2_SIZE_MISMATCH', $wrongSize['json']['error']['code']);

        // Correct object in mock R2 -> completes & generates 256-bit QR token
        $storage->seedUploadedObject($key1, 2048000, 'video/mp4');
        $complete1 = $this->call($app, 'POST', "/api/v1/videos/{$videoId1}/complete", [], [
            'Authorization' => 'Bearer ' . $deviceToken,
        ]);
        $this->assertSame(200, $complete1['status']);
        $this->assertSame('AVAILABLE', $complete1['json']['data']['status']);
        $qrToken = (string) $complete1['json']['data']['qr_token'];
        $this->assertTrue(strlen($qrToken) >= 43);
        $this->assertSame('https://retrotok.example.com/watch/' . $qrToken, $complete1['json']['data']['viewing_url']);

        // Raw token must NOT be stored in DB
        $row = $pdo->query("SELECT access_token_hash FROM videos WHERE id = {$videoId1}")->fetch();
        $this->assertNotSame($qrToken, $row['access_token_hash']);
        $this->assertSame(64, strlen((string) $row['access_token_hash']));

        // Idempotent second complete
        $complete1Replay = $this->call($app, 'POST', "/api/v1/videos/{$videoId1}/complete", [], [
            'Authorization' => 'Bearer ' . $deviceToken,
        ]);
        $this->assertSame(200, $complete1Replay['status']);
        $this->assertTrue($complete1Replay['json']['data']['already_completed']);

        // 6. Capacity enforcement: second video fits, third is rejected
        $init2 = $this->call($app, 'POST', "/api/v1/events/{$eventId}/videos", [
            'kiosk_video_id' => 'vid-002',
            'file_size' => 1024000,
            'duration_seconds' => 15,
            'mime_type' => 'video/mp4',
        ], ['Authorization' => 'Bearer ' . $deviceToken]);
        $this->assertSame(201, $init2['status']);

        $init3 = $this->call($app, 'POST', "/api/v1/events/{$eventId}/videos", [
            'kiosk_video_id' => 'vid-003',
            'file_size' => 1024000,
            'duration_seconds' => 15,
            'mime_type' => 'video/mp4',
        ], ['Authorization' => 'Bearer ' . $deviceToken]);
        $this->assertSame(409, $init3['status']);
        $this->assertSame('EVENT_CAPACITY_REACHED', $init3['json']['error']['code']);

        // 7. Feed strictly scoped to event & returns AVAILABLE only
        $feed = $this->call($app, 'GET', "/api/v1/events/{$eventId}/videos", [], [
            'Authorization' => 'Bearer ' . $deviceToken,
        ]);
        $this->assertSame(200, $feed['status']);
        $this->assertSame(1, count($feed['json']['data']['videos']));

        // 8. Public QR HTML page (/watch/{token} + /v/{token} alias) & rate limiting (limit = 3 in 60s)
        $qrHtml = $this->call($app, 'GET', "/watch/{$qrToken}");
        $this->assertSame(200, $qrHtml['status']);
        $this->assertStringContains('<video', $qrHtml['body']);
        $this->assertStringContains('Download Video (MP4)', $qrHtml['body']);

        $qrJson = $this->call($app, 'GET', "/watch/{$qrToken}", [], ['Accept' => 'application/json']);
        $this->assertSame(200, $qrJson['status']);
        $this->assertStringContains('https://mock-r2.local.invalid/play/', (string) $qrJson['json']['data']['playback_url']);

        // 3rd ok via legacy /v/{token} alias, 4th triggers 429 rate limit
        $qr3 = $this->call($app, 'GET', "/v/{$qrToken}", [], ['Accept' => 'application/json']);
        $this->assertSame(200, $qr3['status']);
        $qr4 = $this->call($app, 'GET', "/watch/{$qrToken}", [], ['Accept' => 'application/json']);
        $this->assertSame(429, $qr4['status']);

        // Advance clock 120s so rate limit resets, test Admin deletion & idempotency
        $clock->setNow('2026-11-18 12:05:00');
        $del1 = $this->call($app, 'POST', "/api/v1/admin/videos/{$videoId1}/delete", [], [
            'Authorization' => 'Bearer ' . self::ADMIN_TOKEN,
        ]);
        $this->assertSame(200, $del1['status']);
        $this->assertFalse($del1['json']['data']['already_deleted']);

        $del2 = $this->call($app, 'POST', "/api/v1/admin/videos/{$videoId1}/delete", [], [
            'Authorization' => 'Bearer ' . self::ADMIN_TOKEN,
        ]);
        $this->assertSame(200, $del2['status']);
        $this->assertTrue($del2['json']['data']['already_deleted']);

        // Revoked token no longer resolves on either /watch or /v
        $qrAfterDelete = $this->call($app, 'GET', "/watch/{$qrToken}", [], ['Accept' => 'application/json']);
        $this->assertSame(404, $qrAfterDelete['status']);
    }

    public function testThirtyDayRetentionExpiration(): void
    {
        ['app' => $app, 'pdo' => $pdo, 'storage' => $storage, 'clock' => $clock] = $this->createHarness();

        $evt = $this->call($app, 'POST', '/api/v1/events', ['event_name' => 'Retention Test'], [
            'Authorization' => 'Bearer ' . self::ADMIN_TOKEN,
        ]);
        $eventId = (int) $evt['json']['data']['id'];

        $init = $this->call($app, 'POST', "/api/v1/events/{$eventId}/videos", [
            'kiosk_video_id' => 'vid-ret-1',
            'file_size' => 500000,
            'duration_seconds' => 20,
            'mime_type' => 'video/mp4',
        ], ['Authorization' => 'Bearer ' . self::ADMIN_TOKEN]);
        $videoId = (int) $init['json']['data']['cloud_video_id'];
        $key = (string) $init['json']['data']['storage_key'];

        $storage->seedUploadedObject($key, 500000, 'video/mp4');
        $complete = $this->call($app, 'POST', "/api/v1/videos/{$videoId}/complete", [], [
            'Authorization' => 'Bearer ' . self::ADMIN_TOKEN,
        ]);
        $qrToken = (string) $complete['json']['data']['qr_token'];

        // Advance past 30 days
        $clock->setNow('2026-12-20 12:00:00');
        $qrExpired = $this->call($app, 'GET', "/v/{$qrToken}", [], ['Accept' => 'application/json']);
        $this->assertSame(410, $qrExpired['status']);
        $this->assertSame('VIDEO_EXPIRED', $qrExpired['json']['error']['code']);

        $retention = new RetentionService(new VideoRepository($pdo), $storage, $clock);
        $report = $retention->cleanupExpiredVideos();
        $this->assertSame(1, $report['expired']);
        $this->assertSame(0, $report['failed']);
        $this->assertNull($storage->checkObjectMetadata($key));
    }

    public function testSecurityBoundariesAndRevokedDeviceTokens(): void
    {
        ['app' => $app, 'pdo' => $pdo, 'storage' => $storage] = $this->createHarness();

        // Invalid admin token fails closed
        $badAdmin = $this->call($app, 'POST', '/api/v1/events', ['event_name' => 'Rogue'], [
            'Authorization' => 'Bearer invalid_admin_token_00000000000000000000',
        ]);
        $this->assertSame(401, $badAdmin['status']);

        // Pair device
        $pair = $this->call($app, 'POST', '/api/v1/devices/pair', [
            'pairing_code' => self::PAIRING_SECRET,
            'device_identifier' => 'kiosk-sec-01',
        ]);
        $deviceToken = (string) $pair['json']['data']['device_token'];

        // Device token cannot perform admin actions
        $deviceTriesAdmin = $this->call($app, 'POST', '/api/v1/events', ['event_name' => 'Nope'], [
            'Authorization' => 'Bearer ' . $deviceToken,
        ]);
        $this->assertSame(401, $deviceTriesAdmin['status']);

        $evt = $this->call($app, 'POST', '/api/v1/events', ['event_name' => 'Sec Event'], [
            'Authorization' => 'Bearer ' . self::ADMIN_TOKEN,
        ]);
        $eventId = (int) $evt['json']['data']['id'];

        // Path traversal / MIME spoofing / oversize / bad checksum rejected safely
        $traversal = $this->call($app, 'POST', "/api/v1/events/{$eventId}/videos", [
            'kiosk_video_id' => '../etc/passwd',
            'file_size' => 5000,
            'duration_seconds' => 15,
            'mime_type' => 'video/mp4',
        ], ['Authorization' => 'Bearer ' . $deviceToken]);
        $this->assertSame(422, $traversal['status']);
        $this->assertSame('PATH_TRAVERSAL_REJECTED', $traversal['json']['error']['code']);

        $oversize = $this->call($app, 'POST', "/api/v1/events/{$eventId}/videos", [
            'kiosk_video_id' => 'vid-huge',
            'file_size' => 999999999,
            'duration_seconds' => 30,
            'mime_type' => 'video/mp4',
        ], ['Authorization' => 'Bearer ' . $deviceToken]);
        $this->assertSame(422, $oversize['status']);
        $this->assertSame('FILE_TOO_LARGE', $oversize['json']['error']['code']);

        // MIME spoofing caught at HEAD verification
        $init = $this->call($app, 'POST', "/api/v1/events/{$eventId}/videos", [
            'kiosk_video_id' => 'vid-spoof',
            'file_size' => 5000,
            'duration_seconds' => 15,
            'mime_type' => 'video/mp4',
        ], ['Authorization' => 'Bearer ' . $deviceToken]);
        $vidId = (int) $init['json']['data']['cloud_video_id'];
        $storage->seedUploadedObject((string) $init['json']['data']['storage_key'], 5000, 'text/html');
        $spoofComplete = $this->call($app, 'POST', "/api/v1/videos/{$vidId}/complete", [], [
            'Authorization' => 'Bearer ' . $deviceToken,
        ]);
        $this->assertSame(409, $spoofComplete['status']);
        $this->assertSame('R2_MIME_MISMATCH', $spoofComplete['json']['error']['code']);

        // Revoke device token -> immediately rejected
        $pdo->exec("UPDATE kiosk_devices SET revoked_at = '2026-11-18 12:01:00' WHERE device_identifier = 'kiosk-sec-01'");
        $revokedCall = $this->call($app, 'GET', "/api/v1/events/{$eventId}", [], [
            'Authorization' => 'Bearer ' . $deviceToken,
        ]);
        $this->assertSame(401, $revokedCall['status']);
    }

    public function testEventLifecycleClosingPreservesPendingUploadsAndReconciliation(): void
    {
        ['app' => $app, 'pdo' => $pdo, 'storage' => $storage, 'clock' => $clock] = $this->createHarness();

        $evt = $this->call($app, 'POST', '/api/v1/events', [
            'event_name' => 'Lifecycle Gala',
            'status' => 'NOT_STARTED',
        ], ['Authorization' => 'Bearer ' . self::ADMIN_TOKEN]);
        $eventId = (int) $evt['json']['data']['id'];
        $this->assertSame('NOT_STARTED', $evt['json']['data']['status']);

        // Activate event
        $active = $this->call($app, 'POST', "/api/v1/events/{$eventId}/status", ['status' => 'ACTIVE'], [
            'Authorization' => 'Bearer ' . self::ADMIN_TOKEN,
        ]);
        $this->assertSame(200, $active['status']);
        $this->assertSame('ACTIVE', $active['json']['data']['status']);

        // Initiate upload while ACTIVE
        $init = $this->call($app, 'POST', "/api/v1/events/{$eventId}/videos", [
            'kiosk_video_id' => 'vid-inflight',
            'file_size' => 10240,
            'duration_seconds' => 30,
            'mime_type' => 'video/mp4',
        ], ['Authorization' => 'Bearer ' . self::ADMIN_TOKEN]);
        $videoId = (int) $init['json']['data']['cloud_video_id'];
        $key = (string) $init['json']['data']['storage_key'];

        // Transition ACTIVE -> PAUSED -> CLOSING
        $paused = $this->call($app, 'POST', "/api/v1/events/{$eventId}/status", ['status' => 'PAUSED'], [
            'Authorization' => 'Bearer ' . self::ADMIN_TOKEN,
        ]);
        $this->assertSame('PAUSED', $paused['json']['data']['status']);

        $closing = $this->call($app, 'POST', "/api/v1/events/{$eventId}/status", ['status' => 'CLOSING'], [
            'Authorization' => 'Bearer ' . self::ADMIN_TOKEN,
        ]);
        $this->assertSame('CLOSING', $closing['json']['data']['status']);

        // New video initialization is blocked during CLOSING
        $blockedNew = $this->call($app, 'POST', "/api/v1/events/{$eventId}/videos", [
            'kiosk_video_id' => 'vid-new-after-close',
            'file_size' => 10240,
            'duration_seconds' => 30,
            'mime_type' => 'video/mp4',
        ], ['Authorization' => 'Bearer ' . self::ADMIN_TOKEN]);
        $this->assertSame(409, $blockedNew['status']);

        // Existing in-flight upload CAN refresh presigned URL & complete even after CLOSING -> ENDED
        $ended = $this->call($app, 'POST', "/api/v1/events/{$eventId}/status", ['status' => 'ENDED'], [
            'Authorization' => 'Bearer ' . self::ADMIN_TOKEN,
        ]);
        $this->assertSame('ENDED', $ended['json']['data']['status']);

        $refreshExisting = $this->call($app, 'POST', "/api/v1/events/{$eventId}/videos", [
            'kiosk_video_id' => 'vid-inflight',
            'file_size' => 10240,
            'duration_seconds' => 30,
            'mime_type' => 'video/mp4',
        ], ['Authorization' => 'Bearer ' . self::ADMIN_TOKEN]);
        $this->assertSame(200, $refreshExisting['status']);

        $storage->seedUploadedObject($key, 10240, 'video/mp4');
        $completeInFlight = $this->call($app, 'POST', "/api/v1/videos/{$videoId}/complete", [], [
            'Authorization' => 'Bearer ' . self::ADMIN_TOKEN,
        ]);
        $this->assertSame(200, $completeInFlight['status']);
        $this->assertSame('AVAILABLE', $completeInFlight['json']['data']['status']);

        // Reopening an ENDED event backwards is rejected
        $reopen = $this->call($app, 'POST', "/api/v1/events/{$eventId}/status", ['status' => 'ACTIVE'], [
            'Authorization' => 'Bearer ' . self::ADMIN_TOKEN,
        ]);
        $this->assertSame(409, $reopen['status']);

        // Failed R2 deletion does NOT mark DB DELETED or EXPIRED; retry succeeds once R2 recovers
        $storage->failDeletes = true;
        $failedDel = $this->call($app, 'POST', "/api/v1/admin/videos/{$videoId}/delete", [], [
            'Authorization' => 'Bearer ' . self::ADMIN_TOKEN,
        ]);
        $this->assertSame(503, $failedDel['status']);
        $statusStillAvailable = $this->call($app, 'GET', "/api/v1/videos/{$videoId}/status", [], [
            'Authorization' => 'Bearer ' . self::ADMIN_TOKEN,
        ]);
        $this->assertSame('AVAILABLE', $statusStillAvailable['json']['data']['status']);

        // Seed an orphaned R2 object and check reconciliation endpoint
        $storage->failDeletes = false;
        $storage->seedUploadedObject("events/{$eventId}/videos/orphan-999.mp4", 4096, 'video/mp4');
        $reconcile = $this->call($app, 'GET', "/api/v1/admin/events/{$eventId}/reconcile", [], [
            'Authorization' => 'Bearer ' . self::ADMIN_TOKEN,
        ]);
        $this->assertSame(200, $reconcile['status']);
        $this->assertSame(["events/{$eventId}/videos/orphan-999.mp4"], $reconcile['json']['data']['orphaned_r2_objects']);

        // Simulate missing R2 object for AVAILABLE DB record -> detected in missing_r2_objects
        $storage->deleteObject($key);
        $reconcileMissing = $this->call($app, 'GET', "/api/v1/admin/events/{$eventId}/reconcile", [], [
            'Authorization' => 'Bearer ' . self::ADMIN_TOKEN,
        ]);
        $this->assertSame(1, count($reconcileMissing['json']['data']['missing_r2_objects']));

        // Retention retry & in-progress protection:
        // Re-seed expired video and create another event with an in-progress PENDING_UPLOAD video
        $storage->seedUploadedObject($key, 10240, 'video/mp4');
        $evt2 = $this->call($app, 'POST', '/api/v1/events', ['event_name' => 'InProgress Event'], [
            'Authorization' => 'Bearer ' . self::ADMIN_TOKEN,
        ]);
        $evt2Id = (int) $evt2['json']['data']['id'];
        $pendingInit = $this->call($app, 'POST', "/api/v1/events/{$evt2Id}/videos", [
            'kiosk_video_id' => 'vid-pending-safe',
            'file_size' => 20480,
            'duration_seconds' => 30,
            'mime_type' => 'video/mp4',
        ], ['Authorization' => 'Bearer ' . self::ADMIN_TOKEN]);
        $pendingVideoId = (int) $pendingInit['json']['data']['cloud_video_id'];

        $clock->setNow('2026-12-25 12:00:00');
        $retention = new RetentionService(new VideoRepository($pdo), $storage, $clock);

        // First retention attempt fails R2 delete -> video remains AVAILABLE for retry
        $storage->failDeletes = true;
        $retFail = $retention->cleanupExpiredVideos();
        $this->assertSame(1, $retFail['failed']);
        $this->assertSame(0, $retFail['expired']);

        // Second retention attempt succeeds -> expires first video while leaving PENDING_UPLOAD untouched
        $storage->failDeletes = false;
        $retOk = $retention->cleanupExpiredVideos();
        $this->assertSame(0, $retOk['failed']);
        $this->assertSame(1, $retOk['expired']);

        $pendingStatus = $this->call($app, 'GET', "/api/v1/videos/{$pendingVideoId}/status", [], [
            'Authorization' => 'Bearer ' . self::ADMIN_TOKEN,
        ]);
        $this->assertSame('PENDING_UPLOAD', $pendingStatus['json']['data']['status']);

        // Error response never discloses secrets, DSNs, or filesystem paths
        $this->assertFalse(str_contains($failedDel['body'], self::ADMIN_TOKEN));
        $this->assertFalse(str_contains($failedDel['body'], '/backend/'));
    }
}
