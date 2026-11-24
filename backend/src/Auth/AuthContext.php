<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Auth;

final class AuthContext
{
    public function __construct(
        public readonly string $role, // 'admin' | 'device'
        public readonly ?int $deviceId = null,
        public readonly ?string $deviceIdentifier = null
    ) {
    }
}
