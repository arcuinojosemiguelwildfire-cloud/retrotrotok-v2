# RetroTok Cloud Backend — Local Setup Guide

## Prerequisites
- **PHP:** 8.1, 8.2, or 8.3 with extensions: `pdo`, `pdo_mysql`, `curl`, `openssl`, `json`, `mbstring` (plus `pdo_sqlite` for local in-memory tests).
- **Database:** MySQL 8.0+ or MariaDB 10.6+ for migration/API testing (SQLite in-memory is used automatically by the automated integration test suite).
- **Optional:** Composer 2.x (if running PHPUnit; a built-in PSR-4 autoloader and standalone PHP test runner work even without Composer).

## 1. Configure Environment
```bash
cd backend
cp .env.example .env
```
Edit `.env` for local development:
- Set `APP_ENV=local` and `REQUIRE_HTTPS=false` for localhost HTTP testing.
- Generate random tokens:
  ```bash
  php -r "echo 'ADMIN_API_TOKEN=' . bin2hex(random_bytes(32)) . PHP_EOL;"
  php -r "echo 'QR_TOKEN_PEPPER=' . bin2hex(random_bytes(32)) . PHP_EOL;"
  php -r "echo 'DEVICE_PAIRING_SECRET=' . bin2hex(random_bytes(24)) . PHP_EOL;"
  ```
- Keep `R2_*` variables as placeholders until you are testing with a real Cloudflare R2 staging bucket. While placeholders remain, `CloudflareR2Adapter` intentionally refuses presigning and returns `503 OBJECT_STORAGE_NOT_CONFIGURED`.

## 2. Run MySQL Migrations
Create the database (`CREATE DATABASE retrotok_cloud CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;`) and run:
```bash
php bin/migrate.php
```
This applies `000` through `004` in order and records SHA-256 checksums in `schema_migrations`.

## 3. Run Local PHP Server
Point PHP's built-in server to `public/` (never the parent `backend/` directory):
```bash
php -S 127.0.0.1:8080 -t public
```
Verify readiness:
```bash
curl -s http://127.0.0.1:8080/api/v1/health
```

## 4. Run Automated Tests
### Option A — Standalone PHP Test Suite (No Composer/MySQL Required)
```bash
php tests/run_php_tests.php
```
### Option B — PHPUnit (with Composer)
```bash
composer install
composer test
```
### Option C — Structural, SQL & Security Check (Python 3)
```bash
python3 tests/verify_scaffolding.py
```
