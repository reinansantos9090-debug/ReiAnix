"""Local, reusable search/filter/sort engine for the persisted library catalog.

The engine deliberately consumes the existing catalog projection. It never
calls metadata providers, opens media files, or mutates library state.
"""
from __future__ import annotations

import math
import os
import re
import unicodedata
from dataclasses import dataclass, field
from typing import Any, Iterable

from core.consumption import consumption_state, progress_ratio


_SPECIAL_TYPES = {"special", "ova", "oad", "ona", "extra"}
_TOKEN_RE = re.compile(r"[\w]+", re.UNICODE)
_IDENTIFIER_RE = re.compile(
    r"(?P<season>s\s*\d{1,3})?\s*"
    r"(?:(?P<episode>e\s*\d{1,5})|ep(?:is[oó]dio)?\s*\d{1,5})?",
    re.IGNORECASE,
)


def normalize_text(value: Any) -> str:
    """Normalize only for matching; persisted display text is untouched."""
    if value is None:
        return ""
    text = unicodedata.normalize("NFKD", str(value))
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    text = text.casefold()
    text = re.sub(r"[\W_]+", " ", text, flags=re.UNICODE)
    return " ".join(text.split())


def _tokens(value: Any) -> list[str]:
    return _TOKEN_RE.findall(normalize_text(value))


def _as_list(value: Any) -> list[Any]:
    if value is None:
        return []
    if isinstance(value, (list, tuple, set)):
        return list(value)
    return [value]


def _episodes(anime: dict) -> list[dict]:
    result = []
    for season in anime.get("seasons") or []:
        result.extend(season.get("episodes") or [])
    for group in anime.get("specials") or []:
        result.extend(group.get("episodes") or [])
    result.extend(anime.get("media_files") or [])
    return result


def _regular_episodes(anime: dict) -> list[dict]:
    return [
        episode for episode in _episodes(anime)
        if str(episode.get("episode_type") or "regular").casefold() not in _SPECIAL_TYPES
        and str(episode.get("episode_type") or "").casefold() != "movie"
    ]


def _numeric(value: Any, default: float = -1) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return default
    return number if math.isfinite(number) else default


def _ratio(episode: dict) -> float:
    return progress_ratio(episode)


def _title(anime: dict) -> str:
    meta = anime.get("meta") or {}
    return str(
        anime.get("main_title")
        or meta.get("title")
        or meta.get("title_official")
        or ""
    )


def _metadata(anime: dict) -> dict:
    return anime.get("meta") or {}


def _metadata_available(anime: dict) -> bool:
    meta = _metadata(anime)
    return bool(meta.get("anilist_id") or meta.get("metadata_source") not in (None, "", "local"))


def _artwork_available(anime: dict) -> bool:
    if anime.get("artwork_available") is not None:
        return bool(anime.get("artwork_available"))
    meta = _metadata(anime)
    # A remote URL is metadata, not proof that a usable local artwork exists.
    # The local catalog/search layer never performs network I/O.
    local_path = str(
        meta.get("cover_cache")
        or anime.get("cover")
        or ""
    ).strip()
    if not local_path or local_path.startswith(("http://", "https://")):
        return False
    try:
        return os.path.isfile(local_path) and os.path.getsize(local_path) > 0
    except OSError:
        return False


def _source_kinds(anime: dict) -> set[str]:
    values = set()
    for episode in _episodes(anime):
        if episode.get("missing"):
            continue
        kind = episode.get("source_kind")
        if kind:
            values.add(str(kind).casefold())
    return values


def _media_types(anime: dict) -> set[str]:
    media_kind = str(anime.get("media_kind") or _metadata(anime).get("media_kind") or "series").casefold()
    episodes = _episodes(anime)
    types = set()
    if media_kind == "movie" or any(str(e.get("episode_type") or "").casefold() == "movie" for e in episodes):
        types.add("movie")
    if any(str(e.get("episode_type") or "").casefold() in _SPECIAL_TYPES for e in episodes):
        types.add("special")
    regular = [e for e in episodes if str(e.get("episode_type") or "regular").casefold() not in _SPECIAL_TYPES | {"movie"}]
    if regular:
        types.add("episode")
        if media_kind not in {"movie", "unknown"}:
            types.add("series")
    return types


