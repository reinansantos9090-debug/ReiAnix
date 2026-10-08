"""Deterministic, conservative local-media identification."""
from __future__ import annotations

import os
import re
from dataclasses import dataclass, field


VIDEO_EXTENSIONS = {
    ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v",
    ".ts", ".m2ts", ".flv", ".wmv",
}

_TECHNICAL_PATTERNS = (
    re.compile(r"(?<![A-Za-z0-9])(?:2160p|1080p|720p|480p|4320p|4k)(?![A-Za-z0-9])", re.I),
    re.compile(r"(?<![A-Za-z0-9])(?:x264|x265|h264|h\.264|h265|h\.265|hevc|av1|avc)(?![A-Za-z0-9])", re.I),
    re.compile(r"(?<![A-Za-z0-9])(?:8bit|10bit|12bit|hdr10|hdr|dv|dolby[ ._-]*vision)(?![A-Za-z0-9])", re.I),
    re.compile(r"(?<![A-Za-z0-9])(?:web[ ._-]*dl|webrip|bluray|bdrip|bdremux|hdtv)(?![A-Za-z0-9])", re.I),
    re.compile(r"(?<![A-Za-z0-9])(?:aac|ac3|eac3|flac|dts|dual[ ._-]*audio|multi[ ._-]*audio)(?![A-Za-z0-9])", re.I),
    re.compile(r"(?<![A-Za-z0-9])(?:pt[-_ ]?br|pt|portugu[eê]s|eng|english|jpn|japanese|dub|dublado|sub|legendado)(?![A-Za-z0-9])", re.I),
)
_SEASON = re.compile(r"(?:\bS|\bseason[ ._-]*|\btemporada[ ._-]*|\btemp[ ._-]*)(\d{1,3})(?=\b|[ ._-]*E)", re.I)
_TEMP_EPISODE = re.compile(r"\b(?:temp|temporada)[ ._-]*(\d{1,3})[ ._-]*E(?:P(?:ISODE)?)?[ ._-]*(\d{1,4})(?:v\d+)?\b", re.I)
_SXXEXX = re.compile(r"\bS(\d{1,3})[ ._-]*E(?:P(?:ISODE)?)?[ ._-]*(\d{1,4})(?:v\d+)?(?=\b|E\d)", re.I)
_X_EPISODE = re.compile(r"\b(\d{1,2})\s*[xX]\s*(\d{1,4})(?:v\d+)?\b")
_WORD_EPISODE = re.compile(r"\b(?:E(?:P(?:ISODE)?)?|EPISODE|Epis[oó]dio)[ ._-]*(\d{1,4})(?:v\d+)?\b", re.I)
_RECORDING_TIMESTAMP = re.compile(r"\b(?:recording|recorded)[ ._-]*(?:19\d{2}|20\d{2})\d{4}\s+(?:[01]?\d|2[0-3])\d{4}\b", re.I)
_STANDALONE_TIMESTAMP = re.compile(r"\b(?:19\d{2}|20\d{2})\d{4}\s+(?:[01]?\d|2[0-3])\d{4}\b")
_SPECIAL = re.compile(r"\b(OVA|OAD|ONA|SPECIALS?|SP|EXTRA)(?:\b|(?=\d))[ ._-]*(\d{1,4})?", re.I)
_SPECIAL_SEASON_ZERO = re.compile(r"\bS00(?:[ ._-]*E(?:P(?:ISODE)?)?[ ._-]*(\d{1,4}))?\b", re.I)
_MOVIE = re.compile(r"\b(?:MOVIE|FILM)\b", re.I)
_ABSOLUTE = re.compile(r"\b(?:ABS(?:OLUTE)?|ANIME[- ._]?EP)[ ._-]*(\d{1,4})\b", re.I)
_YEAR = re.compile(r"^(?:19\d{2}|20\d{2})$")
_TECH_NUMBER = re.compile(r"^(?:264|265|4320|2160|1080|720|480|8|10|12)$")
_VOLUME_OR_DISC = re.compile(r"\b(?:vol(?:ume)?|disc|disk)[ ._-]*\d{1,3}\b", re.I)


@dataclass(frozen=True)
class ParsedEpisode:
    anime_title: str
    season: int | None
    episode: float | None
    display_title: str
    extension: str
    episode_type: str = "unknown"
    identification_source: str = "unknown"
    confidence: str = "low"
    absolute_number: int | None = None
    evidence: tuple[str, ...] = field(default_factory=tuple)
    unresolved_parts: tuple[str, ...] = field(default_factory=tuple)
    technical_tokens: tuple[str, ...] = field(default_factory=tuple)


def _parts(path: str, library_root: str | None) -> list[str]:
    raw = str(path or "")
    if library_root:
        try:
            relative = os.path.relpath(raw, library_root)
            items = [x for x in relative.replace("\\", "/").split("/")[:-1]
                     if x and x not in {".", ".."}]
            if items:
                return items
        except (ValueError, TypeError):
            pass
    return [x for x in raw.replace("\\", "/").split("/")[:-1] if x and x not in {".", ".."}]


