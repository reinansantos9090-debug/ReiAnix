"""Low-overhead, opt-in performance instrumentation for ReiAnix.

The monitor is observational and bounded. It aggregates timings, counters,
gauges and task lifecycle data in memory and is disabled unless
REIANIX_PERF_DIAGNOSTICS is enabled.
"""
from __future__ import annotations

from collections import Counter, deque
from contextlib import asynccontextmanager, contextmanager
from contextvars import ContextVar
from dataclasses import asdict, dataclass
import asyncio
import hashlib
import inspect
import json
import logging
import os
import threading
import time
import uuid
from typing import Any, Callable

logger = logging.getLogger("reiflix.performance")
_INTERACTION: ContextVar[str | None] = ContextVar("reiflix_perf_interaction", default=None)


def _truthy(value: str | None) -> bool:
    return str(value or "").strip().casefold() in {"1", "true", "yes", "on", "debug"}


@dataclass(frozen=True)
class PerformanceEvent:
    name: str
    timestamp_ms: int
    monotonic_ns: int
    duration_ms: float | None = None
    interaction_id: str | None = None
    screen: str | None = None
    thread: str | None = None
    task_id: str | None = None
    status: str | None = None
    metadata: dict[str, Any] | None = None


class PerformanceMonitor:
    def __init__(self, *, enabled: bool | None = None, max_events: int = 600):
        self.enabled = _truthy(os.getenv("REIANIX_PERF_DIAGNOSTICS")) if enabled is None else bool(enabled)
        self._events: deque[PerformanceEvent] = deque(maxlen=max_events)
        self._counters: Counter[str] = Counter()
        self._gauges: dict[str, float] = {}
        self._sequence = 0
        self._screen_provider: Callable[[], str] | None = None
        self._page_hooks: set[int] = set()
        self._active_tasks: Counter[str] = Counter()
        self._active_task_meta: dict[str, tuple[str, str]] = {}

    def now(self) -> float:
        return time.perf_counter()

    def set_screen_provider(self, provider: Callable[[], str] | None) -> None:
        self._screen_provider = provider

    def current_screen(self) -> str | None:
        if self._screen_provider is None:
            return None
        try:
            value = self._screen_provider()
            return str(value) if value is not None else None
        except Exception:
            return None

    def _safe_metadata(self, metadata: dict[str, Any] | None) -> dict[str, Any] | None:
        if not isinstance(metadata, dict):
            return None
        result: dict[str, Any] = {}
        for key, value in metadata.items():
            normalized = str(key)
            if normalized in {"uri", "path", "media_uri", "file_path"}:
                digest = hashlib.sha256(str(value or "").encode("utf-8")).hexdigest()[:12]
                result[normalized] = f"<media:{digest}>" if value else None
            elif normalized in {"request_id", "event_id", "task_id", "episode_id", "anime_id", "media_identity"}:
                text = str(value or "").strip()
                result[normalized] = text[:80] if text else None
            elif isinstance(value, (str, int, float, bool)) or value is None:
                result[normalized] = value
            elif isinstance(value, (list, tuple)):
                result[normalized] = [str(item)[:80] for item in value[:20]]
            else:
                result[normalized] = str(value)[:160]
        return result or None

    def counter(self, name: str, value: int = 1) -> None:
        if self.enabled:
            self._counters[str(name)] += int(value)

    def gauge(self, name: str, value: float) -> None:
        if self.enabled:
            try:
                self._gauges[str(name)] = float(value)
            except (TypeError, ValueError):
                pass

    def event(self, name: str, *, duration_ms: float | None = None,
              status: str | None = None, screen: str | None = None,
              task_id: str | None = None, metadata: dict[str, Any] | None = None) -> None:
        if not self.enabled:
            return
        item = PerformanceEvent(
            name=str(name),
            timestamp_ms=int(time.time() * 1000),
            monotonic_ns=time.monotonic_ns(),
            duration_ms=None if duration_ms is None else round(float(duration_ms), 3),
            interaction_id=_INTERACTION.get(),
            screen=screen if screen is not None else self.current_screen(),
            thread=threading.current_thread().name,
            task_id=task_id,
            status=status,
            metadata=self._safe_metadata(metadata),
        )
        self._events.append(item)
        logger.info("[PERF] %s", json.dumps(asdict(item), ensure_ascii=False, sort_keys=True))

    @contextmanager
    def span(self, name: str, *, screen: str | None = None,
             metadata: dict[str, Any] | None = None):
        if not self.enabled:
            yield None
            return
        started = self.now()
        try:
            yield started
        except asyncio.CancelledError:
            self.event(name, duration_ms=(self.now() - started) * 1000.0,
                       status="cancelled", screen=screen, metadata=metadata)
            raise
        except Exception as exc:
            self.event(name, duration_ms=(self.now() - started) * 1000.0,
                       status="error", screen=screen,
                       metadata={**(metadata or {}), "error": str(exc)[:120]})
            raise
        else:
            self.event(name, duration_ms=(self.now() - started) * 1000.0,
                       status="ok", screen=screen, metadata=metadata)

    @contextmanager
    def interaction(self, name: str, *, source: str | None = None,
                    target: str | None = None, metadata: dict[str, Any] | None = None):
        if not self.enabled:
            yield None
            return
        self._sequence += 1
        interaction_id = f"i{self._sequence:06d}"
        token = _INTERACTION.set(interaction_id)
        started = self.now()
        payload = dict(metadata or {})
        if source is not None:
            payload["source"] = source
        if target is not None:
            payload["target"] = target
        self.counter("interactions.started")
        self.event(f"interaction.{name}.start", screen=source, metadata=payload)
        try:
            yield interaction_id
        except asyncio.CancelledError:
            self.counter("interactions.cancelled")
            self.event(f"interaction.{name}.end",
                       duration_ms=(self.now() - started) * 1000.0,
                       status="cancelled", screen=target, metadata=payload)
            raise
        except Exception as exc:
            self.counter("interactions.failed")
            self.event(f"interaction.{name}.end",
                       duration_ms=(self.now() - started) * 1000.0,
                       status="error", screen=target,
                       metadata={**payload, "error": str(exc)[:120]})
            raise
        else:
            duration_ms = (self.now() - started) * 1000.0
            status = "excellent" if duration_ms < 100 else "attention" if duration_ms < 500 else "critical"
            self.event(f"interaction.{name}.end", duration_ms=duration_ms,
                       status=status, screen=target, metadata=payload)
            self.counter("interactions.completed")
        finally:
            task_meta = self._active_task_meta.pop(interaction_id, None)
            if task_meta:
                owner, operation = task_meta
                key = f"{owner}:{operation}"
                self._active_tasks[key] = max(0, self._active_tasks.get(key, 1) - 1)
                self._active_tasks["__all__"] = max(0, self._active_tasks.get("__all__", 1) - 1)
                self.gauge("tasks.active.total", self._active_tasks["__all__"])
                self.gauge(f"tasks.active.screen.{owner}",
                           sum(v for k, v in self._active_tasks.items() if k.startswith(f"{owner}:")))
            _INTERACTION.reset(token)

    @asynccontextmanager
    async def task_scope(self, name: str, *, screen: str | None = None,
                         generation: Any = None):
        if not self.enabled:
            yield None
            return
        task_id = f"t-{uuid.uuid4().hex[:10]}"
        started = self.now()
        metadata = {"generation": generation} if generation is not None else {}
        operation = str(name or "anonymous")
        owner = str(screen or "global")
        active_key = f"{owner}:{operation}"
        self._active_tasks[active_key] += 1
        self._active_tasks["__all__"] += 1
        self._active_task_meta[task_id] = (owner, operation)
        self.counter("tasks.created")
        self.event("TASK_CREATED", task_id=task_id, screen=screen,
                   metadata={"name": name, "operation": operation, "owner": owner,
                             "generation": generation, "active_tasks": self._active_tasks["__all__"], **metadata})
        self.event("task.created", task_id=task_id, screen=screen,
                   metadata={"name": name, **metadata})
        try:
            yield task_id
        except asyncio.CancelledError:
            self.counter("tasks.cancelled")
            self.event("TASK_CANCELLED", task_id=task_id, screen=screen,
                       duration_ms=(self.now() - started) * 1000.0,
                       status="cancelled", metadata={"name": name, "operation": operation,
                                                     "owner": owner, "generation": generation,
                                                     "active_tasks": max(0, self._active_tasks["__all__"] - 1), **metadata})
            self.event("task.finished", task_id=task_id, screen=screen,
                       duration_ms=(self.now() - started) * 1000.0,
                       status="cancelled", metadata={"name": name, **metadata})
            raise
        except Exception as exc:
            self.counter("tasks.failed")
            self.event("TASK_FAILED", task_id=task_id, screen=screen,
                       duration_ms=(self.now() - started) * 1000.0,
                       status="error",
                       metadata={"name": name, "operation": operation, "owner": owner,
                                 "generation": generation,
                                 "active_tasks": max(0, self._active_tasks["__all__"] - 1),
                                 "error": str(exc)[:120], **metadata})
            self.event("task.finished", task_id=task_id, screen=screen,
                       duration_ms=(self.now() - started) * 1000.0,
                       status="error",
                       metadata={"name": name, "error": str(exc)[:120], **metadata})
            raise
        else:
            self.counter("tasks.completed")
            self.event("TASK_FINISHED", task_id=task_id, screen=screen,
                       duration_ms=(self.now() - started) * 1000.0,
                       status="ok", metadata={"name": name, "operation": operation,
                                              "owner": owner, "generation": generation,
                                              "active_tasks": max(0, self._active_tasks["__all__"] - 1), **metadata})
            self.event("task.finished", task_id=task_id, screen=screen,
                       duration_ms=(self.now() - started) * 1000.0,
                       status="ok", metadata={"name": name, **metadata})

    def record_sqlite(self, query_name: str, duration_ms: float, *,
                      rows: int | None = None, status: str = "ok",
                      screen: str | None = None,
                      metadata: dict[str, Any] | None = None) -> None:
        if not self.enabled:
            return
        self.counter(f"sqlite.queries.{query_name}")
        payload = dict(metadata or {})
        payload["rows"] = rows
        self.event(f"sqlite.{query_name}", duration_ms=duration_ms,
                   status=status, screen=screen, metadata=payload)

    def record_ui_build(self, screen: str, duration_ms: float, *,
                        controls: int | None = None,
                        sections: int | None = None,
                        cached: bool = False) -> None:
        if not self.enabled:
            return
        self.counter(f"ui.builds.{screen}")
        self.event("ui.build", duration_ms=duration_ms, screen=screen,
                   metadata={"controls": controls, "sections": sections, "cached": cached})

    def record_native_event(self, event: dict[str, Any]) -> None:
        if not self.enabled or not isinstance(event, dict):
            return
        event_type = str(event.get("type") or "")
        payload = event.get("payload") if isinstance(event.get("payload"), dict) else {}
        created = payload.get("createdAt") or event.get("createdAt") or payload.get("commandCreatedAtMs")
        received = event.get("receivedAtMs")
        latency = None
        try:
            if created is not None and received is not None:
                latency = max(0.0, float(received) - float(created))
        except (TypeError, ValueError):
            pass
        self.counter(f"android.events.received.{event_type}")
        self.event(f"android.{event_type}", duration_ms=latency,
                   metadata={"event_id": event.get("eventId"),
                             "request_id": event.get("requestId") or payload.get("requestId"),
                             "stage": payload.get("stage") or payload.get("event")})

    def install_page_hooks(self, page) -> None:
        if not self.enabled or id(page) in self._page_hooks:
            return
        try:
            original_update = page.update
            def measured_update(*args, **kwargs):
                started = self.now()
                try:
                    return original_update(*args, **kwargs)
                finally:
                    self.counter("page.update")
                    duration_ms = (self.now() - started) * 1000.0
                screen = self.current_screen()
                metadata = {"page_views": len(getattr(page, "views", []) or [])}
                if screen == "home":
                    try:
                        metadata["approx_controls"] = sum(
                            self.control_count(view, limit=4000) or 0
                            for view in (getattr(page, "views", []) or [])
                        )
                    except Exception:
                        metadata["approx_controls"] = None
                    self.event("HOME_PAGE_UPDATE", duration_ms=duration_ms, screen=screen, metadata=metadata)
                self.event("page.update", duration_ms=duration_ms, screen=screen, metadata=metadata)
            page.update = measured_update
        except Exception:
            logger.debug("performance page.update hook unavailable", exc_info=True)
        try:
            original_run_task = page.run_task
            def measured_run_task(handler, *args, **kwargs):
                screen = self.current_screen()
                name = getattr(handler, "__name__", None) or "anonymous"
                if inspect.isawaitable(handler) and not callable(handler):
                    coroutine = handler
                    async def tracked_coroutine():
                        async with self.task_scope(f"page.run_task:{name}", screen=screen):
                            return await coroutine
                    return original_run_task(tracked_coroutine)

                async def tracked_callable():
                    async with self.task_scope(f"page.run_task:{name}", screen=screen):
                        result = handler(*args, **kwargs)
                        if inspect.isawaitable(result):
                            return await result
                        return result

                return original_run_task(tracked_callable)
            page.run_task = measured_run_task
        except Exception:
            logger.debug("performance page.run_task hook unavailable", exc_info=True)
        self._page_hooks.add(id(page))

    def control_count(self, control, *, limit: int = 12000) -> int | None:
        if not self.enabled or control is None:
            return None
        seen: set[int] = set()
        count = 0
        stack = [control]
        while stack and count < limit:
            item = stack.pop()
            if id(item) in seen:
                continue
            seen.add(id(item))
            count += 1
            children = getattr(item, "controls", None)
            if isinstance(children, (list, tuple)):
                stack.extend(children[:limit - count])
            content = getattr(item, "content", None)
            if content is not None and id(content) not in seen:
                stack.append(content)
        return count

    def snapshot(self) -> dict[str, Any]:
        if not self.enabled:
            return {"enabled": False, "counters": {}, "gauges": {}, "events": []}
        return {"enabled": True, "counters": dict(self._counters),
                "gauges": dict(self._gauges),
                "events": [asdict(item) for item in self._events]}

    def clear(self) -> None:
        self._events.clear()
        self._counters.clear()
        self._gauges.clear()


_default_monitor: PerformanceMonitor | None = None


def get_performance_monitor() -> PerformanceMonitor:
    global _default_monitor
    if _default_monitor is None:
        _default_monitor = PerformanceMonitor()
    return _default_monitor
