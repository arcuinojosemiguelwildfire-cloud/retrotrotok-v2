<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Storage;

use DateTimeInterface;
use DateTimeZone;
use RetroTok\Cloud\Config\Config;
use RetroTok\Cloud\Exception\ServiceUnavailableException;
use RetroTok\Cloud\Exception\StorageNotConfiguredException;
use RetroTok\Cloud\Exception\ValidationException;
use RetroTok\Cloud\Security\StorageKeySanitizer;
use RetroTok\Cloud\Support\ClockInterface;

/**
 * Cloudflare R2 adapter using S3-compatible SigV4 signed requests.
 * Fails closed with StorageNotConfiguredException until real R2 credentials
 * and bucket settings are configured and verified.
 */
final class CloudflareR2Adapter implements ObjectStorageInterface
{
    private const MAX_LIST_PAGES = 1000;

    /**
     * @param (callable(string, string, list<string>): array{status: int, headers?: array<string, string>, body?: string})|null $httpTransport
     */
    public function __construct(
        private readonly Config $config,
        private readonly SigV4Signer $signer,
        private readonly ClockInterface $clock,
        private readonly mixed $httpTransport = null
    ) {
    }

    public function isConfigured(): bool
    {
        return $this->config->isR2Configured();
    }

    public function createUploadAuthorization(
        string $objectKey,
        string $contentType,
        int $contentLengthBytes,
        int $ttlSeconds = 900
    ): UploadAuthorization {
        $this->assertConfigured();
        $safeKey = StorageKeySanitizer::validateObjectKey($objectKey);

        if ($contentLengthBytes <= 0) {
            throw new ValidationException('Expected upload size must be positive.', 'INVALID_FILE_SIZE');
        }
        $normalizedContentType = strtolower(preg_replace('/\s+/', ' ', trim($contentType)) ?? '');
        if ($normalizedContentType === '') {
            throw new ValidationException('Content-Type must not be empty.', 'UNSUPPORTED_MIME_TYPE');
        }
        if ($ttlSeconds < SigV4Signer::MIN_PRESIGN_TTL_SECONDS || $ttlSeconds > SigV4Signer::MAX_PRESIGN_TTL_SECONDS) {
            throw new ValidationException('Upload authorization TTL out of bounds.', 'INVALID_PRESIGN_TTL');
        }

        $nowUtc = $this->clock->now()->setTimezone(new DateTimeZone('UTC'));

        // Both Content-Type and Content-Length are signed so the Android client must send
        // exact fixed-length PUT requests matching the registered metadata.
        $headersToSign = [
            'content-type' => $normalizedContentType,
            'content-length' => (string) $contentLengthBytes,
        ];

        $url = $this->signer->presignUrl(
            method: 'PUT',
            endpoint: $this->config->r2Endpoint,
            bucket: $this->config->r2Bucket,
            objectKey: $safeKey,
            accessKeyId: $this->config->r2AccessKeyId,
            secretAccessKey: $this->config->r2SecretAccessKey,
            region: $this->config->r2Region,
            pathStyle: $this->config->r2PathStyle,
            ttlSeconds: $ttlSeconds,
            now: $nowUtc,
            signedHeaders: $headersToSign
        );

        return new UploadAuthorization(
            objectKey: $safeKey,
            uploadUrl: $url,
            method: 'PUT',
            requiredHeaders: [
                'Content-Type' => $normalizedContentType,
                'Content-Length' => (string) $contentLengthBytes,
            ],
            expiresAtIso: $nowUtc->modify("+{$ttlSeconds} seconds")->format(DateTimeInterface::ATOM),
            expectedSizeBytes: $contentLengthBytes
        );
    }

