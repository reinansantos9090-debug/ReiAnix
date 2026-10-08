import tempfile
import unittest

from core.library_store import LibraryStore
from core.settings import SettingsStore, SettingsValidationError


class SettingsStoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = LibraryStore(self.tmp.name)
        self.settings = SettingsStore(self.store)

    def tearDown(self):
        self.tmp.cleanup()

    def test_defaults_and_persistence(self):
        self.assertEqual(self.settings.get("player.default_speed"), 1.0)
        self.assertTrue(self.settings.get("player.resume"))
        self.assertFalse(self.settings.get("player.zoom_enabled"))
        self.settings.set("player.default_speed", 1.5)
        self.assertEqual(SettingsStore(self.store).get("player.default_speed"), 1.5)

    def test_boolean_and_enum_types(self):
        self.settings.set("gestures.volume", True)
        self.assertIs(self.settings.get("gestures.volume"), True)
        self.settings.set("player.aspect_ratio", "fill")
        self.assertEqual(self.settings.get("player.aspect_ratio"), "fill")
        with self.assertRaises(SettingsValidationError):
            self.settings.set("player.max_video_resolution", "144p")
        with self.assertRaises(SettingsValidationError):
            self.settings.set("player.max_video_frame_rate", 75)
        with self.assertRaises(SettingsValidationError):
            self.settings.set("audio.subtitle_bottom_padding", 60)

    def test_invalid_values_rejected(self):
        with self.assertRaises(SettingsValidationError):
            self.settings.set("player.default_speed", 9.0)
        with self.assertRaises(SettingsValidationError):
            self.settings.set("player.auto_hide_seconds", -1)
        with self.assertRaises(SettingsValidationError):
            self.settings.set("player.aspect_ratio", "stretch")

    def test_reset_category_preserves_library(self):
        self.settings.set("player.default_speed", 2.0)
        self.settings.set("appearance.theme", "light")
        self.settings.reset_category("player")
        self.assertEqual(self.settings.get("player.default_speed"), 1.0)
        self.assertEqual(self.settings.get("appearance.theme"), "light")

    def test_advanced_defaults_persist_and_reset(self):
        self.assertEqual(self.settings.get("player.double_tap_seek_seconds"), 10)
        self.assertEqual(self.settings.get("player.long_press_speed"), 2.0)
        self.assertEqual(self.settings.get("player.max_video_resolution"), "auto")
        self.assertEqual(self.settings.get("player.max_video_frame_rate"), 0)
        self.assertEqual(self.settings.get("player.aspect_ratio"), "fit")
        self.assertFalse(self.settings.get("player.zoom_enabled"))
        self.assertEqual(self.settings.get("player.max_audio_channels"), 0)
        self.assertEqual(self.settings.get("audio.subtitle_scale"), 1.0)
        self.assertEqual(self.settings.get("audio.subtitle_bottom_padding"), 8)
        self.assertTrue(self.settings.get("audio.subtitle_embedded_style"))
        self.settings.set("player.double_tap_seek_seconds", 30)
        self.settings.set("player.zoom_enabled", True)
        self.settings.set("player.max_video_resolution", "1080p")
        self.settings.set("audio.subtitle_scale", 1.5)
        self.settings.set("artwork.cache_limit_mb", 256)
        reloaded = SettingsStore(self.store)
        self.assertEqual(reloaded.get("player.double_tap_seek_seconds"), 30)
        self.assertTrue(reloaded.get("player.zoom_enabled"))
        self.assertEqual(reloaded.get("player.max_video_resolution"), "1080p")
        self.assertEqual(reloaded.get("audio.subtitle_scale"), 1.5)
        self.assertEqual(reloaded.get("artwork.cache_limit_mb"), 256)
        self.settings.reset_category("player")
        self.assertEqual(self.settings.get("player.double_tap_seek_seconds"), 10)
        self.assertEqual(self.settings.get("player.max_video_resolution"), "auto")


    def test_legacy_aspect_modes_are_migrated_without_fake_choices(self):
        self.store.set_preference("player.aspect_ratio", "zoom")
        migrated = SettingsStore(self.store)
        self.assertEqual(migrated.get("player.aspect_ratio"), "fill")
        self.assertEqual(self.store.get_preference("player.aspect_ratio"), "fill")

    def test_legacy_preferences_are_migrated(self):
        self.store.set_preference("resume_playback", "false")
        migrated = SettingsStore(self.store)
        self.assertFalse(migrated.get("player.resume"))

    def test_single_corrupt_key_falls_back_only_for_that_key(self):
        self.store.set_preference("player.default_speed", "not-a-speed")
        self.store.set_preference("player.resume", "false")
        settings = SettingsStore(self.store)
        self.assertEqual(settings.get("player.default_speed"), 1.0)
        self.assertFalse(settings.get("player.resume"))

    def test_continue_watching_limits(self):
        for valid in (5, 10, 15, 20):
            self.settings.set("library.continue_watching_limit", valid)
            self.assertEqual(self.settings.get("library.continue_watching_limit"), valid)
        for invalid in (0, -1, 1, 25, 5.5):
            with self.assertRaises(SettingsValidationError):
                self.settings.set("library.continue_watching_limit", invalid)

    def test_language_and_subtitle_settings(self):
        self.settings.set("audio.preferred_language", "pt-BR")
        self.settings.set("audio.preferred_subtitle_language", "ja")
        self.settings.set("audio.subtitles", "always")
        self.assertEqual(self.settings.get("audio.preferred_language"), "pt-BR")
        self.assertEqual(self.settings.get("audio.preferred_subtitle_language"), "ja")
        self.assertEqual(self.settings.get("audio.subtitles"), "always")
        with self.assertRaises(SettingsValidationError):
            self.settings.set("audio.preferred_language", "not a language")

    def test_export_import_round_trip(self):
        self.settings.set("player.default_speed", 1.5)
        self.settings.set("appearance.theme", "light")
        self.settings.set("audio.preferred_language", "pt-BR")
        exported = self.settings.export_json()
        other_tmp = tempfile.TemporaryDirectory()
        try:
            other = SettingsStore(LibraryStore(other_tmp.name))
            result = other.import_json(exported)
            self.assertEqual(result["imported"], len(SettingsStore.EXPORT_KEYS))
            self.assertEqual(other.get("player.default_speed"), 1.5)
            self.assertEqual(other.get("appearance.theme"), "light")
            self.assertEqual(other.get("audio.preferred_language"), "pt-BR")
        finally:
            other_tmp.cleanup()


    def test_import_rejects_bad_version_and_invalid_values_atomically(self):
        self.settings.set("player.default_speed", 1.5)
        with self.assertRaises(SettingsValidationError):
            self.settings.import_json(
                '{"format":"reiflix-settings","schema_version":999,"settings":{}}'
            )
        self.assertEqual(self.settings.get("player.default_speed"), 1.5)
        with self.assertRaises(SettingsValidationError):
            self.settings.import_payload({
                "format": "reiflix-settings",
                "schema_version": 1,
                "settings": {"player.default_speed": 8.0, "player.resume": False},
            })
        self.assertEqual(self.settings.get("player.default_speed"), 1.5)
        self.assertTrue(self.settings.get("player.resume"))

    def test_import_ignores_unknown_supported_safe(self):
        result = self.settings.import_payload({
            "format": "reiflix-settings",
            "schema_version": 1,
            "settings": {"player.resume": False, "future.option": "ignored"},
        })
        self.assertEqual(result["imported"], 1)
        self.assertEqual(result["unknown"], ["future.option"])
        self.assertFalse(self.settings.get("player.resume"))


if __name__ == "__main__":
    unittest.main()
