"""Offline Collector Journey derived from ReiAnix canonical library/consumption state.

No persistence for derived XP, achievements or statistics is required.  The
snapshot is rebuilt from LibraryStore rows every time the Collector Journey is
opened/refreshed.  Only the optional active title is persisted via the existing
preferences table.
"""
from __future__ import annotations

from collections import Counter, defaultdict
from datetime import datetime
import json
import math


LEVEL_EPISODES = 10
GENRE_DISCOVERY_TARGET = 5
DECADE_DISCOVERY_TARGET = 3
MARATHON_TITLE_TARGET = 10
ACTIVE_DAY_TARGET = 7
ACTIVE_DAY_VETERAN_TARGET = 30


def _finite_number(value, default=0.0):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return default
    return number if math.isfinite(number) else default


def _completed(row):
    if bool(row.get("watched")):
        return True
    duration = _finite_number(row.get("duration"))
    progress = max(0.0, _finite_number(row.get("progress")))
    return duration > 0 and min(progress / duration, 1.0) >= 0.90


def _valid_completed_activity(row):
    progress = max(0.0, _finite_number(row.get("progress")))
    duration = max(0.0, _finite_number(row.get("duration")))
    ratio = min(progress / duration, 1.0) if duration > 0 else 0.0
    return bool(row.get("watched")) or ratio >= 0.05


def _decode_genres(value):
    if isinstance(value, list):
        raw = value
    elif isinstance(value, str):
        try:
            raw = json.loads(value)
        except (TypeError, json.JSONDecodeError):
            raw = [value] if value.strip() else []
    else:
        raw = []
    result = []
    for item in raw:
        text = " ".join(str(item).split()).strip()
        if text and text.casefold() not in {x.casefold() for x in result}:
            result.append(text)
    return result


def _year(row):
    try:
        value = int(row.get("year"))
    except (TypeError, ValueError):
        return None
    return value if 1800 <= value <= 3000 else None


def _achievement(key, title, description, category, target, value, icon):
    target = max(1, int(target))
    value = max(0, int(value))
    return {
        "id": key,
        "title": title,
        "description": description,
        "category": category,
        "target": target,
        "progress": min(value, target),
        "value": value,
        "unlocked": value >= target,
        "icon": icon,
    }


