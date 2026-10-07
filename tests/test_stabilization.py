import tempfile
import threading
import time
import unittest
from pathlib import Path

from core.library_store import LibraryStore
from core.settings import SettingsStore


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "main.py").read_text(encoding="utf-8")
DETAILS = (ROOT / "views" / "details_view.py").read_text(encoding="utf-8")
ANILIST = (ROOT / "core" / "anilist.py").read_text(encoding="utf-8")
STORE = (ROOT / "core" / "library_store.py").read_text(encoding="utf-8")
MAIN_ACTIVITY = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
PLAYER = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8")
SYSTEM_UI = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/SystemUiController.kt").read_text(encoding="utf-8")


class StabilizationTests(unittest.TestCase):
    def _episode_store(self):
        tmp = tempfile.TemporaryDirectory()
        store = LibraryStore(tmp.name)
        anime = store.upsert_anime(
            "stabilization-fixture",
            {"title": "Stabilization Fixture", "genres": "[]", "media_kind": "series"},
        )
        path = "/storage/emulated/0/Anime/Stabilization Fixture S01E01.mkv"
        store.upsert_episode(anime, path, "Stabilization Fixture S01E01.mkv", 1, 1)
        return tmp, store, path

    def test_details_guards_remain(self):
        self.assertNotIn("autofocus=bool(primary_target)", DETAILS)
        self.assertIn("palette_changed = (", DETAILS)
        self.assertIn("home_state.get('_update_thumbnail')", MAIN)
        self.assertIn("player_transition_inflight", MAIN)
        self.assertIn("episodeChangeTimeout", PLAYER)
        self.assertIn("resolveImmersivePolicy", PLAYER)

    def test_host_is_immersive_and_lifecycle_reapplies_the_central_policy(self):
        self.assertIn("applyApplicationImmersivePolicy(useContextAppearance = false)", MAIN_ACTIVITY)
        self.assertIn("override fun onStart()", MAIN_ACTIVITY)
        self.assertIn("override fun onResume()", MAIN_ACTIVITY)
        self.assertIn("override fun onWindowFocusChanged(hasFocus: Boolean)", MAIN_ACTIVITY)
        self.assertIn("override fun onConfigurationChanged", MAIN_ACTIVITY)
        self.assertIn("applyApplicationImmersivePolicy", SYSTEM_UI)
        self.assertIn("hide(WindowInsetsCompat.Type.systemBars())", SYSTEM_UI)
        self.assertIn("show(WindowInsetsCompat.Type.systemBars())", SYSTEM_UI)

    def test_player_startup_applies_final_system_ui_policy_without_normal_flash(self):
        bootstrap = PLAYER[PLAYER.index("override fun onCreate"):PLAYER.index("override fun onNewIntent")]
        self.assertNotIn("systemUiController.applyApplicationPolicy()", bootstrap)
        self.assertIn('if (shouldUseImmersive()) enterImmersiveMode() else restoreSystemUiBeforeExit()', bootstrap)

    def test_native_player_transition_write_failure_clears_pending_state(self):
        start = PLAYER.index("private fun requestEpisode")
        end = PLAYER.index("private fun seekToSavedPosition", start)
        block = PLAYER[start:end]
        self.assertIn("playbackWorker.submit", block)
        self.assertIn("val published = NativeMailbox.write(", block)
        self.assertIn("if (!published)", block)
        self.assertIn("invalidateTransition(\"mailbox_publish_failed\")", block)
        self.assertIn("episodeChangePending = false", block)
        self.assertIn("transitionGeneration", block)
        self.assertIn("handler.removeCallbacks(episodeChangeTimeout)", block)
        destroy = PLAYER[PLAYER.index("override fun onDestroy"):PLAYER.index("private fun shouldUseImmersive")]
        self.assertIn("playbackWorker.shutdown()", destroy)

    def test_player_overlaid_events_do_not_rebuild_flet_under_the_native_activity(self):
        watched_start = MAIN.index("elif event_type == 'player_mark_watched':")
        watched_end = MAIN.index("elif event_type == 'player_mark_unwatched':", watched_start)
        unwatched_end = MAIN.index("elif event_type == 'player_autoplay_changed':", watched_end)
        watched = MAIN[watched_start:watched_end]
        unwatched = MAIN[watched_end:unwatched_end]
        self.assertNotIn("render_current()", watched)
        self.assertNotIn("render_current()", unwatched)
        self.assertIn("elif event_type == 'player_exited':", MAIN)
        self.assertIn("on_catalog_changed()", MAIN[MAIN.index("elif event_type == 'player_exited':"):])

    def test_autoplay_event_updates_canonical_settings_key(self):
        start = MAIN.index("elif event_type == 'player_autoplay_changed':")
        end = MAIN.index("elif event_type in {'player_next_request', 'player_previous_request'}:", start)
        block = MAIN[start:end]
        self.assertIn('settings.set("player.autoplay_next", enabled)', block)
        self.assertNotIn("store.set_preference('autoplay_next'", block)

        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            settings = SettingsStore(store)
            settings.set("player.autoplay_next", False)
            self.assertFalse(settings.get("player.autoplay_next"))
            self.assertIsNone(store.get_preference("autoplay_next"))

    def test_file_uri_updates_absolute_path_progress_and_watched_state(self):
        tmp, store, path = self._episode_store()
        self.addCleanup(tmp.cleanup)
        uri = "file:///storage/emulated/0/Anime/Stabilization%20Fixture%20S01E01.mkv"
        now = int(time.time() * 1000)
        self.assertTrue(store.save_progress(uri, 30, 100, event_created_at=now))
        self.assertEqual(30, store.physical_row(path)["progress"])
        self.assertTrue(store.set_watched(uri, True))
        row = store.physical_row(path)
        self.assertTrue(row["watched"])
        self.assertEqual(100, row["progress"])

    def test_playback_event_ordering_is_atomic_across_uri_representations(self):
        tmp, store, path = self._episode_store()
        self.addCleanup(tmp.cleanup)
        uri = "file:///storage/emulated/0/Anime/Stabilization%20Fixture%20S01E01.mkv"
        t1 = int(time.time() * 1000)
        self.assertTrue(store.save_progress(uri, 80, 100, event_created_at=t1))
        self.assertFalse(store.save_progress(path, 40, 100, event_created_at=t1 - 100))
        self.assertEqual(80, store.physical_row(path)["progress"])
        self.assertTrue(store.save_progress(path, 25, 100, event_created_at=t1 + 100))
        self.assertEqual(25, store.physical_row(path)["progress"])

    def test_details_metadata_and_palette_tasks_have_stale_result_guards(self):
        self.assertIn("navigation.current != \"details\"", MAIN)
        self.assertIn("(current[0] or {}).get(\"id\") != anime_id", MAIN)
        self.assertIn("details_instance_generation", MAIN)
        self.assertIn("detail_instance_token", MAIN)
        self.assertIn("callable(is_active) and not is_active()", DETAILS)

    def test_details_refresh_rechecks_view_generation_after_background_catalog_read(self):
        start = MAIN.index("async def refresh_current_details")
        end = MAIN.index("async def refresh_current_metadata", start)
        block = MAIN[start:end]
        self.assertIn("details_token = details_instance_generation[0]", block)
        self.assertIn("details_instance_generation[0] != details_token", block)
        self.assertIn('navigation.current != "details"', block)

    def test_details_mutation_callbacks_ignore_results_after_navigation(self):
        note = DETAILS[DETAILS.index("async def save(_event):", DETAILS.index("def edit_note")):DETAILS.index("async def clear_and_save", DETAILS.index("def edit_note"))]
        tags = DETAILS[DETAILS.index("async def save_tags(tags):"):DETAILS.index("def render_tags():")]
        identification = DETAILS[DETAILS.index("async def save(_event):", DETAILS.index("def edit_identification")):DETAILS.index("save_button.on_click = save", DETAILS.index("def edit_identification"))]
        self.assertIn("callable(is_active) and not is_active()", note)
        self.assertIn("callable(is_active) and not is_active()", tags)
        self.assertIn("callable(is_active) and not is_active()", identification)

    def test_newer_thumbnail_generation_supersedes_inflight_older_request(self):
        request_start = MAIN.index("def request_missing_thumbnail")
        request_end = MAIN.index("def storage_state", request_start)
        block = MAIN[request_start:request_end]
        self.assertIn("previous = thumbnail_latest_key_by_uri.get(path_ref)", block)
        self.assertIn("thumbnail_pending.pop(previous, None)", block)
        self.assertIn("thumbnail_requests.discard(previous)", block)
        self.assertIn("thumbnail_latest_key_by_uri[path_ref] = key", block)
        self.assertIn("thumbnail_key != latest_key", MAIN)

    def test_thumbnail_ready_only_accepts_latest_generation(self):
        start = MAIN.index("elif event_type == 'thumbnail_ready':")
        end = MAIN.index("elif event_type == 'thumbnail_error':", start)
        block = MAIN[start:end]
        self.assertIn("thumbnail_latest_key_by_uri", MAIN)
        self.assertIn("latest_key = thumbnail_latest_key_by_uri.get(uri)", block)
        self.assertIn("thumbnail_key != latest_key", block)
        self.assertIn("media_identity", block)
        self.assertIn("THUMBNAIL_STALE", block)
        self.assertIn("thumbnail_request_started_at", MAIN)

    def test_home_and_organize_catalog_refreshes_are_coalesced(self):
        home = (ROOT / "views" / "home_view.py").read_text(encoding="utf-8")
        organize = (ROOT / "views" / "organize_view.py").read_text(encoding="utf-8")
        for source in (home, organize):
            self.assertIn("catalog_refresh_scheduled", source)
            self.assertIn("catalog_refresh_dirty", source)
            self.assertIn("while catalog_refresh_dirty[0]", source)

    def test_duplicate_player_handoffs_are_serialized(self):
        start = MAIN.index("def play_episode")
        end = MAIN.index("def open_marathon", start)
        block = MAIN[start:end]
        self.assertIn('player_launch_inflight["value"]', block)
        self.assertIn("PLAYER_HANDOFF_DUPLICATE_IGNORED", block)
        self.assertIn('finally:\n                player_launch_inflight["value"] = False', block)

    def test_native_player_reuse_refreshes_autoplay_state(self):
        start = PLAYER.index("override fun onNewIntent")
        end = PLAYER.index("private fun buildMediaItem", start)
        block = PLAYER[start:end]
        self.assertIn('autoplayNext = newIntent.getBooleanExtra("autoplay", autoplayNext)', block)
        self.assertIn("updateEpisodeNavigationButtons()", block)
        button_start = PLAYER.index("private fun updateEpisodeNavigationButtons")
        button_end = PLAYER.index("private fun requestEpisode", button_start)
        button_block = PLAYER[button_start:button_end]
        self.assertIn('intent.getBooleanExtra("canNext", false)', button_block)
        self.assertIn('intent.getBooleanExtra("canPrevious", false)', button_block)

    def test_anilist_original_description_survives_pt_br_localization(self):
        from unittest.mock import patch
        from core.anilist import AniListClient
        media = {
            "id": 99,
            "title": {"english": "Example", "romaji": "Example", "native": "例"},
            "description": "The original description stays intact.",
        }
        with tempfile.TemporaryDirectory() as directory:
            client = AniListClient(directory)
            with patch.object(client, "localize_description_to_pt_br", return_value="A descrição traduzida."):
                metadata = client.metadata_from_media("Example", media, localize_description=True)
            self.assertEqual(metadata["description"], "A descrição traduzida.")
            self.assertEqual(metadata["description_original"], "The original description stays intact.")

    def test_manual_description_is_not_auto_translated(self):
        from unittest.mock import patch
        from core.library_service import LibraryService
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            service = LibraryService(store)
            store.upsert_anime(
                "manual",
                {"title": "Manual", "description": "Keep this exact text", "genres": "[]"},
                source="manual",
            )
            with patch.object(service.anilist, "localize_description_to_pt_br") as translate:
                result = service._ensure_cached_description_pt_br("manual", store.anime_metadata("manual"))
            translate.assert_not_called()
            self.assertEqual(result["description"], "Keep this exact text")


    def test_translation_cache_is_bounded(self):
        self.assertIn("MAX_TRANSLATION_CACHE_ENTRIES = 4096", ANILIST)

    def test_playback_event_dedupe_cache_is_bounded(self):
        self.assertIn("if len(self._last_playback_event_at) > 8192", STORE)
        self.assertIn("if len(processed_native_operations) > 1024", MAIN)

    def test_description_original_is_not_cleared_by_local_metadata_updates(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            store.upsert_anime(
                "merge",
                {
                    "title": "Merge",
                    "description": "Translated description.",
                    "description_original": "Original description.",
                    "genres": "[]",
                },
                source="anilist",
            )
            store.upsert_anime(
                "merge",
                {"description": "Local override"},
                source="local",
            )
            self.assertEqual(
                store.anime_metadata("merge")["description_original"],
                "Original description.",
            )

    def test_anilist_translation_does_not_hold_the_rate_limit_lock(self):
        start = ANILIST.index("def localize_description_to_pt_br")
        end = ANILIST.index("    @staticmethod\n    def _header", start)
        block = ANILIST[start:end]
        self.assertIn("with self._translation_lock:", block)
        self.assertNotIn("with self._rate_lock:", block)
        self.assertIn("self._translation_lock = threading.RLock()", ANILIST)

    def test_google_sign_in_job_is_cancelled_with_main_activity(self):
        self.assertIn("googleSignInJob?.cancel()", MAIN_ACTIVITY)
        self.assertIn("googleSignInJob = null", MAIN_ACTIVITY)
        self.assertIn("val job = lifecycleScope.launch", MAIN_ACTIVITY)
        self.assertIn("googleSignInJob = job", MAIN_ACTIVITY)

    def test_navigation_controller_behavioral_back_flow(self):
        from core.navigation import NavigationController

        nav = NavigationController(clock=lambda: 100.0)
        nav.push("details")
        nav.push("settings")
        nav.push_settings("player")
        self.assertEqual("settings_inner", nav.back())
        self.assertEqual("previous", nav.back())
        self.assertEqual("previous", nav.back())
        self.assertEqual("exit_requested", nav.back())



    def test_background_description_localization_persists_and_notifies_details(self):
        from unittest.mock import patch
        from core.library_service import LibraryService

        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            source = "The story follows a young hero who protects their town."
            anime_id = store.upsert_anime(
                "localized",
                {
                    "title": "Localized",
                    "description": source,
                    "description_original": source,
                    "anilist_id": 123,
                    "genres": "[]",
                },
                source="anilist",
            )
            service = LibraryService(store)
            changed = threading.Event()
            service.set_metadata_change_listener(
                lambda _event, _payload: changed.set()
            )
            started = threading.Event()
            release = threading.Event()

            def localize(_description, **_kwargs):
                started.set()
                release.wait(2.0)
                return "A história acompanha um jovem herói que protege sua cidade."

            try:
                with patch.object(service.anilist, "localize_description_to_pt_br", side_effect=localize):
                    scheduled = service._schedule_description_localization(
                        "localized",
                        source,
                        local_anime_id=anime_id,
                        request_id="translation-test",
                    )
                    self.assertTrue(scheduled)
                    self.assertTrue(started.wait(1.0))
                    self.assertEqual(source, store.anime_metadata_by_id(anime_id)["description"])
                    release.set()
                    self.assertTrue(changed.wait(2.0))
                self.assertEqual(
                    "A história acompanha um jovem herói que protege sua cidade.",
                    store.anime_metadata_by_id(anime_id)["description"],
                )
                self.assertEqual(
                    source,
                    store.anime_metadata_by_id(anime_id)["description_original"],
                )
            finally:
                release.set()
                service.shutdown()

    def test_details_and_main_use_canonical_localized_description_pipeline(self):
        mapper = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/mapper/LibraryUiMappers.kt").read_text(encoding="utf-8")
        details_model = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/model/ReiAnixDetailsUiModels.kt").read_text(encoding="utf-8")
        self.assertIn('metadata.get("description") or metadata.get("description_original")', DETAILS)
        self.assertIn("library.set_metadata_change_listener(_dispatch_metadata_change)", MAIN)
        self.assertIn('f"metadata_translation:{payload.get('anime_id') or 0}"', MAIN)
        self.assertIn('description = metadata.stringOrNull("description")', mapper)
        self.assertIn('?: metadata.stringOrNull("description_original")', mapper)
        self.assertIn("description = anime.description", details_model)
        self.assertIn("description_original", ANILIST)

if __name__ == "__main__":
    unittest.main()
