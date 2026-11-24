<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Tests\Unit;

use RetroTok\Cloud\Config\Config;
use RetroTok\Cloud\Http\Application;
use RetroTok\Cloud\Http\Request;
use RetroTok\Cloud\Storage\LocalMockObjectStorageAdapter;
use RetroTok\Cloud\Support\FixedClock;
use RetroTok\Cloud\Tests\Support\BaseTestCase;
use RetroTok\Cloud\Tests\Support\TestDbHelper;

final class SubdirectoryDeploymentTest extends BaseTestCase
{
    public function testConfigParsesAppUrlPathAndAppBasePathOverrideAndProxyTrustDefault(): void
    {
        $rootConfig = new Config([
            'APP_URL' => 'https://wildfirexstream.com',
        ]);
        $this->assertSame('', $rootConfig->appBasePath);
        $this->assertFalse($rootConfig->trustCloudflareProxy);

        $subUrlConfig = new Config([
            'APP_URL' => 'https://wildfirexstream.com/retrotok/',
        ]);
        $this->assertSame('/retrotok', $subUrlConfig->appBasePath);

        $overrideSubConfig = new Config([
            'APP_URL' => 'https://wildfirexstream.com/retrotok',
            'APP_BASE_PATH' => 'custom/sub/',
            'TRUST_CLOUDFLARE_PROXY' => 'true',
        ]);
        $this->assertSame('/custom/sub', $overrideSubConfig->appBasePath);
        $this->assertTrue($overrideSubConfig->trustCloudflareProxy);

        $overrideRootConfig = new Config([
            'APP_URL' => 'https://wildfirexstream.com/retrotok',
            'APP_BASE_PATH' => '/',
        ]);
        $this->assertSame('', $overrideRootConfig->appBasePath);
    }

    public function testRequestBasePathStrippingRespectsExactSegmentBoundaries(): void
    {
        $savedServer = $_SERVER;
        $savedGet = $_GET;

        try {
            $_GET = [];

            // Empty base path preserves existing behavior
            $_SERVER = [
                'REQUEST_METHOD' => 'GET',
                'REQUEST_URI' => '/api/v1/health',
                'REMOTE_ADDR' => '198.51.100.10',
                'HTTPS' => 'on',
            ];
            $reqEmpty = Request::fromGlobals();
            $this->assertSame('/api/v1/health', $reqEmpty->path);

            // /retrotok and /retrotok/ normalize to /
            $_SERVER['REQUEST_URI'] = '/retrotok';
            $this->assertSame('/', Request::fromGlobals('/retrotok')->path);

            $_SERVER['REQUEST_URI'] = '/retrotok/';
            $this->assertSame('/', Request::fromGlobals('/retrotok')->path);

            // /retrotok/api/v1/health normalizes to /api/v1/health
            $_SERVER['REQUEST_URI'] = '/retrotok/api/v1/health';
            $this->assertSame('/api/v1/health', Request::fromGlobals('/retrotok')->path);

            // /retrotok/watch/example-token normalizes to /watch/example-token
            $_SERVER['REQUEST_URI'] = '/retrotok/watch/example-token?format=json';
            $this->assertSame('/watch/example-token', Request::fromGlobals('/retrotok')->path);

            // Similar prefix /retrotok-other/... is NOT stripped
            $_SERVER['REQUEST_URI'] = '/retrotok-other/api/v1/health';
            $this->assertSame('/retrotok-other/api/v1/health', Request::fromGlobals('/retrotok')->path);

            $_SERVER['REQUEST_URI'] = '/retrotok2';
            $this->assertSame('/retrotok2', Request::fromGlobals('/retrotok')->path);
        } finally {
            $_SERVER = $savedServer;
            $_GET = $savedGet;
        }
    }

    public function testCloudflareHeaderIsIgnoredByDefaultAndAcceptedWhenEnabled(): void
    {
        $savedServer = $_SERVER;
        $savedGet = $_GET;

        try {
            $_GET = [];
            $_SERVER = [
                'REQUEST_METHOD' => 'GET',
                'REQUEST_URI' => '/retrotok/api/v1/health',
                'REMOTE_ADDR' => '198.51.100.25',
                'HTTP_CF_CONNECTING_IP' => '203.0.113.77',
            ];

            $defaultReq = Request::fromGlobals('/retrotok');
            $this->assertSame('198.51.100.25', $defaultReq->clientIp);

            $disabledReq = Request::fromGlobals('/retrotok', false);
            $this->assertSame('198.51.100.25', $disabledReq->clientIp);

            $trustedReq = Request::fromGlobals('/retrotok', true);
            $this->assertSame('203.0.113.77', $trustedReq->clientIp);

            // Invalid CF IP falls back safely to REMOTE_ADDR
            $_SERVER['HTTP_CF_CONNECTING_IP'] = 'not-an-ip';
            $invalidCfReq = Request::fromGlobals('/retrotok', true);
            $this->assertSame('198.51.100.25', $invalidCfReq->clientIp);
        } finally {
            $_SERVER = $savedServer;
            $_GET = $savedGet;
        }
    }

