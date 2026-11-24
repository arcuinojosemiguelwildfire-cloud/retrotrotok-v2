<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Security;

use RetroTok\Cloud\Config\Config;

/**
 * Cryptographic token generation and constant-time hash verification.
 * Uses >= 256 bits of CSPRNG entropy and stores SHA-256 hashes only.
 */
final class TokenService
{
    public function __construct(private readonly string $pepper = '')
    {
    }

    public static function fromConfig(Config $config): self
    {
        $pepper = Config::isPlaceholderValue($config->qrTokenPepper) ? '' : $config->qrTokenPepper;
        return new self($pepper);
    }

    /**
     * Generates a 256-bit (32-byte) URL-safe random token (~43 chars).
     */
    public function generateToken(int $bytes = 32): string
    {
        if ($bytes < 32) {
            $bytes = 32;
        }
        $raw = random_bytes($bytes);
        return rtrim(strtr(base64_encode($raw), '+/', '-_'), '=');
    }

    public function hashQrToken(string $rawToken): string
    {
        if ($this->pepper !== '') {
            return hash_hmac('sha256', $rawToken, $this->pepper);
        }
        return hash('sha256', $rawToken);
    }

    public function hashDeviceToken(string $rawToken): string
    {
        return hash('sha256', $rawToken);
    }

    public function isValidQrTokenFormat(string $token): bool
    {
        return preg_match('/^[A-Za-z0-9_-]{32,128}$/', $token) === 1;
    }

    public function verifyConstantTime(string $expectedHash, string $candidateHash): bool
    {
        if ($expectedHash === '' || $candidateHash === '') {
            return false;
        }
        return hash_equals($expectedHash, $candidateHash);
    }
}
