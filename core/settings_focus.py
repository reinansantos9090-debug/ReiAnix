"""State machine for Settings focus events.

This module deliberately contains no Flet dependencies so focus behavior can be
verified independently from the UI runtime.
"""
from __future__ import annotations

from dataclasses import dataclass
import logging


logger = logging.getLogger("reiflix.settings_focus")


@dataclass(frozen=True)
class FocusDecision:
    should_scroll: bool
    reason: str
    generation: int


class SettingsFocusState:
    """Classify Settings focus events and suppress non-user-driven duplicates."""

    def __init__(self) -> None:
        self._initial_focus_pending = True
        self._last_key: str | None = None
        self._generation = 0

    @property
    def generation(self) -> int:
        return self._generation

    def on_focus(self, key: str) -> FocusDecision:
        self._generation += 1
        normalized = str(key or "").strip()
        if not normalized:
            return FocusDecision(False, "invalid", self._generation)

        if self._initial_focus_pending:
            self._initial_focus_pending = False
            self._last_key = normalized
            return FocusDecision(False, "initial", self._generation)

        if normalized == self._last_key:
            return FocusDecision(False, "duplicate", self._generation)

        self._last_key = normalized
        return FocusDecision(True, "navigation", self._generation)

    def reset_for_rebuild(self) -> None:
        """Reset only the per-view focus state after a new control tree is built."""
        self._initial_focus_pending = True
        self._last_key = None



class SettingsTaskRegistry:
    """Invalidate Settings background tasks when a view generation is replaced."""

    def __init__(self) -> None:
        self._generation = 0
        self._tasks: set[object] = set()

    @property
    def generation(self) -> int:
        return self._generation

    def register(self, task: object) -> object:
        if task is None:
            return task
        self._tasks.add(task)
        add_done_callback = getattr(task, "add_done_callback", None)
        if callable(add_done_callback):
            try:
                add_done_callback(self._discard)
            except Exception:
                logger.debug("unable to register Settings task completion callback", exc_info=True)
        return task

    def invalidate(self) -> int:
        self._generation += 1
        tasks = tuple(self._tasks)
        self._tasks.clear()
        for task in tasks:
            cancel = getattr(task, "cancel", None)
            if callable(cancel):
                try:
                    cancel()
                except Exception:
                    logger.debug("unable to cancel Settings task", exc_info=True)
        return self._generation

    def _discard(self, task: object) -> None:
        self._tasks.discard(task)