    public function testSubdirectoryRoutingAndWatchAndAliasRoutesWorkEndToEnd(): void
    {
        $savedServer = $_SERVER;
        $savedGet = $_GET;

        try {
            $_GET = [];
            $clock = new FixedClock('2026-11-18 12:00:00');
            $pdo = TestDbHelper::createInMemoryPdo();
            $storage = new LocalMockObjectStorageAdapter($clock);
            $config = new Config([
                'APP_ENV' => 'production',
                'APP_URL' => 'https://wildfirexstream.com/retrotok',
                'REQUIRE_HTTPS' => 'true',
                'ADMIN_API_TOKEN' => 'test_admin_token_0123456789abcdef0123456789abcdef',
                'DEVICE_PAIRING_SECRET' => 'test_pairing_secret_0123456789',
                'QR_TOKEN_PEPPER' => 'test_pepper_abcdef',
            ]);
            $app = new Application($config, $pdo, $storage, $clock);

            // Health check via Request::fromGlobals with /retrotok base path (even without HTTPS per existing rule)
            $_SERVER = [
                'REQUEST_METHOD' => 'GET',
                'REQUEST_URI' => '/retrotok/api/v1/health',
                'REMOTE_ADDR' => '198.51.100.10',
            ];
            $healthReq = Request::fromGlobals($config->appBasePath, $config->trustCloudflareProxy);
            $this->assertSame('/api/v1/health', $healthReq->path);
            $healthResp = $app->handle($healthReq);
            $this->assertSame(200, $healthResp->statusCode);

            // Similar prefix /retrotok-other returns 404 ENDPOINT_NOT_FOUND rather than stripping prefix
            $_SERVER = [
                'REQUEST_METHOD' => 'GET',
                'REQUEST_URI' => '/retrotok-other/api/v1/health',
                'REMOTE_ADDR' => '198.51.100.10',
                'HTTPS' => 'on',
            ];
            $otherReq = Request::fromGlobals($config->appBasePath, $config->trustCloudflareProxy);
            $this->assertSame('/retrotok-other/api/v1/health', $otherReq->path);
            $this->assertSame(404, $app->handle($otherReq)->statusCode);

            // Both /watch/{token} and /v/{token} resolve cleanly through the router (returning 404 VIDEO_UNAVAILABLE on invalid format)
            $_SERVER['REQUEST_URI'] = '/retrotok/watch/example-token';
            $_SERVER['HTTP_ACCEPT'] = 'application/json';
            $watchReq = Request::fromGlobals($config->appBasePath, $config->trustCloudflareProxy);
            $this->assertSame('/watch/example-token', $watchReq->path);
            $watchResp = $app->handle($watchReq);
            $this->assertSame(404, $watchResp->statusCode);
            $this->assertStringContains('VIDEO_UNAVAILABLE', $watchResp->body);

            $_SERVER['REQUEST_URI'] = '/retrotok/v/example-token';
            $aliasReq = Request::fromGlobals($config->appBasePath, $config->trustCloudflareProxy);
            $this->assertSame('/v/example-token', $aliasReq->path);
            $aliasResp = $app->handle($aliasReq);
            $this->assertSame(404, $aliasResp->statusCode);
            $this->assertStringContains('VIDEO_UNAVAILABLE', $aliasResp->body);
        } finally {
            $_SERVER = $savedServer;
            $_GET = $savedGet;
        }
    }

