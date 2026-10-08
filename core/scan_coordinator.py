"""Central coordination for local-library discovery requests.

The coordinator owns logical scan scheduling. Native scanner objects remain
execution engines; LibraryService/LibraryStore remain domain and persistence
layers. No Flet or Android UI objects are imported here.
"""
from __future__ import annotations

import asyncio
import logging
import time
import uuid
from dataclasses import dataclass, field
from enum import Enum
from typing import Awaitable, Callable, Iterable

logger = logging.getLogger("reiflix.scan")


class ScanOrigin(str, Enum):
    STARTUP = "STARTUP"
    USER_REFRESH = "USER_REFRESH"
    MEDIA_CHANGE = "MEDIASTORE_CHANGE"
    SAF_CHANGE = "SAF_CHANGE"
    PERMISSION_CHANGE = "PERMISSION_CHANGE"
    VOLUME_MOUNT = "VOLUME_MOUNT"
    VOLUME_UNMOUNT = "VOLUME_UNMOUNT"
    RECOVERY = "RECOVERY"
    EXPLICIT_FULL_RESCAN = "EXPLICIT_FULL_RESCAN"
    BACKGROUND_RECONCILIATION = "BACKGROUND_RECONCILIATION"
    RESTORE_RECONCILIATION = "RESTORE_RECONCILIATION"


class ScanState(str, Enum):
    IDLE = "IDLE"
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    CANCELLING = "CANCELLING"
    COMPLETED = "COMPLETED"
    CANCELLED = "CANCELLED"
    FAILED = "FAILED"
    PARTIAL = "PARTIAL"
    BLOCKED = "BLOCKED"


_PRIORITY = {
    ScanOrigin.EXPLICIT_FULL_RESCAN: 100,
    ScanOrigin.USER_REFRESH: 90,
    ScanOrigin.PERMISSION_CHANGE: 80,
    ScanOrigin.RECOVERY: 75,
    ScanOrigin.STARTUP: 70,
    ScanOrigin.VOLUME_MOUNT: 60,
    ScanOrigin.SAF_CHANGE: 55,
    ScanOrigin.MEDIA_CHANGE: 40,
    ScanOrigin.BACKGROUND_RECONCILIATION: 10,
    ScanOrigin.RESTORE_RECONCILIATION: 65,
    ScanOrigin.VOLUME_UNMOUNT: 95,
}


@dataclass(frozen=True)
class ScanTarget:
    source: str
    scope_ref: str | None = None


@dataclass(frozen=True)
class ScanRequest:
    request_id: str
    origin: ScanOrigin
    source: str | None = None
    scope_ref: str | None = None
    full: bool = False
    reason: str = ""
    created_at: float = field(default_factory=time.monotonic)

    @property
    def priority(self) -> int:
        return _PRIORITY[self.origin]

    @property
    def key(self) -> tuple[str | None, str | None, bool]:
        return (self.source, self.scope_ref, self.full)


@dataclass(frozen=True)
class ScanSnapshot:
    state: ScanState
    request_id: str | None
    origin: str | None
    source: str | None
    active_children: int
    pending_requests: int
    cancel_requested: bool
    last_result: str | None = None

    def as_dict(self) -> dict:
        return {
            "state": self.state.value,
            "requestId": self.request_id,
            "origin": self.origin,
            "source": self.source,
            "activeChildren": self.active_children,
            "pendingRequests": self.pending_requests,
            "cancelRequested": self.cancel_requested,
            "lastResult": self.last_result,
        }


@dataclass(frozen=True)
class ScanTransition:
    accepted: bool
    kind: str
    request_id: str | None = None
    logical_finished: bool = False
    refresh_required: bool = False
    message: str = ""


