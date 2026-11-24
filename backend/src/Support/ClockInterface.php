<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Support;

use DateTimeImmutable;

interface ClockInterface
{
    public function now(): DateTimeImmutable;
}
