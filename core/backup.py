"""Versioned, offline-first backup/restore service for ReiAnix.

The service owns the transport/container protocol while LibraryStore remains the
single persistence authority. Backups contain logical application state only;
authentication state, videos, temporary logs and generated cache are excluded.
"""
from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import sqlite3
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import tempfile
import time
import zipfile
from typing import Any
import threading

from core.settings import SettingsDefaults, SettingsStore, SettingsValidationError

BACKUP_RECOVERY_LOCK = threading.RLock()


class BackupError(RuntimeError):
    """Stable, user-facing backup/restore failure."""

    def __init__(self, code: str, message: str, *, details: dict[str, Any] | None = None):
        super().__init__(message)
        self.code = str(code)
        self.details = details or {}

class BackupValidationError(BackupError):
    pass


class BackupMigrationRegistry:
    """Explicit registry for backup-container migrations.
    
    The repository currently has only format v1, so v1 is accepted as-is.
    Future versions must register a deterministic migration before being accepted.
    """
    _migrations: dict[tuple[int, int], Any] = {}

    @classmethod
    def register(cls, source: int, target: int, migration) -> None:
        cls._migrations[(int(source), int(target))] = migration

    @classmethod
    def can_migrate(cls, source: int, target: int) -> bool:
        return int(source) == int(target) or (int(source), int(target)) in cls._migrations

    @classmethod
    def migrate_manifest(cls, manifest: dict[str, Any], target: int) -> dict[str, Any]:
        current = int(manifest.get("format_version") or 0)
        target = int(target)
        if current == target:
            return dict(manifest)
        migration = cls._migrations.get((current, target))
        if migration is None:
            raise BackupValidationError(
                "BACKUP_UNSUPPORTED_VERSION",
                f"Nenhuma migração registrada de v{current} para v{target}.",
            )
        migrated = migration(dict(manifest))
        if not isinstance(migrated, dict):
            raise BackupValidationError("BACKUP_INVALID", "Migração de backup retornou um manifest inválido.")
        migrated["format_version"] = target
        return migrated