    public function testPublicEntrypointPrivateCoreFallbackValidationAndDisplayErrors(): void
    {
        $backendRoot = dirname(__DIR__, 2);
        $indexFile = $backendRoot . '/public/index.php';
        $tmpRoot = sys_get_temp_dir() . '/retrotok_boot_test_' . bin2hex(random_bytes(6));

        try {
            // 1. Local dev fallback + display_errors disabled when APP_DEBUG=false
            $localResult = $this->runEntrypointProbe($indexFile, [
                'APP_URL' => 'https://wildfirexstream.com',
                'APP_ENV' => 'production',
                'APP_DEBUG' => 'false',
                'REQUEST_URI' => '/api/v1/health',
                'REQUEST_METHOD' => 'GET',
                'REMOTE_ADDR' => '198.51.100.10',
                'RETROTOK_BACKEND_DIR' => '',
            ], ['display_errors' => '1']);

            $this->assertSame(0, $localResult['exit_code']);
            $this->assertSame('0', $localResult['display_errors']);
            $this->assertTrue((bool) ($localResult['decoded']['ok'] ?? false));
            $this->assertSame('retrotok-cloud-backend', $localResult['decoded']['data']['service'] ?? null);

            // 2. Hostinger private-core fallback (domains/wildfirexstream.com/public_html/retrotok/index.php -> retrotok_app)
            $domainDir = $tmpRoot . '/domains/wildfirexstream.com';
            $publicEntryDir = $domainDir . '/public_html/retrotok';
            $privateCoreDir = $domainDir . '/retrotok_app';
            mkdir($publicEntryDir, 0775, true);
            mkdir($privateCoreDir, 0775, true);
            copy($indexFile, $publicEntryDir . '/index.php');
            symlink($backendRoot . '/src', $privateCoreDir . '/src');
            file_put_contents(
                $privateCoreDir . '/.env',
                "APP_ENV=production\nAPP_DEBUG=false\nAPP_URL=https://wildfirexstream.com/retrotok\nREQUIRE_HTTPS=true\n"
            );

            $hostingerResult = $this->runEntrypointProbe($publicEntryDir . '/index.php', [
                'APP_ENV' => '',
                'APP_DEBUG' => '',
                'APP_URL' => '',
                'APP_BASE_PATH' => '',
                'TRUST_CLOUDFLARE_PROXY' => '',
                'REQUEST_URI' => '/retrotok/api/v1/health',
                'REQUEST_METHOD' => 'GET',
                'REMOTE_ADDR' => '198.51.100.10',
                'RETROTOK_BACKEND_DIR' => '',
            ], ['display_errors' => '1']);

            $this->assertSame(0, $hostingerResult['exit_code']);
            $this->assertSame('0', $hostingerResult['display_errors']);
            $this->assertTrue((bool) ($hostingerResult['decoded']['ok'] ?? false));
            $this->assertSame('retrotok-cloud-backend', $hostingerResult['decoded']['data']['service'] ?? null);

            // 3. Invalid RETROTOK_BACKEND_DIR fails closed cleanly without leaking filesystem paths
            $badDir = $tmpRoot . '/missing_autoloader_dir';
            mkdir($badDir, 0775, true);
            $invalidResult = $this->runEntrypointProbe($publicEntryDir . '/index.php', [
                'APP_URL' => '',
                'APP_BASE_PATH' => '',
                'REQUEST_URI' => '/retrotok/api/v1/health',
                'REQUEST_METHOD' => 'GET',
                'REMOTE_ADDR' => '198.51.100.10',
                'RETROTOK_BACKEND_DIR' => $badDir,
            ], ['display_errors' => '0']);

            $this->assertSame(1, $invalidResult['exit_code']);
            $this->assertFalse((bool) ($invalidResult['decoded']['ok'] ?? true));
            $this->assertSame('INTERNAL_SERVER_ERROR', $invalidResult['decoded']['error']['code'] ?? null);
            $this->assertFalse(str_contains($invalidResult['body'], $badDir));

            // 4. Valid explicit RETROTOK_BACKEND_DIR + APP_DEBUG=true preserves display_errors
            $debugResult = $this->runEntrypointProbe($publicEntryDir . '/index.php', [
                'APP_URL' => 'https://wildfirexstream.com/retrotok',
                'APP_BASE_PATH' => '',
                'REQUEST_URI' => '/retrotok/api/v1/health',
                'REQUEST_METHOD' => 'GET',
                'REMOTE_ADDR' => '198.51.100.10',
                'APP_DEBUG' => 'true',
                'RETROTOK_BACKEND_DIR' => $backendRoot,
            ], ['display_errors' => '1']);

            $this->assertSame(0, $debugResult['exit_code']);
            $this->assertSame('1', $debugResult['display_errors']);
            $this->assertTrue((bool) ($debugResult['decoded']['ok'] ?? false));
        } finally {
            $this->removeTree($tmpRoot);
        }
    }

