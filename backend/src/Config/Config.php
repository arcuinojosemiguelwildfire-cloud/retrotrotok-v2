<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Config;

/**
 * Typed runtime configuration with strict placeholder detection.
 * Never supplies fallback real passwords or object-storage credentials.
 */
final class Config
{
    public readonly string $appEnv;
    public readonly bool $appDebug;
    public readonly string $appUrl;
    public readonly string $appBasePath;
    public readonly bool $trustCloudflareProxy;
    public readonly bool $requireHttps;

    public readonly string $dbDriver;
    public readonly string $dbHost;
    public readonly int $dbPort;
    public readonly string $dbDatabase;
    public readonly string $dbUsername;
    public readonly string $dbPassword;
    public readonly string $dbCharset;

    public readonly string $adminApiToken;
    public readonly string $qrTokenPepper;
    public readonly string $devicePairingSecret;

    public readonly string $r2AccountId;
    public readonly string $r2Bucket;
    public readonly string $r2AccessKeyId;
    public readonly string $r2SecretAccessKey;
    public readonly string $r2Endpoint;
    public readonly string $r2Region;
    public readonly bool $r2PathStyle;

    public readonly int $uploadUrlTtlSeconds;
    public readonly int $playbackUrlTtlSeconds;
    public readonly int $videoRetentionDays;
    public readonly int $eventMaxVideos;
    public readonly int $maxVideoUploadBytes;
    public readonly int $maxVideoDurationSeconds;
    /** @var list<string> */
    public readonly array $allowedVideoMimeTypes;

    public readonly int $rateLimitQrMaxRequests;
    public readonly int $rateLimitQrWindowSeconds;
    public readonly int $rateLimitPairMaxRequests;
    public readonly int $rateLimitPairWindowSeconds;

    /**
     * @param array<string, mixed> $values
     */
    public function __construct(array $values = [])
    {
        $get = static function (string $key, string $default = '') use ($values): string {
            if (array_key_exists($key, $values)) {
                return (string) $values[$key];
            }
            if (isset($_ENV[$key])) {
                return (string) $_ENV[$key];
            }
            $envVal = getenv($key);
            return $envVal !== false ? (string) $envVal : $default;
        };

        $this->appEnv = strtolower(trim($get('APP_ENV', 'production')));
        $this->appDebug = filter_var($get('APP_DEBUG', 'false'), FILTER_VALIDATE_BOOLEAN);
        $this->appUrl = rtrim(trim($get('APP_URL', 'https://your-domain.example.com')), '/');
        $rawBasePath = trim($get('APP_BASE_PATH', ''));
        if ($rawBasePath === '') {
            $parsedPath = parse_url($this->appUrl, PHP_URL_PATH);
            $rawBasePath = is_string($parsedPath) ? $parsedPath : '';
        }
        $this->appBasePath = self::normalizeBasePath($rawBasePath);
        $this->trustCloudflareProxy = filter_var($get('TRUST_CLOUDFLARE_PROXY', 'false'), FILTER_VALIDATE_BOOLEAN);
        $requireHttpsDefault = $this->appEnv === 'production' ? 'true' : 'false';
        $this->requireHttps = filter_var($get('REQUIRE_HTTPS', $requireHttpsDefault), FILTER_VALIDATE_BOOLEAN);

        $this->dbDriver = strtolower(trim($get('DB_DRIVER', 'mysql')));
        $this->dbHost = trim($get('DB_HOST', '127.0.0.1'));
        $this->dbPort = max(1, (int) $get('DB_PORT', '3306'));
        $this->dbDatabase = trim($get('DB_DATABASE', ''));
        $this->dbUsername = trim($get('DB_USERNAME', ''));
        $this->dbPassword = $get('DB_PASSWORD', '');
        $this->dbCharset = trim($get('DB_CHARSET', 'utf8mb4'));

        $this->adminApiToken = trim($get('ADMIN_API_TOKEN', ''));
        $this->qrTokenPepper = $get('QR_TOKEN_PEPPER', '');
        $this->devicePairingSecret = trim($get('DEVICE_PAIRING_SECRET', ''));

        $this->r2AccountId = trim($get('R2_ACCOUNT_ID', ''));
        $this->r2Bucket = trim($get('R2_BUCKET', ''));
        $this->r2AccessKeyId = trim($get('R2_ACCESS_KEY_ID', ''));
        $this->r2SecretAccessKey = trim($get('R2_SECRET_ACCESS_KEY', ''));
        $this->r2Endpoint = rtrim(trim($get('R2_ENDPOINT', '')), '/');
        $this->r2Region = trim($get('R2_REGION', 'auto')) ?: 'auto';
        $this->r2PathStyle = filter_var($get('R2_PATH_STYLE', 'true'), FILTER_VALIDATE_BOOLEAN);

        $this->uploadUrlTtlSeconds = max(60, min(3600, (int) $get('UPLOAD_URL_TTL_SECONDS', '900')));
        $this->playbackUrlTtlSeconds = max(60, min(3600, (int) $get('PLAYBACK_URL_TTL_SECONDS', '1800')));
        $this->videoRetentionDays = max(1, (int) $get('VIDEO_RETENTION_DAYS', '30'));
        $this->eventMaxVideos = max(1, min(300, (int) $get('EVENT_MAX_VIDEOS', '300')));
        $this->maxVideoUploadBytes = max(1024, (int) $get('MAX_VIDEO_UPLOAD_BYTES', '104857600'));
        $this->maxVideoDurationSeconds = max(1, min(65, (int) $get('MAX_VIDEO_DURATION_SECONDS', '65')));

        $mimeRaw = $get('ALLOWED_VIDEO_MIME_TYPES', 'video/mp4');
        $mimes = array_values(array_filter(array_map(
            static fn (string $m): string => strtolower(trim($m)),
            explode(',', $mimeRaw)
        )));
        $this->allowedVideoMimeTypes = $mimes !== [] ? $mimes : ['video/mp4'];

        $this->rateLimitQrMaxRequests = max(1, (int) $get('RATE_LIMIT_QR_MAX_REQUESTS', '60'));
        $this->rateLimitQrWindowSeconds = max(1, (int) $get('RATE_LIMIT_QR_WINDOW_SECONDS', '60'));
        $this->rateLimitPairMaxRequests = max(1, (int) $get('RATE_LIMIT_PAIR_MAX_REQUESTS', '10'));
        $this->rateLimitPairWindowSeconds = max(1, (int) $get('RATE_LIMIT_PAIR_WINDOW_SECONDS', '300'));
    }

