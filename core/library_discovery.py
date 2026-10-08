"""Pure local-library discovery algorithms.

This module intentionally contains no persistence, network, artwork downloads, or
playback state. LibraryStore remains the source of truth; LibraryService wires
these calculations to the existing SQLite projections.
"""
from __future__ import annotations

import math
from collections import defaultdict
from statistics import median
from typing import Iterable, Mapping


SPECIAL_EPISODE_TYPES = {"special", "ova", "oad", "ona", "extra", "movie"}


def format_duration(seconds, *, include_seconds=False) -> str:
    try:
        value = float(seconds)
    except (TypeError, ValueError):
        return "Duração desconhecida"
    if not math.isfinite(value) or value < 0:
        return "Duração desconhecida"
    total = int(round(value))
    hours, remainder = divmod(total, 3600)
    minutes, secs = divmod(remainder, 60)
    if hours:
        return f"{hours}h {minutes:02d}min" + (f" {secs:02d}s" if include_seconds else "")
    if minutes:
        return f"{minutes}min" + (f" {secs:02d}s" if include_seconds else "")
    return f"{secs}s"


def _finite_duration(value):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) and number > 0 else None


def _quartiles(values: list[float]) -> tuple[float, float]:
    ordered = sorted(values)
    n = len(ordered)
    if n == 1:
        return ordered[0], ordered[0]
    midpoint = n // 2
    if n % 2:
        lower = ordered[:midpoint]
        upper = ordered[midpoint + 1 :]
    else:
        lower = ordered[:midpoint]
        upper = ordered[midpoint:]
    return median(lower), median(upper)


def _episode_key(item: Mapping):
    def number(name, default=10**9):
        try:
            value = float(item.get(name))
        except (TypeError, ValueError):
            return default
        return value if math.isfinite(value) else default

    return (
        number("season"),
        number("number"),
        number("absolute_number"),
        str(item.get("file_name") or "").casefold(),
        int(item.get("id") or 0),
    )


def timeline_groups(rows: Iterable[Mapping]) -> list[dict]:
    groups: dict[int | None, list[dict]] = defaultdict(list)
    for row in rows or []:
        try:
            year = int(row.get("year"))
            if year < 1800 or year > 3000:
                year = None
        except (TypeError, ValueError):
            year = None
        groups[year].append(dict(row))

    ordered_keys = sorted((key for key in groups if key is not None), reverse=True)
    if None in groups:
        ordered_keys.append(None)

    result = []
    for year in ordered_keys:
        items = sorted(
            groups[year],
            key=lambda item: (
                str(item.get("title") or "").casefold(),
                int(item.get("id") or 0),
            ),
        )
        result.append({
            "year": year,
            "label": str(year) if year is not None else "Ano desconhecido",
            "items": items,
            "count": len(items),
        })
    return result


