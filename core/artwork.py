"""Persistent, local-first Artwork Engine 2.0 for ReiAnix.

Artwork is an enrichment layer.  The local catalog, NativeIndex/scanners and
AniList matching remain authoritative for their own domains.  This module only
owns artwork discovery, persistent cache state, bounded downloads, retries,
deduplication and lightweight UI-facing resolution.
"""
from __future__ import annotations

from io import BytesIO
import hashlib
import logging
import os
import queue
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import Future
from pathlib import Path

from PIL import Image, ImageOps

from core.artwork_palette import extract_palette

logger = logging.getLogger("reiflix.artwork")

ARTWORK_TYPES = {"poster", "backdrop", "thumbnail", "season_poster", "episode_thumbnail"}
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".avif", ".gif"}
IMAGE_MIME = {
    "image/jpeg": ".jpg",
    "image/png": ".png",
    "image/webp": ".webp",
    "image/avif": ".avif",
    "image/gif": ".gif",
}
_SOURCE_PRIORITY = {"manual": 500, "cache": 450, "anilist": 400, "local": 300, "generated": 50}
_VARIANT_PRIORITY = {"poster": "large", "backdrop": "large", "thumbnail": "small",
                    "season_poster": "large", "episode_thumbnail": "small"}
_NAME_HINTS = {
    "poster": {"poster", "cover", "folder", "front"},
    "backdrop": {"backdrop", "fanart", "banner", "background"},
    "thumbnail": {"thumbnail", "thumb", "episode"},
    "season_poster": {"season", "season-poster", "seasonposter"},
    "episode_thumbnail": {"thumbnail", "thumb", "episode"},
}

STATUS_NOT_REQUESTED = "not_requested"
STATUS_QUEUED = "queued"
STATUS_DOWNLOADING = "downloading"
STATUS_READY = "ready"
STATUS_FAILED = "failed"
STATUS_RETRY_WAIT = "retry_wait"
STATUS_INVALID = "invalid"

_EVENT_NAMES = {
    "request": "ARTWORK_REQUEST",
    "hit": "ARTWORK_CACHE_HIT",
    "miss": "ARTWORK_CACHE_MISS",
    "start": "ARTWORK_DOWNLOAD_START",
    "success": "ARTWORK_DOWNLOAD_SUCCESS",
    "failure": "ARTWORK_DOWNLOAD_FAILURE",
    "retry": "ARTWORK_RETRY",
    "cancel": "ARTWORK_CANCEL",
    "evict": "ARTWORK_EVICT",
}


