import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local"
SETTINGS_VIEW = ROOT / "views/settings_view.py"
SETTINGS = ROOT / "core/settings.py"
BRIDGE = ROOT / "core/android_bridge.py"
ACTIVITY = KOTLIN / "MainActivity.kt"
PLAYER = KOTLIN / "NativePlayerActivity.kt"
PLAYER_REQUEST = KOTLIN / "player/NativePlayerRequest.kt"
SETTINGS_CONTRACT = KOTLIN / "data/settings/ReiAnixSettingsContract.kt"
SETTINGS_REPO = KOTLIN / "data/settings/ReiAnixSettingsRepository.kt"
SETTINGS_VM = KOTLIN / "viewmodel/ReiAnixSettingsViewModel.kt"
SQLITE_SETTINGS = KOTLIN / "data/sqlite/ReiAnixSqliteSettingsRepository.kt"
HOST = KOTLIN / "ui/host/ReiAnixComposeLibraryHost.kt"


def read(path):
    return path.read_text(encoding="utf-8")


class SettingsContractTests(unittest.TestCase):
    def test_native_settings_commands_are_correlated_and_lifecycle_scoped(self):
        repo = read(SETTINGS_REPO)
        vm = read(SETTINGS_VM)
        activity = read(ACTIVITY)
        self.assertIn("fun setSetting(key: String, value: String)", repo)
        self.assertIn("scope.launch", repo)
        self.assertIn("settingLocks.computeIfAbsent", repo)
        self.assertIn("Mutex()", repo)
        self.assertIn("withLock", repo)
        self.assertIn("setPreference(normalizedKey, normalizedValue)", repo)
        self.assertIn("requestId", repo)
        self.assertIn("commandResultObserver", repo)
        self.assertIn("fun setSetting(key: String, value: String)", vm)
        self.assertIn("fun requestAction(action: String)", vm)
        self.assertIn("completeComposeSettingsStorageAction", activity)

    def test_native_settings_use_canonical_sqlite_and_post_commit_notification(self):
        repo = read(SETTINGS_REPO)
        sqlite = read(SQLITE_SETTINGS)
        self.assertIn("ReiAnixSqliteSettingsRepository(appContext)", repo)
        self.assertIn("setPreference(normalizedKey, normalizedValue)", repo)
        self.assertIn("setPreferencesAtomically(defaults)", repo)
        self.assertIn('"compose_settings_set"', repo)
        self.assertIn('"nativePersisted"', repo)
        self.assertIn("SELECT key, value FROM preferences ORDER BY key COLLATE NOCASE ASC", sqlite)
        self.assertIn("database.transaction", sqlite)

    def test_native_setting_whitelist_and_normalization_are_centralized(self):
        settings = read(SETTINGS)
        contract = read(SETTINGS_CONTRACT)
        for key in (
            "app.confirm_destructive", "appearance.theme", "appearance.card_size",
            "appearance.show_thumbnails", "player.resume", "player.default_speed",
            "audio.preferred_language", "library.page_size",
        ):
            self.assertIn(f'"{key}"', settings + contract)
        self.assertIn("definitions: Map<String, ReiAnixSettingDefinition>", contract)
        self.assertIn("fun normalize(key: String, value: String): String", contract)
        self.assertIn("fun defaultFor(key: String): String?", contract)

    def test_settings_export_import_use_native_document_contract_and_sqlite_transaction(self):
        repository = read(SETTINGS_REPO)
        service = read(KOTLIN / "data/settings/ReiAnixSettingsFileService.kt")
        activity = read(ACTIVITY)
        self.assertIn('const val EXPORT_FORMAT = "reiflix-settings"', service)
        self.assertIn("SUPPORTED_SCHEMA_VERSIONS", service)
        self.assertIn("setPreferencesAtomically(valuesToCommit)", service)
        self.assertIn('CreateDocument("application/json")', activity)
        self.assertIn("ActivityResultContracts.OpenDocument()", activity)
        self.assertIn("writeSettingsFileActionResult", activity)
        self.assertIn("launchNativeSettingsFileAction", repository)

    def test_player_preferences_reach_media3_through_typed_native_handoff(self):
        settings = read(SETTINGS)
        request = read(PLAYER_REQUEST)
        player = read(PLAYER)
        for key in (
            "player.zoom_enabled", "player.double_tap_seek_seconds", "player.long_press_speed",
            "player.max_video_resolution", "player.max_video_frame_rate", "player.max_audio_channels",
            "audio.subtitle_scale", "audio.subtitle_bottom_padding", "audio.subtitle_embedded_style",
        ):
            self.assertIn(f'"{key}"', settings)
        for extra in (
            "setting_audio_preferred_language", "setting_audio_preferred_subtitle_language",
            "setting_audio_subtitles", "setting_player_max_video_resolution",
            "setting_player_max_video_frame_rate", "setting_player_max_audio_channels",
            "setting_audio_subtitle_scale", "setting_audio_subtitle_bottom_padding",
            "setting_audio_subtitle_embedded_style",
        ):
            self.assertIn(extra, request)
            self.assertIn(extra, player)
        self.assertIn("applyGlobalTrackPreferences()", player)
        self.assertIn("applyAdvancedTrackConstraints()", player)
        self.assertIn("applySubtitlePreferences()", player)

    def test_native_player_gesture_and_media3_contracts_remain_present(self):
        player = read(PLAYER)
        self.assertIn("GestureMode.HORIZONTAL_SEEK", player)
        self.assertIn("PlayerGesturePolicy.horizontalSeekTarget", player)
        self.assertIn("ExoPlayer.Builder(this).build()", player)
        self.assertIn("MediaItem.Builder()", player)
        self.assertIn("restoreSystemUiBeforeExit", player)
        self.assertIn("canEnterPictureInPicture", player)

    def test_continue_watching_and_theme_are_projected_from_native_settings_state(self):
        home = read(KOTLIN / "ui/home/ReiAnixHome.kt")
        vm = read(SETTINGS_VM)
        host = read(HOST)
        self.assertIn("continueWatchingEnabled", vm)
        self.assertIn("continueWatchingLimit", vm)
        self.assertIn("themeMode", vm)
        self.assertIn("settingsViewModel.themeMode.collectAsStateWithLifecycle()", host)
        self.assertIn("continueWatchingLimit", home)
        self.assertIn("continueWatching", home)

    def test_advanced_categories_have_native_actions_and_persisted_preferences(self):
        settings = read(SETTINGS)
        compose = read(KOTLIN / "ui/settings/ReiAnixSettings.kt")
        repository = read(SETTINGS_REPO)
        activity = read(ACTIVITY)
        for category in ("Player", "Gestos", "Áudio e Legendas", "Backup e Restauração", "Diagnóstico"):
            self.assertIn(category, compose)
        for action in ("clear_anilist_cache", "settings_export", "settings_import", "select_saf",
                       "request_media_access", "check_storage_access", "backup_create",
                       "backup_restore", "backup_integrity", "backup_reconcile", "diagnostic_export"):
            self.assertIn(action, repository + activity)
        self.assertNotIn('"privacy.external_sync"', settings)
        self.assertNotIn('"gestures.horizontal_swipe_seek"', settings + compose)


if __name__ == "__main__":
    unittest.main()
