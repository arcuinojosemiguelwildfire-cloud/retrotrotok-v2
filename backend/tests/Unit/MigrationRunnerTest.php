<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Tests\Unit;

use PDO;
use PDOException;
use RetroTok\Cloud\Database\MigrationRunner;
use RetroTok\Cloud\Tests\Support\BaseTestCase;
use RuntimeException;

final class MigrationRunnerTest extends BaseTestCase
{
    /** @var list<string> */
    private array $tempDirs = [];

    protected function tearDown(): void
    {
        foreach ($this->tempDirs as $dir) {
            if (!is_dir($dir)) {
                continue;
            }
            foreach (glob($dir . DIRECTORY_SEPARATOR . '*') ?: [] as $file) {
                @unlink($file);
            }
            @rmdir($dir);
        }
        $this->tempDirs = [];
        parent::tearDown();
    }

    private function createTempMigrationDir(): string
    {
        $dir = sys_get_temp_dir() . DIRECTORY_SEPARATOR . 'retrotok_mig_' . bin2hex(random_bytes(6));
        if (!mkdir($dir, 0777, true) && !is_dir($dir)) {
            throw new RuntimeException("Failed creating temp migration directory: {$dir}");
        }
        $this->tempDirs[] = $dir;
        return $dir;
    }

    private function createSqlitePdo(): PDO
    {
        return new PDO('sqlite::memory:', null, null, [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES => false,
        ]);
    }

