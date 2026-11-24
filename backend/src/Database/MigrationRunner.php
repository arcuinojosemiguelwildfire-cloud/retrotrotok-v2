<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Database;

use PDO;
use RuntimeException;

/**
 * Executes versioned SQL migrations in deterministic filename order
 * and records SHA-256 checksums in `schema_migrations`.
 */
final class MigrationRunner
{
    public function __construct(
        private readonly PDO $pdo,
        private readonly string $migrationsDir
    ) {
    }

    /**
     * @return list<string> List of newly applied migration filenames
     */
    public function run(): array
    {
        if (!is_dir($this->migrationsDir)) {
            throw new RuntimeException('Migrations directory does not exist.');
        }

        $files = glob($this->migrationsDir . DIRECTORY_SEPARATOR . '*.sql') ?: [];
        sort($files, SORT_STRING);
        if ($files === []) {
            return [];
        }

        // Ensure ledger table exists before querying applied migrations.
        $firstSql = file_get_contents($files[0]);
        if ($firstSql === false) {
            throw new RuntimeException('Failed reading initial migration.');
        }
        $this->executeSqlBatch($firstSql);

        $applied = $this->getAppliedMigrations();
        $newlyApplied = [];

        foreach ($files as $filePath) {
            $version = basename($filePath);
            $sql = file_get_contents($filePath);
            if ($sql === false) {
                throw new RuntimeException("Failed reading migration {$version}.");
            }
            $checksum = hash('sha256', $sql);

            if (isset($applied[$version])) {
                if (!hash_equals($applied[$version], $checksum)) {
                    throw new RuntimeException("Checksum mismatch for already-applied migration {$version}.");
                }
                continue;
            }

            $this->executeSqlBatch($sql);

            $stmt = $this->pdo->prepare(
                'INSERT INTO schema_migrations (version, checksum_sha256) VALUES (:version, :checksum)'
            );
            $stmt->execute([
                ':version' => $version,
                ':checksum' => $checksum,
            ]);

            $newlyApplied[] = $version;
        }

        return $newlyApplied;
    }

    /**
     * @return array<string, string>
     */
    public function getAppliedMigrations(): array
    {
        $stmt = $this->pdo->query('SELECT version, checksum_sha256 FROM schema_migrations ORDER BY version ASC');
        if ($stmt === false) {
            return [];
        }
        $map = [];
        foreach ($stmt->fetchAll() as $row) {
            $map[(string) $row['version']] = (string) $row['checksum_sha256'];
        }
        return $map;
    }

    private function executeSqlBatch(string $sql): void
    {
        $driver = (string) $this->pdo->getAttribute(PDO::ATTR_DRIVER_NAME);
        if ($driver === 'mysql') {
            foreach (self::parseStatements($sql) as $statement) {
                $this->pdo->exec($statement);
            }
            return;
        }

        $this->pdo->exec($sql);
    }

