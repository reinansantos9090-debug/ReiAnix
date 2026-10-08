"""Explicit, non-destructive recovery service for an inconsistent local database.

Recovery is an exceptional path. It validates an incoming ReiAnix backup with
the existing BackupService, preserves the current database as a safety copy, and
only replaces the database after the replacement has passed SQLite integrity and
foreign-key checks. Authentication is preserved when the damaged database can
still be read; if it cannot, recovery restore is refused rather than silently
losing the current account.
"""
from __future__ import annotations

import json
import os
import shutil
import sqlite3
import tempfile
import time
from pathlib import Path
import logging

from core.backup import BACKUP_RECOVERY_LOCK

logger = logging.getLogger("reiflix.recovery")


class RecoveryError(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = str(code)


class RecoveryService:
    def __init__(self, store):
        self.store = store

    def diagnose(self) -> dict:
        path = Path(self.store.db_path)
        report = {
            "required": False,
            "database_path": str(path),
            "exists": path.is_file(),
            "size_bytes": path.stat().st_size if path.is_file() else 0,
            "quick_check": None,
            "foreign_key_ok": None,
            "error": None,
        }
        if not path.is_file():
            report["required"] = True
            report["error"] = "database_missing"
            return report
        try:
            with sqlite3.connect(str(path)) as con:
                quick = con.execute("PRAGMA quick_check").fetchone()
                report["quick_check"] = str(quick[0] if quick else "")
                report["foreign_key_ok"] = con.execute(
                    "PRAGMA foreign_key_check"
                ).fetchone() is None
        except Exception as exc:
            report["error"] = str(exc)
        report["required"] = (
            str(report.get("quick_check") or "").strip().casefold() != "ok"
            or report.get("foreign_key_ok") is False
            or bool(report.get("error"))
        )
        return report

    def diagnostic_bytes(self) -> bytes:
        return (json.dumps(self.diagnose(), ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")

    def create_safety_snapshot(self) -> str:
        with BACKUP_RECOVERY_LOCK:
            source = Path(self.store.db_path)
            if not source.is_file():
                raise RecoveryError(
                    "RECOVERY_DATABASE_MISSING",
                    "O banco atual não existe para criar um snapshot de segurança.",
                )
            stamp = time.strftime("%Y%m%d-%H%M%S", time.localtime())
            target = Path(self.store.backup_dir) / f"recovery-snapshot-{stamp}.sqlite3"
            index = 1
            while target.exists():
                target = Path(self.store.backup_dir) / f"recovery-snapshot-{stamp}-{index}.sqlite3"
                index += 1

            # Prefer the SQLite backup API for a healthy database so the safety
            # snapshot is transactionally consistent with WAL/journal state.
            status = self.diagnose()
            if not status["required"]:
                try:
                    self.store.create_backup_snapshot(str(target))
                    self._fsync_file(target)
                    self._fsync_directory(target.parent)
                    return str(target)
                except Exception:
                    logger.warning(
                        "SQLite backup API failed for safety snapshot; falling back to byte-for-byte copy",
                        exc_info=True,
                    )

            # For a corrupted database the only honest rollback primitive is a
            # byte-for-byte snapshot of the database plus its live sidecars.
            shutil.copy2(source, target)
            self._fsync_file(target)
            for suffix in ("-wal", "-shm"):
                sidecar = Path(str(source) + suffix)
                if sidecar.is_file():
                    shutil.copy2(sidecar, Path(str(target) + suffix))
                    self._fsync_file(Path(str(target) + suffix))
            self._fsync_directory(target.parent)
            return str(target)

    @staticmethod
    def _fsync_file(path: Path) -> None:
        with open(path, "rb") as handle:
            os.fsync(handle.fileno())

    @staticmethod
    def _fsync_directory(path: Path) -> None:
        try:
            fd = os.open(path, getattr(os, "O_DIRECTORY", 0))
        except OSError:
            return
        try:
            os.fsync(fd)
        finally:
            os.close(fd)

    @staticmethod
    def _try_read_account(db_path: str) -> tuple[list[tuple[str, str]], bool]:
        try:
            with sqlite3.connect(db_path) as con:
                rows = con.execute("SELECT key,value FROM account ORDER BY key").fetchall()
            return [(str(key), str(value)) for key, value in rows], True
        except Exception:
            return [], False

    @staticmethod
    def _write_account(db_path: str, rows: list[tuple[str, str]]) -> None:
        if not rows:
            return
        with sqlite3.connect(db_path) as con:
            con.execute("CREATE TABLE IF NOT EXISTS account (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            con.executemany(
                "INSERT INTO account(key,value) VALUES (?,?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                rows,
            )
            con.commit()

    def restore_backup(self, raw: bytes) -> dict:
        with BACKUP_RECOVERY_LOCK:
            return self._restore_backup_locked(raw)

    def _restore_backup_locked(self, raw: bytes) -> dict:
        if not isinstance(raw, (bytes, bytearray)) or not raw:
            raise RecoveryError("RECOVERY_BACKUP_INVALID", "O backup selecionado está vazio.")

        from core.backup import BackupService, BackupValidationError

        backup = BackupService(self.store)
        try:
            preview = backup.inspect_bytes(bytes(raw))
        except BackupValidationError as exc:
            raise RecoveryError(exc.code, str(exc)) from exc

        account_rows, account_preserved = self._try_read_account(self.store.db_path)
        if not account_preserved:
            logger.warning(
                "Current account table could not be read; continuing restore without authentication data"
            )

        safety = self.create_safety_snapshot()
        tempdir = tempfile.TemporaryDirectory(prefix=".reiflix-recovery-", dir=self.store.backup_dir)
        created_assets: list[str] = []
        try:
            input_zip = Path(tempdir.name) / "backup.zip"
            input_zip.write_bytes(bytes(raw))
            extracted, mappings, created_assets, restore_temp = backup._prepare_restore(str(input_zip))
            try:
                self._write_account(extracted, account_rows)
                with sqlite3.connect(extracted) as con:
                    quick = con.execute("PRAGMA integrity_check").fetchone()
                    if str(quick[0] if quick else "").strip().casefold() != "ok":
                        raise RecoveryError("RECOVERY_VALIDATION_FAILED", "O banco restaurado falhou no integrity_check.")
                    if con.execute("PRAGMA foreign_key_check").fetchone():
                        raise RecoveryError("RECOVERY_VALIDATION_FAILED", "O banco restaurado possui foreign keys inválidas.")
                    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")

                replacement = Path(self.store.db_path)
                replacement_tmp = replacement.with_name(replacement.name + ".recovery.tmp")
                shutil.copy2(extracted, replacement_tmp)
                self._fsync_file(replacement_tmp)
                os.replace(replacement_tmp, replacement)
                for suffix in ("-wal", "-shm"):
                    stale = Path(str(replacement) + suffix)
                    if stale.exists():
                        stale.unlink()
                self._fsync_directory(replacement.parent)

                verification = self.diagnose()
                if verification["required"]:
                    raise RecoveryError(
                        "RECOVERY_VALIDATION_FAILED",
                        "O banco restaurado não passou na validação final.",
                    )
            finally:
                restore_temp.cleanup()
        except Exception as exc:
            for path in created_assets:
                try:
                    os.unlink(path)
                except FileNotFoundError:
                    continue
            try:
                self._restore_safety_snapshot(safety)
            except Exception:
                logger.exception("recovery safety rollback failed")
            if isinstance(exc, RecoveryError):
                raise
            raise RecoveryError(
                "RECOVERY_RESTORE_FAILED",
                "A restauração falhou e o estado anterior foi restaurado.",
            ) from exc
        finally:
            tempdir.cleanup()

        return {
            "preview": preview,
            "safety_snapshot": safety,
            "report": self.diagnose(),
            "restart_required": True,
            "authentication_preserved": account_preserved,
            "reauthentication_required": not account_preserved,
        }

    def _restore_safety_snapshot(self, safety: str) -> None:
        source = Path(safety)
        replacement = Path(self.store.db_path)
        if not source.is_file():
            raise RecoveryError("RECOVERY_ROLLBACK_FAILED", "Snapshot de segurança não foi encontrado.")

        replacement_tmp = replacement.with_name(replacement.name + ".rollback.tmp")
        shutil.copy2(source, replacement_tmp)
        self._fsync_file(replacement_tmp)
        os.replace(replacement_tmp, replacement)

        for suffix in ("-wal", "-shm"):
            live_sidecar = Path(str(replacement) + suffix)
            safety_sidecar = Path(str(source) + suffix)
            if safety_sidecar.is_file():
                shutil.copy2(safety_sidecar, live_sidecar)
                self._fsync_file(live_sidecar)
            elif live_sidecar.exists():
                live_sidecar.unlink()
        self._fsync_directory(replacement.parent)

