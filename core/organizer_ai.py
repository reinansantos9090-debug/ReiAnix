"""Deterministic local ranking of AniList candidates.

This module never performs network requests. It receives one parsed local title
and the candidate media returned by the existing AniList client.
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from difflib import SequenceMatcher


_TECHNICAL_RE = re.compile(
    r"\b(?:2160p|1080p|720p|480p|4320p|4k|x264|x265|h264|h\.264|h265|h\.265|hevc|av1|avc|"
    r"8bit|10bit|12bit|hdr10|hdr|dv|dolby[ ._-]*vision|web[ ._-]*dl|webrip|bluray|bdrip|bdremux|hdtv|"
    r"aac|ac3|eac3|flac|dts|dual[ ._-]*audio|multi[ ._-]*audio|pt[-_ ]?br|portugu[eê]s|eng|english|"
    r"jpn|japanese|dub|dublado|sub|legendado|softsub|hardsub|ass|srt)\b",
    re.IGNORECASE,
)
_EPISODE_RE = re.compile(
    r"\b(?:s\d{1,3}[ ._-]*e(?:p(?:isode)?)?[ ._-]*\d{1,4}|"
    r"e(?:p(?:isode)?)?[ ._-]*\d{1,4}|episode[ ._-]*\d{1,4}|epis[oó]dio[ ._-]*\d{1,4})\b",
    re.IGNORECASE,
)
_SEASON_RE = re.compile(
    r"\b(?:s|season|temporada|temp)[ ._-]*(\d{1,3})(?:st|nd|rd|th)?\b",
    re.IGNORECASE,
)
_ORDINAL_SEASON_RE = re.compile(r"\b(\d{1,2})(?:st|nd|rd|th)?[ ._-]*season\b", re.IGNORECASE)
_YEAR_RE = re.compile(r"\b((?:19\d{2}|20\d{2}))\b")
_MOVIE_RE = re.compile(r"\b(?:movie|film)\b", re.IGNORECASE)
_FORMAT_ALIASES = {
    "tv": "TV",
    "tv_short": "TV_SHORT",
    "movie": "MOVIE",
    "ova": "OVA",
    "ona": "ONA",
    "special": "SPECIAL",
    "music": "MUSIC",
}


@dataclass(frozen=True)
class MatchContext:
    season_number: int | None = None
    episode_type: str | None = None
    media_kind: str | None = None
    year: int | None = None


def normalize(value: str) -> str:
    """Normalize matching text without destroying non-Latin scripts."""
    text = unicodedata.normalize("NFKD", str(value or "")).casefold()
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    text = text.replace("&", " and ")
    text = re.sub(r"^\s*\[[^\]]{1,120}\]\s*", " ", text)
    text = re.sub(r"\[[^\]]{0,120}\]", " ", text)
    text = _EPISODE_RE.sub(" ", text)
    text = _SEASON_RE.sub(" ", text)
    text = _ORDINAL_SEASON_RE.sub(" ", text)
    text = _TECHNICAL_RE.sub(" ", text)
    text = _MOVIE_RE.sub(" ", text)
    text = re.sub(r"\b(?:19\d{2}|20\d{2})\b", " ", text)
    # Keep Unicode letters/numbers while treating punctuation and separators as spaces.
    text = "".join(" " if unicodedata.category(ch)[0] in {"P", "S"} else ch for ch in text)
    return " ".join(text.split())


def _base_normalize(value: str) -> str:
    text = unicodedata.normalize("NFKD", str(value or "")).casefold()
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    text = re.sub(r"^\s*\[[^\]]{1,120}\]\s*", " ", text)
    text = re.sub(r"\[[^\]]{0,120}\]", " ", text)
    text = _EPISODE_RE.sub(" ", text)
    text = _SEASON_RE.sub(" ", text)
    text = _ORDINAL_SEASON_RE.sub(" ", text)
    text = _TECHNICAL_RE.sub(" ", text)
    text = re.sub(r"\b(?:19\d{2}|20\d{2})\b", " ", text)
    text = "".join(" " if unicodedata.category(ch)[0] in {"P", "S"} else ch for ch in text)
    return " ".join(text.split())


def _extract_season(value: str) -> int | None:
    match = _SEASON_RE.search(str(value or ""))
    if match:
        return int(match.group(1))
    match = _ORDINAL_SEASON_RE.search(str(value or ""))
    if match:
        return int(match.group(1))
    return None


def _extract_year(value: str) -> int | None:
    match = _YEAR_RE.search(str(value or ""))
    return int(match.group(1)) if match else None


def _title_values(candidate: dict) -> list[tuple[str, str]]:
    title = candidate.get("title") or {}
    values: list[tuple[str, str]] = []
    for key in ("english", "romaji", "native"):
        value = title.get(key)
        if value:
            values.append((key, str(value)))
    for value in candidate.get("synonyms") or []:
        if value:
            values.append(("synonym", str(value)))
    return values


def _format(candidate: dict) -> str:
    value = str(candidate.get("format") or "").casefold().replace("-", "_")
    return _FORMAT_ALIASES.get(value, value.upper() if value else "")


def _expected_format(context: MatchContext) -> str | None:
    kind = str(context.episode_type or context.media_kind or "").casefold()
    if kind == "movie":
        return "MOVIE"
    if kind == "ova":
        return "OVA"
    if kind == "ona":
        return "ONA"
    if kind in {"special", "extra"}:
        return "SPECIAL"
    return None


def _token_score(local: str, remote: str) -> float:
    if not local or not remote:
        return 0.0
    if local == remote:
        return 1.0
    if local in remote or remote in local:
        containment = min(len(local), len(remote)) / max(len(local), len(remote))
        return 0.78 + (0.18 * containment)
    local_tokens, remote_tokens = set(local.split()), set(remote.split())
    overlap = len(local_tokens & remote_tokens) / len(local_tokens | remote_tokens) if local_tokens and remote_tokens else 0.0
    return max(SequenceMatcher(None, local, remote).ratio(), overlap)


def _candidate_score(local_title: str, candidate: dict, context: MatchContext) -> tuple[float, list[str], str]:
    local = _base_normalize(local_title)
    values = _title_values(candidate)
    best = 0.0
    best_kind = ""
    reasons: list[str] = []
    for kind, value in values:
        remote = _base_normalize(value)
        score = _token_score(local, remote)
        if score > best:
            best = score
            best_kind = kind
    if best_kind == "synonym" and best >= 0.90:
        reasons.append("synonym_match")
    elif best >= 0.999:
        reasons.append("title_exact")
    elif best >= 0.82:
        reasons.append("title_similarity")

    expected_format = _expected_format(context)
    candidate_format = _format(candidate)
    if expected_format and candidate_format:
        if expected_format == candidate_format:
            best += 0.08
            reasons.append("format_match")
        elif {expected_format, candidate_format} <= {"TV", "TV_SHORT", "ONA"}:
            best -= 0.02
        else:
            best -= 0.18
            reasons.append("format_conflict")

    local_season = context.season_number or _extract_season(local_title)
    candidate_season = None
    for _, value in values:
        candidate_season = _extract_season(value)
        if candidate_season is not None:
            break
    if local_season is not None and candidate_season is not None:
        if local_season == candidate_season:
            best += 0.07
            reasons.append("season_match")
        else:
            best -= 0.12
            reasons.append("season_conflict")

    local_year = context.year or _extract_year(local_title)
    candidate_year = candidate.get("seasonYear")
    if local_year is not None and candidate_year is not None:
        try:
            candidate_year = int(candidate_year)
        except (TypeError, ValueError):
            candidate_year = None
        if candidate_year == local_year:
            best += 0.04
            reasons.append("year_match")
        elif candidate_year is not None:
            best -= 0.03

    return max(0.0, min(1.0, round(best, 3))), reasons, best_kind


class AnimeOrganizer:
    """Rank AniList candidates locally and only auto-confirm safe matches."""
    AUTO_CONFIRM_SCORE = 0.86
    AUTO_CONFIRM_MARGIN = 0.05
    MINIMUM_SCORE = 0.48

    @classmethod
    def rank(
        cls,
        local_title: str,
        candidates: list[dict],
        *,
        context: MatchContext | dict | None = None,
    ) -> list[dict]:
        if isinstance(context, dict):
            context = MatchContext(
                season_number=context.get("season_number"),
                episode_type=context.get("episode_type"),
                media_kind=context.get("media_kind"),
                year=context.get("year"),
            )
        context = context or MatchContext()
        ranked = []
        for candidate in candidates or []:
            if not isinstance(candidate, dict) or candidate.get("id") is None:
                continue
            score, reasons, title_kind = _candidate_score(local_title, candidate, context)
            item = dict(candidate)
            item["match_score"] = score
            item["match_reasons"] = reasons
            item["match_title_kind"] = title_kind
            item["match_format"] = _format(candidate)
            ranked.append(item)
        return sorted(
            ranked,
            key=lambda item: (
                -float(item.get("match_score") or 0.0),
                str((item.get("title") or {}).get("english") or (item.get("title") or {}).get("romaji") or "").casefold(),
                int(item.get("id") or 0),
            ),
        )

    @classmethod
    def choose(
        cls,
        local_title: str,
        candidates: list[dict],
        *,
        context: MatchContext | dict | None = None,
    ) -> tuple[dict | None, bool, list[dict]]:
        ranked = cls.rank(local_title, candidates, context=context)
        best = ranked[0] if ranked and ranked[0]["match_score"] >= cls.MINIMUM_SCORE else None
        second = ranked[1]["match_score"] if len(ranked) > 1 else 0.0
        margin = (best["match_score"] - second) if best else 0.0
        is_confident = bool(
            best
            and best["match_score"] >= cls.AUTO_CONFIRM_SCORE
            and (len(ranked) == 1 or margin >= cls.AUTO_CONFIRM_MARGIN)
        )
        if best is not None:
            best["match_margin"] = round(margin, 3)
        return best, is_confident, ranked
