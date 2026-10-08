"""Single-source settings facade for ReiAnix.

Preferences are persisted by the existing LibraryStore.preferences table.  This
module adds schema/default/validation semantics without introducing another
database or settings backend.
"""
from __future__ import annotations

from dataclasses import dataclass
import json
import logging
import re
import time
from typing import Any

logger = logging.getLogger(__name__)

@dataclass(frozen=True)
class SettingDefinition:
    key: str
    kind: str
    default: Any
    choices: tuple[Any, ...] = ()

class SettingsDefaults:
    DEFINITIONS = (
        SettingDefinition("app.confirm_destructive", "bool", True),
        SettingDefinition("appearance.theme", "enum", "dark", ("system", "light", "dark")),
        SettingDefinition("appearance.card_size", "enum", "medium", ("small", "medium", "large")),
        SettingDefinition("appearance.show_thumbnails", "bool", True),
        SettingDefinition("library.sort_default", "enum", "added_desc", ("added_desc", "title_asc", "title_desc", "recently_watched")),
        SettingDefinition("library.grid_density", "enum", "medium", ("small", "medium", "large")),
        SettingDefinition("library.page_size", "int", 36, (24, 36, 48, 72)),
        SettingDefinition("library.continue_watching", "bool", True),
        SettingDefinition("library.continue_watching_limit", "int", 10, (5, 10, 15, 20)),
        SettingDefinition("player.autoplay_next", "bool", True),
        SettingDefinition("player.resume", "bool", True),
        SettingDefinition("player.default_speed", "float", 1.0, (0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)),
        SettingDefinition("player.aspect_ratio", "enum", "fit", ("fit", "fill")),
        SettingDefinition("player.zoom_enabled", "bool", False),
        SettingDefinition("player.immersive", "enum", "always", ("always", "landscape", "never")),
        SettingDefinition("player.rotation", "enum", "auto", ("auto", "portrait", "landscape")),
        SettingDefinition("player.pip", "bool", True),
        SettingDefinition("player.lock_mode", "bool", False),
        SettingDefinition("player.auto_hide_seconds", "int", 5, (5, 10, 15, 30, 0)),
        SettingDefinition("player.double_tap_seek_seconds", "int", 10, (5, 10, 15, 30)),
        SettingDefinition("player.long_press_speed", "float", 2.0, (1.5, 1.75, 2.0)),
        SettingDefinition("player.max_video_resolution", "enum", "auto", ("auto", "480p", "720p", "1080p", "1440p", "2160p")),
        SettingDefinition("player.max_video_frame_rate", "int", 0, (0, 24, 30, 60)),
        SettingDefinition("player.max_audio_channels", "int", 0, (0, 2, 6, 8)),
        SettingDefinition("gestures.volume", "bool", False),
        SettingDefinition("gestures.brightness", "bool", False),
        SettingDefinition("gestures.double_tap", "bool", False),
        SettingDefinition("gestures.long_press", "bool", False),
        SettingDefinition("audio.preferred_language", "language", ""),
        SettingDefinition("audio.preferred_subtitle_language", "language", ""),
        SettingDefinition("audio.subtitles", "enum", "auto", ("auto", "always", "never")),
        SettingDefinition("audio.subtitle_scale", "float", 1.0, (0.75, 1.0, 1.25, 1.5)),
        SettingDefinition("audio.subtitle_bottom_padding", "int", 8, (4, 8, 12, 16)),
        SettingDefinition("audio.subtitle_embedded_style", "bool", True),
        SettingDefinition("metadata.anilist_enabled", "bool", True),
        SettingDefinition("metadata.auto_match", "bool", True),
        SettingDefinition("artwork.enabled", "bool", True),
        SettingDefinition("artwork.cache_limit_mb", "int", 128, (64, 128, 256, 512)),
    )
    BY_KEY = {item.key: item for item in DEFINITIONS}
    EXPORT_KEYS = (
        "app.confirm_destructive",
        "appearance.theme",
        "appearance.card_size",
        "appearance.show_thumbnails",
        "library.sort_default",
        "library.grid_density",
        "library.page_size",
        "library.continue_watching",
        "library.continue_watching_limit",
        "player.autoplay_next",
        "player.resume",
        "player.default_speed",
        "player.aspect_ratio",
        "player.zoom_enabled",
        "player.immersive",
        "player.rotation",
        "player.pip",
        "player.lock_mode",
        "player.auto_hide_seconds",
        "player.double_tap_seek_seconds",
        "player.long_press_speed",
        "player.max_video_resolution",
        "player.max_video_frame_rate",
        "player.max_audio_channels",
        "gestures.volume",
        "gestures.brightness",
        "gestures.double_tap",
        "gestures.long_press",
        "audio.preferred_language",
        "audio.preferred_subtitle_language",
        "audio.subtitles",
        "audio.subtitle_scale",
        "audio.subtitle_bottom_padding",
        "audio.subtitle_embedded_style",
        "metadata.anilist_enabled",
        "metadata.auto_match",
        "artwork.enabled",
        "artwork.cache_limit_mb",
    )