def _search_values(anime: dict) -> list[str]:
    """Return the persisted fields that define full-text search.

    Search intentionally covers visible and alternate titles, metadata text,
    genres/aliases/user state, plus persisted episode identifiers and file
    fields. A hit outside the visible title is therefore data-driven rather
    than an arbitrary UI match.
    """
    meta = _metadata(anime)
    values = [
        _title(anime), meta.get("title"), meta.get("title_official"),
        meta.get("romaji"), meta.get("english"), meta.get("native"),
        meta.get("description"), meta.get("studio"),
        *(_as_list(anime.get("genres"))),
        *(_as_list(anime.get("genre_aliases"))),
        *(_as_list(anime.get("user_tags"))),
        anime.get("personal_note"),
    ]
    aliases = meta.get("aliases")
    if isinstance(aliases, str):
        try:
            import json
            aliases = json.loads(aliases)
        except (TypeError, ValueError):
            aliases = [aliases]
    values.extend(_as_list(aliases))
    for episode in _episodes(anime):
        values.extend([
            episode.get("file_name"), episode.get("episode_title"),
            episode.get("path"), episode.get("episode_type"),
            f"S{episode.get('season')}" if episode.get("season") is not None else "",
            f"E{episode.get('number')}" if episode.get("number") is not None else "",
            f"EP {episode.get('number')}" if episode.get("number") is not None else "",
            f"absolute {episode.get('absolute_number')}" if episode.get("absolute_number") is not None else "",
        ])
    return [normalize_text(value) for value in values if value not in (None, "")]


def _identifier_match(query: str, episodes: Iterable[dict]) -> bool:
    raw = normalize_text(query)
    if not raw:
        return True
    compact = raw.replace(" ", "")
    season_episode_match = re.fullmatch(r"s(\d{1,3})e(\d{1,5})", compact)
    season_match = re.fullmatch(r"s(\d{1,3})", compact)
    episode_match = re.fullmatch(r"(?:e|ep)(\d{1,5})", compact)
    episode_word_match = re.fullmatch(r"(?:episodio|episode)(\d{1,5})", compact)
    absolute_match = re.fullmatch(r"(?:absolute|abs)(\d+(?:\.\d+)?)", compact)
    bare_number = re.fullmatch(r"\d+(?:\.\d+)?", compact)

    # Identifier syntax targets regular episodes; specials/movies are separate media.
    identifier_episodes = [e for e in episodes if str(e.get("episode_type") or "regular").casefold() not in _SPECIAL_TYPES | {"movie"}]
    for episode in identifier_episodes:
        if season_episode_match and (
            _numeric(episode.get("season")) == float(season_episode_match.group(1))
            and _numeric(episode.get("number")) == float(season_episode_match.group(2))
        ):
            return True
        if season_match and _numeric(episode.get("season")) == float(season_match.group(1)):
            return True
        if episode_match or episode_word_match:
            wanted = float((episode_match or episode_word_match).group(1))
            if _numeric(episode.get("number")) == wanted:
                return True
        if absolute_match and _numeric(episode.get("absolute_number")) == float(absolute_match.group(1)):
            return True
        # A bare number is a search criterion only. It is never used to
        # identify or create an episode; it matches already-persisted fields.
        if bare_number:
            wanted = float(bare_number.group(0))
            if _numeric(episode.get("number")) == wanted or _numeric(episode.get("absolute_number")) == wanted:
                return True
    return False


def _query_matches(anime: dict, query: str) -> bool:
    query_tokens = _tokens(query)
    if not query_tokens:
        return True
    episodes = _episodes(anime)
    compact = normalize_text(query).replace(" ", "")
    looks_like_identifier = bool(re.fullmatch(r"(?:s\d{1,3}(?:e\d{1,5})?|(?:e|ep|episodio|episode)\d{1,5}|(?:absolute|abs)\d+(?:\.\d+)?|\d+(?:\.\d+)?)", compact))
    if looks_like_identifier:
        return _identifier_match(query, episodes)
    haystack = " ".join(_search_values(anime))
    return all(token in haystack for token in query_tokens)


