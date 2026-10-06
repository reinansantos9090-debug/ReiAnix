from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SETTINGS = ROOT / "core/settings.py"
MAIN = ROOT / "main.py"
COMPOSE = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt"
REQUEST = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/NativePlayerRequest.kt"
PLAYER = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
REPOSITORY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt"


PLAYER_KEYS = (
    "player.autoplay_next",
    "player.resume",
    "player.default_speed",
    "player.aspect_ratio",
    "player.zoom_enabled",
    "player.immersive",
    "player.rotation",
    "player.pip",
    "player.auto_hide_seconds",
    "player.double_tap_seek_seconds",
    "player.long_press_speed",
    "player.max_video_resolution",
    "player.max_video_frame_rate",
    "player.max_audio_channels",
)

GESTURE_KEYS = (
    "gestures.volume",
    "gestures.brightness",
    "gestures.double_tap",
    "gestures.long_press",
)

AUDIO_KEYS = (
    "audio.subtitle_scale",
    "audio.subtitle_bottom_padding",
    "audio.subtitle_embedded_style",
    "audio.preferred_language",
    "audio.preferred_subtitle_language",
    "audio.subtitles",
)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_compose_exposes_only_existing_player_preferences():
    settings = read(SETTINGS)
    compose = read(COMPOSE)
    for key in PLAYER_KEYS + GESTURE_KEYS + AUDIO_KEYS:
        assert f'"{key}"' in settings
        assert key in compose

    assert "NativeManagedSettingsCategories =" in compose
    assert "ReiAnixSettingsCategoryUiModel.defaultCategories()" in compose
    assert '.filterNot { it == "Armazenamento" }' in compose
    for category in ("Player", "Gestos", "Áudio e Legendas"):
        assert f'"{category}"' in compose


def test_compose_writes_to_existing_python_settings_store():
    main = read(MAIN)
    repository = read(REPOSITORY)

    assert "compose_settings_set" in main
    assert "settings.EXPORT_KEYS" in main
    scope_start = main.index("supported_compose_settings", main.index("compose_settings_set"))
    scope_end = main.index("if setting_key not in supported_compose_settings:", scope_start)
    supported_scope = main[scope_start:scope_end]
    for prefix in (
        '"library."',
        '"player."',
        '"gestures."',
        '"audio."',
        '"metadata."',
        '"artwork."',
    ):
        assert prefix in supported_scope
    assert "key.startswith(" in supported_scope
    assert "await asyncio.to_thread(settings.set, setting_key, setting_value)" in main
    assert "Compose does not keep a second preference store" in repository
    assert "NativeMailbox.write(appContext, event)" in repository


def test_player_handoff_contains_every_migrated_preference():
    main = read(MAIN)
    request = read(REQUEST)
    player = read(PLAYER)

    for key in PLAYER_KEYS[2:] + GESTURE_KEYS + AUDIO_KEYS:
        assert f'"{key}": settings.get("{key}")' in main

    assert 'autoplay=settings.get("player.autoplay_next")' in main
    assert 'settings.get("player.resume")' in main

    request_extras = (
        "setting_player_default_speed",
        "setting_player_aspect_ratio",
        "setting_player_zoom_enabled",
        "setting_player_immersive",
        "setting_player_rotation",
        "setting_player_pip",
        "setting_player_auto_hide_seconds",
        "setting_player_double_tap_seek_seconds",
        "setting_player_long_press_speed",
        "setting_player_max_video_resolution",
        "setting_player_max_video_frame_rate",
        "setting_player_max_audio_channels",
        "setting_gestures_volume",
        "setting_gestures_brightness",
        "setting_gestures_double_tap",
        "setting_gestures_long_press",
        "setting_audio_preferred_language",
        "setting_audio_preferred_subtitle_language",
        "setting_audio_subtitles",
        "setting_audio_subtitle_scale",
        "setting_audio_subtitle_bottom_padding",
        "setting_audio_subtitle_embedded_style",
    )
    for extra in request_extras:
        assert extra in request
        assert extra in player


def test_does_not_create_false_subtitle_delay_control():
    compose = read(COMPOSE)
    assert "audio.subtitle_delay" not in compose
    assert "Delay global de legenda" not in compose
