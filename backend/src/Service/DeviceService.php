<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Service;

use RetroTok\Cloud\Config\Config;
use RetroTok\Cloud\Exception\ServiceUnavailableException;
use RetroTok\Cloud\Exception\UnauthorizedException;
use RetroTok\Cloud\Exception\ValidationException;
use RetroTok\Cloud\Repository\DeviceRepository;
use RetroTok\Cloud\Security\StorageKeySanitizer;
use RetroTok\Cloud\Security\TokenService;
use RetroTok\Cloud\Support\ClockInterface;

final class DeviceService
{
    public function __construct(
        private readonly Config $config,
        private readonly DeviceRepository $devices,
        private readonly TokenService $tokens,
        private readonly ClockInterface $clock
    ) {
    }

    /**
     * Pairs a kiosk device using an administrator-configured one-time pairing secret.
     *
     * @param array<string, mixed> $payload
     * @return array<string, mixed>
     */
    public function pairDevice(array $payload): array
    {
        if (!$this->config->hasValidPairingSecretConfigured()) {
            throw new ServiceUnavailableException(
                'Device pairing secret is not configured on the server.',
                'PAIRING_NOT_CONFIGURED'
            );
        }

        $pairingCode = trim((string) ($payload['pairing_code'] ?? ''));
        if ($pairingCode === '') {
            throw new ValidationException('pairing_code is required.', 'MISSING_PAIRING_CODE');
        }

        $expectedHash = hash('sha256', $this->config->devicePairingSecret);
        $candidateHash = hash('sha256', $pairingCode);
        if (!hash_equals($expectedHash, $candidateHash)) {
            throw new UnauthorizedException('Invalid pairing code.', 'INVALID_PAIRING_CODE');
        }

        $deviceIdentifier = StorageKeySanitizer::sanitizeIdentifier(
            (string) ($payload['device_identifier'] ?? ''),
            'device_identifier'
        );
        $deviceName = trim((string) ($payload['device_name'] ?? 'RetroTok Kiosk'));
        $deviceName = mb_substr($deviceName !== '' ? $deviceName : 'RetroTok Kiosk', 0, 120, 'UTF-8');
        $appVersion = isset($payload['app_version']) ? mb_substr(trim((string) $payload['app_version']), 0, 32, 'UTF-8') : null;
        $sdkInt = isset($payload['android_sdk_int']) ? filter_var($payload['android_sdk_int'], FILTER_VALIDATE_INT) : null;
        if ($sdkInt === false) {
            throw new ValidationException('Invalid android_sdk_int.', 'INVALID_SDK_INT');
        }

        $rawDeviceToken = $this->tokens->generateToken(32);
        $tokenHash = $this->tokens->hashDeviceToken($rawDeviceToken);
        $nowUtc = $this->clock->now()->format('Y-m-d H:i:s');

        $device = $this->devices->upsertPairedDevice(
            deviceIdentifier: $deviceIdentifier,
            deviceName: $deviceName,
            tokenHash: $tokenHash,
            appVersion: $appVersion,
            androidSdkInt: $sdkInt,
            nowUtc: $nowUtc
        );

        return [
            'device_id' => (int) $device['id'],
            'device_identifier' => (string) $device['device_identifier'],
            'device_name' => (string) $device['device_name'],
            'device_token' => $rawDeviceToken,
            'paired_at' => $nowUtc,
        ];
    }

    /**
     * @param array<string, mixed> $payload
     * @return array<string, mixed>
     */
    public function recordHeartbeat(int $deviceId, array $payload): array
    {
        $nowUtc = $this->clock->now()->format('Y-m-d H:i:s');
        $appVersion = isset($payload['app_version']) ? mb_substr(trim((string) $payload['app_version']), 0, 32, 'UTF-8') : null;
        $this->devices->touchHeartbeat($deviceId, $appVersion, $nowUtc);

        return [
            'device_id' => $deviceId,
            'last_seen_at' => $nowUtc,
            'status' => 'REGISTERED',
        ];
    }
}
