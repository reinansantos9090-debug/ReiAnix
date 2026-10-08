from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def test_android_interaction_profile_uses_real_input_sources():
    source = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/player/DeviceInteractionProfile.kt")
    for token in (
        "Configuration.UI_MODE_TYPE_TELEVISION",
        "PackageManager.FEATURE_LEANBACK",
        "InputDevice.SOURCE_DPAD",
        "InputDevice.SOURCE_GAMEPAD",
        "InputDevice.SOURCE_KEYBOARD",
        "InputDevice.SOURCE_MOUSE",
        "InputDevice.SOURCE_TOUCHSCREEN",
    ):
        assert token in source
    assert "DisplayMetrics" not in source


def test_manifest_keeps_tv_support_optional_for_phones():
    manifest = read("android/app/src/main/AndroidManifest.xml")
    assert 'android.software.leanback" android:required="false"' in manifest
    assert 'android.hardware.touchscreen" android:required="false"' in manifest
    assert "android.intent.category.LEANBACK_LAUNCHER" in manifest


def test_home_cards_use_framework_focus_and_existing_pagination():
    home = read("views/home_view.py")
    assert "ft.OutlinedButton(" in home
    assert "on_focus =" in home
    assert "load_library_page(reset=False)" in home
    assert "catalog_focus_targets" in home
    assert "tv_home" not in home


def test_settings_and_details_have_focusable_actions():
    settings = read("views/settings_view.py")
    details = read("views/details_view.py")
    assert "ft.OutlinedButton(" in settings and "focus_button_style" in settings
    assert "ft.OutlinedButton(" in details and "focus_button_style" in details


def test_player_reuses_single_activity_and_handles_remote_center():
    player = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt")
    assert "override fun dispatchKeyEvent" in player
    for token in ("KEYCODE_DPAD_CENTER", "KEYCODE_DPAD_LEFT", "KEYCODE_DPAD_RIGHT"):
        assert token in player
    assert "cinemaMode" in player
    assert "TVPlayerActivity" not in player


def test_no_duplicate_tv_modules():
    paths = [p.as_posix().lower() for p in ROOT.rglob("*") if p.is_file()]
    assert not any("tv_home" in p for p in paths)
    assert not any("tvplayeractivity" in p for p in paths)
    assert not any("tvplaybackactivity" in p for p in paths)
