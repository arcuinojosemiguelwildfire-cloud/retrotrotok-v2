<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Tests\Unit;

use DateTimeImmutable;
use DateTimeZone;
use RetroTok\Cloud\Config\Config;
use RetroTok\Cloud\Exception\ServiceUnavailableException;
use RetroTok\Cloud\Exception\StorageNotConfiguredException;
use RetroTok\Cloud\Exception\ValidationException;
use RetroTok\Cloud\Security\StorageKeySanitizer;
use RetroTok\Cloud\Security\TokenService;
use RetroTok\Cloud\Security\UploadValidator;
use RetroTok\Cloud\Storage\CloudflareR2Adapter;
use RetroTok\Cloud\Storage\SigV4Signer;
use RetroTok\Cloud\Support\FixedClock;
use RetroTok\Cloud\Tests\Support\BaseTestCase;

final class SecurityAndR2BoundaryTest extends BaseTestCase
{
    public function testTokenGenerationHasHighEntropyAndConstantTimeHashing(): void
    {
        $service = new TokenService('test-pepper-secret');
        $t1 = $service->generateToken(32);
        $t2 = $service->generateToken(32);

        $this->assertNotSame($t1, $t2);
        $this->assertTrue(strlen($t1) >= 43, '256-bit base64url token must be >= 43 chars');
        $this->assertTrue($service->isValidQrTokenFormat($t1));

        $h1 = $service->hashQrToken($t1);
        $this->assertSame(64, strlen($h1));
        $this->assertTrue($service->verifyConstantTime($h1, $service->hashQrToken($t1)));
        $this->assertFalse($service->verifyConstantTime($h1, $service->hashQrToken($t2)));
    }

    public function testStorageKeySanitizerBlocksTraversalAndEnforcesCanonicalPaths(): void
    {
        $key = StorageKeySanitizer::buildVideoObjectKey(12, 'vid-abc_123');
        $this->assertSame('events/12/videos/vid-abc_123.mp4', $key);

        foreach (['../secret', 'foo/bar', 'foo\\bar', "bad\0id", '%2e%2e'] as $badId) {
            $this->assertThrows(
                ValidationException::class,
                static fn () => StorageKeySanitizer::sanitizeIdentifier($badId)
            );
        }
    }

    public function testUploadValidatorEnforcesSizeMimeDurationAndChecksum(): void
    {
        $config = new Config([
            'MAX_VIDEO_UPLOAD_BYTES' => '50000000',
            'MAX_VIDEO_DURATION_SECONDS' => '65',
            'ALLOWED_VIDEO_MIME_TYPES' => 'video/mp4',
        ]);
        $validator = new UploadValidator($config);

        $valid = $validator->validateInitiatePayload([
            'kiosk_video_id' => 'vid-101',
            'file_size' => 1048576,
            'duration_seconds' => 30,
            'mime_type' => 'video/mp4',
            'width' => 720,
            'height' => 1280,
            'checksum' => str_repeat('a', 64),
        ]);
        $this->assertSame('vid-101', $valid['kiosk_video_id']);

        $this->assertThrows(ValidationException::class, static fn () => $validator->validateInitiatePayload([
            'kiosk_video_id' => 'vid-102',
            'file_size' => 999999999,
            'duration_seconds' => 30,
            'mime_type' => 'video/mp4',
        ]));

        $this->assertThrows(ValidationException::class, static fn () => $validator->validateInitiatePayload([
            'kiosk_video_id' => 'vid-103',
            'file_size' => 50000,
            'duration_seconds' => 30,
            'mime_type' => 'application/x-msdownload',
        ]));

        $this->assertThrows(ValidationException::class, static fn () => $validator->validateInitiatePayload([
            'kiosk_video_id' => 'vid-104',
            'file_size' => 50000,
            'duration_seconds' => 30,
            'mime_type' => 'video/mp4',
            'checksum' => 'not-a-valid-sha256!',
        ]));
    }

