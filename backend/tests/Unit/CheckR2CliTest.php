<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Tests\Unit;

use RetroTok\Cloud\Config\Config;
use RetroTok\Cloud\Tests\Support\BaseTestCase;

require_once dirname(__DIR__, 2) . '/bin/check_r2.php';

final class CheckR2CliTest extends BaseTestCase
{
    /**
     * @return array<string, string>
     */
    private function fakeR2Env(array $overrides = []): array
    {
        return array_merge([
            'R2_ACCOUNT_ID' => 'accttest123456',
            'R2_BUCKET' => 'retrotok-test-private-bucket',
            'R2_ACCESS_KEY_ID' => 'FAKE_ACCESS_KEY_NEVER_PRINT',
            'R2_SECRET_ACCESS_KEY' => 'FAKE_SECRET_KEY_NEVER_LEAK',
            'R2_ENDPOINT' => 'https://accttest123456.r2.cloudflarestorage.com',
            'R2_REGION' => 'auto',
            'R2_PATH_STYLE' => 'true',
        ], $overrides);
    }

    public function testCliSapiGuardRejectsWebSapisAndAllowsCli(): void
    {
        $this->assertTrue(retrotokIsAllowedCliSapi('cli'));
        $this->assertTrue(retrotokIsAllowedCliSapi('phpdbg'));

        foreach (['fpm-fcgi', 'litespeed', 'apache2handler', 'cgi-fcgi'] as $webSapi) {
            $this->assertFalse(retrotokIsAllowedCliSapi($webSapi), "Expected {$webSapi} to be rejected");
        }
    }

    public function testProbeFailsClosedWithoutNetworkWhenR2IsNotConfigured(): void
    {
        $called = false;
        $config = new Config([
            'R2_ACCOUNT_ID' => 'REPLACE_WITH_CLOUDFLARE_ACCOUNT_ID',
            'R2_BUCKET' => 'retrotok-videos-private',
            'R2_ACCESS_KEY_ID' => 'REPLACE_WITH_R2_ACCESS_KEY_ID',
            'R2_SECRET_ACCESS_KEY' => 'REPLACE_WITH_R2_SECRET_ACCESS_KEY',
            'R2_ENDPOINT' => 'https://REPLACE_WITH_CLOUDFLARE_ACCOUNT_ID.r2.cloudflarestorage.com',
        ]);

        $res = retrotokRunR2ConnectivityProbe($config, false, static function () use (&$called): array {
            $called = true;
            return ['status' => 200, 'body' => ''];
        });

        $this->assertFalse($called);
        $this->assertSame(1, $res['exit_code']);
        $this->assertFalse($res['report']['ok']);
        $this->assertFalse($res['report']['configuration_loaded']);
        $this->assertSame('not_attempted', $res['report']['dns_tls_http']);
        $this->assertSame('OBJECT_STORAGE_NOT_CONFIGURED', $res['report']['error_category']);
    }

    public function testProbeSucceedsOnSignedListObjectsV2WithMaxKeysOneAndRedactsSensitiveData(): void
    {
        $capturedMethod = '';
        $capturedUrl = '';
        $config = new Config($this->fakeR2Env());

        $res = retrotokRunR2ConnectivityProbe(
            $config,
            true,
            static function (string $method, string $url) use (&$capturedMethod, &$capturedUrl): array {
                $capturedMethod = $method;
                $capturedUrl = $url;
                return [
                    'status' => 200,
                    'headers' => ['content-type' => 'application/xml'],
                    'body' => '<?xml version="1.0" encoding="UTF-8"?>'
                        . '<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">'
                        . '<Name>retrotok-test-private-bucket</Name>'
                        . '<Prefix>events/999999999123456/</Prefix>'
                        . '<MaxKeys>1</MaxKeys>'
                        . '<IsTruncated>false</IsTruncated>'
                        . '</ListBucketResult>',
                    'curl_errno' => 0,
                ];
            }
        );

        $this->assertSame('GET', $capturedMethod);
        $this->assertStringContains('list-type=2', $capturedUrl);
        $this->assertStringContains('max-keys=1', $capturedUrl);
        $this->assertStringContains('X-Amz-Algorithm=AWS4-HMAC-SHA256', $capturedUrl);
        $this->assertStringContains('X-Amz-Signature=', $capturedUrl);
        $this->assertStringContains('/retrotok-test-private-bucket/', $capturedUrl);

        $this->assertSame(0, $res['exit_code']);
        $this->assertTrue($res['report']['ok']);
        $this->assertTrue($res['report']['configuration_loaded']);
        $this->assertTrue($res['report']['r2_configured']);
        $this->assertSame([
            'scheme_valid' => true,
            'host_valid' => true,
            'no_whitespace_or_control_chars' => true,
            'path_and_query_valid' => true,
        ], $res['report']['url_structure']);
        $this->assertSame('ok', $res['report']['dns_tls_http']);
        $this->assertSame('succeeded', $res['report']['authentication']);
        $this->assertSame('succeeded', $res['report']['bucket_access']);
        $this->assertSame(200, $res['report']['http_status']);
        $this->assertNull($res['report']['error_category']);

        // Confirm zero secrets, signatures, query strings, account IDs, or bucket names appear in JSON output
        $serialized = (string) json_encode($res['report']);
        foreach ([
            'FAKE_ACCESS_KEY_NEVER_PRINT',
            'FAKE_SECRET_KEY_NEVER_LEAK',
            'accttest123456',
            'retrotok-test-private-bucket',
            'X-Amz-Signature',
            'X-Amz-Credential',
        ] as $forbidden) {
            $this->assertFalse(str_contains($serialized, $forbidden), "Report leaked forbidden token: {$forbidden}");
        }
    }