    public function testParsesMigrationWithMultipleLeadingCommentLinesFollowedByCreateTable(): void
    {
        $sql = "\xEF\xBB\xBF" . <<<SQL
-- Migration 000: Schema migration ledger table
-- Target: MySQL 8.0+ / MariaDB 10.6+ (InnoDB, utf8mb4)
-- Additional header note

/*!40101 SET NAMES utf8mb4 */;

CREATE TABLE IF NOT EXISTS `schema_migrations` (
    `version` VARCHAR(64) NOT NULL,
    `checksum_sha256` CHAR(64) NOT NULL,
    PRIMARY KEY (`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SQL;

        $statements = MigrationRunner::parseStatements($sql);
        $this->assertSame(2, count($statements));
        $this->assertSame('/*!40101 SET NAMES utf8mb4 */', $statements[0]);
        $this->assertTrue(str_starts_with($statements[1], 'CREATE TABLE IF NOT EXISTS `schema_migrations`'));
        $this->assertFalse(str_contains($statements[1], 'Migration 000'));
    }

    public function testParsesMultipleStatementsInOrderAndPreservesStringsAndInlineComments(): void
    {
        $sql = <<<'SQL'
-- First table comment
CREATE TABLE `first_table` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, -- inline comment with ; semicolon
    `label` VARCHAR(64) NOT NULL DEFAULT '-- not a comment; keep intact',
    `escaped` VARCHAR(64) NOT NULL DEFAULT 'it\'s \\; ok',
    PRIMARY KEY (`id`)
);

-- Second table comment line 1
-- Second table comment line 2
CREATE TABLE `second;table` (
    `id` BIGINT UNSIGNED NOT NULL,
    `quoted` VARCHAR(64) NOT NULL DEFAULT 'it''s still; inside string',
    `dq` VARCHAR(64) NOT NULL DEFAULT "-- double;quoted"
);
SQL;

        $statements = MigrationRunner::parseStatements($sql);
        $this->assertSame(2, count($statements));
        $this->assertStringContains('`first_table`', $statements[0]);
        $this->assertStringContains("'-- not a comment; keep intact'", $statements[0]);
        $this->assertStringContains("'it\\'s \\\\; ok'", $statements[0]);
        $this->assertStringContains('-- inline comment with ; semicolon', $statements[0]);
        $this->assertStringContains('`second;table`', $statements[1]);
        $this->assertStringContains("'it''s still; inside string'", $statements[1]);
        $this->assertStringContains('"-- double;quoted"', $statements[1]);
        $this->assertFalse(str_contains($statements[1], 'Second table comment'));
    }

    public function testSkipsEmptyStatementsAndCommentOnlySections(): void
    {
        $sql = <<<SQL
-- Comment only section 1

;
   ;
-- Comment only section 2
-- Still just a comment

CREATE TABLE `alpha` (`id` INT NOT NULL);

;
-- Trailing comment only section
-- Another trailing comment
SQL;

        $statements = MigrationRunner::parseStatements($sql);
        $this->assertSame(['CREATE TABLE `alpha` (`id` INT NOT NULL)'], $statements);
    }

    public function testParsesStatementsWithoutLeadingCommentsAndAllProjectMigrations(): void
    {
        $plain = 'CREATE TABLE `plain_table` (`id` INT NOT NULL); INSERT INTO `plain_table` VALUES (1)';
        $parsedPlain = MigrationRunner::parseStatements($plain);
        $this->assertSame([
            'CREATE TABLE `plain_table` (`id` INT NOT NULL)',
            'INSERT INTO `plain_table` VALUES (1)',
        ], $parsedPlain);

        $migrationsDir = dirname(__DIR__, 2) . '/database/migrations';
        $files = glob($migrationsDir . '/*.sql') ?: [];
        sort($files, SORT_STRING);
        $this->assertSame(5, count($files));

        $expectedCounts = [
            '000_create_schema_migrations.sql' => 1,
            '001_create_events_table.sql' => 1,
            '002_create_kiosk_devices_and_videos_table.sql' => 2,
            '003_create_video_uploads_table.sql' => 1,
            '004_create_video_access_logs_and_rate_limits.sql' => 2,
        ];

        foreach ($files as $file) {
            $name = basename($file);
            $content = file_get_contents($file);
            $this->assertTrue($content !== false);
            $stmts = MigrationRunner::parseStatements((string) $content);
            $this->assertSame($expectedCounts[$name], count($stmts), "Unexpected statement count in {$name}");
            foreach ($stmts as $stmt) {
                $this->assertTrue(str_starts_with($stmt, 'CREATE TABLE IF NOT EXISTS '), "Expected CREATE TABLE start in {$name}");
            }
        }
    }

    public function testRunnerCreatesLedgerBeforeQueryingOrdersDeterministicAndIsIdempotentOnRepeatRun(): void
    {
        $dir = $this->createTempMigrationDir();
        file_put_contents($dir . '/000_ledger.sql', <<<SQL
-- Leading comment 1
-- Leading comment 2
CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    checksum_sha256 TEXT NOT NULL,
    applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
SQL);
        file_put_contents($dir . '/002_second.sql', <<<SQL
-- Runs after 001 due to deterministic filename sort
INSERT INTO execution_log (step_name) VALUES ('second');
SQL);
        file_put_contents($dir . '/001_first.sql', <<<SQL
CREATE TABLE IF NOT EXISTS execution_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    step_name TEXT NOT NULL
);
INSERT INTO execution_log (step_name) VALUES ('first');
SQL);

        $pdo = $this->createSqlitePdo();
        $runner = new MigrationRunner($pdo, $dir);

        // First run creates schema_migrations before querying it and applies 000 -> 001 -> 002.
        $firstRun = $runner->run();
        $this->assertSame(['000_ledger.sql', '001_first.sql', '002_second.sql'], $firstRun);

        $orderRows = $pdo->query('SELECT step_name FROM execution_log ORDER BY id ASC')->fetchAll();
        $this->assertSame([
            ['step_name' => 'first'],
            ['step_name' => 'second'],
        ], $orderRows);

        // Repeat run must be a no-op with zero duplicate ledger rows.
        $secondRun = $runner->run();
        $this->assertSame([], $secondRun);

        $ledgerCount = (int) $pdo->query('SELECT COUNT(*) FROM schema_migrations')->fetchColumn();
        $this->assertSame(3, $ledgerCount);

        // Tampering with an already-applied migration fails closed on checksum verification.
        file_put_contents($dir . '/001_first.sql', "CREATE TABLE IF NOT EXISTS tampered (id INTEGER);\n");
        $this->assertThrows(RuntimeException::class, static fn () => $runner->run());
    }

    public function testRunnerPreservesPdoExceptionBehaviorOnInvalidSql(): void
    {
        $dir = $this->createTempMigrationDir();
        file_put_contents($dir . '/000_ledger.sql', <<<SQL
CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    checksum_sha256 TEXT NOT NULL,
    applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
SQL);
        file_put_contents($dir . '/001_bad.sql', "THIS IS INVALID SQL SYNTAX;\n");

        $pdo = $this->createSqlitePdo();
        $runner = new MigrationRunner($pdo, $dir);

        $this->assertThrows(PDOException::class, static fn () => $runner->run());
    }
}