    public function testR2AdapterFailsClosedWhenUnconfiguredOrPlaceholder(): void
    {
        $config = new Config([
            'R2_ACCOUNT_ID' => 'REPLACE_WITH_CLOUDFLARE_ACCOUNT_ID',
            'R2_BUCKET' => 'retrotok-videos-private',
            'R2_ACCESS_KEY_ID' => 'REPLACE_WITH_R2_ACCESS_KEY_ID',
            'R2_SECRET_ACCESS_KEY' => 'REPLACE_WITH_R2_SECRET_ACCESS_KEY',
            'R2_ENDPOINT' => 'https://REPLACE_WITH_CLOUDFLARE_ACCOUNT_ID.r2.cloudflarestorage.com',
        ]);
        $adapter = new CloudflareR2Adapter($config, new SigV4Signer(), new FixedClock('2026-11-18 12:00:00'));

        $this->assertFalse($adapter->isConfigured());

        $this->assertThrows(
            StorageNotConfiguredException::class,
            static fn () => $adapter->createUploadAuthorization('events/1/videos/vid-1.mp4', 'video/mp4', 2048, 900)
        );
        $this->assertThrows(
            StorageNotConfiguredException::class,
            static fn () => $adapter->generatePlaybackUrl('events/1/videos/vid-1.mp4', 900)
        );
        $this->assertThrows(
            StorageNotConfiguredException::class,
            static fn () => $adapter->deleteObject('events/1/videos/vid-1.mp4')
        );
    }

    public function testSigV4SignerGeneratesValidPresignedQueryStructureWithoutExposingSecret(): void
    {
        $config = new Config([
            'R2_ACCOUNT_ID' => 'acct123456',
            'R2_BUCKET' => 'retrotok-test-bucket',
            'R2_ACCESS_KEY_ID' => 'test-access-key',
            'R2_SECRET_ACCESS_KEY' => 'super-secret-never-leaked',
            'R2_ENDPOINT' => 'https://acct123456.r2.cloudflarestorage.com',
            'R2_REGION' => 'auto',
            'R2_PATH_STYLE' => 'true',
        ]);
        $adapter = new CloudflareR2Adapter($config, new SigV4Signer(), new FixedClock('2026-11-18 12:00:00'));
        $this->assertTrue($adapter->isConfigured());

        $auth = $adapter->createUploadAuthorization('events/1/videos/vid-1.mp4', ' Video/MP4 ', 4096, 900);
        $this->assertSame([
            'Content-Type' => 'video/mp4',
            'Content-Length' => '4096',
        ], $auth->requiredHeaders);
        $this->assertStringContains('X-Amz-Algorithm=AWS4-HMAC-SHA256', $auth->uploadUrl);
        $this->assertStringContains('X-Amz-Expires=900', $auth->uploadUrl);
        $this->assertStringContains('X-Amz-Date=20261118T120000Z', $auth->uploadUrl);
        $this->assertStringContains('X-Amz-SignedHeaders=content-length%3Bcontent-type%3Bhost', $auth->uploadUrl);
        $this->assertStringContains('X-Amz-Signature=b0f7512bac53285193bbef0d954a10be5229b78e815fb6b0d21d4652fc2ff613', $auth->uploadUrl);
        $this->assertStringContains('/retrotok-test-bucket/events/1/videos/vid-1.mp4', $auth->uploadUrl);
        $this->assertFalse(str_contains($auth->uploadUrl, 'super-secret-never-leaked'));

        $downloadUrl = $adapter->generatePlaybackUrl('events/1/videos/vid-1.mp4', 1800, 'dance clip.mp4');
        $this->assertStringContains('X-Amz-Expires=1800', $downloadUrl);
        $this->assertStringContains('response-content-disposition=', $downloadUrl);
        $this->assertStringContains('X-Amz-Signature=2c784c6fde3cf7fbc0b1a41872547c8a25a2774db15ed9c6b325558286399a3f', $downloadUrl);
    }

