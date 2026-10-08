import os
import sqlite3
import tempfile
import unittest
from pathlib import Path

from core.library_store import LibraryStore
from core.recovery import RecoveryError, RecoveryService


class RecoveryModeTests(unittest.TestCase):
    def test_healthy_database_does_not_require_recovery(self):
        with tempfile.TemporaryDirectory() as root:
            store = LibraryStore(root)
            status = RecoveryService(store).diagnose()
            self.assertFalse(status["required"])
            self.assertEqual("ok", status["quick_check"].casefold())
            self.assertTrue(status["foreign_key_ok"])

    def test_inconsistent_database_enters_recovery_without_deleting_it(self):
        with tempfile.TemporaryDirectory() as root:
            store = LibraryStore(root)
            with sqlite3.connect(store.db_path) as con:
                con.execute("CREATE TABLE recovery_probe(id INTEGER)")
                con.execute("INSERT INTO recovery_probe VALUES (1)")
                con.commit()
            # A deliberately malformed SQLite header makes the health check fail.
            with open(store.db_path, "r+b") as handle:
                handle.seek(0)
                handle.write(b"not-a-sqlite-database")
            status = RecoveryService(store).diagnose()
            self.assertTrue(status["required"])
            self.assertTrue(os.path.isfile(store.db_path))

    def test_store_startup_exposes_recovery_error_instead_of_replacing_database(self):
        with tempfile.TemporaryDirectory() as root:
            store = LibraryStore(root)
            with open(store.db_path, "r+b") as handle:
                handle.seek(0)
                handle.write(b"not-a-sqlite-database")
            reopened = LibraryStore(root)
            self.assertTrue(reopened.recovery_error)
            self.assertTrue(os.path.isfile(reopened.db_path))
            self.assertTrue(RecoveryService(reopened).diagnose()["required"])

    def test_recovery_restore_is_explicit_and_preserves_account_when_readable(self):
        with tempfile.TemporaryDirectory() as source_root, tempfile.TemporaryDirectory() as target_root:
            source = LibraryStore(source_root)
            source.save_account({"email": "local@example.invalid", "name": "Local"})
            backup = source.create_backup()
            target = LibraryStore(target_root)
            target.save_account({"email": "keep@example.invalid", "name": "Keep"})
            service = RecoveryService(target)
            result = service.restore_backup(open(backup, "rb").read())
            self.assertTrue(result["restart_required"])
            restored = LibraryStore(target_root)
            self.assertEqual("keep@example.invalid", restored.account().get("email"))

    def test_recovery_restores_catalog_when_account_table_cannot_be_read(self):
        with tempfile.TemporaryDirectory() as source_root, tempfile.TemporaryDirectory() as root:
            source = LibraryStore(source_root)
            backup = source.create_backup()
            store = LibraryStore(root)
            with sqlite3.connect(store.db_path) as con:
                con.execute("DROP TABLE account")
                con.commit()
            result = RecoveryService(store).restore_backup(open(backup, "rb").read())
            self.assertFalse(result["authentication_preserved"])
            self.assertTrue(result["reauthentication_required"])
            reopened = LibraryStore(root)
            with sqlite3.connect(reopened.db_path) as con:
                self.assertEqual("ok", str(con.execute("PRAGMA integrity_check").fetchone()[0]).casefold())

    def test_recovery_safety_snapshot_restores_database_and_wal_shm_sidecars(self):
        with tempfile.TemporaryDirectory() as root:
            store = LibraryStore(root)
            service = RecoveryService(store)
            safety = service.create_safety_snapshot()
            with sqlite3.connect(store.db_path) as con:
                con.execute("CREATE TABLE rollback_probe(id INTEGER)")
                con.execute("INSERT INTO rollback_probe VALUES (42)")
                con.commit()
            Path(store.db_path + "-wal").write_bytes(b"live-wal")
            Path(store.db_path + "-shm").write_bytes(b"live-shm")

            service._restore_safety_snapshot(safety)

            with sqlite3.connect(store.db_path) as con:
                row = con.execute(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='rollback_probe'"
                ).fetchone()
            self.assertIsNone(row)
            self.assertFalse(os.path.exists(store.db_path + "-wal"))
            self.assertFalse(os.path.exists(store.db_path + "-shm"))

    def test_recovery_accepts_explicitly_corrupt_database_only_when_raw_safety_snapshot_can_be_created(self):
        with tempfile.TemporaryDirectory() as source_root, tempfile.TemporaryDirectory() as root:
            source = LibraryStore(source_root)
            raw = open(source.create_backup(), "rb").read()
            store = LibraryStore(root)
            with open(store.db_path, "r+b") as handle:
                handle.seek(0)
                handle.write(b"not-a-sqlite-database")
            # The corrupt database cannot safely preserve the current account,
            # but RecoveryService still keeps the byte-for-byte safety snapshot.
            result = RecoveryService(store).restore_backup(raw)
            self.assertTrue(result["reauthentication_required"])
            self.assertTrue(os.path.isfile(result["safety_snapshot"]))
