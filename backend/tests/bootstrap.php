<?php

declare(strict_types=1);

use PHPUnit\Framework\TestCase;
use RetroTok\Cloud\Support\Autoloader;
use RetroTok\Cloud\Tests\Support\StandaloneTestCase;

$backendRoot = dirname(__DIR__);
require_once $backendRoot . '/src/Support/Autoloader.php';
Autoloader::register($backendRoot);

spl_autoload_register(static function (string $class) use ($backendRoot): void {
    $prefix = 'RetroTok\\Cloud\\Tests\\';
    if (!str_starts_with($class, $prefix)) {
        return;
    }
    $relative = substr($class, strlen($prefix));
    $file = $backendRoot . '/tests/' . str_replace('\\', DIRECTORY_SEPARATOR, $relative) . '.php';
    if (is_file($file)) {
        require_once $file;
    }
});

if (!class_exists(TestCase::class)) {
    class_alias(StandaloneTestCase::class, TestCase::class);
}
