<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Support;

/**
 * Lightweight PSR-4 autoloader fallback so Hostinger deployments work
 * even on plans without SSH/Composer execution.
 */
final class Autoloader
{
    public static function register(string $baseDir): void
    {
        $composerAutoload = $baseDir . DIRECTORY_SEPARATOR . 'vendor' . DIRECTORY_SEPARATOR . 'autoload.php';
        if (is_file($composerAutoload)) {
            require_once $composerAutoload;
            return;
        }

        $prefix = 'RetroTok\\Cloud\\';
        $srcDir = $baseDir . DIRECTORY_SEPARATOR . 'src' . DIRECTORY_SEPARATOR;

        spl_autoload_register(static function (string $class) use ($prefix, $srcDir): void {
            if (!str_starts_with($class, $prefix)) {
                return;
            }
            $relativeClass = substr($class, strlen($prefix));
            $file = $srcDir . str_replace('\\', DIRECTORY_SEPARATOR, $relativeClass) . '.php';
            if (is_file($file)) {
                require_once $file;
            }
        });
    }
}