    public function testSigV4SignerMatchesOfficialAwsS3PresignedGetTestVector(): void
    {
        $signer = new SigV4Signer();
        $url = $signer->presignUrl(
            method: 'GET',
            endpoint: 'https://s3.amazonaws.com',
            bucket: 'examplebucket',
            objectKey: 'test.txt',
            accessKeyId: 'AKIAIOSFODNN7EXAMPLE',
            secretAccessKey: 'wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY',
            region: 'us-east-1',
            pathStyle: false,
            ttlSeconds: 86400,
            now: new DateTimeImmutable('2013-05-24 00:00:00', new DateTimeZone('UTC'))
        );

        $expectedUrl = 'https://examplebucket.s3.amazonaws.com/test.txt'
            . '?X-Amz-Algorithm=AWS4-HMAC-SHA256'
            . '&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request'
            . '&X-Amz-Date=20130524T000000Z'
            . '&X-Amz-Expires=86400'
            . '&X-Amz-SignedHeaders=host'
            . '&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404';

        $this->assertSame($expectedUrl, $url);
    }

    public function testSigV4SignerProducesExactIndependentSignaturesForPutGetHeadDeleteAndVirtualHostedEncoding(): void
    {
        $signer = new SigV4Signer();
        $utcTime = new DateTimeImmutable('2026-11-18 12:00:00', new DateTimeZone('UTC'));
        $localTime = new DateTimeImmutable('2026-11-18 04:00:00', new DateTimeZone('America/Los_Angeles'));

        // Presigned PUT with signed Content-Type & Content-Length headers
        $putUrl = $signer->presignUrl(
            method: 'PUT',
            endpoint: 'https://acct123456.r2.cloudflarestorage.com:443',
            bucket: 'retrotok-test-bucket',
            objectKey: 'events/1/videos/vid-1.mp4',
            accessKeyId: 'test-access-key',
            secretAccessKey: 'super-secret-never-leaked',
            region: 'auto',
            pathStyle: true,
            ttlSeconds: 900,
            now: $utcTime,
            signedHeaders: [
                'Content-Type' => 'video/mp4',
                'Content-Length' => '4096',
            ]
        );
        $this->assertSame(
            'https://acct123456.r2.cloudflarestorage.com/retrotok-test-bucket/events/1/videos/vid-1.mp4'
            . '?X-Amz-Algorithm=AWS4-HMAC-SHA256'
            . '&X-Amz-Credential=test-access-key%2F20261118%2Fauto%2Fs3%2Faws4_request'
            . '&X-Amz-Date=20261118T120000Z'
            . '&X-Amz-Expires=900'
            . '&X-Amz-SignedHeaders=content-length%3Bcontent-type%3Bhost'
            . '&X-Amz-Signature=b0f7512bac53285193bbef0d954a10be5229b78e815fb6b0d21d4652fc2ff613',
            $putUrl
        );

        // Presigned GET with sorted extra query params & RFC 3986 encoding
        $getUrl = $signer->presignUrl(
            method: 'GET',
            endpoint: 'https://acct123456.r2.cloudflarestorage.com',
            bucket: 'retrotok-test-bucket',
            objectKey: 'events/1/videos/vid-1.mp4',
            accessKeyId: 'test-access-key',
            secretAccessKey: 'super-secret-never-leaked',
            region: 'auto',
            pathStyle: true,
            ttlSeconds: 1800,
            now: $localTime,
            extraQueryParams: [
                'response-content-type' => 'video/mp4',
                'response-content-disposition' => 'attachment; filename="dance_clip.mp4"',
            ]
        );
        $this->assertSame(
            'https://acct123456.r2.cloudflarestorage.com/retrotok-test-bucket/events/1/videos/vid-1.mp4'
            . '?X-Amz-Algorithm=AWS4-HMAC-SHA256'
            . '&X-Amz-Credential=test-access-key%2F20261118%2Fauto%2Fs3%2Faws4_request'
            . '&X-Amz-Date=20261118T120000Z'
            . '&X-Amz-Expires=1800'
            . '&X-Amz-SignedHeaders=host'
            . '&response-content-disposition=attachment%3B%20filename%3D%22dance_clip.mp4%22'
            . '&response-content-type=video%2Fmp4'
            . '&X-Amz-Signature=2c784c6fde3cf7fbc0b1a41872547c8a25a2774db15ed9c6b325558286399a3f',
            $getUrl
        );

        // Signed HEAD
        $head = $signer->signHeaders(
            method: 'HEAD',
            endpoint: 'https://acct123456.r2.cloudflarestorage.com',
            bucket: 'retrotok-test-bucket',
            objectKey: 'events/1/videos/vid-1.mp4',
            accessKeyId: 'test-access-key',
            secretAccessKey: 'super-secret-never-leaked',
            region: 'auto',
            pathStyle: true,
            now: $utcTime
        );
        $this->assertSame(
            'https://acct123456.r2.cloudflarestorage.com/retrotok-test-bucket/events/1/videos/vid-1.mp4',
            $head['url']
        );
        $this->assertSame(
            'AWS4-HMAC-SHA256 Credential=test-access-key/20261118/auto/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=f08abc5ab665b05f80dbc2a7af4dfc766c1ee3879cb99bd55e0cd5f8476e7530',
            $head['Authorization']
        );
        $this->assertSame('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', $head['x-amz-content-sha256']);
        $this->assertSame('20261118T120000Z', $head['x-amz-date']);

        // Signed DELETE (including non-UTC DateTimeImmutable normalized to UTC)
        $delete = $signer->signHeaders(
            method: 'DELETE',
            endpoint: 'https://acct123456.r2.cloudflarestorage.com',
            bucket: 'retrotok-test-bucket',
            objectKey: 'events/1/videos/vid-1.mp4',
            accessKeyId: 'test-access-key',
            secretAccessKey: 'super-secret-never-leaked',
            region: 'auto',
            pathStyle: true,
            now: $localTime
        );
        $this->assertSame(
            'AWS4-HMAC-SHA256 Credential=test-access-key/20261118/auto/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=145a0d820033e28e93d19483f4219384677b58a1815ac4ccdae057935875a912',
            $delete['Authorization']
        );

        // Virtual-hosted addressing + special-character segment encoding
        $vhUrl = $signer->presignUrl(
            method: 'GET',
            endpoint: 'https://acct123456.r2.cloudflarestorage.com',
            bucket: 'retrotok-test-bucket',
            objectKey: 'events/1/videos/my video+1~v2.mp4',
            accessKeyId: 'test-access-key',
            secretAccessKey: 'super-secret-never-leaked',
            region: 'auto',
            pathStyle: false,
            ttlSeconds: 300,
            now: $utcTime
        );
        $this->assertSame(
            'https://retrotok-test-bucket.acct123456.r2.cloudflarestorage.com/events/1/videos/my%20video%2B1~v2.mp4'
            . '?X-Amz-Algorithm=AWS4-HMAC-SHA256'
            . '&X-Amz-Credential=test-access-key%2F20261118%2Fauto%2Fs3%2Faws4_request'
            . '&X-Amz-Date=20261118T120000Z'
            . '&X-Amz-Expires=300'
            . '&X-Amz-SignedHeaders=host'
            . '&X-Amz-Signature=54ec1faf6dcb8ac63fffeedfbd86d60dd420d496f1cdfc0dbaf7a3b2e15dc85b',
            $vhUrl
        );
    }