class SettingsValidationError(ValueError):
    pass

class SettingsStore:
    SCHEMA_VERSION = 2
    EXPORT_FORMAT = "reiflix-settings"
    EXPORT_KEYS = SettingsDefaults.EXPORT_KEYS


    def __init__(self, store):
        self.store = store
        self._migrate_legacy_keys()
        raw_version = self.store.get_preference("settings.schema_version")
        try:
            stored_version = int(raw_version or 0)
        except (TypeError, ValueError):
            stored_version = 0
        if stored_version < self.SCHEMA_VERSION:
            self.store.set_preference("settings.schema_version", str(self.SCHEMA_VERSION))
        self._cache: dict[str, Any] = {}

    def _migrate_legacy_keys(self):
        legacy = {
            "resume_playback": "player.resume",
            "autoplay_next": "player.autoplay_next",
            "gesture_volume": "gestures.volume",
            "gesture_brightness": "gestures.brightness",
            "gesture_double_tap": "gestures.double_tap",
            "gesture_long_press": "gestures.long_press",
        }
        for old, new in legacy.items():
            value = self.store.get_preference(old)
            if value is not None and self.store.get_preference(new) is None:
                definition = SettingsDefaults.BY_KEY[new]
                try:
                    self._write(new, self._coerce(definition, value))
                except SettingsValidationError:
                    continue

    @staticmethod
    def _coerce(definition: SettingDefinition, value: Any):
        if definition.kind == "bool":
            if isinstance(value, bool):
                return value
            if str(value).strip().casefold() in {"true", "1", "yes", "on"}:
                return True
            if str(value).strip().casefold() in {"false", "0", "no", "off"}:
                return False
            raise SettingsValidationError(f"valor booleano inválido para {definition.key}")
        if definition.kind == "int":
            if isinstance(value, bool):
                raise SettingsValidationError(f"inteiro inválido para {definition.key}")
            if isinstance(value, int):
                number = value
            elif isinstance(value, str) and re.fullmatch(r"\d+", value.strip()):
                number = int(value.strip())
            else:
                raise SettingsValidationError(f"inteiro inválido para {definition.key}")
            if number < 0:
                raise SettingsValidationError(f"inteiro negativo para {definition.key}")
            if definition.choices and number not in definition.choices:
                raise SettingsValidationError(f"valor não permitido para {definition.key}")
            return number
        if definition.kind == "float":
            number = float(value)
            if number <= 0:
                raise SettingsValidationError(f"número inválido para {definition.key}")
            if definition.choices and number not in definition.choices:
                raise SettingsValidationError(f"valor não permitido para {definition.key}")
            return number
        if definition.kind in {"enum", "string", "language"}:
            text = str(value).strip()
            if definition.kind == "enum" and text not in definition.choices:
                raise SettingsValidationError(f"opção não permitida para {definition.key}")
            if definition.kind == "language":
                if len(text) > 32:
                    raise SettingsValidationError(f"idioma inválido para {definition.key}")
                if text and not re.fullmatch(r"[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*", text):
                    raise SettingsValidationError(f"idioma inválido para {definition.key}")
            return text
        raise SettingsValidationError(f"tipo desconhecido: {definition.kind}")

    @staticmethod
    def _encode(value: Any) -> str:
        if isinstance(value, bool):
            return "true" if value else "false"
        return str(value)

    def _write(self, key, value):
        self.store.set_preference(key, self._encode(value))
        self.store.set_preference("settings.schema_version", str(self.SCHEMA_VERSION))
        if hasattr(self, "_cache"):
            self._cache[key] = value

    def invalidate_cache(self, *keys: str) -> None:
        """Drop cached preference values after an out-of-band store mutation."""
        if not keys:
            self._cache.clear()
            return
        for key in keys:
            self._cache.pop(key, None)

    def get(self, key):
        definition = SettingsDefaults.BY_KEY[key]
        if key in self._cache:
            return self._cache[key]
        raw = self.store.get_preference(key)
        if raw is None:
            self._cache[key] = definition.default
            return definition.default
        if key == "player.aspect_ratio" and str(raw).strip().casefold() in {"zoom", "auto", "original"}:
            raw = "fill" if str(raw).strip().casefold() == "zoom" else "fit"
            try:
                self._write(key, raw)
            except Exception:
                logger.exception("Could not normalize legacy aspect setting")
        try:
            normalized = self._coerce(definition, raw)
            self._cache[key] = normalized
            return normalized
        except (ValueError, TypeError, SettingsValidationError):
            # A single corrupt key falls back without resetting unrelated settings.
            self._cache[key] = definition.default
            return definition.default

    def set(self, key, value):
        if key not in SettingsDefaults.BY_KEY:
            raise SettingsValidationError(f"chave desconhecida: {key}")
        definition = SettingsDefaults.BY_KEY[key]
        normalized = self._coerce(definition, value)
        self._write(key, normalized)
        return normalized

    def reset(self, key):
        if key not in SettingsDefaults.BY_KEY:
            raise SettingsValidationError(f"chave desconhecida: {key}")
        value = SettingsDefaults.BY_KEY[key].default
        self._write(key, value)
        return value

    def reset_category(self, prefix: str):
        changed = 0
        for key in SettingsDefaults.BY_KEY:
            if key.startswith(prefix + "."):
                self.reset(key)
                changed += 1
        return changed

    def reset_all(self):
        for key in SettingsDefaults.BY_KEY:
            self.reset(key)


    def export_payload(self):
        """Return only the supported SettingsStore state in a versioned structure."""
        return {
            "format": self.EXPORT_FORMAT,
            "schema_version": self.SCHEMA_VERSION,
            "settings": {key: self.get(key) for key in SettingsDefaults.EXPORT_KEYS},
        }

    def export_json(self) -> str:
        return json.dumps(self.export_payload(), ensure_ascii=False, indent=2, sort_keys=True) + "\n"

    def _validate_import_payload(self, payload):
        if not isinstance(payload, dict):
            raise SettingsValidationError("arquivo de configurações inválido")
        if payload.get("format") != self.EXPORT_FORMAT:
            raise SettingsValidationError("formato de configurações incompatível")
        schema_version = payload.get("schema_version")
        if schema_version not in {1, self.SCHEMA_VERSION}:
            raise SettingsValidationError("versão de configurações incompatível")
        settings = payload.get("settings")
        if not isinstance(settings, dict):
            raise SettingsValidationError("campo settings inválido")
        normalized = {}
        unknown = []
        for key, value in settings.items():
            if key not in SettingsDefaults.EXPORT_KEYS:
                unknown.append(str(key))
                continue
            normalized[key] = self._coerce(SettingsDefaults.BY_KEY[key], value)
        return normalized, unknown

    def import_payload(self, payload):
        """Validate the complete import before changing any stored preference."""
        normalized, unknown = self._validate_import_payload(payload)
        if not normalized:
            return {"imported": 0, "unknown": unknown}
        now = time.time()
        with self.store._conn() as con:
            for key, value in normalized.items():
                con.execute(
                    """INSERT INTO preferences(key,value,updated_at) VALUES (?,?,?)
                       ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at""",
                    (key, self._encode(value), now),
                )
            con.execute(
                """INSERT INTO preferences(key,value,updated_at) VALUES (?,?,?)
                   ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at""",
                ("settings.schema_version", str(self.SCHEMA_VERSION), now),
            )
        self._cache.update(normalized)
        return {"imported": len(normalized), "unknown": unknown}

    def import_json(self, raw: str):
        try:
            payload = json.loads(raw)
        except (TypeError, json.JSONDecodeError) as exc:
            raise SettingsValidationError("JSON de configurações inválido") from exc
        return self.import_payload(payload)

    def snapshot(self):
        return {key: self.get(key) for key in SettingsDefaults.BY_KEY}