class BackupService:
    FORMAT = "rei-flix-backup-v1"
    FORMAT_VERSION = 1
    APP_NAME = "ReiAnix"
    DEFAULT_APP_VERSION = "0.2.1"
    REQUIRED_MEMBERS = frozenset({"manifest.json", "library.sqlite3"})
    MANUAL_ARTWORK_PREFIX = "artwork/manual/"
    MAX_ARCHIVE_BYTES = 512 * 1024 * 1024
    MAX_MEMBER_BYTES = 64 * 1024 * 1024
    MAX_MEMBER_COUNT = 2048
    SNAPSHOT_TABLES = (
        "folders",
        "anime",
        "episodes",
        "episode_observations",
        "artwork",
        "associations",
        "pending_matches",
        "preferences",
        "genres",
        "genre_aliases",
        "anime_genres",
        "schema_migrations",
        "scan_runs",
        "account",
    )
    BACKUP_PREFERENCE_KEYS = frozenset(SettingsDefaults.EXPORT_KEYS) | {"settings.schema_version"}

    def __init__(self, store, settings=None, *, app_version: str | None = None):
        self.store = store
        self.settings = settings
        self.app_version = str(app_version or self.DEFAULT_APP_VERSION)

    @staticmethod
    def _canonical(value: Any) -> bytes:
        return json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")

    @staticmethod
    def _sha256_file(path: str | os.PathLike[str]) -> str:
        digest = hashlib.sha256()
        with open(path, "rb") as handle:
            while True:
                chunk = handle.read(1024 * 1024)
                if not chunk:
                    break
                digest.update(chunk)
        return digest.hexdigest()

    @classmethod
    def _sha256_stream(cls, stream) -> tuple[str, int]:
        digest = hashlib.sha256()
        total = 0
        while True:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            digest.update(chunk)
            if total > cls.MAX_MEMBER_BYTES:
                raise BackupValidationError(
                    "BACKUP_TOO_LARGE",
                    "O arquivo de backup contém um membro grande demais.",
                )
        return digest.hexdigest(), total

    @staticmethod
    def _timestamp() -> str:
        return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")

    @staticmethod
    def _json_bytes(value: Any) -> bytes:
        return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")

    @staticmethod
    def _safe_member_name(name: str) -> str:
        normalized = str(name or "").replace("\\", "/")
        path = PurePosixPath(normalized)
        if not normalized or path.is_absolute() or ".." in path.parts:
            raise BackupValidationError(
                "BACKUP_PATH_TRAVERSAL",
                "O backup contém uma entrada de caminho inválida.",
            )
        if "\x00" in normalized:
            raise BackupValidationError("BACKUP_INVALID", "O backup contém um nome inválido.")
        return normalized

    @classmethod
    def _validate_archive_members(cls, archive: zipfile.ZipFile) -> list[zipfile.ZipInfo]:
        infos = archive.infolist()
        if len(infos) > cls.MAX_MEMBER_COUNT:
            raise BackupValidationError("BACKUP_TOO_LARGE", "Backup contém entradas demais.")
        seen = set()
        total = 0
        validated = []
        for info in infos:
            name = cls._safe_member_name(info.filename)
            if name in seen:
                raise BackupValidationError("BACKUP_INVALID", f"Entrada duplicada no backup: {name}")
            if info.is_dir():
                raise BackupValidationError("BACKUP_UNEXPECTED_ENTRY", f"Entrada de diretório não permitida: {name}")
            seen.add(name)
            mode = (info.external_attr >> 16) & 0xFFFF
            if stat.S_ISLNK(mode):
                raise BackupValidationError("BACKUP_UNSAFE_ENTRY", "Backups não podem conter links simbólicos.")
            declared = int(info.file_size or 0)
            if declared < 0 or declared > cls.MAX_MEMBER_BYTES:
                raise BackupValidationError("BACKUP_TOO_LARGE", f"Entrada grande demais: {name}")
            total += declared
            if total > cls.MAX_ARCHIVE_BYTES:
                raise BackupValidationError("BACKUP_TOO_LARGE", "O backup excede o limite de tamanho suportado.")
            if name not in cls.REQUIRED_MEMBERS and not name.startswith(cls.MANUAL_ARTWORK_PREFIX):
                raise BackupValidationError("BACKUP_UNEXPECTED_ENTRY", f"Entrada não permitida: {name}")
            validated.append(info)
        if not cls.REQUIRED_MEMBERS.issubset(seen):
            raise BackupValidationError("BACKUP_INVALID", "Backup sem manifest.json ou library.sqlite3.")
        return validated

    @classmethod
    def _read_manifest(cls, archive: zipfile.ZipFile) -> dict[str, Any]:
        try:
            raw = archive.read("manifest.json")
            manifest = json.loads(raw.decode("utf-8"))
        except (KeyError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise BackupValidationError("BACKUP_INVALID", "manifest.json inválido.") from exc
        if not isinstance(manifest, dict):
            raise BackupValidationError("BACKUP_INVALID", "Manifest inválido.")
        if manifest.get("app") != cls.APP_NAME:
            raise BackupValidationError("BACKUP_INVALID", "Este arquivo não é um backup do ReiAnix.")
        if manifest.get("format") != cls.FORMAT:
            raise BackupValidationError("BACKUP_UNSUPPORTED_VERSION", "Formato de backup incompatível.")
        try:
            version = int(manifest.get("format_version"))
        except (TypeError, ValueError) as exc:
            raise BackupValidationError("BACKUP_UNSUPPORTED_VERSION", "Versão de backup inválida.") from exc
        if version != cls.FORMAT_VERSION:
            raise BackupValidationError(
                "BACKUP_UNSUPPORTED_VERSION",
                f"Versão de backup {version} não suportada; atual é {cls.FORMAT_VERSION}.",
            )
        try:
            schema = int(manifest.get("schema_version"))
        except (TypeError, ValueError) as exc:
            raise BackupValidationError("BACKUP_SCHEMA_MISMATCH", "Schema do backup inválido.") from exc
        if schema != int(cls._schema_version_from_store()):
            raise BackupValidationError(
                "BACKUP_SCHEMA_MISMATCH",
                f"Schema {schema} não é compatível com o schema atual {cls._schema_version_from_store()}.",
            )
        compatibility = manifest.get("compatibility") or {}
        if not isinstance(compatibility, dict):
            raise BackupValidationError("BACKUP_INVALID", "Bloco de compatibilidade inválido.")
        for key in ("min_schema", "max_schema"):
            if key in compatibility:
                try:
                    int(compatibility[key])
                except (TypeError, ValueError) as exc:
                    raise BackupValidationError("BACKUP_INVALID", "Faixa de schema inválida.") from exc
        integrity = manifest.get("integrity")
        if not isinstance(integrity, dict) or integrity.get("algorithm") != "SHA-256":
            raise BackupValidationError("BACKUP_INVALID", "Integridade SHA-256 ausente.")
        entries = integrity.get("entries")
        if not isinstance(entries, dict) or "library.sqlite3" not in entries:
            raise BackupValidationError("BACKUP_INVALID", "Manifesto de integridade incompleto.")
        return manifest

    @staticmethod
    def _schema_version_from_store() -> int:
        from core.library_store import LibraryStore
        return int(LibraryStore.SCHEMA_VERSION)

    def _validate_integrity(self, archive: zipfile.ZipFile, manifest: dict[str, Any], infos: list[zipfile.ZipInfo]) -> set[str]:
        integrity = manifest["integrity"]
        stored_entries = integrity.get("entries") or {}
        optional_entries = {self._safe_member_name(name) for name in (integrity.get("optional_entries") or [])}
        actual_entries: dict[str, dict[str, Any]] = {}
        invalid_optional: set[str] = set()
        by_name = {self._safe_member_name(info.filename): info for info in infos}
        by_name.pop("manifest.json", None)
        for name, info in by_name.items():
            with archive.open(info, "r") as stream:
                digest, size = self._sha256_stream(stream)
            actual_entries[name] = {"size": size, "sha256": digest}
            expected = stored_entries.get(name)
            if expected is None:
                raise BackupValidationError("BACKUP_CHECKSUM_MISMATCH", f"Checksum ausente para {name}.")
            if int(expected.get("size", -1)) != size or str(expected.get("sha256", "")).lower() != digest:
                if name in optional_entries:
                    invalid_optional.add(name)
                    continue
                raise BackupValidationError(
                    "BACKUP_CHECKSUM_MISMATCH",
                    f"Checksum/integridade inválida para {name}.",
                    details={"member": name},
                )
        missing_entries = set(stored_entries) - set(actual_entries)
        missing_required = missing_entries - optional_entries
        if missing_required:
            raise BackupValidationError("BACKUP_CHECKSUM_MISMATCH", "Manifesto de integridade não corresponde ao container.")
        expected_payload = str(integrity.get("payload_sha256") or "")
        actual_payload = hashlib.sha256(self._canonical(actual_entries)).hexdigest()
        optional_missing_or_invalid = (missing_entries & optional_entries) | invalid_optional
        if expected_payload != actual_payload and not optional_missing_or_invalid:
            raise BackupValidationError("BACKUP_CHECKSUM_MISMATCH", "Checksum do payload inválido.")
        core = dict(manifest)
        core.pop("integrity", None)
        expected_core = str(integrity.get("manifest_core_sha256") or "")
        actual_core = hashlib.sha256(self._canonical(core)).hexdigest()
        if expected_core != actual_core:
            raise BackupValidationError("BACKUP_CHECKSUM_MISMATCH", "Checksum do manifest inválido.")
        return invalid_optional | (missing_entries & optional_entries)

    def _validate_snapshot_semantics(self, db_path: str, *, sanitize_unknown: bool = False) -> None:
        """Validate logical values that SQLite constraints cannot express."""
        with sqlite3.connect(db_path) as con:
            rows = con.execute("SELECT key,value FROM preferences").fetchall()
            supported = set(SettingsDefaults.EXPORT_KEYS) | {"settings.schema_version"}
            unknown = [str(key) for key, _ in rows if str(key) not in supported]
            if sanitize_unknown and unknown:
                placeholders = ",".join("?" for _ in unknown)
                con.execute(f"DELETE FROM preferences WHERE key IN ({placeholders})", unknown)

            schema_rows = [value for key, value in rows if str(key) == "settings.schema_version"]
            if schema_rows and str(schema_rows[0]) != str(SettingsStore.SCHEMA_VERSION):
                raise BackupValidationError(
                    "BACKUP_SCHEMA_MISMATCH",
                    "Versão persistida do SettingsStore incompatível com o aplicativo.",
                )
            for key, raw in rows:
                key = str(key)
                if key not in SettingsDefaults.EXPORT_KEYS:
                    continue
                try:
                    SettingsStore._coerce(SettingsDefaults.BY_KEY[key], raw)
                except (SettingsValidationError, TypeError, ValueError) as exc:
                    raise BackupValidationError(
                        "BACKUP_VALIDATION_FAILED",
                        f"Valor inválido no SettingsStore: {key}.",
                    ) from exc

            invalid_progress = con.execute(
                """SELECT COUNT(*) FROM episodes
                   WHERE progress < 0 OR duration < 0
                      OR (duration > 0 AND progress > duration + 0.001)"""
            ).fetchone()[0]
            if invalid_progress:
                raise BackupValidationError(
                    "BACKUP_VALIDATION_FAILED",
                    "Backup contém progresso/duração inconsistentes.",
                )
            watched_invalid = con.execute(
                "SELECT COUNT(*) FROM episodes WHERE watched NOT IN (0,1)"
            ).fetchone()[0]
            if watched_invalid:
                raise BackupValidationError(
                    "BACKUP_VALIDATION_FAILED",
                    "Backup contém estados de consumo inválidos.",
                )

            if sanitize_unknown:
                con.commit()

    def _database_counts(self, db_path: str) -> dict[str, int]:
        with sqlite3.connect(db_path) as con:
            tables = [
                ("folders", "folders"),
                ("anime", "anime"),
                ("episodes", "episodes"),
                ("favorites", "SELECT COUNT(*) FROM anime WHERE favorite=1"),
                ("pinned", "SELECT COUNT(*) FROM anime WHERE is_pinned=1"),
                ("notes", "SELECT COUNT(*) FROM anime WHERE NULLIF(TRIM(personal_note),'') IS NOT NULL"),
                ("history", "SELECT COUNT(*) FROM episodes WHERE last_played_at IS NOT NULL"),
                ("progress", "SELECT COUNT(*) FROM episodes WHERE progress > 0 OR watched=1"),
                ("missing_files", "SELECT COUNT(*) FROM episodes WHERE missing=1"),
                ("genres", "genres"),
                ("artwork_references", "artwork"),
                ("anilist_matches", "SELECT COUNT(*) FROM anime WHERE anilist_id IS NOT NULL"),
                ("tags", "SELECT COUNT(*) FROM anime WHERE json_array_length(CASE WHEN json_valid(user_tags) THEN user_tags ELSE '[]' END) > 0"),
            ]
            out = {}
            for key, query in tables:
                if query in {"folders", "anime", "episodes", "genres", "artwork"}:
                    value = con.execute(f"SELECT COUNT(*) FROM {query}").fetchone()[0]
                else:
                    value = con.execute(query).fetchone()[0]
                out[key] = int(value or 0)
            return out

    def _sanitize_snapshot(self, db_path: str) -> None:
        allowed = tuple(sorted(self.BACKUP_PREFERENCE_KEYS))
        placeholders = ",".join("?" for _ in allowed)
        with sqlite3.connect(db_path) as con:
            con.execute("PRAGMA foreign_keys=ON")
            con.execute("DELETE FROM account")
            con.execute("UPDATE folders SET account_id=NULL")
            con.execute("DELETE FROM scan_runs")
            con.execute(
                f"DELETE FROM preferences WHERE key NOT IN ({placeholders})",
                allowed,
            )
            con.commit()
            integrity = con.execute("PRAGMA integrity_check").fetchone()
            if str(integrity[0] if integrity else "").strip().casefold() != "ok":
                raise BackupError("DATABASE_INTEGRITY_FAILED", "Snapshot SQLite inválido após sanitização.")
            if con.execute("PRAGMA foreign_key_check").fetchone():
                raise BackupError("DATABASE_INTEGRITY_FAILED", "Snapshot possui referências inválidas.")

    def _manual_artwork(self, db_path: str) -> list[dict[str, Any]]:
        results = []
        with sqlite3.connect(db_path) as con:
            rows = con.execute(
                "SELECT id,entity_type,entity_id,artwork_type,local_path,source FROM artwork WHERE manual=1 AND local_path IS NOT NULL"
            ).fetchall()
        for row in rows:
            path = str(row[4] or "").strip()
            portable = False
            member = None
            digest = None
            size = None
            if path:
                try:
                    root = Path(self.store.cache_dir).resolve()
                    candidate = Path(path).resolve()
                    candidate.relative_to(root)
                    if candidate.is_file():
                        size = int(candidate.stat().st_size)
                        if size <= self.MAX_MEMBER_BYTES:
                            digest = self._sha256_file(candidate)
                            member = f"{self.MANUAL_ARTWORK_PREFIX}{digest}{candidate.suffix.lower() or '.bin'}"
                            portable = True
                except (OSError, ValueError):
                    portable = False
            results.append(
                {
                    "artwork_id": int(row[0]),
                    "entity_type": str(row[1]),
                    "entity_id": str(row[2]),
                    "artwork_type": str(row[3]),
                    "original_path": path,
                    "member": member,
                    "sha256": digest,
                    "size": size,
                    "portable": portable,
                    "source": str(row[5] or ""),
                }
            )
        return results

    def _build_manifest(self, db_path: str, artwork_entries: list[dict[str, Any]], archive_entries: dict[str, dict[str, Any]]) -> dict[str, Any]:
        manifest = {
            "format": self.FORMAT,
            "format_version": self.FORMAT_VERSION,
            "app": self.APP_NAME,
            "app_version": self.app_version,
            "schema_version": self._schema_version_from_store(),
            "created_at": self._timestamp(),
            "compatibility": {
                "min_schema": self._schema_version_from_store(),
                "max_schema": self._schema_version_from_store(),
                "migrations": ["backup-format-v1"],
            },
            "contents": {
                "logical_database": True,
                "video_files": False,
                "authentication": False,
                "temporary_logs": False,
                "generated_artwork_cache": False,
                "manual_artwork": [item for item in artwork_entries if item.get("portable")],
            },
            "counts": self._database_counts(db_path),
            "artwork": artwork_entries,
        }
        payload_sha = hashlib.sha256(self._canonical(archive_entries)).hexdigest()
        core_sha = hashlib.sha256(self._canonical(manifest)).hexdigest()
        manifest["integrity"] = {
            "algorithm": "SHA-256",
            "entries": archive_entries,
            "optional_entries": sorted(
                name for name in archive_entries
                if name.startswith(self.MANUAL_ARTWORK_PREFIX)
            ),
            "payload_sha256": payload_sha,
            "manifest_core_sha256": core_sha,
        }
        return manifest

    def _prepare_snapshot(self) -> tuple[str, list[dict[str, Any]]]:
        parent = str(Path(self.store.db_path).resolve().parent)
        fd, snapshot = tempfile.mkstemp(prefix=".reiflix-backup-", suffix=".sqlite3", dir=parent)
        os.close(fd)
        try:
            self.store.create_backup_snapshot(snapshot)
            self._sanitize_snapshot(snapshot)
            self._validate_snapshot_semantics(snapshot)
            artwork = self._manual_artwork(snapshot)
            return snapshot, artwork
        except Exception:
            try:
                os.unlink(snapshot)
            except FileNotFoundError:
                pass
            raise

    def create_backup_file(self, destination: str | os.PathLike[str] | None = None) -> str:
        with BACKUP_RECOVERY_LOCK:
            return self._create_backup_file_locked(destination)

    def _create_backup_file_locked(self, destination: str | os.PathLike[str] | None = None) -> str:
        destination = self._choose_destination(destination)
        parent = str(Path(destination).resolve().parent)
        os.makedirs(parent, exist_ok=True)
        snapshot, artwork_entries = self._prepare_snapshot()
        temp_zip = f"{destination}.tmp"
        try:
            required_space = max(
                16 * 1024 * 1024,
                os.path.getsize(snapshot)
                + sum(int(item.get("size") or 0) for item in artwork_entries if item.get("portable"))
                + 4 * 1024 * 1024,
            )
            try:
                free_space = shutil.disk_usage(parent).free
            except OSError:
                free_space = None
            if free_space is not None and free_space < required_space:
                raise BackupError(
                    "BACKUP_INSUFFICIENT_SPACE",
                    f"Espaço insuficiente para criar o backup. Necessário aproximadamente {required_space} bytes; disponível {free_space} bytes.",
                    details={"required_bytes": required_space, "available_bytes": free_space},
                )
            archive_entries: dict[str, dict[str, Any]] = {}
            with zipfile.ZipFile(temp_zip, "w", compression=zipfile.ZIP_DEFLATED, allowZip64=True) as archive:
                archive.write(snapshot, "library.sqlite3")
                archive_entries["library.sqlite3"] = {
                    "size": os.path.getsize(snapshot),
                    "sha256": self._sha256_file(snapshot),
                }
                for item in artwork_entries:
                    if not item.get("portable"):
                        continue
                    source = item["original_path"]
                    member = item["member"]
                    archive.write(source, member)
                    archive_entries[member] = {
                        "size": os.path.getsize(source),
                        "sha256": self._sha256_file(source),
                    }
                manifest = self._build_manifest(snapshot, artwork_entries, archive_entries)
                archive.writestr("manifest.json", self._json_bytes(manifest))
            self.inspect_file(temp_zip)
            with open(temp_zip, "rb") as handle:
                os.fsync(handle.fileno())
            os.replace(temp_zip, destination)
            try:
                flags = getattr(os, "O_DIRECTORY", 0)
                dir_fd = os.open(parent, flags)
                try:
                    os.fsync(dir_fd)
                finally:
                    os.close(dir_fd)
            except (AttributeError, OSError):
                pass
            return destination
        finally:
            try:
                os.unlink(snapshot)
            except FileNotFoundError:
                pass
            try:
                os.unlink(temp_zip)
            except FileNotFoundError:
                pass

    def create_backup_bytes(self) -> bytes:
        parent = str(Path(self.store.backup_dir).resolve())
        os.makedirs(parent, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S", time.localtime())
        destination = os.path.join(parent, f".export-{stamp}.zip")
        path = self.create_backup_file(destination)
        try:
            with open(path, "rb") as handle:
                return handle.read()
        finally:
            try:
                os.unlink(path)
            except FileNotFoundError:
                pass

    def _choose_destination(self, destination: str | os.PathLike[str] | None) -> str:
        if destination:
            base = Path(os.path.abspath(os.path.expanduser(str(destination))))
        else:
            stamp = time.strftime("%Y%m%d-%H%M%S", time.localtime())
            base = Path(self.store.backup_dir) / f"reiflix-backup-{stamp}.zip"
        if not base.suffix:
            base = base.with_suffix(".zip")
        candidate = base
        index = 1
        while candidate.exists():
            candidate = base.with_name(f"{base.stem}-{index}{base.suffix}")
            index += 1
        return str(candidate)

    def inspect_bytes(self, raw: bytes) -> dict[str, Any]:
        if not isinstance(raw, (bytes, bytearray)) or not raw:
            raise BackupValidationError("BACKUP_INVALID", "Arquivo de backup vazio.")
        if len(raw) > self.MAX_ARCHIVE_BYTES:
            raise BackupValidationError("BACKUP_TOO_LARGE", "Backup maior que o limite suportado.")
        with tempfile.TemporaryDirectory(prefix=".reiflix-inspect-", dir=self.store.backup_dir) as root:
            path = os.path.join(root, "backup.zip")
            with open(path, "wb") as handle:
                handle.write(bytes(raw))
            return self.inspect_file(path)

    def inspect_file(self, path: str | os.PathLike[str]) -> dict[str, Any]:
        path = os.path.abspath(os.path.expanduser(str(path)))
        if not os.path.isfile(path):
            raise BackupValidationError("BACKUP_INVALID", "Arquivo de backup não encontrado.")
        if os.path.getsize(path) > self.MAX_ARCHIVE_BYTES:
            raise BackupValidationError("BACKUP_TOO_LARGE", "Backup maior que o limite suportado.")
        with tempfile.TemporaryDirectory(prefix=".reiflix-inspect-", dir=self.store.backup_dir) as root:
            try:
                with zipfile.ZipFile(path, "r") as archive:
                    infos = self._validate_archive_members(archive)
                    manifest = self._read_manifest(archive)
                    integrity_warnings = self._validate_integrity(archive, manifest, infos)
                    extracted = os.path.join(root, "library.sqlite3")
                    with archive.open("library.sqlite3", "r") as source, open(extracted, "wb") as target:
                        shutil.copyfileobj(source, target)
                    self.store._validate_backup_database(extracted)
                    self._validate_snapshot_semantics(extracted)
                    counts = self._database_counts(extracted)
            except zipfile.BadZipFile as exc:
                raise BackupValidationError("BACKUP_INVALID", "Container ZIP inválido ou truncado.") from exc
            return {
                "format": manifest["format"],
                "format_version": int(manifest["format_version"]),
                "app_version": str(manifest.get("app_version") or "unknown"),
                "schema_version": int(manifest["schema_version"]),
                "created_at": str(manifest.get("created_at") or ""),
                "counts": counts,
                "artwork": manifest.get("artwork") or [],
                "integrity": "SHA-256 válido",
                "integrity_warnings": sorted(integrity_warnings),
                "videos_included": False,
                "authentication_included": False,
            }

    def _prepare_restore(self, path: str) -> tuple[str, list[tuple[str, str]], list[str], tempfile.TemporaryDirectory]:
        tempdir = tempfile.TemporaryDirectory(prefix=".reiflix-restore-", dir=self.store.backup_dir)
        root = tempdir.name
        extracted = os.path.join(root, "library.sqlite3")
        mappings: list[tuple[str, str]] = []
        created_assets: list[str] = []
        with zipfile.ZipFile(path, "r") as archive:
            infos = self._validate_archive_members(archive)
            manifest = self._read_manifest(archive)
            invalid_optional = self._validate_integrity(archive, manifest, infos)
            with archive.open("library.sqlite3", "r") as source, open(extracted, "wb") as target:
                shutil.copyfileobj(source, target)
            self.store._validate_backup_database(extracted)
            self._validate_snapshot_semantics(extracted, sanitize_unknown=True)
            self.store._validate_backup_database(extracted)
            for item in manifest.get("artwork") or []:
                if not item.get("portable") or not item.get("member"):
                    continue
                member = self._safe_member_name(item["member"])
                if member in invalid_optional:
                    continue
                target_temp = os.path.join(root, "assets", os.path.basename(member))
                os.makedirs(os.path.dirname(target_temp), exist_ok=True)
                with archive.open(member, "r") as source, open(target_temp, "wb") as target:
                    shutil.copyfileobj(source, target)
                final_dir = Path(self.store.cache_dir) / "artwork" / "manual"
                final_dir.mkdir(parents=True, exist_ok=True)
                suffix = Path(member).suffix.lower() or ".bin"
                final = final_dir / f"{str(item.get('sha256') or '')}{suffix}"
                if not item.get("sha256") or self._sha256_file(target_temp) != str(item["sha256"]):
                    raise BackupValidationError("BACKUP_CHECKSUM_MISMATCH", f"Artwork inválido: {member}")
                if final.exists():
                    if self._sha256_file(final) != str(item["sha256"]):
                        raise BackupValidationError("BACKUP_CHECKSUM_MISMATCH", f"Artwork conflitante: {member}")
                else:
                    shutil.copy2(target_temp, final)
                    created_assets.append(str(final))
                mappings.append((str(item.get("original_path") or ""), str(final)))
        return extracted, mappings, created_assets, tempdir

    def restore_file(self, backup_path: str | os.PathLike[str]) -> dict[str, Any]:
        with BACKUP_RECOVERY_LOCK:
            return self._restore_file_locked(backup_path)

    def _restore_file_locked(self, backup_path: str | os.PathLike[str]) -> dict[str, Any]:
        backup_path = os.path.abspath(os.path.expanduser(str(backup_path)))
        preview = self.inspect_file(backup_path)
        safety_name = self._choose_destination(
            Path(self.store.backup_dir) / f"pre-restore-{time.strftime('%Y%m%d-%H%M%S', time.localtime())}.zip"
        )
        safety = self.create_backup_file(safety_name)
        tempdir = None
        created_assets: list[str] = []
        try:
            extracted, mappings, created_assets, tempdir = self._prepare_restore(backup_path)
            self.store.restore_backup_transaction(extracted, artwork_mappings=mappings)
            if self.settings is not None:
                self.settings.invalidate_cache()
            actual = self._database_counts(self.store.db_path)
            return {
                "preview": preview,
                "safety_backup": safety,
                "report": self._restore_report(preview, mappings, actual_counts=actual),
            }
        except Exception as exc:
            for path in created_assets:
                try:
                    os.unlink(path)
                except FileNotFoundError:
                    pass
            if isinstance(exc, BackupError):
                raise
            raise BackupError("RESTORE_TRANSACTION_FAILED", "Não foi possível concluir o restore de forma atômica.") from exc
        finally:
            if tempdir is not None:
                tempdir.cleanup()

    def restore_bytes(self, raw: bytes) -> dict[str, Any]:
        if not isinstance(raw, (bytes, bytearray)) or not raw:
            raise BackupValidationError("BACKUP_INVALID", "Arquivo de backup vazio.")
        with tempfile.TemporaryDirectory(prefix=".reiflix-restore-input-", dir=self.store.backup_dir) as root:
            path = os.path.join(root, "backup.zip")
            with open(path, "wb") as handle:
                handle.write(bytes(raw))
            return self.restore_file(path)

    @staticmethod
    def _restore_report(
        preview: dict[str, Any],
        mappings: list[tuple[str, str]],
        *,
        actual_counts: dict[str, int] | None = None,
    ) -> dict[str, Any]:
        counts = preview.get("counts") or {}
        actual = actual_counts or counts
        return {
            "imported": int(actual.get("anime", 0)) + int(actual.get("episodes", 0)),
            "merged": 0,
            "updated": 0,
            "ignored": 0,
            "missing_files": int(actual.get("missing_files", 0)),
            "conflicts": 0,
            "warnings": [
                "Autenticação Google não foi restaurada.",
                "Vídeos locais nunca são incluídos no backup.",
                "Artwork gerado/cache remoto pode ser reconstruído.",
            ],
            "manual_artwork_reconnected": len(mappings),
        }
