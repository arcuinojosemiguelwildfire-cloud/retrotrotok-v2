<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Storage;

use DateTimeImmutable;
use DateTimeZone;
use RetroTok\Cloud\Exception\ValidationException;

/**
 * Pure-PHP AWS Signature Version 4 signer compatible with Cloudflare R2 S3 API.
 * Works on ordinary Hostinger PHP 8.x hosting even without Composer dependencies.
 */
final class SigV4Signer
{
    public const MIN_PRESIGN_TTL_SECONDS = 1;
    public const MAX_PRESIGN_TTL_SECONDS = 604800;

    /**
     * @param array<string, string> $extraQueryParams
     * @param array<string, string> $signedHeaders
     */
    public function presignUrl(
        string $method,
        string $endpoint,
        string $bucket,
        string $objectKey,
        string $accessKeyId,
        string $secretAccessKey,
        string $region,
        bool $pathStyle,
        int $ttlSeconds,
        DateTimeImmutable $now,
        array $extraQueryParams = [],
        array $signedHeaders = []
    ): string {
        if ($ttlSeconds < self::MIN_PRESIGN_TTL_SECONDS || $ttlSeconds > self::MAX_PRESIGN_TTL_SECONDS) {
            throw new ValidationException(
                'Presigned URL TTL must be between 1 and 604800 seconds.',
                'INVALID_PRESIGN_TTL'
            );
        }

        $httpMethod = $this->normalizeMethod($method);
        $this->assertCredentialsAndBucket($bucket, $accessKeyId, $secretAccessKey, $region);
        [$scheme, $requestHost, $canonicalUri] = $this->resolveTarget($endpoint, $bucket, $objectKey, $pathStyle);

        $utcNow = $now->setTimezone(new DateTimeZone('UTC'));
        $amzDate = $utcNow->format('Ymd\THis\Z');
        $dateStamp = $utcNow->format('Ymd');
        $credentialScope = "{$dateStamp}/{$region}/s3/aws4_request";

        [$canonicalHeadersBlock, $signedHeadersList] = $this->buildCanonicalHeaders($requestHost, $signedHeaders);

        // Caller extraQueryParams cannot override SigV4 control parameters or inject X-Amz-Signature.
        $filteredExtra = [];
        foreach ($extraQueryParams as $k => $v) {
            $keyStr = (string) $k;
            if (in_array($keyStr, [
                'X-Amz-Algorithm',
                'X-Amz-Credential',
                'X-Amz-Date',
                'X-Amz-Expires',
                'X-Amz-SignedHeaders',
                'X-Amz-Signature',
            ], true)) {
                continue;
            }
            $filteredExtra[$keyStr] = (string) $v;
        }

        $queryParams = array_merge($filteredExtra, [
            'X-Amz-Algorithm' => 'AWS4-HMAC-SHA256',
            'X-Amz-Credential' => $accessKeyId . '/' . $credentialScope,
            'X-Amz-Date' => $amzDate,
            'X-Amz-Expires' => (string) $ttlSeconds,
            'X-Amz-SignedHeaders' => $signedHeadersList,
        ]);

        $canonicalQuery = $this->buildCanonicalQueryString($queryParams);

        // AWS SigV4 CanonicalRequest:
        // Method \n CanonicalURI \n CanonicalQueryString \n CanonicalHeadersBlock (trailing \n) \n SignedHeaders \n HashedPayload
        // Note: CanonicalHeadersBlock already ends with \n, so concatenating "\n" produces the required single blank separator line.
        $canonicalRequest = $httpMethod . "\n"
            . $canonicalUri . "\n"
            . $canonicalQuery . "\n"
            . $canonicalHeadersBlock . "\n"
            . $signedHeadersList . "\n"
            . 'UNSIGNED-PAYLOAD';

        $stringToSign = implode("\n", [
            'AWS4-HMAC-SHA256',
            $amzDate,
            $credentialScope,
            hash('sha256', $canonicalRequest),
        ]);

        $signingKey = $this->deriveSigningKey($secretAccessKey, $dateStamp, $region, 's3');
        $signature = hash_hmac('sha256', $stringToSign, $signingKey);

        $url = sprintf(
            '%s://%s%s?%s&X-Amz-Signature=%s',
            $scheme,
            $requestHost,
            $canonicalUri,
            $canonicalQuery,
            $signature
        );
        self::assertSafeRequestUrl($url);

        return $url;
    }

