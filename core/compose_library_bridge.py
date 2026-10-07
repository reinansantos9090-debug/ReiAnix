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

    @staticmethod
    def _valid_local_artwork_path(path: Any) -> str | None:
        """Return a cheap local-artwork candidate without decoding image pixels.

        Artwork validity is owned by ArtworkEngine when it persists/downloads the
        cache and by the Compose decoder when the item enters the viewport. The
        projection bridge must not open/verify every poster on every catalog
        publish because that turns an otherwise cheap snapshot refresh into an
        O(N) image-decoding pass.
        """
        value = str(path or "").strip()
        if not value or value.startswith(("content://", "http://", "https://")):
            return None
        try:
            file_path = Path(value)
            return (
                str(file_path)
                if file_path.is_file() and file_path.stat().st_size > 0
                else None
            )
        except (OSError, ValueError):
            return None
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
            # Give bursts of scanner/artwork/metadata callbacks a short coalescing
            # window. The newest revision remains authoritative, while a fast burst
            # no longer forces one full snapshot write per callback.
            revision = self._requested_revision
            await asyncio.sleep(0.06)
            if revision != self._requested_revision:
                continue
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
            projected_animes = []

            # Snapshot publication is projection-only. Do not resolve artwork for
            # the complete catalog here. ArtworkEngine remains the canonical artwork
            # owner and Compose decodes only the local/cache reference for items that
            # are actually composed in the viewport.
            #
            # The previous artwork-batch resolver used to run for every anime, movie and
            # episode on every snapshot publish. A scanner batch or a single artwork event
            # therefore fan out into a full-library artwork pass. Removing that eager
            # work establishes real lazy visual loading without a second cache.
            def hydrate_episode(episode):
                return dict(episode) if isinstance(episode, dict) else episode

            def hydrate_anime(item):
                return dict(item) if isinstance(item, dict) else item

            for item in catalog:
                projected = hydrate_anime(item)
                if callable(playback_target_method) and not isinstance(
                    item.get("current_episode"), dict
                ):
                    anime_id = item.get("id")
                    if anime_id is not None:
                        target = playback_target_method(anime_id)
                        if isinstance(target, dict):
                            projected = dict(projected)
                            projected["playback_target_episode"] = hydrate_episode(target)

                projected_animes.append(
                    self._project_anime(projected)
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
        onboarding_state = "checking"
        onboarding_message: str | None = None
        onboarding_error: str | None = None
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
                    onboarding_state = str(snapshot.get("onboardingState") or "checking").strip().lower()
                    onboarding_message = (
                        str(snapshot.get("onboardingMessage")).strip()
                        if snapshot.get("onboardingMessage") is not None
                        else None
                    )
                    onboarding_error = (
                        str(snapshot.get("onboardingError")).strip()
                        if snapshot.get("onboardingError") is not None
                        else None
                    )
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
            "onboardingState": onboarding_state,
            "onboardingMessage": onboarding_message,
            "onboardingError": onboarding_error,
        }

    @staticmethod
    def _source_state(folders: Any) -> str:
        rows = [row for row in (folders or []) if isinstance(row, dict)]
        if not rows:
            return "NOT_CONFIGURED"
        active = False
        unavailable = True
        terminal_unavailable = {"revoked", "unavailable", "error", "removed"}
        for row in rows:
            status = str(row.get("status") or "").strip().lower()
            authorization = str(row.get("authorization") or "").strip().lower()
            # Folder status is authoritative; stale authorization must not
            # promote a revoked SAF source back to AVAILABLE.
            if status in terminal_unavailable:
                continue
            if status in {"granted", "available", "active"} or authorization == "granted":
                active = True
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
            "user_tags": list(source.get("user_tags") or []),
            "personal_note": source.get("personal_note"),

            "artwork_local_path": (
                cls._valid_local_artwork_path((poster_artwork or {}).get("local_path"))
                or cls._valid_local_artwork_path(meta.get("cover_cache"))
            ),
            "artwork_external_url": (
                (poster_artwork or {}).get("external_url")
                or meta.get("cover_url")
            ),
            "backdrop_local_path": cls._valid_local_artwork_path(
                (backdrop_artwork or {}).get("local_path")
            ),
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
                "aliases": meta.get("aliases", source.get("aliases")),
                "romaji": meta.get("romaji"),
                "english": meta.get("english"),
                "native": meta.get("native"),
                "description": meta.get("description") or source.get("description"),
                "description_original": (
                    meta.get("description_original")
                    or source.get("description_original")
                ),
                "status": meta.get("status") or source.get("status"),
                "format": meta.get("format") or source.get("format"),
                "duration": meta.get("duration") if meta.get("duration") is not None else source.get("duration"),
                "studio": meta.get("studio") or source.get("studio"),
                "season": meta.get("season") or source.get("season"),
                "cover_cache": meta.get("cover_cache") or source.get("cover_cache"),
                "cover_url": meta.get("cover_url") or source.get("cover_url"),
                "banner_url": meta.get("banner_url") or source.get("banner_url"),
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

    @classmethod
    def project_library_page(
        cls,
        page_result: dict[str, Any] | None,
        *,
        generation: int = 0,
    ) -> dict[str, Any]:
        """Project one bounded Library page without touching the legacy full snapshot."""
        page_result = dict(page_result or {})
        items = page_result.get("items") or []
        return {
            "kind": "library_page",
            "generation": int(generation or 0),
            "page": int(page_result.get("page") or 0),
            "page_size": int(page_result.get("page_size") or 0),
            "total": int(page_result.get("total") or 0),
            "has_more": bool(page_result.get("has_more")),
            "items": [
                cls._project_anime(item)
                for item in items
                if isinstance(item, dict)
            ],
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
        payload: dict[str, Any] | None = None,
    ) -> None:
        if not self.enabled:
            return
        normalized_id = str(request_id or "").strip() or uuid.uuid4().hex
        result_payload = {
            "schemaVersion": self.SCHEMA_VERSION,
            "requestId": normalized_id,
            "action": str(action or "").strip(),
            "status": str(status or "").upper(),
            "timestamp": int(time.time() * 1000),
            "error": str(error)[:500] if error else None,
            "message": str(message)[:500] if message else None,
        }
        if isinstance(payload, dict):
            result_payload["payload"] = payload
        self._atomic_write_json(
            self.command_result_dir / f"command-{normalized_id}.json",
            result_payload,
        )

    @staticmethod
    async def run_command(
        command_handler: Callable[[str, dict[str, Any]], Any],
        action: str,
        payload: dict[str, Any],
    ) -> Any:
        """Keep command dispatch on the event loop while handlers own their IO."""
        return await command_handler(action, payload)