    public function verifyUploadedObject(
        string $objectKey,
        int $expectedSizeBytes,
        string $expectedMimeType
    ): ObjectVerificationResult {
        $this->assertConfigured();
        $metadata = $this->checkObjectMetadata($objectKey);
        if ($metadata === null) {
            return ObjectVerificationResult::fail(
                'R2_OBJECT_MISSING',
                'Uploaded object does not exist in private storage.'
            );
        }

        if ($metadata->contentLength !== $expectedSizeBytes) {
            return ObjectVerificationResult::fail(
                'R2_SIZE_MISMATCH',
                'Uploaded object size does not match expected file_size.',
                $metadata
            );
        }

        $actualMime = strtolower(trim(explode(';', $metadata->contentType)[0]));
        if ($actualMime !== strtolower($expectedMimeType)) {
            return ObjectVerificationResult::fail(
                'R2_MIME_MISMATCH',
                'Uploaded object Content-Type does not match expected MIME type.',
                $metadata
            );
        }

        return ObjectVerificationResult::ok($metadata);
    }

    public function generatePlaybackUrl(
        string $objectKey,
        int $ttlSeconds = 1800,
        ?string $downloadFilename = null
    ): string {
        $this->assertConfigured();
        $safeKey = StorageKeySanitizer::validateObjectKey($objectKey);
        if ($ttlSeconds < SigV4Signer::MIN_PRESIGN_TTL_SECONDS || $ttlSeconds > SigV4Signer::MAX_PRESIGN_TTL_SECONDS) {
            throw new ValidationException('Playback URL TTL out of bounds.', 'INVALID_PRESIGN_TTL');
        }

        $extraQuery = [];
        if ($downloadFilename !== null && $downloadFilename !== '') {
            $safeName = preg_replace('/[^A-Za-z0-9._-]/', '_', $downloadFilename) ?: 'retrotok-video.mp4';
            $extraQuery['response-content-disposition'] = 'attachment; filename="' . $safeName . '"';
            $extraQuery['response-content-type'] = 'video/mp4';
        }

        return $this->signer->presignUrl(
            method: 'GET',
            endpoint: $this->config->r2Endpoint,
            bucket: $this->config->r2Bucket,
            objectKey: $safeKey,
            accessKeyId: $this->config->r2AccessKeyId,
            secretAccessKey: $this->config->r2SecretAccessKey,
            region: $this->config->r2Region,
            pathStyle: $this->config->r2PathStyle,
            ttlSeconds: $ttlSeconds,
            now: $this->clock->now(),
            extraQueryParams: $extraQuery
        );
    }

    public function deleteObject(string $objectKey): bool
    {
        $this->assertConfigured();
        $safeKey = StorageKeySanitizer::validateObjectKey($objectKey);
        $signed = $this->signer->signHeaders(
            method: 'DELETE',
            endpoint: $this->config->r2Endpoint,
            bucket: $this->config->r2Bucket,
            objectKey: $safeKey,
            accessKeyId: $this->config->r2AccessKeyId,
            secretAccessKey: $this->config->r2SecretAccessKey,
            region: $this->config->r2Region,
            pathStyle: $this->config->r2PathStyle,
            now: $this->clock->now()
        );

        [$statusCode] = $this->executeHttp('DELETE', $signed['url'], [
            'Authorization: ' . $signed['Authorization'],
            'x-amz-date: ' . $signed['x-amz-date'],
            'x-amz-content-sha256: ' . $signed['x-amz-content-sha256'],
        ]);

        // S3 DeleteObject returns 204 or 200; 404 confirms verified absence.
        if (in_array($statusCode, [200, 204, 404], true)) {
            return true;
        }

        throw new ServiceUnavailableException(
            'Unable to delete object from R2 storage.',
            'R2_DELETE_FAILED'
        );
    }