    /**
     * Builds signed HTTP headers for server-side HEAD / DELETE / GET S3 requests.
     *
     * @param array<string, string> $queryParams
     * @param array<string, string> $extraHeaders
     * @return array<string, string>
     */
    public function signHeaders(
        string $method,
        string $endpoint,
        string $bucket,
        string $objectKey,
        string $accessKeyId,
        string $secretAccessKey,
        string $region,
        bool $pathStyle,
        DateTimeImmutable $now,
        array $queryParams = [],
        array $extraHeaders = []
    ): array {
        $httpMethod = $this->normalizeMethod($method);
        $this->assertCredentialsAndBucket($bucket, $accessKeyId, $secretAccessKey, $region);
        [$scheme, $requestHost, $canonicalUri] = $this->resolveTarget($endpoint, $bucket, $objectKey, $pathStyle);

        $utcNow = $now->setTimezone(new DateTimeZone('UTC'));
        $amzDate = $utcNow->format('Ymd\THis\Z');
        $dateStamp = $utcNow->format('Ymd');
        $payloadHash = hash('sha256', '');
        $credentialScope = "{$dateStamp}/{$region}/s3/aws4_request";

        $headersToSign = array_merge($extraHeaders, [
            'x-amz-content-sha256' => $payloadHash,
            'x-amz-date' => $amzDate,
        ]);
        [$canonicalHeadersBlock, $signedHeadersList] = $this->buildCanonicalHeaders($requestHost, $headersToSign);
        $canonicalQuery = $this->buildCanonicalQueryString($queryParams);

        $canonicalRequest = $httpMethod . "\n"
            . $canonicalUri . "\n"
            . $canonicalQuery . "\n"
            . $canonicalHeadersBlock . "\n"
            . $signedHeadersList . "\n"
            . $payloadHash;

        $stringToSign = implode("\n", [
            'AWS4-HMAC-SHA256',
            $amzDate,
            $credentialScope,
            hash('sha256', $canonicalRequest),
        ]);

        $signingKey = $this->deriveSigningKey($secretAccessKey, $dateStamp, $region, 's3');
        $signature = hash_hmac('sha256', $stringToSign, $signingKey);

        $authorization = sprintf(
            'AWS4-HMAC-SHA256 Credential=%s/%s, SignedHeaders=%s, Signature=%s',
            $accessKeyId,
            $credentialScope,
            $signedHeadersList,
            $signature
        );

        $url = "{$scheme}://{$requestHost}{$canonicalUri}";
        if ($canonicalQuery !== '') {
            $url .= '?' . $canonicalQuery;
        }
        self::assertSafeRequestUrl($url);

        return [
            'url' => $url,
            'Authorization' => $authorization,
            'x-amz-date' => $amzDate,
            'x-amz-content-sha256' => $payloadHash,
            'Host' => $requestHost,
        ];
    }

    private function normalizeMethod(string $method): string
    {
        $upper = strtoupper(trim($method));
        if (preg_match('/^[A-Z]+$/', $upper) !== 1) {
            throw new ValidationException('Invalid HTTP method for SigV4 signing.', 'INVALID_HTTP_METHOD');
        }
        return $upper;
    }

    private function assertCredentialsAndBucket(
        string $bucket,
        string $accessKeyId,
        string $secretAccessKey,
        string $region
    ): void {
        if ($bucket === '' || str_contains($bucket, '/') || preg_match('/[\x00-\x1F\x7F]/', $bucket) === 1) {
            throw new ValidationException('Invalid R2 bucket name.', 'INVALID_BUCKET');
        }
        if ($accessKeyId === '' || $secretAccessKey === '' || $region === '') {
            throw new ValidationException('R2 signing credentials and region must not be empty.', 'INVALID_R2_CREDENTIALS');
        }
    }

