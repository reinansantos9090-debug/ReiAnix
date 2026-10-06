from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from core.anilist import AniListClient
from core.library_store import LibraryStore


ROOT = Path(__file__).resolve().parents[1]
DETAILS = (ROOT / "views" / "details_view.py").read_text(encoding="utf-8")
MAIN = (ROOT / "main.py").read_text(encoding="utf-8")
PLAYER = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8")
SYSTEM_UI = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/SystemUiController.kt").read_text(encoding="utf-8")


class RegressionTests(unittest.TestCase):
    def test_details_primary_action_does_not_autofocus_or_rebuild_for_thumbnails(self):
        self.assertNotIn("autofocus=bool(primary_target)", DETAILS)
        self.assertIn("palette_changed = (", DETAILS)
        self.assertIn("home_state.get('_update_thumbnail')", MAIN)
        self.assertIn('if navigation.current == "details":', MAIN)
        self.assertIn("if not refresh_details:", MAIN)

    def test_next_accepts_file_uri_for_absolute_path_episode(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("demo", {"title": "Demo", "genres": "[]"})
            first = "/storage/emulated/0/Anime/Demo S01E01.mkv"
            second = "/storage/emulated/0/Anime/Demo S01E02.mkv"
            store.upsert_episode(anime, first, "Demo S01E01.mkv", 1, 1)
            store.upsert_episode(anime, second, "Demo S01E02.mkv", 1, 2)
            result = store.next_episode("file:///storage/emulated/0/Anime/Demo%20S01E01.mkv")
            self.assertIsNotNone(result)
            self.assertEqual(second, result["path"])

    def test_player_next_has_concurrency_guard_and_native_timeout(self):
        self.assertIn("player_transition_inflight", MAIN)
        self.assertIn("transition_in_progress", MAIN)
        self.assertIn("episodeChangeTimeout", PLAYER)
        self.assertIn("EPISODE_CHANGE_WATCHDOG", PLAYER)
        self.assertIn("handler.postDelayed(episodeChangeTimeout, 5_000L)", PLAYER)

    def test_details_focus_is_localized_to_primary_button_only(self):
        primary_start = DETAILS.find("primary_button =")
        primary_end = DETAILS.find("primary_button.style =", primary_start)
        primary_block = DETAILS[primary_start:primary_end if primary_end >= 0 else len(DETAILS)]
        self.assertNotIn("autofocus", primary_block)
        self.assertIn("on_click=lambda _: play(primary_target)", primary_block)

    def test_thumbnail_refresh_does_not_pop_details_cache(self):
        thumb_block_start = MAIN.find("elif event_type == 'thumbnail_ready':")
        thumb_block_end = MAIN.find("elif event_type == 'thumbnail_error':", thumb_block_start)
        thumb_block = MAIN[thumb_block_start:thumb_block_end]
        self.assertIn("home_update(uri, thumbnail_path, media_identity)", thumb_block)
        self.assertNotIn("screen_cache.pop('details'", thumb_block)

    def test_next_without_next_episode_is_supported(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("solo", {"title": "Solo", "genres": "[]"})
            path = "/storage/emulated/0/Anime/Solo S01E01.mkv"
            store.upsert_episode(anime, path, "Solo S01E01.mkv", 1, 1)
            self.assertIsNone(store.next_episode(path))

    def test_next_repeat_is_serialized(self):
        self.assertIn('if player_transition_inflight["value"]:', MAIN)
        self.assertIn('player_transition_inflight["value"] = True', MAIN)
        transition_start = MAIN.index("elif event_type in {'player_next_request', 'player_previous_request'}:")
        transition_end = MAIN.index("elif event_type == 'player_error':", transition_start)
        transition_block = MAIN[transition_start:transition_end]
        self.assertIn("finally:", transition_block)
        self.assertIn('player_transition_inflight["value"] = False', transition_block)

    def test_system_ui_policy_is_reapplied_and_player_setting_is_respected(self):
        self.assertIn("show(WindowInsetsCompat.Type.systemBars())", SYSTEM_UI)
        self.assertIn("hide(WindowInsetsCompat.Type.systemBars())", SYSTEM_UI)
        self.assertIn("applyApplicationSystemUi()", (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8"))
        self.assertIn("resolveImmersivePolicy", PLAYER)
        self.assertIn('intent.getStringExtra("setting_player_immersive")', PLAYER)
        self.assertIn("applyImmersiveAfterLayout()", PLAYER)
        self.assertNotIn("private fun shouldUseImmersive(): Boolean = true", PLAYER)

    def test_player_media_transition_keeps_single_media3_prepare_path(self):
        self.assertIn("onNewIntent", PLAYER)
        self.assertIn("player.setMediaItem(mediaItem)", PLAYER)
        self.assertIn("player.prepare()", PLAYER)
        self.assertEqual(1, PLAYER.count("ExoPlayer.Builder(this).build()"))

    def test_full_regression_contract_keeps_core_flows_present(self):
        for token in (
            "on_catalog_changed",
            "start_native_player",
            "player_exited",
            "store.save_progress",
            "library.player_navigation",
            "resolve_artwork_palette",
        ):
            self.assertIn(token, MAIN)
        for token in (
            "onResume()",
            "onConfigurationChanged",
            "onPictureInPictureModeChanged",
            "restoreSystemUiBeforeExit",
        ):
            self.assertIn(token, PLAYER)

    def test_anilist_ptbr_translation_is_cached(self):
        with tempfile.TemporaryDirectory() as directory:
            client = AniListClient(directory)
            source = "This is the story of a young girl who moves to a new town."
            with patch.object(client, "_translate_chunk_to_pt_br", return_value="Esta é a história de uma jovem que se muda para uma nova cidade.") as translate:
                first = client.localize_description_to_pt_br(source)
                second = client.localize_description_to_pt_br(source)
            self.assertEqual(first, second)
            self.assertEqual(1, translate.call_count)
            cache = Path(directory) / "anilist_description_ptbr.json"
            self.assertTrue(cache.is_file())

    def test_anilist_translation_failure_is_not_retried_immediately(self):
        with tempfile.TemporaryDirectory() as directory:
            client = AniListClient(directory)
            source = "This is the story of a young girl in a new town."
            with patch.object(client, "_translate_chunk_to_pt_br", return_value=None) as translate:
                self.assertEqual(source, client.localize_description_to_pt_br(source))
                self.assertEqual(source, client.localize_description_to_pt_br(source))
            self.assertEqual(1, translate.call_count)

    def test_metadata_localization_is_opt_in_and_uses_translated_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            client = AniListClient(directory)
            media = {
                "id": 7,
                "title": {"romaji": "Example", "english": "Example", "native": "Example"},
                "description": "This is the story of a young hero.",
                "genres": ["Action"],
                "coverImage": {},
            }
            with patch.object(client, "_translate_chunk_to_pt_br", return_value="Esta é a história de um jovem herói."):
                metadata = client.metadata_from_media("Example", media, localize_description=True)
            self.assertEqual("Esta é a história de um jovem herói.", metadata["description"])


if __name__ == "__main__":
    unittest.main()
