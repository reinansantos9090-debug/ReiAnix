"""Bridge protocol shared with the Android host without ever fabricating paths.

The Android overlay writes short JSON events into the application's private
files directory. Normal host commands retain the app-owned `reiflix://`
intent contract, while player/thumbnail operations use a private command
mailbox so they never resolve through MainActivity's singleTask route.
Python drains the event mailbox and inserts document URIs into SQLite.
Desktop deliberately reports this bridge as unavailable.
"""
from __future__ import annotations
import asyncio
import heapq
import hashlib
import json
import logging
import os
import time
import uuid
from pathlib import Path
from urllib.parse import urlencode
from core.performance import get_performance_monitor

import flet as ft

logger = logging.getLogger("reiflix.android")
MAILBOX = "reiflix-native-events.json"
BRIDGE_PROTOCOL_VERSION = 2


class AndroidBridge:
    DEFAULT_MAX_DRAIN_EVENTS = 64
    def __init__(self, data_dir: str, page=None):
        self.data_dir = Path(data_dir); self.page = page
        self.mailbox = self.data_dir / MAILBOX
        self.queue_dir = self.data_dir / "reiflix-native-events"
        self._claimed: list[Path] = []
        self._retained: set[Path] = set()
        self._command_delivery_timeout_s = max(
            1.0, float(os.getenv("REIFLIX_ANDROID_COMMAND_TIMEOUT_S", "10.0"))
        )
        self._command_delivery_waiters: dict[str, asyncio.Future] = {}
        self._command_delivery_expected_events: dict[str, str] = {}
        self._internal_command_actions = {
            "play",
            "extract_thumbnail",
            "cancel_player_transition",
        }
        self._recover_unacknowledged_batches()

    def _recover_unacknowledged_batches(self) -> None:
        """Return batches left in .consumed form by a previous Python process."""
        try:
            legacy = self.mailbox.with_suffix(".consumed")
            if legacy.exists() and not self.mailbox.exists():
                legacy.replace(self.mailbox)
            for consumed in sorted(self.queue_dir.glob("event-*.consumed")):
                target = consumed.with_suffix(".json")
                if target.exists():
                    continue
                consumed.replace(target)
        except OSError as exc:
            logger.warning("[ANDROID] Failed to recover unacknowledged batches: %s", exc)

    @property
    def available(self) -> bool:
        platform = getattr(self.page, "platform", None) if self.page else None
        value = getattr(platform, "value", platform)
        return (
            str(value).lower() == "android"
            or os.getenv("FLET_PLATFORM") == "android"
            or os.getenv("ANDROID_ARGUMENT") is not None
        )

    def observe_native_event(self, event: dict) -> None:
        """Resolve a command at the actual contract boundary, not merely at receipt."""
        performance = get_performance_monitor()
        if not isinstance(event, dict) or event.get("type") != "diagnostic":
            return
        payload = event.get("payload")
        if not isinstance(payload, dict):
            return
        event_name = str(payload.get("event") or "").strip()
        request_id = str(event.get("requestId") or payload.get("requestId") or "").strip()
        if not request_id:
            return
        waiter = self._command_delivery_waiters.get(request_id)
        if waiter is None or waiter.done():
            return
        expected_event = self._command_delivery_expected_events.get(request_id, "COMMAND_RECEIVED")
        if event_name == "COMMAND_RECEIVED":
            performance.event(
                "android.command_received",
                screen="android_bridge",
                metadata={"request_id": request_id, "action": payload.get("action"),
                          "expected_event": expected_event},
            )
            logger.info(
                "[ANDROID_BRIDGE] COMMAND_RECEIVED request_id=%s action=%s timestamp=%s "
                "expected_delivery=%s",
                request_id,
                payload.get("action") or "-",
                payload.get("timestamp") or "-",
                expected_event,
            )
            if expected_event == "COMMAND_RECEIVED":
                waiter.set_result(payload)
            return
        if event_name == expected_event:
            performance.event(
                "android.command_delivery_confirmed",
                screen="android_bridge",
                metadata={"request_id": request_id, "action": payload.get("action"),
                          "delivery_event": event_name},
            )
            logger.info(
                "[ANDROID_BRIDGE] DELIVERY_CONFIRMED request_id=%s action=%s event=%s",
                request_id,
                payload.get("action") or "-",
                event_name,
            )
            waiter.set_result(payload)
            return
        if event_name in {
            "COMMAND_FAILED",
            "PLAYER_HANDOFF_FAILED",
            "PLAYER_HANDOFF_REJECTED",
            "PLAYER_HANDOFF_DUPLICATE",
            "GOOGLE_SIGN_OUT_FAILED",
        }:
            reason = str(
                payload.get("error")
                or payload.get("result")
                or event_name
                or "native_command_failed"
            )
            waiter.set_exception(
                RuntimeError(
                    f"O Android recebeu o comando '{payload.get('action') or '-'}', "
                    f"mas não concluiu o processamento (request {request_id}, "
                    f"event={event_name}, reason={reason})."
                )
            )

    async def _launch(self, action: str, **params):
        performance = get_performance_monitor()
        launch_started = performance.now()
        if not self.available:
            raise RuntimeError("A ponte Android está disponível somente no APK ReiFlix.")
        request_id = uuid.uuid4().hex
        created_at = int(time.time() * 1000)
        created_monotonic_ns = time.monotonic_ns()
        performance.event(
            "NATIVE_COMMAND_CREATED",
            screen="android_bridge",
            metadata={
                "request_id": request_id,
                "operation": action,
                "commandCreatedAtMs": created_at,
                "commandCreatedMonotonicNs": created_monotonic_ns,
                "player_session_id": params.get("player_session_id"),
                "episode_id": params.get("episode_id"),
                "anime_id": params.get("anime_id"),
            },
        )
        query = urlencode({
            "action": action,
            "request_id": request_id,
            "protocol_version": BRIDGE_PROTOCOL_VERSION,
            "created_at": created_at,
            "created_monotonic_ns": created_monotonic_ns,
            **{k: v for k, v in params.items() if v is not None},
        })
        url = f"reiflix://native?{query}"
        loop = asyncio.get_running_loop()
        delivery_waiter = loop.create_future()
        self._command_delivery_waiters[request_id] = delivery_waiter
        if action == "google_sign_out":
            expected_event = "GOOGLE_SIGN_OUT_COMPLETED"
        else:
            expected_event = "PLAYER_HANDOFF_DISPATCHED" if action == "play" else "COMMAND_RECEIVED"
        self._command_delivery_expected_events[request_id] = expected_event
        logger.info(
            "[ANDROID_BRIDGE] COMMAND_CREATED request_id=%s action=%s created_at=%s protocol=%s",
            request_id,
            action,
            created_at,
            BRIDGE_PROTOCOL_VERSION,
        )
        logger.info(
            "[ANDROID_BRIDGE] COMMAND_LAUNCH_REQUESTED request_id=%s action=%s timestamp=%s params=%s",
            request_id,
            action,
            created_at,
            ",".join(sorted(str(key) for key, value in params.items() if value is not None)) or "-",
        )
        try:
            if action in self._internal_command_actions and self.available:
                self._write_internal_command(
                    request_id=request_id,
                    action=action,
                    created_at=created_at,
                    url=url,
                )
                performance.event(
                    "NATIVE_COMMAND_SENT",
                    duration_ms=(performance.now()-launch_started)*1000.0,
                    screen="android_bridge",
                    metadata={
                        "request_id": request_id,
                        "operation": action,
                        "commandCreatedAtMs": created_at,
                        "player_session_id": params.get("player_session_id"),
                        "episode_id": params.get("episode_id"),
                        "anime_id": params.get("anime_id"),
                        "transport": "native_command_mailbox",
                    },
                )
                logger.info(
                    "[ANDROID_BRIDGE] COMMAND_FILE_WRITE_ACCEPTED request_id=%s action=%s "
                    "timestamp=%s transport=native_command_mailbox",
                    request_id,
                    action,
                    int(time.time() * 1000),
                )
            else:
                launcher = getattr(self.page, "url_launcher", None)
                launch_mode_type = getattr(ft, "LaunchMode", None)
                external_non_browser = getattr(
                    launch_mode_type,
                    "EXTERNAL_NON_BROWSER_APPLICATION",
                    None,
                )
                if launcher is None or external_non_browser is None:
                    raise RuntimeError(
                        "Flet 0.86.5 não expôs UrlLauncher/EXTERNAL_NON_BROWSER_APPLICATION."
                    )
                await launcher.launch_url(url, mode=external_non_browser)
                performance.event(
                    "NATIVE_COMMAND_SENT",
                    duration_ms=(performance.now()-launch_started)*1000.0,
                    screen="android_bridge",
                    metadata={
                        "request_id": request_id,
                        "operation": action,
                        "commandCreatedAtMs": created_at,
                        "player_session_id": params.get("player_session_id"),
                        "episode_id": params.get("episode_id"),
                        "anime_id": params.get("anime_id"),
                        "transport": "url_launcher",
                    },
                )
                performance.event(
                    "android.launch_url",
                    duration_ms=(performance.now()-launch_started)*1000.0,
                    screen="android_bridge",
                    metadata={
                        "action": action,
                        "request_id": request_id,
                        "player_session_id": params.get("player_session_id"),
                        "episode_id": params.get("episode_id"),
                        "anime_id": params.get("anime_id"),
                    },
                )
                logger.info(
                    "[ANDROID_BRIDGE] COMMAND_LAUNCH_ACCEPTED request_id=%s action=%s "
                    "timestamp=%s launcher=UrlLauncher mode=EXTERNAL_NON_BROWSER_APPLICATION",
                    request_id,
                    action,
                    int(time.time() * 1000),
                )
        except Exception as exc:
            self._command_delivery_waiters.pop(request_id, None)
            self._command_delivery_expected_events.pop(request_id, None)
            if not delivery_waiter.done():
                delivery_waiter.cancel()
            logger.exception(
                "[ANDROID_BRIDGE] COMMAND_FAILED request_id=%s action=%s",
                request_id,
                action,
            )
            raise RuntimeError(
                f"Falha ao enviar a ação Android '{action}' (request {request_id})."
            ) from exc

        try:
            await asyncio.wait_for(
                asyncio.shield(delivery_waiter),
                timeout=self._command_delivery_timeout_s,
            )
            performance.event("android.command_delivery", duration_ms=(performance.now()-launch_started)*1000.0,
                              screen="android_bridge",
                              metadata={"action": action, "request_id": request_id, "status": "received"})
        except asyncio.TimeoutError as exc:
            if action == "play" and str(params.get("origin_request_id") or "").strip():
                try:
                    await self._launch(
                        "cancel_player_transition",
                        origin_request_id=str(params.get("origin_request_id") or "").strip(),
                        origin_player_session_id=str(params.get("origin_player_session_id") or "").strip() or None,
                    )
                    performance.event(
                        "NATIVE_PLAYER_TRANSITION_CANCEL_SENT",
                        screen="android_bridge",
                        metadata={
                            "origin_request_id": str(params.get("origin_request_id") or "").strip(),
                            "origin_player_session_id": str(params.get("origin_player_session_id") or "").strip(),
                            "reason": "play_delivery_timeout",
                        },
                    )
                except Exception as cancel_exc:
                    logger.warning(
                        "[ANDROID_BRIDGE] PLAYER_TRANSITION_CANCEL_FAILED origin_request_id=%s error=%s",
                        str(params.get("origin_request_id") or "").strip(),
                        cancel_exc,
                    )

            performance.event(
                "android.command_delivery",
                duration_ms=(performance.now()-launch_started)*1000.0,
                status="timeout",
                screen="android_bridge",
                metadata={"action": action, "request_id": request_id,
                          "expected_event": expected_event},
            )
            performance.event(
                "NATIVE_COMMAND_DELIVERY_TIMEOUT",
                duration_ms=(performance.now()-launch_started)*1000.0,
                status="timeout",
                screen="android_bridge",
                metadata={"request_id": request_id, "operation": action,
                          "expected_event": expected_event},
            )
            logger.error(
                "[ANDROID_BRIDGE] COMMAND_DELIVERY_TIMEOUT request_id=%s action=%s "
                "timeout_s=%s expected=%s",
                request_id,
                action,
                self._command_delivery_timeout_s,
                expected_event,
            )
            if expected_event == "PLAYER_HANDOFF_DISPATCHED":
                raise RuntimeError(
                    f"O comando Android '{action}' chegou à MainActivity, mas o handoff "
                    f"para o player não foi confirmado (request {request_id}) dentro de "
                    f"{self._command_delivery_timeout_s:.1f}s."
                ) from exc
            raise RuntimeError(
                f"O comando Android '{action}' não chegou à MainActivity "
                f"(request {request_id}) dentro de {self._command_delivery_timeout_s:.1f}s."
            ) from exc
        finally:
            self._command_delivery_waiters.pop(request_id, None)
            self._command_delivery_expected_events.pop(request_id, None)
            if not delivery_waiter.done():
                delivery_waiter.cancel()
        performance.event(
            "NATIVE_HANDOFF_ACCEPTED" if action == "play" else "NATIVE_COMMAND_ACCEPTED",
            duration_ms=(performance.now()-launch_started)*1000.0,
            screen="android_bridge",
            metadata={"request_id": request_id, "operation": action,
                      "expected_event": expected_event},
        )
        logger.info(
            "[ANDROID_BRIDGE] COMMAND_SENT request_id=%s action=%s timestamp=%s "
            "delivery=%s_CONFIRMED",
            request_id,
            action,
            int(time.time() * 1000),
            expected_event,
        )
        return request_id

    def _write_internal_command(self, *, request_id: str, action: str, created_at: int, url: str) -> None:
        command_dir = self.data_dir / "reiflix-native-commands"
        command_dir.mkdir(parents=True, exist_ok=True)
        target = command_dir / f"command-{request_id}.json"
        temporary = command_dir / f".command-{request_id}.tmp"
        payload = json.dumps(
            {
                "version": 1,
                "requestId": request_id,
                "action": action,
                "createdAt": created_at,
                "url": url,
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
        try:
            with temporary.open("w", encoding="utf-8") as stream:
                stream.write(payload)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, target)
        except Exception:
            try:
                temporary.unlink(missing_ok=True)
            except OSError:
                pass
            raise
        logger.info(
            "[ANDROID_BRIDGE] INTERNAL_COMMAND_FILE_WRITTEN request_id=%s action=%s path=%s",
            request_id,
            action,
            target,
        )

    async def select_tree(self): return await self._launch("select_tree")
    async def rescan_tree(self, tree_uri: str): return await self._launch("scan_tree", tree_uri=tree_uri)
    async def scan_media_store(self): return await self._launch("scan_media_store")
    async def request_media_access(self): return await self._launch("request_media_access")
    async def check_storage_access(self): return await self._launch("check_storage_access")
    async def open_broad_storage_settings(self): return await self._launch("open_broad_storage_settings")
    async def open_storage_settings(self): return await self._launch("open_storage_settings")
    async def scan_all_storage(self): return await self._launch("scan_all_storage")
    async def request_thumbnail(self, uri: str, size: int = 0, modified_at: int = 0, media_identity: str = ""): return await self._launch("extract_thumbnail", uri=uri, size=max(0, int(size)), modified_at=max(0, int(modified_at)), media_identity=str(media_identity or ""))
    async def cancel_scans(self): return await self._launch("cancel_scan")
    async def open_library(self): return await self._launch("open_library")
    async def open_organize(self): return await self._launch("open_organize")
    async def hide_library(self): return await self._launch("hide_library")
    async def open_settings(self): return await self._launch("open_settings")
    async def hide_settings(self): return await self._launch("hide_settings")
    async def verify_tree(self, tree_uri: str): return await self._launch("verify_tree", tree_uri=tree_uri)
    async def release_tree(self, tree_uri: str): return await self._launch("release_tree", tree_uri=tree_uri)
    async def sign_in(self, server_client_id: str): return await self._launch("google_sign_in", server_client_id=server_client_id)
    async def sign_out(self): return await self._launch("google_sign_out")

    async def play(self, uri: str, title: str, position_ms: int = 0, *, can_next=False,
                   can_previous=False, autoplay=False, player_settings=None,
                   episode_id=None, anime_id=None, player_session_id=None,
                   origin_request_id=None, origin_created_at_ms=0, origin_transition_generation=0,
                   origin_player_session_id=None, origin_monotonic_ns=0, transition_direction=None):
        normalized_uri = self.normalize_local_media_reference(uri)
        if normalized_uri is None:
            raise ValueError("A reprodução aceita somente arquivos locais ou URIs content://.")
        canonical_episode_id = str(episode_id or "").strip()
        if not canonical_episode_id:
            raise ValueError("A reprodução requer um episode_id válido.")
        try:
            if int(canonical_episode_id) <= 0:
                raise ValueError
        except (TypeError, ValueError) as exc:
            raise ValueError("A reprodução requer um episode_id válido.") from exc
        return await self._launch(
            "play",
            uri=normalized_uri,
            title=title,
            position_ms=max(0, int(position_ms)),
            can_next=str(bool(can_next)).lower(),
            can_previous=str(bool(can_previous)).lower(),
            autoplay=str(bool(autoplay)).lower(),
            episode_id=str(episode_id) if episode_id is not None else None,
            anime_id=str(anime_id) if anime_id is not None else None,
            player_session_id=str(player_session_id).strip() if player_session_id else None,
            origin_request_id=str(origin_request_id).strip() if origin_request_id else None,
            origin_created_at=max(0, int(origin_created_at_ms or 0)) if origin_created_at_ms else None,
            origin_transition_generation=max(0, int(origin_transition_generation or 0)),
            origin_player_session_id=str(origin_player_session_id).strip() if origin_player_session_id else None,
            origin_monotonic_ns=max(0, int(origin_monotonic_ns or 0)),
            transition_direction=str(transition_direction or "").strip().upper() or None,
            **({
                f"setting_{key.replace('.', '_')}": str(value).lower() if isinstance(value, bool) else str(value)
                for key, value in (player_settings or {}).items()
            }),
        )

    @staticmethod
    def normalize_local_media_reference(reference: str) -> str | None:
        value = str(reference or "").strip()
        if not value:
            return None
        if os.path.isabs(value):
            try:
                return Path(value).resolve().as_uri()
            except (OSError, ValueError):
                return None
        try:
            from urllib.parse import urlsplit, urlunsplit
            parsed = urlsplit(value)
        except ValueError:
            return None
        scheme = parsed.scheme.lower()
        if scheme not in {"content", "file"}:
            return None
        if scheme == "content" and not parsed.netloc:
            return None
        normalized = urlunsplit((scheme, parsed.netloc, parsed.path, parsed.query, parsed.fragment))
        return normalized or None

    @classmethod
    def is_local_media_reference(cls, uri: str) -> bool:
        return cls.normalize_local_media_reference(uri) is not None

    @staticmethod
    def _normalize_event(event: dict, source_name: str, index: int) -> dict | None:
        if not isinstance(event, dict):
            return None
        normalized = dict(event)
        event_id = str(normalized.get("eventId") or "").strip()
        if not event_id:
            canonical = json.dumps(event, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
            digest = hashlib.sha256(canonical).hexdigest()[:24]
            event_id = f"legacy:{source_name}:{index}:{digest}"
            normalized["eventId"] = event_id
            logger.warning("[ANDROID] EVENT_ID_MISSING source=%s synthesized=%s", source_name, event_id)
        event_type = str(normalized.get("type") or "").strip()
        if not event_type:
            logger.error("[ANDROID] EVENT_REJECTED source=%s eventId=%s reason=missing_type", source_name, event_id)
            return None
        try:
            created_at = float(normalized.get("createdAt") or normalized.get("timestamp") or 0)
        except (TypeError, ValueError):
            created_at = 0
        normalized["createdAt"] = created_at
        logger.info(
            "[ANDROID] EVENT_CLAIMED eventId=%s type=%s requestId=%s createdAt=%s",
            event_id,
            event_type,
            str(normalized.get("requestId") or ""),
            created_at,
        )
        return normalized

    @staticmethod
    def _event_time(event: dict) -> float:
        for key in ("createdAt", "timestamp"):
            value = event.get(key)
            try:
                return float(value)
            except (TypeError, ValueError):
                continue
        return float("inf")

    def pending_count(self) -> int:
        """Return the number of queued event files plus an unclaimed legacy batch."""
        try:
            legacy = 1 if self.mailbox.exists() else 0
            return legacy + sum(1 for _ in self.queue_dir.glob("event-*.json"))
        except OSError:
            return 0

    def _migrate_legacy_mailbox(self) -> None:
        """Migrate the legacy batch into individually claimable event files.
        
        The legacy format stores multiple events in one JSON document, so it
        cannot be safely acknowledged or requeued one event at a time. Convert
        it to the modern per-event format before applying the bounded drain.
        """
        if not self.mailbox.exists():
            return
        legacy = self.mailbox.with_suffix(".consumed")
        try:
            self.mailbox.replace(legacy)
        except OSError as exc:
            logger.error("[ANDROID] Failed to claim legacy native mailbox: %s", exc)
            return

        try:
            payload = json.loads(legacy.read_text(encoding="utf-8"))
            raw_events = (
                payload if isinstance(payload, list)
                else [payload] if isinstance(payload, dict)
                else []
            )
            migrated = 0
            for index, event in enumerate(raw_events):
                normalized = self._normalize_event(event, legacy.name, index)
                if normalized is None:
                    continue
                event_id = str(normalized["eventId"]).strip()
                digest = hashlib.sha256(event_id.encode("utf-8")).hexdigest()[:24]
                target = self.queue_dir / f"event-legacy-{digest}.json"
                if target.exists():
                    continue
                temp = target.with_suffix(".tmp")
                temp.write_text(
                    json.dumps(normalized, ensure_ascii=False, separators=(",", ":")),
                    encoding="utf-8",
                )
                temp.replace(target)
                migrated += 1
            if migrated == 0 and raw_events:
                logger.error("[ANDROID] Invalid legacy native mailbox batch discarded: %s", legacy.name)
            legacy.unlink(missing_ok=True)
            logger.info("[ANDROID] LEGACY_MAILBOX_MIGRATED events=%s", migrated)
        except (OSError, json.JSONDecodeError) as exc:
            logger.error(
                "[ANDROID] Legacy native mailbox migration failed; "
                "claimed batch retained for recovery: %s",
                exc,
            )

    def drain(self, max_events: int | None = None) -> list[dict]:
        """Claim a bounded, time-ordered slice of native events."""
        if self._claimed:
            return []
        limit = self.DEFAULT_MAX_DRAIN_EVENTS if max_events is None else max(1, int(max_events))
        events: list[dict] = []
        claimed: list[Path] = []
        try:
            self.queue_dir.mkdir(parents=True, exist_ok=True)
            self._migrate_legacy_mailbox()

            candidates: list[tuple[float, int, Path]] = []
            for index, source in enumerate(sorted(self.queue_dir.glob("event-*.json"))):
                try:
                    payload = json.loads(source.read_text(encoding="utf-8"))
                except (OSError, json.JSONDecodeError) as exc:
                    logger.error("[ANDROID] Invalid native mailbox event discarded: %s (%s)", source.name, exc)
                    source.unlink(missing_ok=True)
                    continue
                if not isinstance(payload, dict):
                    logger.warning("[ANDROID] Ignoring non-object modern mailbox payload file=%s", source.name)
                    continue
                normalized = self._normalize_event(payload, source.name, 0)
                if normalized is None:
                    continue
                event_time = self._event_time(normalized)
                candidate = (-event_time, -index, source)
                if len(candidates) < limit:
                    heapq.heappush(candidates, candidate)
                elif candidate > candidates[0]:
                    heapq.heapreplace(candidates, candidate)

            selected = sorted(
                ((-item[0], -item[1], item[2]) for item in candidates),
                key=lambda item: (item[0], item[1]),
            )
            for _, _, source in selected:
                consumed = source.with_suffix(".consumed")
                try:
                    payload = json.loads(source.read_text(encoding="utf-8"))
                    if not isinstance(payload, dict):
                        continue
                    normalized = self._normalize_event(payload, source.name, 0)
                    if normalized is None:
                        continue
                    source.replace(consumed)
                except (OSError, json.JSONDecodeError) as exc:
                    logger.warning("[ANDROID] Failed to claim selected native event %s: %s", source.name, exc)
                    continue
                claimed.append(consumed)
                events.append(normalized)

            self._claimed = claimed
            self._retained = set()
            events.sort(key=self._event_time)
            coalesced = self._coalesce_progress_events(events)
            if len(coalesced) != len(events):
                logger.info(
                    "[ANDROID] PLAYER_PROGRESS_COALESCED selected=%s retained=%s",
                    len(events),
                    len(coalesced),
                )
            return coalesced
        except OSError as exc:
            logger.error("[ANDROID] Native mailbox drain failed; claimed events will be restored/retried: %s", exc)
            for path in claimed:
                try:
                    if path.suffix == ".consumed":
                        path.replace(path.with_suffix(".json"))
                except OSError as restore_exc:
                    logger.warning("[ANDROID] Failed to restore native event %s: %s", path.name, restore_exc)
            self._claimed = []
            self._retained = set()
            return []

    @staticmethod
    def _coalesce_progress_events(events: list[dict]) -> list[dict]:
        """Collapse only consecutive progress samples for the exact playback identity.

        Control-plane events (pause/completion/exit/transition) remain untouched,
        and progress from different sessions/episodes/requests is never merged.
        Claimed mailbox files still acknowledge normally because coalescing happens
        after claim, inside the existing drain pipeline.
        """
        compacted: list[dict] = []
        for event in events:
            event_type = str(event.get("eventType") or event.get("type") or "").strip()
            payload = event.get("payload") if isinstance(event.get("payload"), dict) else {}
            if (
                compacted
                and event_type == "player_progress"
                and str(compacted[-1].get("eventType") or compacted[-1].get("type") or "").strip() == "player_progress"
            ):
                previous_payload = compacted[-1].get("payload") if isinstance(compacted[-1].get("payload"), dict) else {}
                identity = (
                    str(payload.get("playerSessionId") or "").strip(),
                    str(payload.get("activityInstanceId") or "").strip(),
                    str(event.get("requestId") or payload.get("requestId") or "").strip(),
                    str(payload.get("episodeId") or "").strip(),
                    str(payload.get("uri") or "").strip(),
                    str(payload.get("generation") or payload.get("playerGeneration") or "").strip(),
                    str(payload.get("transitionGeneration") or "").strip(),
                )
                previous_identity = (
                    str(previous_payload.get("playerSessionId") or "").strip(),
                    str(previous_payload.get("activityInstanceId") or "").strip(),
                    str(compacted[-1].get("requestId") or previous_payload.get("requestId") or "").strip(),
                    str(previous_payload.get("episodeId") or "").strip(),
                    str(previous_payload.get("uri") or "").strip(),
                    str(previous_payload.get("generation") or previous_payload.get("playerGeneration") or "").strip(),
                    str(previous_payload.get("transitionGeneration") or "").strip(),
                )
                if any(identity) and identity == previous_identity:
                    compacted[-1] = event
                    continue
            compacted.append(event)
        return compacted

    def requeue_event_ids(self, event_ids: set[str]) -> None:
        wanted = {str(item).strip() for item in event_ids if str(item).strip()}
        if not wanted:
            return
        for consumed in list(self._claimed):
            try:
                payload = json.loads(consumed.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                self._retained.add(consumed)
                continue
            if isinstance(payload, dict) and str(payload.get("eventId") or "").strip() in wanted:
                try:
                    consumed.replace(consumed.with_suffix(".json"))
                    logger.info("[ANDROID] EVENT_REQUEUED eventId=%s", str(payload.get("eventId") or "-"))
                except OSError as exc:
                    self._retained.add(consumed)
                    logger.warning("[ANDROID] Failed to requeue native event %s: %s", consumed.name, exc)

    def acknowledge(self) -> None:
        if not self._claimed:
            return
        for consumed in self._claimed:
            if consumed in self._retained:
                continue
            try:
                consumed.unlink(missing_ok=True)
                logger.info("[ANDROID] EVENT_ACKED file=%s", consumed.name)
            except OSError as exc:
                logger.warning("[ANDROID] Failed to acknowledge native event %s: %s", consumed.name, exc)
        self._claimed = []
        self._retained = set()


__all__ = ["AndroidBridge"]
