<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Config;

/**
 * Dependency-free .env parser safe for PHP 8.1+ shared hosting.
 */
final class EnvLoader
{
    /**
     * @return array<string, string>
     */
    public static function load(string $envFilePath, bool $overrideExisting = false): array
    {
        if (!is_file($envFilePath) || !is_readable($envFilePath)) {
            return [];
        }

        $lines = file($envFilePath, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
        if ($lines === false) {
            return [];
        }

        $loaded = [];
        foreach ($lines as $index => $rawLine) {
            if ($index === 0 && str_starts_with($rawLine, "\xEF\xBB\xBF")) {
                $rawLine = substr($rawLine, 3);
            }
            $line = trim($rawLine);
            if ($line === '' || str_starts_with($line, '#')) {
                continue;
            }

            if (str_starts_with($line, 'export ')) {
                $line = trim(substr($line, 7));
            }

            $eqPos = strpos($line, '=');
            if ($eqPos === false) {
                continue;
            }

            $key = trim(substr($line, 0, $eqPos));
            $value = trim(substr($line, $eqPos + 1));
            if ($key === '' || preg_match('/^[A-Z0-9_]+$/', $key) !== 1) {
                continue;
            }

            $firstChar = $value[0] ?? '';
            if ($firstChar === '"' || $firstChar === "'") {
                $closingQuotePos = strpos($value, $firstChar, 1);
                if ($closingQuotePos !== false) {
                    $trailing = trim(substr($value, $closingQuotePos + 1));
                    if ($trailing === '' || str_starts_with($trailing, '#')) {
                        $value = substr($value, 1, $closingQuotePos - 1);
                    }
                }
            } else {
                $commentPos = strpos($value, ' #');
                if ($commentPos !== false) {
                    $value = rtrim(substr($value, 0, $commentPos));
                }
            }

            $loaded[$key] = $value;
            if ($overrideExisting || getenv($key) === false) {
                $_ENV[$key] = $value;
                $_SERVER[$key] = $value;
                putenv($key . '=' . $value);
            }
        }

        return $loaded;
    }
}
