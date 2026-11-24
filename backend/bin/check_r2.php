#!/usr/bin/env php
<?php

declare(strict_types=1);

use RetroTok\Cloud\Config\Config;
use RetroTok\Cloud\Config\EnvLoader;
use RetroTok\Cloud\Exception\HttpException;
use RetroTok\Cloud\Exception\StorageNotConfiguredException;
use RetroTok\Cloud\Storage\CloudflareR2Adapter;
use RetroTok\Cloud\Storage\SigV4Signer;
use RetroTok\Cloud\Support\SystemClock;

if (PHP_SAPI !== 'cli' && PHP_SAPI !== 'phpdbg') {
    if (!headers_sent()) {
        http_response_code(403);
        header('Content-Type: text/plain; charset=utf-8');
    }
    fwrite(STDERR, "Forbidden: CLI execution only.\n");
    exit(1);
}

$baseDir = dirname(__DIR__);
require_once $baseDir . '/src/Support/Autoloader.php';
\RetroTok\Cloud\Support\Autoloader::register($baseDir);

if (!function_exists('retrotokIsAllowedCliSapi')) {
    function retrotokIsAllowedCliSapi(string $sapi = PHP_SAPI): bool
    {
        return $sapi === 'cli' || $sapi === 'phpdbg';
    }
}

if (!function_exists('retrotokExtractSafeS3ErrorCode')) {
    function retrotokExtractSafeS3ErrorCode(string $body): ?string
    {
        if (trim($body) === '' || !function_exists('simplexml_load_string')) {
            return null;
        }
        $prevUseErrors = libxml_use_internal_errors(true);
        $xml = simplexml_load_string($body);
        libxml_clear_errors();
        libxml_use_internal_errors($prevUseErrors);

        if (!$xml instanceof SimpleXMLElement) {
            return null;
        }
        $code = trim((string) ($xml->Code ?? ''));
        if ($code === '' || preg_match('/^[A-Za-z0-9_.-]{1,64}$/', $code) !== 1) {
            return null;
        }
        return $code;
    }
}

if (!function_exists('retrotokInspectUrlStructure')) {
    /**
     * @return array{
     *     scheme_valid: bool,
     *     host_valid: bool,
     *     no_whitespace_or_control_chars: bool,
     *     path_and_query_valid: bool
     * }
     */
    function retrotokInspectUrlStructure(string $url, bool $requireOriginOnly = false): array
    {
        return SigV4Signer::inspectUrlStructure($url, $requireOriginOnly);
    }
}

if (!function_exists('retrotokSummarizeUrlStructureError')) {
    /**
     * @param array{
     *     scheme_valid: bool,
     *     host_valid: bool,
     *     no_whitespace_or_control_chars: bool,
     *     path_and_query_valid: bool
     * } $structure
     */
    function retrotokSummarizeUrlStructureError(array $structure, string $prefix = 'MALFORMED_R2_URL'): ?string
    {
        if (!$structure['no_whitespace_or_control_chars']) {
            return $prefix . ':WHITESPACE_OR_CONTROL_CHARS';
        }
        if (!$structure['scheme_valid']) {
            return $prefix . ':INVALID_SCHEME';
        }
        if (!$structure['host_valid']) {
            return $prefix . ':INVALID_HOST';
        }
        if (!$structure['path_and_query_valid']) {
            return $prefix . ':INVALID_PATH_OR_QUERY';
        }
        return null;
    }
}