    /**
     * Evaluates structural validity of a URL without leaking hostnames, credentials, or query strings.
     *
     * @return array{
     *     scheme_valid: bool,
     *     host_valid: bool,
     *     no_whitespace_or_control_chars: bool,
     *     path_and_query_valid: bool
     * }
     */
    public static function inspectUrlStructure(string $url, bool $requireOriginOnly = false): array
    {
        $trimmed = trim($url);
        $noWhitespaceOrControl = $trimmed !== ''
            && preg_match('/[\s\x00-\x1F\x7F\x80-\xFF]/', $trimmed) !== 1;

        $parsed = $trimmed !== '' ? parse_url($trimmed) : false;
        $schemeValid = is_array($parsed)
            && isset($parsed['scheme'])
            && strtolower((string) $parsed['scheme']) === 'https'
            && substr_count($trimmed, '://') === 1
            && !isset($parsed['user'])
            && !isset($parsed['pass'])
            && !isset($parsed['fragment']);

        $hostValid = false;
        if (is_array($parsed) && isset($parsed['host'])) {
            $host = strtolower((string) $parsed['host']);
            $port = $parsed['port'] ?? null;
            $portValid = $port === null || ((int) $port >= 1 && (int) $port <= 65535);
            if ($portValid && self::isValidHost($host)) {
                $hostValid = true;
            }
        }

        $pathAndQueryValid = false;
        if (is_array($parsed) && !isset($parsed['fragment'])) {
            $path = (string) ($parsed['path'] ?? '');
            if ($requireOriginOnly) {
                $pathAndQueryValid = ($path === '' || $path === '/') && !isset($parsed['query']);
            } else {
                $pathOk = $path === '' || preg_match('#^/[A-Za-z0-9\-._~%!$&\'()*+,;=:@/]*$#', $path) === 1;
                $query = $parsed['query'] ?? null;
                $queryOk = $query === null
                    || preg_match('#^[A-Za-z0-9\-._~%!$&\'()*+,;=:@/?]*$#', (string) $query) === 1;
                $pathAndQueryValid = $pathOk && $queryOk;
            }
        }

        return [
            'scheme_valid' => $schemeValid,
            'host_valid' => $hostValid,
            'no_whitespace_or_control_chars' => $noWhitespaceOrControl,
            'path_and_query_valid' => $pathAndQueryValid,
        ];
    }

    private static function isValidHost(string $host): bool
    {
        if ($host === '' || preg_match('/[\s\x00-\x1F\x7F\x80-\xFF]/', $host) === 1) {
            return false;
        }
        if (str_starts_with($host, '[') && str_ends_with($host, ']')) {
            $ipv6 = substr($host, 1, -1);
            return $ipv6 !== '' && filter_var($ipv6, FILTER_VALIDATE_IP, FILTER_FLAG_IPV6) !== false;
        }
        if (filter_var($host, FILTER_VALIDATE_IP, FILTER_FLAG_IPV4) !== false) {
            return true;
        }
        return strlen($host) <= 253
            && filter_var($host, FILTER_VALIDATE_DOMAIN, FILTER_FLAG_HOSTNAME) !== false;
    }

    private static function assertSafeRequestUrl(string $url): void
    {
        $structure = self::inspectUrlStructure($url, false);
        if (
            !$structure['scheme_valid']
            || !$structure['host_valid']
            || !$structure['no_whitespace_or_control_chars']
            || !$structure['path_and_query_valid']
        ) {
            throw new ValidationException(
                'Constructed R2 request URL failed structural validation.',
                'INVALID_R2_URL'
            );
        }
    }

    /**
     * @return array{0: string, 1: string, 2: string} [scheme, requestHost, canonicalUri]
     */
    private function resolveTarget(string $endpoint, string $bucket, string $objectKey, bool $pathStyle): array
    {
        $trimmed = trim($endpoint);
        if ($trimmed === '' || preg_match('/[\s\x00-\x1F\x7F\x80-\xFF]/', $trimmed) === 1) {
            throw new ValidationException(
                'R2 endpoint must not be empty or contain whitespace or control characters.',
                'INVALID_R2_ENDPOINT'
            );
        }

        $parsed = parse_url($trimmed);
        if (
            !is_array($parsed)
            || !isset($parsed['scheme'], $parsed['host'])
            || strtolower((string) $parsed['scheme']) !== 'https'
            || substr_count($trimmed, '://') !== 1
            || trim((string) $parsed['host']) === ''
            || isset($parsed['user'])
            || isset($parsed['pass'])
            || isset($parsed['query'])
            || isset($parsed['fragment'])
        ) {
            throw new ValidationException(
                'R2 endpoint must be a valid HTTPS origin URL without path, query, or credentials.',
                'INVALID_R2_ENDPOINT'
            );
        }

        $endpointPath = (string) ($parsed['path'] ?? '');
        if ($endpointPath !== '' && $endpointPath !== '/') {
            throw new ValidationException(
                'R2 endpoint must not include a sub-path.',
                'INVALID_R2_ENDPOINT'
            );
        }

        if (isset($parsed['port']) && ((int) $parsed['port'] < 1 || (int) $parsed['port'] > 65535)) {
            throw new ValidationException(
                'R2 endpoint port is out of valid TCP port range.',
                'INVALID_R2_ENDPOINT'
            );
        }

        $scheme = 'https';
        $host = strtolower((string) $parsed['host']);
        if (!self::isValidHost($host)) {
            throw new ValidationException(
                'R2 endpoint host is not a valid hostname or IP address.',
                'INVALID_R2_ENDPOINT'
            );
        }

        $port = isset($parsed['port']) && (int) $parsed['port'] !== 443 ? ':' . (int) $parsed['port'] : '';
        $authority = $host . $port;

        $encodedKey = $this->encodePath($objectKey);
        if ($pathStyle) {
            $requestHost = $authority;
            $canonicalUri = '/' . rawurlencode($bucket) . '/' . $encodedKey;
        } else {
            $virtualHost = strtolower($bucket) . '.' . $host;
            if (!self::isValidHost($virtualHost)) {
                throw new ValidationException(
                    'R2 bucket name is not valid for virtual-hosted addressing.',
                    'INVALID_BUCKET'
                );
            }
            $requestHost = $virtualHost . $port;
            $canonicalUri = '/' . $encodedKey;
        }

        return [$scheme, $requestHost, $canonicalUri];
    }