def _normalise(value: str) -> str:
    value = re.sub(r"[._]+", " ", value)
    value = re.sub(r"\s+", " ", value)
    return value.strip()


def _technical_tokens(value: str) -> tuple[str, ...]:
    found = []
    for pattern in _TECHNICAL_PATTERNS:
        found.extend(m.group(0) for m in pattern.finditer(value))
    return tuple(dict.fromkeys(found))


def _clean_title(value: str) -> str:
    value = _normalise(value)
    value = re.sub(r"^\s*\[[^\]]{1,120}\]\s*", "", value)
    value = re.sub(r"\[[^\]]{0,120}\]", " ", value)
    for pattern in _TECHNICAL_PATTERNS:
        value = pattern.sub(" ", value)
    value = _RECORDING_TIMESTAMP.sub(" ", value)
    value = _STANDALONE_TIMESTAMP.sub(" ", value)
    value = re.sub(r"\b(?:complete|batch|season[ ._-]*pack)\b", " ", value, flags=re.I)
    value = re.sub(r"\s+", " ", value)
    return value.strip(" -_[](){}")


def _folder_season(parts: list[str]) -> int | None:
    for part in reversed(parts):
        m = _SEASON.search(part)
        if m:
            return int(m.group(1))
    return None


def _probable_title_folder(parts: list[str]) -> str:
    ignored = {"anime", "videos", "video", "media", "downloads", "download",
               "complete", "finished", "batch", "dub", "sub"}
    for part in reversed(parts):
        cleaned = _clean_title(part)
        if not cleaned or cleaned.casefold() in ignored:
            continue
        if _SEASON.search(part) or _VOLUME_OR_DISC.search(part):
            continue
        if _YEAR.fullmatch(cleaned) or _TECH_NUMBER.fullmatch(cleaned):
            continue
        return cleaned
    return ""


def _episode_title_after(stem: str, marker: re.Match[str] | None) -> str | None:
    if not marker:
        return None
    tail = re.sub(r"^[ ._\-–—]+", "", stem[marker.end():])
    tail = re.sub(r"\[[^\]]*\]", " ", tail)
    for pattern in _TECHNICAL_PATTERNS:
        tail = pattern.sub(" ", tail)
    tail = re.sub(r"\s+", " ", tail).strip(" -_")
    if not tail or re.fullmatch(r"v?\d+", tail, re.I):
        return None
    return tail