    /**
     * Splits a SQL batch into individual executable statements while stripping
     * leading full-line `--` comments and skipping empty/comment-only sections.
     * Preserves string literals, quoted identifiers, and inline comments inside statements.
     *
     * @return list<string>
     */
    public static function parseStatements(string $sql): array
    {
        if (str_starts_with($sql, "\xEF\xBB\xBF")) {
            $sql = substr($sql, 3);
        }

        $length = strlen($sql);
        $statements = [];
        $buffer = '';
        $hasExecutableContent = false;
        $state = 'normal'; // normal | single_quote | double_quote | backtick | line_comment | block_comment
        $skipBlockComment = false;

        $i = 0;
        while ($i < $length) {
            $ch = $sql[$i];
            $next = $i + 1 < $length ? $sql[$i + 1] : '';

            if ($state === 'single_quote') {
                $buffer .= $ch;
                if ($ch === '\\' && $next !== '') {
                    $buffer .= $next;
                    $i += 2;
                    continue;
                }
                if ($ch === "'" && $next === "'") {
                    $buffer .= $next;
                    $i += 2;
                    continue;
                }
                if ($ch === "'") {
                    $state = 'normal';
                }
                $i++;
                continue;
            }

            if ($state === 'double_quote') {
                $buffer .= $ch;
                if ($ch === '\\' && $next !== '') {
                    $buffer .= $next;
                    $i += 2;
                    continue;
                }
                if ($ch === '"' && $next === '"') {
                    $buffer .= $next;
                    $i += 2;
                    continue;
                }
                if ($ch === '"') {
                    $state = 'normal';
                }
                $i++;
                continue;
            }

            if ($state === 'backtick') {
                $buffer .= $ch;
                if ($ch === '`' && $next === '`') {
                    $buffer .= $next;
                    $i += 2;
                    continue;
                }
                if ($ch === '`') {
                    $state = 'normal';
                }
                $i++;
                continue;
            }

            if ($state === 'line_comment') {
                $buffer .= $ch;
                if ($ch === "\n") {
                    $state = 'normal';
                }
                $i++;
                continue;
            }

            if ($state === 'block_comment') {
                if (!$skipBlockComment) {
                    $buffer .= $ch;
                }
                if ($ch === '*' && $next === '/') {
                    if (!$skipBlockComment) {
                        $buffer .= $next;
                    }
                    $state = 'normal';
                    $skipBlockComment = false;
                    $i += 2;
                    continue;
                }
                $i++;
                continue;
            }

            if (!$hasExecutableContent) {
                if ($ch === ' ' || $ch === "\t" || $ch === "\r" || $ch === "\n") {
                    $i++;
                    continue;
                }

                if ($ch === ';') {
                    $buffer = '';
                    $i++;
                    continue;
                }

                if ($ch === '-' && $next === '-') {
                    $after = $i + 2 < $length ? $sql[$i + 2] : '';
                    if ($after === '' || $after === ' ' || $after === "\t" || $after === "\r" || $after === "\n" || $after === '-') {
                        $newlinePos = strpos($sql, "\n", $i + 2);
                        if ($newlinePos === false) {
                            break;
                        }
                        $i = $newlinePos + 1;
                        continue;
                    }
                }

                if ($ch === '/' && $next === '*') {
                    $after = $i + 2 < $length ? $sql[$i + 2] : '';
                    if ($after !== '!') {
                        $state = 'block_comment';
                        $skipBlockComment = true;
                        $i += 2;
                        continue;
                    }
                }
            }

            if ($ch === ';') {
                $trimmed = trim($buffer);
                if ($trimmed !== '') {
                    $statements[] = $trimmed;
                }
                $buffer = '';
                $hasExecutableContent = false;
                $i++;
                continue;
            }

            if ($ch === "'") {
                $hasExecutableContent = true;
                $state = 'single_quote';
                $buffer .= $ch;
                $i++;
                continue;
            }

            if ($ch === '"') {
                $hasExecutableContent = true;
                $state = 'double_quote';
                $buffer .= $ch;
                $i++;
                continue;
            }

            if ($ch === '`') {
                $hasExecutableContent = true;
                $state = 'backtick';
                $buffer .= $ch;
                $i++;
                continue;
            }

            if ($ch === '/' && $next === '*') {
                $hasExecutableContent = true;
                $state = 'block_comment';
                $skipBlockComment = false;
                $buffer .= '/*';
                $i += 2;
                continue;
            }

            if ($ch === '-' && $next === '-') {
                $after = $i + 2 < $length ? $sql[$i + 2] : '';
                if ($after === '' || $after === ' ' || $after === "\t" || $after === "\r" || $after === "\n") {
                    $state = 'line_comment';
                    $buffer .= '--';
                    $i += 2;
                    continue;
                }
            }

            $hasExecutableContent = true;
            $buffer .= $ch;
            $i++;
        }

        if ($hasExecutableContent) {
            $trimmed = trim($buffer);
            if ($trimmed !== '') {
                $statements[] = $trimmed;
            }
        }

        return $statements;
    }
}
