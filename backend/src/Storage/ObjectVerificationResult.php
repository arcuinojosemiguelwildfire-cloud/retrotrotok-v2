<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Storage;

final class ObjectVerificationResult
{
    public function __construct(
        public readonly bool $verified,
        public readonly ?string $failureCode,
        public readonly ?string $failureMessage,
        public readonly ?ObjectMetadata $metadata
    ) {
    }

    public static function ok(ObjectMetadata $metadata): self
    {
        return new self(true, null, null, $metadata);
    }

    public static function fail(string $code, string $message, ?ObjectMetadata $metadata = null): self
    {
        return new self(false, $code, $message, $metadata);
    }
}
