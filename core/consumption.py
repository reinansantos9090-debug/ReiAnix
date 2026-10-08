"""Central local consumption-state policy for the media library.

The current schema persists progress and the legacy watched flag. Rich playback
state is derived from those facts so the library does not grow a second state
machine without a persistence requirement.
"""
from __future__ import annotations
from enum import Enum
from typing import Any
import math

COMPLETION_RATIO = 0.90

class ConsumptionState(str, Enum):
    UNWATCHED = "unwatched"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    WATCHED = "watched"

def _number(value: Any, default: float = 0.0) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return default
    return number if math.isfinite(number) else default

def normalized_progress(episode: dict) -> float:
    progress = max(0.0, _number(episode.get("progress"), 0.0))
    duration = max(0.0, _number(episode.get("duration"), 0.0))
    return min(progress, duration) if duration > 0 else progress

def progress_ratio(episode: dict) -> float:
    duration = max(0.0, _number(episode.get("duration"), 0.0))
    if duration <= 0:
        return 0.0
    return max(0.0, min(normalized_progress(episode) / duration, 1.0))

def is_completed(episode: dict) -> bool:
    if bool(episode.get("watched")):
        return True
    duration = max(0.0, _number(episode.get("duration"), 0.0))
    return duration > 0 and progress_ratio(episode) >= COMPLETION_RATIO

def is_in_progress(episode: dict) -> bool:
    return not bool(episode.get("missing")) and normalized_progress(episode) > 0 and not is_completed(episode)

def consumption_state(episode: dict) -> ConsumptionState:
    if is_completed(episode):
        return ConsumptionState.WATCHED if bool(episode.get("watched")) else ConsumptionState.COMPLETED
    if is_in_progress(episode):
        return ConsumptionState.IN_PROGRESS
    return ConsumptionState.UNWATCHED

def playback_action(episode: dict) -> str:
    """Return the single user-facing playback action for one local item."""
    if not episode or bool(episode.get("missing")):
        return "unavailable"
    if is_completed(episode):
        return "replay"
    if is_in_progress(episode):
        return "continue"
    return "watch"


def is_regular_episode(episode: dict) -> bool:
    return str(episode.get("episode_type") or "regular").casefold() not in {
        "movie", "special", "ova", "oad", "ona", "extra"
    }