    public function testSigV4SignerValidatesTtlBoundsEndpointFormatBucketAndObjectKey(): void
    {
        $signer = new SigV4Signer();
        $now = new DateTimeImmutable('2026-11-18 12:00:00', new DateTimeZone('UTC'));

        $this->assertThrows(ValidationException::class, static fn () => $signer->presignUrl(
            'GET', 'https://acct.r2.cloudflarestorage.com', 'bucket', 'events/1/videos/v.mp4',
            'ak', 'sk', 'auto', true, 0, $now
        ));
        $this->assertThrows(ValidationException::class, static fn () => $signer->presignUrl(
            'GET', 'https://acct.r2.cloudflarestorage.com', 'bucket', 'events/1/videos/v.mp4',
            'ak', 'sk', 'auto', true, 604801, $now
        ));
        $this->assertThrows(ValidationException::class, static fn () => $signer->presignUrl(
            'GET', 'http://acct.r2.cloudflarestorage.com', 'bucket', 'events/1/videos/v.mp4',
            'ak', 'sk', 'auto', true, 60, $now
        ));
        $this->assertThrows(ValidationException::class, static fn () => $signer->presignUrl(
            'GET', 'https://acct.r2.cloudflarestorage.com/extra-path', 'bucket', 'events/1/videos/v.mp4',
            'ak', 'sk', 'auto', true, 60, $now
        ));
        $this->assertThrows(ValidationException::class, static fn () => $signer->presignUrl(
            'GET', 'https://acct.r2.cloudflarestorage.com', 'bad/bucket', 'events/1/videos/v.mp4',
            'ak', 'sk', 'auto', true, 60, $now
        ));
        $this->assertThrows(ValidationException::class, static fn () => $signer->presignUrl(
            'GET', 'https://acct.r2.cloudflarestorage.com', 'bucket', 'events/../secret.mp4',
            'ak', 'sk', 'auto', true, 60, $now
        ));

        foreach ([
            'https://<account_id>.r2.cloudflarestorage.com',
            'https://{account_id}.r2.cloudflarestorage.com',
            'https://[not-ipv6].r2.cloudflarestorage.com',
            'https://my account.r2.cloudflarestorage.com',
            "https://acct\t.r2.cloudflarestorage.com",
            "https://acct.r2.cloudflarestorage.com\xC2\xA0",
            'https://"acct".r2.cloudflarestorage.com',
            'https://acct\\.r2.cloudflarestorage.com',
            'https://acct..r2.cloudflarestorage.com',
            'https://acct.r2.cloudflarestorage.com:0',
            'https://acct.r2.cloudflarestorage.com:70000',
        ] as $malformedEndpoint) {
            $this->assertThrows(
                ValidationException::class,
                static fn () => $signer->presignUrl(
                    'GET',
                    $malformedEndpoint,
                    'bucket',
                    'events/1/videos/v.mp4',
                    'ak',
                    'sk',
                    'auto',
                    true,
                    60,
                    $now
                ),
                "Expected malformed endpoint to be rejected: {$malformedEndpoint}"
            );
        }

        // Virtual-hosted mode must reject bucket names that produce invalid DNS hostnames
        $this->assertThrows(ValidationException::class, static fn () => $signer->presignUrl(
            'GET', 'https://acct.r2.cloudflarestorage.com', 'bad bucket', 'events/1/videos/v.mp4',
            'ak', 'sk', 'auto', false, 60, $now
        ));

        $validStructure = SigV4Signer::inspectUrlStructure(
            'https://acct123456.r2.cloudflarestorage.com/retrotok-test-bucket/?list-type=2&max-keys=1',
            false
        );
        $this->assertSame([
            'scheme_valid' => true,
            'host_valid' => true,
            'no_whitespace_or_control_chars' => true,
            'path_and_query_valid' => true,
        ], $validStructure);

        $badHostStructure = SigV4Signer::inspectUrlStructure('https://<acct>.r2.cloudflarestorage.com/bucket/', false);
        $this->assertTrue($badHostStructure['scheme_valid']);
        $this->assertFalse($badHostStructure['host_valid']);
        $this->assertTrue($badHostStructure['no_whitespace_or_control_chars']);
        $this->assertTrue($badHostStructure['path_and_query_valid']);
    }

