# Hostinger Verification & Deployment Checklist (RetroTok Cloud)

Do **not** deploy to production hosting until every item below is verified against your Hostinger subscription and Cloudflare R2 bucket.

## 1. Hostinger Plan, PHP Version & Extensions
- [ ] Confirm Hostinger plan name and document root path (e.g., `domains/your-domain.com/public_html`).
- [ ] Verify PHP version is **8.1, 8.2, or 8.3** in Hostinger hPanel → *PHP Configuration*.
- [ ] Verify required PHP extensions are enabled:
  - `pdo`
  - `pdo_mysql`
  - `curl` (with TLS 1.2+ support for Cloudflare R2)
  - `openssl`
  - `json`
  - `mbstring`
  - `simplexml` / `libxml` (used for admin R2 `ListObjectsV2` reconciliation)
- [ ] Verify CLI PHP path (`/usr/bin/php -v`) matches the web PHP version before configuring Cron jobs.

## 2. Composer or Autoloader Fallback
- [ ] Check whether SSH and `composer` are available on your Hostinger plan.
- [ ] If SSH/Composer is available, run `composer install --no-dev --optimize-autoloader` inside `backend/`.
- [ ] If Composer is unavailable (ordinary shared hosting), upload `backend/src/` as-is; `backend/src/Support/Autoloader.php` and `backend/src/Storage/SigV4Signer.php` operate with zero Composer runtime dependencies.

## 3. MySQL Version & Configuration
- [ ] Confirm MySQL **8.0+** or MariaDB **10.6+** in Hostinger hPanel → *Databases*.
- [ ] Create a dedicated database (`utf8mb4` / `utf8mb4_unicode_ci`) and least-privilege user (`SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES`).
- [ ] Populate `DB_HOST`, `DB_PORT`, `DB_DATABASE`, `DB_USERNAME`, `DB_PASSWORD`, `DB_CHARSET=utf8mb4` in `/backend/.env`.
- [ ] Verify InnoDB is the default storage engine (required for `FOREIGN KEY` and `SELECT ... FOR UPDATE` row locking).
- [ ] Apply migrations `000`–`004` in order:
  - Via SSH: `php backend/bin/migrate.php`
  - Via phpMyAdmin (no SSH): import `000`–`004` sequentially and record each filename + SHA-256 checksum in `schema_migrations`.

## 4. Apache Rewrite, HTTPS & Authorization Header Passthrough
- [ ] Point your API/QR subdomain's web root directly to `/backend/public/` (keep `.env`, `src/`, `database/`, `bin/`, and `tests/` **outside** `public_html` or web root).
- [ ] Provision a valid SSL certificate (Let's Encrypt / Cloudflare SSL) and enable Force HTTPS in Hostinger hPanel.
- [ ] Set `APP_ENV=production` and `REQUIRE_HTTPS=true` in `.env`.
- [ ] Confirm `mod_rewrite` is active and `/backend/public/.htaccess` passes `Authorization: Bearer ...` headers through PHP-FPM/FastCGI (`HTTP_AUTHORIZATION` / `REDIRECT_HTTP_AUTHORIZATION`).
- [ ] Verify direct HTTP requests to `/.env`, `/src/`, `/database/`, and `/composer.json` return `403` or `404`.
- [ ] Verify TLS 1.2 compatibility on Android 6.0.1 (`ZC-328-20190823`) against both your Hostinger domain and Cloudflare R2 endpoint before starting Android Cloud Phase 2.

## 5. Environment Variables & Secrets
- [ ] Copy `/backend/.env.example` to `/backend/.env` (`chmod 600 .env`).
- [ ] Generate unique high-entropy secrets (`>= 32` random bytes):
  - `ADMIN_API_TOKEN`
  - `QR_TOKEN_PEPPER`
  - `DEVICE_PAIRING_SECRET`
- [ ] Ensure `APP_DEBUG=false` in production so internal exceptions never leak configuration or paths.

## 6. Private Cloudflare R2 Bucket Setup
- [ ] Create a **private** Cloudflare R2 bucket (disable `r2.dev` public access and disable public bucket listing).
- [ ] Create a scoped R2 API Token limited strictly to Object Read & Write on the RetroTok bucket.
- [ ] Configure R2 CORS policy on the bucket if browser direct uploads are ever used (not required for Android `OkHttp` `PUT` + `<video>` `GET`).
- [ ] Set `R2_ACCOUNT_ID`, `R2_BUCKET`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_ENDPOINT` (`https://<ACCOUNT_ID>.r2.cloudflarestorage.com`), `R2_REGION=auto`, `R2_PATH_STYLE=true` in `/backend/.env`.
- [ ] Configure a secondary 32-day R2 object lifecycle expiration rule as defense-in-depth behind `bin/cleanup_expired.php`.

## 7. Cron Configuration
- [ ] Configure a daily Hostinger Cron Job in hPanel → *Cron Jobs*:
  ```bash
  0 3 * * * /usr/bin/php /home/USER/retrotok/backend/bin/cleanup_expired.php >> /home/USER/retrotok/logs/retention.log 2>&1
  ```
- [ ] Confirm `logs/` is outside `public/` and rotated periodically.

## 8. Backup & Rollback Procedures
- [ ] **Pre-Deployment Database Backup:** Before running migrations or backend updates, take a full logical dump:
  ```bash
  mysqldump --single-transaction --routines=false -h "$DB_HOST" -u "$DB_USERNAME" -p"$DB_PASSWORD" "$DB_DATABASE" > retrotok_pre_deploy_$(date -u +%Y%m%dT%H%M%SZ).sql
  ```
- [ ] **Configuration Backup:** Keep an encrypted offline copy of `.env` and `kiosk_devices` pairing records (do not store `.env` inside git or `public/`).
- [ ] **Release Artifacts:** Keep the previous known-good `/backend/` directory archived (`backend_prev/`) outside web root for instant symlink/folder swap rollback.
- [ ] **Rollback Procedure (Code):** Restore `backend_prev/` and switch the subdomain pointer or symlink back to the previous release, then verify `GET /api/v1/health`.
- [ ] **Rollback Procedure (Schema):** Migrations `000`–`004` are additive only; if a fresh Phase 1 deploy must be reverted before production traffic, restore from the pre-deployment `mysqldump` snapshot rather than running ad-hoc `DROP TABLE` commands on active event data.
- [ ] **R2 Object Recovery:** Never enable automatic deletion of `PENDING_UPLOAD` records; failed retention deletions remain eligible for retry on the next `cleanup_expired.php` run, and `GET /api/v1/admin/events/{eventId}/reconcile` detects any mismatched DB/R2 keys before manual intervention.