if (!function_exists('retrotokClassifyCurlError')) {
    function retrotokClassifyCurlError(int $curlErrno, float $connectTimeSeconds = 0.0): string
    {
        $urlMalformatErrno = defined('CURLE_URL_MALFORMAT') ? (int) constant('CURLE_URL_MALFORMAT') : 3;
        if ($curlErrno === $urlMalformatErrno) {
            return 'MALFORMED_URL';
        }
        $dnsErrnos = [
            defined('CURLE_COULDNT_RESOLVE_PROXY') ? (int) constant('CURLE_COULDNT_RESOLVE_PROXY') : 5,
            defined('CURLE_COULDNT_RESOLVE_HOST') ? (int) constant('CURLE_COULDNT_RESOLVE_HOST') : 6,
        ];
        if (in_array($curlErrno, $dnsErrnos, true)) {
            return 'DNS_RESOLUTION_FAILED';
        }

        $connectRefusedErrno = defined('CURLE_COULDNT_CONNECT') ? (int) constant('CURLE_COULDNT_CONNECT') : 7;
        if ($curlErrno === $connectRefusedErrno) {
            return 'CONNECTION_REFUSED';
        }

        $timeoutErrno = defined('CURLE_OPERATION_TIMEDOUT') ? (int) constant('CURLE_OPERATION_TIMEDOUT') : 28;
        if ($curlErrno === $timeoutErrno) {
            return $connectTimeSeconds > 0.0 ? 'REQUEST_TIMEOUT' : 'CONNECTION_TIMEOUT';
        }

        $tlsCertErrnos = [
            defined('CURLE_SSL_CERTPROBLEM') ? (int) constant('CURLE_SSL_CERTPROBLEM') : 58,
            defined('CURLE_SSL_CACERT') ? (int) constant('CURLE_SSL_CACERT') : 60,
            defined('CURLE_PEER_FAILED_VERIFICATION') ? (int) constant('CURLE_PEER_FAILED_VERIFICATION') : 60,
            defined('CURLE_SSL_CACERT_BADFILE') ? (int) constant('CURLE_SSL_CACERT_BADFILE') : 77,
            defined('CURLE_SSL_ISSUER_ERROR') ? (int) constant('CURLE_SSL_ISSUER_ERROR') : 83,
            defined('CURLE_SSL_PINNEDPUBKEYNOTMATCH') ? (int) constant('CURLE_SSL_PINNEDPUBKEYNOTMATCH') : 90,
            defined('CURLE_SSL_INVALIDCERTSTATUS') ? (int) constant('CURLE_SSL_INVALIDCERTSTATUS') : 91,
        ];
        if (in_array($curlErrno, $tlsCertErrnos, true)) {
            return 'TLS_CERT_VERIFICATION_FAILED';
        }

        $tlsHandshakeErrnos = [
            defined('CURLE_SSL_CONNECT_ERROR') ? (int) constant('CURLE_SSL_CONNECT_ERROR') : 35,
            defined('CURLE_SSL_ENGINE_NOTFOUND') ? (int) constant('CURLE_SSL_ENGINE_NOTFOUND') : 53,
            defined('CURLE_SSL_ENGINE_SETFAILED') ? (int) constant('CURLE_SSL_ENGINE_SETFAILED') : 54,
            defined('CURLE_SSL_CIPHER') ? (int) constant('CURLE_SSL_CIPHER') : 59,
            defined('CURLE_SSL_SHUTDOWN_FAILED') ? (int) constant('CURLE_SSL_SHUTDOWN_FAILED') : 80,
        ];
        if (in_array($curlErrno, $tlsHandshakeErrnos, true)) {
            return 'TLS_HANDSHAKE_FAILED';
        }

        $resetRecvErrnos = [
            defined('CURLE_HTTP2') ? (int) constant('CURLE_HTTP2') : 16,
            defined('CURLE_PARTIAL_FILE') ? (int) constant('CURLE_PARTIAL_FILE') : 18,
            defined('CURLE_GOT_NOTHING') ? (int) constant('CURLE_GOT_NOTHING') : 52,
            defined('CURLE_SEND_ERROR') ? (int) constant('CURLE_SEND_ERROR') : 55,
            defined('CURLE_RECV_ERROR') ? (int) constant('CURLE_RECV_ERROR') : 56,
            defined('CURLE_HTTP2_STREAM') ? (int) constant('CURLE_HTTP2_STREAM') : 92,
        ];
        if (in_array($curlErrno, $resetRecvErrnos, true)) {
            return 'CONNECTION_RESET_OR_RECV_FAILED';
        }

        return 'NETWORK_ERROR';
    }
}

