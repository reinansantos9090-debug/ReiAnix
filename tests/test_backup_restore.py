from __future__ import annotations

import asyncio
import json
import os
import shutil
import sqlite3
import tempfile
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from core.backup import BackupError, BackupMigrationRegistry, BackupService, BackupValidationError
from core.diagnostic_service import DiagnosticsService
from core.library_store import LibraryStore
from core.scan_coordinator import ScanCoordinator, ScanOrigin
from core.settings import SettingsStore


class BackupRestoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.store = LibraryStore(str(self.root / "data"))
        self.settings = SettingsStore(self.store)
        self.video = self.root / "Anime" / "Ação" / "Episode03.mkv"
        self.video.parent.mkdir(parents=True, exist_ok=True)
        self.video.write_bytes(b"local-video-payload")
        self.art = Path(self.store.cache_dir) / "artwork" / "manual-source.jpg"
        self.art.parent.mkdir(parents=True, exist_ok=True)
        self.art.write_bytes(b"fake-image-for-fixture")
        self._seed()

    def tearDown(self):
        self.temp.cleanup()

    def _seed(self):
        now = 1_800_000_000.0
        with self.store._conn() as con:
            anime_id = con.execute(
                """INSERT INTO anime(
                    lookup_title,anilist_id,title,romaji,english,native,aliases,description,
                    cover_url,cover_cache,banner_url,genres,year,season,status,episodes_count,
                    duration,score,format,studio,metadata_updated_at,metadata_fetched_at,
                    metadata_source,metadata_confidence,metadata_status,metadata_manual_fields,
                    anilist_match_status,anilist_match_score,anilist_match_margin,anilist_match_manual,
                    favorite,user_tags,media_kind,is_pinned,personal_note,added_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    "acao", 12345, "Ação", "Acao", "Action", "アクション", '["ドラマ"]',
                    "Descrição Unicode ação 漢字", "https://example.invalid/poster.jpg",
                    str(self.art), "", '["Action","Drama"]', 2025, "winter", "finished", 3,
                    24, 90, "TV", "Studio", now, now, "anilist", "high", "available", "[]",
                    "matched", 0.97, 0.2, 1, 1, '["Ação","ドラマ"]', "series", 1,
                    "nota: ação especial — keep progress", now,
                ),
            ).lastrowid
            episode_id = con.execute(
                """INSERT INTO episodes(
                    anime_id,path,file_name,season,number,duration,progress,watched,mime_type,
                    file_size,modified_at,source_folder,absolute_number,relative_path,volume_id,
                    volume_uuid,episode_type,episode_title,identification_source,
                    identification_confidence,manual_override,missing,last_played_at,
                    media_identity,availability_state
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    anime_id, str(self.video), self.video.name, 1, 3, 1200.0, 600.0, 0,
                    "video/x-matroska", self.video.stat().st_size, self.video.stat().st_mtime,
                    str(self.video.parent), 3, "Ação/Episode03.mkv", "primary", "uuid-1",
                    "regular", "Ep 03", "parser", "high", 0, 0, now,
                    "stable-episode-03", "available",
                ),
            ).lastrowid
            con.execute(
                """INSERT INTO folders(path,name,kind,authorization,account_id,added_at,saf_authority,
                   saf_document_id,saf_volume_id,saf_identity)
                   VALUES (?,?,?,?,?,?,?,?,?,?)""",
                ("content://sensitive-tree", "Animes", "saf", "granted", "google-secret-id",
                 now, "com.android.documents", "doc", "vol", "saf-stable"),
            )
            con.execute(
                "INSERT INTO account(key,value) VALUES (?,?)",
                ("email", "user@example.com"),
            )
            con.execute(
                "INSERT INTO account(key,value) VALUES (?,?)",
                ("id", "google-sub-secret"),
            )
            con.execute(
                "INSERT INTO preferences(key,value,updated_at) VALUES (?,?,?)",
                ("appearance.theme", "light", now),
            )
            con.execute(
                "INSERT INTO preferences(key,value,updated_at) VALUES (?,?,?)",
                ("native_event_ids", json.dumps(["secret-native-event"]), now),
            )
            con.execute(
                "INSERT INTO preferences(key,value,updated_at) VALUES (?,?,?)",
                ("unknown.future.secret", "do-not-export", now),
            )
            con.execute(
                "INSERT INTO genres(id,canonical_name,normalized_name,source,is_system,is_custom,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
                ("genre-1", "Ação", "acao", "local", 0, 1, now, now),
            )
            con.execute(
                "INSERT INTO genre_aliases(id,genre_id,alias,normalized_alias,source,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
                (1, "genre-1", "ドラマ", "ドラマ", "local", now, now),
            )
            con.execute(
                "INSERT INTO anime_genres(anime_id,genre_id,source,created_at,updated_at) VALUES (?,?,?,?,?)",
                (anime_id, "genre-1", "local", now, now),
            )
            con.execute(
                """INSERT INTO artwork(
                    entity_type,entity_id,artwork_type,source,source_ref,local_path,external_url,
                    manual,priority,status,discovered_at,updated_at,failure_count,artwork_key,
                    variant,byte_size,width,height,checksum,content_type,last_access,next_retry_at,http_status
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    "anime", str(anime_id), "poster", "manual", str(self.art), str(self.art),
                    None, 1, 500, "ready", now, now, 0, "manual-fixture-key", "large",
                    self.art.stat().st_size, 100, 100, "fixture-checksum", "image/jpeg",
                    now, None, 200,
                ),
            )
            con.execute(
                """INSERT INTO episode_observations(
                    episode_id,source_kind,scope_kind,scope_ref,uri,volume_id,native_generation,
                    fingerprint,first_seen,last_seen,last_checked_at,state,error
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    episode_id, "saf", "source", "content://sensitive-tree", str(self.video),
                    "primary", 1, "fingerprint-1", now, now, now, "available", None,
                ),
            )
            con.execute(
                "INSERT INTO associations(lookup_title,anilist_id) VALUES (?,?)",
                ("acao", 12345),
            )
            con.execute(
                "INSERT INTO scan_runs(started_at,finished_at,status,episodes,scan_id) VALUES (?,?,?,?,?)",
                (now - 5, now, "completed", 1, "scan-fixture"),
            )

    def _backup(self) -> tuple[BackupService, bytes, dict]:
        service = BackupService(self.store, self.settings)
        raw = service.create_backup_bytes()
        preview = service.inspect_bytes(raw)
        return service, raw, preview

    def test_backup_is_versioned_hashed_and_excludes_auth_secrets_and_videos(self):
        service, raw, preview = self._backup()
        self.assertEqual("rei-flix-backup-v1", preview["format"])
        self.assertEqual(1, preview["format_version"])
        self.assertEqual("SHA-256 válido", preview["integrity"])
        self.assertFalse(preview["videos_included"])
        self.assertFalse(preview["authentication_included"])

        archive_path = self.root / "backup.zip"
        archive_path.write_bytes(raw)
        with zipfile.ZipFile(archive_path) as archive:
            names = set(archive.namelist())
            self.assertIn("manifest.json", names)
            self.assertIn("library.sqlite3", names)
            self.assertFalse(any(name.endswith(".mkv") for name in names))
            with tempfile.TemporaryDirectory() as extract_root:
                archive.extract("library.sqlite3", extract_root)
                con = sqlite3.connect(Path(extract_root) / "library.sqlite3")
                try:
                    self.assertEqual(0, con.execute("SELECT COUNT(*) FROM account").fetchone()[0])
                    keys = {row[0] for row in con.execute("SELECT key FROM preferences")}
                    self.assertNotIn("native_event_ids", keys)
                    self.assertNotIn("unknown.future.secret", keys)
                    self.assertEqual(1, con.execute("SELECT COUNT(*) FROM anime").fetchone()[0])
                    self.assertEqual(1, con.execute("SELECT COUNT(*) FROM episodes").fetchone()[0])
                    self.assertEqual(1, con.execute("SELECT COUNT(*) FROM genres").fetchone()[0])
                    self.assertIsNone(con.execute("SELECT account_id FROM folders").fetchone()[0])
                finally:
                    con.close()

    def test_restore_preserves_logical_state_and_marks_missing_without_deleting(self):
        service, raw, _ = self._backup()
        self.video.unlink()
        with self.store._conn() as con:
            row_before = con.execute("SELECT id,anime_id,progress,watched,missing,media_identity FROM episodes").fetchone()
            self.assertEqual(600.0, row_before["progress"])
            con.execute("UPDATE anime SET title='estado-local-modificado',favorite=0,is_pinned=0,personal_note='alterado'")
        service.restore_bytes(raw)
        with self.store._conn() as con:
            anime = con.execute("SELECT * FROM anime").fetchone()
            episode = con.execute("SELECT * FROM episodes").fetchone()
            self.assertEqual(1, anime["favorite"])
            self.assertEqual(1, anime["is_pinned"])
            self.assertEqual("nota: ação especial — keep progress", anime["personal_note"])
            self.assertEqual(600.0, episode["progress"])
            self.assertEqual(0, episode["watched"])
            self.assertTrue(episode["missing"])
            self.assertEqual("missing", episode["availability_state"])
            self.assertEqual("stable-episode-03", episode["media_identity"])
            self.assertEqual("Ação", con.execute("SELECT canonical_name FROM genres").fetchone()[0])
            self.assertEqual(12345, con.execute("SELECT anilist_id FROM anime").fetchone()[0])

    def test_restore_restores_supported_settings(self):
        self.settings.set("appearance.theme", "light")
        self.settings.set("player.default_speed", 2.0)
        self.settings.set("audio.preferred_language", "en")
        self.settings.set("audio.preferred_subtitle_language", "pt-BR")
        service, raw, _ = self._backup()

        self.settings.set("appearance.theme", "dark")
        self.settings.set("player.default_speed", 0.5)
        self.settings.set("audio.preferred_language", "")
        self.settings.set("audio.preferred_subtitle_language", "")
        service.restore_bytes(raw)

        self.assertEqual("light", self.settings.get("appearance.theme"))
        self.assertEqual(2.0, self.settings.get("player.default_speed"))
        self.assertEqual("en", self.settings.get("audio.preferred_language"))
        self.assertEqual("pt-BR", self.settings.get("audio.preferred_subtitle_language"))

    def test_pre_restore_snapshot_is_created(self):
        service, raw, _ = self._backup()
        service.restore_bytes(raw)
        backups = sorted(Path(self.store.backup_dir).glob("pre-restore-*.zip"))
        self.assertTrue(backups)
        self.assertGreater(backups[-1].stat().st_size, 0)

    def test_restore_does_not_overwrite_current_authentication(self):
        service, raw, _ = self._backup()
        self.store.clear_account()
        self.store.save_account({"id": "current-install-id", "email": "current@example.com"})
        service.restore_bytes(raw)
        self.assertEqual(
            {"id": "current-install-id", "email": "current@example.com"},
            self.store.account(),
        )

    def test_corrupted_backup_is_rejected_before_database_change(self):
        service, raw, _ = self._backup()
        with self.store._conn() as con:
            con.execute("UPDATE anime SET title='before-corruption'")
        with zipfile.ZipFile(self.root / "clean.zip", "w", zipfile.ZIP_DEFLATED) as out:
            with zipfile.ZipFile(__import__("io").BytesIO(raw), "r") as source:
                for info in source.infolist():
                    payload = source.read(info.filename)
                    if info.filename == "library.sqlite3":
                        payload = bytes([payload[0] ^ 0x01]) + payload[1:]
                    out.writestr(info, payload)
        with self.assertRaises(BackupValidationError) as ctx:
            service.restore_file(self.root / "clean.zip")
        self.assertEqual("BACKUP_CHECKSUM_MISMATCH", ctx.exception.code)
        self.assertEqual("before-corruption", self.store.anime_metadata("acao")["title"])

    def test_truncated_backup_is_rejected_with_controlled_error(self):
        service, raw, _ = self._backup()
        truncated = raw[:-32]
        with self.assertRaises(BackupValidationError) as ctx:
            service.inspect_bytes(truncated)
        self.assertEqual("BACKUP_INVALID", ctx.exception.code)

    def test_restore_rollback_preserves_previous_state_on_transaction_failure(self):
        service, raw, _ = self._backup()
        with self.store._conn() as con:
            con.execute("UPDATE anime SET title='state-before-rollback'")
        original = self.store._reconcile_restored_files_locked
        self.store._reconcile_restored_files_locked = lambda con: (_ for _ in ()).throw(RuntimeError("forced failure"))
        try:
            with self.assertRaises(Exception) as ctx:
                service.restore_bytes(raw)
            self.assertEqual("RESTORE_TRANSACTION_FAILED", getattr(ctx.exception, "code", None))
        finally:
            self.store._reconcile_restored_files_locked = original
        self.assertEqual("state-before-rollback", self.store.anime_metadata("acao")["title"])

    def test_restore_is_idempotent(self):
        service, raw, _ = self._backup()
        service.restore_bytes(raw)
        first = self.store.library_summary()
        service.restore_bytes(raw)
        second = self.store.library_summary()
        self.assertEqual(first, second)
        with self.store._conn() as con:
            self.assertEqual(1, con.execute("SELECT COUNT(*) FROM anime").fetchone()[0])
            self.assertEqual(1, con.execute("SELECT COUNT(*) FROM episodes").fetchone()[0])
            self.assertEqual(1, con.execute("SELECT COUNT(*) FROM artwork").fetchone()[0])
            self.assertEqual(1, con.execute("SELECT COUNT(*) FROM anime_genres").fetchone()[0])

    def test_scan_coordinator_blocks_new_scans_during_restore_gate(self):
        coordinator = ScanCoordinator(object(), self.store, lambda _source, _scope: ())
        self.assertTrue(asyncio.run(coordinator.begin_exclusive("restore")))
        try:
            transition = asyncio.run(coordinator.request(ScanOrigin.USER_REFRESH))
            self.assertTrue(transition.accepted)
            self.assertEqual("blocked", transition.kind)
            self.assertIn("exclusive_operation:restore", transition.message)
        finally:
            asyncio.run(coordinator.end_exclusive())
        transition = asyncio.run(coordinator.request(ScanOrigin.USER_REFRESH))
        self.assertEqual("blocked", transition.kind)

    def test_restore_reconciliation_is_distinct_from_startup_or_full_rescan(self):
        self.assertEqual("RESTORE_RECONCILIATION", ScanOrigin.RESTORE_RECONCILIATION.value)
        self.assertNotEqual(ScanOrigin.RESTORE_RECONCILIATION, ScanOrigin.STARTUP)
        self.assertNotEqual(ScanOrigin.RESTORE_RECONCILIATION, ScanOrigin.EXPLICIT_FULL_RESCAN)

    def test_future_backup_version_is_rejected_and_migration_registry_is_explicit(self):
        self.assertTrue(BackupMigrationRegistry.can_migrate(1, 1))
        self.assertFalse(BackupMigrationRegistry.can_migrate(1, 999))
        with self.assertRaises(BackupValidationError) as ctx:
            BackupMigrationRegistry.migrate_manifest({"format_version": 1}, 2)
        self.assertEqual("BACKUP_UNSUPPORTED_VERSION", ctx.exception.code)

    def test_diagnostic_export_redacts_storage_references(self):
        report = DiagnosticsService(self.store).report(
            storage_snapshot={
                "safRoots": ["content://com.android.documents/tree/private-user"],
                "removableVolumes": [{"state": "mounted", "uuid": "private-volume"}],
            },
            scan_snapshot={"volume": "/storage/emulated/0/private", "state": "COMPLETED"},
        )
        raw = json.dumps(report, ensure_ascii=False)
        self.assertNotIn("private-user", raw)
        self.assertNotIn("/storage/emulated/0/private", raw)
        self.assertIn("paths_redacted", raw)

    def test_diagnostics_detects_orphan_and_bad_progress(self):
        with self.store._conn() as con:
            con.execute(
                """INSERT INTO artwork(
                    entity_type,entity_id,artwork_type,source,source_ref,local_path,manual,priority,
                    status,discovered_at,updated_at,artwork_key,variant
                ) VALUES ('anime','99999','poster','local','orphan',NULL,0,0,'ready',1,1,'orphan-key','large')"""
            )
            con.execute("UPDATE episodes SET progress=5000,duration=100")
        report = DiagnosticsService(self.store).report()
        self.assertNotEqual("OK", report["overall"])
        self.assertGreater(report["orphans"]["artwork_missing_anime"], 0)
        self.assertGreater(report["consumption"]["invalid_progress_rows"], 0)
        self.assertEqual("ok", report["database"]["integrity_check"].casefold())
        self.assertTrue(report["privacy"]["secrets_exported"] is False)
        self.assertTrue(report["privacy"]["device_identifiers_exported"] is False)

    def test_manifest_tampering_is_rejected_without_touching_live_database(self):
        service, raw, _ = self._backup()
        archive_in = zipfile.ZipFile(__import__("io").BytesIO(raw), "r")
        tampered = self.root / "manifest-tampered.zip"
        manifest = json.loads(archive_in.read("manifest.json").decode("utf-8"))
        manifest["app_version"] = "tampered"
        with zipfile.ZipFile(tampered, "w", zipfile.ZIP_DEFLATED) as out:
            for info in archive_in.infolist():
                payload = archive_in.read(info.filename)
                if info.filename == "manifest.json":
                    payload = (json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")
                out.writestr(info, payload)
        archive_in.close()
        with self.store._conn() as con:
            con.execute("UPDATE anime SET title='before-manifest-tamper'")
        with self.assertRaises(BackupValidationError) as ctx:
            service.inspect_file(tampered)
        self.assertEqual("BACKUP_CHECKSUM_MISMATCH", ctx.exception.code)
        self.assertEqual("before-manifest-tamper", self.store.anime_metadata("acao")["title"])

    def test_corrupted_optional_artwork_is_reported_but_library_remains_recoverable(self):
        service, raw, _ = self._backup()
        source = zipfile.ZipFile(__import__("io").BytesIO(raw), "r")
        corrupted = self.root / "artwork-corrupted.zip"
        corrupted_member = "artwork/manual/" + self.art.read_bytes().hex()[:64] + ".jpg"
        artwork_members = [name for name in source.namelist() if name.startswith("artwork/manual/")]
        self.assertTrue(artwork_members)
        corrupted_member = artwork_members[0]
        with zipfile.ZipFile(corrupted, "w", zipfile.ZIP_DEFLATED) as out:
            for info in source.infolist():
                payload = source.read(info.filename)
                if info.filename == corrupted_member:
                    payload = b"corrupted-artwork-payload"
                out.writestr(info, payload)
        source.close()
        preview = service.inspect_file(corrupted)
        self.assertIn(corrupted_member, preview["integrity_warnings"])
        result = service.restore_file(corrupted)
        self.assertEqual(0, result["report"]["manual_artwork_reconnected"])
        with self.store._conn() as con:
            self.assertEqual(1, con.execute("SELECT COUNT(*) FROM anime").fetchone()[0])
            self.assertEqual(1, con.execute("SELECT COUNT(*) FROM episodes").fetchone()[0])

    def test_backup_fails_cleanly_when_estimated_space_is_insufficient(self):
        service = BackupService(self.store, self.settings)
        with mock.patch("core.backup.shutil.disk_usage", return_value=SimpleNamespace(free=0)):
            with self.assertRaises(BackupError) as ctx:
                service.create_backup_bytes()
        self.assertEqual("BACKUP_INSUFFICIENT_SPACE", ctx.exception.code)
        self.assertFalse(list((self.root / "data" / "backups").glob("*.tmp")))

    def test_large_snapshot_with_1000_episodes(self):
        now = 1_800_100_000.0
        with self.store._conn() as con:
            anime_id = con.execute(
                "INSERT INTO anime(lookup_title,title,aliases,metadata_manual_fields,genres,user_tags,added_at) VALUES (?,?,?,?,?,?,?)",
                ("large-fixture", "Large Fixture", "[]", "[]", "[]", "[]", now),
            ).lastrowid
            rows = []
            for number in range(1, 1001):
                rows.append((
                    anime_id,
                    str(self.root / f"Large{number:04d}.mkv"),
                    f"Large{number:04d}.mkv",
                    1, number, 100.0, float(number % 60), 0,
                    "video/x-matroska", 1, now, str(self.root),
                    number, f"Large{number:04d}.mkv", "large-vol", "uuid-large",
                    "regular", None, "fixture", "medium", 0, 1, None,
                    f"large-media-{number}", "missing",
                ))
            con.executemany(
                """INSERT INTO episodes(
                    anime_id,path,file_name,season,number,duration,progress,watched,mime_type,
                    file_size,modified_at,source_folder,absolute_number,relative_path,volume_id,
                    volume_uuid,episode_type,episode_title,identification_source,
                    identification_confidence,manual_override,missing,last_played_at,
                    media_identity,availability_state
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                rows,
            )
        service, raw, preview = self._backup()
        self.assertEqual(1001, preview["counts"]["episodes"])
        self.assertGreater(len(raw), 0)


if __name__ == "__main__":
    unittest.main()