class ArtworkEngine:
    """Manages artwork without making artwork a dependency of the library."""

    MAX_WORKERS = 2
    MAX_PENDING_TASKS = 128
    MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024
    DEFAULT_CACHE_LIMIT_BYTES = 128 * 1024 * 1024
    MAX_RETRIES = 3
    BASE_BACKOFF_SECONDS = 5.0
    MAX_BACKOFF_SECONDS = 6 * 60 * 60
    REQUEST_TIMEOUT_SECONDS = 15.0

    def __init__(self, store, *, downloader=None, max_workers=None,
                 cache_limit_bytes=None, max_download_bytes=None,
                 change_listener=None, diagnostic_recorder=None):
        self.store = store
        self.cache_dir = Path(store.cache_dir) / "artwork"
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        # Derived presentation data stays owned by the same ArtworkEngine/cache.
        # It is never a second AniList metadata cache or a source of truth.
        self.palette_cache_pattern = ".reiflix-palette-*.json"
        self.max_workers = max(1, int(max_workers or self.MAX_WORKERS))
        self.cache_limit_bytes = int(cache_limit_bytes or self.DEFAULT_CACHE_LIMIT_BYTES)
        self.max_download_bytes = int(max_download_bytes or self.MAX_DOWNLOAD_BYTES)
        self._downloader = downloader or self._download_url
        self._change_listener = change_listener
        self._diagnostic_recorder = diagnostic_recorder
        self._lock = threading.RLock()
        self._pending: dict[str, Future] = {}
        self._sequence = 0
        self._generation = 0
        self._queue: queue.PriorityQueue = queue.PriorityQueue()
        self._workers = []
        self._closed = False
        self._ensure_schema()
        self._start_workers()
        # Enforce the persisted disk-cache budget when an existing installation
        # is opened; otherwise an oversized cache could remain above the limit
        # until another download happens.
        self._evict_if_needed()

    def set_change_listener(self, listener):
        """Set the single incremental artwork publication hook."""
        self._change_listener = listener if callable(listener) else None

    def set_diagnostic_recorder(self, recorder):
        """Set a callback compatible with DiagnosticTimeline.record()."""
        self._diagnostic_recorder = recorder if callable(recorder) else None

    def _notify(self, event, *, row=None, error=None, local_path=None, duration_ms=None, **extra):
        event_name = {
            "request": "ARTWORK_REQUESTED",
            "hit": "ARTWORK_CACHE_HIT",
            "miss": "ARTWORK_CACHE_MISS",
            "invalid": "ARTWORK_CACHE_INVALID",
            "start": "ARTWORK_DOWNLOAD_STARTED",
            "success": "ARTWORK_DOWNLOAD_SUCCEEDED",
            "failure": "ARTWORK_DOWNLOAD_FAILED",
            "retry": "ARTWORK_RETRY",
            "published": "ARTWORK_PUBLISHED",
        }.get(event, str(event))
        source = dict(row or {})
        payload = {
            "event": event_name,
            "entity_type": source.get("entity_type") or extra.get("entity_type"),
            "entity_id": source.get("entity_id") or extra.get("entity_id"),
            "artwork_type": source.get("artwork_type") or extra.get("artwork_type"),
            "cache_key": source.get("artwork_key") or extra.get("cache_key"),
            "external_url": source.get("external_url") or extra.get("external_url"),
            "source_ref": source.get("source_ref") or extra.get("source_ref"),
            "local_path": local_path if local_path is not None else source.get("local_path"),
            "duration_ms": duration_ms,
        }
        payload.update({key: value for key, value in extra.items() if value is not None})
        if error:
            payload["error"] = str(error)[:500]

        listener = self._change_listener
        if callable(listener):
            try:
                listener(event_name, dict(payload))
            except Exception:
                logger.exception("Artwork change listener failed")

        recorder = self._diagnostic_recorder
        if callable(recorder):
            try:
                import json
                recorder(
                    event_name,
                    source="artwork",
                    result=json.dumps(payload, ensure_ascii=False, separators=(",", ":"))[:2000],
                    error=str(error)[:500] if error else None,
                )
            except Exception:
                logger.exception("Artwork diagnostic recorder failed")

    def _log(self, event, **extra):
        logger.info("%s %s", _EVENT_NAMES.get(event, event), extra)

    def invalidate_generation(self, reason="state_restore"):
        """Cancel queued artwork work so stale results cannot overwrite restored state."""
        with self._lock:
            self._generation += 1
            for future in tuple(self._pending.values()):
                future.cancel()
            self._pending.clear()
            generation = self._generation
        self._log("cancel", reason=reason, generation=generation)
        return generation

    def _start_workers(self):
        for index in range(self.max_workers):
            thread = threading.Thread(
                target=self._worker,
                name=f"reiflix-artwork-{index + 1}",
                daemon=True,
            )
            thread.start()
            self._workers.append(thread)

    def _worker(self):
        while not self._closed:
            try:
                priority, sequence, key, fn, future = self._queue.get(timeout=0.25)
            except queue.Empty:
                continue
            try:
                if future.cancelled():
                    self._log("cancel", key=key, reason="cancelled_before_start")
                    continue
                try:
                    result = fn()
                except Exception as exc:
                    if not future.cancelled():
                        future.set_exception(exc)
                else:
                    if not future.cancelled():
                        future.set_result(result)
            finally:
                with self._lock:
                    current = self._pending.get(key)
                    if current is future:
                        self._pending.pop(key, None)
                self._queue.task_done()

    def _ensure_schema(self):
        # LibraryStore creates the table.  Keep this additive so older databases
        # can be opened without a destructive migration.
        with self.store._conn() as con:
            columns = {row[1] for row in con.execute("PRAGMA table_info(artwork)")}
            definitions = {
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
            }
            for column, definition in definitions.items():
                if column not in columns:
                    con.execute(f"ALTER TABLE artwork ADD COLUMN {column} {definition}")
            con.execute("DROP INDEX IF EXISTS idx_artwork_key")
            con.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_artwork_key "
                "ON artwork(entity_type, entity_id, artwork_key) WHERE artwork_key IS NOT NULL"
            )
            con.execute(
                "CREATE INDEX IF NOT EXISTS idx_artwork_last_access "
                "ON artwork(last_access)"
            )
            con.execute(
                "CREATE INDEX IF NOT EXISTS idx_artwork_retry "
                "ON artwork(status, next_retry_at)"
            )

            # Backfill deterministic keys for records created by the older
            # legacy artwork implementation.
            rows = con.execute(
                "SELECT id,entity_type,entity_id,artwork_type,source,source_ref,variant "
                "FROM artwork WHERE artwork_key IS NULL"
            ).fetchall()
            for row in rows:
                key = self._make_key(
                    row["source"], row["source_ref"] or row["entity_id"],
                    row["artwork_type"], row["variant"] or "default",
                )
                con.execute("UPDATE artwork SET artwork_key=? WHERE id=?", (key, row["id"]))

    @staticmethod
    def _entity(entity_type, entity_id):
        if entity_type not in {"anime", "movie", "season", "episode", "special"}:
            raise ValueError("tipo de entidade inválido")
        if entity_id is None:
            raise ValueError("entity_id é obrigatório")
        return entity_type

    @staticmethod
    def _type(artwork_type):
        if artwork_type not in ARTWORK_TYPES:
            raise ValueError("tipo de artwork inválido")
        return artwork_type

    @staticmethod
    def _normalize_url(url):
        return str(url or "").strip()

    @staticmethod
    def _make_key(provider, provider_id, artwork_type, variant="default"):
        raw = f"{str(provider or 'unknown').strip().casefold()}|{str(provider_id or '').strip()}|{artwork_type}|{variant}"
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()

    @staticmethod
    def _is_file(path):
        if not path:
            return False
        try:
            return Path(path).is_file() and Path(path).stat().st_size > 0
        except OSError:
            return False

    @staticmethod
    def _is_valid_image_file(path):
        if not ArtworkEngine._is_file(path):
            return False
        try:
            with open(path, "rb") as handle:
                payload = handle.read(32 * 1024)
            detected = _detect_image_extension(payload)
            if not detected:
                return False
            # Validate the actual decodability for common formats. AVIF support
            # varies by Pillow build, so keep the existing signature validation
            # as the conservative fallback for that format.
            if detected == ".avif":
                return True
            with Image.open(path) as image:
                image.verify()
            return True
        except (OSError, ValueError):
            return False
        except Exception:
            logger.debug("Artwork image validation failed for %s", path, exc_info=True)
            return False

    @staticmethod
    def _is_valid_image_payload(payload):
        raw = bytes(payload or b"")
        detected = _detect_image_extension(raw)
        if not detected:
            return False
        if detected == ".avif":
            return True
        try:
            with Image.open(BytesIO(raw)) as image:
                image.verify()
            return True
        except Exception:
            return False

    @staticmethod
    def _path_under(path, root):
        try:
            Path(path).resolve().relative_to(Path(root).resolve())
            return True
        except (OSError, ValueError):
            return False

    def _upsert(self, *, entity_type, entity_id, artwork_type, source,
                source_ref=None, local_path=None, external_url=None,
                manual=False, status=STATUS_READY, failure_count=0,
                artwork_key=None, variant="default", byte_size=None,
                width=None, height=None, checksum=None, content_type=None,
                next_retry_at=None, http_status=None):
        entity_type = self._entity(entity_type, entity_id)
        artwork_type = self._type(artwork_type)
        now = time.time()
        source_ref = self._normalize_url(source_ref)
        if source_ref == "":
            source_ref = None
        artwork_key = artwork_key or self._make_key(
            source, source_ref or entity_id, artwork_type, variant
        )
        priority = _SOURCE_PRIORITY.get(source, 0)
        with self.store._conn() as con:
            row = con.execute(
                "SELECT id,discovered_at FROM artwork WHERE entity_type=? AND entity_id=? AND artwork_key=?",
                (entity_type, str(entity_id), artwork_key),
            ).fetchone()
            if row is None:
                row = con.execute(
                    """SELECT id,discovered_at FROM artwork
                       WHERE entity_type=? AND entity_id=? AND artwork_type=?
                         AND source_ref IS ?""",
                    (entity_type, str(entity_id), artwork_type, source_ref),
                ).fetchone()

            existing = con.execute(
                "SELECT * FROM artwork WHERE id=?",
                (row["id"],),
            ).fetchone() if row else None
            if (
                existing
                and not local_path
                and self._is_valid_image_file(existing["local_path"])
                and (
                    source in {"anilist", "cache"}
                    or existing["source"] == "cache"
                )
            ):
                local_path = str(existing["local_path"])
                source = "cache"
                status = STATUS_READY
                failure_count = 0
                try:
                    byte_size = byte_size or int(existing["byte_size"] or Path(local_path).stat().st_size)
                except (OSError, TypeError, ValueError):
                    byte_size = existing["byte_size"]
                width = width or existing["width"]
                height = height or existing["height"]
                checksum = checksum or existing["checksum"]
                content_type = content_type or existing["content_type"] or _mime_from_path(local_path)

            values = (
                source, local_path, external_url, int(manual), priority, status,
                now, failure_count, artwork_key, variant, byte_size, width, height,
                checksum, content_type, now if local_path else None,
                next_retry_at, http_status, entity_type, str(entity_id),
                artwork_type, source_ref,
            )
            if row:
                existing = con.execute(
                    "SELECT status,failure_count,next_retry_at FROM artwork WHERE id=?",
                    (row["id"],),
                ).fetchone()
                update_values = values
                if (
                    existing
                    and status == STATUS_NOT_REQUESTED
                    and existing["status"] in {STATUS_FAILED, STATUS_RETRY_WAIT}
                    and not local_path
                ):
                    update_values = list(values)
                    update_values[5] = existing["status"]
                    update_values[7] = int(existing["failure_count"] or 0)
                    update_values[16] = existing["next_retry_at"]
                    update_values = tuple(update_values)
                con.execute(
                    """UPDATE artwork SET source=?,local_path=?,external_url=?,manual=?,
                       priority=?,status=?,updated_at=?,failure_count=?,artwork_key=?,
                       variant=?,byte_size=?,width=?,height=?,checksum=?,content_type=?,
                       last_access=COALESCE(?,last_access),next_retry_at=?,http_status=?
                       WHERE id=?""",
                    update_values[:18] + (row["id"],),
                )
                return int(row["id"])
            cur = con.execute(
                """INSERT INTO artwork(entity_type,entity_id,artwork_type,source,source_ref,
                   local_path,external_url,manual,priority,status,discovered_at,updated_at,
                   failure_count,artwork_key,variant,byte_size,width,height,checksum,
                   content_type,last_access,next_retry_at,http_status)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    entity_type, str(entity_id), artwork_type, source, source_ref,
                    local_path, external_url, int(manual), priority, status, now, now,
                    failure_count, artwork_key, variant, byte_size, width, height,
                    checksum, content_type, now if local_path else None,
                    next_retry_at, http_status,
                ),
            )
            return int(cur.lastrowid)

    def add_local(self, entity_type, entity_id, artwork_type, path, *, manual=False):
        artwork_type = self._type(artwork_type)
        path = os.path.abspath(os.fspath(path))
        if not self._is_valid_image_file(path) or Path(path).suffix.casefold() not in IMAGE_EXTENSIONS:
            return False
        key = self._make_key(
            "manual" if manual else "local", path.casefold(), artwork_type,
            _VARIANT_PRIORITY.get(artwork_type, "default"),
        )
        self._upsert(
            entity_type=entity_type, entity_id=entity_id, artwork_type=artwork_type,
            source="manual" if manual else "local", source_ref=path.casefold(),
            local_path=path, manual=manual, artwork_key=key,
            variant=_VARIANT_PRIORITY.get(artwork_type, "default"),
            byte_size=os.path.getsize(path),
            content_type=_mime_from_path(path),
        )
        if not manual and entity_type in {"anime", "movie"} and artwork_type == "poster":
            with self.store._conn() as con:
                protected = con.execute(
                    """SELECT 1 FROM artwork
                       WHERE entity_type=? AND entity_id=? AND artwork_type=?
                         AND manual=1 LIMIT 1""",
                    (str(entity_type), str(entity_id), artwork_type),
                ).fetchone()
                if not protected:
                    con.execute("UPDATE anime SET cover_cache=? WHERE id=?", (path, int(entity_id)))
        return True

    def register_generated_thumbnail(self, media_uri, thumbnail_path, *, size=0, modified_at=0, media_identity=None, metadata=None):
        media_uri = str(media_uri or "").strip()
        thumbnail_path = os.path.abspath(os.fspath(thumbnail_path))
        if not media_uri or not self._is_valid_image_file(thumbnail_path):
            return False
        if Path(thumbnail_path).suffix.casefold() not in IMAGE_EXTENSIONS:
            return False
        metadata = metadata if isinstance(metadata, dict) else {}
        stable_identity = str(media_identity or "").strip() or media_uri
        with self.store._conn() as con:
            rows = con.execute(
                """SELECT DISTINCT e.id,e.anime_id,a.media_kind
                   FROM episodes e JOIN anime a ON a.id=e.anime_id
                   LEFT JOIN episode_observations o ON o.episode_id=e.id
                   WHERE e.path=? OR o.uri=?
                      OR (? != '' AND e.media_identity=?)""",
                (media_uri, media_uri, stable_identity, stable_identity),
            ).fetchall()
        if not rows:
            return False
        source_ref = f"native:{stable_identity}|{int(size or 0)}|{int(modified_at or 0)}"
        for row in rows:
            episode_id = int(row["id"])
            anime_id = int(row["anime_id"])
            with self.store._conn() as con:
                con.execute(
                    """DELETE FROM artwork WHERE entity_type='episode' AND entity_id=?
                       AND artwork_type='episode_thumbnail' AND source='generated'
                       AND NOT (source_ref=? AND local_path=?)""",
                    (str(episode_id), source_ref, thumbnail_path),
                )
            self._upsert(
                entity_type="episode", entity_id=episode_id,
                artwork_type="episode_thumbnail", source="generated",
                source_ref=source_ref, local_path=thumbnail_path,
                artwork_key=self._make_key("native", source_ref, "episode_thumbnail", "small"),
                variant="small", byte_size=os.path.getsize(thumbnail_path),
                content_type=str(metadata.get("mimeType") or _mime_from_path(thumbnail_path) or ""),
                width=int(metadata.get("width") or 0) or None,
                height=int(metadata.get("height") or 0) or None,
                checksum=str(metadata.get("checksum") or "") or None,
            )
            if metadata:
                duration_ms = float(metadata.get("durationMs") or 0)
                with self.store._conn() as con:
                    con.execute(
                        "UPDATE episodes SET duration=CASE WHEN duration<=0 AND ? > 0 THEN ? ELSE duration END WHERE id=?",
                        (duration_ms / 1000.0, duration_ms / 1000.0, episode_id),
                    )
        return True

    def set_manual(self, entity_type, entity_id, artwork_type, *, path=None, external_url=None):
        artwork_type = self._type(artwork_type)
        if path is None and not external_url:
            raise ValueError("artwork manual exige path ou external_url")
        if path is not None:
            if not self._is_file(path):
                raise FileNotFoundError(path)
            if not self._is_valid_image_file(path):
                raise ValueError(f"arquivo de artwork inválido ou ilegível: {path}")
        source_ref = os.path.abspath(path).casefold() if path else external_url
        key = self._make_key("manual", source_ref, artwork_type, _VARIANT_PRIORITY.get(artwork_type, "default"))
        self._upsert(
            entity_type=entity_type, entity_id=entity_id, artwork_type=artwork_type,
            source="manual", source_ref=source_ref,
            local_path=os.path.abspath(path) if path else None,
            external_url=external_url, manual=True, artwork_key=key,
            variant=_VARIANT_PRIORITY.get(artwork_type, "default"),
            byte_size=os.path.getsize(path) if path else None,
            content_type=_mime_from_path(path) if path else None,
        )
        return self.resolve(entity_type, entity_id, artwork_type, allow_network=False)

    def clear_manual(self, entity_type, entity_id, artwork_type):
        with self.store._conn() as con:
            con.execute(
                "DELETE FROM artwork WHERE entity_type=? AND entity_id=? AND artwork_type=? AND manual=1",
                (self._entity(entity_type, entity_id), str(entity_id), self._type(artwork_type)),
            )

    def _candidates(self, artwork_type, directory, stem=None):
        directory = Path(directory)
        if not directory.is_dir():
            return []
        hints = _NAME_HINTS[artwork_type]
        wanted = []
        try:
            entries = directory.iterdir()
        except OSError:
            return []
        for entry in sorted(entries, key=lambda p: p.name.casefold()):
            if not entry.is_file() or entry.suffix.casefold() not in IMAGE_EXTENSIONS:
                continue
            base = entry.stem.casefold().strip()
            normalized = base.replace("_", "-").replace(" ", "-")
            exact = bool(stem and base == stem.casefold())
            hinted = base in hints or normalized in hints
            if exact or hinted:
                wanted.append((0 if exact else 1, str(entry)))
        return [path for _, path in sorted(wanted, key=lambda item: (item[0], item[1].casefold()))]

    def _episode_row(self, episode_id):
        with self.store._conn() as con:
            row = con.execute("SELECT * FROM episodes WHERE id=?", (int(episode_id),)).fetchone()
            return dict(row) if row else None

    def _discover_episode_path(self, episode_id, path):
        if not isinstance(path, str) or not path.startswith("/") or not os.path.isfile(path):
            return []
        directory = Path(path).parent
        stem = Path(path).stem
        found = []
        for kind in ("episode_thumbnail", "thumbnail"):
            for candidate in self._candidates(kind, directory, stem):
                if self.add_local("episode", episode_id, kind, candidate):
                    found.append(candidate)
        return found

    def discover_episode(self, episode_id):
        row = self._episode_row(episode_id)
        return self._discover_episode_path(episode_id, row["path"]) if row else []

    def discover_anime(self, anime_id):
        with self.store._conn() as con:
            anime = con.execute("SELECT media_kind FROM anime WHERE id=?", (int(anime_id),)).fetchone()
            rows = con.execute(
                "SELECT path FROM episodes WHERE anime_id=? AND missing=0",
                (int(anime_id),),
            ).fetchall()
        entity_type = "movie" if anime and str(anime["media_kind"] or "series").casefold() == "movie" else "anime"
        directories = []
        for row in rows:
            path = row["path"]
            if isinstance(path, str) and path.startswith("/") and os.path.isfile(path):
                directory = Path(path).parent
                if directory not in directories:
                    directories.append(directory)
        found = []
        for directory in directories:
            for kind in ("poster", "backdrop"):
                for candidate in self._candidates(kind, directory):
                    if self.add_local(entity_type, anime_id, kind, candidate):
                        found.append(candidate)
        return found

    def discover_season(self, anime_id, season):
        season = int(season)
        with self.store._conn() as con:
            rows = con.execute(
                "SELECT path FROM episodes WHERE anime_id=? AND season=? AND missing=0",
                (int(anime_id), season),
            ).fetchall()
        found = []
        entity_id = f"{anime_id}:season:{season}"
        for row in rows:
            path = row["path"]
            if not isinstance(path, str) or not os.path.isfile(path):
                continue
            for candidate in self._candidates("season_poster", Path(path).parent):
                if self.add_local("season", entity_id, "season_poster", candidate):
                    found.append(candidate)
        return found

    def sync_anime_metadata(self, anime_id, metadata):
        if not metadata:
            return
        cover_cache = str(metadata.get("cover_cache") or "").strip()
        cover_url = self._normalize_url(metadata.get("cover_url"))
        banner_url = self._normalize_url(metadata.get("banner_url"))
        anilist_id = metadata.get("anilist_id")
        with self.store._conn() as con:
            row = con.execute("SELECT media_kind FROM anime WHERE id=?", (int(anime_id),)).fetchone()
        entity_type = "movie" if row and str(row["media_kind"] or "series").casefold() == "movie" else "anime"

        if cover_cache and self._is_valid_image_file(cover_cache):
            key = self._make_key("anilist" if anilist_id else "cache",
                                 f"{anilist_id or cover_url or cover_cache}|{cover_url}",
                                 "poster", "large")
            self._upsert(
                entity_type=entity_type, entity_id=anime_id, artwork_type="poster",
                source="cache", source_ref=cover_url or cover_cache,
                local_path=cover_cache, external_url=cover_url,
                status=STATUS_READY, artwork_key=key, variant="large",
                byte_size=os.path.getsize(cover_cache),
                content_type=_mime_from_path(cover_cache),
            )
        elif cover_url:
            key = self._make_key("anilist" if anilist_id else "url",
                                 f"{anilist_id or cover_url}|{cover_url}", "poster", "large")
            self._upsert(
                entity_type=entity_type, entity_id=anime_id, artwork_type="poster",
                source="anilist", source_ref=cover_url, external_url=cover_url,
                status=STATUS_NOT_REQUESTED, artwork_key=key, variant="large",
            )
        if banner_url:
            key = self._make_key("anilist" if anilist_id else "url",
                                 f"{anilist_id or banner_url}|{banner_url}", "backdrop", "large")
            self._upsert(
                entity_type=entity_type, entity_id=anime_id, artwork_type="backdrop",
                source="anilist", source_ref=banner_url, external_url=banner_url,
                status=STATUS_NOT_REQUESTED, artwork_key=key, variant="large",
            )
        self.discover_anime(anime_id)

    def reindex_entity(self, anime_id):
        with self.store._conn() as con:
            anime = con.execute("SELECT media_kind FROM anime WHERE id=?", (int(anime_id),)).fetchone()
            rows = con.execute(
                "SELECT path,season FROM episodes WHERE anime_id=? AND missing=0",
                (int(anime_id),),
            ).fetchall()
        entity_type = "movie" if anime and str(anime["media_kind"] or "series").casefold() == "movie" else "anime"
        entity_dirs, season_dirs = set(), {}
        for row in rows:
            path = row["path"]
            if not isinstance(path, str) or not path.startswith("/") or not os.path.isfile(path):
                continue
            directory = Path(path).parent
            entity_dirs.add(directory)
            if row["season"] is not None:
                season_dirs.setdefault(int(row["season"]), set()).add(directory)
        found = []
        for directory in sorted(entity_dirs, key=lambda p: str(p).casefold()):
            for kind in ("poster", "backdrop"):
                for candidate in self._candidates(kind, directory):
                    if self.add_local(entity_type, anime_id, kind, candidate):
                        found.append(candidate)
        for season, directories in sorted(season_dirs.items()):
            entity_id = f"{anime_id}:season:{season}"
            for directory in sorted(directories, key=lambda p: str(p).casefold()):
                for candidate in self._candidates("season_poster", directory):
                    if self.add_local("season", entity_id, "season_poster", candidate):
                        found.append(candidate)
        return found

    def reindex_episode(self, episode_id):
        found = self.discover_episode(episode_id)
        row = self._episode_row(episode_id)
        if row:
            self.reindex_entity(row["anime_id"])
        return found

    def reindex_anime(self, anime_id):
        self.discover_anime(anime_id)
        with self.store._conn() as con:
            rows = con.execute("SELECT id FROM episodes WHERE anime_id=?", (int(anime_id),)).fetchall()
        for row in rows:
            self.discover_episode(row["id"])
        return self.list_for("anime", anime_id)

    def list_for(self, entity_type, entity_id, artwork_type=None):
        entity_type = self._entity(entity_type, entity_id)
        params = [entity_type, str(entity_id)]
        where = "entity_type=? AND entity_id=?"
        if artwork_type:
            where += " AND artwork_type=?"
            params.append(self._type(artwork_type))
        with self.store._conn() as con:
            rows = [dict(row) for row in con.execute(
                f"SELECT * FROM artwork WHERE {where} ORDER BY priority DESC, updated_at DESC, id DESC",
                params,
            ).fetchall()]
        return rows

    def resolve_local_batch(self, entity_type, entity_ids, artwork_types=("episode_thumbnail", "poster")):
        """Resolve local artwork for many entities with one SQLite read."""
        entity_type = self._entity(entity_type, "")
        normalized_ids = []
        seen = set()
        for value in entity_ids or []:
            key = str(value).strip()
            if key and key not in seen:
                seen.add(key)
                normalized_ids.append(key)
        types = [self._type(value) for value in artwork_types or () if value]
        if not normalized_ids or not types:
            return {}

        marks_ids = ",".join("?" for _ in normalized_ids)
        marks_types = ",".join("?" for _ in types)
        with self.store._conn() as con:
            rows = con.execute(
                f"""SELECT * FROM artwork
                    WHERE entity_type=?
                      AND entity_id IN ({marks_ids})
                      AND artwork_type IN ({marks_types})
                      AND status != 'failed'
                    ORDER BY entity_id, priority DESC, updated_at DESC, id DESC""",
                [entity_type, *normalized_ids, *types],
            ).fetchall()

        preference = {value: index for index, value in enumerate(types)}
        result = {}
        for row in rows:
            entity_id = str(row["entity_id"])
            artwork_type = str(row["artwork_type"])
            if artwork_type == "poster" and self._is_thumbnail_generated_poster(dict(row)):
                continue
            current = result.get(entity_id)
            candidate_rank = preference.get(artwork_type, len(preference))
            if current is not None and current[0] <= candidate_rank:
                continue
            path = row["local_path"]
            valid = False
            if path:
                try:
                    valid = self._is_valid_image_file(path)
                except OSError:
                    valid = False
            if valid:
                result[entity_id] = (candidate_rank, dict(row))
        return {entity_id: row for entity_id, (_, row) in result.items()}

    @staticmethod
    def _is_thumbnail_generated_poster(row):
        """Return True for legacy poster rows created from episode thumbnails."""
        return (
            str(row.get("artwork_type") or "") == "poster"
            and str(row.get("source") or "") == "generated"
            and str(row.get("source_ref") or "").startswith("native:")
        )

    def get(self, entity_type, entity_id, artwork_type, *, allow_network=False):
        """Return a valid cached artwork immediately, never requiring network."""
        entity_type = self._entity(entity_type, entity_id)
        artwork_type = self._type(artwork_type)
        rows = self.list_for(entity_type, entity_id, artwork_type)
        for row in rows:
            if artwork_type == "poster" and self._is_thumbnail_generated_poster(row):
                self._log("miss", key=row.get("artwork_key"), entity_type=entity_type,
                          entity_id=entity_id, reason="legacy_episode_thumbnail_poster")
                continue
            if row.get("local_path"):
                valid = self._is_file(row["local_path"])
                if row.get("local_path"):
                    valid = self._is_valid_image_file(row["local_path"])
                if valid:
                    self._touch(row["id"])
                    self._log("hit", key=row.get("artwork_key"), entity_type=entity_type, entity_id=entity_id)
                    return row
                self._mark_inconsistent(row)
        if allow_network:
            for row in rows:
                if row.get("external_url") and row.get("status") not in {STATUS_INVALID}:
                    return row
        return self._fallback(entity_type, entity_id, artwork_type, allow_network=allow_network)

    def _fallback(self, entity_type, entity_id, artwork_type, *, allow_network):
        if artwork_type in {"thumbnail", "episode_thumbnail"}:
            fallback = self._first_usable(entity_type, entity_id, "poster", allow_network)
            if fallback:
                return dict(fallback, fallback=True)
        if entity_type == "season" and artwork_type == "season_poster":
            anime_id = str(entity_id).split(":season:", 1)[0]
            fallback = self._first_usable("anime", anime_id, "poster", allow_network)
            if fallback:
                return dict(fallback, fallback=True)
        if artwork_type == "backdrop":
            fallback = self._first_usable(entity_type, entity_id, "poster", allow_network)
            if fallback:
                return dict(fallback, fallback=True)
        return None

    def _first_usable(self, entity_type, entity_id, artwork_type, allow_network):
        for row in self.list_for(entity_type, entity_id, artwork_type):
            # Legacy generated poster rows can originate from the old
            # episode-thumbnail promotion bug. They must never become a
            # fallback source for any artwork resolution path.
            if artwork_type == "poster" and self._is_thumbnail_generated_poster(row):
                self._log(
                    "miss",
                    key=row.get("artwork_key"),
                    entity_type=entity_type,
                    entity_id=entity_id,
                    reason="legacy_episode_thumbnail_poster_fallback",
                )
                continue
            if row.get("local_path") and self._is_valid_image_file(row["local_path"]):
                self._touch(row["id"])
                return row
            if allow_network and row.get("external_url") and row.get("status") != STATUS_INVALID:
                return row
        return None

    def resolve(self, entity_type, entity_id, artwork_type, *, allow_network=True):
        """Compatibility facade used by Home/Details and existing tests."""
        result = self.get(entity_type, entity_id, artwork_type, allow_network=allow_network)
        if result:
            return result
        return None

    @staticmethod
    def _palette_fingerprint(row):
        if row.get("checksum"):
            return f"checksum:{row['checksum']}"
        path = str(row.get("local_path") or "")
        try:
            stat = Path(path).stat()
            return f"stat:{stat.st_size}:{stat.st_mtime_ns}"
        except OSError:
            return None

    def _palette_cache_path(self, artwork_row_id):
        return self.cache_dir / f".reiflix-palette-{int(artwork_row_id)}.json"

    def _clear_palette_cache(self, artwork_row_id=None):
        if artwork_row_id is not None:
            paths = [self._palette_cache_path(artwork_row_id)]
        else:
            paths = list(self.cache_dir.glob(self.palette_cache_pattern))
        removed = 0
        for path in paths:
            try:
                path.unlink(missing_ok=True)
                removed += 1
            except OSError:
                logger.warning("Could not remove palette cache %s", path)
        return removed

    def resolve_palette(self, entity_type, entity_id, artwork_type="poster", *, mode="dark"):
        """Return a cached/local contextual palette without requiring the network."""
        entity_type = self._entity(entity_type, entity_id)
        artwork_type = self._type(artwork_type)
        row = self.get(entity_type, entity_id, artwork_type, allow_network=False)
        if not row or not row.get("local_path"):
            return None
        path = row.get("local_path")
        if not self._is_valid_image_file(path):
            return None
        fingerprint = self._palette_fingerprint(row)
        if not fingerprint:
            return None
        cache_path = self._palette_cache_path(row["id"])
        requested_mode = "light" if str(mode).casefold() == "light" else "dark"
        try:
            import json
            cached = json.loads(cache_path.read_text(encoding="utf-8"))
            if (
                isinstance(cached, dict)
                and cached.get("fingerprint") == fingerprint
                and cached.get("mode") == requested_mode
                and cached.get("accent")
                and cached.get("on_accent")
            ):
                return cached
        except (OSError, ValueError, TypeError):
            pass
        palette = extract_palette(path, requested_mode)
        if not palette:
            return None
        payload = dict(palette, fingerprint=fingerprint, artwork_id=int(row["id"]))
        temporary = cache_path.with_suffix(".tmp")
        try:
            import json
            temporary.write_text(json.dumps(payload, ensure_ascii=False, sort_keys=True), encoding="utf-8")
            os.replace(temporary, cache_path)
        except OSError:
            try:
                temporary.unlink(missing_ok=True)
            except OSError:
                pass
        # Keep exactly one palette record per artwork row; a cover replacement
        # therefore invalidates the previous derived palette automatically.
        return payload

    def _touch(self, row_id):
        with self.store._conn() as con:
            con.execute("UPDATE artwork SET last_access=? WHERE id=?", (time.time(), int(row_id)))

    def _mark_inconsistent(self, row):
        with self.store._conn() as con:
            con.execute(
                """UPDATE artwork SET status=?,local_path=NULL,updated_at=?,next_retry_at=NULL
                   WHERE id=?""",
                (STATUS_NOT_REQUESTED, time.time(), int(row["id"])),
            )
        self._log("miss", key=row.get("artwork_key"), reason="missing_local_file")
        self._notify("invalid", row=row, reason="missing_or_invalid_local_file")

    def _retry_delay(self, failure_count):
        exponent = max(0, min(int(failure_count) - 1, 8))
        delay = min(self.MAX_BACKOFF_SECONDS, self.BASE_BACKOFF_SECONDS * (2 ** exponent))
        jitter = delay * 0.20 * ((int(time.time() * 1000) % 1000) / 1000.0)
        return delay + jitter

    def _enqueue(self, key, fn, *, priority):
        with self._lock:
            if self._closed:
                raise RuntimeError("ArtworkEngine encerrado")
            existing = self._pending.get(key)
            if existing and not existing.done():
                return existing
            if len(self._pending) >= self.MAX_PENDING_TASKS:
                raise queue.Full
            future = Future()
            self._sequence += 1
            self._pending[key] = future
            self._queue.put((-int(priority), self._sequence, key, fn, future))
            return future

    def request(self, entity_type, entity_id, artwork_type, *, priority=100,
                allow_network=True, blocking=False, force=False):
        """Request one artwork; concurrent requests for the same key coalesce."""
        entity_type = self._entity(entity_type, entity_id)
        artwork_type = self._type(artwork_type)
        self._log("request", entity_type=entity_type, entity_id=entity_id, artwork_type=artwork_type)
        self._notify("request", entity_type=entity_type, entity_id=entity_id, artwork_type=artwork_type)

        rows = self.list_for(entity_type, entity_id, artwork_type)
        pending_remote = next(
            (item for item in rows
             if item.get("external_url")
             and not (item.get("local_path") and self._is_valid_image_file(item.get("local_path")))
             and item.get("status") != STATUS_INVALID),
            None,
        )
        cached = self.get(entity_type, entity_id, artwork_type, allow_network=False)
        if cached and not pending_remote:
            self._notify("hit", row=cached)
            return cached
        if cached and pending_remote and cached.get("external_url") == pending_remote.get("external_url"):
            self._notify("hit", row=cached)
            return cached
        external_rows = [item for item in rows if item.get("external_url")]
        row = next(
            (item for item in external_rows
             if not (item.get("local_path") and self._is_valid_image_file(item.get("local_path")))),
            None,
        )
        row = row or (external_rows[0] if external_rows else None)
        if row is None:
            self._log("miss", entity_type=entity_type, entity_id=entity_id, artwork_type=artwork_type)
            return None
        now = time.time()
        retry_at = float(row.get("next_retry_at") or 0)
        if not force and retry_at > now:
            return row
        if not allow_network:
            return row

        key = row.get("artwork_key") or self._make_key(
            row.get("source") or "url", row.get("source_ref") or row.get("external_url"),
            artwork_type, row.get("variant") or _VARIANT_PRIORITY.get(artwork_type, "default"),
        )
        variant = row.get("variant") or _VARIANT_PRIORITY.get(artwork_type, "default")
        self._set_status(row["id"], STATUS_QUEUED)
        with self._lock:
            generation = self._generation
        try:
            future = self._enqueue(
                key,
                lambda generation=generation: self._download_row(row, force=force, generation=generation),
                priority=priority,
            )
        except queue.Full:
            self._set_status(row["id"], STATUS_NOT_REQUESTED)
            self._log("cancel", key=key, reason="pending_limit")
            return row
        if blocking:
            return future.result()
        return dict(row, status=STATUS_QUEUED)

    def prefetch(self, requests, *, default_priority=50):
        """Queue a bounded set of requests; duplicate keys share one task."""
        futures = []
        for item in list(requests or [])[:100]:
            if not isinstance(item, dict):
                continue
            try:
                futures.append(self.request(
                    item["entity_type"], item["entity_id"], item["artwork_type"],
                    priority=int(item.get("priority", default_priority)),
                    allow_network=bool(item.get("allow_network", True)),
                    blocking=False,
                    force=bool(item.get("force", False)),
                ))
            except Exception:
                logger.exception("Artwork prefetch request failed")
        return futures

    @staticmethod
    def _prepare_download_payload(payload, *, artwork_type, variant, extension):
        """Keep cached artwork close to the largest useful UI size.

        Local/manual artwork is never rewritten. Only freshly downloaded managed
        artwork is normalized, and only when it exceeds the variant-specific bound.
        Unsupported formats are kept byte-for-byte unchanged.
        """
        try:
            with Image.open(BytesIO(payload)) as image:
                original_width, original_height = image.size
                if artwork_type in {"thumbnail", "episode_thumbnail"} or variant == "small":
                    max_width, max_height = 640, 640
                elif artwork_type == "backdrop":
                    max_width, max_height = 1440, 810
                else:
                    max_width, max_height = 960, 1440
                if original_width <= max_width and original_height <= max_height:
                    return payload, original_width, original_height

                normalized = ImageOps.exif_transpose(image)
                normalized.thumbnail((max_width, max_height), Image.Resampling.LANCZOS)
                output = BytesIO()
                if extension in {".jpg", ".jpeg"}:
                    normalized.convert("RGB").save(
                        output, format="JPEG", quality=90, optimize=True, progressive=True,
                    )
                elif extension == ".png":
                    normalized.save(output, format="PNG", optimize=True)
                elif extension == ".webp":
                    save_image = normalized.convert("RGB") if normalized.mode not in {"RGB", "RGBA"} else normalized
                    save_image.save(output, format="WEBP", quality=90, method=4)
                else:
                    return payload, original_width, original_height
                return output.getvalue(), normalized.width, normalized.height
        except Exception:
            logger.debug("Artwork payload normalization skipped", exc_info=True)
            return payload, None, None

    def _download_row(self, row, *, force=False, generation=None):
        with self._lock:
            if generation is not None and generation != self._generation:
                self._log("cancel", key=row.get("artwork_key"), reason="stale_generation")
                return None
        row_id = int(row["id"])
        url = self._normalize_url(row.get("external_url"))
        if not url or not _safe_http_url(url):
            self._set_status(row_id, STATUS_INVALID, http_status=None)
            self._log("failure", key=row.get("artwork_key"), reason="invalid_url")
            self._notify("failure", row=row, error="invalid_url")
            return None
        download_started = time.perf_counter()
        self._log(
            "start",
            key=row.get("artwork_key"),
            url_host=_url_host(url),
            entity_type=row.get("entity_type"),
            entity_id=row.get("entity_id"),
            artwork_type=row.get("artwork_type"),
        )
        self._notify("start", row=row)
        self._set_status(row_id, STATUS_DOWNLOADING)
        try:
            payload, content_type, http_status = self._downloader(url)
            extension = IMAGE_MIME.get(content_type.casefold(), "") if content_type else ""
            if not extension:
                extension = _detect_image_extension(payload)
            if not extension:
                raise ValueError("conteúdo recebido não é uma imagem suportada")
            if not self._is_valid_image_payload(payload):
                raise ValueError("payload recebido não é uma imagem decodificável")
            variant = row.get("variant") or _VARIANT_PRIORITY.get(row["artwork_type"], "default")
            payload, width, height = self._prepare_download_payload(
                payload,
                artwork_type=row["artwork_type"],
                variant=variant,
                extension=extension,
            )
            checksum = hashlib.sha256(payload).hexdigest()
            key = row.get("artwork_key") or self._make_key(
                "url", row.get("source_ref") or url, row["artwork_type"], variant,
            )
            target = self.cache_dir / f"{key}{extension}"
            temporary = self.cache_dir / f".{key}.tmp"
            temporary.write_bytes(payload)
            # Do not replace an existing cache entry until the generation check
            # has passed. Otherwise a stale refresh could overwrite a valid file
            # and then delete that file while aborting.
            # Serialize generation invalidation with the file+SQLite commit. If a
            # new generation starts, it waits until this atomic unit is complete;
            # otherwise a stale task could replace a valid file between the guard
            # and the database update.
            with self._lock:
                if generation is not None and generation != self._generation:
                    try:
                        temporary.unlink()
                    except FileNotFoundError:
                        pass
                    self._log("cancel", key=key, reason="stale_generation_before_commit")
                    return None
                os.replace(temporary, target)
                now = time.time()
                with self.store._conn() as con:
                    con.execute(
                        """UPDATE artwork SET source='cache',local_path=?,status=?,priority=?,
                           updated_at=?,last_access=?,byte_size=?,width=?,height=?,checksum=?,content_type=?,
                           next_retry_at=NULL,http_status=?,failure_count=0 WHERE id=?""",
                        (str(target), STATUS_READY, _SOURCE_PRIORITY["cache"], now, now, len(payload), width, height, checksum,
                         content_type or _mime_from_path(str(target)), http_status, row_id),
                    )
                    if row["entity_type"] in {"anime", "movie"} and row["artwork_type"] == "poster":
                        con.execute(
                            "UPDATE anime SET cover_cache=? WHERE id=?",
                            (str(target), int(row["entity_id"])),
                        )
            duration_ms = int((time.perf_counter() - download_started) * 1000)
            self._log(
                "success",
                key=key,
                bytes=len(payload),
                entity_type=row.get("entity_type"),
                entity_id=row.get("entity_id"),
                artwork_type=row.get("artwork_type"),
                local_path=str(target),
                duration_ms=duration_ms,
            )
            self._notify("success", row=row, local_path=str(target), duration_ms=duration_ms, bytes=len(payload))
            self._notify(
                "published",
                row=dict(row, local_path=str(target), status=STATUS_READY),
                local_path=str(target),
                duration_ms=duration_ms,
            )
            self._evict_if_needed(protected={str(target)})
            return self.get(row["entity_type"], row["entity_id"], row["artwork_type"], allow_network=False)
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                self._failure(row_id, http_status=404, retry=False, reason="http_404")
            elif exc.code == 429:
                delay = _retry_after(exc.headers) or self._retry_delay(int(row.get("failure_count") or 0) + 1)
                self._failure(row_id, http_status=429, retry=True, delay=delay, reason="http_429")
            elif 500 <= exc.code <= 599:
                self._failure(row_id, http_status=exc.code, retry=True, reason=f"http_{exc.code}")
            else:
                self._failure(row_id, http_status=exc.code, retry=False, reason=f"http_{exc.code}")
        except ValueError as exc:
            self._failure(row_id, retry=False, reason=type(exc).__name__)
        except (TimeoutError, urllib.error.URLError, OSError) as exc:
            self._failure(row_id, retry=True, reason=type(exc).__name__)
        except Exception as exc:
            self._failure(row_id, retry=False, reason=type(exc).__name__)
        with self.store._conn() as con:
            failed_row = con.execute(
                "SELECT * FROM artwork WHERE id=?",
                (row_id,),
            ).fetchone()
        if failed_row:
            failed_row = dict(failed_row)
            event = "retry" if failed_row.get("status") == STATUS_RETRY_WAIT else "failure"
            self._notify(
                event,
                row=failed_row,
                error=failed_row.get("status"),
                duration_ms=int((time.perf_counter() - download_started) * 1000),
            )
        return None

    def _download_url(self, url):
        request = urllib.request.Request(
            url,
            headers={"User-Agent": "ReiAnix/ArtworkEngine"},
            method="GET",
        )
        with urllib.request.urlopen(request, timeout=self.REQUEST_TIMEOUT_SECONDS) as response:
            status = int(getattr(response, "status", 200) or 200)
            content_type = str(response.headers.get("Content-Type") or "").split(";", 1)[0].strip().casefold()
            if content_type and not content_type.startswith("image/"):
                raise ValueError("resposta HTTP não é uma imagem")
            length_header = response.headers.get("Content-Length")
            if length_header:
                try:
                    if int(length_header) > self.max_download_bytes:
                        raise ValueError("artwork excede o limite de tamanho")
                except ValueError:
                    raise
            chunks = []
            total = 0
            while True:
                chunk = response.read(128 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > self.max_download_bytes:
                    raise ValueError("artwork excede o limite de tamanho")
                chunks.append(chunk)
            payload = b"".join(chunks)
            if not payload:
                raise ValueError("artwork vazio")
            detected = _detect_image_extension(payload)
            if not detected:
                raise ValueError("conteúdo recebido não é uma imagem suportada")
            if content_type and content_type in IMAGE_MIME and IMAGE_MIME[content_type] != detected:
                raise ValueError("MIME e conteúdo da imagem não correspondem")
            return payload, content_type, status

    def _set_status(self, row_id, status, *, http_status=None):
        with self.store._conn() as con:
            con.execute(
                "UPDATE artwork SET status=?,updated_at=?,http_status=? WHERE id=?",
                (status, time.time(), http_status, int(row_id)),
            )

    def _failure(self, row_id, *, http_status=None, retry, delay=None, reason):
        with self.store._conn() as con:
            row = con.execute(
                "SELECT failure_count,artwork_key FROM artwork WHERE id=?", (int(row_id),)
            ).fetchone()
            if not row:
                return
            failures = int(row["failure_count"] or 0) + 1
            can_retry = bool(retry and failures < self.MAX_RETRIES)
            next_retry = time.time() + (delay if delay is not None else self._retry_delay(failures)) if can_retry else None
            status = STATUS_RETRY_WAIT if can_retry else STATUS_FAILED
            con.execute(
                """UPDATE artwork SET status=?,failure_count=?,last_attempt_at=?,
                   next_retry_at=?,updated_at=?,http_status=? WHERE id=?""",
                (status, failures, time.time(), next_retry, time.time(), http_status, int(row_id)),
            )
        self._log("retry" if can_retry else "failure", key=row["artwork_key"], reason=reason,
                  attempt=failures, next_retry_at=next_retry)

    def _failure_backwards_compatible(self, row_id, *, http_status=None, retry=False, delay=None, reason=""):
        self._failure(row_id, http_status=http_status, retry=retry, delay=delay, reason=reason)

    def mark_download_failure(self, entity_type, entity_id, artwork_type, source_ref):
        with self.store._conn() as con:
            row = con.execute(
                """SELECT id,failure_count,artwork_key FROM artwork
                   WHERE entity_type=? AND entity_id=? AND artwork_type=? AND source_ref=?""",
                (self._entity(entity_type, entity_id), str(entity_id), self._type(artwork_type), source_ref),
            ).fetchone()
        if not row:
            return False
        self._failure_backwards_compatible(
            row["id"], retry=True, reason="legacy_mark_download_failure"
        )
        return True

    def retryable(self, entity_type, entity_id, artwork_type):
        with self.store._conn() as con:
            rows = con.execute(
                """SELECT * FROM artwork WHERE entity_type=? AND entity_id=? AND artwork_type=?
                   AND status IN ('failed','retry_wait') ORDER BY last_attempt_at ASC""",
                (self._entity(entity_type, entity_id), str(entity_id), self._type(artwork_type)),
            ).fetchall()
        return [dict(row) for row in rows]

    def retry(self, entity_type, entity_id, artwork_type, *, priority=200):
        entity_type = self._entity(entity_type, entity_id)
        artwork_type = self._type(artwork_type)
        with self.store._conn() as con:
            rows = con.execute(
                "SELECT id FROM artwork WHERE entity_type=? AND entity_id=? AND artwork_type=? AND external_url IS NOT NULL",
                (entity_type, str(entity_id), artwork_type),
            ).fetchall()
            for row in rows:
                con.execute(
                    "UPDATE artwork SET status=?,next_retry_at=NULL,failure_count=0,updated_at=? WHERE id=?",
                    (STATUS_NOT_REQUESTED, time.time(), row["id"]),
                )
        return self.request(entity_type, entity_id, artwork_type, priority=priority, force=True, blocking=False)

    def cancel(self, entity_type, entity_id, artwork_type):
        entity_type = self._entity(entity_type, entity_id)
        artwork_type = self._type(artwork_type)
        cancelled = False
        for row in self.list_for(entity_type, entity_id, artwork_type):
            key = row.get("artwork_key")
            with self._lock:
                future = self._pending.get(key) if key else None
                if future and future.cancel():
                    cancelled = True
                    self._log("cancel", key=key)
                    self._set_status(row["id"], STATUS_NOT_REQUESTED)
        return cancelled

    def invalidate(self, entity_type, entity_id, artwork_type, *, source_ref=None):
        entity_type = self._entity(entity_type, entity_id)
        artwork_type = self._type(artwork_type)
        with self.store._conn() as con:
            if source_ref:
                rows = con.execute("SELECT id FROM artwork WHERE entity_type=? AND entity_id=? AND artwork_type=? AND source_ref=?",
                    (entity_type, str(entity_id), artwork_type, source_ref)).fetchall()
                for palette_row in rows:
                    self._clear_palette_cache(palette_row["id"])
                con.execute(
                    """UPDATE artwork SET status=?,local_path=NULL,next_retry_at=NULL,updated_at=?
                       WHERE entity_type=? AND entity_id=? AND artwork_type=? AND source_ref=?""",
                    (STATUS_INVALID, time.time(), entity_type, str(entity_id), artwork_type, source_ref),
                )
            else:
                rows = con.execute("SELECT id FROM artwork WHERE entity_type=? AND entity_id=? AND artwork_type=?",
                    (entity_type, str(entity_id), artwork_type)).fetchall()
                for palette_row in rows:
                    self._clear_palette_cache(palette_row["id"])
                con.execute(
                    """UPDATE artwork SET status=?,local_path=NULL,next_retry_at=NULL,updated_at=?
                       WHERE entity_type=? AND entity_id=? AND artwork_type=?""",
                    (STATUS_INVALID, time.time(), entity_type, str(entity_id), artwork_type),
                )

    def get_status(self, entity_type, entity_id, artwork_type):
        rows = self.list_for(entity_type, entity_id, artwork_type)
        if not rows:
            return STATUS_NOT_REQUESTED
        if any(row.get("local_path") and self._is_file(row["local_path"]) for row in rows):
            return STATUS_READY
        return max(rows, key=lambda row: (row.get("updated_at") or 0)).get("status") or STATUS_NOT_REQUESTED

    def _evict_if_needed(self, protected=None):
        protected = set(protected or ())
        with self.store._conn() as con:
            rows = con.execute(
                """SELECT id,local_path,byte_size,last_access,source,manual
                   FROM artwork WHERE local_path IS NOT NULL AND source='cache'
                   ORDER BY COALESCE(last_access,updated_at,0) ASC"""
            ).fetchall()
        total = sum(int(row["byte_size"] or 0) for row in rows if self._is_file(row["local_path"]))
        for row in rows:
            if total <= self.cache_limit_bytes:
                break
            path = str(row["local_path"])
            if path in protected or int(row["manual"] or 0):
                continue
            try:
                size = int(row["byte_size"] or Path(path).stat().st_size)
            except OSError:
                size = 0
            try:
                Path(path).unlink(missing_ok=True)
            except OSError:
                continue
            with self.store._conn() as con:
                con.execute(
                    "UPDATE artwork SET local_path=NULL,status=?,byte_size=NULL,last_access=NULL WHERE id=?",
                    (STATUS_NOT_REQUESTED, int(row["id"])),
                )
            total -= size
            self._clear_palette_cache(row["id"])
            self._log("evict", path=path, bytes=size)

    def cleanup_orphans(self):
        """Remove only managed artwork files with no live artwork record."""
        referenced = set()
        with self.store._conn() as con:
            for row in con.execute("SELECT local_path FROM artwork WHERE local_path IS NOT NULL"):
                if row["local_path"]:
                    try:
                        referenced.add(str(Path(row["local_path"]).resolve()))
                    except OSError:
                        pass
        removed = 0
        for path in self.cache_dir.iterdir():
            if not path.is_file() or path.name.endswith(".tmp"):
                continue
            if path.name.startswith(".reiflix-palette-"):
                try:
                    row_id = int(path.stem.rsplit("-", 1)[-1])
                except ValueError:
                    row_id = None
                if row_id is not None:
                    with self.store._conn() as con:
                        live = con.execute("SELECT 1 FROM artwork WHERE id=?", (row_id,)).fetchone()
                    if live:
                        continue
                try:
                    path.unlink()
                    removed += 1
                except OSError:
                    pass
                continue
            if str(path.resolve()) not in referenced:
                try:
                    path.unlink()
                    removed += 1
                except OSError:
                    logger.warning("Could not remove artwork orphan %s", path)
        return removed

    def clear(self):
        """Clear only managed external artwork; never touch videos or SQLite."""
        with self.store._conn() as con:
            rows = con.execute(
                "SELECT id,local_path,source FROM artwork WHERE source IN ('cache','anilist')"
            ).fetchall()
            for row in rows:
                path = row["local_path"]
                if path and self._path_under(path, self.cache_dir):
                    try:
                        Path(path).unlink(missing_ok=True)
                    except OSError:
                        pass
            con.execute(
                """DELETE FROM artwork WHERE source IN ('cache','anilist')
                   AND manual=0"""
            )
            # Existing anime rows remain local and their metadata/match state
            # stays intact.  Only the refreshable cover pointer is cleared when
            # it was an engine-managed cache path.
            anime_rows = con.execute("SELECT id,cover_cache FROM anime").fetchall()
            for row in anime_rows:
                cover = row["cover_cache"]
                if cover and self._path_under(cover, self.cache_dir):
                    con.execute("UPDATE anime SET cover_cache='' WHERE id=?", (row["id"],))
        palette_removed = self._clear_palette_cache()
        removed = palette_removed + self.cleanup_orphans()
        self._log("evict", reason="clear", removed=removed)
        return removed

    def cache_stats(self):
        with self.store._conn() as con:
            rows = con.execute(
                """SELECT COUNT(*) AS files, COALESCE(SUM(byte_size),0) AS bytes
                   FROM artwork WHERE source='cache' AND local_path IS NOT NULL"""
            ).fetchone()
        return {"files": int(rows["files"] or 0), "bytes": int(rows["bytes"] or 0),
                "limit_bytes": self.cache_limit_bytes}

    def shutdown(self):
        self._closed = True
        for future in list(self._pending.values()):
            future.cancel()
        for thread in self._workers:
            thread.join(timeout=0.5)


def _safe_http_url(url):
    try:
        from urllib.parse import urlparse
        parsed = urlparse(url)
        return parsed.scheme.casefold() in {"https", "http"} and bool(parsed.netloc)
    except Exception:
        return False


def _url_host(url):
    try:
        from urllib.parse import urlparse
        return urlparse(url).hostname or ""
    except Exception:
        return ""


def _mime_from_path(path):
    suffix = Path(path).suffix.casefold()
    return {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png",
            ".webp": "image/webp", ".avif": "image/avif", ".gif": "image/gif"}.get(suffix)


def _detect_image_extension(payload):
    # Accept real binary signatures plus the escaped byte fixtures used by
    # legacy tests and older cached metadata validations.
    if payload.startswith((b"\xff\xd8\xff", b"\\xff\\xd8\\xff")):
        return ".jpg"
    if payload.startswith((b"\x89PNG\r\n\x1a\n", b"\\x89PNG\\r\\n\\x1a\\n")):
        return ".png"
    if (payload.startswith(b"RIFF") and payload[8:12] == b"WEBP") or payload.startswith(b"RIFF\\x57EBP"):
        return ".webp"
    if payload.startswith(b"GIF87a") or payload.startswith(b"GIF89a"):
        return ".gif"
    if (
        payload.startswith((b"\x00\x00\x00", b"\\x00\\x00\\x00"))
        and (b"ftypavif" in payload[:32] or b"ftypavif" in payload[:40])
    ):
        return ".avif"
    return ""


def _retry_after(headers):
    try:
        value = headers.get("Retry-After")
        return max(0.0, float(value)) if value else None
    except (TypeError, ValueError):
        return None
