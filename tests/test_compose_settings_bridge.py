from __future__ import annotations

import json
from pathlib import Path

from core.compose_settings_bridge import ComposeSettingsBridge


class _FakeSettings:
    def __init__(self, values):
        self._values = values

    def snapshot(self):
        return dict(self._values)


def test_compose_settings_bridge_projects_only_persisted_settings(tmp_path):
    values = {
        "app.confirm_destructive": True,
        "appearance.theme": "dark",
        "library.page_size": 36,
        "player.default_speed": 1.0,
        "gestures.volume": False,
        "audio.subtitles": "auto",
        "metadata.anilist_enabled": True,
        "artwork.cache_limit_mb": 128,
        "unknown.value": "sentinel",
    }
    bridge = ComposeSettingsBridge(
        str(tmp_path),
        _FakeSettings(values),
        account_provider=lambda: {
            "id": "local-account-id",
            "name": "Test User",
            "email": "test@example.invalid",
        },
        account_state_provider=lambda: "connected",
        storage_available=True,
    )

    bridge._build_and_write_snapshot(7, "test")
    payload = json.loads(
        (tmp_path / "reianix-compose" / "settings.json").read_text(encoding="utf-8")
    )

    assert payload["schemaVersion"] == 1
    assert payload["revision"] == 7
    assert payload["settings"] == values
    assert payload["account"] == {
        "integrationAvailable": True,
        "connected": True,
        "name": "Test User",
        "email": "test@example.invalid",
        "state": "connected",
    }
    assert payload["categories"]["Geral"] is True
    assert payload["categories"]["Aparência"] is True
    assert payload["categories"]["Player"] is True
    assert payload["categories"]["Armazenamento"] is True
    assert "Segurança" not in payload["categories"]


def test_compose_settings_bridge_does_not_invent_missing_preference_category(tmp_path):
    bridge = ComposeSettingsBridge(
        str(tmp_path),
        _FakeSettings({"player.autoplay_next": True}),
        storage_available=False,
    )

    bridge._build_and_write_snapshot(1, "test")
    payload = json.loads(
        (tmp_path / "reianix-compose" / "settings.json").read_text(encoding="utf-8")
    )

    assert payload["categories"]["Player"] is True
    assert payload["categories"]["Armazenamento"] is False
    assert payload["categories"]["Geral"] is False
    assert "Segurança" not in payload["categories"]


def test_native_settings_integration_contract():
    root = Path(__file__).resolve().parents[1]
    main = (root / "main.py").read_text(encoding="utf-8")
    request_state = (
        root
        / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeRequestState.kt"
    ).read_text(encoding="utf-8")
    activity = (
        root
        / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
    ).read_text(encoding="utf-8")
    settings_screen = (
        root
        / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt"
    ).read_text(encoding="utf-8")
    assert "ComposeSettingsBridge" in main
    assert "compose_settings_navigation" in main
    assert "_show_compose_settings" in main
    assert "open_settings" in request_state
    assert "hide_settings" in request_state
    assert "composeSettingsHost" not in activity
    assert "composeLibraryHost" in activity
    assert "ReiAnixRoutes.SETTINGS" in activity
    assert "collectAsStateWithLifecycle" in settings_screen
    assert 'ReiAnixSettingsCategoryUiModel("Player"' in settings_screen
    assert 'ReiAnixSettingsCategoryUiModel("Armazenamento"' in settings_screen
    assert 'ReiAnixSettingsCategoryUiModel("Sobre"' in settings_screen
    assert "Segurança" not in settings_screen
