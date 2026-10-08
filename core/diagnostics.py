"""Bounded end-to-end diagnostic timeline for ReiAnix.

The timeline is intentionally observational: it never becomes a second source of
truth for permissions or the catalog. It records the hand-off between native
Android, Python ingestion, persistence, UI refresh, and player events.
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass, asdict
import json
import logging
import time
from typing import Any

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class DiagnosticEvent:
    name: str
    timestamp_ms: int
    request_id: str | None = None
    scan_id: str | None = None
    source: str | None = None
    result: str | None = None
    refresh_id: str | None = None
    counts: dict[str, int] | None = None
    error: str | None = None
    extra: dict[str, Any] | None = None


class DiagnosticTimeline:
    """Keep the latest diagnostic hand-off events in memory and structured logs."""

    def __init__(self, max_events: int = 300):
        self._events: deque[DiagnosticEvent] = deque(maxlen=max_events)

    def record(self, name: str, *, request_id: Any = None, scan_id: Any = None,
               source: Any = None, result: Any = None, refresh_id: Any = None,
               counts: dict[str, Any] | None = None, error: Any = None,
               **extra: Any) -> DiagnosticEvent:
        normalized_counts = None
        if isinstance(counts, dict):
            normalized_counts = {
                str(k): int(v) for k, v in counts.items()
                if isinstance(v, (int, float)) and not isinstance(v, bool)
            } or None
        normalized_extra = None
        if extra:
            normalized_extra = {str(key): self._normalize_extra(value) for key, value in extra.items()}

        event = DiagnosticEvent(
            name=str(name),
            timestamp_ms=int(time.time() * 1000),
            request_id=str(request_id).strip() or None if request_id is not None else None,
            scan_id=str(scan_id).strip() or None if scan_id is not None else None,
            source=str(source).strip() or None if source is not None else None,
            result=str(result).strip() or None if result is not None else None,
            refresh_id=str(refresh_id).strip() or None if refresh_id is not None else None,
            counts=normalized_counts,
            error=str(error)[:500] if error else None,
            extra=normalized_extra,
        )
        self._events.append(event)
        logger.info("[E2E] %s", json.dumps(asdict(event), ensure_ascii=False, sort_keys=True))
        return event

    @classmethod
    def _normalize_extra(cls, value: Any) -> Any:
        if value is None or isinstance(value, (str, int, float, bool)):
            return value
        if isinstance(value, dict):
            return {str(key): cls._normalize_extra(item) for key, item in value.items()}
        if isinstance(value, (list, tuple, set)):
            return [cls._normalize_extra(item) for item in value]
        return str(value)

    def snapshot(self) -> list[dict[str, Any]]:
        return [asdict(event) for event in self._events]

    def clear(self) -> None:
        self._events.clear()