class ScanCoordinator:
    """Single logical coordinator for all discovery/refresh requests."""

    TERMINAL_EVENT_TYPES = {
        "saf_scan",
        "broad_storage_scan",
        "mediastore_scan",
        "saf_error",
        "broad_storage_error",
        "mediastore_error",
    }

    WAITING_STATUSES = {"WAITING_FOR_MEDIASTORE"}
    SOURCE_ALIASES = {
        "broad-storage": "broad_storage",
        "broad": "broad_storage",
        "mediastore:external:video": "mediastore",
    }

    def __init__(
        self,
        bridge,
        store,
        target_provider: Callable[[str | None, str | None], Iterable[ScanTarget]],
        *,
        on_state: Callable[[ScanSnapshot], None] | None = None,
        clock: Callable[[], float] = time.monotonic,
    ):
        self.bridge = bridge
        self.store = store
        self.target_provider = target_provider
        self.on_state = on_state
        self._clock = clock
        self._lock = asyncio.Lock()
        self._active_request: ScanRequest | None = None
        self._active_children: dict[str, ScanTarget] = {}
        self._child_statuses: dict[str, str] = {}
        self._launch_failures = 0
        self._pending: list[ScanRequest] = []
        self._cancel_requested = False
        self._last_result: str | None = None
        self._exclusive_reason: str | None = None
        self._state = ScanState.IDLE

    @property
    def active(self) -> bool:
        return self._active_request is not None

    @property
    def exclusive(self) -> bool:
        return self._exclusive_reason is not None

    async def begin_exclusive(self, reason: str = "exclusive_operation") -> bool:
        """Reserve the coordinator for a destructive coordinated operation."""
        async with self._lock:
            if self._active_request is not None or self._pending:
                return False
            self._exclusive_reason = str(reason)
            self._log("SCAN_EXCLUSIVE_BEGIN", ScanRequest(
                request_id=uuid.uuid4().hex,
                origin=ScanOrigin.RESTORE_RECONCILIATION,
                reason=str(reason),
            ))
            self._emit()
            return True

    async def end_exclusive(self) -> None:
        async with self._lock:
            self._exclusive_reason = None
            self._emit()

    @property
    def snapshot(self) -> ScanSnapshot:
        request = self._active_request
        return ScanSnapshot(
            state=self._state,
            request_id=request.request_id if request else None,
            origin=request.origin.value if request else None,
            source=request.source if request else None,
            active_children=len(self._active_children),
            pending_requests=len(self._pending),
            cancel_requested=self._cancel_requested,
            last_result=self._last_result,
        )

    def _emit(self) -> None:
        if not self.on_state:
            return
        try:
            self.on_state(self.snapshot)
        except Exception:
            # State observers are informational/UI plumbing. A broken observer
            # must never abort the real scan state machine or native dispatch.
            logger.exception("[SCAN] on_state listener failed")

    def _log(self, event: str, request: ScanRequest, **extra) -> None:
        details = {
            "requestId": request.request_id,
            "origin": request.origin.value,
            "source": request.source or "all",
            **extra,
        }
        logger.info("[SCAN] %s %s", event, details)

    @classmethod
    def normalize_source(cls, source: str | None) -> str | None:
        value = str(source or "").strip().lower()
        if not value or value == "all":
            return None
        return cls.SOURCE_ALIASES.get(value, value)

    def _request_covers(self, broader: ScanRequest, narrower: ScanRequest) -> bool:
        if broader.full and broader.source is None:
            return True
        if broader.source is None and narrower.source is None:
            return broader.full >= narrower.full
        if broader.source != narrower.source:
            return False
        return broader.scope_ref is None or broader.scope_ref == narrower.scope_ref

    def _enqueue_pending(self, request: ScanRequest) -> str:
        for existing in self._pending:
            if self._request_covers(existing, request):
                self._log("SCAN_DEDUPED", request, against=existing.request_id, queued=True)
                return "deduped"
        kept: list[ScanRequest] = []
        for existing in self._pending:
            if self._request_covers(request, existing):
                self._log("SCAN_COALESCED", existing, superseded_by=request.request_id)
                continue
            kept.append(existing)
        kept.append(request)
        kept.sort(key=lambda item: (-item.priority, item.created_at))
        self._pending = kept[:8]
        self._log("SCAN_COALESCED", request, pending=len(self._pending))
        return "queued"

    def _startup_needed(self) -> bool:
        last = self.store.last_scan()
        if not last:
            return True
        status = str(last.get("status") or "").casefold()
        return status not in {"completed", "complete", "empty_complete"}

    async def request_restore_reconciliation(self, *, source: str | None = None, scope_ref: str | None = None, reason: str = "post_restore") -> ScanTransition:
        """Explicit restore reconciliation; never runs automatically from restore."""
        return await self.request(
            ScanOrigin.RESTORE_RECONCILIATION,
            source=source,
            scope_ref=scope_ref,
            full=False,
            reason=reason,
        )

    async def request(
        self,
        origin: ScanOrigin | str,
        *,
        source: str | None = None,
        scope_ref: str | None = None,
        full: bool = False,
        reason: str = "",
        request_id: str | None = None,
    ) -> ScanTransition:
        try:
            origin = ScanOrigin(origin)
        except ValueError as exc:
            raise ValueError(f"Unknown scan origin: {origin}") from exc
        source = self.normalize_source(source)
        if self._exclusive_reason is not None:
            return ScanTransition(True, "blocked", message=f"exclusive_operation:{self._exclusive_reason}")
        if origin == ScanOrigin.BACKGROUND_RECONCILIATION:
            return ScanTransition(True, "ignored", message="background_reconciliation_disabled")
        if origin == ScanOrigin.STARTUP and not self._startup_needed():
            logger.info("[SCAN] SCAN_DEDUPED startup reason=catalog_already_indexed")
            return ScanTransition(True, "deduped", message="startup_scan_not_needed")
        request = ScanRequest(
            request_id=request_id or uuid.uuid4().hex,
            origin=origin,
            source=source,
            scope_ref=scope_ref,
            full=full or origin == ScanOrigin.EXPLICIT_FULL_RESCAN,
            reason=reason,
        )
        self._log("SCAN_STARTED", request)
        logger.info("[SCAN] instrumentation monotonic_ns=%s", time.monotonic_ns())
        async with self._lock:
            if self._exclusive_reason is not None:
                return ScanTransition(
                    True,
                    "blocked",
                    message=f"exclusive_operation:{self._exclusive_reason}",
                )
            if self._active_request is not None:
                if self._request_covers(self._active_request, request):
                    self._log("SCAN_DEDUPED", request, active=self._active_request.request_id)
                    return ScanTransition(True, "deduped", self._active_request.request_id, message="scan already running")
                kind = self._enqueue_pending(request)
                self._state = ScanState.QUEUED
                self._emit()
                return ScanTransition(True, kind, request.request_id, message="scan queued")
            self._active_request = request
            self._cancel_requested = False
            self._last_result = None
            self._state = ScanState.RUNNING
            self._emit()
            self._log("SCAN_REQUEST", request)
        transition = await self._dispatch_active()
        return transition

    async def _dispatch_active(self) -> ScanTransition:
        request = self._active_request
        if request is None:
            return ScanTransition(True, "ignored")
        targets = list(self.target_provider(request.source, request.scope_ref))
        if not targets:
            self._log("SCAN_SOURCE_REJECTED", request, reason="no_authorized_library_source")
            async with self._lock:
                self._state = ScanState.BLOCKED
                self._last_result = "no_authorized_scan_source"
                self._emit()
                self._active_request = None
                self._cancel_requested = False
            return ScanTransition(
                True,
                "blocked",
                request.request_id,
                logical_finished=True,
                message="no authorized scan source",
            )

        failed_launches = []
        async with self._lock:
            self._active_children.clear()
            self._child_statuses.clear()
            self._launch_failures = 0

        for target in targets:
            try:
                self._log("SCAN_SOURCE_STARTED", request, target=target.source, scope_ref=target.scope_ref)
                if target.source == "mediastore":
                    child_id = await self.bridge.scan_media_store()
                elif target.source == "broad_storage":
                    child_id = await self.bridge.scan_all_storage()
                elif target.source == "saf":
                    if not target.scope_ref:
                        self._log("SCAN_SOURCE_REJECTED", request, target=target.source, reason="missing_scope")
                        failed_launches.append((target, ValueError("missing SAF library source scope")))
                        continue
                    child_id = await self.bridge.rescan_tree(target.scope_ref)
                else:
                    raise ValueError(f"unsupported scan source: {target.source}")
            except Exception as exc:
                failed_launches.append((target, exc))
                self._log(
                    "SCAN_SOURCE_REJECTED",
                    request,
                    target=target.source,
                    scope_ref=target.scope_ref,
                    reason=f"launch_failed:{exc.__class__.__name__}",
                )
                logger.exception("[SCAN] launch failed source=%s scope=%s", target.source, target.scope_ref)
                continue
            async with self._lock:
                self._active_children[str(child_id)] = target
                self._emit()
            self._log("SCAN_SOURCE_DISPATCHED", request, child_request_id=str(child_id),
                      target=target.source, scope_ref=target.scope_ref)

        self._launch_failures = len(failed_launches)
        if failed_launches and not self._active_children:
            return await self._finish_logical("FAILED", refresh_required=False)
        if failed_launches:
            logger.warning("[SCAN] some child launches failed count=%s", len(failed_launches))
        self._emit()
        return ScanTransition(True, "started", request.request_id, message="scan started")

    async def cancel(self) -> ScanTransition:
        async with self._lock:
            if self._active_request is None:
                return ScanTransition(True, "ignored", message="no active scan")
            self._cancel_requested = True
            self._pending.clear()
            self._state = ScanState.CANCELLING
            request = self._active_request
            self._emit()
            self._log("SCAN_CANCEL_REQUESTED", request)
        try:
            await self.bridge.cancel_scans()
        except Exception:
            logger.exception("[SCAN] cancel dispatch failed")
        return ScanTransition(True, "cancelling", request.request_id, message="scan cancellation requested")

    async def handle_native_event(self, event_type: str, request_id: str | None, payload: dict) -> ScanTransition:
        request_id = str(request_id or payload.get("requestId") or "").strip()
        if event_type not in self.TERMINAL_EVENT_TYPES:
            return ScanTransition(False, "ignored")
        status = str(payload.get("status") or payload.get("generationStatus") or (payload.get("stats") or {}).get("status") or "").upper()
        if status in self.WAITING_STATUSES:
            self._emit()
            return ScanTransition(True, "waiting", self._active_request.request_id if self._active_request else None, message=status)
        async with self._lock:
            if request_id and request_id not in self._active_children:
                if self._active_request is None:
                    self._last_result = ScanState.COMPLETED.value
                    return ScanTransition(
                        True,
                        "unmatched",
                        request_id,
                        logical_finished=True,
                        refresh_required=True,
                    )
                return ScanTransition(False, "unmatched")
            completed_target = self._active_children.pop(request_id, None) if request_id else None
            if completed_target is not None:
                self._log(
                    "SCAN_SOURCE_COMPLETED",
                    self._active_request,
                    child_request_id=request_id,
                    target=completed_target.source,
                    scope_ref=completed_target.scope_ref,
                    status=status,
                )
            if self._active_request is None:
                return ScanTransition(True, "ignored")
            child_state = (
                ScanState.CANCELLED.value if "CANCEL" in status else
                ScanState.FAILED.value if "FAIL" in status or "ERROR" in event_type.upper() else
                ScanState.PARTIAL.value if "PARTIAL" in status or "UNAVAILABLE" in status else
                ScanState.COMPLETED.value
            )
            if request_id:
                self._child_statuses[request_id] = child_state
            statuses = list(self._child_statuses.values())
            if self._launch_failures:
                statuses.append(ScanState.FAILED.value)
            if ScanState.CANCELLED.value in statuses:
                self._last_result = ScanState.CANCELLED.value
            elif ScanState.FAILED.value in statuses:
                self._last_result = ScanState.FAILED.value
            elif ScanState.PARTIAL.value in statuses:
                self._last_result = ScanState.PARTIAL.value
            else:
                self._last_result = ScanState.COMPLETED.value
            self._emit()
            if self._active_children:
                return ScanTransition(True, "child_completed")
        return await self._finish_logical(
            self._last_result or ScanState.COMPLETED.value,
            refresh_required=True,
        )

    async def _finish_logical(self, status: str, *, refresh_required: bool) -> ScanTransition:
        async with self._lock:
            request = self._active_request
            if request is None:
                return ScanTransition(True, "ignored")
            logical_id = request.request_id
            pending = self._pending
            self._pending = []
            self._state = ScanState(status) if status in ScanState._value2member_map_ else ScanState.COMPLETED
            self._last_result = status
            self._emit()
            self._active_request = None
            self._active_children.clear()
            self._child_statuses.clear()
            self._launch_failures = 0
            self._cancel_requested = False
            next_request = pending[0] if pending else None
            if pending:
                self._pending = pending[1:]
                self._active_request = next_request
                self._state = ScanState.RUNNING
                self._emit()
        if next_request:
            self._log("SCAN_REQUEST", next_request, chained=True)
            await self._dispatch_active()
            return ScanTransition(True, "chained", logical_id, logical_finished=False, refresh_required=False)
        self._log(
            "SCAN_COMPLETED" if status == ScanState.COMPLETED.value else f"SCAN_{status}",
            request,
            pending_after=len(self._pending),
        )
        self._log("SCAN_FINISHED", request, status=status, duration_monotonic_hint_ms=None)
        return ScanTransition(True, "completed", logical_id, logical_finished=True, refresh_required=refresh_required)

    async def event_refresh_required(self, transition: ScanTransition) -> bool:
        return bool(transition.logical_finished and transition.refresh_required)

    async def snapshot_dict(self) -> dict:
        return self.snapshot.as_dict()