    public function testProbeClassifiesAuthBucketDnsTlsAndServerFailuresSafely(): void
    {
        $config = new Config($this->fakeR2Env());

        // 1. Authentication failure (403 SignatureDoesNotMatch)
        $authFail = retrotokRunR2ConnectivityProbe($config, true, static fn (): array => [
            'status' => 403,
            'body' => '<?xml version="1.0"?><Error><Code>SignatureDoesNotMatch</Code>'
                . '<Message>The request signature we calculated does not match. Secret=FAKE_SECRET_KEY_NEVER_LEAK</Message></Error>',
            'curl_errno' => 0,
        ]);
        $this->assertSame(1, $authFail['exit_code']);
        $this->assertSame('ok', $authFail['report']['dns_tls_http']);
        $this->assertSame('failed', $authFail['report']['authentication']);
        $this->assertSame('failed', $authFail['report']['bucket_access']);
        $this->assertSame(403, $authFail['report']['http_status']);
        $this->assertSame('R2_AUTH_ERROR:SignatureDoesNotMatch', $authFail['report']['error_category']);
        $this->assertFalse(str_contains((string) json_encode($authFail['report']), 'FAKE_SECRET_KEY_NEVER_LEAK'));

        // 2. Bucket missing (404 NoSuchBucket)
        $bucketMissing = retrotokRunR2ConnectivityProbe($config, true, static fn (): array => [
            'status' => 404,
            'body' => '<?xml version="1.0"?><Error><Code>NoSuchBucket</Code><BucketName>retrotok-test-private-bucket</BucketName></Error>',
            'curl_errno' => 0,
        ]);
        $this->assertSame(1, $bucketMissing['exit_code']);
        $this->assertSame('ok', $bucketMissing['report']['dns_tls_http']);
        $this->assertSame('succeeded', $bucketMissing['report']['authentication']);
        $this->assertSame('failed', $bucketMissing['report']['bucket_access']);
        $this->assertSame(404, $bucketMissing['report']['http_status']);
        $this->assertSame('BUCKET_NOT_FOUND', $bucketMissing['report']['error_category']);

        // 3. DNS resolution failure (curl_errno 6)
        $dnsFail = retrotokRunR2ConnectivityProbe($config, true, static fn (): array => [
            'status' => 0,
            'body' => '',
            'curl_errno' => 6,
            'connect_time' => 0.0,
        ]);
        $this->assertSame(1, $dnsFail['exit_code']);
        $this->assertSame('failed', $dnsFail['report']['dns_tls_http']);
        $this->assertSame('not_verified', $dnsFail['report']['authentication']);
        $this->assertNull($dnsFail['report']['http_status']);
        $this->assertSame(6, $dnsFail['report']['curl_errno']);
        $this->assertSame('DNS_RESOLUTION_FAILED', $dnsFail['report']['error_category']);

        // 4. Connection refused (curl_errno 7)
        $refusedFail = retrotokRunR2ConnectivityProbe($config, true, static fn (): array => [
            'status' => 0,
            'body' => '',
            'curl_errno' => 7,
            'connect_time' => 0.0,
        ]);
        $this->assertSame(1, $refusedFail['exit_code']);
        $this->assertSame(7, $refusedFail['report']['curl_errno']);
        $this->assertSame('CONNECTION_REFUSED', $refusedFail['report']['error_category']);

        // 5. Connection timeout (curl_errno 28 with connect_time == 0.0)
        $connTimeout = retrotokRunR2ConnectivityProbe($config, true, static fn (): array => [
            'status' => 0,
            'body' => '',
            'curl_errno' => 28,
            'connect_time' => 0.0,
        ]);
        $this->assertSame(1, $connTimeout['exit_code']);
        $this->assertSame(28, $connTimeout['report']['curl_errno']);
        $this->assertSame('CONNECTION_TIMEOUT', $connTimeout['report']['error_category']);

        // 6. Overall request timeout (curl_errno 28 with connect_time > 0.0)
        $reqTimeout = retrotokRunR2ConnectivityProbe($config, true, static fn (): array => [
            'status' => 0,
            'body' => '',
            'curl_errno' => 28,
            'connect_time' => 0.125,
        ]);
        $this->assertSame(1, $reqTimeout['exit_code']);
        $this->assertSame(28, $reqTimeout['report']['curl_errno']);
        $this->assertSame('REQUEST_TIMEOUT', $reqTimeout['report']['error_category']);

        // 7. TLS certificate verification failure (curl_errno 60)
        $tlsCertFail = retrotokRunR2ConnectivityProbe($config, true, static fn (): array => [
            'status' => 0,
            'body' => '',
            'curl_errno' => 60,
            'connect_time' => 0.08,
        ]);
        $this->assertSame(1, $tlsCertFail['exit_code']);
        $this->assertSame('failed', $tlsCertFail['report']['dns_tls_http']);
        $this->assertSame(60, $tlsCertFail['report']['curl_errno']);
        $this->assertSame('TLS_CERT_VERIFICATION_FAILED', $tlsCertFail['report']['error_category']);

        // 8. TLS handshake failure (curl_errno 35 / 59)
        $tlsHandshakeFail = retrotokRunR2ConnectivityProbe($config, true, static fn (): array => [
            'status' => 0,
            'body' => '',
            'curl_errno' => 35,
            'connect_time' => 0.05,
        ]);
        $this->assertSame(1, $tlsHandshakeFail['exit_code']);
        $this->assertSame(35, $tlsHandshakeFail['report']['curl_errno']);
        $this->assertSame('TLS_HANDSHAKE_FAILED', $tlsHandshakeFail['report']['error_category']);

        // 9. Connection reset / receive failure (curl_errno 56 / 52 / 55)
        foreach ([56, 52, 55, 18] as $recvErrno) {
            $recvFail = retrotokRunR2ConnectivityProbe($config, true, static fn (): array => [
                'status' => 0,
                'body' => '',
                'curl_errno' => $recvErrno,
                'connect_time' => 0.09,
            ]);
            $this->assertSame(1, $recvFail['exit_code']);
            $this->assertSame($recvErrno, $recvFail['report']['curl_errno']);
            $this->assertSame('CONNECTION_RESET_OR_RECV_FAILED', $recvFail['report']['error_category']);
        }

        // 10. CURLE_URL_MALFORMAT (curl_errno 3) classification fallback
        $urlMalformatFail = retrotokRunR2ConnectivityProbe($config, true, static fn (): array => [
            'status' => 0,
            'body' => '',
            'curl_errno' => 3,
            'connect_time' => 0.0,
        ]);
        $this->assertSame(1, $urlMalformatFail['exit_code']);
        $this->assertSame(3, $urlMalformatFail['report']['curl_errno']);
        $this->assertSame('MALFORMED_URL', $urlMalformatFail['report']['error_category']);

        // 11. Other network error fallback (e.g. curl_errno 97)
        $otherFail = retrotokRunR2ConnectivityProbe($config, true, static fn (): array => [
            'status' => 0,
            'body' => '',
            'curl_errno' => 97,
            'connect_time' => 0.0,
        ]);
        $this->assertSame(1, $otherFail['exit_code']);
        $this->assertSame(97, $otherFail['report']['curl_errno']);
        $this->assertSame('NETWORK_ERROR', $otherFail['report']['error_category']);

        // 12. Never exposes returned object keys even if mock response contains objects
        $objectListNonEmpty = retrotokRunR2ConnectivityProbe(
            $config,
            true,
            static fn (): array => [
                'status' => 200,
                'body' => '<?xml version="1.0"?><ListBucketResult><IsTruncated>false</IsTruncated>'
                    . '<Contents><Key>events/999999999123/videos/secret-clip.mp4</Key></Contents></ListBucketResult>',
                'curl_errno' => 0,
            ],
            'events/999999999123/'
        );
        $this->assertSame(0, $objectListNonEmpty['exit_code']);
        $this->assertSame(0, $objectListNonEmpty['report']['curl_errno']);
        $this->assertFalse(str_contains((string) json_encode($objectListNonEmpty['report']), 'secret-clip.mp4'));
    }