if (!function_exists('retrotokRunR2ConnectivityProbe')) {
    /**
     * Executes a read-only, non-destructive signed ListObjectsV2 probe (max-keys=1)
     * using CloudflareR2Adapter and returns strictly sanitized diagnostics plus exit code.
     *
     * @param (callable(string, string, list<string>): array{
     *     status: int,
     *     headers?: array<string, string>,
     *     body?: string,
     *     curl_errno?: int,
     *     connect_time?: float
     * })|null $transport
     * @return array{exit_code: int, report: array<string, mixed>}
     */
    function retrotokRunR2ConnectivityProbe(
        Config $config,
        bool $envFilePresent,
        ?callable $transport = null,
        ?string $probePrefix = null
    ): array {
        $probeState = [
            'method' => null,
            'max_keys' => null,
            'http_status' => null,
            'curl_errno' => null,
            'connect_time' => 0.0,
            'error_code' => null,
            'url_structure' => $config->r2Endpoint !== ''
                ? retrotokInspectUrlStructure($config->r2Endpoint, true)
                : null,
        ];

        $effectiveTransport = $transport ?? static function (string $method, string $url, array $requestHeaders): array {
            if (!function_exists('curl_init')) {
                return [
                    'status' => 0,
                    'headers' => [],
                    'body' => '',
                    'curl_errno' => -1,
                    'connect_time' => 0.0,
                ];
            }

            $structure = retrotokInspectUrlStructure($url, false);
            if (retrotokSummarizeUrlStructureError($structure, 'MALFORMED_R2_URL') !== null) {
                return [
                    'status' => 0,
                    'headers' => [],
                    'body' => '',
                    'curl_errno' => defined('CURLE_URL_MALFORMAT') ? (int) constant('CURLE_URL_MALFORMAT') : 3,
                    'connect_time' => 0.0,
                ];
            }

            $responseHeaders = [];
            $ch = curl_init($url);
            if ($ch === false) {
                return [
                    'status' => 0,
                    'headers' => [],
                    'body' => '',
                    'curl_errno' => -1,
                    'connect_time' => 0.0,
                ];
            }

            $curlOptions = [
                CURLOPT_CUSTOMREQUEST => $method,
                CURLOPT_HTTPGET => true,
                CURLOPT_RETURNTRANSFER => true,
                CURLOPT_FOLLOWLOCATION => false,
                CURLOPT_HTTPHEADER => $requestHeaders,
                CURLOPT_CONNECTTIMEOUT => 5,
                CURLOPT_TIMEOUT => 15,
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
            $errno = curl_errno($ch);
            $statusCode = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
            $connectTime = (float) curl_getinfo($ch, CURLINFO_CONNECT_TIME);
            curl_close($ch);

            return [
                'status' => $statusCode,
                'headers' => $responseHeaders,
                'body' => is_string($ok) ? $ok : '',
                'curl_errno' => $errno,
                'connect_time' => $connectTime,
            ];
        };

        $instrumentedTransport = static function (string $method, string $url, array $requestHeaders) use ($effectiveTransport, &$probeState): array {
            $probeState['method'] = $method;
            $urlStructure = retrotokInspectUrlStructure($url, false);
            $probeState['url_structure'] = $urlStructure;

            $queryStr = (string) (parse_url($url, PHP_URL_QUERY) ?? '');
            parse_str($queryStr, $parsedQuery);
            $probeState['max_keys'] = isset($parsedQuery['max-keys']) ? (int) $parsedQuery['max-keys'] : null;

            $urlStructureErr = retrotokSummarizeUrlStructureError($urlStructure, 'MALFORMED_R2_URL');
            if ($urlStructureErr !== null) {
                $probeState['http_status'] = null;
                $probeState['curl_errno'] = null;
                $probeState['connect_time'] = 0.0;
                $probeState['error_code'] = $urlStructureErr;
                throw new HttpException(422, $urlStructureErr, 'Constructed R2 request URL failed structural check.');
            }

            $result = $effectiveTransport($method, $url, $requestHeaders);
            $status = (int) ($result['status'] ?? 0);
            $curlErrno = array_key_exists('curl_errno', $result) ? (int) $result['curl_errno'] : null;
            $connectTime = (float) ($result['connect_time'] ?? 0.0);
            $body = (string) ($result['body'] ?? '');

            $probeState['http_status'] = $status > 0 ? $status : null;
            $probeState['curl_errno'] = $curlErrno;
            $probeState['connect_time'] = $connectTime;
            if ($status >= 400) {
                $probeState['error_code'] = retrotokExtractSafeS3ErrorCode($body);
            }

            return [
                'status' => $status,
                'headers' => is_array($result['headers'] ?? null) ? $result['headers'] : [],
                'body' => $body,
            ];
        };

        $adapter = new CloudflareR2Adapter($config, new SigV4Signer(), new SystemClock(), $instrumentedTransport);
        $isConfigured = $adapter->isConfigured();

        $report = [
            'ok' => false,
            'php_version' => PHP_VERSION,
            'configuration_loaded' => $isConfigured,
            'env_file_present' => $envFilePresent,
            'r2_configured' => $isConfigured,
            'r2_path_style' => $config->r2PathStyle,
            'r2_region_configured' => $config->r2Region !== '',
            'url_structure' => $probeState['url_structure'],
            'dns_tls_http' => 'not_attempted',
            'authentication' => 'not_verified',
            'bucket_access' => 'not_verified',
            'http_status' => null,
            'curl_errno' => null,
            'error_category' => null,
        ];

        $requiredExtensions = ['curl', 'openssl', 'simplexml', 'libxml'];
        foreach ($requiredExtensions as $ext) {
            if (!extension_loaded($ext)) {
                $report['authentication'] = 'not_attempted';
                $report['bucket_access'] = 'not_attempted';
                $report['error_category'] = 'MISSING_PHP_EXTENSION:' . strtoupper($ext);
                return ['exit_code' => 1, 'report' => $report];
            }
        }

        if (!$isConfigured) {
            $report['authentication'] = 'not_attempted';
            $report['bucket_access'] = 'not_attempted';
            $report['error_category'] = 'OBJECT_STORAGE_NOT_CONFIGURED';
            return ['exit_code' => 1, 'report' => $report];
        }

        $prefix = $probePrefix ?? sprintf('events/999999999%d/', random_int(100000, 999999));

        try {
            $adapter->listObjectsByPrefix($prefix, 1);

            $report['ok'] = true;
            $report['url_structure'] = $probeState['url_structure'];
            $report['dns_tls_http'] = 'ok';
            $report['authentication'] = 'succeeded';
            $report['bucket_access'] = 'succeeded';
            $report['http_status'] = $probeState['http_status'] ?? 200;
            $report['curl_errno'] = ($probeState['curl_errno'] !== null && $probeState['curl_errno'] >= 0)
                ? $probeState['curl_errno']
                : null;
            $report['error_category'] = null;

            return ['exit_code' => 0, 'report' => $report];
        } catch (StorageNotConfiguredException $e) {
            $report['url_structure'] = $probeState['url_structure'];
            $report['authentication'] = 'not_attempted';
            $report['bucket_access'] = 'not_attempted';
            $report['error_category'] = $e->errorCode;
            return ['exit_code' => 1, 'report' => $report];
        } catch (HttpException $e) {
            $httpStatus = $probeState['http_status'];
            $curlErrno = $probeState['curl_errno'];
            $connectTime = $probeState['connect_time'];
            $s3Code = $probeState['error_code'];
            $report['url_structure'] = $probeState['url_structure'];
            $report['http_status'] = $httpStatus;
            $report['curl_errno'] = ($curlErrno !== null && $curlErrno >= 0) ? $curlErrno : null;

            if ($httpStatus === null) {
                $category = ($curlErrno !== null && $curlErrno > 0)
                    ? retrotokClassifyCurlError($curlErrno, $connectTime)
                    : $e->errorCode;
                if ($curlErrno === -1) {
                    $category = 'CURL_INIT_FAILED';
                } elseif (
                    $curlErrno === null
                    && $e->errorCode === 'INVALID_R2_ENDPOINT'
                    && is_array($probeState['url_structure'])
                ) {
                    $category = retrotokSummarizeUrlStructureError(
                        $probeState['url_structure'],
                        'MALFORMED_R2_ENDPOINT'
                    ) ?? $e->errorCode;
                }
                $report['dns_tls_http'] = $curlErrno !== null ? 'failed' : 'not_attempted';
                $report['authentication'] = $curlErrno !== null ? 'not_verified' : 'not_attempted';
                $report['bucket_access'] = $curlErrno !== null ? 'not_verified' : 'not_attempted';
                $report['error_category'] = $category;
                return ['exit_code' => 1, 'report' => $report];
            }

            $report['dns_tls_http'] = 'ok';
            if ($httpStatus === 401 || $httpStatus === 403) {
                if ($s3Code === 'AccessDenied') {
                    $report['authentication'] = 'succeeded';
                    $report['bucket_access'] = 'failed';
                    $report['error_category'] = 'BUCKET_ACCESS_DENIED';
                } else {
                    $report['authentication'] = 'failed';
                    $report['bucket_access'] = 'failed';
                    $report['error_category'] = $s3Code !== null
                        ? 'R2_AUTH_ERROR:' . $s3Code
                        : 'AUTHENTICATION_FAILED';
                }
            } elseif ($httpStatus === 404) {
                $report['authentication'] = $s3Code === 'NoSuchBucket' ? 'succeeded' : 'indeterminate';
                $report['bucket_access'] = 'failed';
                $report['error_category'] = $s3Code === 'NoSuchBucket' ? 'BUCKET_NOT_FOUND' : 'HTTP_NOT_FOUND';
            } elseif ($httpStatus === 200) {
                $report['authentication'] = 'succeeded';
                $report['bucket_access'] = 'failed';
                $report['error_category'] = $e->errorCode;
            } elseif ($httpStatus >= 300 && $httpStatus < 400) {
                $report['authentication'] = 'indeterminate';
                $report['bucket_access'] = 'failed';
                $report['error_category'] = 'UNEXPECTED_HTTP_REDIRECT';
            } elseif ($httpStatus >= 500) {
                $report['authentication'] = 'indeterminate';
                $report['bucket_access'] = 'failed';
                $report['error_category'] = $s3Code !== null ? 'R2_SERVER_ERROR:' . $s3Code : 'R2_SERVER_ERROR';
            } else {
                $report['authentication'] = 'indeterminate';
                $report['bucket_access'] = 'failed';
                $report['error_category'] = $s3Code !== null ? 'R2_HTTP_ERROR:' . $s3Code : $e->errorCode;
            }

            return ['exit_code' => 1, 'report' => $report];
        } catch (Throwable) {
            $report['dns_tls_http'] = $probeState['http_status'] !== null ? 'ok' : 'failed';
            $report['authentication'] = 'not_verified';
            $report['bucket_access'] = 'not_verified';
            $report['http_status'] = $probeState['http_status'];
            $report['curl_errno'] = ($probeState['curl_errno'] !== null && $probeState['curl_errno'] >= 0)
                ? $probeState['curl_errno']
                : null;
            $report['error_category'] = 'UNEXPECTED_PROBE_ERROR';
            return ['exit_code' => 1, 'report' => $report];
        }
    }
}

$invokedDirectly = realpath((string) ($_SERVER['SCRIPT_FILENAME'] ?? '')) === __FILE__;
if ($invokedDirectly) {
    $envPath = $baseDir . '/.env';
    $envPresent = is_file($envPath);
    EnvLoader::load($envPath);
    $config = new Config();

    $result = retrotokRunR2ConnectivityProbe($config, $envPresent);
    fwrite(STDOUT, json_encode($result['report'], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES) . PHP_EOL);
    exit((int) $result['exit_code']);
}