@dataclass(frozen=True)
class SearchFilterSort:
    query: str = ""
    media_type: str = "Todos"
    state: str = "Todos"
    season: int | str | None = None
    episode_type: str = "Todos"
    genre: str = "Todos"
    genre_id: str | None = None
    tag: str = "Todos"
    source_kind: str = "Todos"
    availability: str = "Todos"
    metadata: str = "Todos"
    artwork: str = "Todos"
    sort: str = ""
    descending: bool = True
    _extra: dict = field(default_factory=dict, repr=False, compare=False)

    def _matches(self, anime: dict) -> bool:
        episodes = _episodes(anime)
        available = [e for e in episodes if not e.get("missing")]
        state = self.state
        episode_states = [consumption_state(e) for e in available]
        active = any(s.value == "in_progress" for s in episode_states)
        watched = bool(available) and all(s.value in {"completed", "watched"} for s in episode_states)
        any_watched = any(s.value in {"completed", "watched"} for s in episode_states)
        unwatched = any(s.value == "unwatched" for s in episode_states)
        if state == "Favoritos" and not anime.get("favorite"):
            return False
        if state == "Fixados" and not anime.get("is_pinned"):
            return False
        if state == "Assistidos" and not any_watched:
            return False
        if state == "Não assistidos" and not unwatched:
            return False
        if state == "Em andamento" and not active:
            return False
        if state == "Concluídos" and not watched:
            return False
        if state == "Não iniciados" and (not available or active or any(e.get("watched") for e in available)):
            return False
        if state == "Com nota" and not str(anime.get("personal_note") or "").strip():
            return False
        if state == "Sem nota" and str(anime.get("personal_note") or "").strip():
            return False
        if state == "Sem metadata" and _metadata_available(anime):
            return False
        if state == "Sem capa" and _artwork_available(anime):
            return False

        if self.media_type != "Todos":
            wanted = {"Série/Anime": "series", "Série": "series", "Anime": "series",
                      "Filme": "movie", "Movie": "movie", "Especial": "special",
                      "Special": "special", "Episódio": "episode", "Episode": "episode"}.get(self.media_type, self.media_type.casefold())
            if wanted not in _media_types(anime):
                return False

        if self.season not in (None, "", "Todos"):
            try:
                wanted_season = int(self.season)
            except (TypeError, ValueError):
                return False
            if not any(_numeric(e.get("season")) == wanted_season for e in episodes):
                return False

        if self.episode_type not in ("Todos", "", None):
            wanted_type = str(self.episode_type).casefold()
            if not any(str(e.get("episode_type") or "regular").casefold() == wanted_type for e in episodes):
                return False

        if self.genre_id:
            if self.genre_id not in {str(value) for value in _as_list(anime.get("genre_ids"))}:
                return False
        elif self.genre not in ("Todos", "", None):
            wanted_genre = normalize_text(self.genre)
            aliases = _as_list(anime.get("genre_aliases"))
            if not any(normalize_text(g) == wanted_genre for g in _as_list(anime.get("genres")) + aliases):
                return False

        tags = {normalize_text(t) for t in _as_list(anime.get("user_tags"))}
        if self.tag == "Sem etiqueta" and tags:
            return False
        if self.tag not in ("Todos", "Sem etiqueta", "", None) and normalize_text(self.tag) not in tags:
            return False

        if self.source_kind not in ("Todos", "", None):
            wanted_source = normalize_text(self.source_kind)
            if wanted_source not in _source_kinds(anime):
                return False

        if self.availability == "Disponível" and not available:
            return False
        if self.availability == "Missing" and not any(e.get("missing") for e in episodes):
            return False
        if self.availability == "Com missing" and not any(e.get("missing") for e in episodes):
            return False
        if self.availability == "Sem missing" and any(e.get("missing") for e in episodes):
            return False

        if self.metadata == "Disponível" and not _metadata_available(anime):
            return False
        if self.metadata == "Ausente" and _metadata_available(anime):
            return False
        if self.artwork == "Disponível" and not _artwork_available(anime):
            return False
        if self.artwork == "Ausente" and _artwork_available(anime):
            return False

        return _query_matches(anime, self.query)

    @staticmethod
    def _sort_key(anime: dict, sort: str):
        episodes = _episodes(anime)
        playable = [e for e in episodes if not e.get("missing")]
        title = normalize_text(_title(anime))
        added = _numeric(_metadata(anime).get("added_at"), 0)
        watched_at = max((_numeric(e.get("last_played_at"), 0) for e in playable), default=0)
        progress = sum(_ratio(e) for e in playable) / len(playable) if playable else -1
        first = min(
            ((_numeric(e.get("season"), 10**6), _numeric(e.get("number"), 10**6), _numeric(e.get("absolute_number"), 10**6)) for e in playable),
            default=(10**6, 10**6, 10**6),
        )
        modified = max((_numeric(e.get("modified_at"), 0) for e in playable), default=0)
        duration = sum(max(0, _numeric(e.get("duration"), 0)) for e in playable)
        size = sum(max(0, _numeric(e.get("file_size"), 0)) for e in playable)
        favorites = int(bool(anime.get("favorite")))
        pinned = int(bool(anime.get("is_pinned")))
        if sort in ("Nome A-Z", "Título A-Z", "Titulo A-Z"):
            return (title,)
        if sort in ("Nome Z-A", "Título Z-A", "Titulo Z-A"):
            return (title,)
        if sort in ("Assistidos recentemente", "Recentemente assistidos"):
            return (watched_at,)
        if sort in ("Progresso", "Mais progresso"):
            return (progress, title)
        if sort in ("Episódio", "Número do episódio", "Temporada + episódio"):
            return (first, title)
        if sort in ("Modificação", "Data de modificação"):
            return (modified, title)
        if sort in ("Duração",):
            return (duration, title)
        if sort in ("Tamanho",):
            return (size, title)
        if sort in ("Favoritos primeiro",):
            return (favorites, title)
        if sort in ("Fixados primeiro",):
            return (pinned, title)
        return (added, title)

    def apply(self, catalog: Iterable[dict]) -> list[dict]:
        result = [anime for anime in catalog if self._matches(anime)]
        sort = self.sort or ""
        if not sort:
            return result
        reverse = self.descending
        if sort in ("Nome A-Z", "Título A-Z", "Titulo A-Z"):
            reverse = False
        elif sort in ("Nome Z-A", "Título Z-A", "Titulo Z-A"):
            reverse = True
        result.sort(key=lambda anime: self._sort_key(anime, sort), reverse=reverse)
        return result


