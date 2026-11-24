<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Tests\Support;

use AssertionError;

/**
 * Minimal standalone TestCase fallback used only when PHPUnit is not installed via Composer.
 */
abstract class StandaloneTestCase
{
    public function __construct(string $name = '')
    {
    }

    protected function setUp(): void
    {
    }

    protected function tearDown(): void
    {
    }

    public static function assertTrue(mixed $condition, string $message = 'Expected true.'): void
    {
        if ($condition !== true) {
            throw new AssertionError($message);
        }
    }

    public static function assertFalse(mixed $condition, string $message = 'Expected false.'): void
    {
        if ($condition !== false) {
            throw new AssertionError($message);
        }
    }

    public static function assertSame(mixed $expected, mixed $actual, string $message = ''): void
    {
        if ($expected !== $actual) {
            $detail = $message !== '' ? $message . ' — ' : '';
            throw new AssertionError($detail . 'Expected ' . var_export($expected, true) . ', got ' . var_export($actual, true));
        }
    }

    public static function assertNotSame(mixed $notExpected, mixed $actual, string $message = ''): void
    {
        if ($notExpected === $actual) {
            throw new AssertionError($message !== '' ? $message : 'Did not expect identical values.');
        }
    }

    public static function assertNull(mixed $actual, string $message = 'Expected null.'): void
    {
        if ($actual !== null) {
            throw new AssertionError($message);
        }
    }

    public static function assertNotNull(mixed $actual, string $message = 'Expected non-null.'): void
    {
        if ($actual === null) {
            throw new AssertionError($message);
        }
    }

    public static function assertInstanceOf(string $expected, mixed $actual, string $message = ''): void
    {
        if (!($actual instanceof $expected)) {
            $actualType = is_object($actual) ? $actual::class : gettype($actual);
            $detail = $message !== '' ? $message . ' — ' : '';
            throw new AssertionError($detail . "Failed asserting that {$actualType} is an instance of class {$expected}.");
        }
    }

    public static function assertStringContainsString(string $needle, string $haystack, string $message = ''): void
    {
        if (!str_contains($haystack, $needle)) {
            $detail = $message !== '' ? $message . ' — ' : '';
            throw new AssertionError($detail . "Expected '{$haystack}' to contain '{$needle}'.");
        }
    }

    public static function fail(string $message = ''): never
    {
        throw new AssertionError($message !== '' ? $message : 'Test failed.');
    }
}