def build_collector_journey(rows, *, active_title=None):
    rows = [dict(row) for row in (rows or [])]
    available_rows = [row for row in rows if not int(row.get("missing") or 0)]
    completed_rows = [row for row in available_rows if _completed(row)]

    by_anime = defaultdict(list)
    completed_titles = {}
    for row in available_rows:
        anime_id = row.get("anime_id")
        by_anime[anime_id].append(row)
    for anime_id, items in by_anime.items():
        completed = [item for item in items if _completed(item)]
        if items and completed and len(completed) == len(items):
            completed_titles[anime_id] = items[0]

    completed_episode_count = len(completed_rows)
    library_title_count = len({row.get("anime_id") for row in rows if row.get("anime_id") is not None})
    anime_completed_count = len(completed_titles)

    completed_genres = Counter()
    completed_decades = set()
    completed_formats = set()
    genre_title_counts = Counter()
    active_days = set()
    completed_duration_seconds = 0.0
    max_completed_in_one_anime = 0

    for anime_id, items in by_anime.items():
        completed_here = [item for item in items if _completed(item)]
        max_completed_in_one_anime = max(max_completed_in_one_anime, len(completed_here))
        if not completed_here:
            continue

        source = completed_here[0]
        genres = _decode_genres(source.get("genres"))
        for genre in genres:
            completed_genres[genre] += 1
            genre_title_counts[(genre, anime_id)] += 1

        year = _year(source)
        if year is not None:
            completed_decades.add((year // 10) * 10)

        fmt = str(source.get("format") or source.get("media_kind") or "").strip()
        if fmt and fmt.casefold() not in {"unknown", "none", "null"}:
            completed_formats.add(fmt.casefold())

    for row in completed_rows:
        completed_duration_seconds += max(0.0, _finite_number(row.get("duration")))
        if _valid_completed_activity(row) and row.get("last_played_at") is not None:
            try:
                timestamp = float(row["last_played_at"])
                if math.isfinite(timestamp) and timestamp > 0:
                    active_days.add(datetime.fromtimestamp(timestamp).date().isoformat())
            except (TypeError, ValueError, OverflowError, OSError):
                pass

    # Active day can also come from meaningful in-progress consumption that was
    # not yet completed. Opening Home/Settings alone never creates an active day.
    for row in available_rows:
        if not _valid_completed_activity(row) or row.get("last_played_at") is None:
            continue
        try:
            timestamp = float(row["last_played_at"])
            if math.isfinite(timestamp) and timestamp > 0:
                active_days.add(datetime.fromtimestamp(timestamp).date().isoformat())
        except (TypeError, ValueError, OverflowError, OSError):
            continue

    genres_explored = len(completed_genres)
    decades_explored = len(completed_decades)
    formats_explored = len(completed_formats)
    active_day_count = len(active_days)

    # XP is deliberately transparent: one XP == one completed local episode.
    # Level is a simple deterministic projection, not a separate persisted state.
    xp = completed_episode_count
    level = 1 + (xp // LEVEL_EPISODES)
    xp_into_level = xp % LEVEL_EPISODES
    xp_to_next = LEVEL_EPISODES - xp_into_level if xp_into_level else LEVEL_EPISODES
    level_progress = xp_into_level / LEVEL_EPISODES

    achievements = [
        _achievement("first_episode", "Primeiro Passo", "Conclua seu primeiro episódio local.", "Consumo", 1, completed_episode_count, "CHECK_CIRCLE"),
        _achievement("episodes_10", "10 Episódios", "Conclua 10 episódios locais.", "Consumo", 10, completed_episode_count, "FORMAT_LIST_NUMBERED"),
        _achievement("episodes_50", "50 Episódios", "Conclua 50 episódios locais.", "Consumo", 50, completed_episode_count, "FORMAT_LIST_NUMBERED"),
        _achievement("episodes_100", "100 Episódios", "Conclua 100 episódios locais.", "Consumo", 100, completed_episode_count, "FORMAT_LIST_NUMBERED"),
        _achievement("episodes_500", "500 Episódios", "Conclua 500 episódios locais.", "Consumo", 500, completed_episode_count, "FORMAT_LIST_NUMBERED"),
        _achievement("anime_1", "Primeiro Anime", "Conclua o primeiro título disponível na biblioteca.", "Consumo", 1, anime_completed_count, "MOVIE_OUTLINED"),
        _achievement("anime_10", "10 Animes", "Conclua 10 títulos da biblioteca.", "Consumo", 10, anime_completed_count, "MOVIE_OUTLINED"),
        _achievement("library_10", "Colecionador", "Mantenha 10 títulos na biblioteca local.", "Coleção", 10, library_title_count, "STAR"),
        _achievement("library_50", "Grande Acervo", "Mantenha 50 títulos na biblioteca local.", "Coleção", 50, library_title_count, "STAR"),
        _achievement("genres_5", "Explorador de Gêneros", "Conclua títulos de 5 gêneros diferentes.", "Exploração", GENRE_DISCOVERY_TARGET, genres_explored, "LOCAL_OFFER_OUTLINED"),
        _achievement("decades_3", "Explorador Temporal", "Conclua títulos de 3 décadas diferentes.", "Exploração", DECADE_DISCOVERY_TARGET, decades_explored, "CALENDAR_TODAY"),
        _achievement("formats_2", "Formatos Diversos", "Conclua títulos de 2 formatos diferentes.", "Exploração", 2, formats_explored, "MOVIE_OUTLINED"),
        _achievement("active_7", "Ritmo de Colecionador", "Tenha atividade de consumo significativa em 7 dias diferentes.", "Atividade", ACTIVE_DAY_TARGET, active_day_count, "HISTORY"),
        _achievement("active_30", "Jornada Constante", "Tenha atividade de consumo significativa em 30 dias diferentes.", "Atividade", ACTIVE_DAY_VETERAN_TARGET, active_day_count, "HISTORY"),
        _achievement("marathon_10", "Maratonista Local", "Conclua 10 episódios de um mesmo título.", "Consumo", MARATHON_TITLE_TARGET, max_completed_in_one_anime, "SCHEDULE_OUTLINED"),
        _achievement("content_10h", "10 Horas de Conteúdo", "Conclua pelo menos 10 horas de conteúdo local.", "Consumo", 10 * 3600, int(completed_duration_seconds), "SCHEDULE_OUTLINED"),
        _achievement("content_50h", "50 Horas de Conteúdo", "Conclua pelo menos 50 horas de conteúdo local.", "Consumo", 50 * 3600, int(completed_duration_seconds), "SCHEDULE_OUTLINED"),
    ]

    decade_names = {
        1980: "Explorador dos anos 80",
        1990: "Explorador dos anos 90",
        2000: "Explorador dos anos 2000",
        2010: "Explorador dos anos 2010",
        2020: "Explorador dos anos 2020",
    }
    for decade, label in decade_names.items():
        title_count = sum(
            1
            for item in completed_titles.values()
            if _year(item) is not None and (_year(item) // 10) * 10 == decade
        )
        achievements.append(
            _achievement(
                f"decade_{decade}",
                label,
                f"Conclua pelo menos um título lançado nos anos {str(decade)[-2:]}0.",
                "Exploração",
                1,
                title_count,
                "CALENDAR_TODAY",
            )
        )

    # Niche achievements are generated from real canonical genres. They appear
    # only when the user has completed at least 3 titles in that genre.
    genre_title_count = Counter()
    for anime_id, item in completed_titles.items():
        for genre in _decode_genres(item.get("genres")):
            genre_title_count[genre] += 1
    for genre, count in sorted(genre_title_count.items(), key=lambda pair: (pair[0].casefold(), pair[0])):
        if count >= 3:
            slug = "genre_" + "".join(ch if ch.isalnum() else "_" for ch in genre.casefold()).strip("_")
            achievements.append(
                _achievement(
                    slug,
                    f"Explorador de {genre}",
                    f"Conclua 3 títulos classificados com o gênero {genre}.",
                    "Exploração",
                    3,
                    count,
                    "LOCAL_OFFER_OUTLINED",
                )
            )

    achievements.sort(key=lambda item: (item["category"], item["id"]))
    unlocked_count = sum(1 for item in achievements if item["unlocked"])

    titles = [
        {
            "id": "collector_beginner",
            "title": "Colecionador Iniciante",
            "condition": "1 episódio concluído.",
            "unlocked": completed_episode_count >= 1,
        },
        {
            "id": "collector",
            "title": "Colecionador",
            "condition": "10 episódios concluídos.",
            "unlocked": completed_episode_count >= 10,
        },
        {
            "id": "temporal_explorer",
            "title": "Explorador Temporal",
            "condition": "3 décadas exploradas.",
            "unlocked": decades_explored >= 3,
        },
        {
            "id": "genre_explorer",
            "title": "Explorador de Gêneros",
            "condition": "5 gêneros explorados.",
            "unlocked": genres_explored >= 5,
        },
        {
            "id": "marathon_local",
            "title": "Maratonista",
            "condition": "10 episódios concluídos em um mesmo título.",
            "unlocked": max_completed_in_one_anime >= MARATHON_TITLE_TARGET,
        },
        {
            "id": "veteran",
            "title": "Veterano",
            "condition": "100 episódios concluídos.",
            "unlocked": completed_episode_count >= 100,
        },
        {
            "id": "master",
            "title": "Mestre",
            "condition": "300 episódios concluídos.",
            "unlocked": completed_episode_count >= 300,
        },
    ]
    for genre, count in sorted(genre_title_count.items(), key=lambda pair: (pair[0].casefold(), pair[0])):
        if count >= 3:
            title_id = "genre_" + "".join(ch if ch.isalnum() else "_" for ch in genre.casefold()).strip("_")
            titles.append(
                {
                    "id": title_id,
                    "title": f"Explorador de {genre}",
                    "condition": f"3 títulos concluídos com o gênero {genre}.",
                    "unlocked": True,
                }
            )

    valid_active_ids = {item["id"] for item in titles if item["unlocked"]}
    if active_title not in valid_active_ids:
        active_title = None
    if active_title is None:
        active_title = next((item["id"] for item in titles if item["unlocked"]), None)
    active_title_label = next((item["title"] for item in titles if item["id"] == active_title), None)

    return {
        "xp": xp,
        "level": level,
        "xp_into_level": xp_into_level,
        "xp_to_next": xp_to_next,
        "level_progress": level_progress,
        "episodes_completed": completed_episode_count,
        "animes_completed": anime_completed_count,
        "library_titles": library_title_count,
        "active_days": active_day_count,
        "genres_explored": genres_explored,
        "decades_explored": decades_explored,
        "formats_explored": formats_explored,
        "completed_duration_seconds": int(completed_duration_seconds),
        "max_completed_in_one_anime": max_completed_in_one_anime,
        "active_title": active_title,
        "active_title_label": active_title_label,
        "titles": titles,
        "achievements": achievements,
        "achievements_unlocked": unlocked_count,
        "achievements_total": len(achievements),
        "rebuildable": True,
        "schema_version": 1,
    }


def unlocked_title_ids(journey):
    return {item["id"] for item in journey.get("titles", []) if item.get("unlocked")}


def set_active_title(journey, title_id):
    return title_id if title_id in unlocked_title_ids(journey) else None
