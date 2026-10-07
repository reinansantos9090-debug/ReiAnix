"""SQLite persistence for the local library and its document-folder diagnostics."""
from __future__ import annotations

import hashlib
import json
import logging
import math
import os
import re
import shutil
import sqlite3
import tempfile
import time
import threading
import zipfile
from pathlib import Path
from urllib.parse import unquote, urlparse

from core.consumption import consumption_state, is_completed, is_in_progress, is_regular_episode
from core.search_engine import normalize_text
from core.performance import get_performance_monitor


logger = logging.getLogger(__name__)


class LibraryStore:
    SCHEMA_VERSION = 29
    SQLITE_TIMEOUT_SECONDS = 10.0
    SQLITE_BUSY_TIMEOUT_MS = 10_000
    def __init__(self, data_dir: str):
        os.makedirs(data_dir, exist_ok=True)
        self.db_path = os.path.join(data_dir, "library.sqlite3")
        self.cache_dir = os.path.join(data_dir, "covers")
        self.backup_dir = os.path.join(data_dir, "backups")
        os.makedirs(self.cache_dir, exist_ok=True)
        os.makedirs(self.backup_dir, exist_ok=True)
        self._last_playback_event_at = {}
        self._playback_session_lock = threading.RLock()
        self._active_playback_session_id = None
        self.recovery_error = None
        try:
            self._init()
        except Exception as exc:
            # A corrupt SQLite file must not be replaced or deleted implicitly.
            # Keep the store object constructible so the explicit Recovery Mode
            # can diagnose and offer a validated, user-confirmed restore.
            self.recovery_error = str(exc)

    @staticmethod
    def _row_factory(cursor, row):
        """Preserve the semantic type of episode numbers on SQLite REAL columns.

        SQLite may return an integer-valued REAL as 6.0. The project accepts
        decimal episode numbers too, so only finite integral values in a column
        named "number" are narrowed to int at the repository boundary.
        """
        columns = {description[0]: index for index, description in enumerate(cursor.description or ())}
        number_index = columns.get("number")
        if number_index is not None:
            value = row[number_index]
            if isinstance(value, float) and math.isfinite(value) and value.is_integer():
                row = list(row)
                row[number_index] = int(value)
                row = tuple(row)
        return sqlite3.Row(cursor, row)

    def _conn(self):
        # Connections are short-lived and therefore never cross asyncio worker
        # threads. A bounded busy timeout turns transient writer contention into
        # waiting rather than an immediate "database is locked" failure. Journal
        # mode remains the default because backup/recovery snapshots deliberately
        # operate on the primary database file and its known sidecar contract.
        con = sqlite3.connect(self.db_path, timeout=self.SQLITE_TIMEOUT_SECONDS)
        con.row_factory = self._row_factory
        con.execute(f"PRAGMA busy_timeout={self.SQLITE_BUSY_TIMEOUT_MS}")
        con.execute("PRAGMA foreign_keys=ON")
        con.create_function("reiflix_normalize", 1, lambda value: normalize_text(value), deterministic=True)
        return con

    @staticmethod
    def _episode_path_candidates(path):
        raw = str(path or "").strip()
        if not raw:
            return []
        candidates = [raw]
        if raw.casefold().startswith("file://"):
            try:
                decoded = unquote(urlparse(raw).path)
            except ValueError:
                decoded = ""
            if decoded and decoded not in candidates:
                candidates.append(decoded)
        elif os.path.isabs(raw):
            try:
                file_uri = Path(raw).resolve().as_uri()
            except (OSError, ValueError):
                file_uri = ""
            if file_uri and file_uri not in candidates:
                candidates.append(file_uri)
        return candidates

    @classmethod
    def _find_episode_row(cls, con, path):
        for candidate in cls._episode_path_candidates(path):
            row = con.execute("SELECT * FROM episodes WHERE path=?", (candidate,)).fetchone()
            if row:
                return row
        return None

    def _init(self):
        with self._conn() as c:
            c.executescript('''
            CREATE TABLE IF NOT EXISTS folders (
              path TEXT PRIMARY KEY, name TEXT, kind TEXT NOT NULL DEFAULT 'path',
              authorization TEXT NOT NULL DEFAULT 'unknown', account_id TEXT, added_at REAL NOT NULL,
              last_scan_at REAL, last_error TEXT,
              saf_authority TEXT, saf_document_id TEXT, saf_volume_id TEXT,
              saf_identity TEXT
            );
            CREATE TABLE IF NOT EXISTS anime (
              id INTEGER PRIMARY KEY, lookup_title TEXT UNIQUE NOT NULL, anilist_id INTEGER,
              title TEXT NOT NULL, romaji TEXT, english TEXT, native TEXT, aliases TEXT DEFAULT '[]', description TEXT,
              cover_url TEXT, cover_cache TEXT, banner_url TEXT, genres TEXT, year INTEGER,
              season TEXT, status TEXT, episodes_count INTEGER, duration INTEGER, score INTEGER, format TEXT, studio TEXT,
              metadata_updated_at REAL, metadata_fetched_at REAL, metadata_source TEXT NOT NULL DEFAULT 'unknown', metadata_confidence TEXT NOT NULL DEFAULT 'low', metadata_status TEXT NOT NULL DEFAULT 'unresolved', metadata_manual_fields TEXT NOT NULL DEFAULT '[]', favorite INTEGER NOT NULL DEFAULT 0, user_tags TEXT NOT NULL DEFAULT '[]',
              media_kind TEXT NOT NULL DEFAULT 'series', is_pinned INTEGER NOT NULL DEFAULT 0, personal_note TEXT, added_at REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS episodes (
              id INTEGER PRIMARY KEY, anime_id INTEGER NOT NULL REFERENCES anime(id) ON DELETE CASCADE,
              path TEXT UNIQUE NOT NULL, file_name TEXT NOT NULL, season INTEGER NOT NULL,
              number REAL, duration REAL DEFAULT 0, progress REAL DEFAULT 0, watched INTEGER DEFAULT 0,
              mime_type TEXT, file_size INTEGER, modified_at REAL, source_folder TEXT, absolute_number REAL, relative_path TEXT, volume_id TEXT, volume_uuid TEXT, episode_type TEXT NOT NULL DEFAULT 'regular', episode_title TEXT, identification_source TEXT NOT NULL DEFAULT 'legacy', identification_confidence TEXT NOT NULL DEFAULT 'medium', manual_override INTEGER NOT NULL DEFAULT 0,
              missing INTEGER DEFAULT 0, last_played_at REAL, media_identity TEXT, availability_state TEXT NOT NULL DEFAULT 'available');
            CREATE TABLE IF NOT EXISTS episode_observations (
              id INTEGER PRIMARY KEY,
              episode_id INTEGER NOT NULL REFERENCES episodes(id) ON DELETE CASCADE,
              source_kind TEXT NOT NULL,
              scope_kind TEXT NOT NULL,
              scope_ref TEXT NOT NULL DEFAULT '',
              uri TEXT NOT NULL,
              volume_id TEXT,
              native_generation INTEGER,
              fingerprint TEXT,
              first_seen REAL NOT NULL,
              last_seen REAL,
              last_checked_at REAL,
              state TEXT NOT NULL DEFAULT 'available',
              error TEXT
            );
            CREATE UNIQUE INDEX IF NOT EXISTS idx_episode_observation_unique
              ON episode_observations(episode_id, source_kind, scope_kind, scope_ref, uri);
            CREATE INDEX IF NOT EXISTS idx_episode_observation_scope
              ON episode_observations(source_kind, scope_kind, scope_ref, state);
            CREATE INDEX IF NOT EXISTS idx_episode_observation_volume
              ON episode_observations(volume_id, state);
            CREATE TABLE IF NOT EXISTS artwork (
              id INTEGER PRIMARY KEY, entity_type TEXT NOT NULL, entity_id TEXT NOT NULL,
              artwork_type TEXT NOT NULL, source TEXT NOT NULL, source_ref TEXT,
              local_path TEXT, external_url TEXT, manual INTEGER NOT NULL DEFAULT 0,
              priority INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'ready',
              discovered_at REAL NOT NULL, updated_at REAL NOT NULL, last_attempt_at REAL,
              failure_count INTEGER NOT NULL DEFAULT 0,
              artwork_key TEXT,
              variant TEXT NOT NULL DEFAULT 'default',
              byte_size INTEGER,
              width INTEGER,
              height INTEGER,
              checksum TEXT,
              content_type TEXT,
              last_access REAL,
              next_retry_at REAL,
              http_status INTEGER,
              UNIQUE(entity_type, entity_id, artwork_type, source_ref)
            );
            CREATE INDEX IF NOT EXISTS idx_artwork_entity
              ON artwork(entity_type, entity_id, artwork_type, priority DESC);
            CREATE INDEX IF NOT EXISTS idx_artwork_status
              ON artwork(status, last_attempt_at);
            CREATE TABLE IF NOT EXISTS associations (lookup_title TEXT PRIMARY KEY, anilist_id INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS pending_matches (lookup_title TEXT PRIMARY KEY, display_title TEXT NOT NULL, candidates TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS account (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS preferences (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS genres (
              id TEXT PRIMARY KEY, canonical_name TEXT NOT NULL, normalized_name TEXT NOT NULL UNIQUE,
              source TEXT NOT NULL DEFAULT 'local', is_system INTEGER NOT NULL DEFAULT 0,
              is_custom INTEGER NOT NULL DEFAULT 0, created_at REAL NOT NULL, updated_at REAL NOT NULL
            );
            CREATE TABLE IF NOT EXISTS genre_aliases (
              id INTEGER PRIMARY KEY, genre_id TEXT NOT NULL REFERENCES genres(id) ON DELETE CASCADE,
              alias TEXT NOT NULL, normalized_alias TEXT NOT NULL UNIQUE, source TEXT NOT NULL DEFAULT 'system',
              created_at REAL NOT NULL, updated_at REAL NOT NULL
            );
            CREATE TABLE IF NOT EXISTS anime_genres (
              anime_id INTEGER NOT NULL REFERENCES anime(id) ON DELETE CASCADE,
              genre_id TEXT NOT NULL REFERENCES genres(id) ON DELETE CASCADE,
              source TEXT NOT NULL DEFAULT 'local', created_at REAL NOT NULL, updated_at REAL NOT NULL,
              PRIMARY KEY(anime_id, genre_id, source)
            );
            CREATE INDEX IF NOT EXISTS idx_genre_aliases_genre ON genre_aliases(genre_id);
            CREATE INDEX IF NOT EXISTS idx_anime_genres_genre ON anime_genres(genre_id, anime_id);
            CREATE INDEX IF NOT EXISTS idx_anime_genres_anime ON anime_genres(anime_id, genre_id);
            CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS scan_runs (
              id INTEGER PRIMARY KEY, started_at REAL NOT NULL, finished_at REAL, status TEXT NOT NULL DEFAULT 'running', folders INTEGER DEFAULT 0,
              files INTEGER DEFAULT 0, videos INTEGER DEFAULT 0, animes INTEGER DEFAULT 0,
              episodes INTEGER DEFAULT 0, new_files INTEGER DEFAULT 0, updated_files INTEGER DEFAULT 0, unchanged_files INTEGER DEFAULT 0, ignored_files INTEGER DEFAULT 0, duplicate_files INTEGER DEFAULT 0, unknown_files INTEGER DEFAULT 0, reconciled_files INTEGER DEFAULT 0,
              scan_id TEXT, request_id TEXT, source TEXT, volume_id TEXT, scope TEXT, source_kind TEXT, scope_kind TEXT, scope_ref TEXT,
              native_generation INTEGER, generation_id TEXT, generation_status TEXT, cancelled INTEGER NOT NULL DEFAULT 0,
              batch_id TEXT, batch_number INTEGER DEFAULT 0, batch_size INTEGER DEFAULT 0, discovered INTEGER DEFAULT 0, processed INTEGER DEFAULT 0, inserted_files INTEGER DEFAULT 0, removed_files INTEGER DEFAULT 0, elapsed_ms INTEGER DEFAULT 0,
              errors TEXT NOT NULL DEFAULT '[]');
            ''')
            # Migration for databases made by earlier versions.
            existing = {r[1] for r in c.execute("PRAGMA table_info(folders)")}
            for column, definition in {
                "name": "TEXT", "kind": "TEXT NOT NULL DEFAULT 'path'", "authorization": "TEXT NOT NULL DEFAULT 'unknown'",
                "last_scan_at": "REAL", "last_error": "TEXT", "account_id": "TEXT",
                "saf_authority": "TEXT", "saf_document_id": "TEXT", "saf_volume_id": "TEXT", "saf_identity": "TEXT",
            }.items():
                if column not in existing:
                    c.execute(f"ALTER TABLE folders ADD COLUMN {column} {definition}")
            anime_columns = {r[1] for r in c.execute("PRAGMA table_info(anime)")}
            for column, definition in {"aliases": "TEXT DEFAULT '[]'", "description_original": "TEXT", "score": "INTEGER", "format": "TEXT", "metadata_updated_at": "REAL", "metadata_fetched_at": "REAL", "metadata_source": "TEXT NOT NULL DEFAULT 'unknown'", "metadata_confidence": "TEXT NOT NULL DEFAULT 'low'", "metadata_status": "TEXT NOT NULL DEFAULT 'unresolved'", "metadata_manual_fields": "TEXT NOT NULL DEFAULT '[]'", "media_kind": "TEXT NOT NULL DEFAULT 'series'", "anilist_match_status": "TEXT NOT NULL DEFAULT 'unmatched'", "anilist_match_score": "REAL", "anilist_match_margin": "REAL", "anilist_match_manual": "INTEGER NOT NULL DEFAULT 0", "favorite": "INTEGER NOT NULL DEFAULT 0", "user_tags": "TEXT NOT NULL DEFAULT '[]'", "is_pinned": "INTEGER NOT NULL DEFAULT 0", "personal_note": "TEXT"}.items():
                if column not in anime_columns:
                    c.execute(f"ALTER TABLE anime ADD COLUMN {column} {definition}")
            episode_columns = {r[1] for r in c.execute("PRAGMA table_info(episodes)")}
            for column, definition in {"mime_type": "TEXT", "file_size": "INTEGER", "modified_at": "REAL", "source_folder": "TEXT", "absolute_number": "REAL", "relative_path": "TEXT", "volume_id": "TEXT", "volume_uuid": "TEXT", "episode_type": "TEXT NOT NULL DEFAULT 'regular'", "episode_title": "TEXT", "identification_source": "TEXT NOT NULL DEFAULT 'legacy'", "identification_confidence": "TEXT NOT NULL DEFAULT 'medium'", "manual_override": "INTEGER NOT NULL DEFAULT 0", "last_played_at": "REAL", "media_identity": "TEXT", "availability_state": "TEXT NOT NULL DEFAULT 'available'"}.items():
                if column not in episode_columns:
                    c.execute(f"ALTER TABLE episodes ADD COLUMN {column} {definition}")
            c.execute("CREATE INDEX IF NOT EXISTS idx_episodes_anime_playback ON episodes(anime_id, missing, watched, last_played_at)")
            c.execute("CREATE INDEX IF NOT EXISTS idx_episodes_source_folder ON episodes(source_folder)")
            c.execute("CREATE INDEX IF NOT EXISTS idx_episodes_volume_availability ON episodes(volume_id, availability_state, missing)")
            c.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_episodes_media_identity ON episodes(media_identity) WHERE media_identity IS NOT NULL")
            c.execute("CREATE INDEX IF NOT EXISTS idx_episode_observation_scope_runtime ON episode_observations(source_kind, scope_kind, scope_ref, uri)")
            # Version records make additive schema changes auditable
            # while CREATE IF NOT EXISTS keeps all earlier databases intact.
            c.execute("CREATE INDEX IF NOT EXISTS idx_folders_account ON folders(account_id)")
            c.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_folders_saf_identity ON folders(saf_identity) WHERE saf_identity IS NOT NULL")
            scan_columns = {r[1] for r in c.execute("PRAGMA table_info(scan_runs)")}
            for column, definition in {
                "status": "TEXT NOT NULL DEFAULT 'running'",
                "new_files": "INTEGER DEFAULT 0", "updated_files": "INTEGER DEFAULT 0",
                "unchanged_files": "INTEGER DEFAULT 0", "ignored_files": "INTEGER DEFAULT 0",
                "duplicate_files": "INTEGER DEFAULT 0", "unknown_files": "INTEGER DEFAULT 0",
                "reconciled_files": "INTEGER DEFAULT 0", "scan_id": "TEXT",
                "request_id": "TEXT", "source": "TEXT", "volume_id": "TEXT", "scope": "TEXT",
                "source_kind": "TEXT", "scope_kind": "TEXT", "scope_ref": "TEXT",
                "native_generation": "INTEGER", "generation_id": "TEXT",
                "generation_status": "TEXT", "cancelled": "INTEGER NOT NULL DEFAULT 0",
                "batch_id": "TEXT", "batch_number": "INTEGER DEFAULT 0", "batch_size": "INTEGER DEFAULT 0",
                "discovered": "INTEGER DEFAULT 0", "processed": "INTEGER DEFAULT 0",
                "inserted_files": "INTEGER DEFAULT 0", "removed_files": "INTEGER DEFAULT 0",
                "elapsed_ms": "INTEGER DEFAULT 0",
            }.items():
                if column not in scan_columns:
                    c.execute(f"ALTER TABLE scan_runs ADD COLUMN {column} {definition}")
            c.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_scan_runs_scan_id ON scan_runs(scan_id) WHERE scan_id IS NOT NULL")
            c.execute("CREATE INDEX IF NOT EXISTS idx_scan_runs_status ON scan_runs(status, started_at)")
            c.execute("CREATE INDEX IF NOT EXISTS idx_anime_anilist_match ON anime(anilist_id, anilist_match_status, anilist_match_manual)")
            c.execute("UPDATE anime SET anilist_match_status=CASE WHEN metadata_status='manual' AND anilist_id IS NOT NULL THEN 'manual' WHEN anilist_id IS NOT NULL THEN 'matched' ELSE COALESCE(NULLIF(anilist_match_status,''),'unmatched') END WHERE anilist_id IS NOT NULL OR anilist_match_status IS NULL")
            artwork_columns = {r[1] for r in c.execute("PRAGMA table_info(artwork)")}
            for column, definition in {
                "artwork_key": "TEXT",
                "variant": "TEXT NOT NULL DEFAULT 'default'",
                "byte_size": "INTEGER",
                "width": "INTEGER",
                "height": "INTEGER",
                "checksum": "TEXT",
                "content_type": "TEXT",
                "last_access": "REAL",
                "next_retry_at": "REAL",
                "http_status": "INTEGER",
            }.items():
                if column not in artwork_columns:
                    c.execute(f"ALTER TABLE artwork ADD COLUMN {column} {definition}")
            c.execute("DROP INDEX IF EXISTS idx_artwork_key")
            c.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_artwork_key ON artwork(entity_type, entity_id, artwork_key) WHERE artwork_key IS NOT NULL")
            c.execute("CREATE INDEX IF NOT EXISTS idx_artwork_last_access ON artwork(last_access)")
            c.execute("CREATE INDEX IF NOT EXISTS idx_artwork_retry ON artwork(status, next_retry_at)")
            c.execute("CREATE INDEX IF NOT EXISTS idx_anime_pinned ON anime(is_pinned, added_at)")
            c.execute("CREATE INDEX IF NOT EXISTS idx_anime_media_kind ON anime(media_kind, added_at)")
            c.execute("CREATE INDEX IF NOT EXISTS idx_anime_added_title ON anime(added_at DESC, title COLLATE NOCASE, id DESC)")
            c.execute("CREATE INDEX IF NOT EXISTS idx_anime_favorite_added ON anime(favorite, added_at DESC, id DESC)")
            c.execute("CREATE INDEX IF NOT EXISTS idx_episodes_resume ON episodes(missing, last_played_at DESC, anime_id, episode_type)")
            c.execute("CREATE INDEX IF NOT EXISTS idx_episodes_season_number ON episodes(season, number, anime_id)")
            c.execute("CREATE INDEX IF NOT EXISTS idx_episodes_hierarchy ON episodes(anime_id, episode_type, season, number, absolute_number)")
            # Query-plan driven indexes:
            # - exact anime/season/number lookups cannot use idx_episodes_hierarchy
            #   efficiently because episode_type is its second key.
            # - per-anime resume windows benefit from last_played_at immediately
            #   after anime_id.
            c.execute("CREATE INDEX IF NOT EXISTS idx_episodes_anime_season_number_abs ON episodes(anime_id, season, number, absolute_number, id)")

            c.execute("INSERT OR IGNORE INTO schema_migrations(version,applied_at) VALUES (?,?)", (self.SCHEMA_VERSION, time.time()))
        # A process can disappear between begin_scan() and finish_scan().
        # Recovering here keeps startup deterministic while leaving the
        # recovery operation testable and reusable by callers.
        self.recover_interrupted_scans()

    def database_check(self):
        """Return a real SQLite health check without scanning library contents."""
        started = time.perf_counter()
        try:
            with self._conn() as con:
                result = con.execute("PRAGMA quick_check").fetchone()
            value = str(result[0] if result else "").strip().casefold()
            get_performance_monitor().record_sqlite("database_check", (time.perf_counter()-started)*1000.0, rows=1)
            return value == "ok", value or "unknown"
        except Exception as exc:
            get_performance_monitor().record_sqlite("database_check", (time.perf_counter()-started)*1000.0, rows=0, status="error")
            return False, str(exc)

    def get_preference(self, key, default=None):
        with self._conn() as c:
            row = c.execute("SELECT value FROM preferences WHERE key=?", (key,)).fetchone()
            return row["value"] if row else default

    @staticmethod
    def _normalize_scan_generation_status(status, *, cancelled=False):
        if cancelled:
            return "CANCELLED"
        value = str(status or "completed").strip().casefold()
        return {
            "started": "STARTED",
            "running": "RUNNING",
            "completed": "COMPLETED",
            "complete": "COMPLETED",
            "empty_complete": "EMPTY_COMPLETE",
            "partial": "PARTIAL",
            "cancelled": "CANCELLED",
            "canceled": "CANCELLED",
            "failed": "FAILED",
            "error": "FAILED",
            "interrupted": "FAILED",
            "revoked": "REVOKED",
            "unavailable": "UNAVAILABLE",
        }.get(value, "FAILED" if value else "COMPLETED")

    def set_native_volume_states(self, volumes):
        """Persist the latest native volume snapshot in the existing SQLite preferences store."""
        normalized = {}
        for item in volumes or []:
            if not isinstance(item, dict):
                continue
            volume_id = str(item.get("volumeId") or "").strip()
            if not volume_id:
                continue
            normalized[volume_id] = {
                "volumeId": volume_id,
                "uuid": str(item.get("uuid") or ""),
                "state": str(item.get("state") or "unknown"),
                "available": bool(item.get("available")) if "available" in item else str(item.get("state") or "").casefold() in {"mounted", "mounted_ro", "mounted_rofs"},
                "removable": bool(item.get("removable")),
                "emulated": bool(item.get("emulated")),
                "primary": bool(item.get("primary")),
                "directory": str(item.get("directory") or ""),
                "description": str(item.get("description") or ""),
                "observed_at": float(item.get("observed_at") or time.time()),
                "unavailable_at": item.get("unavailable_at"),
                "reason": str(item.get("reason") or ""),
            }
        self.set_preference("native_volume_states", json.dumps(normalized, ensure_ascii=False, sort_keys=True))
        return normalized

    def record_native_volume_change(self, payload):
        payload = payload or {}
        event_at = 0.0
        for candidate in (payload.get("eventTimestamp"), payload.get("timestamp"), payload.get("observedAt")):
            try:
                event_at = float(candidate or 0)
            except (TypeError, ValueError):
                event_at = 0.0
            if event_at > 0:
                break
        try:
            last_event_at = float(self.get_preference("native_volume_event_at", 0) or 0)
        except (TypeError, ValueError):
            last_event_at = 0.0
        previous = self.native_volume_states()
        if event_at and last_event_at and event_at < last_event_at:
            stale = dict(previous)
            stale["ignored"] = True
            return stale
        current = payload.get("current") or []
        removed = payload.get("removed") or []
        merged = dict(previous)
        mounted_states = {"mounted", "mounted_ro", "mounted_rofs"}

        for item in current:
            if not isinstance(item, dict):
                continue
            volume_id = str(item.get("volumeId") or "").strip()
            if not volume_id:
                continue
            state = str(item.get("state") or "unknown").strip().casefold()
            available = bool(item.get("available")) if "available" in item else state in mounted_states
            merged[volume_id] = {
                "volumeId": volume_id,
                "uuid": str(item.get("uuid") or ""),
                "state": state or "unknown",
                "available": available,
                "removable": bool(item.get("removable")),
                "emulated": bool(item.get("emulated")),
                "primary": bool(item.get("primary")),
                "directory": str(item.get("directory") or ""),
                "description": str(item.get("description") or ""),
                "observed_at": time.time(),
            }
            if not available:
                self.mark_volume_unavailable(volume_id, state)

        for item in removed:
            if not isinstance(item, dict):
                continue
            volume_id = str(item.get("volumeId") or item.get("uuid") or "").strip()
            if not volume_id:
                continue
            before = dict(merged.get(volume_id) or {})
            before.update({
                "volumeId": volume_id,
                "state": "unavailable",
                "available": False,
                "unavailable_at": time.time(),
                "reason": str(payload.get("reason") or "volume_removed"),
            })
            merged[volume_id] = before
            self.mark_volume_unavailable(volume_id, before["reason"])

        self.set_native_volume_states(list(merged.values()))
        if event_at:
            self.set_preference("native_volume_event_at", max(event_at, last_event_at))
        merged["ignored"] = False
        return merged

    def native_volume_states(self):
        raw = self.get_preference("native_volume_states", "{}")
        try:
            value = json.loads(raw or "{}")
        except (TypeError, json.JSONDecodeError):
            value = {}
        return value if isinstance(value, dict) else {}

    def set_preference(self, key, value):
        value = str(value)
        with self._conn() as c:
            c.execute("""INSERT INTO preferences(key,value,updated_at) VALUES (?,?,?)
                         ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at""",
                      (key, value, time.time()))

    def remove_preference(self, key):
        with self._conn() as c:
            c.execute("DELETE FROM preferences WHERE key=?", (key,))

    def library_summary(self):
        """Small settings projection; it never loads the full catalog."""
        started = time.perf_counter()
        with self._conn() as c:
            row = c.execute(
                """SELECT
                    (SELECT COUNT(*) FROM folders) AS folders,
                    (SELECT COUNT(*) FROM anime) AS animes,
                    (SELECT COUNT(*) FROM episodes) AS episodes,
                    (SELECT COUNT(*) FROM episodes WHERE last_played_at IS NOT NULL) AS history"""
            ).fetchone()
        result = {
            "folders": int(row["folders"] or 0),
            "animes": int(row["animes"] or 0),
            "episodes": int(row["episodes"] or 0),
            "history": int(row["history"] or 0),
        }
        get_performance_monitor().record_sqlite(
            "library_summary",
            (time.perf_counter()-started)*1000.0,
            rows=1,
            metadata={"sql_statements": 1},
        )
        return result

    def library_statistics(self):
        """Offline aggregate projection for Settings; never opens media or uses network."""
        with self._conn() as c:
            row = c.execute("""SELECT
                COUNT(*) AS animes, SUM(CASE WHEN favorite=1 THEN 1 ELSE 0 END) AS favorites,
                SUM(CASE WHEN is_pinned=1 THEN 1 ELSE 0 END) AS pinned,
                SUM(CASE WHEN NULLIF(TRIM(personal_note), '') IS NOT NULL THEN 1 ELSE 0 END) AS notes,
                SUM(CASE WHEN anilist_id IS NULL THEN 1 ELSE 0 END) AS without_metadata,
                SUM(CASE WHEN NULLIF(TRIM(cover_cache), '') IS NULL AND NULLIF(TRIM(cover_url), '') IS NULL THEN 1 ELSE 0 END) AS without_cover
                FROM anime""").fetchone()
            episodes = c.execute("""SELECT COUNT(*) AS total, SUM(CASE WHEN missing=0 THEN 1 ELSE 0 END) AS available,
                SUM(CASE WHEN missing=0 AND watched=1 THEN 1 ELSE 0 END) AS watched,
                SUM(CASE WHEN missing=0 AND progress>0 AND watched=0 THEN 1 ELSE 0 END) AS active,
                SUM(CASE WHEN missing=0 THEN progress ELSE 0 END) AS recorded_seconds,
                SUM(CASE WHEN missing=0 THEN duration ELSE 0 END) AS duration_seconds FROM episodes""").fetchone()
            tags = c.execute("SELECT user_tags FROM anime").fetchall()
            anime_state_rows = c.execute("""SELECT anime_id,
                SUM(CASE WHEN missing=0 THEN 1 ELSE 0 END) AS available,
                SUM(CASE WHEN missing=0 AND watched=1 THEN 1 ELSE 0 END) AS watched,
                SUM(CASE WHEN missing=0 AND progress>0 AND watched=0 THEN 1 ELSE 0 END) AS active
                FROM episodes GROUP BY anime_id""").fetchall()
        tag_count = len({str(tag).casefold() for entry in tags for tag in self._decode_tags(entry["user_tags"])})
        state_completed = sum(bool(r["available"]) and r["watched"] == r["available"] for r in anime_state_rows)
        state_active = sum(bool(r["active"]) for r in anime_state_rows)
        state_not_started = sum(bool(r["available"]) and not r["watched"] and not r["active"] for r in anime_state_rows)
        available = int(episodes["available"] or 0)
        watched = int(episodes["watched"] or 0)
        return {"animes": int(row["animes"] or 0), "episodes": int(episodes["total"] or 0), "episodes_available": available,
                "episodes_watched": watched, "animes_in_progress": state_active,
                "animes_completed": state_completed, "animes_not_started": state_not_started,
                "favorites": int(row["favorites"] or 0), "pinned": int(row["pinned"] or 0), "notes": int(row["notes"] or 0),
                "tags": tag_count, "without_metadata": int(row["without_metadata"] or 0), "without_cover": int(row["without_cover"] or 0),
                "recorded_seconds": float(episodes["recorded_seconds"] or 0), "available_duration_seconds": float(episodes["duration_seconds"] or 0)}

    @staticmethod
    def _normalize_json_list(value):
        if isinstance(value, str):
            return value
        if value is None:
            return "[]"
        try:
            return json.dumps(list(value), ensure_ascii=False)
        except (TypeError, ValueError):
            return "[]"

    @staticmethod
    def _decode_tags(value):
        try:
            decoded = json.loads(value or "[]")
            return decoded if isinstance(decoded, list) else []
        except (TypeError, json.JSONDecodeError):
            return []

    def _anime_state_count(self, state):
        # A single grouped query keeps aggregate state semantics aligned with the catalog.
        with self._conn() as c:
            rows = c.execute("""SELECT anime_id, SUM(CASE WHEN missing=0 THEN 1 ELSE 0 END) available,
                SUM(CASE WHEN missing=0 AND watched=1 THEN 1 ELSE 0 END) watched,
                SUM(CASE WHEN missing=0 AND progress>0 AND watched=0 THEN 1 ELSE 0 END) active FROM episodes GROUP BY anime_id""").fetchall()
        if state == "completed": return sum(bool(r["available"]) and r["watched"] == r["available"] for r in rows)
        if state == "in_progress": return sum(bool(r["active"]) for r in rows)
        return sum(bool(r["available"]) and not r["watched"] and not r["active"] for r in rows)


    @staticmethod
    def _validate_backup_database(path):
        """Validate an extracted backup without mutating the live database."""
        with sqlite3.connect(path) as c:
            c.execute("PRAGMA foreign_keys=ON")
            tables = {row[0] for row in c.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )}
            required = {
                "folders", "anime", "episodes", "episode_observations", "artwork",
                "associations", "pending_matches", "preferences", "schema_migrations",
                "scan_runs", "genres", "genre_aliases", "anime_genres", "account",
            }
            if not required.issubset(tables):
                missing = ", ".join(sorted(required - tables))
                raise ValueError(f"Backup incompleto: tabelas ausentes: {missing}")
            version = c.execute("SELECT MAX(version) FROM schema_migrations").fetchone()[0]
            if int(version or 0) != LibraryStore.SCHEMA_VERSION:
                raise ValueError(
                    f"Schema de backup incompatível: {version or 0}; esperado {LibraryStore.SCHEMA_VERSION}."
                )
            integrity = c.execute("PRAGMA integrity_check").fetchone()
            if str(integrity[0] if integrity else "").strip().casefold() != "ok":
                raise ValueError("Backup SQLite inválido: integrity_check falhou.")
            foreign = c.execute("PRAGMA foreign_key_check").fetchone()
            if foreign:
                raise ValueError("Backup contém inconsistências de integridade referencial.")

    def create_backup_snapshot(self, destination):
        """Create a consistent SQLite snapshot through SQLite's backup API."""
        destination = os.path.abspath(os.path.expanduser(str(destination)))
        os.makedirs(os.path.dirname(destination), exist_ok=True)
        with sqlite3.connect(self.db_path) as source, sqlite3.connect(destination) as snapshot:
            source.backup(snapshot)
        return destination

    @staticmethod
    def _restore_table_columns(connection, schema_name, table):
        rows = connection.execute(
            f"PRAGMA {schema_name}.table_info({table})"
        ).fetchall()
        return [row[1] for row in rows]

    def _reconcile_restored_files_locked(self, connection):
        """Project physical-path availability without deleting logical catalog rows.
        
        Native content:// references cannot be verified from Python. They remain
        untouched so the existing MediaStore/SAF reconciliation can prove their
        availability later. Absolute filesystem paths are checked immediately.
        """
        from urllib.parse import urlparse
        rows = connection.execute(
            "SELECT id,path,availability_state FROM episodes"
        ).fetchall()
        for row in rows:
            path = str(row["path"] or "").strip()
            if not path:
                connection.execute(
                    "UPDATE episodes SET missing=1,availability_state='missing' "
                    "WHERE id=? AND availability_state!='scope_removed'",
                    (row["id"],),
                )
                continue
            scheme = urlparse(path).scheme.casefold()
            if scheme in {"content", "file", "http", "https"}:
                continue
            exists = os.path.isfile(path)
            connection.execute(
                "UPDATE episodes SET missing=?,availability_state=? "
                "WHERE id=? AND availability_state!='scope_removed'",
                (0 if exists else 1, "available" if exists else "missing", row["id"]),
            )

    def restore_backup_transaction(self, source_db, *, artwork_mappings=None):
        """Restore one validated database inside one SQLite transaction.
        
        Authentication is deliberately not imported. The existing account table
        remains owned by the current installation, while all logical library
        state is replaced from the validated snapshot. A transaction guarantees
        that an import failure leaves the previous catalog untouched.
        """
        source_db = os.path.abspath(os.path.expanduser(str(source_db)))
        if not os.path.isfile(source_db):
            raise FileNotFoundError(source_db)
        self._validate_backup_database(source_db)

        delete_order = (
            "episode_observations",
            "anime_genres",
            "genre_aliases",
            "episodes",
            "artwork",
            "associations",
            "pending_matches",
            "scan_runs",
            "folders",
            "anime",
            "genres",
            "preferences",
            "schema_migrations",
        )
        insert_order = (
            "schema_migrations",
            "preferences",
            "folders",
            "anime",
            "genres",
            "genre_aliases",
            "episodes",
            "artwork",
            "associations",
            "pending_matches",
            "anime_genres",
            "episode_observations",
            "scan_runs",
        )
        with self._conn() as con:
            attached = False
            try:
                con.execute("ATTACH DATABASE ? AS restore_db", (source_db,))
                attached = True
                con.execute("BEGIN IMMEDIATE")
                for table in delete_order:
                    con.execute(f"DELETE FROM main.{table}")
                for table in insert_order:
                    main_columns = self._restore_table_columns(con, "main", table)
                    source_columns = self._restore_table_columns(con, "restore_db", table)
                    if main_columns != source_columns:
                        raise ValueError(f"Schema incompatível na tabela {table}.")
                    columns = ",".join(main_columns)
                    con.execute(
                        f"INSERT INTO main.{table} ({columns}) "
                        f"SELECT {columns} FROM restore_db.{table}"
                    )
                for old_path, new_path in tuple(artwork_mappings or ()):
                    if not old_path or not new_path:
                        continue
                    con.execute(
                        "UPDATE artwork SET local_path=?,source_ref=? WHERE local_path=? AND manual=1",
                        (new_path, new_path, old_path),
                    )
                    con.execute(
                        "UPDATE anime SET cover_cache=? WHERE cover_cache=?",
                        (new_path, old_path),
                    )
                self._reconcile_restored_files_locked(con)
                integrity = con.execute("PRAGMA integrity_check").fetchone()
                if str(integrity[0] if integrity else "").strip().casefold() != "ok":
                    raise ValueError("SQLite integrity_check falhou durante restore.")
                if con.execute("PRAGMA foreign_key_check").fetchone():
                    raise ValueError("foreign_key_check falhou durante restore.")
                duplicate = con.execute(
                    "SELECT media_identity FROM episodes "
                    "WHERE media_identity IS NOT NULL AND TRIM(media_identity)!='' "
                    "GROUP BY media_identity HAVING COUNT(*)>1 LIMIT 1"
                ).fetchone()
                if duplicate:
                    raise ValueError("Restore produziria identidade de mídia duplicada.")
                con.execute("COMMIT")
            except Exception:
                try:
                    con.rollback()
                finally:
                    if attached:
                        try:
                            con.execute("DETACH DATABASE restore_db")
                        except sqlite3.Error:
                            pass
                raise
            else:
                if attached:
                    con.execute("DETACH DATABASE restore_db")
        self._last_playback_event_at.clear()
        return True

    def create_backup(self, destination=None):
        """Compatibility facade over the versioned BackupService."""
        from core.backup import BackupService
        return BackupService(self).create_backup_file(destination)

    def latest_backup(self):
        candidates = [
            os.path.join(self.backup_dir, name)
            for name in os.listdir(self.backup_dir)
            if name.endswith(".zip") and not name.endswith(".tmp")
        ]
        return max(candidates, key=os.path.getmtime) if candidates else None

    @staticmethod
    def _safe_zip_members(archive):
        # Kept for compatibility with older callers. BackupService now owns the
        # complete ZIP security policy and checksum validation.
        members = []
        for info in archive.infolist():
            name = str(info.filename).replace("\\", "/")
            if not name or name.startswith("/") or name.startswith("../") or "/../" in name or name == "..":
                raise ValueError("Backup contém um caminho inválido.")
            members.append((info, name))
        return members

    def restore_backup(self, backup_path=None):
        """Compatibility facade over the versioned BackupService."""
        from core.backup import BackupService
        chosen = backup_path or self.latest_backup()
        if not chosen:
            raise FileNotFoundError("Nenhum backup local ReiAnix foi encontrado.")
        return BackupService(self).restore_file(chosen)
    
    def clear_anilist_metadata_cache(self):
        """Expire metadata and cover paths, preserving library rows and associations."""
        with self._conn() as c:
            c.execute("UPDATE anime SET metadata_updated_at=NULL, cover_cache=''")

    def folders(self):
        started = time.perf_counter()
        with self._conn() as c:
            rows = [dict(r) for r in c.execute("SELECT * FROM folders ORDER BY added_at")]
        get_performance_monitor().record_sqlite("folders", (time.perf_counter()-started)*1000.0, rows=len(rows))
        return rows

    def add_folder(self, reference, name=None, kind="path", authorization="granted", account_id=None,
                   saf_authority=None, saf_document_id=None, saf_volume_id=None, saf_identity=None):
        reference = str(reference or "").strip()
        if not reference:
            raise ValueError("A fonte da biblioteca não pode ser vazia.")
        name = name or os.path.basename(reference.rstrip("/")) or reference
        with self._conn() as c:
            if str(kind or "").casefold() == "saf" and saf_identity:
                existing = c.execute(
                    "SELECT path FROM folders WHERE saf_identity=? LIMIT 1",
                    (str(saf_identity),),
                ).fetchone()
                if existing and str(existing["path"]) != reference:
                    existing_path = str(existing["path"])
                    c.execute(
                        """UPDATE folders SET name=?,kind='saf',authorization=?,
                           account_id=COALESCE(?,account_id),
                           saf_authority=COALESCE(?,saf_authority),
                           saf_document_id=COALESCE(?,saf_document_id),
                           saf_volume_id=COALESCE(?,saf_volume_id),
                           saf_identity=?,last_error=NULL,last_scan_at=NULL
                           WHERE path=?""",
                        (name, authorization, account_id, saf_authority, saf_document_id,
                         saf_volume_id, str(saf_identity), existing_path),
                    )
                    return existing_path
            c.execute("""INSERT INTO folders(
                            path,name,kind,authorization,account_id,added_at,
                            saf_authority,saf_document_id,saf_volume_id,saf_identity
                         ) VALUES (?,?,?,?,?,?,?,?,?,?)
                         ON CONFLICT(path) DO UPDATE SET
                            name=excluded.name,kind=excluded.kind,
                            authorization=excluded.authorization,
                            account_id=COALESCE(excluded.account_id,folders.account_id),
                            saf_authority=COALESCE(excluded.saf_authority,folders.saf_authority),
                            saf_document_id=COALESCE(excluded.saf_document_id,folders.saf_document_id),
                            saf_volume_id=COALESCE(excluded.saf_volume_id,folders.saf_volume_id),
                            saf_identity=COALESCE(excluded.saf_identity,folders.saf_identity),
                            last_error=NULL""",
                      (reference, name, kind, authorization, account_id,
                       time.time(), saf_authority, saf_document_id, saf_volume_id, saf_identity))
            return reference

    def update_saf_identity(self, reference, authority, document_id, volume_id=None, identity=None):
        with self._conn() as c:
            c.execute(
                """UPDATE folders
                   SET kind='saf',saf_authority=?,saf_document_id=?,saf_volume_id=?,saf_identity=?
                   WHERE path=?""",
                (authority, document_id, volume_id, identity, reference),
            )

    def saf_folder_by_identity(self, identity):
        if not identity:
            return None
        with self._conn() as c:
            row = c.execute("SELECT * FROM folders WHERE saf_identity=? LIMIT 1", (str(identity),)).fetchone()
            return dict(row) if row else None

    def update_folder_status(self, reference, authorization, error=None):
        with self._conn() as c:
            c.execute("UPDATE folders SET authorization=?,last_error=?,last_scan_at=? WHERE path=?",
                      (authorization, error, time.time(), reference))

    def remove_folder(self, reference):
        """Remove one configured discovery source without destroying cross-source media."""
        reference = str(reference or "").strip()
        if not reference:
            return
        source_kind = self._infer_source_kind(reference)
        with self._conn() as c:
            if source_kind == "broad_storage":
                scope_filter = "source_kind='broad_storage'"
                params = ()
            elif source_kind == "saf":
                scope_filter = "source_kind='saf' AND scope_ref=?"
                params = (reference,)
            else:
                scope_filter = "source_kind=? AND scope_ref=?"
                params = (source_kind, reference)
            rows = c.execute(
                "SELECT DISTINCT episode_id FROM episode_observations WHERE " + scope_filter,
                params,
            ).fetchall()
            for row in rows:
                c.execute(
                    "UPDATE episode_observations SET state='scope_removed',error='source_removed',last_checked_at=? WHERE episode_id=? AND " + scope_filter,
                    (time.time(), row["episode_id"], *params),
                )
                self._recompute_episode_availability_locked(c, row["episode_id"])
            c.execute(
                """UPDATE episodes SET missing=1,availability_state='scope_removed'
                   WHERE source_folder=? AND id NOT IN (SELECT episode_id FROM episode_observations)""",
                (reference,),
            )
            c.execute("DELETE FROM folders WHERE path=?", (reference,))

    def begin_scan(self, scan_id=None, *, source_kind=None, scope_kind="global", scope_ref=None, native_generation=None, generation_id=None):
        import uuid
        scan_id = scan_id or str(uuid.uuid4())
        generation_id = str(generation_id or (f"native:{native_generation}" if native_generation is not None else scan_id))
        with self._conn() as c:
            cur = c.execute(
                """INSERT INTO scan_runs(started_at,status,scan_id,source_kind,scope_kind,scope_ref,native_generation,generation_id,generation_status,cancelled)
                   VALUES (?, 'running', ?, ?, ?, ?, ?, ?, 'RUNNING', 0)""",
                (time.time(), scan_id, source_kind, scope_kind, scope_ref, native_generation, generation_id),
            )
            return cur.lastrowid

    def has_observation_for_generation(self, uri, *, source_kind, scope_kind, scope_ref=None, native_generation=None):
        uri = str(uri or "").strip()
        if not uri or native_generation is None:
            return False
        with self._conn() as c:
            row = c.execute(
                """SELECT 1 FROM episode_observations
                   WHERE uri=? AND source_kind=? AND scope_kind=? AND scope_ref=?
                     AND native_generation=? LIMIT 1""",
                (uri, str(source_kind or "unknown").casefold(), str(scope_kind or "source").casefold(),
                 self._scope_ref(scope_ref), int(native_generation)),
            ).fetchone()
            return row is not None

    def update_scan_progress(self, run_id, summary, *, request_id=None, source=None, volume_id=None, scope=None,
                             batch_id=None, batch_number=None, batch_size=None, discovered=None, processed=None,
                             inserted=None, removed=None, elapsed_ms=None, errors=None):
        """Update one running scan atomically without replacing its counters."""
        if not run_id:
            return False
        with self._conn() as c:
            row = c.execute("SELECT errors FROM scan_runs WHERE id=?", (run_id,)).fetchone()
            existing_errors = []
            if row:
                try:
                    existing_errors = json.loads(row["errors"] or "[]")
                except (TypeError, json.JSONDecodeError):
                    existing_errors = []
            if not isinstance(existing_errors, list):
                existing_errors = []
            for item in (errors or []):
                if str(item) not in existing_errors:
                    existing_errors.append(str(item))
            c.execute(
                """UPDATE scan_runs SET
                    files=files+?, videos=videos+?, new_files=new_files+?, updated_files=updated_files+?,
                    unchanged_files=unchanged_files+?, ignored_files=ignored_files+?,
                    duplicate_files=duplicate_files+?, unknown_files=unknown_files+?, reconciled_files=reconciled_files+?,
                    request_id=COALESCE(?,request_id), source=COALESCE(?,source), volume_id=COALESCE(?,volume_id),
                    scope=COALESCE(?,scope), batch_id=COALESCE(?,batch_id),
                    batch_number=COALESCE(?,batch_number), batch_size=COALESCE(?,batch_size),
                    discovered=COALESCE(?,discovered), processed=COALESCE(?,processed),
                    inserted_files=COALESCE(?,inserted_files), removed_files=COALESCE(?,removed_files),
                    elapsed_ms=COALESCE(?,elapsed_ms), errors=?
                   WHERE id=?""",
                (
                    int(summary.get("files", 0)), int(summary.get("videos", 0)),
                    int(summary.get("new", 0)), int(summary.get("updated", 0)),
                    int(summary.get("unchanged", 0)), int(summary.get("ignored", 0)),
                    int(summary.get("duplicates", 0)), int(summary.get("unknown", 0)),
                    int(summary.get("reconciled", 0)), request_id, source, volume_id, scope,
                    batch_id, batch_number, batch_size, discovered, processed, inserted, removed,
                    elapsed_ms, json.dumps(existing_errors, ensure_ascii=False), run_id,
                ),
            )
            return True

    def finish_scan(self, run_id, summary):
        status = str(summary.get("status", "completed") or "completed").casefold()
        cancelled = bool(summary.get("cancelled")) or status in {"cancelled", "canceled"}
        generation_status = self._normalize_scan_generation_status(status, cancelled=cancelled)
        final_status = "cancelled" if cancelled else ("error" if status in {"error", "failed"} else status)
        with self._conn() as c:
            c.execute("""UPDATE scan_runs SET finished_at=?,status=?,folders=?,files=?,videos=?,animes=?,episodes=?,
                         new_files=?,updated_files=?,unchanged_files=?,ignored_files=?,duplicate_files=?,unknown_files=?,reconciled_files=?,
                         generation_status=?,cancelled=?,discovered=?,processed=?,inserted_files=?,removed_files=?,
                         elapsed_ms=?,errors=? WHERE id=?""",
                      (time.time(), final_status, summary.get("folders", 0), summary.get("files", 0), summary.get("videos", 0),
                       summary.get("animes", 0), summary.get("episodes", 0), summary.get("new", 0), summary.get("updated", 0),
                       summary.get("unchanged", 0), summary.get("ignored", 0), summary.get("duplicates", 0),
                       summary.get("unknown", 0), summary.get("reconciled", 0), generation_status, int(cancelled),
                       int(summary.get("discovered", summary.get("files", 0)) or 0),
                       int(summary.get("processed", summary.get("files", 0)) or 0),
                       int(summary.get("inserted", summary.get("new", 0)) or 0),
                       int(summary.get("removed", summary.get("reconciled", 0)) or 0),
                       int(summary.get("elapsed_ms", 0) or 0),
                       json.dumps(summary.get("errors", []), ensure_ascii=False), run_id))

    def last_scan(self):
        started = time.perf_counter()
        with self._conn() as c:
            row = c.execute("SELECT * FROM scan_runs ORDER BY id DESC LIMIT 1").fetchone()
        get_performance_monitor().record_sqlite("last_scan", (time.perf_counter()-started)*1000.0, rows=1 if row else 0)
        return dict(row) if row else None

    def scan_by_id(self, scan_id):
        """Return a persisted scan record without materializing the catalog."""
        if not scan_id:
            return None
        with self._conn() as c:
            row = c.execute("SELECT * FROM scan_runs WHERE scan_id=? LIMIT 1", (str(scan_id),)).fetchone()
            return dict(row) if row else None

    def latest_native_generation(self, source_kind, scope_kind, scope_ref):
        """Return the newest observed native generation, regardless of outcome."""
        with self._conn() as c:
            row = c.execute(
                """SELECT native_generation FROM scan_runs
                   WHERE source_kind=? AND scope_kind=? AND scope_ref=?
                     AND native_generation IS NOT NULL
                   ORDER BY native_generation DESC, id DESC LIMIT 1""",
                (source_kind, scope_kind, scope_ref),
            ).fetchone()
            return int(row["native_generation"]) if row and row["native_generation"] is not None else None

    def latest_completed_native_generation(self, source_kind, scope_kind, scope_ref):
        with self._conn() as c:
            row = c.execute(
                """SELECT native_generation FROM scan_runs
                   WHERE source_kind=? AND scope_kind=? AND scope_ref=?
                     AND native_generation IS NOT NULL
                     AND generation_status IN ('COMPLETED','EMPTY_COMPLETE')
                   ORDER BY native_generation DESC, id DESC LIMIT 1""",
                (source_kind, scope_kind, scope_ref),
            ).fetchone()
            return int(row["native_generation"]) if row and row["native_generation"] is not None else None

    def has_native_event(self, event_id):
        event_id = str(event_id or "").strip()
        if not event_id:
            return False
        with self._conn() as c:
            row = c.execute("SELECT value FROM preferences WHERE key='native_event_ids'").fetchone()
            try:
                ids = json.loads(row["value"]) if row else []
            except (TypeError, json.JSONDecodeError):
                ids = []
            return event_id in ids if isinstance(ids, list) else False

    def claim_native_event(self, event_id, *, limit=1000):
        """Atomically remember a mailbox event id using the existing preferences store.
        
        NativeMailbox already gives every event an eventId. Keeping the bounded
        dedupe ledger in preferences avoids a second persistence subsystem/table.
        """
        if not event_id:
            return True
        event_id = str(event_id).strip()
        if not event_id:
            return True
        with self._conn() as c:
            row = c.execute("SELECT value FROM preferences WHERE key='native_event_ids'").fetchone()
            try:
                ids = json.loads(row["value"]) if row else []
            except (TypeError, json.JSONDecodeError):
                ids = []
            if not isinstance(ids, list):
                ids = []
            if event_id in ids:
                return False
            ids.append(event_id)
            ids = ids[-max(1, int(limit)):]
            c.execute(
                """INSERT INTO preferences(key,value,updated_at) VALUES ('native_event_ids',?,?)
                   ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at""",
                (json.dumps(ids, ensure_ascii=False), time.time()),
            )
            return True

    def has_native_request(self, request_id, *, namespace="default"):
        """Check a bounded requestId ledger stored in the existing preferences table."""
        request_id = str(request_id or "").strip()
        namespace = str(namespace or "default").strip() or "default"
        if not request_id:
            return False
        key = f"native_request_ids:{namespace}"
        with self._conn() as c:
            row = c.execute("SELECT value FROM preferences WHERE key=?", (key,)).fetchone()
            try:
                ids = json.loads(row["value"]) if row else []
            except (TypeError, json.JSONDecodeError):
                ids = []
            return request_id in ids if isinstance(ids, list) else False

    def claim_native_request(self, request_id, *, namespace="default", limit=1000):
        """Atomically claim a requestId without introducing a new table or store."""
        request_id = str(request_id or "").strip()
        namespace = str(namespace or "default").strip() or "default"
        if not request_id:
            return True
        key = f"native_request_ids:{namespace}"
        with self._conn() as c:
            row = c.execute("SELECT value FROM preferences WHERE key=?", (key,)).fetchone()
            try:
                ids = json.loads(row["value"]) if row else []
            except (TypeError, json.JSONDecodeError):
                ids = []
            if not isinstance(ids, list):
                ids = []
            if request_id in ids:
                return False
            ids.append(request_id)
            ids = ids[-max(1, int(limit)):]
            c.execute(
                """INSERT INTO preferences(key,value,updated_at) VALUES (?,?,?)
                   ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at""",
                (key, json.dumps(ids, ensure_ascii=False), time.time()),
            )
            return True

    def interrupted_scans(self):
        """Return scans that were interrupted by a prior process shutdown."""
        with self._conn() as c:
            return [dict(row) for row in c.execute(
                "SELECT * FROM scan_runs WHERE status='interrupted' ORDER BY id DESC"
            )]

    def recover_interrupted_scans(self):
        """Finalize orphaned scan runs without touching library media rows."""
        with self._conn() as c:
            rows = c.execute(
                "SELECT id FROM scan_runs WHERE status='running' AND finished_at IS NULL"
            ).fetchall()
            if not rows:
                return 0
            c.executemany(
                "UPDATE scan_runs SET status='interrupted',generation_status='FAILED',cancelled=1,finished_at=? WHERE id=?",
                ((time.time(), row["id"]) for row in rows),
            )
            return len(rows)

    def anilist_match(self, lookup):
        with self._conn() as c:
            row = c.execute(
                """SELECT id,anilist_id,anilist_match_status,anilist_match_score,
                          anilist_match_margin,anilist_match_manual,metadata_status
                   FROM anime WHERE lookup_title=?""",
                (lookup,),
            ).fetchone()
            return dict(row) if row else None

    def set_anilist_match(self, lookup, anilist_id, *, status="matched", score=None, margin=None, manual=False):
        with self._conn() as c:
            row = c.execute("SELECT id FROM anime WHERE lookup_title=?", (lookup,)).fetchone()
            if not row:
                raise ValueError("Obra local não encontrada.")
            c.execute(
                """UPDATE anime SET anilist_id=?,anilist_match_status=?,
                                   anilist_match_score=?,anilist_match_margin=?,
                                   anilist_match_manual=? WHERE id=?""",
                (anilist_id, str(status or "unmatched"), score, margin, int(bool(manual)), row["id"]),
            )
            if anilist_id is not None:
                c.execute("INSERT OR REPLACE INTO associations VALUES (?,?)", (lookup, anilist_id))
            else:
                c.execute("DELETE FROM associations WHERE lookup_title=?", (lookup,))
            return True

    def clear_anilist_match(self, lookup):
        with self._conn() as c:
            row = c.execute("SELECT id FROM anime WHERE lookup_title=?", (lookup,)).fetchone()
            if not row:
                raise ValueError("Obra local não encontrada.")
            c.execute(
                """UPDATE anime SET anilist_id=NULL,anilist_match_status='unmatched',
                                   anilist_match_score=NULL,anilist_match_margin=NULL,
                                   anilist_match_manual=0,metadata_status='unresolved',
                                   metadata_source='local',metadata_updated_at=NULL,
                                   metadata_fetched_at=NULL WHERE id=?""",
                (row["id"],),
            )
            c.execute("DELETE FROM associations WHERE lookup_title=?", (lookup,))
            c.execute("DELETE FROM pending_matches WHERE lookup_title=?", (lookup,))
            return True
    def association(self, lookup):
        with self._conn() as c:
            r = c.execute("SELECT anilist_id FROM associations WHERE lookup_title=?", (lookup,)).fetchone()
            return r[0] if r else None

    def anime_metadata_by_id(self, anime_id):
        """Return one local anime row by its canonical internal identity."""
        try:
            anime_id = int(anime_id)
        except (TypeError, ValueError):
            return None
        if anime_id <= 0:
            return None
        with self._conn() as c:
            row = c.execute("SELECT * FROM anime WHERE id=?", (anime_id,)).fetchone()
            return dict(row) if row else None

    def resolve_local_anime_owner(
        self,
        *,
        anime_id=None,
        media_identity=None,
        path=None,
        source_folder=None,
        relative_path=None,
        volume_id=None,
        lookup_title=None,
    ):
        """Resolve the canonical local anime owner without making title the primary identity."""
        normalized_identity = str(media_identity or "").strip()
        normalized_path = str(path or "").strip()
        normalized_source = str(source_folder or "").strip()
        normalized_relative = str(relative_path or "").strip().replace(chr(92), "/").strip("/")
        normalized_volume = str(volume_id or "").strip()
        normalized_lookup = str(lookup_title or "").strip()

        with self._conn() as c:
            try:
                candidate_id = int(anime_id) if anime_id is not None else None
            except (TypeError, ValueError):
                candidate_id = None
            if candidate_id and candidate_id > 0:
                row = c.execute("SELECT id FROM anime WHERE id=?", (candidate_id,)).fetchone()
                if row:
                    return int(row["id"])

            if normalized_identity:
                rows = c.execute(
                    "SELECT DISTINCT anime_id FROM episodes WHERE media_identity=? ORDER BY anime_id",
                    (normalized_identity,),
                ).fetchall()
                owner_ids = {int(row["anime_id"]) for row in rows if row["anime_id"] is not None}
                if len(owner_ids) == 1:
                    return next(iter(owner_ids))

            if normalized_path:
                rows = c.execute(
                    "SELECT DISTINCT anime_id FROM episodes WHERE path=? ORDER BY anime_id",
                    (normalized_path,),
                ).fetchall()
                owner_ids = {int(row["anime_id"]) for row in rows if row["anime_id"] is not None}
                if len(owner_ids) == 1:
                    return next(iter(owner_ids))

            if normalized_source and normalized_relative:
                if normalized_volume:
                    rows = c.execute(
                        """SELECT DISTINCT anime_id FROM episodes
                           WHERE source_folder=? AND relative_path=? AND volume_id=?
                           ORDER BY anime_id""",
                        (normalized_source, normalized_relative, normalized_volume),
                    ).fetchall()
                else:
                    rows = c.execute(
                        """SELECT DISTINCT anime_id FROM episodes
                           WHERE source_folder=? AND relative_path=?
                           ORDER BY anime_id""",
                        (normalized_source, normalized_relative),
                    ).fetchall()
                owner_ids = {int(row["anime_id"]) for row in rows if row["anime_id"] is not None}
                if len(owner_ids) == 1:
                    return next(iter(owner_ids))

            if normalized_lookup:
                row = c.execute(
                    "SELECT id FROM anime WHERE lookup_title=?",
                    (normalized_lookup,),
                ).fetchone()
                if row:
                    return int(row["id"])
        return None

    def anime_metadata(self, lookup):
        """Return the cached AniList-derived metadata for a local title."""
        with self._conn() as c:
            row = c.execute("SELECT * FROM anime WHERE lookup_title=?", (lookup,)).fetchone()
            return dict(row) if row else None

    def set_association(self, lookup, anilist_id):
        with self._conn() as c: c.execute("INSERT OR REPLACE INTO associations VALUES (?,?)", (lookup, anilist_id))

    def set_pending_match(self, lookup, display_title, candidates):
        with self._conn() as c: c.execute("INSERT OR REPLACE INTO pending_matches VALUES (?,?,?)", (lookup, display_title, json.dumps(candidates, ensure_ascii=False)))

    def pending_matches(self):
        with self._conn() as c:
            return [{"lookup_title": r["lookup_title"], "display_title": r["display_title"], "candidates": json.loads(r["candidates"])} for r in c.execute("SELECT * FROM pending_matches ORDER BY display_title")]

    def resolve_match(self, lookup, anilist_id):
        with self._conn() as c:
            c.execute("INSERT OR REPLACE INTO associations VALUES (?,?)", (lookup, anilist_id))
            c.execute("DELETE FROM pending_matches WHERE lookup_title=?", (lookup,))

    def toggle_favorite(self, anime_id):
        with self._conn() as c:
            c.execute("UPDATE anime SET favorite=1-favorite WHERE id=?", (anime_id,))
            row = c.execute("SELECT favorite FROM anime WHERE id=?", (anime_id,)).fetchone()
            return bool(row and row[0])

    def toggle_pinned(self, anime_id):
        with self._conn() as c:
            if not c.execute("UPDATE anime SET is_pinned=1-is_pinned WHERE id=?", (anime_id,)).rowcount:
                raise ValueError("Anime local não encontrado.")
            return bool(c.execute("SELECT is_pinned FROM anime WHERE id=?", (anime_id,)).fetchone()[0])

    def set_personal_note(self, anime_id, note):
        note = "" if note is None else str(note).strip()
        if len(note) > 2000:
            raise ValueError("A nota pessoal pode ter no máximo 2000 caracteres.")
        with self._conn() as c:
            if not c.execute("UPDATE anime SET personal_note=? WHERE id=?", (note or None, anime_id)).rowcount:
                raise ValueError("Anime local não encontrado.")
        return note or None

    def is_favorite(self, anime_id):
        with self._conn() as c:
            row = c.execute("SELECT favorite FROM anime WHERE id=?", (anime_id,)).fetchone()
            return bool(row and row[0])

    def set_user_tags(self, anime_id, tags):
        """Persist a small, private set of labels without touching AniList metadata."""
        normalized = []
        for tag in tags or []:
            tag = " ".join(str(tag).split()).strip()
            if tag and tag.casefold() not in {item.casefold() for item in normalized}:
                normalized.append(tag[:40])
        with self._conn() as c:
            if not c.execute("UPDATE anime SET user_tags=? WHERE id=?", (json.dumps(normalized, ensure_ascii=False), anime_id)).rowcount:
                raise ValueError("Anime local não encontrado.")
        return normalized

    def upsert_anime(self, lookup, metadata, *, source=None, confidence=None, status=None, fetched_at=None, local_anime_id=None):
        """Upsert editorial metadata with source-aware, field-level merge safety.

        User metadata (favorites, tags, pins, notes) is stored in separate columns.
        Editorial fields marked manual are protected from automatic sources.
        """
        title = metadata.get("title") or lookup
        incoming_kind = str(metadata.get("media_kind") or "series").casefold()
        if incoming_kind not in {"series", "movie", "unknown"}:
            incoming_kind = "series"
        source = str(source or metadata.get("metadata_source") or "local").casefold()
        if source not in {"local", "anilist", "manual", "classifier", "user", "system", "unknown"}:
            source = "unknown"
        confidence = str(confidence or metadata.get("metadata_confidence") or ("high" if source == "manual" else "low")).casefold()
        status = str(status or metadata.get("metadata_status") or ("manual" if source == "manual" else "available" if source == "anilist" else "unresolved")).casefold()
        fetched_at = fetched_at if fetched_at is not None else metadata.get("metadata_fetched_at")
        now = time.time()
        metadata_updated_at = metadata.get("metadata_updated_at", now)
        editorial = ("title", "romaji", "english", "native", "aliases", "description", "cover_url", "cover_cache", "banner_url", "genres", "year", "season", "status", "episodes_count", "duration", "score", "format", "studio")
        description_original = metadata.get("description_original")
        if source == "anilist" and description_original in (None, ""):
            description_original = metadata.get("description")
        values = {
            "anilist_id": metadata.get("anilist_id"),
            "title": title,
            "romaji": metadata.get("romaji"),
            "english": metadata.get("english"),
            "native": metadata.get("native"),
            "aliases": self._normalize_json_list(metadata.get("aliases", "[]")),
            "description": metadata.get("description"),
            "description_original": description_original,
            "cover_url": metadata.get("cover_url", ""),
            "cover_cache": metadata.get("cover_cache", ""),
            "banner_url": metadata.get("banner_url", ""),
            "genres": self._normalize_json_list(metadata.get("genres", "[]")),
            "year": metadata.get("year"),
            "season": metadata.get("season"),
            "status": metadata.get("status"),
            "episodes_count": metadata.get("episodes_count"),
            "duration": metadata.get("duration"),
            "score": metadata.get("score"),
            "format": metadata.get("format"),
            "studio": metadata.get("studio"),
        }
        with self._conn() as c:
            row = None
            try:
                owner_id = int(local_anime_id) if local_anime_id is not None else None
            except (TypeError, ValueError):
                owner_id = None
            if owner_id and owner_id > 0:
                row = c.execute("SELECT * FROM anime WHERE id=?", (owner_id,)).fetchone()
            if row is None:
                row = c.execute("SELECT * FROM anime WHERE lookup_title=?", (lookup,)).fetchone()
            if row:
                if source != "anilist" and "description_original" not in metadata:
                    values["description_original"] = row["description_original"]
                elif source == "anilist" and not values.get("description_original"):
                    values["description_original"] = row["description_original"]
                try:
                    manual_fields = set(json.loads(row["metadata_manual_fields"] or "[]"))
                except (TypeError, json.JSONDecodeError):
                    manual_fields = set()
                if source == "manual":
                    manual_fields.update(k for k in editorial if k in metadata)
                if source == "anilist":
                    # Preserve previously known values when an API response is partial.
                    for key in editorial:
                        incoming = values.get(key)
                        if key not in manual_fields and (key not in metadata or incoming is None or incoming == "" or incoming == "[]"):
                            values[key] = row[key]
                    # Keep a previously cached cover if the network returned none.
                    values["cover_cache"] = values.get("cover_cache") or row["cover_cache"] or ""
                    values["cover_url"] = values.get("cover_url") or row["cover_url"] or ""
                    # External metadata must never replace a manually corrected field.
                    for key in manual_fields:
                        if key in values:
                            values[key] = row[key]
                else:
                    for key in editorial:
                        incoming = values.get(key)
                        if key not in metadata or incoming is None or incoming == "" or incoming == "[]":
                            values[key] = row[key]
                if source == "anilist" and not values.get("anilist_id"):
                    values["anilist_id"] = row["anilist_id"]
                media_kind = incoming_kind if incoming_kind == "movie" or not row["media_kind"] or row["media_kind"] == "unknown" else row["media_kind"]
                if source == "manual":
                    metadata_source = "manual"
                    metadata_status = "manual"
                elif row["metadata_source"] == "manual" and source != "manual":
                    metadata_source = "manual"
                    metadata_status = "manual"
                else:
                    metadata_source = source
                    metadata_status = status
                metadata_fetched = fetched_at if source == "anilist" else row["metadata_fetched_at"]
                metadata_conf = confidence if source in {"anilist", "manual"} else row["metadata_confidence"]
                metadata_updated = now if source in {"anilist", "manual"} else row["metadata_updated_at"]
                c.execute("""UPDATE anime SET anilist_id=?,title=?,romaji=?,english=?,native=?,aliases=?,description=?,description_original=?,cover_url=?,cover_cache=?,banner_url=?,genres=?,year=?,season=?,status=?,episodes_count=?,duration=?,score=?,format=?,studio=?,metadata_updated_at=?,metadata_fetched_at=?,metadata_source=?,metadata_confidence=?,metadata_status=?,metadata_manual_fields=?,media_kind=? WHERE id=?""",
                          (values["anilist_id"], values["title"] or row["title"] or lookup, values["romaji"], values["english"], values["native"], values["aliases"], values["description"], values["description_original"], values["cover_url"], values["cover_cache"], values["banner_url"], values["genres"], values["year"], values["season"], values["status"], values["episodes_count"], values["duration"], values["score"], values["format"], values["studio"], metadata_updated, metadata_fetched, metadata_source, metadata_conf, metadata_status, json.dumps(sorted(manual_fields), ensure_ascii=False), media_kind, row["id"]))
                return row["id"]
            c.execute("""INSERT INTO anime(lookup_title,anilist_id,title,romaji,english,native,aliases,description,description_original,cover_url,cover_cache,banner_url,genres,year,season,status,episodes_count,duration,score,format,studio,metadata_updated_at,metadata_fetched_at,metadata_source,metadata_confidence,metadata_status,metadata_manual_fields,media_kind,added_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                      (lookup, values["anilist_id"], values["title"], values["romaji"], values["english"], values["native"], values["aliases"], values["description"], values["description_original"], values["cover_url"], values["cover_cache"], values["banner_url"], values["genres"], values["year"], values["season"], values["status"], values["episodes_count"], values["duration"], values["score"], values["format"], values["studio"], metadata_updated_at, fetched_at, source, confidence, status, json.dumps(sorted(k for k in editorial if source == "manual" and k in metadata), ensure_ascii=False), incoming_kind, now))
            return c.execute("SELECT id FROM anime WHERE lookup_title=?", (lookup,)).fetchone()[0]

    def set_metadata_status(self, lookup, status, *, confidence=None):
        with self._conn() as c:
            if confidence is None:
                updated = c.execute("UPDATE anime SET metadata_status=? WHERE lookup_title=?", (status, lookup)).rowcount
            else:
                updated = c.execute("UPDATE anime SET metadata_status=?,metadata_confidence=? WHERE lookup_title=?", (status, confidence, lookup)).rowcount
            return bool(updated)

    def set_manual_metadata(self, lookup, values):
        """Persist explicit editorial corrections without touching user state."""
        allowed = {"title", "romaji", "english", "native", "aliases", "description", "genres", "year", "season", "status", "episodes_count", "duration", "score", "format", "studio"}
        values = {key: value for key, value in (values or {}).items() if key in allowed}
        for key in ("aliases", "genres"):
            if key in values:
                values[key] = self._normalize_json_list(values[key])
        if not values:
            raise ValueError("Nenhum campo de metadata manual válido foi informado.")
        with self._conn() as c:
            row = c.execute("SELECT * FROM anime WHERE lookup_title=?", (lookup,)).fetchone()
            if not row:
                raise ValueError("Obra local não encontrada.")
            try:
                manual_fields = set(json.loads(row["metadata_manual_fields"] or "[]"))
            except (TypeError, json.JSONDecodeError):
                manual_fields = set()
            manual_fields.update(values)
            assignments = ",".join(f"{key}=?" for key in values)
            params = list(values.values()) + [json.dumps(sorted(manual_fields), ensure_ascii=False), time.time(), "manual", "high", "manual", row["id"]]
            c.execute(f"UPDATE anime SET {assignments},metadata_manual_fields=?,metadata_updated_at=?,metadata_source=?,metadata_confidence=?,metadata_status=? WHERE id=?", tuple(params))
            return dict(c.execute("SELECT * FROM anime WHERE id=?", (row["id"],)).fetchone())

    def clear_manual_metadata(self, lookup, fields=None):
        with self._conn() as c:
            row = c.execute("SELECT * FROM anime WHERE lookup_title=?", (lookup,)).fetchone()
            if not row:
                raise ValueError("Obra local não encontrada.")
            try:
                manual_fields = set(json.loads(row["metadata_manual_fields"] or "[]"))
            except (TypeError, json.JSONDecodeError):
                manual_fields = set()
            remove = set(fields or manual_fields) & manual_fields
            manual_fields -= remove
            c.execute("UPDATE anime SET metadata_manual_fields=?,metadata_status=?,metadata_source=? WHERE id=?", (json.dumps(sorted(manual_fields), ensure_ascii=False), "available" if row["anilist_id"] else "unresolved", "anilist" if row["anilist_id"] else "local", row["id"]))
            return True

    @staticmethod
    def _identification_confidence_rank(value):
        return {"low": 0, "medium": 1, "high": 2}.get(str(value or "").strip().casefold(), 0)

    @staticmethod
    def _episode_identity_preference_key(row):
        """Rank which duplicate row owns the durable semantic episode identity."""
        return (
            int(bool(row["manual_override"])),
            LibraryStore._identification_confidence_rank(row["identification_confidence"]),
            int(bool(row["identification_source"] and row["identification_source"] != "legacy")),
            float(row["last_played_at"] or 0),
            int(row["watched"] or 0),
            -int(row["id"]),
        )

    def upsert_episode(self, anime_id, path, file_name, season, number, mime_type=None, file_size=None,
                       modified_at=None, source_folder=None, media_identity=None, absolute_number=None,
                       episode_type="regular", episode_title=None, *, identification_source=None,
                       identification_confidence=None, identity_key=None):
        """Upsert by URI, then by proven cross-source identity.

        Season 0 is the explicit unknown bucket when no season evidence exists;
        it is never treated as Season 1.
        """
        season = 0 if season is None else season
        media_identity = media_identity or identity_key
        effective_media_identity = media_identity

        def scoped_conflict_identity(owner_id, identity):
            raw = str(identity or "").strip()
            digest = hashlib.sha1(
                f"{int(owner_id)}\0{raw}".encode("utf-8", "replace")
            ).hexdigest()[:16]
            return f"{raw}#owner-conflict:{int(owner_id)}:{digest}"

        with self._conn() as c:
            def remember_saf_source_observation(episode_id, source_folder, uri):
                source_folder = str(source_folder or "").strip()
                uri = str(uri or "").strip()
                if not episode_id or not source_folder or not uri:
                    return
                if self._infer_source_kind(source_folder) != "saf":
                    return
                now = time.time()
                row = c.execute(
                    """SELECT first_seen FROM episode_observations
                       WHERE episode_id=? AND source_kind='saf' AND scope_kind='source' AND scope_ref=? AND uri=?""",
                    (episode_id, source_folder, uri),
                ).fetchone()
                first_seen = float(row["first_seen"]) if row else now
                c.execute(
                    """INSERT INTO episode_observations(
                         episode_id,source_kind,scope_kind,scope_ref,uri,volume_id,
                         native_generation,fingerprint,first_seen,last_seen,last_checked_at,state,error)
                       VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                       ON CONFLICT(episode_id,source_kind,scope_kind,scope_ref,uri)
                       DO UPDATE SET last_seen=excluded.last_seen,
                         last_checked_at=excluded.last_checked_at,state='available',error=NULL""",
                    (
                        episode_id,
                        "saf",
                        "source",
                        source_folder,
                        uri,
                        None,
                        None,
                        effective_media_identity,
                        first_seen,
                        now,
                        now,
                        "available",
                        None,
                    ),
                )

            by_path = c.execute("SELECT * FROM episodes WHERE path=?", (path,)).fetchone()
            by_identity = None
            if media_identity:
                by_identity = c.execute(
                    "SELECT * FROM episodes WHERE media_identity=? ORDER BY id LIMIT 1",
                    (media_identity,),
                ).fetchone()

            def effective_identification(existing):
                manual = bool(existing and existing["manual_override"])
                incoming_confidence = identification_confidence or (existing["identification_confidence"] if existing else "medium")
                if manual:
                    return (
                        existing["season"],
                        existing["number"],
                        existing["episode_type"],
                        existing["episode_title"],
                        existing["identification_source"],
                        existing["identification_confidence"],
                    )
                if (
                    existing
                    and effective_media_identity
                    and existing["media_identity"]
                    and str(existing["media_identity"]) == str(effective_media_identity)
                    and self._identification_confidence_rank(incoming_confidence)
                    < self._identification_confidence_rank(existing["identification_confidence"])
                ):
                    # A restart/rescan may have weaker filename/path evidence than
                    # the canonical episode already stored. Never let that weaker
                    # evidence move or reclassify the same stable media item.
                    return (
                        existing["season"],
                        existing["number"],
                        existing["episode_type"],
                        existing["episode_title"],
                        existing["identification_source"],
                        existing["identification_confidence"],
                    )
                return (
                    season,
                    number,
                    episode_type,
                    episode_title,
                    identification_source or (existing["identification_source"] if existing else "legacy"),
                    incoming_confidence,
                )

            def update_existing(row_id, new_path=None):
                existing = c.execute("SELECT * FROM episodes WHERE id=?", (row_id,)).fetchone()
                effective_season, effective_number, effective_type, effective_title, effective_source, effective_confidence = effective_identification(existing)
                # Provider metadata can legitimately be absent (for example a SAF
                # provider may omit size/mtime). Never replace durable known facts
                # with null/empty values during a degraded rescan.
                effective_mime = str(mime_type).strip() if mime_type is not None and str(mime_type).strip() else (existing["mime_type"] if existing else None)
                if effective_mime == "application/octet-stream" and existing and existing["mime_type"]:
                    effective_mime = existing["mime_type"]
                effective_size = file_size if file_size is not None else (existing["file_size"] if existing else None)
                effective_modified = modified_at if modified_at is not None else (existing["modified_at"] if existing else None)
                preserve_existing_identity = bool(
                    existing
                    and (
                        existing["manual_override"]
                        or (
                            media_identity
                            and existing["media_identity"]
                            and str(existing["media_identity"]) == str(media_identity)
                            and self._identification_confidence_rank(
                                identification_confidence or existing["identification_confidence"]
                            ) < self._identification_confidence_rank(existing["identification_confidence"])
                        )
                    )
                )
                effective_absolute = (
                    existing["absolute_number"]
                    if preserve_existing_identity
                    else absolute_number if absolute_number is not None else (existing["absolute_number"] if existing else None)
                )
                # Local episode ownership is canonical once persisted. A rescan,
                # title change, metadata refresh, or weaker parser evidence may
                # never transfer an existing episode to another anime.
                effective_anime_id = existing["anime_id"]
                existing_source_folder = str(existing["source_folder"] or "").strip() if existing else ""
                incoming_source_folder = str(source_folder or "").strip()
                if (
                    existing
                    and existing_source_folder
                    and incoming_source_folder
                    and existing_source_folder != incoming_source_folder
                    and self._infer_source_kind(existing_source_folder) == "saf"
                    and self._infer_source_kind(incoming_source_folder) == "saf"
                ):
                    remember_saf_source_observation(row_id, existing_source_folder, existing["path"])
                    remember_saf_source_observation(row_id, incoming_source_folder, path)
                if new_path is None:
                    c.execute(
                        """UPDATE episodes SET anime_id=?,file_name=?,season=?,number=?,mime_type=?,
                           file_size=?,modified_at=?,source_folder=?,media_identity=?,absolute_number=?,
                           episode_type=?,episode_title=?,identification_source=?,identification_confidence=?,missing=0,availability_state='available' WHERE id=?""",
                        (effective_anime_id,file_name,effective_season,effective_number,effective_mime,effective_size,effective_modified,source_folder,
                         effective_media_identity,effective_absolute,effective_type,effective_title,effective_source,effective_confidence,row_id),
                    )
                else:
                    c.execute(
                        """UPDATE episodes SET anime_id=?,path=?,file_name=?,season=?,number=?,mime_type=?,
                           file_size=?,modified_at=?,source_folder=?,media_identity=?,absolute_number=?,
                           episode_type=?,episode_title=?,identification_source=?,identification_confidence=?,missing=0,availability_state='available' WHERE id=?""",
                        (effective_anime_id,new_path,file_name,effective_season,effective_number,effective_mime,effective_size,effective_modified,source_folder,
                         effective_media_identity,effective_absolute,effective_type,effective_title,effective_source,effective_confidence,row_id),
                    )
                return row_id

            if by_path and by_identity and by_path["id"] != by_identity["id"]:
                # A physical file may have two legacy rows after an older identity
                # migration. Metadata/scanner updates are never allowed to transfer
                # ownership between local anime entities.
                if int(by_path["anime_id"]) != int(by_identity["anime_id"]):
                    effective_media_identity = scoped_conflict_identity(anime_id, media_identity)
                    logger.warning(
                        "[EPISODE_OWNER_INVARIANT] conflicting legacy owners path_id=%s path_anime=%s identity_id=%s identity_anime=%s scoped_identity=%s",
                        by_path["id"], by_path["anime_id"], by_identity["id"], by_identity["anime_id"],
                        effective_media_identity,
                    )
                    return update_existing(by_path["id"])

                progress = max(float(by_path["progress"] or 0), float(by_identity["progress"] or 0))
                watched = max(int(by_path["watched"] or 0), int(by_identity["watched"] or 0))
                last_played = max(float(by_path["last_played_at"] or 0), float(by_identity["last_played_at"] or 0)) or None
                preferred_existing = max(
                    (by_path, by_identity),
                    key=self._episode_identity_preference_key,
                )
                incoming_confidence = identification_confidence or "medium"
                (
                    duplicate_season,
                    duplicate_number,
                    duplicate_type,
                    duplicate_title,
                    duplicate_source,
                    duplicate_confidence,
                ) = effective_identification(preferred_existing)
                duplicate_absolute = (
                    preferred_existing["absolute_number"]
                    if self._identification_confidence_rank(incoming_confidence)
                    < self._identification_confidence_rank(preferred_existing["identification_confidence"])
                    else absolute_number
                    if absolute_number is not None
                    else preferred_existing["absolute_number"]
                )
                survivor_id = int(preferred_existing["id"])
                duplicate_id = int(by_identity["id"] if survivor_id == int(by_path["id"]) else by_path["id"])
                effective_mime = (
                    str(mime_type).strip()
                    if mime_type is not None and str(mime_type).strip()
                    else preferred_existing["mime_type"]
                )
                effective_size = file_size if file_size is not None else preferred_existing["file_size"]
                effective_modified = modified_at if modified_at is not None else preferred_existing["modified_at"]
                c.execute(
                    """UPDATE episodes SET anime_id=?,path=?,file_name=?,season=?,number=?,mime_type=?,
                       file_size=?,modified_at=?,source_folder=?,media_identity=?,absolute_number=?,
                       episode_type=?,episode_title=?,identification_source=?,identification_confidence=?,
                       missing=0,progress=?,watched=?,last_played_at=? WHERE id=?""",
                    (
                        preferred_existing["anime_id"], path, file_name, duplicate_season, duplicate_number,
                        effective_mime, effective_size, effective_modified, source_folder, media_identity,
                        duplicate_absolute, duplicate_type, duplicate_title, duplicate_source,
                        duplicate_confidence, progress, watched, last_played, survivor_id,
                    ),
                )
                c.execute(
                    """INSERT OR IGNORE INTO episode_observations(
                         episode_id,source_kind,scope_kind,scope_ref,uri,volume_id,native_generation,
                         fingerprint,first_seen,last_seen,last_checked_at,state,error)
                       SELECT ?,source_kind,scope_kind,scope_ref,uri,volume_id,native_generation,
                         fingerprint,first_seen,last_seen,last_checked_at,state,error
                       FROM episode_observations WHERE episode_id=?""",
                    (survivor_id, duplicate_id),
                )
                c.execute("DELETE FROM episodes WHERE id=?", (duplicate_id,))
                self._recompute_episode_availability_locked(c, survivor_id)
                return survivor_id

            if by_identity and int(by_identity["anime_id"]) != int(anime_id):
                effective_media_identity = scoped_conflict_identity(anime_id, media_identity)
                logger.warning(
                    "[EPISODE_OWNER_INVARIANT] cross-anime media identity conflict identity=%s existing_episode=%s existing_anime=%s incoming_anime=%s scoped_identity=%s",
                    media_identity, by_identity["id"], by_identity["anime_id"], anime_id,
                    effective_media_identity,
                )
                by_identity = None

            if by_path:
                return update_existing(by_path["id"])
            if by_identity:
                return update_existing(by_identity["id"], new_path=path)

            cur = c.execute(
                """INSERT INTO episodes(anime_id,path,file_name,season,number,mime_type,file_size,modified_at,
                                         source_folder,missing,media_identity,availability_state,absolute_number,episode_type,episode_title,
                                         identification_source,identification_confidence)
                   VALUES(?,?,?,?,?,?,?,?,?,0,?,'available',?,?,?,?,?)""",
                (anime_id,path,file_name,season,number,mime_type,file_size,modified_at,source_folder,
                 effective_media_identity,absolute_number,episode_type,episode_title,
                 identification_source or "legacy", identification_confidence or "medium"),
            )
            return cur.lastrowid

    def _merge_duplicate_media_identities_locked(self, c):
        duplicate_keys = [row[0] for row in c.execute(
            "SELECT media_identity FROM episodes WHERE media_identity IS NOT NULL GROUP BY media_identity HAVING COUNT(*) > 1"
        )]
        merged = 0
        for identity in duplicate_keys:
            rows = c.execute(
                """SELECT * FROM episodes WHERE media_identity=?
                   ORDER BY id ASC""",
                (identity,),
            ).fetchall()
            if len(rows) < 2:
                continue
            owner_groups = {}
            for row in rows:
                owner_groups.setdefault(int(row["anime_id"]), []).append(row)
            if len(owner_groups) > 1:
                logger.warning(
                    "[EPISODE_OWNER_INVARIANT] skip cross-anime duplicate merge identity=%s owners=%s",
                    identity, sorted(owner_groups),
                )
                continue
            rows = next(iter(owner_groups.values()))
            if len(rows) < 2:
                continue
            survivor = max(rows, key=self._episode_identity_preference_key)
            best_progress = max(float(row["progress"] or 0) for row in rows)
            best_watched = max(int(row["watched"] or 0) for row in rows)
            best_played = max((float(row["last_played_at"] or 0) for row in rows), default=0)
            c.execute(
                """UPDATE episodes SET progress=?,watched=?,last_played_at=?,
                   missing=?,availability_state=?
                   WHERE id=?""",
                (
                    best_progress,
                    best_watched,
                    best_played or None,
                    min(int(row["missing"] or 1) for row in rows),
                    survivor["availability_state"] or "available",
                    survivor["id"],
                ),
            )
            survivor_id = survivor["id"]
            for row in rows:
                if row["id"] == survivor_id:
                    continue
                c.execute(
                    """INSERT OR IGNORE INTO episode_observations(
                         episode_id,source_kind,scope_kind,scope_ref,uri,volume_id,native_generation,
                         fingerprint,first_seen,last_seen,last_checked_at,state,error)
                       SELECT ?,source_kind,scope_kind,scope_ref,uri,volume_id,native_generation,
                         fingerprint,first_seen,last_seen,last_checked_at,state,error
                       FROM episode_observations WHERE episode_id=?""",
                    (survivor_id, row["id"]),
                )
                progress_value = max(best_progress, float(row["progress"] or 0))
                watched_value = max(best_watched, int(row["watched"] or 0))
                c.execute(
                    "UPDATE episodes SET progress=?,watched=?,last_played_at=? WHERE id=?",
                    (
                        progress_value,
                        watched_value,
                        max(best_played, float(row["last_played_at"] or 0)) or None,
                        survivor_id,
                    ),
                )
                c.execute("DELETE FROM episodes WHERE id=?", (row["id"],))
                merged += 1
            self._recompute_episode_availability_locked(c, survivor_id)
        return merged

    def merge_duplicate_media_identities(self):
        """Merge legacy duplicate rows that now resolve to one media identity."""
        with self._conn() as c:
            return self._merge_duplicate_media_identities_locked(c)

    def physical_row(self, path):
        with self._conn() as c:
            row = c.execute("SELECT * FROM episodes WHERE path=?", (path,)).fetchone()
            return dict(row) if row else None

    def apply_library_reconciliation(self, remove_episode_ids, duplicate_merges=None):
        """Apply library-only cleanup atomically; never touch physical media files."""
        remove_ids = []
        seen_ids = set()
        for value in remove_episode_ids or ():
            try:
                episode_id = int(value)
            except (TypeError, ValueError):
                continue
            if episode_id > 0 and episode_id not in seen_ids:
                seen_ids.add(episode_id)
                remove_ids.append(episode_id)

        removed = 0
        merged = 0
        with self._conn() as c:
            for merge in duplicate_merges or ():
                try:
                    source_id = int(merge.get("source_id"))
                    target_id = int(merge.get("target_id"))
                except (TypeError, ValueError, AttributeError):
                    continue
                if source_id <= 0 or target_id <= 0 or source_id == target_id:
                    continue
                source = c.execute("SELECT * FROM episodes WHERE id=?", (source_id,)).fetchone()
                target = c.execute("SELECT * FROM episodes WHERE id=?", (target_id,)).fetchone()
                if not source or not target:
                    continue
                if int(source["anime_id"]) != int(target["anime_id"]):
                    logger.warning(
                        "[EPISODE_OWNER_INVARIANT] skip reconciliation merge across anime source_id=%s source_anime=%s target_id=%s target_anime=%s",
                        source_id, source["anime_id"], target_id, target["anime_id"],
                    )
                    continue

                progress = max(float(source["progress"] or 0), float(target["progress"] or 0))
                watched = max(int(source["watched"] or 0), int(target["watched"] or 0))
                last_played = max(
                    float(source["last_played_at"] or 0),
                    float(target["last_played_at"] or 0),
                ) or None

                source_is_stronger = (
                    self._episode_identity_preference_key(source)
                    > self._episode_identity_preference_key(target)
                )
                if source_is_stronger:
                    # The target is the currently valid physical observation. Keep
                    # its path/native fields, but restore the stronger durable
                    # semantic identity before deleting the weaker source row.
                    c.execute(
                        """UPDATE episodes
                           SET anime_id=?,season=?,number=?,absolute_number=?,
                               episode_type=?,episode_title=?,identification_source=?,
                               identification_confidence=?,manual_override=?,
                               progress=?,watched=?,last_played_at=?
                           WHERE id=?""",
                        (
                            source["anime_id"], source["season"], source["number"],
                            source["absolute_number"], source["episode_type"],
                            source["episode_title"], source["identification_source"],
                            source["identification_confidence"], source["manual_override"],
                            progress, watched, last_played, target_id,
                        ),
                    )
                else:
                    c.execute(
                        "UPDATE episodes SET progress=?,watched=?,last_played_at=? WHERE id=?",
                        (progress, watched, last_played, target_id),
                    )

                if source_id not in seen_ids:
                    seen_ids.add(source_id)
                    remove_ids.append(source_id)
                merged += 1

            for episode_id in remove_ids:
                removed += c.execute("DELETE FROM episodes WHERE id=?", (episode_id,)).rowcount

            merged += self._merge_duplicate_media_identities_locked(c)

        return {"removed": removed, "duplicates_merged": merged}
    def missing_candidate(self, anime_id, source_folder, file_size, modified_at, volume_id=None, *, excluded_paths=None):
        """Find one unambiguous row that can survive a move/rename.

        Size + mtime are only a reconciliation hint; uniqueness is required.
        ``excluded_paths`` contains paths confirmed present in the current
        complete scan, so an old path can be recognized as moved before the
        missing projection is applied.
        """
        if file_size is None or modified_at is None:
            return None
        excluded = {str(path) for path in (excluded_paths or []) if path}
        with self._conn() as c:
            rows = c.execute(
                """SELECT * FROM episodes
                   WHERE anime_id=? AND source_folder=?
                     AND file_size=? AND modified_at=?
                     AND (? IS NULL OR volume_id=?)
                   ORDER BY id""",
                (anime_id, source_folder, file_size, modified_at, volume_id, volume_id),
            ).fetchall()
            rows = [row for row in rows if row["path"] not in excluded]
            if len(rows) != 1:
                return None
            return dict(rows[0])


    def _infer_source_kind(self, source_folder, source_kind=None):
        if source_kind:
            return str(source_kind).strip().casefold()
        with self._conn() as c:
            row = c.execute("SELECT kind FROM folders WHERE path=? LIMIT 1", (str(source_folder),)).fetchone()
        return str(row["kind"]).strip().casefold() if row and row["kind"] else "unknown"

    @staticmethod
    def _scope_ref(scope_ref):
        return str(scope_ref or "").strip()

    @staticmethod
    def _recompute_episode_availability_locked(c, episode_id):
        rows = c.execute(
            """SELECT source_kind,scope_kind,scope_ref,volume_id,state,last_checked_at,id
               FROM episode_observations
               WHERE episode_id=?
               ORDER BY last_checked_at DESC,id DESC""",
            (episode_id,),
        ).fetchall()
        if not rows:
            return

        # Reduce observations per physical source location before aggregating.
        # Volume observations are authoritative for that source+volume. When an
        # observation has no volume, keep its configured scope independent so a
        # second source can keep the logical episode available after another
        # source is removed.
        latest_by_location = {}
        for row in rows:
            source_kind = str(row["source_kind"] or "unknown").casefold()
            volume_id = str(row["volume_id"] or "").strip()
            if volume_id:
                location_key = (source_kind, "volume", volume_id)
            else:
                location_key = (
                    source_kind,
                    str(row["scope_kind"] or "source").casefold(),
                    str(row["scope_ref"] or "").strip(),
                )
            if location_key not in latest_by_location:
                latest_by_location[location_key] = str(row["state"] or "").casefold()

        states = set(latest_by_location.values())
        if "available" in states:
            missing, availability = 0, "available"
        elif "volume_unavailable" in states:
            missing, availability = 1, "volume_unavailable"
        elif "scope_unavailable" in states:
            missing, availability = 1, "scope_unavailable"
        elif "unavailable" in states:
            missing, availability = 1, "unavailable"
        else:
            missing, availability = 1, "missing"

        c.execute(
            "UPDATE episodes SET missing=?,availability_state=? WHERE id=? AND availability_state != 'scope_removed'",
            (missing, availability, episode_id),
        )

    def record_observation(
        self, episode_id, *, source_kind, scope_kind, scope_ref=None, uri,
        volume_id=None, native_generation=None, fingerprint=None, state="available", error=None
    ):
        source_kind = str(source_kind or "unknown").strip().casefold()
        scope_kind = str(scope_kind or "source").strip().casefold()
        scope_ref = self._scope_ref(scope_ref)
        uri = str(uri or "").strip()
        if not episode_id or not uri:
            return False
        state = str(state or "available").strip().casefold()
        now = time.time()
        with self._conn() as c:
            row = c.execute(
                """SELECT first_seen FROM episode_observations
                   WHERE episode_id=? AND source_kind=? AND scope_kind=? AND scope_ref=? AND uri=?""",
                (episode_id, source_kind, scope_kind, scope_ref, uri),
            ).fetchone()
            first_seen = float(row["first_seen"]) if row else now
            c.execute(
                """INSERT INTO episode_observations(
                     episode_id,source_kind,scope_kind,scope_ref,uri,volume_id,
                     native_generation,fingerprint,first_seen,last_seen,last_checked_at,state,error)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                   ON CONFLICT(episode_id,source_kind,scope_kind,scope_ref,uri)
                   DO UPDATE SET volume_id=excluded.volume_id,
                     native_generation=excluded.native_generation,
                     fingerprint=excluded.fingerprint,
                     last_seen=excluded.last_seen,
                     last_checked_at=excluded.last_checked_at,
                     state=excluded.state,error=excluded.error""",
                (episode_id, source_kind, scope_kind, scope_ref, uri, str(volume_id or "") or None,
                 native_generation, fingerprint, first_seen, now, now, state, error),
            )
            self._recompute_episode_availability_locked(c, episode_id)
        return True

    def _seed_legacy_scope_observations_locked(self, c, source_folder, source_kind, scope_kind, scope_ref):
        where = "source_folder=?"
        params = [str(source_folder)]
        if scope_kind == "volume" and scope_ref:
            where += " AND volume_id=?"
            params.append(scope_ref)
        elif scope_kind in {"root", "directory"} and scope_ref:
            prefix = str(scope_ref).strip("/").replace("\\", "/")
            where += " AND (relative_path=? OR relative_path LIKE ?)"
            params.extend([prefix, prefix + "/%"])
        rows = c.execute("SELECT id,path,volume_id,missing,media_identity FROM episodes WHERE " + where, tuple(params)).fetchall()
        now = time.time()
        for row in rows:
            exists = c.execute(
                """SELECT 1 FROM episode_observations
                   WHERE episode_id=? AND source_kind=? AND scope_kind=? AND scope_ref=? LIMIT 1""",
                (row["id"], source_kind, scope_kind, scope_ref),
            ).fetchone()
            if exists:
                continue
            c.execute(
                """INSERT OR IGNORE INTO episode_observations(
                     episode_id,source_kind,scope_kind,scope_ref,uri,volume_id,
                     native_generation,fingerprint,first_seen,last_seen,last_checked_at,state)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                (row["id"], source_kind, scope_kind, scope_ref, row["path"], row["volume_id"],
                 None, row["media_identity"], now, now, now,
                 "missing" if row["missing"] else "available"),
            )

    def reconcile_scope_generation(self, source_folder, *, source_kind, scope_kind, scope_ref=None, native_generation=None, complete=False):
        """Reconcile only after a trusted complete generation, without a document-sized seen set."""
        if not complete or native_generation is None:
            return 0
        source_kind = self._infer_source_kind(source_folder, source_kind)
        scope_kind = str(scope_kind or "source").strip().casefold()
        scope_ref = self._scope_ref(scope_ref)
        generation = int(native_generation)
        now = time.time()
        with self._conn() as c:
            self._seed_legacy_scope_observations_locked(c, source_folder, source_kind, scope_kind, scope_ref)
            rows = c.execute(
                """SELECT id,episode_id,native_generation FROM episode_observations
                   WHERE source_kind=? AND scope_kind=? AND scope_ref=?""",
                (source_kind, scope_kind, scope_ref),
            ).fetchall()
            affected = set()
            for row in rows:
                state = "available" if row["native_generation"] == generation else "missing"
                c.execute(
                    "UPDATE episode_observations SET state=?,last_checked_at=? WHERE id=?",
                    (state, now, row["id"]),
                )
                affected.add(int(row["episode_id"]))
            for episode_id in affected:
                self._recompute_episode_availability_locked(c, episode_id)
            return len(affected)

    def generation_anime_ids(self, *, source_kind, scope_kind, scope_ref=None, native_generation=None):
        if native_generation is None:
            return set()
        with self._conn() as c:
            rows = c.execute(
                """SELECT DISTINCT e.anime_id
                   FROM episodes e
                   JOIN episode_observations o ON o.episode_id=e.id
                   WHERE o.source_kind=? AND o.scope_kind=? AND o.scope_ref=? AND o.native_generation=?""",
                (str(source_kind or "unknown").casefold(), str(scope_kind or "source").casefold(),
                 self._scope_ref(scope_ref), int(native_generation)),
            ).fetchall()
            return {int(row["anime_id"]) for row in rows}

    def reconcile_scope(self, source_folder, seen, *, source_kind=None, scope_kind="source", scope_ref=None, complete=False):
        if not complete:
            return 0
        source_kind = self._infer_source_kind(source_folder, source_kind)
        scope_kind = str(scope_kind or "source").strip().casefold()
        scope_ref = self._scope_ref(scope_ref)
        seen = {str(path).strip() for path in (seen or []) if str(path).strip()}
        now = time.time()
        with self._conn() as c:
            self._seed_legacy_scope_observations_locked(c, source_folder, source_kind, scope_kind, scope_ref)
            rows = c.execute(
                """SELECT id,episode_id,uri FROM episode_observations
                   WHERE source_kind=? AND scope_kind=? AND scope_ref=?""",
                (source_kind, scope_kind, scope_ref),
            ).fetchall()

            # Native volume scans can carry complete/partial information per
            # volume while the legacy row may only have a global observation.
            # Materialize the trusted scope from the canonical episode fields
            # before reconciling so a complete volume can mark only that volume
            # missing without touching other volumes/sources.
            if scope_kind == "volume" and scope_ref:
                candidates = c.execute(
                    """SELECT e.id,e.path,e.volume_id,e.media_identity
                       FROM episodes e
                       LEFT JOIN folders f ON f.path=e.source_folder
                       WHERE e.volume_id=?
                         AND (e.source_folder=? OR f.kind=?)""",
                    (scope_ref, source_folder, source_kind),
                ).fetchall()
                now = time.time()
                existing_keys = {
                    (int(row["episode_id"]), str(row["uri"] or ""))
                    for row in rows
                }
                for candidate in candidates:
                    key = (int(candidate["id"]), str(candidate["path"] or ""))
                    if key in existing_keys:
                        continue
                    c.execute(
                        """INSERT OR IGNORE INTO episode_observations(
                             episode_id,source_kind,scope_kind,scope_ref,uri,volume_id,
                             native_generation,fingerprint,first_seen,last_seen,last_checked_at,state,error)
                           VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                        (
                            int(candidate["id"]),
                            source_kind,
                            scope_kind,
                            scope_ref,
                            candidate["path"],
                            scope_ref,
                            None,
                            candidate["media_identity"],
                            now,
                            now,
                            now,
                            "available" if not int(c.execute(
                                "SELECT missing FROM episodes WHERE id=?",
                                (candidate["id"],),
                            ).fetchone()["missing"] or 0) else "missing",
                            None,
                        ),
                    )
                rows = c.execute(
                    """SELECT id,episode_id,uri FROM episode_observations
                       WHERE source_kind=? AND scope_kind=? AND scope_ref=?""",
                    (source_kind, scope_kind, scope_ref),
                ).fetchall()
            affected = set()
            for row in rows:
                state = "available" if row["uri"] in seen else "missing"
                c.execute(
                    "UPDATE episode_observations SET state=?,last_checked_at=? WHERE id=?",
                    (state, now, row["id"]),
                )
                affected.add(int(row["episode_id"]))
            for episode_id in affected:
                self._recompute_episode_availability_locked(c, episode_id)
        return len(affected)

    def reconcile_missing(self, source_folder, seen, *, scope_kind="source", scope_ref=None, source_kind=None, complete=False):
        """Compatibility facade for the single scoped reconciliation engine."""
        return self.reconcile_scope(
            source_folder,
            seen,
            source_kind=source_kind,
            scope_kind=scope_kind,
            scope_ref=scope_ref,
            complete=complete,
        )

    def mark_volume_unavailable(self, volume_id, reason=None):
        volume_id = str(volume_id or "").strip()
        if not volume_id:
            return 0
        reason = str(reason or "volume_unavailable")
        with self._conn() as c:
            obs = c.execute(
                "SELECT DISTINCT episode_id FROM episode_observations WHERE volume_id=?",
                (volume_id,),
            ).fetchall()
            changed = 0
            for row in obs:
                changed += c.execute(
                    "UPDATE episode_observations SET state='volume_unavailable',error=?,last_checked_at=? WHERE volume_id=? AND episode_id=? AND state != 'missing'",
                    (reason, time.time(), volume_id, row["episode_id"]),
                ).rowcount
                self._recompute_episode_availability_locked(c, row["episode_id"])
            # Legacy rows without observations still need a durable unavailable
            # state until a complete source observation is recorded again.
            changed += c.execute(
                """UPDATE episodes
                   SET missing=1,availability_state='volume_unavailable'
                   WHERE volume_id=? AND availability_state != 'scope_removed'
                     AND id NOT IN (SELECT episode_id FROM episode_observations WHERE volume_id=?)""",
                (volume_id, volume_id),
            ).rowcount
            return changed

    def restore_volume(self, volume_id):
        volume_id = str(volume_id or "").strip()
        if not volume_id:
            return 0
        with self._conn() as c:
            return c.execute(
                """UPDATE episodes
                   SET missing=0,availability_state='available'
                   WHERE volume_id=? AND availability_state='volume_unavailable'""",
                (volume_id,),
            ).rowcount

    def mark_source_unavailable(self, source_folder, reason=None):
        source_folder = str(source_folder or "").strip()
        if not source_folder:
            return 0
        source_kind = self._infer_source_kind(source_folder)
        reason = str(reason or "scope_unavailable")
        with self._conn() as c:
            if source_kind == "saf":
                scope_filter = "source_kind='saf' AND scope_ref=?"
                params = (source_folder,)
            elif source_kind == "broad_storage":
                scope_filter = "source_kind='broad_storage'"
                params = ()
            else:
                scope_filter = "source_kind=? AND scope_ref=?"
                params = (source_kind, source_folder)
            obs = c.execute(
                "SELECT DISTINCT episode_id FROM episode_observations WHERE " + scope_filter,
                params,
            ).fetchall()
            changed = 0
            for row in obs:
                changed += c.execute(
                    "UPDATE episode_observations SET state='scope_unavailable',error=?,last_checked_at=? WHERE episode_id=? AND " + scope_filter,
                    (reason, time.time(), row["episode_id"], *params),
                ).rowcount
                self._recompute_episode_availability_locked(c, row["episode_id"])
            changed += c.execute(
                """UPDATE episodes
                   SET missing=1,availability_state='scope_unavailable'
                   WHERE source_folder=? AND availability_state != 'scope_removed'
                     AND id NOT IN (SELECT episode_id FROM episode_observations)""",
                (source_folder,),
            ).rowcount
            return changed

    def restore_source(self, source_folder):
        source_folder = str(source_folder or "").strip()
        if not source_folder:
            return 0
        source_kind = self._infer_source_kind(source_folder)
        with self._conn() as c:
            if source_kind == "saf":
                scope_filter = "source_kind='saf' AND scope_ref=?"
                params = (source_folder,)
            elif source_kind == "broad_storage":
                scope_filter = "source_kind='broad_storage'"
                params = ()
            else:
                scope_filter = "source_kind=? AND scope_ref=?"
                params = (source_kind, source_folder)
            rows = c.execute(
                "SELECT DISTINCT episode_id FROM episode_observations WHERE " + scope_filter + " AND state='scope_unavailable'",
                params,
            ).fetchall()
            changed = 0
            for row in rows:
                changed += c.execute(
                    "UPDATE episode_observations SET state='available',error=NULL,last_checked_at=? WHERE episode_id=? AND state='scope_unavailable' AND " + scope_filter,
                    (time.time(), row["episode_id"], *params),
                ).rowcount
                self._recompute_episode_availability_locked(c, row["episode_id"])
            changed += c.execute(
                """UPDATE episodes SET missing=0,availability_state='available'
                   WHERE source_folder=? AND availability_state='scope_unavailable'
                     AND id NOT IN (SELECT episode_id FROM episode_observations)""",
                (source_folder,),
            ).rowcount
            return changed

    def mark_missing(self, source_folder, seen):
        """Compatibility facade for an explicit complete source observation."""
        self.reconcile_missing(
            source_folder,
            seen,
            source_kind=self._infer_source_kind(source_folder),
            scope_kind="source",
            scope_ref=source_folder,
            complete=True,
        )

    def catalog(self, favorites_only=False, anime_ids=None):
        """Project the local library into the visual hierarchy used by Home/Details."""
        normalized_ids = []
        for value in anime_ids or []:
            try:
                normalized_ids.append(int(value))
            except (TypeError, ValueError):
                continue
        with self._conn() as c:
            where = []
            params = []
            if favorites_only:
                where.append("favorite=1")
            if normalized_ids:
                placeholders = ",".join("?" for _ in normalized_ids)
                where.append(f"id IN ({placeholders})")
                params.extend(normalized_ids)
            elif anime_ids is not None:
                return []
            query = "SELECT * FROM anime"
            if where:
                query += " WHERE " + " AND ".join(where)
            query += " ORDER BY added_at DESC, title COLLATE NOCASE"
            anime_rows = c.execute(query, tuple(params)).fetchall()
            if normalized_ids:
                placeholders = ",".join("?" for _ in normalized_ids)
                episode_rows = c.execute(
                    f"SELECT * FROM episodes WHERE anime_id IN ({placeholders}) AND availability_state != 'scope_removed' ORDER BY anime_id, season, number, absolute_number, file_name",
                    tuple(normalized_ids),
                ).fetchall()
            else:
                episode_rows = c.execute(
                    "SELECT * FROM episodes WHERE availability_state != 'scope_removed' ORDER BY anime_id, season, number, absolute_number, file_name"
                ).fetchall()
            folder_kinds = {
                row["path"]: row["kind"]
                for row in c.execute("SELECT path, kind FROM folders")
                if row["path"]
            }
            if normalized_ids:
                placeholders = ",".join("?" for _ in normalized_ids)
                genre_rows = c.execute(
                    f"SELECT ag.anime_id,g.id,g.canonical_name FROM anime_genres ag JOIN genres g ON g.id=ag.genre_id WHERE ag.anime_id IN ({placeholders}) ORDER BY g.normalized_name",
                    tuple(normalized_ids),
                ).fetchall()
            else:
                genre_rows = c.execute(
                    "SELECT ag.anime_id,g.id,g.canonical_name FROM anime_genres ag JOIN genres g ON g.id=ag.genre_id ORDER BY g.normalized_name"
                ).fetchall()
            genres_by_anime = {}
            for genre_row in genre_rows:
                genres_by_anime.setdefault(int(genre_row["anime_id"]), []).append(
                    (str(genre_row["id"]), str(genre_row["canonical_name"]))
                )
            if normalized_ids:
                placeholders = ",".join("?" for _ in normalized_ids)
                artwork_rows = c.execute(
                    f"SELECT entity_type, entity_id, local_path FROM artwork WHERE status != 'failed' AND entity_id IN ({placeholders})",
                    tuple(str(value) for value in normalized_ids),
                ).fetchall()
            else:
                artwork_rows = c.execute(
                    "SELECT entity_type, entity_id, local_path FROM artwork WHERE status != 'failed'"
                ).fetchall()
            local_artwork_anime = {
                str(row["entity_id"])
                for row in artwork_rows
                if row["local_path"] and str(row["entity_type"]) in {"anime", "movie"}
            }
            history_sql = "SELECT anime_id, MAX(last_played_at) AS last_played_at FROM episodes WHERE last_played_at IS NOT NULL"
            history_params = []
            if normalized_ids:
                placeholders = ",".join("?" for _ in normalized_ids)
                history_sql += f" AND anime_id IN ({placeholders})"
                history_params.extend(normalized_ids)
            history_sql += " GROUP BY anime_id"
            history_rows = c.execute(history_sql, tuple(history_params)).fetchall()
            history = {int(row["anime_id"]): row["last_played_at"] for row in history_rows}

            def project(e):
                return {
                    "id": e["id"], "title": e["file_name"], "file_name": e["file_name"],
                    "episode_title": e["episode_title"], "path": e["path"], "season": e["season"],
                    "number": e["number"], "absolute_number": e["absolute_number"],
                    "episode_type": e["episode_type"], "identification_source": e["identification_source"],
                    "identification_confidence": e["identification_confidence"], "manual_override": bool(e["manual_override"]),
                    "progress": e["progress"], "duration": e["duration"], "watched": bool(e["watched"]),
                    "consumption_state": consumption_state(dict(e)).value,
                    "missing": bool(e["missing"]), "availability_state": e["availability_state"] or ("missing" if e["missing"] else "available"), "last_played_at": e["last_played_at"],
                    "mime_type": e["mime_type"], "file_size": e["file_size"], "modified_at": e["modified_at"],
                    "source_folder": e["source_folder"], "source_kind": folder_kinds.get(e["source_folder"]),
                    "relative_path": e["relative_path"], "media_identity": e["media_identity"],
                    "volume_id": e["volume_id"], "volume_uuid": e["volume_uuid"],
                }

            by_anime = {}
            for row in episode_rows:
                by_anime.setdefault(row["anime_id"], []).append(project(row))
            special_types = {"special", "ova", "oad", "ona", "extra"}
            result = []
            for a in anime_rows:
                eps = by_anime.get(a["id"], [])
                if not eps and not normalized_ids:
                    continue
                projected = eps
                special_eps = [e for e in projected if e["episode_type"] in special_types]
                movie_eps = [e for e in projected if e["episode_type"] == "movie"]
                regulars = [e for e in projected if e["episode_type"] not in special_types and e["episode_type"] != "movie"]
                seasons = {}
                for ep in regulars:
                    seasons.setdefault(ep["season"], []).append(ep)
                registered_genres = genres_by_anime.get(int(a["id"]), [])
                if registered_genres:
                    genre_ids = [item[0] for item in registered_genres]
                    genres = [item[1] for item in registered_genres]
                else:
                    try:
                        genres = json.loads(a["genres"] or "[]")
                    except (TypeError, json.JSONDecodeError):
                        genres = []
                    genre_ids = []
                ordered_seasons = []
                for season, values in sorted(seasons.items(), key=lambda item: item[0] if item[0] is not None else -1):
                    values = sorted(values, key=lambda e: (e["number"] if e["number"] is not None else -1, e["file_name"].casefold(), e["path"].casefold()))
                    season_available = [e for e in values if not e["missing"]]
                    season_completed = [e for e in season_available if is_completed(e)]
                    season_active = [e for e in season_available if is_in_progress(e)]
                    ordered_seasons.append({
                        "season_name": f"Temporada {season}" if season is not None else "Temporada especial",
                        "season": season, "folder_path": "", "episodes": values,
                        "available_count": len(season_available),
                        "watched_count": len(season_completed),
                        "active_count": len(season_active),
                        "remaining_count": max(0, len(season_available) - len(season_completed)),
                        "progress_ratio": (len(season_completed) / len(season_available)) if season_available else 0.0,
                    })
                available = [e for e in projected if not e["missing"]]
                watched = [e for e in available if is_completed(e)]
                active = [e for e in available if is_in_progress(e)]
                eligible = [e for e in regulars if not e["missing"]]
                movie_available = [e for e in movie_eps if not e["missing"]]
                current = self._current_from_rows(movie_available, include_movies=True) if a["media_kind"] == "movie" else self._current_from_rows(eligible)
                next_ep = None
                if a["media_kind"] != "movie":
                    if current and not is_completed(current):
                        next_ep = current
                    elif watched:
                        # History timestamp determines recency, but sequence
                        # continuation must use the furthest completed local episode.
                        latest_regular = [e for e in watched if is_regular_episode(e)]
                        if latest_regular:
                            latest = max(latest_regular, key=self._episode_order_key)
                            next_ep = self._adjacent_from_rows(latest, eligible, 1)
                result.append({
                    "id": a["id"], "main_title": a["title"], "meta": dict(a),
                    "favorite": bool(a["favorite"]), "is_pinned": bool(a["is_pinned"]),
                    "user_tags": self._decode_tags(a["user_tags"]),
                    "personal_note": a["personal_note"], "genres": genres, "genre_ids": genre_ids,
                    "seasons": ordered_seasons,
                    "specials": [{"season_name": "Especiais", "season": None, "folder_path": "",
                                  "episodes": sorted(special_eps, key=lambda e: (e["number"] if e["number"] is not None else -1, e["file_name"].casefold()))}],
                    "media_files": movie_eps,
                    "artwork_available": (
                        str(a["id"]) in local_artwork_anime
                        or bool(a["cover_cache"])
                    ),
                    "current_episode": current,
                    "next_episode": next_ep,
                    "available_count": len(available), "watched_count": len(watched),
                    "active_count": len(active), "missing_count": len(projected) - len(available),
                    "content_count": len(projected), "regular_count": len(regulars),
                    "special_count": len(special_eps), "movie_file_count": len(movie_eps),
                    "last_played_at": history.get(a["id"]),
                    "media_kind": a["media_kind"] or "series",
                })
            return result

    def catalog_page(
        self, *, page=0, page_size=36, query="", state="Todos", genre="Todos",
        sort="Mais recentes", tag="Todos", media_type="Todos", season=None,
        episode_type="Todos", source_kind="Todos", availability="Todos",
        metadata="Todos", artwork="Todos", favorites_only=False,
        watching_only=False, completed_only=False, _hydrate=True, _include_total=True,
    ):
        """Return one bounded catalog page directly from SQLite."""
        started = time.perf_counter()
        try: page = max(0, int(page))
        except (TypeError, ValueError): page = 0
        try: page_size = min(100, max(1, int(page_size)))
        except (TypeError, ValueError): page_size = 36
        where = ["EXISTS (SELECT 1 FROM episodes e0 WHERE e0.anime_id=a.id)"]
        params = []
        completed_sql = "(e.watched=1 OR (e.duration>0 AND MIN(MAX(COALESCE(e.progress,0),0),e.duration)/e.duration >= 0.90))"
        in_progress_sql = "(e.missing=0 AND COALESCE(e.progress,0)>0 AND NOT " + completed_sql + ")"
        unwatched_sql = "(e.missing=0 AND COALESCE(e.progress,0)<=0 AND NOT " + completed_sql + ")"
        state_sql = {
            "Favoritos": "a.favorite=1",
            "Fixados": "a.is_pinned=1",
            "Assistidos": f"EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND {completed_sql})",
            "Não assistidos": f"EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND {unwatched_sql})",
            "Em andamento": f"EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND {in_progress_sql})",
            "Concluídos": f"EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND e.missing=0) AND NOT EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND e.missing=0 AND NOT {completed_sql})",
            "Não iniciados": f"EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND e.missing=0) AND NOT EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND e.missing=0 AND (e.watched=1 OR COALESCE(e.progress,0)>0))",
            "Com nota": "NULLIF(TRIM(a.personal_note),'') IS NOT NULL",
            "Sem nota": "NULLIF(TRIM(a.personal_note),'') IS NULL",
            "Sem metadata": "(a.anilist_id IS NULL OR TRIM(COALESCE(a.anilist_id,''))='') AND COALESCE(a.metadata_source,'local') IN ('local','unresolved','unknown','')",
        }
        state = str(state or "Todos")
        if state in state_sql:
            where.append(state_sql[state])
        elif state == "Sem capa":
            where.append("NULLIF(TRIM(COALESCE(a.cover_cache,'')),'') IS NULL AND NULLIF(TRIM(COALESCE(a.cover_url,'')),'') IS NULL AND NULLIF(TRIM(COALESCE(a.banner_url,'')),'') IS NULL AND NOT EXISTS (SELECT 1 FROM artwork ar WHERE ar.entity_id=CAST(a.id AS TEXT) AND ar.status='ready' AND NULLIF(TRIM(COALESCE(ar.local_path,'')),'') IS NOT NULL)")
        # Compose Library may combine the three boolean filters. Keep the
        # SQL-side filtering bounded so pagination remains complete instead of
        # filtering only the already-loaded client page.
        if favorites_only:
            where.append("a.favorite=1")
        if watching_only:
            where.append(f"EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND {in_progress_sql})")
        if completed_only:
            where.append(
                f"EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND e.missing=0) "
                f"AND NOT EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND e.missing=0 AND NOT {completed_sql})"
            )
        media = str(media_type or "Todos")
        if media in {"Série/Anime", "Série", "Anime"}:
            where.append("a.media_kind!='movie' AND EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND LOWER(COALESCE(e.episode_type,'regular')) NOT IN ('special','ova','oad','ona','extra','movie'))")
        elif media in {"Filme", "Movie"}:
            where.append("(a.media_kind='movie' OR EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND e.episode_type='movie'))")
        elif media in {"Especial", "Special"}:
            where.append("EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND LOWER(COALESCE(e.episode_type,'regular')) IN ('special','ova','oad','ona','extra'))")
        elif media in {"Episódio", "Episode"}:
            where.append("EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND LOWER(COALESCE(e.episode_type,'regular')) NOT IN ('special','ova','oad','ona','extra','movie'))")
        if season not in (None, "", "Todos"):
            try: wanted_season = int(season)
            except (TypeError, ValueError): wanted_season = None
            if wanted_season is not None:
                where.append("EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND e.season=?)")
                params.append(wanted_season)
        if episode_type not in ("Todos", "", None):
            where.append("EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND LOWER(COALESCE(e.episode_type,'regular'))=LOWER(?))")
            params.append(str(episode_type))
        if source_kind not in ("Todos", "", None):
            where.append("EXISTS (SELECT 1 FROM episodes e JOIN folders f ON f.path=e.source_folder WHERE e.anime_id=a.id AND LOWER(COALESCE(f.kind,''))=LOWER(?))")
            params.append(str(source_kind))
        if genre not in ("Todos", "", None):
            label = str(genre).strip()
            where.append("(EXISTS (SELECT 1 FROM anime_genres ag JOIN genres g ON g.id=ag.genre_id WHERE ag.anime_id=a.id AND (CAST(g.id AS TEXT)=? OR LOWER(g.canonical_name)=LOWER(?) OR LOWER(g.normalized_name)=LOWER(?))) OR LOWER(COALESCE(a.genres,'')) LIKE LOWER(?))")
            params.extend([label, label, label, f"%{label}%"])
        if tag == "Sem etiqueta":
            where.append("COALESCE(TRIM(a.user_tags),'[]') IN ('[]','')")
        elif tag not in ("Todos", "", None):
            where.append("LOWER(COALESCE(a.user_tags,'')) LIKE LOWER(?)")
            params.append(f'%"{str(tag).strip()}"%')
        if availability == "Disponível":
            where.append("EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND e.missing=0)")
        elif availability in {"Com missing", "Missing"}:
            where.append("EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND e.missing=1)")
        elif availability == "Sem missing":
            where.append("NOT EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND e.missing=1)")
        if metadata == "Disponível":
            where.append("(a.anilist_id IS NOT NULL OR COALESCE(a.metadata_source,'local') NOT IN ('local','unresolved','unknown',''))")
        elif metadata == "Ausente":
            where.append("(a.anilist_id IS NULL AND COALESCE(a.metadata_source,'local') IN ('local','unresolved','unknown',''))")
        artwork_ready = "EXISTS (SELECT 1 FROM artwork ar WHERE ar.entity_id=CAST(a.id AS TEXT) AND ar.status='ready' AND NULLIF(TRIM(COALESCE(ar.local_path,'')),'') IS NOT NULL)"
        if artwork == "Disponível":
            where.append("(NULLIF(TRIM(COALESCE(a.cover_cache,'')),'') IS NOT NULL OR NULLIF(TRIM(COALESCE(a.cover_url,'')),'') IS NOT NULL OR " + artwork_ready + ")")
        elif artwork == "Ausente":
            where.append("NULLIF(TRIM(COALESCE(a.cover_cache,'')),'') IS NULL AND NULLIF(TRIM(COALESCE(a.cover_url,'')),'') IS NULL AND NOT " + artwork_ready)
        raw = str(query or "").strip()
        compact = re.sub(r'\s+', '', raw.casefold())
        m = re.fullmatch(r's(\d{1,3})e(\d{1,5})', compact)
        sm = re.fullmatch(r's(\d{1,3})', compact)
        em = re.fullmatch(r'(?:e|ep|episodio|episode)(\d{1,5})', compact)
        am = re.fullmatch(r'(?:absolute|abs)(\d+(?:\.\d+)?)', compact)
        bm = re.fullmatch(r'\d+(?:\.\d+)?', compact)
        if m:
            where.append("EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND e.missing=0 AND e.season=? AND e.number=?)")
            params.extend([int(m.group(1)), float(m.group(2))])
        elif sm:
            where.append("EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND e.season=?)")
            params.append(int(sm.group(1)))
        elif em or am or bm:
            value = float((em or am).group(1) if (em or am) else bm.group(0))
            if am:
                where.append("EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND e.absolute_number=?)")
                params.append(value)
            else:
                where.append("EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND (e.number=? OR e.absolute_number=?))")
                params.extend([value, value])
        elif raw:
            normalized_query = normalize_text(raw)
            tokens = [t for t in re.findall(r'[\w]+', normalized_query) if t]
            cols = ("a.title", "a.romaji", "a.english", "a.native", "a.aliases", "a.description", "a.genres", "a.user_tags", "a.personal_note")
            for token in tokens:
                like = f"%{token}%"
                cols_sql = " OR ".join(f"reiflix_normalize(COALESCE({column},'')) LIKE ?" for column in cols)
                where.append(f"({cols_sql} OR EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND (reiflix_normalize(COALESCE(e.file_name,'')) LIKE ? OR reiflix_normalize(COALESCE(e.episode_title,'')) LIKE ? OR reiflix_normalize(COALESCE(e.path,'')) LIKE ?)))")
                params.extend([like] * len(cols) + [like, like, like])
        order_map = {
            "Mais recentes": "a.added_at DESC, a.title COLLATE NOCASE ASC, a.id DESC",
            "Assistidos recentemente": "(SELECT COALESCE(MAX(e.last_played_at),0) FROM episodes e WHERE e.anime_id=a.id) DESC, a.title COLLATE NOCASE ASC, a.id DESC",
            "Progresso": "(SELECT COALESCE(AVG(CASE WHEN e.missing=0 AND e.duration>0 THEN MIN(MAX(COALESCE(e.progress,0),0),e.duration)/e.duration ELSE 0 END),0) FROM episodes e WHERE e.anime_id=a.id) DESC, a.title COLLATE NOCASE ASC, a.id DESC",
            "Episódio": "(SELECT COALESCE(e.number,999999) FROM episodes e WHERE e.anime_id=a.id AND e.missing=0 ORDER BY COALESCE(e.season,999999), COALESCE(e.number,999999), COALESCE(e.absolute_number,999999) LIMIT 1), a.title COLLATE NOCASE ASC, a.id DESC",
            "Temporada + episódio": "(SELECT COALESCE(MIN(CASE WHEN e.missing=0 THEN e.season END),999999) FROM episodes e WHERE e.anime_id=a.id) ASC, (SELECT COALESCE(MIN(CASE WHEN e.missing=0 THEN e.number END),999999) FROM episodes e WHERE e.anime_id=a.id) ASC, a.title COLLATE NOCASE ASC, a.id DESC",
            "Modificação": "(SELECT COALESCE(MAX(e.modified_at),0) FROM episodes e WHERE e.anime_id=a.id) DESC, a.title COLLATE NOCASE ASC, a.id DESC",
            "Duração": "(SELECT COALESCE(SUM(e.duration),0) FROM episodes e WHERE e.anime_id=a.id) DESC, a.title COLLATE NOCASE ASC, a.id DESC",
            "Tamanho": "(SELECT COALESCE(SUM(e.file_size),0) FROM episodes e WHERE e.anime_id=a.id) DESC, a.title COLLATE NOCASE ASC, a.id DESC",
            "Favoritos primeiro": "a.favorite DESC, a.title COLLATE NOCASE ASC, a.id DESC",
            "Fixados primeiro": "a.is_pinned DESC, a.title COLLATE NOCASE ASC, a.id DESC",
            "Nome A-Z": "LOWER(COALESCE(a.title,'')) ASC, a.id ASC",
            "Nome Z-A": "LOWER(COALESCE(a.title,'')) DESC, a.id DESC",
        }
        order_by = order_map.get(sort or "Mais recentes", order_map["Mais recentes"])
        where_sql = " AND ".join(where)
        with self._conn() as c:
            total = None
            if _include_total:
                total = int(c.execute(f"SELECT COUNT(*) FROM anime a WHERE {where_sql}", tuple(params)).fetchone()[0] or 0)
            offset = page * page_size
            rows = c.execute(
                f"SELECT a.id FROM anime a WHERE {where_sql} ORDER BY {order_by} LIMIT ? OFFSET ?",
                tuple(params + [page_size, offset]),
            ).fetchall()
            ids = [int(row["id"]) for row in rows]
        if not _hydrate:
            result = {
                "ids": ids,
                "page": page,
                "page_size": page_size,
                "total": total,
                "has_more": (offset + len(ids) < total) if total is not None else None,
            }
            get_performance_monitor().record_sqlite(
                "catalog_page_ids",
                (time.perf_counter()-started)*1000.0,
                rows=len(ids),
                metadata={"page": page, "page_size": page_size, "include_total": bool(_include_total)},
            )
            return result
        items = self.catalog(anime_ids=ids) if ids else []
        by_id = {int(item["id"]): item for item in items}
        ordered = [by_id[anime_id] for anime_id in ids if anime_id in by_id]
        result = {"items": ordered, "page": page, "page_size": page_size, "total": int(total or 0), "has_more": offset + len(ordered) < int(total or 0)}
        get_performance_monitor().record_sqlite("catalog_page", (time.perf_counter()-started)*1000.0,
                                                rows=len(ordered), metadata={"page": page, "page_size": page_size})
        return result

    def search_options(self):
        """Return filter values without projecting the full visual catalog."""
        with self._conn() as c:
            seasons = [int(row[0]) for row in c.execute("SELECT DISTINCT season FROM episodes WHERE season IS NOT NULL ORDER BY season").fetchall()]
            episode_types = [str(row[0]) for row in c.execute("SELECT DISTINCT episode_type FROM episodes WHERE episode_type IS NOT NULL AND episode_type!='' ORDER BY episode_type").fetchall()]
            source_kinds = [str(row[0]) for row in c.execute("SELECT DISTINCT f.kind FROM episodes e JOIN folders f ON f.path=e.source_folder WHERE f.kind IS NOT NULL AND f.kind!='' ORDER BY f.kind").fetchall()]
            tags_rows = c.execute("SELECT user_tags FROM anime WHERE user_tags IS NOT NULL").fetchall()
        tags = {}
        for row in tags_rows:
            try: values = json.loads(row["user_tags"] or "[]")
            except (TypeError, json.JSONDecodeError): values = []
            for value in values if isinstance(values, list) else []:
                text_value = str(value).strip()
                if text_value: tags[text_value.casefold()] = text_value
        return {"genres": [], "tags": sorted(tags.values(), key=str.casefold), "seasons": seasons, "episode_types": episode_types, "source_kinds": source_kinds, "media_types": ["Série/Anime", "Filme", "Especial", "Episódio"], "availability": ["Disponível", "Com missing", "Sem missing"], "metadata": ["Disponível", "Ausente"], "artwork": ["Disponível", "Ausente"], "states": ["Todos", "Favoritos", "Fixados", "Assistidos", "Não assistidos", "Em andamento", "Concluídos", "Não iniciados", "Com nota", "Sem nota", "Sem metadata", "Sem capa"], "sorts": ["Mais recentes", "Assistidos recentemente", "Progresso", "Episódio", "Temporada + episódio", "Modificação", "Duração", "Tamanho", "Favoritos primeiro", "Fixados primeiro", "Nome A-Z", "Nome Z-A"]}

    def random_catalog_item(self, *, exclude_id=None, **filters):
        """Select one existing library item without materializing the whole catalog."""
        import random

        page = self.catalog_page(page=0, page_size=1, **filters)
        total = int(page.get("total") or 0)
        if total <= 0:
            return None

        attempts = 4 if total > 1 else 1
        for _ in range(attempts):
            offset = random.SystemRandom().randrange(total)
            candidate = self.catalog_page(page=offset, page_size=1, **filters)
            item = (candidate.get("items") or [None])[0]
            if not item:
                continue
            if exclude_id is None or int(item.get("id") or -1) != int(exclude_id):
                return item

        if exclude_id is not None and total > 1:
            # Deterministic fallback avoids an immediate repeat even if a random
            # offset lands on the excluded item several times.
            first = self.catalog_page(page=0, page_size=min(2, total), **filters).get("items") or []
            for item in first:
                if int(item.get("id") or -1) != int(exclude_id):
                    return item
        return (page.get("items") or [None])[0]

    def timeline_items(self):
        """Return the minimal local metadata needed to build a release timeline."""
        with self._conn() as c:
            rows = c.execute(
                """
                SELECT a.id, a.title, a.year, a.season, a.status, a.media_kind,
                       a.cover_cache, a.cover_url, a.added_at
                FROM anime a
                WHERE EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id)
                ORDER BY
                    CASE WHEN a.year IS NULL THEN 1 ELSE 0 END,
                    a.year DESC,
                    a.title COLLATE NOCASE ASC,
                    a.id ASC
                """
            ).fetchall()
        return [dict(row) for row in rows]

    def duration_observations(self, *, anime_id=None):
        """Return only persisted episode duration fields required by the anomaly detector."""
        where = [
            "e.missing=0",
            "LOWER(COALESCE(e.episode_type,'regular')) NOT IN ('special','ova','oad','ona','extra','movie')",
        ]
        params = []
        if anime_id is not None:
            where.append("e.anime_id=?")
            params.append(int(anime_id))
        where_sql = " AND ".join(where)
        with self._conn() as c:
            rows = c.execute(
                f"""
                SELECT e.id, e.anime_id, e.season, e.number, e.absolute_number,
                       e.file_name, e.episode_title, e.duration,
                       a.title AS anime_title
                FROM episodes e
                JOIN anime a ON a.id=e.anime_id
                WHERE {where_sql}
                ORDER BY e.anime_id, e.season, e.number, e.absolute_number, e.id
                """,
                tuple(params),
            ).fetchall()
        return [dict(row) for row in rows]

    def marathon_episodes(self, anime_id):
        """Return the existing regular local episodes for one anime only."""
        with self._conn() as c:
            rows = c.execute(
                """
                SELECT id, anime_id, path, file_name, season, number,
                       absolute_number, duration, progress, watched,
                       missing, episode_type, episode_title
                FROM episodes
                WHERE anime_id=? AND missing=0
                  AND LOWER(COALESCE(episode_type,'regular')) NOT IN
                      ('special','ova','oad','ona','extra','movie')
                ORDER BY
                    COALESCE(season,1000000),
                    COALESCE(number,1000000),
                    COALESCE(absolute_number,1000000),
                    id
                """,
                (int(anime_id),),
            ).fetchall()
        return [dict(row) for row in rows]

    def collector_snapshot(self):
        """Return canonical local consumption/metadata rows for Collector Journey rebuilds."""
        with self._conn() as c:
            rows = c.execute(
                """
                SELECT
                    e.id,
                    e.anime_id,
                    e.progress,
                    e.duration,
                    e.watched,
                    e.missing,
                    e.last_played_at,
                    e.episode_type,
                    a.title AS anime_title,
                    a.year,
                    a.genres,
                    a.format,
                    a.media_kind
                FROM episodes e
                JOIN anime a ON a.id=e.anime_id
                WHERE e.missing=0
                ORDER BY e.anime_id, e.season, e.number, e.absolute_number, e.id
                """
            ).fetchall()
        return [dict(row) for row in rows]

    def home_sections(self, limit=12, *, continue_limit=None):
        """Build only the bounded projections still displayed on Home."""
        started = time.perf_counter()
        page_limit = min(24, max(1, int(limit)))
        section_filters = {
            "favorites": {"state": "Favoritos", "sort": "Mais recentes"},
            "pinned": {"state": "Fixados", "sort": "Mais recentes"},
            "movies": {"media_type": "Filme", "sort": "Mais recentes"},
        }
        section_ids = {}
        all_ids = set()
        for name, filters in section_filters.items():
            projection = self.catalog_page(
                page=0,
                page_size=page_limit,
                _hydrate=False,
                _include_total=False,
                **filters,
            )
            ids = projection["ids"]
            section_ids[name] = ids
            all_ids.update(ids)

        hydrated = self.catalog(anime_ids=sorted(all_ids)) if all_ids else []
        by_id = {int(item["id"]): item for item in hydrated}
        continue_page_limit = page_limit if continue_limit is None else min(24, max(1, int(continue_limit)))
        result = {
            "continue_watching": self.continue_watching(limit=continue_page_limit),
            "favorites": [by_id[anime_id] for anime_id in section_ids["favorites"] if anime_id in by_id],
            "pinned": [by_id[anime_id] for anime_id in section_ids["pinned"] if anime_id in by_id],
            "movies": [by_id[anime_id] for anime_id in section_ids["movies"] if anime_id in by_id],
        }
        total_rows = sum(len(value or []) for value in result.values())
        get_performance_monitor().record_sqlite(
            "home_sections",
            (time.perf_counter() - started) * 1000.0,
            rows=total_rows,
            metadata={"limit": page_limit, "sections": len(result), "catalog_hydrations": 1 if all_ids else 0},
        )
        return result
    def organize_summary(self):
        """Return bounded Organize counters and genre summaries from SQLite."""
        started = time.perf_counter()
        logger.info("ORGANIZE_QUERY_START")
        with self._conn() as c:
            completed_sql = """(
                e.watched=1 OR
                (e.duration>0 AND
                 MIN(MAX(COALESCE(e.progress,0),0),e.duration) / e.duration >= 0.90)
            )"""
            per_anime = f"""
                WITH per_anime AS (
                    SELECT
                        a.id,
                        a.favorite,
                        a.is_pinned,
                        SUM(CASE WHEN e.missing=0 THEN 1 ELSE 0 END) AS available,
                        SUM(CASE WHEN {completed_sql} THEN 1 ELSE 0 END) AS completed_any,
                        SUM(CASE WHEN e.missing=0 AND {completed_sql} THEN 1 ELSE 0 END) AS completed,
                        SUM(CASE WHEN e.missing=0 AND COALESCE(e.progress,0)>0
                                  AND NOT {completed_sql} THEN 1 ELSE 0 END) AS active,
                        SUM(CASE WHEN e.missing=0 AND COALESCE(e.progress,0)<=0
                                  AND NOT {completed_sql} THEN 1 ELSE 0 END) AS unwatched
                    FROM anime a
                    JOIN episodes e ON e.anime_id=a.id
                    GROUP BY a.id
                )
                SELECT
                    COUNT(*) AS all_count,
                    SUM(CASE WHEN favorite=1 THEN 1 ELSE 0 END) AS favorites_count,
                    SUM(CASE WHEN is_pinned=1 THEN 1 ELSE 0 END) AS pinned_count,
                    SUM(CASE WHEN completed_any>0 THEN 1 ELSE 0 END) AS watched_count,
                    SUM(CASE WHEN unwatched>0 THEN 1 ELSE 0 END) AS not_watched_count,
                    SUM(CASE WHEN active>0 THEN 1 ELSE 0 END) AS active_count,
                    SUM(CASE WHEN available>0 AND completed=available THEN 1 ELSE 0 END) AS completed_anime_count,
                    SUM(CASE WHEN available>0 AND completed=0 AND active=0 THEN 1 ELSE 0 END) AS not_started_count
                FROM per_anime
            """
            state_row = c.execute(per_anime).fetchone()
            values = {
                "Todos": int(state_row["all_count"] or 0),
                "Favoritos": int(state_row["favorites_count"] or 0),
                "Fixados": int(state_row["pinned_count"] or 0),
                "Assistidos": int(state_row["watched_count"] or 0),
                "Não assistidos": int(state_row["not_watched_count"] or 0),
                "Em andamento": int(state_row["active_count"] or 0),
                "Concluídos": int(state_row["completed_anime_count"] or 0),
                "Não iniciados": int(state_row["not_started_count"] or 0),
            }
            collections = [{"name": name, "count": count} for name, count in values.items()]
            genre_rows = c.execute("""
                SELECT g.id, g.canonical_name, COUNT(DISTINCT ag.anime_id) AS count,
                       MIN(CASE WHEN NULLIF(TRIM(a.cover_cache),'') IS NOT NULL THEN a.cover_cache
                                WHEN NULLIF(TRIM(a.cover_url),'') IS NOT NULL THEN a.cover_url END) AS cover
                FROM genres g
                JOIN anime_genres ag ON ag.genre_id=g.id
                JOIN anime a ON a.id=ag.anime_id
                JOIN episodes e
                  ON e.anime_id=a.id
                 AND e.missing=0
                 AND COALESCE(e.availability_state,'available')='available'
                GROUP BY g.id, g.canonical_name, g.normalized_name
                ORDER BY g.normalized_name
            """).fetchall()
        result = {
            "collections": collections,
            "states": [item for item in collections if item["name"] in {"Todos","Favoritos","Em andamento","Concluídos"}],
            "genres": [{"id": str(row["id"]), "name": str(row["canonical_name"]), "count": int(row["count"] or 0), "cover": row["cover"] or ""} for row in genre_rows],
        }
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        logger.info(
            "ORGANIZE_QUERY_DONE collections=%s genres=%s duration_ms=%.2f",
            len(collections), len(result["genres"]), elapsed_ms,
        )
        get_performance_monitor().record_sqlite(
            "organize_summary",
            elapsed_ms,
            rows=len(result["genres"]),
            metadata={
                "collections": len(collections),
                "genres": len(result["genres"]),
                "state_queries": 1,
            },
        )
        return result

    def set_episode_identification(self, path, *, season=None, number=None, episode_type="regular", title=None):
        """Persist an explicit user identification without changing consumption data."""
        if season is None:
            normalized_season = 0
        else:
            try:
                raw_season = float(season)
            except (TypeError, ValueError):
                raise ValueError("Temporada inválida.")
            if not math.isfinite(raw_season) or raw_season < 0 or not raw_season.is_integer():
                raise ValueError("Temporada inválida.")
            normalized_season = int(raw_season)
        if number is None:
            normalized_number = None
        else:
            try:
                normalized_number = float(number)
            except (TypeError, ValueError):
                raise ValueError("Número do episódio inválido.")
            if not math.isfinite(normalized_number) or normalized_number < 0:
                raise ValueError("Número do episódio inválido.")
            if normalized_number.is_integer():
                normalized_number = int(normalized_number)
        normalized_type = str(episode_type or "regular").casefold()
        allowed = {"regular", "special", "ova", "oad", "ona", "extra", "movie", "unknown"}
        if normalized_type not in allowed:
            raise ValueError("Tipo de episódio inválido.")
        clean_title = (str(title).strip() if title is not None else "") or None
        with self._conn() as c:
            row = c.execute("SELECT id FROM episodes WHERE path=?", (path,)).fetchone()
            if not row:
                raise ValueError("Arquivo local não encontrado.")
            c.execute(
                "UPDATE episodes SET season=?,number=?,episode_type=?,episode_title=?,"
                "identification_source='manual',identification_confidence='high',manual_override=1,missing=0 WHERE id=?",
                (normalized_season, normalized_number, normalized_type, clean_title, row["id"]),
            )
            if normalized_type == "movie":
                c.execute(
                    "UPDATE anime SET media_kind='movie' WHERE id=(SELECT anime_id FROM episodes WHERE id=?)",
                    (row["id"],),
                )
        return True

    def apply_episode_identification(self, path, *, absolute_number=None, relative_path=None, volume_id=None, volume_uuid=None, episode_type="regular", episode_title=None, identification_source="legacy", identification_confidence="medium"):
        with self._conn() as c:
            row=c.execute(
                "SELECT manual_override,season,number,episode_type,episode_title,identification_source,identification_confidence FROM episodes WHERE path=?",
                (path,),
            ).fetchone()
            if not row:
                raise ValueError("Arquivo local não encontrado.")
            if row["manual_override"]:
                c.execute("UPDATE episodes SET absolute_number=COALESCE(?,absolute_number),relative_path=COALESCE(?,relative_path),volume_id=COALESCE(?,volume_id),volume_uuid=COALESCE(?,volume_uuid),missing=0 WHERE path=?",(absolute_number,relative_path,volume_id,volume_uuid,path))
            elif self._identification_confidence_rank(identification_confidence) < self._identification_confidence_rank(row["identification_confidence"]):
                # Keep the durable semantic identity when the follow-up parser has
                # less evidence; still refresh non-semantic native fields.
                c.execute(
                    "UPDATE episodes SET absolute_number=COALESCE(?,absolute_number),relative_path=COALESCE(?,relative_path),volume_id=COALESCE(?,volume_id),volume_uuid=COALESCE(?,volume_uuid),missing=0 WHERE path=?",
                    (absolute_number, relative_path, volume_id, volume_uuid, path),
                )
            else:
                c.execute("UPDATE episodes SET absolute_number=?,relative_path=COALESCE(?,relative_path),volume_id=COALESCE(?,volume_id),volume_uuid=COALESCE(?,volume_uuid),episode_type=?,episode_title=?,identification_source=?,identification_confidence=?,missing=0 WHERE path=?",(absolute_number,relative_path,volume_id,volume_uuid,episode_type,episode_title,identification_source,identification_confidence,path))
            return True

    def activate_playback_session(self, session_id):
        session = str(session_id or "").strip()
        if not session:
            return False
        with self._playback_session_lock:
            self._active_playback_session_id = session
        return True

    def invalidate_playback_session(self, session_id):
        session = str(session_id or "").strip()
        with self._playback_session_lock:
            if session and self._active_playback_session_id not in (None, session):
                return False
            self._active_playback_session_id = None
        return True

    def episode_by_id(self, episode_id):
        try:
            normalized_id = int(episode_id)
        except (TypeError, ValueError):
            return None
        if normalized_id <= 0:
            return None
        with self._conn() as c:
            row = c.execute(
                "SELECT e.*, a.title AS anime_title FROM episodes e "
                "JOIN anime a ON a.id=e.anime_id WHERE e.id=?",
                (normalized_id,),
            ).fetchone()
        return dict(row) if row else None

    def save_progress(self, path, position, duration, *, episode_id=None, media_id=None, event_created_at=None, session_id=None):
        # Playback ordering contract: if durable_time <= last_seen, reject the stale event before any write.
        """Persist one normalized playback event with canonical local-media identity."""
        started = time.perf_counter()
        try:
            position, duration = float(position), float(duration)
            event_time = None if event_created_at is None else float(event_created_at)
        except (TypeError, ValueError):
            return False
        if not (math.isfinite(position) and math.isfinite(duration)):
            return False
        if event_created_at is not None and (event_time is None or not math.isfinite(event_time)):
            return False
        if position < 0 or duration < 0:
            return False
        if duration > 0:
            position = min(position, duration)

        normalized_session_id = str(session_id or "").strip()
        with self._playback_session_lock:
            active_session_id = self._active_playback_session_id
            if normalized_session_id and active_session_id not in (None, normalized_session_id):
                get_performance_monitor().record_sqlite(
                    "save_progress",
                    (time.perf_counter() - started) * 1000.0,
                    rows=0,
                    status="stale_session",
                    metadata={
                        "session_id": normalized_session_id,
                        "active_session_id": active_session_id,
                    },
                )
                return False

            durable_time = time.time()
            with self._conn() as c:
                parsed_episode_id = None
                try:
                    if episode_id is not None and str(episode_id).strip():
                        parsed_episode_id = int(episode_id)
                except (TypeError, ValueError):
                    parsed_episode_id = None
                if parsed_episode_id is not None and parsed_episode_id <= 0:
                    parsed_episode_id = None

                if parsed_episode_id is not None:
                    row = c.execute(
                        "SELECT * FROM episodes WHERE id=?",
                        (parsed_episode_id,),
                    ).fetchone()
                    if not row:
                        return False
                    if path:
                        path_row = self._find_episode_row(c, path)
                        if path_row and path_row["id"] != row["id"]:
                            get_performance_monitor().record_sqlite(
                                "save_progress",
                                (time.perf_counter() - started) * 1000.0,
                                rows=0,
                                status="identity_mismatch",
                                metadata={"episode_id": parsed_episode_id},
                            )
                            return False
                else:
                    row = self._find_episode_row(c, path)
                    if not row:
                        return False

                canonical_path = str(row["path"])
                canonical_episode_id = int(row["id"])
                normalized_media_id = str(media_id or "").strip()
                if normalized_media_id:
                    expected_media_id = f"episode:{canonical_episode_id}"
                    # NativePlayerActivity uses the canonical episode identity as
                    # MediaItem.mediaId. Reject a mismatched identity at the
                    # persistence boundary so late events cannot be written to
                    # another episode.
                    if parsed_episode_id is not None:
                        if normalized_media_id != expected_media_id:
                            get_performance_monitor().record_sqlite(
                                "save_progress",
                                (time.perf_counter() - started) * 1000.0,
                                rows=0,
                                status="media_identity_mismatch",
                                metadata={
                                    "episode_id": canonical_episode_id,
                                    "media_id": normalized_media_id,
                                    "expected_media_id": expected_media_id,
                                },
                            )
                            return False
                    elif normalized_media_id not in {expected_media_id, canonical_path}:
                        return False
                stored_duration = float(row["duration"] or 0)
                if duration <= 0 and stored_duration > 0:
                    duration = stored_duration
                if duration > 0:
                    position = min(position, duration)

                watched = int(
                    is_completed(
                        {
                            "progress": position,
                            "duration": duration,
                            "watched": bool(row["watched"]),
                        }
                    )
                )

                if event_time is not None:
                    durable_time = (
                        event_time / 1000.0
                        if event_time > 10_000_000_000
                        else event_time
                    )
                    event_key = f"episode:{canonical_episode_id}"
                    last_seen = self._last_playback_event_at.get(event_key, 0.0)
                    if durable_time <= last_seen:
                        get_performance_monitor().record_sqlite(
                            "save_progress",
                            (time.perf_counter() - started) * 1000.0,
                            rows=0,
                            status="stale",
                        )
                        return False

                    updated = c.execute(
                        """UPDATE episodes
                           SET progress=?, duration=?, watched=?, last_played_at=?
                           WHERE id=?
                             AND (last_played_at IS NULL OR last_played_at < ?)""",
                        (
                            position,
                            duration,
                            watched,
                            durable_time,
                            canonical_episode_id,
                            durable_time,
                        ),
                    ).rowcount
                    if updated:
                        self._last_playback_event_at[event_key] = durable_time
                        if len(self._last_playback_event_at) > 8192:
                            oldest = sorted(
                                self._last_playback_event_at.items(),
                                key=lambda item: item[1],
                            )[:2048]
                            for old_key, _ in oldest:
                                self._last_playback_event_at.pop(old_key, None)
                    get_performance_monitor().record_sqlite(
                        "save_progress",
                        (time.perf_counter() - started) * 1000.0,
                        rows=int(bool(updated)),
                        status="ok" if updated else "ignored",
                    )
                    return bool(updated)

                updated = c.execute(
                    "UPDATE episodes SET progress=?,duration=?,watched=?,last_played_at=? WHERE id=?",
                    (position, duration, watched, durable_time, canonical_episode_id),
                ).rowcount

        get_performance_monitor().record_sqlite(
            "save_progress",
            (time.perf_counter() - started) * 1000.0,
            rows=int(bool(updated)),
            status="ok" if updated else "ignored",
        )
        return bool(updated)
    @staticmethod
    def consumption_state(episode):
        return consumption_state(episode).value

    @staticmethod
    def is_completed(episode):
        return is_completed(episode)

    @staticmethod
    def is_in_progress(episode):
        return is_in_progress(episode)

    def set_watched(self, path, watched, *, episode_id=None):
        """Set an episode completion state using canonical local-media identity."""
        with self._conn() as c:
            parsed_episode_id = None
            try:
                if episode_id is not None and str(episode_id).strip():
                    parsed_episode_id = int(episode_id)
            except (TypeError, ValueError):
                parsed_episode_id = None
            if parsed_episode_id is not None and parsed_episode_id <= 0:
                parsed_episode_id = None
            if parsed_episode_id is not None:
                row = c.execute("SELECT * FROM episodes WHERE id=?", (parsed_episode_id,)).fetchone()
                if not row:
                    return False
                if path:
                    path_row = self._find_episode_row(c, path)
                    if path_row and path_row["id"] != row["id"]:
                        return False
            else:
                row = self._find_episode_row(c, path)
                if not row:
                    return False
            canonical_path = str(row["path"])
            duration = float(row["duration"] or 0)
            progress = duration if watched and duration > 0 else (0 if not watched else 0)
            c.execute(
                "UPDATE episodes SET watched=?,progress=?,last_played_at=? WHERE id=?",
                (int(bool(watched)), progress, time.time(), int(row["id"])),
            )
        return True
    @staticmethod
    def _episode_order_key(episode):
        def numeric(value, default=10**6):
            try:
                number = float(value)
            except (TypeError, ValueError):
                return default
            return number if math.isfinite(number) else default

        season = numeric(episode.get("season"))
        number = numeric(episode.get("number"))
        absolute = numeric(episode.get("absolute_number"))
        identity = str(episode.get("media_identity") or "")
        file_name = str(episode.get("file_name") or "").casefold()
        path = str(episode.get("path") or "").casefold()

        if number < 10**6:
            return (season, 1, number, absolute, identity, file_name, path)
        if absolute < 10**6:
            return (season, 0, absolute, identity, file_name, path)
        return (season, 0, 10**6, identity, file_name, path)

    @staticmethod
    def _adjacent_from_rows(current, available, direction):
        if not current:
            return None
        current_key = LibraryStore._episode_order_key(current)
        ordered = sorted(available, key=LibraryStore._episode_order_key)
        if direction < 0:
            ordered.reverse()
        for episode in ordered:
            key = LibraryStore._episode_order_key(episode)
            if (direction > 0 and key > current_key) or (direction < 0 and key < current_key):
                return episode
        return None

    def adjacent_episode(self, path, direction=1):
        """Return the adjacent playable local episode in catalog order."""
        started = time.perf_counter()
        if direction not in (-1, 1):
            raise ValueError("direction must be -1 or 1")
        with self._conn() as c:
            current = self._find_episode_row(c, path)
            if not current:
                return None
            if not is_regular_episode(dict(current)):
                return None
            rows = c.execute(
                "SELECT e.*, a.title AS anime_title FROM episodes e JOIN anime a ON a.id=e.anime_id WHERE e.anime_id=? AND e.missing=0 AND COALESCE(e.availability_state,'available')='available' AND e.episode_type NOT IN ('movie','special','ova','oad','ona','extra')",
                (current["anime_id"],),
            ).fetchall()
        current_row = dict(current)
        result = self._adjacent_from_rows(
            current_row,
            [dict(row) for row in rows],
            direction,
        )
        get_performance_monitor().record_sqlite("next_episode" if direction > 0 else "previous_episode",
                                                (time.perf_counter()-started)*1000.0, rows=len(rows))
        return result
    def player_navigation(self, path):
        """Resolve Next/Previous plus destination edge flags in one SQLite read."""
        started = time.perf_counter()
        monitor = get_performance_monitor()
        monitor.event("SQLITE_NEIGHBOR_QUERY_STARTED", screen="player",
                      metadata={"operation": "player_navigation"})
        with self._conn() as c:
            current = self._find_episode_row(c, path)
            if not current or not is_regular_episode(dict(current)):
                return {
                    "current": dict(current) if current else None,
                    "next": None,
                    "previous": None,
                    "can_next": False,
                    "can_previous": False,
                    "next_can_next": False,
                    "next_can_previous": False,
                    "previous_can_next": False,
                    "previous_can_previous": False,
                }
            rows = c.execute(
                "SELECT e.*, a.title AS anime_title FROM episodes e JOIN anime a ON a.id=e.anime_id "
                "WHERE e.anime_id=? AND e.missing=0 "
                "AND COALESCE(e.availability_state,'available')='available' "
                "AND e.episode_type NOT IN "
                "('movie','special','ova','oad','ona','extra')",
                (current["anime_id"],),
            ).fetchall()
        available = [dict(row) for row in rows]
        current_row = dict(current)
        next_item = self._adjacent_from_rows(current_row, available, 1)
        previous_item = self._adjacent_from_rows(current_row, available, -1)
        next_after = self._adjacent_from_rows(next_item, available, 1) if next_item else None
        previous_before = self._adjacent_from_rows(previous_item, available, -1) if previous_item else None
        result = {
            "current": current_row,
            "next": next_item,
            "previous": previous_item,
            "can_next": next_item is not None,
            "can_previous": previous_item is not None,
            "next_can_next": next_after is not None,
            "next_can_previous": next_item is not None,
            "previous_can_next": previous_item is not None,
            "previous_can_previous": previous_before is not None,
        }
        monitor.event(
            "SQLITE_NEIGHBOR_QUERY_FINISHED",
            duration_ms=(time.perf_counter() - started) * 1000.0,
            screen="player",
            metadata={"operation": "player_navigation", "rows": len(rows),
                      "can_next": next_item is not None,
                      "can_previous": previous_item is not None},
        )
        monitor.record_sqlite(
            "player_navigation",
            (time.perf_counter() - started) * 1000.0,
            rows=len(rows),
        )
        return result

    def next_episode(self, path):
        return self.adjacent_episode(path, 1)

    def previous_episode(self, path):
        return self.adjacent_episode(path, -1)

    @staticmethod
    def _current_from_rows(episodes, *, include_movies=False):
        """Choose a playable current item using the central availability policy."""
        available = [episode for episode in episodes
                     if not episode.get("missing", False)
                     and (include_movies and episode.get("episode_type") == "movie" or is_regular_episode(episode))]
        available.sort(key=LibraryStore._episode_order_key)
        if not available:
            return None

        partial = next(
            (
                episode for episode in sorted(
                    available,
                    key=lambda entry: entry.get("last_played_at") or 0,
                    reverse=True,
                )
                if is_in_progress(episode)
            ),
            None,
        )
        if partial:
            return partial

        completed = [episode for episode in available if is_completed(episode)]
        if completed:
            # Sequence continuation follows the furthest completed episode,
            # so replaying an older episode cannot move Next Episode backwards.
            furthest_completed = max(completed, key=LibraryStore._episode_order_key)
            next_episode = LibraryStore._adjacent_from_rows(furthest_completed, available, 1)
            if next_episode:
                return next_episode

        for episode in available:
            if not is_completed(episode):
                return episode

        return available[0]

    def current_episode(self, anime_id):
        started = time.perf_counter()
        with self._conn() as c:
            anime = c.execute("SELECT media_kind FROM anime WHERE id=?", (anime_id,)).fetchone()
            rows = c.execute(
                "SELECT * FROM episodes WHERE anime_id=? AND missing=0 "
                "AND COALESCE(availability_state,'available')='available'",
                (anime_id,),
            ).fetchall()
        get_performance_monitor().record_sqlite("current_episode", (time.perf_counter()-started)*1000.0, rows=len(rows))
        episodes = [dict(row) for row in rows]
        if anime and str(anime["media_kind"] or "series").casefold() == "movie":
            return episodes[0] if episodes else None
        return self._current_from_rows(episodes)

    def _conn_media_kind(self, anime_id):
        with self._conn() as c:
            row = c.execute("SELECT media_kind FROM anime WHERE id=?", (anime_id,)).fetchone()
            return row["media_kind"] if row else None

    def playback_target(self, anime_id):
        """Return the single local episode the Details primary action should play.

        This intentionally owns the continuation policy so the UI does not need
        to reproduce ordering, completion, or missing-file rules.
        """
        started = time.perf_counter()
        monitor = get_performance_monitor()
        monitor.event("SQLITE_PLAYBACK_LOOKUP_STARTED", screen="details",
                      metadata={"operation": "playback_target", "anime_id": anime_id})
        current = self.current_episode(anime_id)
        if current:
            get_performance_monitor().record_sqlite("playback_target", (time.perf_counter()-started)*1000.0,
                                                    rows=1, metadata={"source": "current_episode"})
            return current
        with self._conn() as c:
            rows = c.execute(
                "SELECT * FROM episodes WHERE anime_id=? AND missing=0 "
                "AND COALESCE(availability_state,'available')='available'",
                (anime_id,),
            ).fetchall()
        media_kind = str(self._conn_media_kind(anime_id) or "series").casefold()
        candidates = [
            dict(row) for row in rows
            if media_kind == "movie" or is_regular_episode(dict(row))
        ]
        ordered = sorted(candidates, key=self._episode_order_key)
        if ordered:
            result = ordered[0]
            get_performance_monitor().record_sqlite("playback_target", (time.perf_counter()-started)*1000.0,
                                                    rows=len(rows), metadata={"source": "ordered_regular"})
            return result
        # A library containing only specials still needs a valid Details
        # playback target, but specials must never become part of the regular
        # episode sequence used by next/previous/autoplay.
        specials = [
            dict(row) for row in rows
            if str(row["episode_type"] or "").casefold() in {"special", "ova", "oad", "ona", "extra"}
        ]
        result = sorted(specials, key=self._episode_order_key)[0] if specials else None
        get_performance_monitor().record_sqlite("playback_target", (time.perf_counter()-started)*1000.0,
                                                rows=len(rows), metadata={"source": "projection"})
        return result

    def continue_watching(self, limit=12):
        """Return bounded resumable episode rows ordered by their real playback activity."""
        started = time.perf_counter()
        try:
            limit = max(1, min(100, int(limit)))
        except (TypeError, ValueError):
            limit = 12
        completed_sql = """(
            e.watched=1 OR
            (e.duration>0 AND
             MIN(MAX(COALESCE(e.progress,0),0),e.duration) / e.duration >= 0.90)
        )"""
        with self._conn() as c:
            rows = c.execute(
                f"""
                SELECT
                    e.*,
                    a.title AS anime_title,
                    a.media_kind,
                    a.cover_cache,
                    a.cover_url
                FROM episodes e
                JOIN anime a ON a.id=e.anime_id
                WHERE e.missing=0
                  AND COALESCE(e.progress,0)>0
                  AND NOT {completed_sql}
                  AND (
                      a.media_kind='movie'
                      OR LOWER(COALESCE(e.episode_type,'regular'))
                         NOT IN ('special','ova','oad','ona','extra','movie')
                  )
                ORDER BY COALESCE(e.last_played_at,0) DESC, e.id DESC
                LIMIT ?
                """,
                (limit,),
            ).fetchall()
        result = [
            {
                "episode_id": row["id"],
                "anime_id": row["anime_id"],
                "anime_title": row["anime_title"],
                "cover": row["cover_cache"] or row["cover_url"],
                **dict(row),
            }
            for row in rows
        ]
        get_performance_monitor().record_sqlite("continue_watching", (time.perf_counter()-started)*1000.0, rows=len(result))
        return result

    def playback_history(self, limit=50):
        """Latest state for played local episodes; one durable row per episode."""
        started = time.perf_counter()
        with self._conn() as c:
            rows = c.execute("""SELECT e.*, a.title AS anime_title FROM episodes e
                JOIN anime a ON a.id=e.anime_id WHERE e.last_played_at IS NOT NULL
                ORDER BY e.last_played_at DESC LIMIT ?""", (limit,)).fetchall()
        result = [dict(row) for row in rows]
        get_performance_monitor().record_sqlite(
            "playback_history",
            (time.perf_counter()-started)*1000.0,
            rows=len(result),
            metadata={"limit": limit},
        )
        return result

    def account(self):
        with self._conn() as c: return {r["key"]: r["value"] for r in c.execute("SELECT key,value FROM account")}
    def save_account(self, values):
        with self._conn() as c: c.executemany("INSERT OR REPLACE INTO account(key,value) VALUES (?,?)", values.items())
    def clear_account(self):
        with self._conn() as c: c.execute("DELETE FROM account")
