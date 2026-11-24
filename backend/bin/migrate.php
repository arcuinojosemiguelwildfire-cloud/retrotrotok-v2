#!/usr/bin/env php
<?php

declare(strict_types=1);

use RetroTok\Cloud\Config\Config;
use RetroTok\Cloud\Config\EnvLoader;
use RetroTok\Cloud\Database\ConnectionFactory;
use RetroTok\Cloud\Database\MigrationRunner;
use RetroTok\Cloud\Support\Autoloader;

$baseDir = dirname(__DIR__);
require_once $baseDir . '/src/Support/Autoloader.php';
Autoloader::register($baseDir);

EnvLoader::load($baseDir . '/.env');
$config = new Config();

try {
    $pdo = ConnectionFactory::create($config);
    $runner = new MigrationRunner($pdo, $baseDir . '/database/migrations');
    $applied = $runner->run();

    if ($applied === []) {
        fwrite(STDOUT, "Database schema is already up to date.\n");
    } else {
        foreach ($applied as $file) {
            fwrite(STDOUT, "Applied migration: {$file}\n");
        }
    }
    exit(0);
} catch (Throwable $e) {
    fwrite(STDERR, "Migration error: {$e->getMessage()}\n");
    exit(1);
}