    public function testSubdirectoryHtaccessTemplatePreservesSecurityAndAuthorizationRules(): void
    {
        $htaccessPath = dirname(__DIR__, 2) . '/public/.htaccess.subdirectory.example';
        $this->assertTrue(is_file($htaccessPath), 'Missing subdirectory .htaccess template');
        $content = (string) file_get_contents($htaccessPath);

        $this->assertStringContains('RewriteEngine On', $content);
        $this->assertStringContains('RewriteBase /retrotok/', $content);
        $this->assertStringContains('E=HTTP_AUTHORIZATION:%{HTTP:Authorization}', $content);
        $this->assertStringContains('RewriteRule (^|/)\\. - [F,L]', $content);
        $this->assertStringContains('RewriteRule ^ index.php [QSA,L]', $content);
        $this->assertStringContains('X-Content-Type-Options "nosniff"', $content);
        $this->assertStringContains('X-Frame-Options "DENY"', $content);
        $this->assertStringContains('Referrer-Policy "no-referrer"', $content);
    }

    /**
     * @param array<string, string> $envVars
     * @param array<string, string> $iniSettings
     * @return array{exit_code: int, body: string, decoded: array<string, mixed>|null, display_errors: string}
     */
    private function runEntrypointProbe(string $entryFile, array $envVars, array $iniSettings): array
    {
        $probeScript = tempnam(sys_get_temp_dir(), 'retrotok_probe_');
        if ($probeScript === false) {
            static::fail('Failed to create temporary probe script.');
        }

        $displayBefore = $iniSettings['display_errors'] ?? '0';
        $envExport = var_export($envVars, true);
        $entryExport = var_export($entryFile, true);
        $marker = '__DISPLAY_ERRORS__=';

        $code = <<<PHP
<?php
declare(strict_types=1);
ini_set('display_errors', '{$displayBefore}');
\$vars = {$envExport};
foreach (\$vars as \$k => \$v) {
    if (\$v === '') {
        putenv(\$k);
        unset(\$_ENV[\$k], \$_SERVER[\$k]);
    } else {
        putenv(\$k . '=' . \$v);
        \$_ENV[\$k] = \$v;
        \$_SERVER[\$k] = \$v;
    }
}
\$_SERVER['REQUEST_METHOD'] = \$vars['REQUEST_METHOD'] ?? 'GET';
\$_SERVER['REQUEST_URI'] = \$vars['REQUEST_URI'] ?? '/';
\$_SERVER['REMOTE_ADDR'] = \$vars['REMOTE_ADDR'] ?? '198.51.100.10';
register_shutdown_function(static function (): void {
    fwrite(STDERR, '{$marker}' . (string) ini_get('display_errors') . "\n");
});
require {$entryExport};
PHP;
        file_put_contents($probeScript, $code);

        try {
            $descriptors = [
                0 => ['pipe', 'r'],
                1 => ['pipe', 'w'],
                2 => ['pipe', 'w'],
            ];
            $proc = proc_open([PHP_BINARY, $probeScript], $descriptors, $pipes);
            if (!is_resource($proc)) {
                static::fail('Failed to spawn PHP probe process.');
            }
            fclose($pipes[0]);
            $stdout = stream_get_contents($pipes[1]) ?: '';
            $stderr = stream_get_contents($pipes[2]) ?: '';
            fclose($pipes[1]);
            fclose($pipes[2]);
            $exitCode = proc_close($proc);

            $displayAfter = '';
            if (preg_match('/__DISPLAY_ERRORS__=([^\r\n]+)/', $stderr, $m) === 1) {
                $displayAfter = trim($m[1]);
            }

            $decoded = json_decode($stdout, true);

            return [
                'exit_code' => $exitCode,
                'body' => $stdout,
                'decoded' => is_array($decoded) ? $decoded : null,
                'display_errors' => $displayAfter,
            ];
        } finally {
            @unlink($probeScript);
        }
    }

    private function removeTree(string $dir): void
    {
        if (!is_dir($dir)) {
            return;
        }
        $items = scandir($dir);
        if ($items === false) {
            return;
        }
        foreach ($items as $item) {
            if ($item === '.' || $item === '..') {
                continue;
            }
            $path = $dir . '/' . $item;
            if (is_link($path) || is_file($path)) {
                @unlink($path);
            } elseif (is_dir($path)) {
                $this->removeTree($path);
            }
        }
        @rmdir($dir);
    }
}
