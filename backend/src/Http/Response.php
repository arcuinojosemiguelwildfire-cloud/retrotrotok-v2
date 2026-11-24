<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Http;

final class Response
{
    /**
     * @param array<string, string> $headers
     */
    public function __construct(
        public readonly int $statusCode,
        public readonly string $body,
        public readonly string $contentType = 'application/json; charset=utf-8',
        public readonly array $headers = []
    ) {
    }

    /**
     * @param array<string, mixed> $data
     * @param array<string, string> $headers
     */
    public static function json(int $statusCode, array $data, array $headers = []): self
    {
        $payload = json_encode($data, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
        if ($payload === false) {
            $payload = '{"ok":false,"error":{"code":"JSON_ENCODE_ERROR","message":"Failed to encode response."}}';
            $statusCode = 500;
        }
        return new self($statusCode, $payload, 'application/json; charset=utf-8', $headers);
    }

    /**
     * @param array<string, string> $headers
     */
    public static function html(int $statusCode, string $html, array $headers = []): self
    {
        $defaultSecurityHeaders = [
            'Content-Security-Policy' => "default-src 'self'; media-src https:; img-src 'self' https: data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; frame-ancestors 'none';",
        ];
        return new self($statusCode, $html, 'text/html; charset=utf-8', array_merge($defaultSecurityHeaders, $headers));
    }

    public function emit(): void
    {
        http_response_code($this->statusCode);
        header('Content-Type: ' . $this->contentType);
        header('X-Content-Type-Options: nosniff');
        header('X-Frame-Options: DENY');
        header('Referrer-Policy: no-referrer');
        header('Cache-Control: no-store, max-age=0');

        foreach ($this->headers as $name => $value) {
            header($name . ': ' . $value);
        }

        echo $this->body;
    }
}
