<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Http;

use RetroTok\Cloud\Exception\ValidationException;

final class Request
{
    public const MAX_JSON_BODY_BYTES = 65536; // 64 KB cap for metadata JSON endpoints

    /**
     * @param array<string, string> $headers Lowercased header map
     * @param array<string, string> $query
     * @param array<string, mixed> $body
     */
    public function __construct(
        public readonly string $method,
        public readonly string $path,
        public readonly array $headers,
        public readonly array $query,
        public readonly array $body,
        public readonly string $clientIp,
        public readonly bool $isHttps
    ) {
    }

    public static function fromGlobals(string $basePath = '', bool $trustCloudflareProxy = false): self
    {
        $method = strtoupper((string) ($_SERVER['REQUEST_METHOD'] ?? 'GET'));
        $rawUri = (string) ($_SERVER['REQUEST_URI'] ?? '/');
        $path = parse_url($rawUri, PHP_URL_PATH);
        $path = is_string($path) && $path !== '' ? $path : '/';
        if ($path !== '/') {
            $inner = trim($path, '/');
            $path = $inner === '' ? '/' : '/' . $inner;
        }

        $normalizedBaseInner = trim(trim($basePath), '/');
        $normalizedBase = $normalizedBaseInner === '' ? '' : '/' . $normalizedBaseInner;
        if ($normalizedBase !== '') {
            if ($path === $normalizedBase) {
                $path = '/';
            } elseif (str_starts_with($path, $normalizedBase . '/')) {
                $path = substr($path, strlen($normalizedBase));
                if ($path === '') {
                    $path = '/';
                }
            }
        }

        $headers = [];
        foreach ($_SERVER as $key => $value) {
            if (str_starts_with($key, 'HTTP_') && is_string($value)) {
                $headerName = strtolower(str_replace('_', '-', substr($key, 5)));
                $headers[$headerName] = $value;
            }
        }
        // Hostinger Apache / FastCGI authorization header pass-through fallbacks
        foreach (['REDIRECT_HTTP_AUTHORIZATION', 'HTTP_AUTHORIZATION'] as $authKey) {
            if (!isset($headers['authorization']) && !empty($_SERVER[$authKey]) && is_string($_SERVER[$authKey])) {
                $headers['authorization'] = $_SERVER[$authKey];
            }
        }
        if (function_exists('getallheaders')) {
            foreach (getallheaders() as $k => $v) {
                $lower = strtolower((string) $k);
                if (!isset($headers[$lower]) && is_string($v)) {
                    $headers[$lower] = $v;
                }
            }
        }

        $query = [];
        foreach ($_GET as $k => $v) {
            if (is_string($k) && (is_string($v) || is_numeric($v))) {
                $query[$k] = (string) $v;
            }
        }

        $body = [];
        $rawBody = file_get_contents('php://input');
        if ($rawBody !== false && trim($rawBody) !== '') {
            if (strlen($rawBody) > self::MAX_JSON_BODY_BYTES) {
                throw new ValidationException('Request body exceeds maximum allowed metadata size.', 'PAYLOAD_TOO_LARGE');
            }
            $decoded = json_decode($rawBody, true);
            if (!is_array($decoded)) {
                throw new ValidationException('Request body must be valid JSON.', 'INVALID_JSON');
            }
            $body = $decoded;
        }

        $isHttps = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
            || ((int) ($_SERVER['SERVER_PORT'] ?? 0) === 443)
            || strtolower((string) ($headers['x-forwarded-proto'] ?? '')) === 'https';

        $clientIp = self::resolveClientIp($headers, $trustCloudflareProxy);

        return new self($method, $path, $headers, $query, $body, $clientIp, $isHttps);
    }

    public function header(string $name, ?string $default = null): ?string
    {
        return $this->headers[strtolower($name)] ?? $default;
    }

    public function wantsJson(): bool
    {
        $accept = strtolower($this->header('accept', '') ?? '');
        return str_contains($accept, 'application/json') || ($this->query['format'] ?? '') === 'json';
    }

    /**
     * @param array<string, string> $headers
     */
    private static function resolveClientIp(array $headers, bool $trustCloudflareProxy = false): string
    {
        $remote = (string) ($_SERVER['REMOTE_ADDR'] ?? '0.0.0.0');
        if ($trustCloudflareProxy) {
            $cfIp = $headers['cf-connecting-ip'] ?? null;
            if ($cfIp !== null && filter_var($cfIp, FILTER_VALIDATE_IP) !== false) {
                return $cfIp;
            }
        }
        return filter_var($remote, FILTER_VALIDATE_IP) !== false ? $remote : '0.0.0.0';
    }
}
