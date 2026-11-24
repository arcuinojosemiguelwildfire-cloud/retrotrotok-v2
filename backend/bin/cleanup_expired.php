#!/usr/bin/env php
<?php

declare(strict_types=1);

use RetroTok\Cloud\Config\Config;
use RetroTok\Cloud\Config\EnvLoader;
use RetroTok\Cloud\Database\ConnectionFactory;
use RetroTok\Cloud\Repository\VideoRepository;
use RetroTok\Cloud\Service\RetentionService;
use RetroTok\Cloud\Storage\CloudflareR2Adapter;
use RetroTok\Cloud\Storage\SigV4Signer;
use RetroTok\Cloud\Support\Autoloader;
use RetroTok\Cloud\Support\SystemClock;

$baseDir = dirname(__DIR__);
require_once $baseDir . '/src/Support/Autoloader.php';
Autoloader::register($baseDir);

EnvLoader::load($baseDir . '/.env');
$config = new Config();
$clock = new SystemClock();

try {
    $pdo = ConnectionFactory::create($config);
    $storage = new CloudflareR2Adapter($config, new SigV4Signer(), $clock);
    $service = new RetentionService(new VideoRepository($pdo), $storage, $clock);

    $report = $service->cleanupExpiredVideos(100);
    fwrite(STDOUT, json_encode($report, JSON_PRETTY_PRINT) . "\n");
    exit($report['failed'] > 0 ? 1 : 0);
} catch (Throwable $e) {
    fwrite(STDERR, "Retention cleanup error: {$e->getMessage()}\n");
    exit(1);
}
