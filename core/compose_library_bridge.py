"""Bridge between the real Python/SQLite library and the native Compose UI.

SQLite and LibraryService remain the source of truth. This bridge only publishes
a compact derived projection for Compose and accepts narrow commands from the
native UI. Player launch is delegated back to the existing AndroidBridge so
Media3/player/session rules are not duplicated in Kotlin.
"""
from __future__ import annotations

import asyncio
import json
import os
import time
import uuid
from pathlib import Path
from typing import Any, Callable

from core.storage_access import StorageCapabilities, saf_source_identity


class ComposeLibraryBridge:
    SNAPSHOT_DIR_NAME = "reianix-compose"
    SNAPSHOT_FILE_NAME = "library.json"
    COMMAND_RESULT_DIR_NAME = "command-results"
    SCHEMA_VERSION = 1

    def __init__(
        self,
        data_dir: str,
        library,
        store,
        *,
        enabled: bool = True,
        scan_state_provider: Callable[[], dict[str, Any]] | None = None,
        storage_state_provider: Callable[[], StorageCapabilities | dict[str, Any]] | None = None,
    ):
        self.data_dir = Path(data_dir)
        self.library = library
        self.store = store
        self.enabled = bool(enabled)
        self.snapshot_dir = self.data_dir / self.SNAPSHOT_DIR_NAME
        self.snapshot_path = self.snapshot_dir / self.SNAPSHOT_FILE_NAME
        self.command_result_dir = self.snapshot_dir / self.COMMAND_RESULT_DIR_NAME
        self._requested_revision = 0
        self._publish_task: asyncio.Task[Any] | None = None
        self._last_published_revision = 0
        self._pending_reason_text = "unknown"
        self._scan_state_provider = scan_state_provider
        self._storage_state_provider = storage_state_provider
        if self.enabled:
            self.snapshot_dir.mkdir(parents=True, exist_ok=True)
            self.command_result_dir.mkdir(parents=True, exist_ok=True)

    def set_scan_state_provider(
        self,
        provider: Callable[[], dict[str, Any]] | None,
    ) -> None:
        self._scan_state_provider = provider

    def set_storage_state_provider(
        self,
        provider: Callable[[], StorageCapabilities | dict[str, Any]] | None,
    ) -> None:
        self._storage_state_provider = provider

    def request_publish(self, reason: str = "unknown") -> None:
        """Coalesce projection requests onto one cancellable asyncio worker."""
        if not self.enabled:
            return
        self._requested_revision += 1
        self._pending_reason_text = str(reason or "unknown")
        if self._publish_task is None or self._publish_task.done():
            self._publish_task = asyncio.create_task(self._publish_loop())

    async def wait_for_idle(self) -> None:
        task = self._publish_task
        if task is not None:
            await task

    async def _publish_loop(self) -> None:
        while True:
            revision = self._requested_revision
            reason = self._pending_reason()
            await asyncio.to_thread(self._build_and_write_snapshot, revision, reason)
            self._last_published_revision = revision
            if revision == self._requested_revision:
                return

    def _pending_reason(self) -> str:
        return self._pending_reason_text

    def _build_and_write_snapshot(self, revision: int, reason: str) -> None:
        generated_at = int(time.time() * 1000)
        try:
            catalog = self.library.catalog()
            folders = self.store.folders()
            source_state = self._source_state(folders)
            scan_snapshot = self._scan_snapshot()
            storage_snapshot = self._storage_snapshot(folders)
            continue_method = getattr(self.library, "continue_watching", None)
            continue_rows = continue_method(limit=12) if callable(continue_method) else []
            status = "READY" if catalog else "EMPTY"

            # Details must consume the existing canonical playback_target()
            # policy. Normal catalog rows already expose current_episode, so
            # only the special-only fallback needs the extra canonical lookup.
            playback_target_method = getattr(self.library, "playback_target", None)
            artwork_batch_method = getattr(self.library, "resolve_artwork_batch", None)
            projected_animes = []

            # Details needs both poster and backdrop from the existing ArtworkEngine.
            # Resolution is batched here; Compose still consumes only the resulting
            # local/cache references and never performs network work in composition.
            artwork_by_entity = {"anime": {}, "movie": {}}
            if callable(artwork_batch_method):
                for entity_type in ("anime", "movie"):
                    entity_ids = [
                        item.get("id")
                        for item in catalog
                        if item.get("id") is not None
                        and (
                            entity_type == "movie"
                            and str(item.get("media_kind") or item.get("meta", {}).get("media_kind") or "").strip().lower() == "movie"
                            or entity_type == "anime"
                            and str(item.get("media_kind") or item.get("meta", {}).get("media_kind") or "").strip().lower() != "movie"
                        )
                    ]
                    entity_ids = list(dict.fromkeys(entity_ids))
                    if entity_ids:
                        poster_rows = artwork_batch_method(entity_type, entity_ids, ("poster",))
                        backdrop_rows = artwork_batch_method(entity_type, entity_ids, ("backdrop",))
                        for entity_id, row in (poster_rows or {}).items():
                            artwork_by_entity[entity_type].setdefault(str(entity_id), {})["poster"] = row
                        for entity_id, row in (backdrop_rows or {}).items():
                            artwork_by_entity[entity_type].setdefault(str(entity_id), {})["backdrop"] = row

            for item in catalog:
                projected = item
                if callable(playback_target_method) and not isinstance(
                    item.get("current_episode"), dict
                ):
                    anime_id = item.get("id")
                    if anime_id is not None:
                        target = playback_target_method(anime_id)
                        if isinstance(target, dict):
                            projected = dict(item)
                            projected["playback_target_episode"] = target

                media_kind = str(projected.get("media_kind") or "").strip().lower()
                entity_type = "movie" if media_kind == "movie" else "anime"
                artwork_rows = artwork_by_entity[entity_type].get(str(projected.get("id")), {})
                projected_animes.append(
                    self._project_anime(
                        projected,
                        poster_artwork=artwork_rows.get("poster"),
                        backdrop_artwork=artwork_rows.get("backdrop"),
                    )
                )

            payload = {
                "schemaVersion": self.SCHEMA_VERSION,
                "revision": int(revision),
                "generatedAt": generated_at,
                "reason": str(reason),
                "status": status,
                "sourceState": source_state,
                "sourceAvailable": source_state == "AVAILABLE",
                "scanInProgress": scan_snapshot["in_progress"],
                "scanState": scan_snapshot["state"],
                "storage": storage_snapshot,
                "error": None,
                "animes": projected_animes,
                "continue_watching": [
                    self._project_continue_watching(item)
                    for item in (continue_rows or [])
                    if isinstance(item, dict)
                ],
            }
        except Exception as exc:
            payload = {
                "schemaVersion": self.SCHEMA_VERSION,
                "revision": int(revision),
                "generatedAt": generated_at,
                "reason": str(reason),
                "status": "ERROR",
                "sourceState": "UNKNOWN",
                "sourceAvailable": False,
                "scanInProgress": False,
                "scanState": "UNKNOWN",
                "storage": self._storage_snapshot([]),
                "error": str(exc)[:500],
                "animes": [],
                "continue_watching": [],
            }
        self._atomic_write_json(self.snapshot_path, payload)

    def _scan_snapshot(self) -> dict[str, Any]:
        provider = self._scan_state_provider
        if not callable(provider):
            return {"in_progress": False, "state": "IDLE"}
        try:
            snapshot = provider() or {}
            state = str(snapshot.get("state") or "IDLE").strip().upper()
        except Exception:
            return {"in_progress": False, "state": "UNKNOWN"}

        return {
            "in_progress": state in {"CHECKING", "SCANNING", "WAITING_FOR_MEDIASTORE"},
            "state": state,
        }

    def _storage_snapshot(self, folders: Any) -> dict[str, Any]:
        provider = self._storage_state_provider
        capabilities: dict[str, Any] = {}
        saf_selection_pending = False
        if callable(provider):
            try:
                snapshot = provider()
                if isinstance(snapshot, StorageCapabilities):
                    capabilities = snapshot.as_mapping()
                elif isinstance(snapshot, dict):
                    raw_capabilities = snapshot.get("capabilities")
                    if isinstance(raw_capabilities, StorageCapabilities):
                        capabilities = raw_capabilities.as_mapping()
                    elif isinstance(raw_capabilities, dict):
                        capabilities = dict(raw_capabilities)
                    else:
                        capabilities = dict(snapshot)
                    saf_selection_pending = bool(snapshot.get("safSelectionPending"))
            except Exception:
                capabilities = {}

        roots = capabilities.get("safRoots") or []
        capabilities["safRoots"] = list(dict.fromkeys(
            str(value).strip() for value in roots if str(value).strip()
        ))
        capabilities["safRootIdentities"] = sorted({
            identity
            for identity in (saf_source_identity(value) for value in capabilities["safRoots"])
            if identity
        })

        sources = []
        for folder in folders or []:
            if not isinstance(folder, dict):
                continue
            reference = str(folder.get("path") or "").strip()
            name = str(folder.get("name") or reference).strip()
            kind = str(folder.get("kind") or "").strip().lower()
            if not reference and not name:
                continue
            sources.append({
                "reference": reference,
                "name": name,
                "kind": kind,
                "authorization": str(folder.get("authorization") or "").strip().lower(),
                "status": str(folder.get("status") or "").strip().lower(),
                "saf_identity": str(folder.get("saf_identity") or "").strip() or None,
                "saf_volume_id": str(folder.get("saf_volume_id") or "").strip() or None,
                "saf_document_id": str(folder.get("saf_document_id") or "").strip() or None,
            })
        sources.sort(key=lambda item: (
            str(item.get("saf_identity") or item.get("reference") or item.get("name") or "").lower(),
            str(item.get("name") or "").lower(),
        ))
        return {
            "capabilities": capabilities,
            "configuredSources": sources,
            "safSelectionPending": saf_selection_pending,
        }

    @staticmethod
    def _source_state(folders: Any) -> str:
        rows = [row for row in (folders or []) if isinstance(row, dict)]
        if not rows:
            return "NOT_CONFIGURED"
        active = False
        unavailable = True
        for row in rows:
            status = str(row.get("status") or "").strip().lower()
            authorization = str(row.get("authorization") or "").strip().lower()
            if status in {"granted", "available", "active"} or authorization == "granted":
                active = True
            if status not in {"revoked", "unavailable", "error", "removed"}:
                unavailable = False
        if active:
            return "AVAILABLE"
        if unavailable:
            return "UNAVAILABLE"
        return "UNKNOWN"

    @classmethod
    def _project_anime(
        cls,
        source: dict[str, Any],
        *,
        poster_artwork: dict[str, Any] | None = None,
        backdrop_artwork: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        meta = source.get("meta") if isinstance(source.get("meta"), dict) else {}
        return {
            "id": source.get("id"),
            "main_title": source.get("main_title") or source.get("title"),
            "lookup_title": source.get("lookup_title"),
            "favorite": source.get("favorite"),
            "is_pinned": source.get("is_pinned"),
            "last_played_at": source.get("last_played_at"),
            "media_kind": source.get("media_kind") or meta.get("media_kind"),
            "year": source.get("year"),
            "playback_target_episode_id": (
                (source.get("playback_target_episode") or {}).get("id")
                if isinstance(source.get("playback_target_episode"), dict)
                else (
                    (source.get("current_episode") or {}).get("id")
                    if isinstance(source.get("current_episode"), dict)
                    else None
                )
            ),
            "genres": list(source.get("genres") or []),
            "genre_ids": list(source.get("genre_ids") or []),
            "artwork_local_path": (
                (poster_artwork or {}).get("local_path")
                or meta.get("cover_cache")
            ),
            "artwork_external_url": (
                (poster_artwork or {}).get("external_url")
                or meta.get("cover_url")
            ),
            "backdrop_local_path": (backdrop_artwork or {}).get("local_path"),
            "backdrop_external_url": (
                (backdrop_artwork or {}).get("external_url")
                or meta.get("banner_url")
            ),
            "meta": {
                "added_at": meta.get("added_at", source.get("added_at")),
                "year": meta.get("year", source.get("year")),
                "metadata_status": meta.get("metadata_status") or source.get("metadata_status"),
                "score": meta.get("score", source.get("score")),
                "title": meta.get("title") or source.get("main_title") or source.get("title"),
                "romaji": meta.get("romaji"),
                "english": meta.get("english"),
                "native": meta.get("native"),
                "description": meta.get("description"),
                "status": meta.get("status"),
                "format": meta.get("format"),
                "duration": meta.get("duration"),
                "studio": meta.get("studio"),
                "season": meta.get("season"),
                "cover_cache": meta.get("cover_cache"),
                "cover_url": meta.get("cover_url"),
                "banner_url": meta.get("banner_url"),
            },
            "seasons": [
                {
                    "season": season.get("season"),
                    "season_name": season.get("season_name"),
                    "episodes": [cls._project_episode(ep) for ep in (season.get("episodes") or [])],
                }
                for season in (source.get("seasons") or [])
                if isinstance(season, dict)
            ],
            "specials": [
                {
                    "season": group.get("season"),
                    "season_name": group.get("season_name"),
                    "episodes": [cls._project_episode(ep) for ep in (group.get("episodes") or [])],
                }
                for group in (source.get("specials") or [])
                if isinstance(group, dict)
            ],
            "media_files": [cls._project_episode(ep) for ep in (source.get("media_files") or [])],
        }

    @staticmethod
    def _project_episode(source: dict[str, Any]) -> dict[str, Any]:
        artwork = source.get("artwork") if isinstance(source.get("artwork"), dict) else {}
        return {
            "id": source.get("id"),
            "anime_id": source.get("anime_id"),
            "season": source.get("season"),
            "number": source.get("number"),
            "episode_title": source.get("episode_title") or source.get("title"),
            "file_name": source.get("file_name") or source.get("title"),
            "path": source.get("path"),
            "uri": source.get("uri"),
            "media_identity": source.get("media_identity"),
            "availability_state": source.get("availability_state"),
            "missing": source.get("missing"),
            "progress": source.get("progress"),
            "duration": source.get("duration"),
            "last_played_at": source.get("last_played_at"),
            "modified_at": source.get("modified_at"),
            "file_size": source.get("file_size"),
            "watched": source.get("watched"),
            "consumption_state": source.get("consumption_state"),
            "artwork_local_path": (
                source.get("artwork_local_path")
                or artwork.get("localPath")
                or artwork.get("local_path")
            ),
            "artwork_external_url": (
                source.get("artwork_external_url")
                or artwork.get("externalUrl")
                or artwork.get("external_url")
            ),
            "cover_cache": source.get("cover_cache"),
            "cover_url": source.get("cover_url"),
            "banner_url": source.get("banner_url"),
        }

    @staticmethod
    def _project_continue_watching(source: dict[str, Any]) -> dict[str, Any]:
        return {
            "episode_id": source.get("episode_id") or source.get("id"),
            "anime_id": source.get("anime_id"),
            "anime_title": source.get("anime_title"),
            "season": source.get("season"),
            "number": source.get("number"),
            "episode_title": source.get("episode_title") or source.get("title"),
            "file_name": source.get("file_name") or source.get("title"),
            "path": source.get("path"),
            "uri": source.get("uri"),
            "media_identity": source.get("media_identity"),
            "availability_state": source.get("availability_state"),
            "progress": source.get("progress"),
            "duration": source.get("duration"),
            "watched": source.get("watched"),
            "consumption_state": source.get("consumption_state"),
            "artwork_local_path": source.get("cover_cache"),
            "artwork_external_url": source.get("cover_url"),
        }

    @staticmethod
    def _atomic_write_json(path: Path, payload: dict[str, Any]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
        temporary.write_text(
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
            encoding="utf-8",
        )
        os.replace(temporary, path)

    def write_command_result(
        self,
        request_id: str | None,
        action: str,
        status: str,
        *,
        error: str | None = None,
        message: str | None = None,
    ) -> None:
        if not self.enabled:
            return
        normalized_id = str(request_id or "").strip() or uuid.uuid4().hex
        payload = {
            "schemaVersion": self.SCHEMA_VERSION,
            "requestId": normalized_id,
            "action": str(action or "").strip(),
            "status": str(status or "").upper(),
            "timestamp": int(time.time() * 1000),
            "error": str(error)[:500] if error else None,
            "message": str(message)[:500] if message else None,
        }
        self._atomic_write_json(
            self.command_result_dir / f"command-{normalized_id}.json",
            payload,
        )

    @staticmethod
    async def run_command(
        command_handler: Callable[[str, dict[str, Any]], Any],
        action: str,
        payload: dict[str, Any],
    ) -> Any:
        """Keep command dispatch on the event loop while handlers own their IO."""
        return await command_handler(action, payload)
