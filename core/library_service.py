"""Real recursive scanner for references that the Python process can read."""
from __future__ import annotations
import os
import time
import json
import logging
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import unquote, urlparse
from pathlib import Path
from dataclasses import dataclass, field
from core.anilist import AniListClient
from core.consumption import consumption_state
from core.artwork import ArtworkEngine
from core.library_parser import VIDEO_EXTENSIONS, parse_video_path
from core.media_identity import identity_from_document
from core.storage_access import saf_source_identity
from core.organizer_ai import AnimeOrganizer, MatchContext
from core.search_engine import LibrarySearchEngine, normalize_text
from core.genre_classifier import GenreClassifier
from core.genre_registry import GenreRegistry
from core.library_discovery import duration_anomalies, format_duration, marathon_plan, timeline_groups
from core.collector_journey import build_collector_journey, set_active_title

logger = logging.getLogger(__name__)

@dataclass
class ScanResult:
    catalog: list
    folders: int = 0
    files: int = 0
    videos: int = 0
    animes: int = 0
    episodes: int = 0
    errors: list[str] = field(default_factory=list)
    new: int = 0
    updated: int = 0
    unchanged: int = 0
    ignored: int = 0
    duplicates: int = 0
    unknown: int = 0
    reconciled: int = 0
    scan_id: str | None = None
    status: str = "completed"
    nomedia_directories: int = 0
    nomedia_files: int = 0

    def message(self):
        if self.videos == 0:
            return "Nenhum vídeo encontrado nas fontes autorizadas."
        return (f"Biblioteca atualizada: {self.episodes} mídias — "
                f"{self.new} novas, {self.updated} atualizadas, {self.unchanged} inalteradas.")

