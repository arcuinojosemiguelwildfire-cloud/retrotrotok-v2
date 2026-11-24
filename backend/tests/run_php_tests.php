#!/usr/bin/env php
<?php

declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';

use RetroTok\Cloud\Tests\Integration\BackendApiIntegrationTest;
use RetroTok\Cloud\Tests\Support\BaseTestCase;
use RetroTok\Cloud\Tests\Unit\CheckR2CliTest;
use RetroTok\Cloud\Tests\Unit\MigrationRunnerTest;
use RetroTok\Cloud\Tests\Unit\SecurityAndR2BoundaryTest;
use RetroTok\Cloud\Tests\Unit\SubdirectoryDeploymentTest;

$suiteClasses = [
    SecurityAndR2BoundaryTest::class,
    MigrationRunnerTest::class,
    SubdirectoryDeploymentTest::class,
    CheckR2CliTest::class,
    BackendApiIntegrationTest::class,
];

$passed = 0;
$failed = 0;

foreach ($suiteClasses as $suiteClass) {
    $ref = new ReflectionClass($suiteClass);
    foreach ($ref->getMethods(ReflectionMethod::IS_PUBLIC) as $method) {
        $methodName = $method->getName();
        if (!str_starts_with($methodName, 'test') || $method->getDeclaringClass()->getName() !== $suiteClass) {
            continue;
        }
        $name = $ref->getShortName() . '::' . $methodName;
        try {
            /** @var BaseTestCase $instance */
            $instance = $ref->newInstance($methodName);
            $instance->executeStandaloneTest($methodName);
            fwrite(STDOUT, "[PASS] {$name}\n");
            $passed++;
        } catch (Throwable $e) {
            fwrite(STDERR, "[FAIL] {$name}: {$e->getMessage()}\n");
            $failed++;
        }
    }
}

fwrite(STDOUT, "\nSummary: {$passed} passed, {$failed} failed.\n");
exit($failed > 0 ? 1 : 0);