    public function testEnvLoaderStripsUtf8BomAndInlineCommentsAfterQuotedValues(): void
    {
        $tmpEnv = tempnam(sys_get_temp_dir(), 'retrotok_env_');
        $this->assertTrue(is_string($tmpEnv));
        file_put_contents(
            $tmpEnv,
            "\xEF\xBB\xBFR2_ENDPOINT=\"https://0123456789abcdef0123456789abcdef.r2.cloudflarestorage.com\" # R2 S3 endpoint\n"
            . "R2_BUCKET='retrotok-videos-private' # Private bucket\n"
            . "R2_REGION=auto # R2 region\n"
        );
        try {
            $parsed = \RetroTok\Cloud\Config\EnvLoader::load($tmpEnv, true);
            $this->assertSame(
                'https://0123456789abcdef0123456789abcdef.r2.cloudflarestorage.com',
                $parsed['R2_ENDPOINT'] ?? null
            );
            $this->assertSame('retrotok-videos-private', $parsed['R2_BUCKET'] ?? null);
            $this->assertSame('auto', $parsed['R2_REGION'] ?? null);
        } finally {
            @unlink($tmpEnv);
        }
    }

    public function testListObjectsByPrefixPaginatesAllPagesEncodesContinuationTokenAndFailsClosed(): void
    {
        $config = new Config([
            'R2_ACCOUNT_ID' => 'acct123456',
            'R2_BUCKET' => 'retrotok-test-bucket',
            'R2_ACCESS_KEY_ID' => 'test-access-key',
            'R2_SECRET_ACCESS_KEY' => 'super-secret-never-leaked',
            'R2_ENDPOINT' => 'https://acct123456.r2.cloudflarestorage.com',
            'R2_REGION' => 'auto',
            'R2_PATH_STYLE' => 'true',
        ]);

        $capturedUrls = [];
        $adapter = new CloudflareR2Adapter(
            $config,
            new SigV4Signer(),
            new FixedClock('2026-11-18 12:00:00'),
            static function (string $method, string $url) use (&$capturedUrls): array {
                $capturedUrls[] = $method . ' ' . $url;
                if (count($capturedUrls) === 1) {
                    return [
                        'status' => 200,
                        'body' => '<?xml version="1.0" encoding="UTF-8"?>'
                            . '<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">'
                            . '<IsTruncated>true</IsTruncated>'
                            . '<NextContinuationToken>tok+page2/==</NextContinuationToken>'
                            . '<Contents><Key>events/7/videos/a.mp4</Key></Contents>'
                            . '<Contents><Key>events/7/thumbs/a.jpg</Key></Contents>'
                            . '</ListBucketResult>',
                    ];
                }
                return [
                    'status' => 200,
                    'body' => '<?xml version="1.0" encoding="UTF-8"?>'
                        . '<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">'
                        . '<IsTruncated>false</IsTruncated>'
                        . '<Contents><Key>events/7/videos/b.mp4</Key></Contents>'
                        . '</ListBucketResult>',
                ];
            }
        );

        $keys = $adapter->listObjectsByPrefix('events/7/', 2);
        $this->assertSame([
            'events/7/videos/a.mp4',
            'events/7/thumbs/a.jpg',
            'events/7/videos/b.mp4',
        ], $keys);
        $this->assertSame(2, count($capturedUrls));
        $this->assertStringContains('list-type=2&max-keys=2&prefix=events%2F7%2F&X-Amz-Signature=', $capturedUrls[0]);
        $this->assertStringContains('continuation-token=tok%2Bpage2%2F%3D%3D&list-type=2&max-keys=2&prefix=events%2F7%2F&X-Amz-Signature=', $capturedUrls[1]);

        // Reject invalid event prefix outside events/{id}/
        $this->assertThrows(
            ServiceUnavailableException::class,
            static fn () => $adapter->listObjectsByPrefix('other/7/')
        );

        // Fail closed when IsTruncated=true without NextContinuationToken
        $truncatedMissingToken = new CloudflareR2Adapter(
            $config,
            new SigV4Signer(),
            new FixedClock('2026-11-18 12:00:00'),
            static fn (): array => [
                'status' => 200,
                'body' => '<ListBucketResult><IsTruncated>true</IsTruncated><Contents><Key>events/7/videos/a.mp4</Key></Contents></ListBucketResult>',
            ]
        );
        $this->assertThrows(
            ServiceUnavailableException::class,
            static fn () => $truncatedMissingToken->listObjectsByPrefix('events/7/')
        );

        // Fail closed if R2 returns a key outside the requested event prefix
        $scopeLeakAdapter = new CloudflareR2Adapter(
            $config,
            new SigV4Signer(),
            new FixedClock('2026-11-18 12:00:00'),
            static fn (): array => [
                'status' => 200,
                'body' => '<ListBucketResult><IsTruncated>false</IsTruncated><Contents><Key>events/8/videos/other.mp4</Key></Contents></ListBucketResult>',
            ]
        );
        $this->assertThrows(
            ServiceUnavailableException::class,
            static fn () => $scopeLeakAdapter->listObjectsByPrefix('events/7/')
        );
    }
}