class LibraryService:
    METADATA_CACHE_SECONDS = 30 * 24 * 60 * 60
    COVER_RETRY_SECONDS = 6 * 60 * 60
    REQUEST_DEDUPE_SECONDS = 5

    def __init__(self, store, settings=None):
        self.store = store
        self.settings = settings
        self.anilist = AniListClient(store.cache_dir)
        self.artwork = ArtworkEngine(store)
        self.genre_registry = GenreRegistry(store)
        self._scan_lock = threading.Lock()
        self._metadata_lock = threading.RLock()
        self._translation_executor = ThreadPoolExecutor(
            max_workers=2,
            thread_name_prefix="reianix-translation",
        )
        self._translation_pending: dict[str, object] = {}
        self._translation_pending_lock = threading.RLock()
        self._translation_pending_limit = 64
        self._diagnostic_recorder = None
        self._metadata_change_listener = None
        if settings is not None:
            self.configure_settings(settings)

    def _setting(self, key, default):
        if self.settings is None:
            return default
        try:
            return self.settings.get(key)
        except Exception:
            logger.exception("Could not read setting %s", key)
            return default

    def configure_settings(self, settings):
        """Apply advanced runtime settings to existing service owners only."""
        self.settings = settings
        try:
            limit_mb = int(settings.get("artwork.cache_limit_mb"))
            self.artwork.cache_limit_bytes = max(1, limit_mb) * 1024 * 1024
            self.artwork._evict_if_needed()
        except Exception:
            logger.exception("Could not apply artwork cache settings")
        return True

    def _sync_genres(self, anime_id, metadata, *, source=None):
        if not anime_id:
            return []
        names = metadata.get("genres") if isinstance(metadata, dict) else []
        if isinstance(names, str):
            try:
                names = json.loads(names or "[]")
            except (TypeError, json.JSONDecodeError):
                names = []
        return self.genre_registry.sync_anime(anime_id, names or [],
                                              source=source or metadata.get("metadata_source") or "local",
                                              replace_source=True)

    @staticmethod
    def _metadata_is_materialized(cached):
        """Return whether metadata is durably available without a new AniList call."""
        if not cached:
            return False
        source = str(cached.get("metadata_source") or "").strip().casefold()
        status = str(cached.get("metadata_status") or "").strip().casefold()
        if source == "manual" or status == "manual":
            return True
        anilist_id = str(cached.get("anilist_id") or "").strip()
        try:
            fetched_at = float(cached.get("metadata_fetched_at") or 0)
        except (TypeError, ValueError):
            fetched_at = 0
        return (
            source == "anilist"
            and bool(anilist_id)
            and fetched_at > 0
            and status not in {"unresolved", "error", "ambiguous", "refreshing"}
        )

    def _cached_metadata_is_current(self, cached, associated_id):
        if self._metadata_is_materialized(cached):
            # TTL is informational. Automatic library rendering never turns an
            # already-materialized row back into a remote fetch.
            return True
        if not cached:
            return False
        if associated_id and cached.get("anilist_id") != associated_id:
            return False

        updated_at = cached.get("metadata_updated_at")
        if not updated_at:
            return False
        try:
            age = time.time() - float(updated_at)
        except (TypeError, ValueError):
            return False

        # A previously expected cover has its own short retry window.
        # This check must happen before the long metadata TTL: metadata can
        # still be fresh while a missing cover is already due for retry.
        cover_cache = (cached.get("cover_cache") or "").strip()
        if cover_cache and not Path(cover_cache).is_file():
            return age < self.COVER_RETRY_SECONDS

        # An empty/missing cover cache is valid metadata state. In that case,
        # only the normal metadata TTL controls freshness.
        return age < self.METADATA_CACHE_SECONDS

    def _identify(self, lookup_title, display_title, on_status=lambda _ : None, *, allow_network=True):
        """Return local/cached metadata without making the library depend on network."""
        allow_network = bool(allow_network and self._setting("metadata.anilist_enabled", True))
        cached = self.store.anime_metadata(lookup_title)
        cached_id = cached.get("anilist_id") if cached else None
        match_state = self.store.anilist_match(lookup_title) or {}
        associated_id = match_state.get("anilist_id") or self.store.association(lookup_title)
        if cached:
            if self._metadata_is_materialized(cached):
                self._record_diagnostic(
                    "METADATA_MATERIALIZATION_SKIPPED",
                    anime_id=cached.get("id"),
                    anilist_id=cached.get("anilist_id"),
                    lookup_title=lookup_title,
                    reason="already_materialized",
                )
                return self._ensure_cached_description_pt_br(
                    lookup_title,
                    cached,
                    local_anime_id=cached.get("id"),
                    schedule=True,
                )
            if associated_id and cached.get("anilist_id") != associated_id:
                # An existing materialized association remains authoritative.
                if allow_network:
                    return self.refresh_metadata(lookup_title, display_title, force=True)
            if self._cached_metadata_is_current(cached, associated_id or cached.get("anilist_id")):
                return cached
            if not allow_network:
                return cached
        if not allow_network:
            genres = GenreClassifier.classify(display_title)
            return cached or {
                "title": display_title,
                "genres": json.dumps(genres, ensure_ascii=False),
                "metadata_source": "classifier" if genres else "local",
                "metadata_status": "unresolved",
                "metadata_confidence": "low",
            }
        if not self._setting("metadata.auto_match", True) and not associated_id and not cached_id:
            genres = GenreClassifier.classify(display_title)
            return cached or {
                "title": display_title,
                "genres": json.dumps(genres, ensure_ascii=False),
                "metadata_source": "classifier" if genres else "local",
                "metadata_status": "unresolved",
                "metadata_confidence": "low",
            }
        return self.refresh_metadata(lookup_title, display_title, force=False)

    def metadata_state(self, metadata):
        """Expose a stable UI state without changing persisted library data."""
        if not metadata:
            return "unresolved"
        status = str(metadata.get("metadata_status") or "unresolved").casefold()
        if status == "manual":
            return "manual"
        if status in {"ambiguous", "unresolved", "error"}:
            return status
        updated = metadata.get("metadata_updated_at")
        try:
            stale = not updated or (time.time() - float(updated)) >= self.METADATA_CACHE_SECONDS
        except (TypeError, ValueError):
            stale = True
        return "stale" if stale else "available"

    def set_diagnostic_recorder(self, recorder) -> None:
        self._diagnostic_recorder = recorder if callable(recorder) else None
        self.anilist.set_diagnostic_recorder(self._record_diagnostic)

    def _record_diagnostic(self, name, **kwargs) -> None:
        recorder = self._diagnostic_recorder
        if not callable(recorder):
            return
        try:
            recorder(name, **kwargs)
        except TypeError:
            logger.debug("Diagnostic recorder rejected event %s", name, exc_info=True)

    def set_metadata_change_listener(self, listener) -> None:
        """Receive canonical metadata changes from background localization work."""
        self._metadata_change_listener = listener if callable(listener) else None

    def _notify_metadata_change(self, event_name, *, anime_id=None, lookup_title=None, source_language=None) -> None:
        listener = self._metadata_change_listener
        if not callable(listener):
            return
        try:
            listener(
                event_name,
                {
                    "anime_id": anime_id,
                    "lookup_title": lookup_title,
                    "source_language": source_language,
                },
            )
        except Exception:
            logger.exception("Metadata change listener failed for %s", event_name)

    def _persist_localized_description(
        self,
        lookup_title,
        original,
        localized,
        *,
        local_anime_id=None,
        source_language=None,
        from_cache=False,
    ):
        original = self.anilist.normalize_description(original)
        localized = self.anilist.normalize_description(localized)
        if not original or not self.anilist._is_valid_translation(original, localized):
            return False
        current = (
            self.store.anime_metadata_by_id(local_anime_id)
            if local_anime_id
            else None
        ) or self.store.anime_metadata(lookup_title)
        current_original = self.anilist.normalize_description(
            (current or {}).get("description_original") or ""
        )
        if current_original and current_original != original:
            logger.info(
                "TRANSLATION_STALE_IGNORED lookupTitle=%s animeId=%s",
                lookup_title,
                (current or {}).get("id") or local_anime_id or "-",
            )
            return False
        if current and str(current.get("description") or "").strip() == localized:
            return False
        self.store.upsert_anime(
            lookup_title,
            {
                "description": localized,
                "description_original": original,
            },
            source="anilist",
            confidence=(current or {}).get("metadata_confidence") or "medium",
            status=(current or {}).get("metadata_status") or "available",
            local_anime_id=local_anime_id,
        )
        row = (
            self.store.anime_metadata_by_id(local_anime_id)
            if local_anime_id
            else None
        ) or self.store.anime_metadata(lookup_title)
        anime_id = (row or current or {}).get("id") or local_anime_id
        logger.info(
            "TRANSLATION_PERSISTED animeId=%s lookupTitle=%s sourceLanguage=%s cacheHit=%s",
            anime_id or "-", lookup_title, source_language or "-", bool(from_cache),
        )
        self._notify_metadata_change(
            "TRANSLATION_CACHE_HIT" if from_cache else "TRANSLATION_SUCCEEDED",
            anime_id=anime_id,
            lookup_title=lookup_title,
            source_language=source_language,
        )
        return True

    def _translation_task_done(self, key, future) -> None:
        with self._translation_pending_lock:
            if self._translation_pending.get(key) is future:
                self._translation_pending.pop(key, None)
        try:
            future.result()
        except Exception:
            logger.exception("Background description localization task failed")

    def _run_description_localization(
        self,
        lookup_title,
        original,
        *,
        local_anime_id=None,
        request_id=None,
    ):
        original = self.anilist.normalize_description(original)
        if not original:
            return original
        started = time.monotonic()
        source_language, _ = self.anilist.detect_description_language(original)
        localized = self.anilist.localize_description_to_pt_br(
            original,
            source_language=source_language,
            request_id=request_id,
        )
        if localized and localized != original and self.anilist._is_valid_translation(original, localized):
            from_cache = self.anilist.get_cached_description_pt_br(original, source_language) == localized
            self._persist_localized_description(
                lookup_title,
                original,
                localized,
                local_anime_id=local_anime_id,
                source_language=source_language,
                from_cache=from_cache,
            )
        logger.info(
            "TRANSLATION_BACKGROUND_DONE lookupTitle=%s sourceLanguage=%s durationMs=%d",
            lookup_title,
            source_language or "-",
            int((time.monotonic() - started) * 1000),
        )
        return localized

    def _schedule_description_localization(
        self,
        lookup_title,
        original,
        *,
        local_anime_id=None,
        request_id=None,
    ) -> bool:
        original = self.anilist.normalize_description(original)
        if not original:
            return False
        source_language, _ = self.anilist.detect_description_language(original)
        if source_language == "pt":
            return False
        if source_language == "unknown":
            logger.info(
                "TRANSLATION_SKIPPED lookupTitle=%s reason=source_language_unknown",
                lookup_title,
            )
            return False
        key = self.anilist.translation_cache_key(original, source_language)
        with self._translation_pending_lock:
            if key in self._translation_pending:
                return False
            if len(self._translation_pending) >= self._translation_pending_limit:
                logger.warning(
                    "TRANSLATION_BACKPRESSURE lookupTitle=%s pending=%d limit=%d",
                    lookup_title,
                    len(self._translation_pending),
                    self._translation_pending_limit,
                )
                self._record_diagnostic(
                    "TRANSLATION_FAILED",
                    request_id=request_id,
                    source=source_language,
                    result="backpressure",
                )
                return False
            future = self._translation_executor.submit(
                self._run_description_localization,
                lookup_title,
                original,
                local_anime_id=local_anime_id,
                request_id=request_id,
            )
            self._translation_pending[key] = future
            future.add_done_callback(
                lambda completed, cache_key=key: self._translation_task_done(cache_key, completed)
            )
        return True

    def shutdown(self) -> None:
        with self._translation_pending_lock:
            self._translation_executor.shutdown(wait=False, cancel_futures=True)
            self._translation_pending.clear()

    def _ensure_cached_description_pt_br(self, lookup_title, cached, *, local_anime_id=None, schedule=True):
        if not cached:
            return cached
        if str(cached.get("metadata_source") or "").casefold() != "anilist":
            return cached

        original = self.anilist.normalize_description(
            cached.get("description_original") or cached.get("description") or ""
        )
        description = self.anilist.normalize_description(cached.get("description") or "")
        if not original:
            return cached

        source_language, _ = self.anilist.detect_description_language(original)
        cached_localized = self.anilist.get_cached_description_pt_br(original, source_language)

        if cached_localized and cached_localized != description:
            changed = self._persist_localized_description(
                lookup_title,
                original,
                cached_localized,
                local_anime_id=local_anime_id,
                source_language=source_language,
                from_cache=True,
            )
            if changed:
                return (
                    self.store.anime_metadata_by_id(local_anime_id)
                    if local_anime_id
                    else None
                ) or self.store.anime_metadata(lookup_title) or cached

        if source_language == "pt":
            if description != original:
                self.store.upsert_anime(
                    lookup_title,
                    {
                        "description": original,
                        "description_original": original,
                    },
                    source="anilist",
                    confidence=cached.get("metadata_confidence") or "medium",
                    status=cached.get("metadata_status") or "available",
                    local_anime_id=local_anime_id,
                )
                return (
                    self.store.anime_metadata_by_id(local_anime_id)
                    if local_anime_id
                    else None
                ) or self.store.anime_metadata(lookup_title) or cached
            return cached

        if schedule and description == original and source_language != "unknown":
            self._schedule_description_localization(
                lookup_title,
                original,
                local_anime_id=local_anime_id,
            )

        return (
            self.store.anime_metadata_by_id(local_anime_id)
            if local_anime_id
            else None
        ) or self.store.anime_metadata(lookup_title) or cached

    def refresh_metadata(
        self,
        lookup_title,
        display_title,
        *,
        force=False,
        bypass_request_dedupe=False,
        match_context=None,
        local_anime_id=None,
        request_id=None,
    ):
        """Refresh editorial metadata without changing the canonical local owner."""
        request_id = str(request_id or uuid.uuid4())
        with self._metadata_lock:
            local_row = self.store.anime_metadata_by_id(local_anime_id) if local_anime_id else None
            owner_id = int(local_row["id"]) if local_row else None
            local_lookup = str(local_row["lookup_title"]) if local_row else str(lookup_title)
            anilist_enabled = bool(self._setting("metadata.anilist_enabled", True))
            cached = local_row or self.store.anime_metadata(local_lookup)
            logger.info(
                "METADATA_ACTION_START requestId=%s animeId=%s lookupTitle=%s anilistId=%s screen=library_service",
                request_id, owner_id or "-", local_lookup, (cached or {}).get("anilist_id") or "-",
            )
            if cached and anilist_enabled:
                cached = self._ensure_cached_description_pt_br(
                    local_lookup,
                    cached,
                    local_anime_id=owner_id,
                    schedule=False,
                )
            if not anilist_enabled:
                return cached or {
                    "title": display_title,
                    "genres": json.dumps(GenreClassifier.classify(display_title), ensure_ascii=False),
                    "metadata_source": "classifier",
                    "metadata_status": "unresolved",
                    "metadata_confidence": "low",
                }

            match_state = self.store.anilist_match(local_lookup) or {}
            associated_id = (cached or {}).get("anilist_id") or match_state.get("anilist_id") or self.store.association(local_lookup)
            cached_id = cached.get("anilist_id") if cached else None
            refresh_id = associated_id or cached_id
            logger.info(
                "METADATA_ACTION_LOOKUP requestId=%s animeId=%s lookupTitle=%s anilistId=%s screen=library_service",
                request_id, owner_id or "-", local_lookup, refresh_id or "-",
            )

            if cached and cached.get("metadata_fetched_at") and not bypass_request_dedupe:
                try:
                    if time.time() - float(cached["metadata_fetched_at"]) < self.REQUEST_DEDUPE_SECONDS:
                        return cached
                except (TypeError, ValueError):
                    pass
            if not force and self._cached_metadata_is_current(cached, refresh_id):
                return cached

            if cached:
                self.store.set_metadata_status(local_lookup, "refreshing")

            try:
                if refresh_id:
                    media = self.anilist.by_id(refresh_id)
                    if media:
                        refreshed = self.anilist.metadata_from_media(display_title, media, localize_description=False)
                        refreshed["anilist_id"] = refresh_id
                        logger.info(
                            "METADATA_ACTION_DB_WRITE requestId=%s animeId=%s lookupTitle=%s anilistId=%s screen=library_service",
                            request_id, owner_id or "-", local_lookup, refresh_id,
                        )
                        self.store.upsert_anime(
                            local_lookup,
                            refreshed,
                            source="anilist",
                            confidence="high",
                            status="available",
                            fetched_at=time.time(),
                            local_anime_id=owner_id,
                        )
                        row = self.store.anime_metadata_by_id(owner_id) if owner_id else self.store.anime_metadata(local_lookup)
                        if row:
                            self._schedule_description_localization(
                                local_lookup,
                                row.get("description_original") or row.get("description") or "",
                                local_anime_id=row.get("id"),
                                request_id=request_id,
                            )
                            self._sync_genres(row["id"], row, source="anilist")
                            self.artwork.sync_anime_metadata(row["id"], row)
                        return row or refreshed
                    if cached:
                        self.store.set_metadata_status(
                            local_lookup,
                            "stale",
                            confidence=cached.get("metadata_confidence") or "high",
                        )
                        return (
                            self.store.anime_metadata_by_id(owner_id)
                            if owner_id
                            else None
                        ) or self.store.anime_metadata(local_lookup) or cached
                    return {
                        "title": display_title,
                        "genres": "[]",
                        "metadata_source": "local",
                        "metadata_status": "unresolved",
                        "metadata_confidence": "low",
                    }

                candidates = self.anilist.search(display_title)
                search_status = str(self.anilist.last_request_status or "idle")
                if search_status in {"network_error", "rate_limited", "invalid_response", "http_error"}:
                    existing_match = self.store.anilist_match(local_lookup)
                    if search_status in {"network_error", "rate_limited"} and existing_match:
                        self.store.set_anilist_match(
                            local_lookup,
                            existing_match.get("anilist_id"),
                            status="rate_limited" if search_status == "rate_limited" else "network_error",
                            manual=bool(existing_match.get("anilist_match_manual")),
                        )
                    logger.info("ANILIST_MATCH_%s title=%s", search_status.upper(), display_title)
                    return cached or {
                        "title": display_title,
                        "genres": "[]",
                        "metadata_source": "local",
                        "metadata_status": "unresolved",
                        "metadata_confidence": "low",
                    }

                candidates = candidates or []
                if isinstance(match_context, dict):
                    context = MatchContext(
                        season_number=match_context.get("season_number"),
                        episode_type=match_context.get("episode_type"),
                        media_kind=match_context.get("media_kind"),
                        year=match_context.get("year"),
                    )
                else:
                    context = match_context or MatchContext()

                selected, confident, ranked = AnimeOrganizer.choose(
                    display_title,
                    candidates,
                    context=context,
                )
                if selected and confident:
                    score = float(selected.get("match_score") or 0.0)
                    second = float(ranked[1].get("match_score") or 0.0) if len(ranked) > 1 else 0.0
                    margin = round(score - second, 3)
                    refreshed = self.anilist.metadata_from_media(display_title, selected, localize_description=False)
                    refreshed["anilist_id"] = selected["id"]
                    confidence = "high" if score >= 0.9 else "medium"
                    logger.info(
                        "METADATA_ACTION_DB_WRITE requestId=%s animeId=%s lookupTitle=%s anilistId=%s screen=library_service",
                        request_id, owner_id or "-", local_lookup, selected["id"],
                    )
                    self.store.upsert_anime(
                        local_lookup,
                        refreshed,
                        source="anilist",
                        confidence=confidence,
                        status="available",
                        fetched_at=time.time(),
                        local_anime_id=owner_id,
                    )
                    self.store.set_anilist_match(
                        local_lookup,
                        selected["id"],
                        status="matched",
                        score=score,
                        margin=margin,
                        manual=False,
                    )
                    row = self.store.anime_metadata_by_id(owner_id) if owner_id else self.store.anime_metadata(local_lookup)
                    if row:
                        self._schedule_description_localization(
                            local_lookup,
                            row.get("description_original") or row.get("description") or "",
                            local_anime_id=row.get("id"),
                            request_id=request_id,
                        )
                        self._sync_genres(row["id"], row, source="anilist")
                        self.artwork.sync_anime_metadata(row["id"], row)
                    logger.info(
                        "ANILIST_MATCH_RESULT title=%s id=%s score=%.3f margin=%.3f",
                        display_title,
                        selected["id"],
                        score,
                        margin,
                    )
                    return row or refreshed

                if ranked:
                    best_score = float(ranked[0].get("match_score") or 0.0)
                    second_score = float(ranked[1].get("match_score") or 0.0) if len(ranked) > 1 else 0.0
                    self.store.set_pending_match(local_lookup, display_title, ranked[:5])
                    if self.store.anime_metadata(local_lookup):
                        self.store.set_anilist_match(
                            local_lookup,
                            None,
                            status="ambiguous",
                            score=best_score,
                            margin=round(best_score - second_score, 3),
                        )
                    logger.info("ANILIST_MATCH_AMBIGUOUS title=%s candidates=%d", display_title, len(ranked))
                    if cached:
                        self.store.set_metadata_status(local_lookup, "ambiguous", confidence="medium")
                        return cached
                    local = {
                        "title": display_title,
                        "genres": "[]",
                        "metadata_source": "local",
                        "metadata_status": "ambiguous",
                        "metadata_confidence": "low",
                    }
                    self.store.upsert_anime(
                        local_lookup,
                        local,
                        source="local",
                        confidence="low",
                        status="ambiguous",
                        local_anime_id=owner_id,
                    )
                    self.store.set_anilist_match(
                        local_lookup,
                        None,
                        status="ambiguous",
                        score=best_score,
                        margin=round(best_score - second_score, 3),
                    )
                    row = self.store.anime_metadata_by_id(owner_id) if owner_id else self.store.anime_metadata(local_lookup)
                    if row:
                        self.artwork.sync_anime_metadata(row["id"], row)
                    return row or local

                if cached:
                    self.store.set_metadata_status(local_lookup, "unresolved", confidence="low")
                    self.store.set_anilist_match(local_lookup, None, status="not_found")
                    logger.info("ANILIST_MATCH_NOT_FOUND title=%s", display_title)
                    return cached

                local = {
                    "title": display_title,
                    "genres": "[]",
                    "metadata_source": "local",
                    "metadata_status": "unresolved",
                    "metadata_confidence": "low",
                }
                self.store.upsert_anime(
                    local_lookup,
                    local,
                    source="local",
                    confidence="low",
                    status="unresolved",
                    local_anime_id=owner_id,
                )
                self.store.set_anilist_match(local_lookup, None, status="not_found")
                row = self.store.anime_metadata_by_id(owner_id) if owner_id else self.store.anime_metadata(local_lookup)
                if row:
                    self.artwork.sync_anime_metadata(row["id"], row)
                logger.info("ANILIST_MATCH_NOT_FOUND title=%s", display_title)
                return row or local
            except Exception as exc:
                logger.warning(
                    "METADATA_ACTION_ERROR requestId=%s animeId=%s lookupTitle=%s anilistId=%s screen=library_service error=%s",
                    request_id, owner_id or "-", local_lookup, refresh_id or "-", exc,
                )
                logger.warning("Metadata AniList indisponível para %s: %s", display_title, exc)
                if cached:
                    self.store.set_metadata_status(
                        local_lookup,
                        "stale",
                        confidence=cached.get("metadata_confidence") or "low",
                    )
                    return cached
                local = {
                    "title": display_title,
                    "genres": "[]",
                    "metadata_source": "local",
                    "metadata_status": "unresolved",
                    "metadata_confidence": "low",
                }
                self.store.upsert_anime(
                    local_lookup,
                    local,
                    source="local",
                    confidence="low",
                    status="unresolved",
                    local_anime_id=owner_id,
                )
                row = self.store.anime_metadata_by_id(owner_id) if owner_id else self.store.anime_metadata(local_lookup)
                if row:
                    self.artwork.sync_anime_metadata(row["id"], row)
                return row or local
    @staticmethod
    def _match_context_from_catalog(item):
        media_kind = str(item.get("media_kind") or (item.get("meta") or {}).get("media_kind") or "series").casefold()
        if media_kind == "movie":
            return MatchContext(media_kind="movie", episode_type="movie")
        seasons = {
            int(episode.get("season"))
            for season in (item.get("seasons") or [])
            for episode in (season.get("episodes") or [])
            if episode.get("season") is not None
        }
        season_number = next(iter(seasons)) if len(seasons) == 1 else None
        return MatchContext(season_number=season_number, media_kind=media_kind)

    def hydrate_catalog_metadata(self, catalog):
        """Hydrate local items that still need AniList metadata or poster artwork.

        The queue is intentionally sequential and reuses the existing AniListClient,
        ArtworkEngine and SQLite catalog. It never creates a second cache or catalog.
        """
        hydrated = []
        pending_cache = None
        for item in list(catalog or []):
            metadata = dict(item.get('meta') or {})
            lookup_title = str(metadata.get('lookup_title') or item.get('main_title') or '').strip()
            display_title = str(item.get('main_title') or metadata.get('title') or lookup_title).strip()
            if not lookup_title or not display_title: continue

            local_anime_id = item.get("id")
            try:
                local_anime_id = int(local_anime_id) if local_anime_id is not None else None
            except (TypeError, ValueError):
                local_anime_id = None
            cached = (
                self.store.anime_metadata_by_id(local_anime_id)
                if local_anime_id
                else None
            ) or self.store.anime_metadata(lookup_title) or metadata
            effective_lookup = str(cached.get("lookup_title") or lookup_title)
            anilist_id = cached.get('anilist_id') or self.store.association(effective_lookup)
            if cached and anilist_id:
                cached = self._ensure_cached_description_pt_br(effective_lookup, cached)
            status = str(cached.get('metadata_status') or 'unresolved').casefold()
            materialized = self._metadata_is_materialized(cached)
            if materialized:
                self._record_diagnostic(
                    "METADATA_MATERIALIZATION_SKIPPED",
                    anime_id=cached.get("id"),
                    anilist_id=anilist_id,
                    lookup_title=effective_lookup,
                    reason="hydrate_materialized",
                )
            if status == 'manual' and not anilist_id:
                continue
            entity_type = 'movie' if str(cached.get('media_kind') or item.get('media_kind') or 'series').casefold() == 'movie' else 'anime'
            if cached.get('id'):
                try:
                    # Always reconcile durable AniList artwork metadata before
                    # deciding whether a network request is needed. The
                    # ArtworkEngine itself is cache-hit aware and deduplicates
                    # identical requests.
                    self.artwork.sync_anime_metadata(cached['id'], cached)
                except Exception:
                    logger.debug(
                        'Artwork metadata reconciliation failed during hydration',
                        extra={'lookup_title': lookup_title, 'anime_id': cached.get('id')},
                        exc_info=True,
                    )
            # "stale" is an informational TTL state, never an automatic
            # AniList trigger. Only genuinely unmaterialized metadata enters
            # the one-shot materialization path.
            needs_metadata = not materialized and (
                not anilist_id or status in {'unresolved', 'error', 'ambiguous'}
            )
            if status == 'ambiguous' and not anilist_id:
                if pending_cache is None: pending_cache = self.store.pending_matches()
                needs_metadata = not any(p.get('lookup_title') == effective_lookup for p in pending_cache)
            needs_cover = False
            needs_backdrop = False
            if anilist_id and cached.get('id') and self._setting("artwork.enabled", True):
                entity_type = 'movie' if str(cached.get('media_kind') or item.get('media_kind') or 'series').casefold() == 'movie' else 'anime'
                poster_rows = self.artwork.list_for(entity_type, cached['id'], 'poster')
                backdrop_rows = self.artwork.list_for(entity_type, cached['id'], 'backdrop')
                needs_cover = bool(
                    str(cached.get('cover_url') or '').strip()
                    and not any(
                        row.get('local_path') and self.artwork._is_valid_image_file(row.get('local_path'))
                        for row in poster_rows
                    )
                )
                needs_backdrop = bool(
                    str(cached.get('banner_url') or '').strip()
                    and not any(
                        row.get('local_path') and self.artwork._is_valid_image_file(row.get('local_path'))
                        for row in backdrop_rows
                    )
                )
            if not needs_metadata and not needs_cover and not needs_backdrop:
                self._record_diagnostic(
                    "METADATA_MATERIALIZATION_SKIPPED",
                    anime_id=cached.get("id"),
                    anilist_id=anilist_id,
                    lookup_title=effective_lookup,
                    reason="materialized_no_artwork_work",
                )
                continue
            try:
                metadata_refreshed = False
                cover_attempt_failed = False
                self._record_diagnostic(
                    "METADATA_MATERIALIZATION_START",
                    anime_id=cached.get("id") if isinstance(cached, dict) else local_anime_id,
                    anilist_id=anilist_id,
                    lookup_title=effective_lookup,
                )
                if needs_metadata:
                    cached = self.refresh_metadata(
                        effective_lookup,
                        display_title,
                        # Hydration may be scheduled again while a Home view is
                        # cached. Respect the short request dedupe window so a
                        # still-fresh result does not make another AniList call.
                        force=True,
                        match_context=self._match_context_from_catalog(item),
                        local_anime_id=local_anime_id,
                        request_id=str(uuid.uuid4()),
                    )
                    cached = (
                        self.store.anime_metadata_by_id(local_anime_id)
                        if local_anime_id
                        else None
                    ) or self.store.anime_metadata(effective_lookup) or cached or {}
                    status = str(cached.get('metadata_status') or status).casefold()
                    effective_lookup = str(cached.get("lookup_title") or effective_lookup)
                    anilist_id = cached.get('anilist_id') or self.store.association(effective_lookup)
                    metadata_refreshed = True
                cover_url = str(cached.get('cover_url') or '').strip()
                if anilist_id and cached.get('id') and self._setting("artwork.enabled", True):
                    entity_type = 'movie' if str(cached.get('media_kind') or item.get('media_kind') or 'series').casefold() == 'movie' else 'anime'
                    self.artwork.sync_anime_metadata(cached['id'], cached)
                    if cover_url and (needs_cover or metadata_refreshed):
                        resolved = self.artwork.request(
                            entity_type,
                            cached['id'],
                            'poster',
                            priority=100,
                            allow_network=True,
                            blocking=True,
                        )
                        cached = (
                            self.store.anime_metadata_by_id(local_anime_id)
                            if local_anime_id
                            else None
                        ) or self.store.anime_metadata(effective_lookup) or cached
                        cover_attempt_failed = not bool(
                            resolved
                            and resolved.get('local_path')
                            and self.artwork._is_valid_image_file(resolved.get('local_path'))
                        )
                    banner_url = str(cached.get('banner_url') or '').strip()
                    if banner_url and (needs_backdrop or metadata_refreshed):
                        self.artwork.request(
                            entity_type,
                            cached['id'],
                            'backdrop',
                            priority=90,
                            allow_network=True,
                            blocking=True,
                        )
                if cached.get('id'):
                    self.artwork.sync_anime_metadata(cached['id'], cached)
                    if cover_attempt_failed:
                        self.artwork.mark_download_failure(entity_type, cached['id'], 'poster', cover_url)
                    self._record_diagnostic(
                        "METADATA_MATERIALIZATION_SUCCESS",
                        anime_id=cached.get("id"),
                        anilist_id=cached.get("anilist_id"),
                        lookup_title=effective_lookup,
                        artwork_local=bool(self.artwork.resolve(entity_type, cached['id'], 'poster', allow_network=False)),
                        backdrop_local=bool(self.artwork.resolve(entity_type, cached['id'], 'backdrop', allow_network=False)),
                    )
                hydrated.append({'lookup_title': effective_lookup, 'id': cached.get('id'), 'metadata': cached})
            except Exception:
                logger.exception('Local metadata/artwork hydration failed', extra={'screen':'home','lookup_title':lookup_title,'library_items':len(catalog)})
        return hydrated
    def set_manual_metadata(self, lookup_title, values):
        row = self.store.set_manual_metadata(lookup_title, values)
        self._sync_genres(row["id"], row, source="user")
        return row

    def organize_summary_bounded(self):
        """Return Organize overview aggregates without materializing the full catalog."""
        return self.store.organize_summary()
    def genre_options(self, *, include_unused=False):
        return self.genre_registry.list_all(include_unused=include_unused)

    def search_genres(self, query):
        return self.genre_registry.search(query)

    def create_custom_genre(self, name):
        return self.genre_registry.register(name, source="user", is_custom=True)

    def attach_genre(self, anime_id, genre_id):
        return self.genre_registry.attach(anime_id, genre_id, source="user")

    def detach_genre(self, anime_id, genre_id):
        return self.genre_registry.detach(anime_id, genre_id, source="user")

    def register_generated_thumbnail(self, media_uri, thumbnail_path, *, size=0, modified_at=0, media_identity=None, metadata=None):
        return self.artwork.register_generated_thumbnail(media_uri, thumbnail_path, size=size, modified_at=modified_at, media_identity=media_identity, metadata=metadata)

    def thumbnail_candidates(self, *, after_id=0, limit=128):
        """Return eligible episodes that do not currently have a valid exact thumbnail."""
        limit = max(1, min(int(limit or 128), 128))
        after_id = max(0, int(after_id or 0))
        with self.store._conn() as con:
            rows = [
                dict(row) for row in con.execute(
                    """SELECT id,anime_id,path,file_size,modified_at,media_identity,missing,
                              availability_state,last_played_at
                       FROM episodes
                       WHERE id > ?
                         AND missing=0
                         AND COALESCE(availability_state,'available')='available'
                         AND path IS NOT NULL
                         AND trim(path) != ''
                       ORDER BY id
                       LIMIT ?""",
                    (after_id, limit),
                ).fetchall()
            ]
        if not rows:
            return []
        exact = self.artwork.resolve_local_batch(
            "episode",
            [row["id"] for row in rows],
            ("episode_thumbnail",),
        )
        return [
            {
                **row,
                "thumbnail_ready": str(row["id"]) in exact,
            }
            for row in rows
            if str(row["id"]) not in exact
        ]

    def resolve_artwork_batch(self, entity_type, entity_ids, artwork_types=("episode_thumbnail", "poster")):
        """Return local artwork for many entities through the shared ArtworkEngine."""
        return self.artwork.resolve_local_batch(entity_type, entity_ids, artwork_types=artwork_types)

    def resolve_artwork(self, entity_type, entity_id, artwork_type, *, allow_network=True):
        effective_allow_network = bool(
            allow_network and self._setting("artwork.enabled", True)
        )
        return self.artwork.resolve(
            entity_type, entity_id, artwork_type, allow_network=effective_allow_network,
        )

    def resolve_artwork_palette(self, entity_type, entity_id, artwork_type="poster", *, mode="dark"):
        """Resolve contextual artwork colors from the shared cached ArtworkEngine."""
        return self.artwork.resolve_palette(
            entity_type, entity_id, artwork_type, mode=mode,
        )

    def set_manual_artwork(self, entity_type, entity_id, artwork_type, *, path=None, external_url=None):
        return self.artwork.set_manual(entity_type, entity_id, artwork_type, path=path, external_url=external_url)

    def clear_manual_artwork(self, entity_type, entity_id, artwork_type):
        return self.artwork.clear_manual(entity_type, entity_id, artwork_type)

    @staticmethod
    def _saf_tree_document_id(reference):
        raw = str(reference or "").strip()
        if not raw:
            return None
        try:
            parsed = urlparse(raw)
        except ValueError:
            return None
        if parsed.scheme.casefold() != "content" or not parsed.path:
            return None
        marker = "/tree/"
        if marker not in parsed.path:
            return None
        encoded = parsed.path.split(marker, 1)[1].split("/", 1)[0]
        document_id = unquote(encoded).strip().strip("/")
        return document_id or None

    @staticmethod
    def _saf_document_id_from_uri(uri):
        raw = str(uri or "").strip()
        if not raw:
            return None
        try:
            parsed = urlparse(raw)
        except ValueError:
            return None
        path = parsed.path or ""
        marker = "/document/"
        if marker not in path:
            return None
        encoded = path.rsplit(marker, 1)[1].split("/", 1)[0]
        document_id = unquote(encoded).strip().strip("/")
        return document_id or None

    @staticmethod
    def _saf_document_id_within_tree(tree_document_id, document_id):
        root = unquote(str(tree_document_id or "")).strip().strip("/")
        child = unquote(str(document_id or "")).strip().strip("/")
        return bool(root and child and (child == root or child.startswith(root + "/")))

    @staticmethod
    def _filesystem_path_within_source(path, source_root):
        try:
            candidate = os.path.realpath(str(path or "").strip())
            root = os.path.realpath(str(source_root or "").strip())
        except (OSError, TypeError):
            return False
        if not candidate or not root:
            return False
        try:
            return os.path.commonpath((candidate, root)) == root
        except ValueError:
            return False

    def _library_source_policy(self, source_reference, source_kind, scope_ref=None):
        kind = str(source_kind or "").strip().casefold()
        reference = str(scope_ref or source_reference or "").strip()
        if not reference:
            return {"ok": False, "reason": "SOURCE_REFERENCE_MISSING"}
        folders = self.store.folders()
        if kind == "saf":
            requested_identity = saf_source_identity(reference) or saf_source_identity(source_reference)
            if not requested_identity:
                return {"ok": False, "reason": "INVALID_SOURCE_URI"}
            for folder in folders:
                if str(folder.get("kind") or "").casefold() != "saf":
                    continue
                folder_reference = str(folder.get("path") or "").strip()
                folder_identity = str(folder.get("saf_identity") or "").strip() or saf_source_identity(folder_reference)
                if folder_identity != requested_identity:
                    continue
                if str(folder.get("authorization") or "").casefold() != "granted":
                    return {"ok": False, "reason": "SOURCE_PERMISSION_LOST", "identity": requested_identity}
                tree_document_id = str(folder.get("saf_document_id") or "").strip() or self._saf_tree_document_id(folder_reference)
                if not tree_document_id:
                    return {"ok": False, "reason": "INVALID_SOURCE_URI", "identity": requested_identity}
                return {"ok": True, "kind": "saf", "reference": folder_reference, "identity": requested_identity, "tree_document_id": tree_document_id}
            return {"ok": False, "reason": "SOURCE_NOT_CONFIGURED", "identity": requested_identity}
        if kind in {"filesystem", "path"}:
            for folder in folders:
                folder_kind = str(folder.get("kind") or "path").casefold()
                folder_reference = str(folder.get("path") or "").strip()
                if folder_kind not in {"filesystem", "path"} or not folder_reference:
                    continue
                if str(folder.get("authorization") or "").casefold() != "granted":
                    continue
                if os.path.realpath(folder_reference) != os.path.realpath(reference):
                    continue
                return {"ok": True, "kind": "filesystem", "reference": folder_reference, "root": os.path.realpath(folder_reference)}
            return {"ok": False, "reason": "SOURCE_NOT_CONFIGURED"}
        return {"ok": False, "reason": "NON_LIBRARY_SCANNER"}

    def _validate_library_document_scope(self, document, source_policy):
        policy = source_policy or {}
        if not policy.get("ok"):
            return False, str(policy.get("reason") or "SOURCE_NOT_CONFIGURED")
        item = document if isinstance(document, dict) else {}
        uri = str(item.get("uri") or "").strip()
        if not uri:
            return False, "INVALID_URI"
        if policy.get("kind") == "saf":
            expected_identity = str(policy.get("identity") or "").strip()
            tree_uri = str(item.get("treeUri") or "").strip()
            if tree_uri and saf_source_identity(tree_uri) != expected_identity:
                return False, "OUTSIDE_SOURCE"
            document_scope = str(item.get("scope") or "").strip()
            if document_scope and document_scope != expected_identity:
                return False, "OUTSIDE_SOURCE"
            try:
                parsed = urlparse(uri)
            except ValueError:
                return False, "INVALID_URI"
            if parsed.scheme.casefold() != "content" or not parsed.netloc:
                return False, "INVALID_URI"
            configured_parsed = urlparse(str(policy.get("reference") or ""))
            if parsed.netloc.casefold() != str(configured_parsed.netloc or "").casefold():
                return False, "OUTSIDE_SOURCE"
            document_id = str(item.get("documentId") or "").strip()
            document_id = unquote(document_id) if document_id else self._saf_document_id_from_uri(uri)
            if not self._saf_document_id_within_tree(policy.get("tree_document_id"), document_id):
                return False, "OUTSIDE_SOURCE"
            uri_document_id = self._saf_document_id_from_uri(uri)
            if uri_document_id and unquote(uri_document_id) != unquote(document_id or ""):
                return False, "INVALID_URI"
            return True, "ACCEPTED"
        if policy.get("kind") == "filesystem":
            if uri.casefold().startswith("file://"):
                try:
                    candidate = unquote(urlparse(uri).path)
                except ValueError:
                    return False, "INVALID_URI"
            elif os.path.isabs(uri):
                candidate = uri
            else:
                candidate = str(item.get("path") or "").strip()
            if not candidate:
                return False, "INVALID_PATH"
            real_candidate = os.path.realpath(candidate)
            if not self._filesystem_path_within_source(real_candidate, policy.get("root")):
                return False, "OUTSIDE_SOURCE"
            if not os.path.isfile(real_candidate):
                return False, "INVALID_FILE"
            return True, "ACCEPTED"
        return False, "NON_LIBRARY_SCANNER"

    @staticmethod
    def _document_relative_path(document, name, uri):
        relative_path = document.get("relativePath") or document.get("path") or name
        if uri.startswith("file://") and not document.get("relativePath") and not document.get("path"):
            relative_path = unquote(urlparse(uri).path)
        return str(relative_path).replace(chr(92), "/").strip("/")

    @staticmethod
    def _physical_unchanged(existing, *, source_folder, file_size, modified_at, relative_path, volume_id):
        if not existing or existing.get("missing"):
            return False
        return (
            existing.get("source_folder") == source_folder
            and existing.get("file_size") == file_size
            and existing.get("modified_at") == modified_at
            and (existing.get("relative_path") or "") == (relative_path or "")
            and (existing.get("volume_id") or "") == (volume_id or "")
        )

    def _record_document(self, *, document, source_folder, source_kind, metadata, result, affected_anime_ids=None, known_paths=None, scope_kind="source", scope_ref=None, native_generation=None, source_policy=None):
        uri = document.get("uri")
        name = document.get("name")
        if not isinstance(uri, str) or not uri or not isinstance(name, str) or not name.strip():
            result.ignored += 1
            result.errors.append("Documento local incompleto recebido da ponte Android.")
            return None

        if source_policy is not None:
            allowed, reason = self._validate_library_document_scope(document, source_policy)
            if not allowed:
                result.ignored += 1
                result.errors.append(f"{name}: fonte rejeitada ({reason}).")
                logger.debug("[LIBRARY_SOURCE] SCAN_SOURCE_FILE_REJECTED source=%s reason=%s uri=%s", source_kind, reason, uri)
                return None
            logger.debug("[LIBRARY_SOURCE] SCAN_SOURCE_FILE_ACCEPTED source=%s uri=%s", source_kind, uri)

        relative_path = self._document_relative_path(document, name, uri)
        is_local_reference = (
            uri.startswith("content://")
            or uri.startswith("file://")
            or (source_kind == "filesystem" and os.path.isabs(uri))
        )
        if not is_local_reference:
            result.ignored += 1
            result.errors.append(f"Referência local inválida para {name}.")
            return None

        file_size = document.get("size")
        modified_at = document.get("modifiedAt")
        volume_id = document.get("volumeId")
        volume_uuid = document.get("volumeUuid")
        existing = self.store.physical_row(uri)
        observation_scope_ref = scope_ref if scope_ref is not None else (source_folder if scope_kind == "source" else None)
        if self._physical_unchanged(
            existing, source_folder=source_folder, file_size=file_size,
            modified_at=modified_at, relative_path=relative_path, volume_id=volume_id,
        ):
            self.store.record_observation(
                existing["id"],
                source_kind=source_kind,
                scope_kind=scope_kind,
                scope_ref=observation_scope_ref,
                uri=uri,
                volume_id=volume_id,
                native_generation=native_generation or document.get("scanGeneration"),
                fingerprint=document.get("nativeFingerprint"),
            )
            result.unchanged += 1
            return uri

        try:
            item = parse_video_path(relative_path, source_folder)
        except (OSError, ValueError, UnicodeError) as exc:
            result.ignored += 1
            result.errors.append(f"Não foi possível identificar {name}: {exc}")
            return None

        key = item.anime_title.casefold()
        if item.episode_type == "unknown" or not item.anime_title or item.anime_title == "Arquivo não identificado":
            result.unknown += 1

        identity_uri = uri if uri.startswith(("file://", "content://")) else Path(uri).as_uri()
        native_identity = document.get("stableId")
        identity = (
            native_identity.strip()
            if isinstance(native_identity, str) and native_identity.strip()
            else identity_from_document(
                identity_uri,
                relative_path,
                volume_id,
                source_folder if source_kind == "saf" else None,
            )
        )

        # Resolve the local owner before consulting title-keyed metadata. Title is
        # an editorial lookup fallback, never the primary owner identity.
        local_owner_id = (
            int(existing["anime_id"])
            if existing and existing.get("anime_id") is not None
            else self.store.resolve_local_anime_owner(
                media_identity=identity,
                path=uri,
                source_folder=source_folder,
                relative_path=relative_path,
                volume_id=volume_id,
                lookup_title=key,
            )
        )
        if local_owner_id:
            logger.info(
                "[EPISODE_OWNER_INVARIANT] scanner reused local anime owner anime_id=%s lookup_title=%s identity=%s path=%s",
                local_owner_id, key, identity or "-", uri,
            )

        if key not in metadata:
            try:
                metadata[key] = (
                    self.store.anime_metadata_by_id(local_owner_id)
                    if local_owner_id
                    else None
                ) or self._identify(key, item.anime_title, lambda message: None, allow_network=False)
            except Exception as exc:
                metadata[key] = (
                    self.store.anime_metadata_by_id(local_owner_id)
                    if local_owner_id
                    else self.store.anime_metadata(key)
                ) or {"title": item.anime_title, "genres": "[]"}
                result.errors.append(f"{item.anime_title}: metadata indisponível ({exc})")
            metadata[key] = dict(metadata[key] or {})
            if item.episode_type == "movie":
                metadata[key]["media_kind"] = "movie"
            elif item.episode_type == "unknown":
                metadata[key]["media_kind"] = "unknown"
            else:
                metadata[key]["media_kind"] = metadata[key].get("media_kind") or "series"

        anime_id = self.store.upsert_anime(
            key,
            metadata[key],
            source=metadata[key].get("metadata_source") or "local",
            confidence=metadata[key].get("metadata_confidence"),
            status=metadata[key].get("metadata_status"),
            local_anime_id=local_owner_id,
        )

        # A move/rename changes the path-derived identity. When Android/storage
        # metadata proves that exactly one missing row for the same title/source/
        # volume has the same size+mtime, carry forward its durable identity.
        if existing is None:
            candidate = self.store.missing_candidate(
                anime_id, source_folder, file_size, modified_at, volume_id,
                excluded_paths=known_paths,
            )
            if candidate and candidate.get("media_identity"):
                identity = candidate["media_identity"]

        row_id = self.store.upsert_episode(
            anime_id, uri, name, item.season, item.episode,
            document.get("mimeType"), file_size, modified_at, source_folder,
            media_identity=identity,
            identification_source=item.identification_source,
            identification_confidence=item.confidence,
        )
        self.store.apply_episode_identification(
            uri, absolute_number=item.absolute_number, relative_path=relative_path,
            volume_id=volume_id, volume_uuid=volume_uuid,
            episode_type=item.episode_type, episode_title=item.display_title,
            identification_source=item.identification_source,
            identification_confidence=item.confidence,
        )
        self.store.record_observation(
            row_id,
            source_kind=source_kind,
            scope_kind=scope_kind,
            scope_ref=observation_scope_ref,
            uri=uri,
            volume_id=volume_id,
            native_generation=native_generation or document.get("scanGeneration"),
            fingerprint=document.get("nativeFingerprint"),
        )
        # Per-episode thumbnails are discovered immediately. Poster/season
        # discovery is deferred to one pass per affected entity.
        self.artwork.discover_episode(row_id)
        if affected_anime_ids is not None:
            affected_anime_ids.add(anime_id)

        if existing:
            result.updated += 1
        elif row_id is not None:
            result.new += 1
        return uri

    def ingest_documents_batch(self, tree_uri: str, documents: list[dict], *,
                               source_kind="saf", scan_id=None, scope_kind="global", scope_ref=None,
                               scan_generation=None, generation_id=None, request_id=None,
                               batch_id=None, batch_number=0, batch_size=None, folder_name=None, scan_errors=None,
                               enforce_library_source=False):
        """Ingest one bounded native batch without destructive reconciliation."""
        with self._scan_lock:
            scan_id = scan_id or str(uuid.uuid4())
            scope_ref = scope_ref or tree_uri
            source_policy = self._library_source_policy(tree_uri, source_kind, scope_ref) if enforce_library_source else None
            if enforce_library_source and not source_policy.get("ok"):
                reason = str(source_policy.get("reason") or "SOURCE_NOT_CONFIGURED")
                logger.warning("[LIBRARY_SOURCE] SCAN_SOURCE_REJECTED source=%s scope_ref=%s reason=%s", source_kind, scope_ref, reason)
                return {"scan_id": scan_id, "ignored": True, "reason": reason, "files": 0, "videos": 0, "discovered": len(documents or []), "processed": 0, "inserted": 0, "new": 0, "updated": 0, "unchanged": 0, "duplicates": 0, "ignored": len(documents or []), "unknown": 0, "errors": [reason], "elapsed_ms": 0, "elapsedMs": 0}
            existing = self.store.scan_by_id(scan_id)
            if existing and str(existing.get("status") or "").casefold() in {"completed","partial","cancelled","error","failed"}:
                return {"scan_id": scan_id, "ignored": True, "reason": "scan_already_finalized"}
            try:
                generation = int(scan_generation) if scan_generation is not None else None
            except (TypeError, ValueError):
                generation = None
            run_id = int(existing["id"]) if existing else self.store.begin_scan(scan_id=scan_id, source_kind=source_kind, scope_kind=scope_kind, scope_ref=scope_ref, native_generation=generation, generation_id=str(generation_id or scan_id))
            if generation is not None:
                latest = self.store.latest_completed_native_generation(source_kind, scope_kind, scope_ref)
                if latest is not None and generation < latest:
                    return {"scan_id": scan_id, "ignored": True, "reason": "stale_generation"}
            if not enforce_library_source:
                self.store.add_folder(tree_uri, name=folder_name or tree_uri.rsplit("/",1)[-1], kind=source_kind, authorization="granted", account_id=self.store.account().get("id"))
            result = ScanResult(catalog=[], scan_id=scan_id)
            metadata, affected_anime_ids, seen = {}, set(), set()
            started = time.time()
            for document in documents or []:
                if not isinstance(document, dict):
                    result.ignored += 1; continue
                uri = document.get("uri")
                if not isinstance(uri, str) or not uri:
                    result.ignored += 1; continue
                if uri in seen:
                    result.duplicates += 1; continue
                seen.add(uri)
                if generation is not None and self.store.has_observation_for_generation(uri, source_kind=source_kind, scope_kind=scope_kind, scope_ref=scope_ref, native_generation=generation):
                    result.duplicates += 1; continue
                accepted = self._record_document(document=document, source_folder=tree_uri, source_kind=source_kind, metadata=metadata, result=result, affected_anime_ids=affected_anime_ids, known_paths=seen, scope_kind=scope_kind, scope_ref=scope_ref, native_generation=generation, source_policy=source_policy)
                if accepted: result.videos += 1
            result.errors.extend(str(e) for e in (scan_errors or []))
            elapsed_ms = int((time.time()-started)*1000)
            self.store.update_scan_progress(run_id, result.__dict__, request_id=request_id, source=source_kind, volume_id=scope_ref if scope_kind=="volume" else None, scope=scope_ref, batch_id=batch_id, batch_number=batch_number, batch_size=batch_size if batch_size is not None else len(documents or []), discovered=len(documents or []), processed=result.files, inserted=result.new, elapsed_ms=elapsed_ms, errors=result.errors)
            return {"scan_id":scan_id,"request_id":request_id,"generation_id":generation_id or scan_id,"batch_id":batch_id,"batch_number":batch_number,"batch_size":batch_size if batch_size is not None else len(documents or []),"files":result.files,"videos":result.videos,"processed":result.files,"discovered":len(documents or []),"inserted":result.new,"new":result.new,"updated":result.updated,"unchanged":result.unchanged,"duplicates":result.duplicates,"removed":0,"ignored":result.ignored,"unknown":result.unknown,"errors":result.errors,"elapsed_ms":elapsed_ms,"elapsedMs":elapsed_ms}

    def finish_ingest_documents(self, tree_uri: str, *, source_kind="saf", scan_id=None, scope_kind="global", scope_ref=None, scan_generation=None, generation_id=None, status="completed", folder_name=None, scan_errors=None, scan_stats=None, enforce_library_source=False):
        """Finalize a native scan; only a trusted COMPLETE generation reconciles."""
        with self._scan_lock:
            scan_id = scan_id or str(uuid.uuid4()); scope_ref = scope_ref or tree_uri
            if enforce_library_source:
                source_policy = self._library_source_policy(tree_uri, source_kind, scope_ref)
                if not source_policy.get("ok"):
                    reason = str(source_policy.get("reason") or "SOURCE_NOT_CONFIGURED")
                    logger.warning("[LIBRARY_SOURCE] SCAN_SOURCE_REJECTED source=%s scope_ref=%s reason=%s", source_kind, scope_ref, reason)
                    return ScanResult(catalog=self.store.catalog(), scan_id=scan_id, status="blocked", errors=[reason])
            row = self.store.scan_by_id(scan_id)
            run_id = int(row["id"]) if row else self.store.begin_scan(scan_id=scan_id, source_kind=source_kind, scope_kind=scope_kind, scope_ref=scope_ref, native_generation=scan_generation, generation_id=str(generation_id or scan_id))
            final_status = str(status or "completed").casefold(); errors = [str(e) for e in (scan_errors or [])]; stats = scan_stats or {}
            row = self.store.scan_by_id(scan_id) or {}
            try:
                historical_errors = json.loads(row.get("errors") or "[]")
            except (TypeError, json.JSONDecodeError):
                historical_errors = []
            if not isinstance(historical_errors, list):
                historical_errors = []
            errors = list(dict.fromkeys([str(e) for e in historical_errors] + errors))
            complete = final_status in {"completed","complete","empty_complete"} and not errors and scan_generation is not None
            generation = int(scan_generation) if scan_generation is not None else None
            reconciled = 0
            if complete:
                reconciled = self.store.reconcile_scope_generation(tree_uri, source_kind=source_kind, scope_kind=scope_kind, scope_ref=scope_ref, native_generation=generation, complete=True)
                for anime_id in sorted(self.store.generation_anime_ids(source_kind=source_kind, scope_kind=scope_kind, scope_ref=scope_ref, native_generation=generation)): self.artwork.reindex_entity(anime_id)
                reconciliation = self.reconcile_existing_library(dry_run=False) if enforce_library_source else None
                self.store.update_folder_status(tree_uri, "granted")
            row = self.store.scan_by_id(scan_id) or {}
            result = ScanResult(catalog=[], scan_id=scan_id, status=final_status)
            for attr,col in (("files","files"),("videos","videos"),("new","new_files"),("updated","updated_files"),("unchanged","unchanged_files"),("duplicates","duplicate_files"),("ignored","ignored_files"),("unknown","unknown_files")): setattr(result,attr,int(row.get(col) or stats.get(col,0) or 0))
            result.reconciled = reconciled; result.errors = errors
            summary = dict(result.__dict__); summary.update(discovered=int(row.get("discovered") or result.files), processed=int(row.get("processed") or result.files), inserted=int(row.get("inserted_files") or result.new), removed=reconciled, elapsed_ms=int(row.get("elapsed_ms") or 0))
            self.store.finish_scan(run_id, summary)
            result.catalog = self.store.catalog(); result.animes = len(result.catalog); result.episodes = sum(len(s["episodes"]) for a in result.catalog for s in a["seasons"]) + sum(len(a.get("media_files",[])) for a in result.catalog)
            return result
    def scan(self, on_status=lambda _ : None):
        with self._scan_lock:
            scan_id = str(uuid.uuid4())
            run_id = self.store.begin_scan(scan_id=scan_id, source_kind="filesystem", scope_kind="global", scope_ref=None, generation_id=scan_id)
            result = ScanResult(catalog=[], scan_id=scan_id)
            try:
                parsed = []
                seen_by_source = {}
                source_policies = {}
                folders = self.store.folders()
                result.folders = len(folders)
                on_status("Verificando pastas autorizadas…")
                for folder in folders:
                    reference = folder["path"]
                    if folder["kind"] == "saf" or reference.startswith("content://"):
                        result.errors.append(f"{folder['name']}: aguardando scanner Android")
                        continue
                    if not os.path.isdir(reference):
                        error = "Pasta indisponível, removida ou sem autorização para este processo."
                        self.store.update_folder_status(reference, "revoked", error)
                        self.store.mark_source_unavailable(reference, "folder_unavailable")
                        result.errors.append(f"{folder['name']}: {error}")
                        continue
                    self.store.update_folder_status(reference, "granted")
                    self.store.restore_source(reference)
                    source_policy = self._library_source_policy(reference, "filesystem", reference)
                    if not source_policy.get("ok"):
                        result.errors.append(f"{folder['name']}: fonte rejeitada ({source_policy.get('reason')}).")
                        continue
                    source_policies[reference] = source_policy
                    on_status(f"Encontrando vídeos em {folder['name']}…")
                    try:
                        seen = []
                        walk_errors = []
                        def _on_walk_error(error):
                            walk_errors.append(error)
                            result.errors.append(f"{folder['name']}: diretório não pôde ser lido: {getattr(error, 'filename', error)}")
                        for root, dirnames, files in os.walk(reference, onerror=_on_walk_error):
                            names_casefold = {str(name).casefold() for name in files}
                            if ".nomedia" in names_casefold:
                                result.nomedia_directories += 1
                                result.nomedia_files += 1
                                result.ignored += sum(1 for name in files if str(name).casefold() != ".nomedia")
                                dirnames[:] = []
                                continue

                            allowed_dirs = []
                            for dirname in dirnames:
                                child = os.path.join(root, dirname)
                                try:
                                    has_nomedia = os.path.isfile(os.path.join(child, ".nomedia"))
                                except OSError as exc:
                                    walk_errors.append(exc)
                                    result.errors.append(f"{folder['name']}: não foi possível verificar {dirname}: {exc}")
                                    has_nomedia = False
                                if has_nomedia:
                                    result.nomedia_directories += 1
                                    result.nomedia_files += 1
                                    continue
                                allowed_dirs.append(dirname)
                            dirnames[:] = allowed_dirs

                            for name in files:
                                result.files += 1
                                if str(name).casefold() == ".nomedia":
                                    result.ignored += 1
                                    continue
                                if os.path.splitext(name)[1].lower() not in VIDEO_EXTENSIONS:
                                    result.ignored += 1
                                    continue
                                path = os.path.join(root, name)
                                try:
                                    stat = os.stat(path)
                                except OSError as exc:
                                    # An inaccessible file is not evidence of removal.
                                    # Block reconciliation for this source until a complete
                                    # scan can establish absence safely.
                                    walk_errors.append(exc)
                                    result.errors.append(f"{folder['name']}: não foi possível acessar {name}: {exc}")
                                    result.ignored += 1
                                    continue
                                seen.append(path)
                                parsed.append(({
                                    "uri": path,
                                    "name": name,
                                    "relativePath": os.path.relpath(path, reference).replace(os.sep, "/"),
                                    "mimeType": None,
                                    "size": stat.st_size,
                                    "modifiedAt": stat.st_mtime_ns // 1_000_000,
                                }, reference, "filesystem"))
                                result.videos += 1
                        seen_by_source[reference] = set(seen)
                        if not walk_errors:
                            self.store.reconcile_missing(reference, seen, source_kind="filesystem", scope_kind="source", scope_ref=reference, complete=True)
                        else:
                            self.store.update_folder_status(reference, "granted", "Scan parcial; reconciliação de ausência não aplicada.")
                    except OSError as exc:
                        result.errors.append(f"{folder['name']}: erro ao ler pasta: {exc}")
                metadata = {}
                affected_anime_ids = set()
                for document, source_folder, source_kind in parsed:
                    self._record_document(
                        document=document,
                        source_folder=source_folder,
                        source_kind=source_kind,
                        metadata=metadata,
                        result=result,
                        affected_anime_ids=affected_anime_ids,
                        known_paths=seen_by_source.get(source_folder),
                        source_policy=source_policies.get(source_folder),
                        scope_kind="source",
                        scope_ref=source_folder,
                    )
                for anime_id in sorted(affected_anime_ids):
                    self.artwork.reindex_entity(anime_id)
                result.catalog = self.store.catalog()
                result.animes = len(result.catalog)
                result.episodes = sum(len(s["episodes"]) for a in result.catalog for s in a["seasons"]) + sum(len(a.get("media_files", [])) for a in result.catalog)
                if result.errors:
                    result.status = "partial"
                self.store.finish_scan(run_id, result.__dict__)
                return result
            except Exception as exc:
                result.errors.append(f"Falha geral no scan: {exc}")
                result.status = "error"
                self.store.finish_scan(run_id, result.__dict__)
                raise

    def _reconciliation_source_descriptors(self):
        descriptors = []
        for folder in self.store.folders():
            kind = str(folder.get("kind") or "").strip().casefold()
            reference = str(folder.get("path") or "").strip()
            authorization = str(folder.get("authorization") or "").strip().casefold()
            if not reference or kind in {"mediastore", "broad_storage", "broad-storage"}:
                continue

            if kind == "saf":
                tree_document_id = (
                    str(folder.get("saf_document_id") or "").strip()
                    or self._saf_tree_document_id(reference)
                )
                authority = (
                    str(folder.get("saf_authority") or "").strip()
                    or str(urlparse(reference).netloc or "").strip()
                )
                shared_prefix = identity_from_document(
                    reference,
                    "",
                    str(folder.get("saf_volume_id") or "").strip() or None,
                    reference,
                )
                descriptors.append({
                    "kind": "saf",
                    "reference": reference,
                    "identity": str(folder.get("saf_identity") or "").strip() or saf_source_identity(reference),
                    "tree_document_id": tree_document_id,
                    "authority": authority,
                    "shared_prefix": shared_prefix,
                    "available": authorization == "granted",
                })
                continue

            if kind in {"filesystem", "path"}:
                root = os.path.realpath(reference)
                descriptors.append({
                    "kind": "filesystem",
                    "reference": reference,
                    "root": root,
                    "available": (
                        authorization == "granted"
                        and os.path.isdir(reference)
                        and os.access(reference, os.R_OK)
                    ),
                })
        return descriptors

    @staticmethod
    def _episode_path_for_filesystem(uri):
        raw = str(uri or "").strip()
        if raw.casefold().startswith("file://"):
            try:
                return unquote(urlparse(raw).path)
            except ValueError:
                return ""
        return raw if os.path.isabs(raw) else ""

    def _episode_matches_reconciliation_source(self, episode, source):
        if source["kind"] == "filesystem":
            candidate = self._episode_path_for_filesystem(episode.get("path"))
            if candidate and self._filesystem_path_within_source(candidate, source["root"]):
                return "valid" if os.path.isfile(os.path.realpath(candidate)) else "removed"

            relative = str(episode.get("relative_path") or "").strip().strip("/").replace("\\", "/")
            if relative and not os.path.isabs(relative):
                candidate = os.path.realpath(os.path.join(source["root"], relative))
                if self._filesystem_path_within_source(candidate, source["root"]):
                    return "valid" if os.path.isfile(candidate) else "removed"

            identity = str(episode.get("media_identity") or "").strip()
            if identity.startswith("file:"):
                candidate = identity[5:]
                if self._filesystem_path_within_source(candidate, source["root"]):
                    return "valid" if os.path.isfile(os.path.realpath(candidate)) else "removed"
            return None

        uri = str(episode.get("path") or "").strip()
        document_id = self._saf_document_id_from_uri(uri) if uri.startswith("content://") else None
        authority = str(urlparse(uri).netloc or "").strip() if uri.startswith("content://") else ""
        if (
            document_id
            and source.get("tree_document_id")
            and authority.casefold() == str(source.get("authority") or "").casefold()
            and self._saf_document_id_within_tree(source["tree_document_id"], document_id)
        ):
            return "valid"

        identity = str(episode.get("media_identity") or "").strip()
        shared_prefix = str(source.get("shared_prefix") or "").strip()
        if identity and shared_prefix and (
            identity == shared_prefix or identity.startswith(shared_prefix + "/")
        ):
            return "valid"
        return None

    def reconcile_existing_library(self, *, dry_run=False):
        """Remove only legacy episode rows proven outside every configured source."""
        logger.info("[RECONCILIATION_STARTED] dry_run=%s", bool(dry_run))
        descriptors = self._reconciliation_source_descriptors()
        available = [source for source in descriptors if source.get("available")]
        unavailable = [source for source in descriptors if not source.get("available")]

        report = {
            "dry_run": bool(dry_run),
            "total": 0,
            "preserved": 0,
            "removed": 0,
            "inaccessible": 0,
            "duplicates": 0,
            "unknown": 0,
            "status": "completed",
        }

        if not descriptors or not available:
            report["status"] = "skipped_no_reliable_sources"
            logger.info(
                "[RECONCILIATION_SKIPPED] reason=no_reliable_sources dry_run=%s",
                report["dry_run"],
            )
            logger.info(
                "[RECONCILIATION_COMPLETED] status=%s total=0 preserved=0 removed=0 inaccessible=0 duplicates=0 unknown=0 dry_run=%s",
                report["status"], report["dry_run"],
            )
            return report

        with self.store._conn() as con:
            rows = [dict(row) for row in con.execute("SELECT * FROM episodes ORDER BY id").fetchall()]
        report["total"] = len(rows)

        remove_ids = []
        duplicate_merges = []

        for episode in rows:
            states = set()

            for source in available:
                state = self._episode_matches_reconciliation_source(episode, source)
                if state:
                    states.add(state)

            for source in unavailable:
                state = self._episode_matches_reconciliation_source(episode, source)
                if state:
                    states.add("inaccessible")

            if "valid" in states:
                report["preserved"] += 1
                continue
            if "inaccessible" in states or "removed" in states:
                report["inaccessible"] += 1
                continue

            identity = str(episode.get("media_identity") or "").strip()
            preserved_duplicate = None
            if identity:
                for candidate in rows:
                    if candidate["id"] == episode["id"] or candidate["id"] in remove_ids:
                        continue
                    if str(candidate.get("media_identity") or "").strip() != identity:
                        continue
                    candidate_states = {
                        self._episode_matches_reconciliation_source(candidate, source)
                        for source in available
                    }
                    if "valid" in candidate_states:
                        preserved_duplicate = int(candidate["id"])
                        break

            if preserved_duplicate is not None:
                duplicate_merges.append({
                    "source_id": int(episode["id"]),
                    "target_id": preserved_duplicate,
                })
                report["duplicates"] += 1
                report["removed"] += 1
                continue

            path = str(episode.get("path") or "").strip()
            identity = str(episode.get("media_identity") or "").strip()

            provable_external = False
            if path.casefold().startswith("file://") or os.path.isabs(path):
                provable_external = True
            elif path.startswith("content://"):
                authority = str(urlparse(path).netloc or "").strip().casefold()
                # External-storage DocumentProvider URIs expose a comparable
                # document scope. Generic MediaStore/cloud content URIs do not.
                provable_external = authority == "com.android.externalstorage.documents"
            if identity.startswith("shared:") or identity.startswith("file:"):
                provable_external = True
            elif path.startswith("content://"):
                authority = str(urlparse(path).netloc or "").strip().casefold()
                configured_saf_authorities = {
                    str(source.get("authority") or "").strip().casefold()
                    for source in available
                    if source.get("kind") == "saf" and source.get("authority")
                }
                if (
                    authority
                    and configured_saf_authorities
                    and authority not in configured_saf_authorities
                    and authority not in {"com.android.providers.media.documents"}
                ):
                    # A content URI from another document provider cannot belong
                    # to any configured SAF library root.
                    provable_external = True

            if provable_external:
                remove_ids.append(int(episode["id"]))
                report["removed"] += 1
            else:
                report["unknown"] += 1

        logger.info(
            "[RECONCILIATION_CLASSIFIED] total=%s preserved=%s removed=%s inaccessible=%s duplicates=%s unknown=%s dry_run=%s",
            report["total"], report["preserved"], report["removed"],
            report["inaccessible"], report["duplicates"], report["unknown"], report["dry_run"],
        )
        if not dry_run and (remove_ids or duplicate_merges):
            result = self.store.apply_library_reconciliation(remove_ids, duplicate_merges)
            report["removed"] = int(result.get("removed") or report["removed"])
            report["duplicates"] = max(
                report["duplicates"],
                int(result.get("duplicates_merged") or 0),
            )
            logger.info(
                "[RECONCILIATION_REMOVED] removed=%s duplicates=%s",
                report["removed"], report["duplicates"],
            )

        logger.info(
            "[RECONCILIATION_COMPLETED] status=%s total=%s preserved=%s removed=%s inaccessible=%s duplicates=%s unknown=%s dry_run=%s",
            report["status"], report["total"], report["preserved"],
            report["removed"], report["inaccessible"],
            report["duplicates"], report["unknown"], report["dry_run"],
        )
        return report

    def ingest_documents(self, tree_uri: str, documents: list[dict], on_status=lambda _: None, *,
                         folder_name=None, scan_errors=None, scan_stats=None, source_kind="saf",
                         scan_id=None, scope_kind="global", scope_ref=None, scan_generation=None,
                         scope_scans=None, enforce_library_source=False):
        """Index one native source without destructive reconciliation on partial scans."""
        with self._scan_lock:
            scan_id = scan_id or str(uuid.uuid4())
            scope_ref = scope_ref or tree_uri
            source_policy = self._library_source_policy(tree_uri, source_kind, scope_ref) if enforce_library_source else None
            if enforce_library_source and not source_policy.get("ok"):
                reason = str(source_policy.get("reason") or "SOURCE_NOT_CONFIGURED")
                logger.warning("[LIBRARY_SOURCE] SCAN_SOURCE_REJECTED source=%s scope_ref=%s reason=%s", source_kind, scope_ref, reason)
                return self.store.catalog()
            previous = self.store.scan_by_id(scan_id)
            if previous and previous.get("status") in {"completed", "partial", "cancelled", "error", "failed"}:
                return self.store.catalog()
            try:
                native_generation = int(scan_generation) if scan_generation is not None else None
            except (TypeError, ValueError):
                native_generation = None
            if native_generation is not None and native_generation > 0:
                latest = self.store.latest_completed_native_generation(source_kind, scope_kind, scope_ref or tree_uri)
                if latest is not None and native_generation < latest:
                    logger.info("Ignoring stale native scan generation %s < %s", native_generation, latest)
                    return self.store.catalog()
            scan_scopes = [scope for scope in (scope_scans or []) if isinstance(scope, dict)]
            scan_errors = list(scan_errors or [])
            scan_stats = scan_stats or {}
            generation_id = str(scan_stats.get("generationId") or scan_stats.get("generation_id") or scan_id)
            run_id = self.store.begin_scan(
                scan_id=scan_id, source_kind=source_kind, scope_kind=scope_kind,
                scope_ref=scope_ref or tree_uri, native_generation=native_generation,
                generation_id=generation_id,
            )
            result = ScanResult(catalog=[], scan_id=scan_id)
            native_scan_state = str(scan_stats.get("status") or scan_stats.get("generationStatus") or "").casefold()
            partial_scan = bool(
                scan_stats.get("partial")
                or scan_stats.get("cancelled")
                or native_scan_state in {"partial", "failed", "cancelled", "canceled", "error", "unavailable", "revoked"}
            )
            try:
                if not enforce_library_source:
                    self.store.add_folder(
                        tree_uri,
                        name=folder_name or tree_uri.rsplit("/", 1)[-1],
                        kind=source_kind,
                        authorization="granted",
                        account_id=self.store.account().get("id"),
                    )
                metadata = {}
                affected_anime_ids = set()
                seen = set()
                trusted_scope_defs = {}
                trusted_scope_seen = {}
                for scope in scan_scopes:
                    status = str(scope.get("status") or ("completed" if scope.get("complete") else "partial")).casefold()
                    complete_scope = bool(scope.get("complete")) and status in {"completed", "complete", "empty_complete"}
                    skind = str(scope.get("scopeKind") or ("volume" if scope.get("volumeId") else scope_kind)).strip() or scope_kind
                    sref = str(scope.get("scopeRef") or scope.get("volumeId") or "").strip()
                    if complete_scope and sref:
                        key = (skind, sref)
                        trusted_scope_defs[key] = scope
                        trusted_scope_seen[key] = set()
                for document in documents or []:
                    uri = document.get("uri") if isinstance(document, dict) else None
                    if uri in seen:
                        result.duplicates += 1
                        continue
                    if isinstance(uri, str) and uri:
                        seen.add(uri)
                    result.files += 1
                    accepted = self._record_document(
                        document=document if isinstance(document, dict) else {},
                        source_folder=tree_uri,
                        source_kind=source_kind,
                        metadata=metadata,
                        result=result,
                        affected_anime_ids=affected_anime_ids,
                        known_paths=seen,
                        scope_kind=scope_kind,
                        scope_ref=scope_ref,
                        native_generation=native_generation,
                        source_policy=source_policy,
                    )
                    if accepted:
                        result.videos += 1
                        volume_id = str((document or {}).get("volumeId") or "").strip() if isinstance(document, dict) else ""
                        physical = self.store.physical_row(uri)
                        if physical:
                            for scope in scan_scopes:
                                skind = str(scope.get("scopeKind") or "").strip().casefold()
                                sref = str(scope.get("scopeRef") or scope.get("volumeId") or "").strip()
                                if not skind or not sref:
                                    continue
                                if skind == "volume" and sref == volume_id:
                                    self.store.record_observation(
                                        physical["id"],
                                        source_kind=source_kind,
                                        scope_kind=skind,
                                        scope_ref=sref,
                                        uri=uri,
                                        volume_id=volume_id,
                                        native_generation=native_generation,
                                        fingerprint=(document or {}).get("nativeFingerprint") if isinstance(document, dict) else None,
                                    )
                                    if scope.get("complete") and str(scope.get("status") or "completed").casefold() in {"completed", "complete", "empty_complete"}:
                                        trusted_scope_seen.setdefault((skind, sref), set()).add(uri)

                for anime_id in sorted(affected_anime_ids):
                    self.artwork.reindex_entity(anime_id)

                result.errors.extend(str(error) for error in scan_errors)
                if scan_stats.get("cancelled") or native_scan_state in {"cancelled", "canceled"}:
                    result.status = "cancelled"
                elif native_scan_state == "revoked":
                    result.status = "revoked"
                elif scan_errors or partial_scan:
                    result.status = "partial" if native_scan_state not in {"failed", "error"} else "error"
                elif native_scan_state == "empty_complete":
                    result.status = "empty_complete"
                if not scan_errors and not partial_scan and trusted_scope_defs:
                    for (skind, sref), _scope in trusted_scope_defs.items():
                        self.store.reconcile_scope(
                            tree_uri,
                            list(trusted_scope_seen.get((skind, sref), set())),
                            source_kind=source_kind,
                            scope_kind=skind,
                            scope_ref=sref,
                            complete=True,
                        )
                        result.reconciled += 1
                    self.store.update_folder_status(tree_uri, "granted")
                elif not scan_errors and not partial_scan:
                    self.store.reconcile_scope(
                        tree_uri,
                        list(seen),
                        source_kind=source_kind,
                        scope_kind=scope_kind,
                        scope_ref=scope_ref,
                        complete=True,
                    )
                    result.reconciled = 1
                    self.store.update_folder_status(tree_uri, "granted")
                else:
                    self.store.update_folder_status(tree_uri, "granted", "; ".join(map(str, scan_errors)))
                if enforce_library_source and not scan_errors and not partial_scan:
                    self.reconcile_existing_library(dry_run=False)
                catalog = self.store.catalog()
                result.catalog = catalog
                result.folders = 1
                if result.errors and result.status not in {"cancelled", "error"}:
                    result.status = "partial"
                    self.store.update_folder_status(tree_uri, "granted", "; ".join(result.errors[-10:]))
                result.files = int(scan_stats.get("files") or result.files)
                result.videos = int(scan_stats.get("videos") or result.videos)
                result.animes = len(catalog)
                result.episodes = sum(len(season["episodes"]) for anime in catalog for season in anime["seasons"]) + sum(len(anime.get("media_files", [])) for anime in catalog)
                self.store.finish_scan(run_id, result.__dict__)
                return catalog
            except Exception as exc:
                result.errors.append(f"Falha ao indexar a fonte: {exc}")
                result.status = "error"
                self.store.finish_scan(run_id, result.__dict__)
                raise

    def ingest_native_volume_change(self, payload):
        """Persist authoritative native volume availability without touching the catalog."""
        if not isinstance(payload, dict):
            raise ValueError("Native volume event must contain an object payload.")
        current = payload.get("current") or []
        if not isinstance(current, list):
            raise ValueError("Native volume snapshot must be a list.")
        normalized = []
        for item in current:
            if not isinstance(item, dict):
                continue
            volume_id = str(item.get("volumeId") or "").strip()
            if not volume_id:
                continue
            normalized.append({
                "volumeId": volume_id,
                "uuid": str(item.get("uuid") or ""),
                "state": str(item.get("state") or "unknown"),
                "removable": bool(item.get("removable")),
                "emulated": bool(item.get("emulated")),
                "primary": bool(item.get("primary")),
                "directory": str(item.get("directory") or ""),
                "description": str(item.get("description") or ""),
            })
        removed = payload.get("removed") or []
        enriched = {
            "current": normalized,
            "removed": removed,
            "eventTimestamp": payload.get("eventTimestamp") or payload.get("timestamp") or payload.get("observedAt"),
        }
        return self.store.record_native_volume_change(enriched)

    def resolve_match(self, lookup_title, anilist_id, *, local_anime_id=None, request_id=None):
        """Apply a manual AniList match to the existing local anime entity."""
        request_id = str(request_id or uuid.uuid4())
        local_row = self.store.anime_metadata_by_id(local_anime_id) if local_anime_id else None
        owner_id = int(local_row["id"]) if local_row else self.store.resolve_local_anime_owner(lookup_title=lookup_title)
        local_row = self.store.anime_metadata_by_id(owner_id) if owner_id else self.store.anime_metadata(lookup_title)
        local_lookup = str(local_row["lookup_title"]) if local_row else str(lookup_title)
        pending = next(
            (item for item in self.store.pending_matches() if item["lookup_title"] == local_lookup),
            None,
        )
        display_title = (
            pending["display_title"]
            if pending
            else (local_row or {}).get("title") or lookup_title
        )
        logger.info(
            "METADATA_ACTION_START requestId=%s animeId=%s lookupTitle=%s anilistId=%s screen=manual_match",
            request_id, owner_id or "-", local_lookup, anilist_id,
        )
        if not pending and not self.store.anilist_match(local_lookup):
            raise ValueError("Este candidato não está mais pendente.")
        logger.info(
            "METADATA_ACTION_LOOKUP requestId=%s animeId=%s lookupTitle=%s anilistId=%s screen=manual_match",
            request_id, owner_id or "-", local_lookup, anilist_id,
        )
        media = self.anilist.by_id(anilist_id)
        if not media or media.get("id") != anilist_id:
            raise ValueError("O anime escolhido não está disponível no AniList.")
        metadata = self.anilist.metadata_from_media(display_title, media, localize_description=True)
        metadata["anilist_id"] = anilist_id
        logger.info(
            "METADATA_ACTION_DB_WRITE requestId=%s animeId=%s lookupTitle=%s anilistId=%s screen=manual_match",
            request_id, owner_id or "-", local_lookup, anilist_id,
        )
        self.store.upsert_anime(
            local_lookup,
            metadata,
            source="anilist",
            confidence="high",
            status="available",
            fetched_at=time.time(),
            local_anime_id=owner_id,
        )
        self.store.set_anilist_match(
            local_lookup,
            anilist_id,
            status="manual",
            score=1.0,
            margin=1.0,
            manual=True,
        )
        self.store.resolve_match(local_lookup, anilist_id)
        row = self.store.anime_metadata_by_id(owner_id) if owner_id else self.store.anime_metadata(local_lookup)
        if row:
            self._schedule_description_localization(
                local_lookup,
                row.get("description_original") or row.get("description") or "",
                local_anime_id=row.get("id"),
                request_id=request_id,
            )
            self._sync_genres(row["id"], row, source="anilist")
            self.artwork.sync_anime_metadata(row["id"], row)
        logger.info(
            "METADATA_ACTION_UI_COMMIT requestId=%s animeId=%s lookupTitle=%s anilistId=%s screen=manual_match",
            request_id, owner_id or "-", local_lookup, anilist_id,
        )
        logger.info("ANILIST_MATCH_MANUAL title=%s id=%s", display_title, anilist_id)
        return row or metadata


    def unlink_match(self, lookup_title):
        self.store.clear_anilist_match(lookup_title)
        logger.info("ANILIST_MATCH_UNLINK title=%s", lookup_title)
        return True
    def catalog(self, favorites_only=False):
        return self.genre_registry.enrich_catalog(self.store.catalog(favorites_only))

    def catalog_by_ids(self, anime_ids):
        """Return a bounded projection for selected anime IDs using the canonical store."""
        return self.genre_registry.enrich_catalog(self.store.catalog(anime_ids=anime_ids))

    def catalog_page(self, **filters):
        """Return a bounded local catalog page while preserving GenreRegistry enrichment."""
        result = self.store.catalog_page(**filters)
        result["items"] = self.genre_registry.enrich_catalog(result.get("items") or [])
        return result
    def create_backup(self, destination=None): return self.store.create_backup(destination)

    def restore_backup(self, backup_path=None): return self.store.restore_backup(backup_path)


    def collector_journey(self):
        """Rebuild the local Collector Journey from canonical SQLite state."""
        active_title = self.store.get_preference("collector.active_title")
        journey = build_collector_journey(
            self.store.collector_snapshot(),
            active_title=active_title,
        )
        if active_title != journey.get("active_title"):
            if journey.get("active_title"):
                self.store.set_preference("collector.active_title", journey["active_title"])
            else:
                self.store.remove_preference("collector.active_title")
        return journey

    def set_collector_active_title(self, title_id):
        """Persist only the user's selected unlocked title in the existing preferences table."""
        journey = self.collector_journey()
        normalized = set_active_title(journey, str(title_id or ""))
        if normalized is None:
            raise ValueError("Título do colecionador indisponível.")
        self.store.set_preference("collector.active_title", normalized)
        journey["active_title"] = normalized
        journey["active_title_label"] = next(
            item["title"] for item in journey["titles"] if item["id"] == normalized
        )
        return journey

    def clear_collector_active_title(self):
        self.store.remove_preference("collector.active_title")
        return self.collector_journey()

    def gacha_pick(self, *, filters=None, exclude_id=None, episode=False):
        """Draw from the existing local catalog; never changes consumption state."""
        import random

        filters = dict(filters or {})
        item = self.store.random_catalog_item(exclude_id=exclude_id, **filters)
        if not item:
            return None

        result = {
            "anime": item,
            "episode": None,
            "duration_label": None,
        }
        if episode:
            rows = self.store.marathon_episodes(item["id"])
            if rows:
                result["episode"] = random.SystemRandom().choice(rows)
                result["duration_label"] = format_duration(result["episode"].get("duration"))
            else:
                # Keep anime-level Gacha useful even when this item has no regular
                # episode rows; callers can display that the episode pool is empty.
                return {
                    "anime": item,
                    "episode": None,
                    "duration_label": None,
                }
        return result

    def library_timeline(self):
        return timeline_groups(self.store.timeline_items())

    def duration_anomaly_report(self, anime_id=None):
        return duration_anomalies(self.store.duration_observations(anime_id=anime_id))

    def marathon(self, anime_id, *, current_path=None):
        return marathon_plan(
            self.store.marathon_episodes(anime_id),
            current_path=current_path,
        )

    def media_center_home(self, limit=12, *, catalog=None, continue_limit=None):
        """Build only the Home projections that remain visible."""
        if catalog is None:
            sections = self.store.home_sections(limit=limit, continue_limit=continue_limit)
            enrich_keys = ("favorites", "pinned", "movies")
            enrich_items = []
            for key in enrich_keys:
                enrich_items.extend(sections.get(key) or [])
            if enrich_items:
                self.genre_registry.enrich_catalog(enrich_items)
            return sections

        catalog = catalog
        continue_items = self.store.continue_watching(limit=limit if continue_limit is None else continue_limit)
        favorites = [a for a in catalog if a.get("favorite")]
        pinned = [a for a in catalog if a.get("is_pinned")]
        movies = [a for a in catalog if a.get("media_kind") == "movie"]
        return {
            "continue_watching": continue_items,
            "favorites": favorites[:limit],
            "pinned": pinned[:limit],
            "movies": movies[:limit],
        }

    def browse_catalog_page(self, **filters):
        return self.catalog_page(**filters)

    def continue_watching(self, limit=12): return self.store.continue_watching(limit)
    def playback_history(self, limit=50): return self.store.playback_history(limit)
    def playback_target(self, anime_id): return self.store.playback_target(anime_id)
    def next_episode(self, path): return self.store.next_episode(path)
    def previous_episode(self, path): return self.store.previous_episode(path)
    def player_navigation(self, path): return self.store.player_navigation(path)
    def set_user_tags(self, anime_id, tags): return self.store.set_user_tags(anime_id, tags)
    def toggle_pinned(self, anime_id): return self.store.toggle_pinned(anime_id)
    def set_personal_note(self, anime_id, note): return self.store.set_personal_note(anime_id, note)
    def library_statistics(self): return self.store.library_statistics()
    def last_scan(self): return self.store.last_scan()

    def clear_artwork_cache(self):
        """Clear managed external artwork without touching the local library."""
        return self.artwork.clear()

    def clear_anilist_cache(self):
        """Compatibility facade for Settings: clear refreshable artwork safely."""
        removed = self.clear_artwork_cache()
        # legacy stored AniList covers directly in store.cache_dir.
        # Keep that legacy cache migration-safe: only files in the dedicated
        # covers root are removed; the new artwork/ subdirectory is owned by
        # ArtworkEngine and was already cleared above.
        legacy_removed = 0
        try:
            for entry in os.scandir(self.store.cache_dir):
                if entry.is_file():
                    os.unlink(entry.path)
                    legacy_removed += 1
        except OSError as exc:
            raise RuntimeError("Não foi possível limpar o cache de capas.") from exc
        self.store.clear_anilist_metadata_cache()
        return removed + legacy_removed
    @staticmethod
    def organize_summary(catalog):
        """Build Organize categories from the same search/filter policy used by collections.

        ``states`` remains the legacy four-category API used by existing callers.
        ``collections`` exposes the expanded Organize 2.0 categories without
        breaking that public compatibility surface.
        """
        catalog = list(catalog or [])
        collection_names = (
            "Todos", "Favoritos", "Fixados", "Assistidos",
            "Não assistidos", "Em andamento", "Concluídos",
        )
        collections = [
            {
                "name": name,
                "count": len(LibrarySearchEngine.search(catalog, state=name)),
            }
            for name in collection_names
        ]
        legacy_order = ("Todos", "Favoritos", "Em andamento", "Concluídos")
        states = sorted(
            (item for item in collections if item["name"] in legacy_order),
            key=lambda item: legacy_order.index(item["name"]),
        )

        genres = {}
        for anime in catalog:
            cover = (anime.get("meta") or {}).get("cover_cache") or (anime.get("meta") or {}).get("cover_url")
            seen_genres = set()
            for genre in anime.get("genres") or []:
                label = str(genre or "").strip()
                if not label:
                    continue
                key = normalize_text(label)
                if not key or key in seen_genres:
                    continue
                seen_genres.add(key)
                entry = genres.setdefault(
                    key,
                    {"name": label, "count": 0, "cover": cover or ""},
                )
                entry["count"] += 1
                if not entry["cover"] and cover:
                    entry["cover"] = cover

        return {
            "genres": sorted(genres.values(), key=lambda item: item["name"].casefold()),
            "states": states,
            "collections": collections,
        }
    @staticmethod
    def browse_catalog(catalog, query="", state="Todos", genre="Todos", sort="Mais recentes", tag="Todos",
                       *, media_type="Todos", season=None, episode_type="Todos", source_kind="Todos",
                       availability="Todos", metadata="Todos", artwork="Todos"):
        """Search/filter/sort the already projected local catalog.

        This compatibility facade keeps Home/Organize callers stable while the
        matching policy lives in the reusable local SearchFilterSort engine.
        """
        genre_id = None
        if genre not in ("Todos", "", None):
            wanted = str(genre)
            if any(wanted in (item.get("genre_ids") or []) for item in catalog):
                genre_id = wanted
        return LibrarySearchEngine.search(
            catalog,
            query=query,
            genre_id=genre_id,
            state=state,
            genre=genre,
            sort=sort,
            tag=tag,
            media_type=media_type,
            season=season,
            episode_type=episode_type,
            source_kind=source_kind,
            availability=availability,
            metadata=metadata,
            artwork=artwork,
        )

    def search_options(self, catalog=None):
        if catalog is None:
            options = self.store.search_options()
        else:
            options = LibrarySearchEngine.options(catalog)
        options["genres"] = [item["name"] for item in self.genre_registry.list_all(include_unused=False)]
        return options