    public static function normalizeBasePath(string $path): string
    {
        $inner = trim(trim($path), '/');
        return $inner === '' ? '' : '/' . $inner;
    }

    public static function isPlaceholderValue(string $value): bool
    {
        $trimmed = trim($value);
        if ($trimmed === '') {
            return true;
        }
        $upper = strtoupper($trimmed);
        return str_contains($upper, 'REPLACE_WITH_')
            || str_contains($upper, 'YOUR-')
            || str_contains($upper, 'YOUR_')
            || str_contains($upper, 'CHANGEME')
            || str_contains($upper, 'EXAMPLE_SECRET')
            || preg_match('/<[A-Za-z0-9_.-]+>/', $trimmed) === 1
            || preg_match('/\{[A-Za-z0-9_.-]+\}/', $trimmed) === 1;
    }

    public function hasValidAdminTokenConfigured(): bool
    {
        return !self::isPlaceholderValue($this->adminApiToken) && strlen($this->adminApiToken) >= 32;
    }

    public function hasValidPairingSecretConfigured(): bool
    {
        return !self::isPlaceholderValue($this->devicePairingSecret) && strlen($this->devicePairingSecret) >= 16;
    }

    public function isR2Configured(): bool
    {
        return !self::isPlaceholderValue($this->r2AccountId)
            && !self::isPlaceholderValue($this->r2Bucket)
            && !self::isPlaceholderValue($this->r2AccessKeyId)
            && !self::isPlaceholderValue($this->r2SecretAccessKey)
            && !self::isPlaceholderValue($this->r2Endpoint)
            && str_starts_with(strtolower($this->r2Endpoint), 'https://');
    }

    public function isDatabaseConfigured(): bool
    {
        if ($this->dbDriver === 'sqlite') {
            return $this->dbDatabase !== '';
        }
        return !self::isPlaceholderValue($this->dbDatabase)
            && !self::isPlaceholderValue($this->dbUsername)
            && !self::isPlaceholderValue($this->dbPassword);
    }
}
