<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Auth;

use RetroTok\Cloud\Config\Config;
use RetroTok\Cloud\Exception\ServiceUnavailableException;
use RetroTok\Cloud\Exception\UnauthorizedException;
use RetroTok\Cloud\Http\Request;
use RetroTok\Cloud\Repository\DeviceRepository;
use RetroTok\Cloud\Security\TokenService;

/**
 * Constant-time, fail-closed Bearer token authenticator for Admin and Kiosk Device routes.
 * Never accepts empty/placeholder tokens or hardcoded fallback secrets.
 */
final class AuthMiddleware
{
    public function __construct(
        private readonly Config $config,
        private readonly DeviceRepository $deviceRepository,
        private readonly TokenService $tokenService
    ) {
    }

    public function requireAdmin(Request $request): AuthContext
    {
        if (!$this->config->hasValidAdminTokenConfigured()) {
            throw new ServiceUnavailableException(
                'Administrator API authentication is not configured.',
                'ADMIN_AUTH_NOT_CONFIGURED'
            );
        }

        $token = $this->extractBearerToken($request);
        $expectedHash = hash('sha256', $this->config->adminApiToken);
        $candidateHash = hash('sha256', $token);

        if (!hash_equals($expectedHash, $candidateHash)) {
            throw new UnauthorizedException('Invalid administrator credentials.', 'INVALID_ADMIN_TOKEN');
        }

        return new AuthContext(role: 'admin');
    }

    public function requireDevice(Request $request): AuthContext
    {
        $token = $this->extractBearerToken($request);
        if (strlen($token) < 32) {
            throw new UnauthorizedException('Invalid device token.', 'INVALID_DEVICE_TOKEN');
        }

        $tokenHash = $this->tokenService->hashDeviceToken($token);
        $device = $this->deviceRepository->findActiveByTokenHash($tokenHash);
        if ($device === null) {
            throw new UnauthorizedException('Device credential is invalid or revoked.', 'INVALID_DEVICE_TOKEN');
        }

        if (!hash_equals((string) $device['token_hash'], $tokenHash)) {
            throw new UnauthorizedException('Device credential verification failed.', 'INVALID_DEVICE_TOKEN');
        }

        return new AuthContext(
            role: 'device',
            deviceId: (int) $device['id'],
            deviceIdentifier: (string) $device['device_identifier']
        );
    }

    public function requireDeviceOrAdmin(Request $request): AuthContext
    {
        $token = $this->extractBearerToken($request);

        if ($this->config->hasValidAdminTokenConfigured()) {
            $expectedAdminHash = hash('sha256', $this->config->adminApiToken);
            if (hash_equals($expectedAdminHash, hash('sha256', $token))) {
                return new AuthContext(role: 'admin');
            }
        }

        if (strlen($token) >= 32) {
            $tokenHash = $this->tokenService->hashDeviceToken($token);
            $device = $this->deviceRepository->findActiveByTokenHash($tokenHash);
            if ($device !== null && hash_equals((string) $device['token_hash'], $tokenHash)) {
                return new AuthContext(
                    role: 'device',
                    deviceId: (int) $device['id'],
                    deviceIdentifier: (string) $device['device_identifier']
                );
            }
        }

        throw new UnauthorizedException('Valid kiosk device or administrator credentials required.', 'UNAUTHORIZED');
    }

    private function extractBearerToken(Request $request): string
    {
        $header = $request->header('authorization');
        if ($header === null || !preg_match('/^Bearer\s+(.+)$/i', trim($header), $matches)) {
            throw new UnauthorizedException('Missing Bearer authorization header.', 'MISSING_BEARER_TOKEN');
        }
        $token = trim($matches[1]);
        if ($token === '') {
            throw new UnauthorizedException('Empty Bearer token.', 'MISSING_BEARER_TOKEN');
        }
        return $token;
    }
}
