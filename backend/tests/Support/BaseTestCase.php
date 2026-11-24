<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Tests\Support;

use PHPUnit\Framework\TestCase;
use Throwable;

/**
 * Shared test base extending PHPUnit\Framework\TestCase while preserving
 * standalone execution via tests/run_php_tests.php.
 */
abstract class BaseTestCase extends TestCase
{
    public function executeStandaloneTest(string $methodName): void
    {
        $this->setUp();
        try {
            $this->{$methodName}();
        } finally {
            $this->tearDown();
        }
    }

    protected function assertStringContains(string $needle, string $haystack, string $message = ''): void
    {
        static::assertStringContainsString($needle, $haystack, $message);
    }

    /**
     * Asserts that invoking $fn throws $exceptionClass and returns the caught throwable.
     * Named assertThrows to avoid colliding with PHPUnit\Framework\TestCase::expectException().
     *
     * @template T of Throwable
     * @param class-string<T> $exceptionClass
     * @return T
     */
    protected function assertThrows(string $exceptionClass, callable $fn): Throwable
    {
        try {
            $fn();
        } catch (Throwable $e) {
            static::assertInstanceOf(
                $exceptionClass,
                $e,
                "Expected exception {$exceptionClass}, got " . $e::class . ': ' . $e->getMessage()
            );

            return $e;
        }

        static::fail("Expected exception {$exceptionClass} was not thrown.");
    }
}