    public function testProbeDiagnosesMalformedEndpointsSafelyBeforeCallingCurlWithoutLeakingValues(): void
    {
        // 1. Angle-bracket placeholder endpoint detected before signing/network
        $placeholderConfig = new Config($this->fakeR2Env([
            'R2_ENDPOINT' => 'https://<account_id>.r2.cloudflarestorage.com',
        ]));
        $called = false;
        $placeholderRes = retrotokRunR2ConnectivityProbe($placeholderConfig, true, static function () use (&$called): array {
            $called = true;
            return ['status' => 200, 'body' => ''];
        });
        $this->assertFalse($called);
        $this->assertFalse($placeholderRes['report']['r2_configured']);
        $this->assertSame('OBJECT_STORAGE_NOT_CONFIGURED', $placeholderRes['report']['error_category']);
        $this->assertFalse($placeholderRes['report']['url_structure']['host_valid']);
        $this->assertFalse(str_contains((string) json_encode($placeholderRes['report']), '<account_id>'));

        // 2. Endpoint with embedded space or control characters fails preflight with safe diagnostic category
        foreach ([
            'https://my account.r2.cloudflarestorage.com' => 'MALFORMED_R2_ENDPOINT:WHITESPACE_OR_CONTROL_CHARS',
            "https://acct\t.r2.cloudflarestorage.com" => 'MALFORMED_R2_ENDPOINT:WHITESPACE_OR_CONTROL_CHARS',
            'https://[not-ipv6].r2.cloudflarestorage.com' => 'MALFORMED_R2_ENDPOINT:INVALID_HOST',
            'https://acct.r2.cloudflarestorage.com:0' => 'MALFORMED_R2_ENDPOINT:INVALID_HOST',
            'https://acct.r2.cloudflarestorage.com/subpath' => 'MALFORMED_R2_ENDPOINT:INVALID_PATH_OR_QUERY',
        ] as $badEndpoint => $expectedCategory) {
            $badConfig = new Config($this->fakeR2Env(['R2_ENDPOINT' => $badEndpoint]));
            $badCalled = false;
            $badRes = retrotokRunR2ConnectivityProbe($badConfig, true, static function () use (&$badCalled): array {
                $badCalled = true;
                return ['status' => 200, 'body' => ''];
            });
            $this->assertFalse($badCalled, "Transport must not run for malformed endpoint: {$badEndpoint}");
            $this->assertSame(1, $badRes['exit_code']);
            $this->assertSame('not_attempted', $badRes['report']['dns_tls_http']);
            $this->assertSame($expectedCategory, $badRes['report']['error_category']);
            $this->assertTrue(is_array($badRes['report']['url_structure']));
            $serialized = (string) json_encode($badRes['report']);
            $this->assertFalse(str_contains($serialized, 'cloudflarestorage.com'));
        }
    }

