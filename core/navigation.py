"""Small, platform-independent navigation and Android back policy."""
from __future__ import annotations

import time


class NavigationController:
    """Single logical navigation policy for top-level screens and nested Settings."""

    ROOT = "home"
    TOP_LEVEL_SCREENS = frozenset({"home", "library", "my_list", "organize", "details", "collector", "search", "settings"})

    def __init__(self, clock=time.monotonic, exit_window_seconds: float = 2.0):
        self._stack = [self.ROOT]
        self._settings_path: list[str] = []
        self._clock = clock
        self._exit_window_seconds = exit_window_seconds
        self._exit_requested_at: float | None = None

    @property
    def current(self) -> str:
        return self._stack[-1]

    @property
    def stack(self) -> tuple[str, ...]:
        return tuple(self._stack)

    @property
    def settings_path(self) -> tuple[str, ...]:
        """Nested Settings path owned by this controller, not a second app stack."""
        return tuple(self._settings_path)

    @property
    def settings_level(self) -> str | None:
        return self._settings_path[-1] if self._settings_path else None

    def push(self, screen: str) -> None:
        if screen not in self.TOP_LEVEL_SCREENS:
            raise ValueError(f"invalid top-level navigation screen: {screen!r}")
        if screen == self.current:
            return
        self._stack.append(screen)
        self._settings_path.clear()
        self._exit_requested_at = None

    def replace(self, screen: str) -> None:
        if screen not in self.TOP_LEVEL_SCREENS:
            raise ValueError(f"invalid top-level navigation screen: {screen!r}")
        self._stack[-1] = screen
        self._settings_path.clear()
        self._exit_requested_at = None

    def sync_top_level(self, screen: str) -> None:
        """Project a native Compose top-level route into the existing logical stack.

        Compose owns the visible navigation on Android, while this controller remains
        the legacy/domain lifecycle authority. The projection is deliberately reduced
        to [Home, screen] so the two layers cannot accumulate divergent stacks.
        """
        if screen not in self.TOP_LEVEL_SCREENS:
            raise ValueError(f"invalid top-level navigation screen: {screen!r}")
        if screen == self.ROOT:
            self.reset_to_root()
            return
        self._stack = [self.ROOT, screen]
        self._settings_path.clear()
        self._exit_requested_at = None

    def reset_to_root(self) -> None:
        self._stack = [self.ROOT]
        self._settings_path.clear()
        self._exit_requested_at = None

    def snapshot(self) -> dict:
        """Return only reconstructible navigation state."""
        return {
            "stack": list(self._stack),
            "settings_path": list(self._settings_path),
        }

    def restore(self, state: object) -> bool:
        """Restore a validated snapshot; invalid state leaves navigation unchanged."""
        if not isinstance(state, dict):
            return False
        raw_stack = state.get("stack")
        raw_settings = state.get("settings_path", [])
        if not isinstance(raw_stack, list) or not raw_stack:
            return False
        stack = [str(item).strip() for item in raw_stack]
        if any(item not in self.TOP_LEVEL_SCREENS for item in stack):
            return False
        if stack[0] != self.ROOT:
            return False
        if not isinstance(raw_settings, list) or any(
            not isinstance(item, str) or not item.strip() for item in raw_settings
        ):
            return False
        if raw_settings and stack[-1] != "settings":
            return False
        self._stack = stack
        self._settings_path = [item.strip() for item in raw_settings]
        self._exit_requested_at = None
        return True

    def push_settings(self, level: str) -> None:
        """Push one Settings level while keeping it under the same Back authority."""
        if self.current != "settings":
            raise RuntimeError("Settings levels require the settings route")
        normalized = str(level or "").strip()
        if not normalized:
            raise ValueError("Settings level must not be empty")
        if self._settings_path and self._settings_path[-1] == normalized:
            return
        self._settings_path.append(normalized)
        self._exit_requested_at = None

    def back(self) -> str:
        """Return settings_inner, previous, exit_requested or exit."""
        if self.current == "settings" and self._settings_path:
            self._settings_path.pop()
            self._exit_requested_at = None
            return "settings_inner"
        if len(self._stack) > 1:
            self._stack.pop()
            self._settings_path.clear()
            self._exit_requested_at = None
            return "previous"
        now = self._clock()
        if self._exit_requested_at is not None and now - self._exit_requested_at <= self._exit_window_seconds:
            self._exit_requested_at = None
            return "exit"
        self._exit_requested_at = now
        return "exit_requested"


class SafSelectionState:
    """Tracks only the period in which Android's tree picker has no result yet."""

    def __init__(self):
        self.pending = False

    def begin(self) -> bool:
        if self.pending:
            return False
        self.pending = True
        return True

    def finish(self) -> None:
        self.pending = False
