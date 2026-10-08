"""Privacy-first technical diagnostics for ReiAnix.

This service observes the existing LibraryStore and runtime timeline. It does
not become a second catalog, settings backend or telemetry pipeline.
"""
from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
from typing import Any

from core.settings import SettingsDefaults
from core.performance import get_performance_monitor


class DiagnosticsService:
    BACKUP_FORMAT_VERSION = 1

    def __init__(self, store, *, timeline=None, app_version="0.2.1"):
        self.store = store
        self.timeline = timeline
        self.app_version = str(app_version)

    @staticmethod
    def _redact_path(value: Any) -> str:
        path = str(value or "")
        digest = hashlib.sha256(path.encode("utf-8")).hexdigest()[:12]
        if path.startswith("content://"):
            return f"content://<ref:{digest}>"
        suffix = Path(path).suffix.lower()
        return f"<path:{digest}>{suffix}"

    @staticmethod
    def _redact_text(value: Any) -> str:
        text = str(value or "")
        if len(text) > 240:
            text = text[:237] + "..."
        if "/" in text or "\\" in text:
            return "<redacted-path>"
        return text

    def _db_report(self) -> dict[str, Any]:
        tables = (
            "folders", "anime", "episodes", "episode_observations", "artwork",
            "associations", "pending_matches", "preferences", "genres",
            "genre_aliases", "anime_genres", "schema_migrations", "scan_runs",
        )
        with self.store._conn() as con:
            integrity_row = con.execute("PRAGMA integrity_check").fetchone()
            integrity = str(integrity_row[0] if integrity_row else "unknown")
            fk_rows = con.execute("PRAGMA foreign_key_check").fetchall()
            version_row = con.execute("SELECT MAX(version) FROM schema_migrations").fetchone()
            schema_version = int(version_row[0] or 0) if version_row else 0
            counts = {
                table: int(con.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] or 0)
                for table in tables
            }
        return {
            "schema_version": schema_version,
            "integrity_check": integrity,
            "foreign_key_ok": not fk_rows,
            "foreign_key_violations": len(fk_rows),
            "table_counts": counts,
        }

    def _library_report(self) -> dict[str, Any]:
        with self.store._conn() as con:
            anime = con.execute(
                """SELECT COUNT(*) AS total,
                          SUM(CASE WHEN favorite=1 THEN 1 ELSE 0 END) AS favorites,
                          SUM(CASE WHEN is_pinned=1 THEN 1 ELSE 0 END) AS pinned,
                          SUM(CASE WHEN NULLIF(TRIM(personal_note),'') IS NOT NULL THEN 1 ELSE 0 END) AS notes,
                          SUM(CASE WHEN anilist_id IS NOT NULL THEN 1 ELSE 0 END) AS anilist_matches
                   FROM anime"""
            ).fetchone()
            episodes = con.execute(
                """SELECT COUNT(*) AS total,
                          SUM(CASE WHEN missing=0 THEN 1 ELSE 0 END) AS available,
                          SUM(CASE WHEN missing=1 THEN 1 ELSE 0 END) AS missing,
                          SUM(CASE WHEN watched=1 THEN 1 ELSE 0 END) AS watched,
                          SUM(CASE WHEN progress>0 AND watched=0 THEN 1 ELSE 0 END) AS in_progress,
                          SUM(CASE WHEN last_played_at IS NOT NULL THEN 1 ELSE 0 END) AS history
                   FROM episodes"""
            ).fetchone()
            tags = con.execute("SELECT user_tags FROM anime").fetchall()
            genres = int(con.execute("SELECT COUNT(*) FROM genres").fetchone()[0] or 0)
        unique_tags = set()
        for row in tags:
            try:
                values = json.loads(row[0] or "[]")
            except (TypeError, json.JSONDecodeError):
                values = []
            if isinstance(values, list):
                unique_tags.update(str(value).casefold() for value in values)
        return {
            "anime": int(anime["total"] or 0),
            "episodes": int(episodes["total"] or 0),
            "available_files": int(episodes["available"] or 0),
            "missing_files": int(episodes["missing"] or 0),
            "watched": int(episodes["watched"] or 0),
            "in_progress": int(episodes["in_progress"] or 0),
            "history": int(episodes["history"] or 0),
            "favorites": int(anime["favorites"] or 0),
            "pinned": int(anime["pinned"] or 0),
            "notes": int(anime["notes"] or 0),
            "unique_tags": len(unique_tags),
            "genres": genres,
            "anilist_matches": int(anime["anilist_matches"] or 0),
        }

    def _orphan_report(self) -> dict[str, int]:
        queries = {
            "episodes_without_anime": """
                SELECT COUNT(*) FROM episodes e
                LEFT JOIN anime a ON a.id=e.anime_id
                WHERE a.id IS NULL
            """,
            "observations_without_episode": """
                SELECT COUNT(*) FROM episode_observations o
                LEFT JOIN episodes e ON e.id=o.episode_id
                WHERE e.id IS NULL
            """,
            "anime_genres_without_anime": """
                SELECT COUNT(*) FROM anime_genres ag
                LEFT JOIN anime a ON a.id=ag.anime_id
                WHERE a.id IS NULL
            """,
            "anime_genres_without_genre": """
                SELECT COUNT(*) FROM anime_genres ag
                LEFT JOIN genres g ON g.id=ag.genre_id
                WHERE g.id IS NULL
            """,
            "genre_aliases_without_genre": """
                SELECT COUNT(*) FROM genre_aliases ga
                LEFT JOIN genres g ON g.id=ga.genre_id
                WHERE g.id IS NULL
            """,
            "artwork_unknown_entity": """
                SELECT COUNT(*) FROM artwork
                WHERE entity_type NOT IN ('anime','movie','episode','season','special')
            """,
            "artwork_missing_anime": """
                SELECT COUNT(*) FROM artwork aw
                WHERE aw.entity_type IN ('anime','movie')
                  AND NOT EXISTS (
                      SELECT 1 FROM anime a WHERE a.id=CAST(aw.entity_id AS INTEGER)
                  )
            """,
            "artwork_missing_episode": """
                SELECT COUNT(*) FROM artwork aw
                WHERE aw.entity_type='episode'
                  AND NOT EXISTS (
                      SELECT 1 FROM episodes e WHERE e.id=CAST(aw.entity_id AS INTEGER)
                  )
            """,
        }
        with self.store._conn() as con:
            return {
                name: int(con.execute(sql).fetchone()[0] or 0)
                for name, sql in queries.items()
            }

    def _duplicate_report(self) -> dict[str, int]:
        with self.store._conn() as con:
            media = int(con.execute(
                """SELECT COUNT(*) FROM (
                       SELECT media_identity
                       FROM episodes
                       WHERE media_identity IS NOT NULL AND TRIM(media_identity)!=''
                       GROUP BY media_identity
                       HAVING COUNT(*)>1
                   )"""
            ).fetchone()[0] or 0)
            return {"media_identity": media}

    def _file_report(self) -> dict[str, int]:
        from urllib.parse import urlparse
        missing = existing = unverified = 0
        with self.store._conn() as con:
            paths = [str(row[0] or "") for row in con.execute("SELECT path FROM episodes")]
        for path in paths:
            if not path:
                missing += 1
                continue
            scheme = urlparse(path).scheme.casefold()
            if scheme in {"content", "file", "http", "https"}:
                unverified += 1
            elif os.path.isfile(path):
                existing += 1
            else:
                missing += 1
        return {
            "existing": existing,
            "missing": missing,
            "unverified_native_or_uri": unverified,
        }

    def _artwork_report(self) -> dict[str, Any]:
        with self.store._conn() as con:
            row = con.execute(
                """SELECT COUNT(*) AS total,
                          SUM(CASE WHEN manual=1 THEN 1 ELSE 0 END) AS manual,
                          SUM(CASE WHEN source='cache' THEN 1 ELSE 0 END) AS cached,
                          SUM(CASE WHEN status IN ('queued','downloading') THEN 1 ELSE 0 END) AS pending
                   FROM artwork"""
            ).fetchone()
            refs = [str(item[0]) for item in con.execute(
                "SELECT local_path FROM artwork WHERE local_path IS NOT NULL AND TRIM(local_path)!=''"
            )]
        missing_refs = sum(1 for path in refs if not os.path.isfile(path))
        cache_files = cache_bytes = 0
        try:
            for item in Path(self.store.cache_dir).rglob("*"):
                if item.is_file():
                    cache_files += 1
                    try:
                        cache_bytes += item.stat().st_size
                    except OSError:
                        pass
        except OSError:
            pass
        return {
            "references": int(row["total"] or 0),
            "manual": int(row["manual"] or 0),
            "cached": int(row["cached"] or 0),
            "pending": int(row["pending"] or 0),
            "missing_local_references": missing_refs,
            "cache_files": cache_files,
            "cache_bytes": cache_bytes,
        }

    def _anilist_report(self) -> dict[str, int]:
        with self.store._conn() as con:
            row = con.execute(
                """SELECT
                       SUM(CASE WHEN anilist_id IS NOT NULL AND anilist_match_status='matched' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN anilist_id IS NULL OR anilist_match_status='unmatched' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN anilist_match_status='ambiguous' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN anilist_match_status IN ('error','network_error','rate_limited') THEN 1 ELSE 0 END),
                       SUM(CASE WHEN metadata_updated_at IS NOT NULL THEN 1 ELSE 0 END)
                   FROM anime"""
            ).fetchone()
        return {
            "matched": int(row[0] or 0),
            "unmatched": int(row[1] or 0),
            "ambiguous": int(row[2] or 0),
            "failed": int(row[3] or 0),
            "cached_metadata": int(row[4] or 0),
        }

    def _consumption_report(self) -> dict[str, int]:
        with self.store._conn() as con:
            invalid = int(con.execute(
                """SELECT COUNT(*) FROM episodes
                   WHERE progress < 0 OR duration < 0
                      OR (duration > 0 AND progress > duration + 0.001)"""
            ).fetchone()[0] or 0)
            completed = int(con.execute("SELECT COUNT(*) FROM episodes WHERE watched=1").fetchone()[0] or 0)
        return {"invalid_progress_rows": invalid, "completed_rows": completed}

    def _player_report(self) -> dict[str, Any]:
        events = self.timeline.snapshot() if self.timeline is not None else []
        errors = []
        for event in events:
            name = str(event.get("name") or "")
            if "PLAYER" not in name.upper() or not event.get("error"):
                continue
            errors.append({
                "name": name,
                "error": self._redact_text(event.get("error")),
                "timestamp_ms": int(event.get("timestamp_ms") or 0),
            })
        return {"last_error": errors[-1] if errors else None, "recent_errors": errors[-5:]}

    def _storage_report(self, storage_snapshot=None, scan_snapshot=None) -> dict[str, Any]:
        raw = {}
        if storage_snapshot is not None:
            try:
                raw = storage_snapshot.as_mapping()
            except AttributeError:
                raw = dict(storage_snapshot) if isinstance(storage_snapshot, dict) else {}
        safe = {}
        for key, value in raw.items():
            normalized = str(key)
            if normalized.casefold() in {"safroots", "saf_roots"} and isinstance(value, (list, tuple)):
                safe[normalized] = [self._redact_path(item) for item in value]
                safe[f"{normalized}_count"] = len(value)
                continue
            if normalized.casefold() in {"removablevolumes", "removable_volumes"} and isinstance(value, (list, tuple)):
                safe[normalized] = [
                    {"state": self._redact_text(item.get("state")) if isinstance(item, dict) else None}
                    for item in value
                ]
                safe[f"{normalized}_count"] = len(value)
                continue
            if isinstance(value, str):
                safe[normalized] = self._redact_text(value)
            elif isinstance(value, (int, float, bool)) or value is None:
                safe[normalized] = value
            elif isinstance(value, (list, tuple)):
                safe[normalized] = [self._redact_text(item) if isinstance(item, str) else item for item in value]
            else:
                safe[normalized] = self._redact_text(value)
        scan = dict(scan_snapshot or {})
        for key in ("volume", "volumeId", "scope", "scope_ref"):
            if key in scan and scan[key]:
                scan[key] = self._redact_text(scan[key])
        return {"capabilities": safe, "scan": scan}

    def report(self, *, storage_snapshot=None, scan_snapshot=None) -> dict[str, Any]:
        database = self._db_report()
        library = self._library_report()
        files = self._file_report()
        orphans = self._orphan_report()
        duplicates = self._duplicate_report()
        consumption = self._consumption_report()
        artwork = self._artwork_report()
        anilist = self._anilist_report()
        player = self._player_report()
        overall_ok = (
            database["integrity_check"].casefold() == "ok"
            and database["foreign_key_ok"]
            and not any(orphans.values())
            and not any(duplicates.values())
            and consumption["invalid_progress_rows"] == 0
        )
        return {
            "format": "rei-flix-diagnostic-report-v1",
            "report_version": 1,
            "app": "ReiAnix",
            "app_version": self.app_version,
            "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
            "overall": "OK" if overall_ok else "ATTENTION_REQUIRED",
            "backup_format_version": self.BACKUP_FORMAT_VERSION,
            "database": database,
            "library": library,
            "files": files,
            "duplicates": duplicates,
            "orphans": orphans,
            "consumption": consumption,
            "artwork": artwork,
            "anilist": anilist,
            "player": player,
            "performance": get_performance_monitor().snapshot(),
            "storage": self._storage_report(storage_snapshot, scan_snapshot),
            "privacy": {
                "secrets_exported": False,
                "paths_redacted": True,
                "authentication_state_exported": False,
                "device_identifiers_exported": False,
            },
        }

    @staticmethod
    def to_json(report: dict[str, Any]) -> str:
        return json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"

    @staticmethod
    def to_text(report: dict[str, Any]) -> str:
        database = report.get("database") or {}
        library = report.get("library") or {}
        files = report.get("files") or {}
        artwork = report.get("artwork") or {}
        anilist = report.get("anilist") or {}
        return "\n".join([
            "ReiAnix DIAGNOSTIC REPORT",
            f"Overall: {report.get('overall')}",
            f"App: {report.get('app')} {report.get('app_version')}",
            f"Generated: {report.get('generated_at')}",
            f"Backup format: v{report.get('backup_format_version')}",
            f"Database: schema={database.get('schema_version')} integrity_check={database.get('integrity_check')} foreign_keys={database.get('foreign_key_ok')}",
            f"Library: anime={library.get('anime')} episodes={library.get('episodes')} available={library.get('available_files')} missing={library.get('missing_files')}",
            f"Consumption: watched={library.get('watched')} in_progress={library.get('in_progress')} history={library.get('history')}",
            f"Favorites/pinned/notes: {library.get('favorites')}/{library.get('pinned')}/{library.get('notes')}",
            f"Files: existing={files.get('existing')} missing={files.get('missing')} unverified={files.get('unverified_native_or_uri')}",
            f"Artwork: refs={artwork.get('references')} manual={artwork.get('manual')} cache_files={artwork.get('cache_files')} cache_bytes={artwork.get('cache_bytes')}",
            f"AniList: matched={anilist.get('matched')} unmatched={anilist.get('unmatched')} ambiguous={anilist.get('ambiguous')} failed={anilist.get('failed')}",
            f"Duplicates: {report.get('duplicates')}",
            f"Orphans: {report.get('orphans')}",
            f"Player last error: {((report.get('player') or {}).get('last_error') or {}).get('error') or 'none'}",
            "Privacy: secrets/authentication/device identifiers are excluded; diagnostic paths are redacted.",
            "",
        ])

    def diagnostic_bytes(self, *, storage_snapshot=None, scan_snapshot=None, text=False) -> bytes:
        report = self.report(storage_snapshot=storage_snapshot, scan_snapshot=scan_snapshot)
        payload = self.to_text(report) if text else self.to_json(report)
        return payload.encode("utf-8")