    /**
     * @param array<string, string> $signedHeaders
     * @return array{0: string, 1: string} [canonicalHeadersBlockEndingWithNewline, semicolonSignedHeadersList]
     */
    private function buildCanonicalHeaders(string $requestHost, array $signedHeaders): array
    {
        $headersToSign = ['host' => $requestHost];
        foreach ($signedHeaders as $name => $val) {
            $normName = strtolower(trim((string) $name));
            if ($normName === '' || preg_match('/^[a-z0-9-]+$/', $normName) !== 1) {
                throw new ValidationException('Invalid header name for SigV4 signing.', 'INVALID_SIGNED_HEADER');
            }
            $valStr = (string) $val;
            if (str_contains($valStr, "\r") || str_contains($valStr, "\n") || str_contains($valStr, "\0")) {
                throw new ValidationException('Header value contains forbidden control characters.', 'INVALID_SIGNED_HEADER');
            }
            $normVal = preg_replace('/\s+/', ' ', trim($valStr)) ?? '';
            $headersToSign[$normName] = $normVal;
        }
        ksort($headersToSign, SORT_STRING);

        $lines = [];
        $names = [];
        foreach ($headersToSign as $name => $val) {
            $lines[] = $name . ':' . $val;
            $names[] = $name;
        }

        return [implode("\n", $lines) . "\n", implode(';', $names)];
    }

    /**
     * @param array<string, string> $queryParams
     */
    private function buildCanonicalQueryString(array $queryParams): string
    {
        if ($queryParams === []) {
            return '';
        }

        $encodedPairs = [];
        foreach ($queryParams as $k => $v) {
            $encodedPairs[rawurlencode((string) $k)] = rawurlencode((string) $v);
        }
        // AWS SigV4 requires lexicographical sorting by URI-encoded parameter name.
        ksort($encodedPairs, SORT_STRING);

        $parts = [];
        foreach ($encodedPairs as $k => $v) {
            $parts[] = $k . '=' . $v;
        }
        return implode('&', $parts);
    }

    private function deriveSigningKey(string $secretKey, string $dateStamp, string $region, string $service): string
    {
        $kDate = hash_hmac('sha256', $dateStamp, 'AWS4' . $secretKey, true);
        $kRegion = hash_hmac('sha256', $region, $kDate, true);
        $kService = hash_hmac('sha256', $service, $kRegion, true);
        return hash_hmac('sha256', 'aws4_request', $kService, true);
    }

    private function encodePath(string $objectKey): string
    {
        if (preg_match('/[\x00-\x1F\x7F]/', $objectKey) === 1) {
            throw new ValidationException('Object key contains control characters.', 'INVALID_OBJECT_KEY');
        }
        $trimmed = ltrim($objectKey, '/');
        if ($trimmed === '') {
            return '';
        }
        $segments = explode('/', $trimmed);
        foreach ($segments as $segment) {
            if ($segment === '..' || $segment === '.') {
                throw new ValidationException('Object key contains unsafe path segment.', 'PATH_TRAVERSAL_REJECTED');
            }
        }
        return implode('/', array_map('rawurlencode', $segments));
    }
}
