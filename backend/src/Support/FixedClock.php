<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Support;

use DateTimeImmutable;
use DateTimeZone;

final class FixedClock implements ClockInterface
{
    private DateTimeImmutable $current;

    public function __construct(DateTimeImmutable|string $initialTime)
    {
        $this->current = is_string($initialTime)
            ? new DateTimeImmutable($initialTime, new DateTimeZone('UTC'))
            : $initialTime->setTimezone(new DateTimeZone('UTC'));
    }

    public function now(): DateTimeImmutable
    {
        return $this->current;
    }

    public function setNow(DateTimeImmutable|string $time): void
    {
        $this->current = is_string($time)
            ? new DateTimeImmutable($time, new DateTimeZone('UTC'))
            : $time->setTimezone(new DateTimeZone('UTC'));
    }
}