def parse_video_path(path: str, library_root: str | None = None) -> ParsedEpisode:
    """Identify local media without network access or aggressive guessing."""
    file_name = os.path.basename(str(path or ""))
    stem, extension = os.path.splitext(file_name)
    folders = _parts(str(path or ""), library_root)
    parent = folders[-1] if folders else os.path.basename(os.path.dirname(str(path or "")))
    technical = _technical_tokens(stem)
    folder_season = _folder_season(folders)

    season = folder_season
    episode = None
    absolute_number = None
    kind = "unknown"
    source = "unknown"
    confidence = "low"
    evidence: list[str] = []
    unresolved: list[str] = []
    marker = None

    special = _SPECIAL.search(stem)
    season_zero = _SPECIAL_SEASON_ZERO.search(stem)
    movie = _MOVIE.search(stem)
    absolute = _ABSOLUTE.search(stem)
    explicit = _SXXEXX.search(stem)
    temp_episode = _TEMP_EPISODE.search(stem)
    cross = _X_EPISODE.search(stem)
    word = _WORD_EPISODE.search(stem)

    if season_zero:
        marker = season_zero
        value = season_zero.group(1)
        episode = float(value) if value else None
        kind = "special"
        source, confidence = "s00_marker", "high"
        evidence.append(f"SPECIAL_TYPE_TOKEN:{season_zero.group(0)}")
    elif special:
        marker = special
        value = special.group(2)
        episode = float(value) if value else None
        token = special.group(1).casefold()
        kind = {"ova": "ova", "oad": "oad", "ona": "ona",
                "extra": "extra", "sp": "special",
                "special": "special", "specials": "special"}.get(token, "special")
        source, confidence = "special_marker", "high"
        evidence.append(f"SPECIAL_TYPE_TOKEN:{special.group(0)}")
    else:
        if absolute:
            absolute_number = int(absolute.group(1))
            source, confidence = "absolute_marker", "high"
            evidence.append(f"ABSOLUTE_NUMBER_TOKEN:{absolute.group(0)}")

        if explicit:
            marker = explicit
            file_season = int(explicit.group(1))
            season = file_season
            episode = float(explicit.group(2))
            kind, source, confidence = "regular", "sxxexx", "high"
            evidence.append(f"EXPLICIT_SEASON_EPISODE:{explicit.group(0)}")
            if folder_season is not None and folder_season != file_season:
                confidence = "medium"
                unresolved.append(f"season_conflict:file={file_season},folder={folder_season}")
                evidence.append("CONFLICT_FILE_SEASON_VS_FOLDER")
        elif temp_episode:
            marker = temp_episode
            file_season = int(temp_episode.group(1))
            season = file_season
            episode = float(temp_episode.group(2))
            kind, source, confidence = "regular", "temp_episode", "high"
            evidence.append(f"EXPLICIT_SEASON_EPISODE:{temp_episode.group(0)}")
            if folder_season is not None and folder_season != file_season:
                confidence = "medium"
                unresolved.append(f"season_conflict:file={file_season},folder={folder_season}")
                evidence.append("CONFLICT_FILE_SEASON_VS_FOLDER")
        elif cross:
            marker = cross
            season = int(cross.group(1))
            episode = float(cross.group(2))
            kind, source, confidence = "regular", "x_episode", "high"
            evidence.append(f"EXPLICIT_SEASON_EPISODE:{cross.group(0)}")
        elif word:
            marker = word
            episode = float(word.group(1))
            season = season or 1
            kind, source, confidence = "regular", "episode_marker", "high"
            evidence.append(f"EXPLICIT_EPISODE_TOKEN:{word.group(0)}")
        elif movie:
            marker = movie
            kind, source, confidence = "movie", "movie_marker", "high"
            evidence.append(f"MOVIE_TYPE_TOKEN:{movie.group(0)}")
        else:
            candidates = []
            for candidate in re.finditer(r"(?<!\d)(\d{1,4})(?:v\d+)?\b", stem):
                value = int(candidate.group(1))
                before = stem[:candidate.start()]
                if _YEAR.fullmatch(candidate.group(1)):
                    continue
                if re.search(rf"(?<!\d){value}p\b", stem, re.I):
                    continue
                if _TECH_NUMBER.fullmatch(candidate.group(1)):
                    if re.search(rf"(?:x|h\.?|codec|bit)\s*{value}\b", stem, re.I):
                        continue
                    if candidate.group(1) in {"264", "265", "1080", "2160", "720", "480"}:
                        continue
                if _VOLUME_OR_DISC.search(stem):
                    continue
                if re.search(r"\b(?:vol|volume|disc|disk)\s*$", before, re.I):
                    continue
                if not before.strip(" ._-[](){}"):
                    continue
                candidates.append(candidate)

            if candidates and absolute is None:
                marker = candidates[-1]
                episode = float(int(marker.group(1)))
                season = season or 1
                kind, source, confidence = "regular", "numeric_suffix", "medium"
                evidence.append(f"NUMERIC_SUFFIX:{marker.group(0)}")
                if len(candidates) > 1:
                    unresolved.append("multiple_numeric_candidates")

    if kind == "regular" and episode is not None and episode <= 0:
        kind = "unknown"
        episode = None
        source = "invalid_episode_number"
        confidence = "low"
        unresolved.append("invalid_regular_episode_number")
        evidence.append("INVALID_REGULAR_EPISODE_NUMBER")

    if kind == "regular" and folder_season == 0:
        kind = "special"
        source = "s00_folder_context"
        confidence = "medium"
        evidence.append("SPECIAL_S00_FOLDER_CONTEXT")

    if kind == "unknown" and folder_season is not None:
        evidence.append(f"FOLDER_SEASON:{folder_season}")
        unresolved.append("episode_not_identified")

    title_source = stem[:marker.start()] if marker else stem
    title = _clean_title(title_source)
    if not marker:
        cleaned_stem = _RECORDING_TIMESTAMP.sub(" ", stem)
        cleaned_stem = _STANDALONE_TIMESTAMP.sub(" ", cleaned_stem)
        if cleaned_stem.strip() != stem.strip():
            title = _clean_title(cleaned_stem)
    if not title or re.fullmatch(r"(?:e|ep|episode)?\s*\d+", title, re.I):
        title = _clean_title(parent)
    if not title or _SEASON.fullmatch(title):
        title = _probable_title_folder(folders)
    if folder_season is not None and kind == "unknown":
        folder_title = _probable_title_folder(folders)
        if folder_title:
            title = folder_title
    if not title:
        title = "Arquivo não identificado"

    # A number-only file remains unknown even when a series folder exists.
    if re.fullmatch(r"\d{1,4}(?:v\d+)?", stem.strip()):
        if _probable_title_folder(folders):
            unresolved.append("numeric_filename_without_episode_marker")
        kind = "unknown"
        source = "unknown"
        confidence = "low"
        episode = None

    # Multi-episode/range files are one physical file.  We keep the first
    # explicit episode only when the parser already supports it, but expose
    # the ambiguity so a future model can represent ranges without duplication.
    if re.search(r"\bS\d{1,3}E\d{1,4}(?:E\d{1,4}|-\d{1,4})\b", stem, re.I):
        unresolved.append("multi_episode_range")

    display_title = _episode_title_after(stem, marker) or title
    if kind == "unknown":
        source = "folder_context" if folder_season is not None else "unknown"
        confidence = "low"

    return ParsedEpisode(
        anime_title=title,
        season=season,
        episode=episode,
        display_title=display_title,
        extension=extension.lower(),
        episode_type=kind,
        identification_source=source,
        confidence=confidence,
        absolute_number=absolute_number,
        evidence=tuple(evidence),
        unresolved_parts=tuple(dict.fromkeys(unresolved)),
        technical_tokens=technical,
    )