    public function checkObjectMetadata(string $objectKey): ?ObjectMetadata
    {
        $this->assertConfigured();
        $safeKey = StorageKeySanitizer::validateObjectKey($objectKey);
        $signed = $this->signer->signHeaders(
            method: 'HEAD',
            endpoint: $this->config->r2Endpoint,
            bucket: $this->config->r2Bucket,
            objectKey: $safeKey,
            accessKeyId: $this->config->r2AccessKeyId,
            secretAccessKey: $this->config->r2SecretAccessKey,
            region: $this->config->r2Region,
            pathStyle: $this->config->r2PathStyle,
            now: $this->clock->now()
        );

        [$statusCode, $headers] = $this->executeHttp('HEAD', $signed['url'], [
            'Authorization: ' . $signed['Authorization'],
            'x-amz-date: ' . $signed['x-amz-date'],
            'x-amz-content-sha256: ' . $signed['x-amz-content-sha256'],
        ]);

        if ($statusCode === 404) {
            return null;
        }
        if ($statusCode < 200 || $statusCode >= 300) {
            throw new ServiceUnavailableException('Unable to inspect object metadata in R2.', 'R2_HEAD_FAILED');
        }

        return new ObjectMetadata(
            objectKey: $safeKey,
            contentLength: (int) ($headers['content-length'] ?? 0),
            contentType: (string) ($headers['content-type'] ?? 'application/octet-stream'),
            etag: isset($headers['etag']) ? trim($headers['etag'], '"') : null,
            lastModified: $headers['last-modified'] ?? null
        );
    }

    public function listObjectsByPrefix(string $prefix, int $maxKeys = 1000): array
    {
        $this->assertConfigured();
        if (preg_match('#^events/[1-9][0-9]*/$#', $prefix) !== 1) {
            throw new ServiceUnavailableException('Invalid reconciliation prefix.', 'INVALID_RECONCILE_PREFIX');
        }

        $safeMax = max(1, min(1000, $maxKeys));
        $keys = [];
        $continuationToken = null;
        $seenTokens = [];
        $pageCount = 0;

        do {
            $pageCount++;
            if ($pageCount > self::MAX_LIST_PAGES) {
                throw new ServiceUnavailableException(
                    'R2 list pagination exceeded maximum page limit.',
                    'R2_LIST_PAGINATION_LOOP'
                );
            }

            $queryParams = [
                'list-type' => '2',
                'max-keys' => (string) $safeMax,
                'prefix' => $prefix,
            ];
            if ($continuationToken !== null) {
                $queryParams['continuation-token'] = $continuationToken;
            }

            $url = $this->signer->presignUrl(
                method: 'GET',
                endpoint: $this->config->r2Endpoint,
                bucket: $this->config->r2Bucket,
                objectKey: '',
                accessKeyId: $this->config->r2AccessKeyId,
                secretAccessKey: $this->config->r2SecretAccessKey,
                region: $this->config->r2Region,
                pathStyle: $this->config->r2PathStyle,
                ttlSeconds: 60,
                now: $this->clock->now(),
                extraQueryParams: $queryParams
            );

            [$statusCode, , $body] = $this->executeHttp('GET', $url);
            if ($statusCode < 200 || $statusCode >= 300) {
                throw new ServiceUnavailableException('Unable to list event objects in R2.', 'R2_LIST_FAILED');
            }

            $prevUseErrors = libxml_use_internal_errors(true);
            $xml = simplexml_load_string($body);
            libxml_clear_errors();
            libxml_use_internal_errors($prevUseErrors);

            if (!$xml instanceof \SimpleXMLElement) {
                throw new ServiceUnavailableException('Invalid XML response from R2 list operation.', 'R2_LIST_PARSE_ERROR');
            }

            foreach ($xml->Contents ?? [] as $item) {
                $key = (string) ($item->Key ?? '');
                if ($key === '') {
                    continue;
                }
                if (!str_starts_with($key, $prefix)) {
                    throw new ServiceUnavailableException(
                        'R2 list response returned an object outside the requested event prefix.',
                        'R2_LIST_SCOPE_VIOLATION'
                    );
                }
                $keys[] = $key;
            }

            $isTruncated = strtolower(trim((string) ($xml->IsTruncated ?? 'false'))) === 'true';
            if ($isTruncated) {
                $nextToken = (string) ($xml->NextContinuationToken ?? '');
                if ($nextToken === '') {
                    throw new ServiceUnavailableException(
                        'R2 list response was truncated without NextContinuationToken.',
                        'R2_LIST_INCOMPLETE'
                    );
                }
                if (isset($seenTokens[$nextToken])) {
                    throw new ServiceUnavailableException(
                        'R2 list pagination returned a duplicate continuation token.',
                        'R2_LIST_PAGINATION_LOOP'
                    );
                }
                $seenTokens[$nextToken] = true;
                $continuationToken = $nextToken;
            } else {
                $continuationToken = null;
            }
        } while ($isTruncated);

        return $keys;
    }

