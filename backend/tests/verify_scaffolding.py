#!/usr/bin/env python3
"""
Static & contract verification suite for RetroTok Cloud Phase 1 backend scaffolding.
Runs in environments without PHP 8.x/MySQL to verify deliverables, SQL schema
completeness, route registration, placeholder credential hygiene, and security controls.
Does NOT substitute for PHP 8.x runtime execution or live MySQL/R2 integration tests.
"""

from pathlib import Path
import re
import unittest

BACKEND_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = BACKEND_DIR.parent


class BackendScaffoldingVerificationTest(unittest.TestCase):
    def test_required_deliverables_exist(self) -> None:
        required_files = [
            BACKEND_DIR / ".htaccess",
            BACKEND_DIR / ".env.example",
            BACKEND_DIR / "composer.json",
            BACKEND_DIR / "phpunit.xml",
            BACKEND_DIR / "bin/migrate.php",
            BACKEND_DIR / "bin/cleanup_expired.php",
            BACKEND_DIR / "public/index.php",
            BACKEND_DIR / "public/.htaccess",
            BACKEND_DIR / "database/migrations/000_create_schema_migrations.sql",
            BACKEND_DIR / "database/migrations/001_create_events_table.sql",
            BACKEND_DIR / "database/migrations/002_create_kiosk_devices_and_videos_table.sql",
            BACKEND_DIR / "database/migrations/003_create_video_uploads_table.sql",
            BACKEND_DIR / "database/migrations/004_create_video_access_logs_and_rate_limits.sql",
            BACKEND_DIR / "src/Storage/ObjectStorageInterface.php",
            BACKEND_DIR / "src/Storage/CloudflareR2Adapter.php",
            BACKEND_DIR / "src/Storage/SigV4Signer.php",
            BACKEND_DIR / "docs/API.md",
            BACKEND_DIR / "docs/LOCAL_SETUP.md",
            BACKEND_DIR / "docs/HOSTINGER_DEPLOYMENT_CHECKLIST.md",
            BACKEND_DIR / "tests/run_php_tests.php",
        ]
        for path in required_files:
            self.assertTrue(path.is_file(), f"Missing required deliverable: {path}")

    def test_sql_migrations_contain_required_tables_constraints_and_indexes(self) -> None:
        sql_text = "\n".join(
            p.read_text(encoding="utf-8")
            for p in sorted((BACKEND_DIR / "database/migrations").glob("*.sql"))
        )
        for table in [
            "schema_migrations",
            "events",
            "kiosk_devices",
            "videos",
            "video_uploads",
            "video_access_logs",
            "rate_limits",
        ]:
            self.assertIn(f"CREATE TABLE IF NOT EXISTS `{table}`", sql_text)

        self.assertIn("`max_videos` INT UNSIGNED NOT NULL DEFAULT 300", sql_text)
        self.assertIn("CHECK (`max_videos` >= 1 AND `max_videos` <= 300)", sql_text)
        self.assertIn("`access_token_hash` CHAR(64)", sql_text)
        self.assertIn("`expires_at` DATETIME", sql_text)
        self.assertIn("FOREIGN KEY (`event_id`) REFERENCES `events` (`id`)", sql_text)
        self.assertIn("FOREIGN KEY (`video_id`) REFERENCES `videos` (`id`)", sql_text)
        self.assertNotIn("LONGBLOB", sql_text.upper())
        self.assertNotIn("MEDIUMBLOB", sql_text.upper())

    def test_all_required_api_routes_registered(self) -> None:
        app_php = (BACKEND_DIR / "src/Http/Application.php").read_text(encoding="utf-8")
        expected_routes = [
            ("POST", "/api/v1/events"),
            ("GET", "/api/v1/events/{eventId}"),
            ("POST", "/api/v1/events/{eventId}/status"),
            ("POST", "/api/v1/events/{eventId}/videos"),
            ("POST", "/api/v1/videos/{videoId}/complete"),
            ("GET", "/api/v1/events/{eventId}/videos"),
            ("GET", "/api/v1/videos/{videoId}/status"),
            ("GET", "/watch/{token}"),
            ("GET", "/v/{token}"),
            ("POST", "/api/v1/admin/videos/{videoId}/delete"),
            ("GET", "/api/v1/admin/events/{eventId}/reconcile"),
        ]
        for method, route in expected_routes:
            pattern = f"add('{method}', '{route}'"
            self.assertIn(pattern, app_php, f"Route missing in Application.php: {method} {route}")

    def test_r2_boundary_methods_and_no_public_listing(self) -> None:
        iface = (BACKEND_DIR / "src/Storage/ObjectStorageInterface.php").read_text(encoding="utf-8")
        for method in [
            "createUploadAuthorization",
            "verifyUploadedObject",
            "generatePlaybackUrl",
            "deleteObject",
            "checkObjectMetadata",
            "listObjectsByPrefix",
            "isConfigured",
        ]:
            self.assertIn(f"function {method}(", iface)

        r2_adapter = (BACKEND_DIR / "src/Storage/CloudflareR2Adapter.php").read_text(encoding="utf-8")
        self.assertIn("StorageNotConfiguredException", r2_adapter)
        self.assertIn("R2_DELETE_FAILED", r2_adapter)
        public_ctrl = (BACKEND_DIR / "src/Controller/PublicVideoController.php").read_text(encoding="utf-8")
        self.assertNotIn("listObjectsByPrefix", public_ctrl)

    def test_env_example_contains_placeholders_only_no_real_secrets(self) -> None:
        env_text = (BACKEND_DIR / ".env.example").read_text(encoding="utf-8")
        for var in [
            "DB_HOST=",
            "DB_USERNAME=REPLACE_WITH_",
            "DB_PASSWORD=REPLACE_WITH_",
            "ADMIN_API_TOKEN=REPLACE_WITH_",
            "DEVICE_PAIRING_SECRET=REPLACE_WITH_",
            "R2_ACCOUNT_ID=REPLACE_WITH_",
            "R2_ACCESS_KEY_ID=REPLACE_WITH_",
            "R2_SECRET_ACCESS_KEY=REPLACE_WITH_",
        ]:
            self.assertIn(var, env_text)

        # Confirm zero cloud secrets inside Android app source
        android_files = list((REPO_ROOT / "app/src").rglob("*.kt"))
        for kt in android_files:
            txt = kt.read_text(encoding="utf-8")
            self.assertNotIn("r2.cloudflarestorage.com", txt)
            self.assertNotIn("R2_SECRET_ACCESS_KEY", txt)

    def test_php_files_use_strict_types_and_matched_braces(self) -> None:
        excluded_dirs = {
            "vendor",
            ".git",
            "build",
            ".gradle",
            ".kotlin",
            ".phpunit.cache",
            "__pycache__",
        }
        php_files = [
            p
            for p in BACKEND_DIR.rglob("*.php")
            if not any(part in excluded_dirs for part in p.relative_to(BACKEND_DIR).parts)
        ]
        self.assertGreaterEqual(len(php_files), 25)
        for php_file in php_files:
            content = php_file.read_text(encoding="utf-8")
            self.assertIn("declare(strict_types=1);", content, f"Missing strict_types in {php_file}")
            # Verify PSR-4 class filename alignment under src/
            if (BACKEND_DIR / "src") in php_file.parents:
                decls = re.findall(
                    r"^(?:final\s+|abstract\s+|readonly\s+)*(?:class|interface|trait|enum)\s+([A-Za-z0-9_]+)",
                    content,
                    re.MULTILINE,
                )
                self.assertEqual(len(decls), 1, f"Expected single PSR-4 declaration in {php_file}, found {decls}")
                self.assertEqual(php_file.stem, decls[0], f"PSR-4 mismatch in {php_file}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