    public function testDirectCliInvocationFailsCleanlyWithExitCodeOneWhenUnconfigured(): void
    {
        $script = dirname(__DIR__, 2) . '/bin/check_r2.php';
        $descriptors = [
            0 => ['pipe', 'r'],
            1 => ['pipe', 'w'],
            2 => ['pipe', 'w'],
        ];
        $env = array_merge($_ENV, [
            'R2_ACCOUNT_ID' => '',
            'R2_BUCKET' => '',
            'R2_ACCESS_KEY_ID' => '',
            'R2_SECRET_ACCESS_KEY' => '',
            'R2_ENDPOINT' => '',
        ]);
        $proc = proc_open([PHP_BINARY, $script], $descriptors, $pipes, dirname(__DIR__, 2), $env);
        $this->assertTrue(is_resource($proc));
        fclose($pipes[0]);
        $stdout = stream_get_contents($pipes[1]) ?: '';
        fclose($pipes[1]);
        fclose($pipes[2]);
        $exitCode = proc_close($proc);

        $this->assertSame(1, $exitCode);
        $decoded = json_decode($stdout, true);
        $this->assertTrue(is_array($decoded));
        $this->assertFalse((bool) ($decoded['ok'] ?? true));
        $this->assertSame('OBJECT_STORAGE_NOT_CONFIGURED', $decoded['error_category'] ?? null);
    }
}