    private function assertConfigured(): void
    {
        if (!$this->isConfigured()) {
            throw new StorageNotConfiguredException();
        }
    }

    /**
     * @param list<string> $requestHeaders
     * @return array{0: int, 1: array<string, string>, 2: string}
     */
    private function executeHttp(string $method, string $url, array $requestHeaders = []): array
    {
        if ($this->httpTransport !== null) {
            $result = ($this->httpTransport)($method, $url, $requestHeaders);
            $rawHeaders = is_array($result['headers'] ?? null) ? $result['headers'] : [];
            $normalizedHeaders = [];
            foreach ($rawHeaders as $k => $v) {
                $normalizedHeaders[strtolower(trim((string) $k))] = trim((string) $v);
            }
            return [
                (int) ($result['status'] ?? 0),
                $normalizedHeaders,
                (string) ($result['body'] ?? ''),
            ];
        }

        if (!function_exists('curl_init')) {
            throw new ServiceUnavailableException('PHP cURL extension is required for R2 operations.', 'CURL_UNAVAILABLE');
        }

        $urlStructure = SigV4Signer::inspectUrlStructure($url, false);
        if (
            !$urlStructure['scheme_valid']
            || !$urlStructure['host_valid']
            || !$urlStructure['no_whitespace_or_control_chars']
            || !$urlStructure['path_and_query_valid']
        ) {
            throw new ValidationException('Constructed R2 request URL failed structural validation.', 'INVALID_R2_URL');
        }

        $responseHeaders = [];
        $ch = curl_init($url);
        if ($ch === false) {
            throw new ServiceUnavailableException('Failed to initialize HTTP client for R2.', 'R2_CLIENT_ERROR');
        }

        $curlOptions = [
            CURLOPT_CUSTOMREQUEST => $method,
            CURLOPT_NOBODY => $method === 'HEAD',
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_HTTPHEADER => $requestHeaders,
            CURLOPT_TIMEOUT => 15,
            CURLOPT_CONNECTTIMEOUT => 5,
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
            CURLOPT_HEADERFUNCTION => static function ($curl, string $headerLine) use (&$responseHeaders): int {
                $len = strlen($headerLine);
                $parts = explode(':', $headerLine, 2);
                if (count($parts) === 2) {
                    $responseHeaders[strtolower(trim($parts[0]))] = trim($parts[1]);
                }
                return $len;
            },
        ];
        if (defined('CURLOPT_PROTOCOLS_STR')) {
            $curlOptions[CURLOPT_PROTOCOLS_STR] = 'https';
        } elseif (defined('CURLPROTO_HTTPS')) {
            $curlOptions[CURLOPT_PROTOCOLS] = CURLPROTO_HTTPS;
        }
        curl_setopt_array($ch, $curlOptions);

        $ok = curl_exec($ch);
        $statusCode = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
        curl_close($ch);

        if ($ok === false && $statusCode === 0) {
            throw new ServiceUnavailableException('Network error communicating with R2 storage.', 'R2_NETWORK_ERROR');
        }

        return [$statusCode, $responseHeaders, is_string($ok) ? $ok : ''];
    }
}