def duration_anomalies(rows: Iterable[Mapping]) -> dict:
    grouped: dict[int, list[dict]] = defaultdict(list)
    names: dict[int, str] = {}
    for row in rows or []:
        try:
            anime_id = int(row.get("anime_id"))
        except (TypeError, ValueError):
            continue
        names[anime_id] = str(row.get("anime_title") or "Anime local")
        duration = _finite_duration(row.get("duration"))
        if duration is None:
            continue
        grouped[anime_id].append({
            **dict(row),
            "duration_seconds": duration,
        })

    results = []
    for anime_id, episodes in grouped.items():
        usable = len(episodes)
        if usable < 4:
            results.append({
                "anime_id": anime_id,
                "anime_title": names.get(anime_id, "Anime local"),
                "status": "insufficient",
                "episodes_used": usable,
                "anomalies": [],
                "median_seconds": median([item["duration_seconds"] for item in episodes]),
                "iqr_seconds": None,
            })
            continue

        durations = [item["duration_seconds"] for item in episodes]
        q1, q3 = _quartiles(durations)
        med = median(durations)
        iqr = max(0.0, q3 - q1)
        lower = q1 - (1.5 * iqr)
        upper = q3 + (1.5 * iqr)
        minimum_relative_delta = 0.30
        anomalies = []
        for item in episodes:
            value = item["duration_seconds"]
            relative_delta = abs(value - med) / med if med > 0 else 0.0
            outside_fence = value < lower or value > upper
            fallback_outlier = iqr == 0 and relative_delta >= 0.50
            robust_relative_outlier = relative_delta >= 0.50
            if (outside_fence or fallback_outlier or robust_relative_outlier) and relative_delta >= minimum_relative_delta:
                anomalies.append({
                    **item,
                    "relative_delta": relative_delta,
                    "direction": "long" if value > med else "short",
                    "label": (
                        "Duração muito acima da média"
                        if value > med
                        else "Duração muito abaixo da média"
                    ),
                })

        results.append({
            "anime_id": anime_id,
            "anime_title": names.get(anime_id, "Anime local"),
            "status": "ok" if not anomalies else "unusual",
            "episodes_used": usable,
            "anomalies": sorted(anomalies, key=_episode_key),
            "median_seconds": med,
            "iqr_seconds": iqr,
            "lower_fence_seconds": lower,
            "upper_fence_seconds": upper,
        })

    return {
        "items": sorted(
            results,
            key=lambda item: (
                0 if item["status"] == "unusual" else 1,
                -len(item["anomalies"]),
                str(item["anime_title"]).casefold(),
            ),
        ),
        "anomaly_count": sum(len(item["anomalies"]) for item in results),
        "series_with_anomalies": sum(item["status"] == "unusual" for item in results),
        "series_with_insufficient_data": sum(item["status"] == "insufficient" for item in results),
    }


def marathon_plan(rows: Iterable[Mapping], *, current_path=None) -> dict:
    episodes = []
    for row in rows or []:
        item = dict(row)
        if int(item.get("missing") or 0):
            continue
        episode_type = str(item.get("episode_type") or "regular").casefold()
        if episode_type in SPECIAL_EPISODE_TYPES:
            continue
        duration = _finite_duration(item.get("duration"))
        try:
            progress = max(0.0, float(item.get("progress") or 0))
        except (TypeError, ValueError):
            progress = 0.0
        if duration is not None:
            progress = min(progress, duration)
        item["_duration_seconds"] = duration
        item["_progress_seconds"] = progress
        episodes.append(item)

    ordered = sorted(episodes, key=_episode_key)
    if not ordered:
        return {
            "items": [],
            "episode_count": 0,
            "known_duration_seconds": 0.0,
            "unknown_duration_count": 0,
            "partial": False,
            "current_path": current_path,
        }

    start_index = None
    if current_path:
        for index, item in enumerate(ordered):
            if str(item.get("path")) == str(current_path):
                start_index = index
                break

    if start_index is None:
        for index, item in enumerate(ordered):
            completed = (
                int(item.get("watched") or 0) == 1
                or (
                    item["_duration_seconds"] is not None
                    and item["_duration_seconds"] > 0
                    and item["_progress_seconds"] / item["_duration_seconds"] >= 0.90
                )
            )
            if not completed:
                start_index = index
                break

    if start_index is None:
        return {
            "items": [],
            "episode_count": 0,
            "known_duration_seconds": 0.0,
            "unknown_duration_count": 0,
            "partial": False,
            "current_path": current_path,
        }

    plan = []
    known = 0.0
    unknown = 0
    for item in ordered[start_index:]:
        if int(item.get("watched") or 0) == 1:
            continue
        duration = item["_duration_seconds"]
        remaining = None if duration is None else max(0.0, duration - item["_progress_seconds"])
        if remaining is None:
            unknown += 1
        else:
            known += remaining
        plan.append({
            **item,
            "remaining_seconds": remaining,
            "is_current": str(item.get("path")) == str(current_path) if current_path else False,
        })

    return {
        "items": plan,
        "episode_count": len(plan),
        "known_duration_seconds": known,
        "unknown_duration_count": unknown,
        "partial": unknown > 0,
        "current_path": current_path,
    }
