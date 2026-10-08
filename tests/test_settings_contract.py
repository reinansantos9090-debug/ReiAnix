import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SETTINGS_VIEW = ROOT / "views/settings_view.py"
SETTINGS = ROOT / "core/settings.py"
MAIN = ROOT / "main.py"
BRIDGE = ROOT / "core/android_bridge.py"
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
PLAYER = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
PLAYER_REQUEST = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/NativePlayerRequest.kt"
HOME = ROOT / "views/home_view.py"


class SettingsContractTests(unittest.TestCase):
    def read(self, path):
        return path.read_text(encoding="utf-8")

    def test_compose_settings_commands_are_async_and_correlated(self):
        main = self.read(MAIN)
        bridge = self.read(ROOT / "core/compose_settings_bridge.py")
        repository = self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt")
        self.assertIn("async def _run_compose_settings_set", main)
        self.assertIn("async def _run_compose_settings_action", main)
        self.assertIn("_track_compose_settings_task", main)
        self.assertIn("COMPOSE_SETTINGS_MAX_TASKS = 8", main)
        self.assertIn("asyncio.create_task(_run_compose_settings_set", main)
        self.assertIn("asyncio.create_task(_run_compose_settings_action", main)
        self.assertIn("claim_native_request(request_id, namespace=\"compose_settings\")", main)
        self.assertIn('write_command_result(', main)
        self.assertIn("COMMAND_RESULT_DIR_NAME", bridge)
        self.assertIn("SETTINGS_SNAPSHOT_PUBLISHED", bridge)
        self.assertIn("commandResultObserver", repository)
        self.assertIn("SETTINGS_SNAPSHOT_CONSUMED", repository)

    def test_mailbox_dispatcher_does_not_await_settings_workers_in_place(self):
        main = self.read(MAIN)
        start = main.index("if event_type == 'compose_settings_set':")
        end = main.index("if event_type == 'compose_navigation_changed':", start)
        block = main[start:end]
        self.assertNotIn("await asyncio.to_thread(settings.set", block)
        self.assertNotIn("await add_folder()", block)
        self.assertNotIn("await create_backup()", block)
        self.assertNotIn("await restore_backup(", block)
        self.assertIn("asyncio.create_task(_run_compose_settings_set", block)
        self.assertIn("asyncio.create_task(_run_compose_settings_action", block)

    def test_settings_export_import_contract_exists(self):
        settings = self.read(SETTINGS)
        view = self.read(SETTINGS_VIEW)
        self.assertIn('EXPORT_FORMAT = "reiflix-settings"', settings)
        self.assertIn("def export_json", settings)
        self.assertIn("def import_json", settings)
        self.assertIn("schema_version", settings)
        self.assertIn("Exportar configurações", view)
        self.assertIn("Importar configurações", view)
        self.assertIn("with_data=True", view)
        self.assertIn("src_bytes=raw", view)

    def test_audio_subtitle_preferences_reach_media3(self):
        settings = self.read(SETTINGS)
        main = self.read(MAIN)
        bridge = self.read(BRIDGE)
        main_activity = self.read(MAIN_ACTIVITY)
        player_request = self.read(PLAYER_REQUEST)
        player = self.read(PLAYER)
        self.assertIn('"audio.preferred_language"', settings)
        self.assertIn('"audio.preferred_subtitle_language"', settings)
        self.assertIn('"audio.subtitles"', settings)
        self.assertIn('"audio.preferred_language": settings.get("audio.preferred_language")', main)
        self.assertIn('"audio.preferred_subtitle_language": settings.get("audio.preferred_subtitle_language")', main)
        self.assertIn('"audio.subtitles": settings.get("audio.subtitles")', main)
        self.assertIn("player_settings", bridge)
        self.assertIn('setting_audio_preferred_language', player_request)
        self.assertIn('setting_audio_preferred_subtitle_language', player_request)
        self.assertIn('setting_audio_subtitles', player_request)
        self.assertIn('setting_player_max_video_resolution', player_request)
        self.assertIn('setting_player_max_video_frame_rate', player_request)
        self.assertIn('setting_player_max_audio_channels', player_request)
        self.assertIn('setting_audio_subtitle_scale', player_request)
        self.assertIn('setting_audio_subtitle_bottom_padding', player_request)
        self.assertIn('setting_audio_subtitle_embedded_style', player_request)
        self.assertIn("applyGlobalTrackPreferences()", player)
        self.assertIn("applyAdvancedTrackConstraints()", player)
        self.assertIn("applySubtitlePreferences()", player)
        self.assertIn("setPreferredAudioLanguage", player)
        self.assertIn("setPreferredTextLanguage", player)
        self.assertIn("setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)", player)

    def test_player_contracts_stay_intact(self):
        player = self.read(PLAYER)
        self.assertIn("GESTURE_HORIZONTAL_IGNORED", player)
        self.assertNotIn("horizontalSeekDelta", player)
        self.assertNotIn("setting_gestures_horizontal_swipe_seek", player)
        self.assertIn("ExoPlayer.Builder(this).build()", player)
        self.assertIn("MediaItem.Builder()", player)
        self.assertIn("restoreSystemUiBeforeExit", player)
        self.assertIn("canEnterPictureInPicture", player)
        self.assertIn("pipEnabled", player)

    def test_continue_watching_and_theme_contracts(self):
        home = self.read(HOME)
        main = self.read(MAIN)
        self.assertIn('settings.get("library.continue_watching")', home)
        self.assertIn('settings.get("library.continue_watching_limit")', home)
        self.assertIn('settings.get("appearance.theme")', main)


    def test_advanced_settings_are_real_and_exported(self):
        settings = self.read(SETTINGS)
        view = self.read(SETTINGS_VIEW)
        main = self.read(MAIN)
        main_activity = self.read(MAIN_ACTIVITY)
        player_request = self.read(PLAYER_REQUEST)
        home = self.read(HOME)
        for key in (
            "player.zoom_enabled",
            "player.double_tap_seek_seconds",
            "player.long_press_speed",
            "player.max_video_resolution",
            "player.max_video_frame_rate",
            "player.max_audio_channels",
            "audio.subtitle_scale",
            "audio.subtitle_bottom_padding",
            "audio.subtitle_embedded_style",
            "metadata.anilist_enabled",
            "metadata.auto_match",
            "artwork.enabled",
            "artwork.cache_limit_mb",
            "library.page_size",
        ):
            self.assertIn(f'"{key}"', settings)
            self.assertIn(key, view)
        self.assertIn('"player.zoom_enabled": settings.get("player.zoom_enabled")', main)
        self.assertIn('"player.double_tap_seek_seconds": settings.get("player.double_tap_seek_seconds")', main)
        self.assertIn('"player.long_press_speed": settings.get("player.long_press_speed")', main)
        self.assertIn('"player.max_video_resolution": settings.get("player.max_video_resolution")', main)
        self.assertIn("setting_player_double_tap_seek_seconds", player_request)
        self.assertIn("setting_player_long_press_speed", player_request)
        self.assertIn("setting_player_max_video_resolution", player_request)
        self.assertIn("NativePlayerRequest.fromBridgeUri", main_activity)
        self.assertIn("playerRequest.toIntent(this, localUri)", main_activity)
        self.assertIn("settings.get(\"library.page_size\")", home)
        self.assertNotIn('"privacy.external_sync"', settings)
        self.assertNotIn("gestures.horizontal_swipe_seek", settings + view + main + player_request)
        self.assertNotIn('"artwork.offline_cache"', settings)
        self.assertNotIn('"metadata.keep_local"', settings)

    def test_settings_search_indexes_descriptions(self):
        view = self.read(SETTINGS_VIEW)
        self.assertIn('container.data = f"{key} {label} {description}".casefold()', view)
        self.assertIn('item_terms = " ".join(str(getattr(item, "data", "")) for item in items)', view)


if __name__ == "__main__":
    unittest.main()
