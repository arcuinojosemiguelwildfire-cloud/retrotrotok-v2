<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Database;

use PDO;
use PDOException;
use RetroTok\Cloud\Config\Config;
use RetroTok\Cloud\Exception\ServiceUnavailableException;

final class ConnectionFactory
{
    public static function create(Config $config): PDO
    {
        if (!$config->isDatabaseConfigured()) {
            throw new ServiceUnavailableException(
                'Database credentials are not configured.',
                'DATABASE_NOT_CONFIGURED'
            );
        }

        $options = [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES => false,
            PDO::ATTR_STRINGIFY_FETCHES => false,
        ];

        try {
            if ($config->dbDriver === 'sqlite') {
                $pdo = new PDO('sqlite:' . $config->dbDatabase, null, null, $options);
                $pdo->exec('PRAGMA foreign_keys = ON;');
                return $pdo;
            }

            $dsn = sprintf(
                'mysql:host=%s;port=%d;dbname=%s;charset=%s',
                $config->dbHost,
                $config->dbPort,
                $config->dbDatabase,
                $config->dbCharset
            );

            $pdo = new PDO($dsn, $config->dbUsername, $config->dbPassword, $options);
            $pdo->exec("SET time_zone = '+00:00';");
            return $pdo;
        } catch (PDOException) {
            // Never leak DSN, host, username, or password in exception messages.
            throw new ServiceUnavailableException('Unable to connect to database.', 'DATABASE_CONNECTION_FAILED');
        }
    }
}