class LibrarySearchEngine:
    """Public facade used by LibraryService/Home and future local-library views."""

    @staticmethod
    def search(catalog: Iterable[dict], **filters) -> list[dict]:
        return SearchFilterSort(**filters).apply(catalog)

    @staticmethod
    def options(catalog: Iterable[dict]) -> dict:
        genres = {}
        tags = {}
        seasons = set()
        episode_types = set()
        sources = set()
        for anime in catalog:
            for genre in _as_list(anime.get("genres")):
                if genre:
                    genres[normalize_text(genre)] = str(genre)
            for tag in _as_list(anime.get("user_tags")):
                if tag:
                    tags[normalize_text(tag)] = str(tag)
            for episode in _episodes(anime):
                if episode.get("season") is not None:
                    seasons.add(int(episode["season"]))
                if episode.get("episode_type"):
                    episode_types.add(str(episode["episode_type"]))
                if episode.get("source_kind"):
                    sources.add(str(episode["source_kind"]))
        return {
            "genres": sorted(genres.values(), key=normalize_text),
            "tags": sorted(tags.values(), key=normalize_text),
            "seasons": sorted(seasons),
            "episode_types": sorted(episode_types, key=normalize_text),
            "source_kinds": sorted(sources, key=normalize_text),
            "media_types": ["Série/Anime", "Filme", "Especial", "Episódio"],
            "availability": ["Disponível", "Com missing", "Sem missing"],
            "metadata": ["Disponível", "Ausente"],
            "artwork": ["Disponível", "Ausente"],
            "states": ["Todos", "Favoritos", "Fixados", "Assistidos", "Não assistidos",
                       "Em andamento", "Concluídos", "Não iniciados", "Com nota", "Sem nota",
                       "Sem metadata", "Sem capa"],
            "sorts": ["Mais recentes", "Assistidos recentemente", "Progresso",
                      "Episódio", "Temporada + episódio", "Modificação", "Duração",
                      "Tamanho", "Favoritos primeiro", "Fixados primeiro",
                      "Nome A-Z", "Nome Z-A"],
        }
