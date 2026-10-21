import tempfile
import unittest
from pathlib import Path
from tests.native_main_contract import native_main_source


ROOT = Path(__file__).resolve().parents[1]
MAIN = native_main_source()
STORE_SOURCE = (ROOT / "core/library_store.py").read_text(encoding="utf-8")
PLAYER = (
    ROOT
    / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
).read_text(encoding="utf-8")
POLICY = (
    ROOT
    / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/PlayerMediaPolicy.kt"
).read_text(encoding="utf-8")
HOME = (ROOT / "views/home_view.py").read_text(encoding="utf-8")


class ContinueResumeTests(unittest.TestCase):
    def test_continue_resolves_episode_by_canonical_episode_id(self):
        view_model = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt").read_text(encoding="utf-8")
        self.assertIn("fun openEpisode(episodeId: Long, expectedAnimeId: Long?", view_model)
        self.assertIn("nativeLibraryRepository.getEpisode(episodeId)", view_model)
        self.assertIn("nativeLibraryRepository.listEpisodes(episode.animeId)", view_model)
        self.assertIn("episode.animeId", view_model)

    def test_continue_does_not_trust_stale_home_anime_identity(self):
        view_model = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt").read_text(encoding="utf-8")
        start = view_model.index("fun openEpisode(episodeId: Long, expectedAnimeId: Long?")
        end = view_model.index("override fun onCleared()", start)
        launch = view_model[start:end]
        self.assertIn("expectedAnimeId", launch)
        self.assertIn("nativeLibraryRepository.getEpisode(episodeId)", launch)
        self.assertIn("Intent.ACTION_VIEW", launch)
        self.assertIn("episode.id", launch)
        self.assertIn('on_play_episode(', HOME)

    def test_resume_uses_seconds_in_sqlite_and_ms_on_native_boundary(self):
        view_model = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt").read_text(encoding="utf-8")
        self.assertIn('preferenceValue("player.resume")', view_model)
        self.assertIn("val progressSeconds = episode.progress", view_model)
        self.assertIn("(progressSeconds * 1_000.0).toLong()", view_model)
        self.assertIn('putExtra("positionMs", positionMs)', (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/NativePlayerRequest.kt").read_text(encoding="utf-8"))
        self.assertIn('saveProgress("player_progress"', PLAYER)

    def test_continue_reloads_the_latest_persisted_progress(self):
        view_model = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt").read_text(encoding="utf-8")
        start = view_model.index("fun openEpisode(episodeId: Long, expectedAnimeId: Long?")
        end = view_model.index("override fun onCleared()", start)
        launch = view_model[start:end]
        self.assertIn("nativeLibraryRepository.getEpisode(episodeId)", launch)
        self.assertIn("preferenceValue(\"player.resume\")", launch)
        self.assertIn("episode.progress", launch)
        self.assertIn("positionMs", launch)
        self.assertIn("positionWasExplicit", (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/NativePlayerRequest.kt").read_text(encoding="utf-8"))

    def test_ready_uses_preloaded_resume_position_before_playback(self):
        prepare = PLAYER[PLAYER.index("private fun prepareCurrentMedia"):PLAYER.index("private fun createPlayerListener")]
        self.assertIn("player.setMediaItem(mediaItem, initialPositionMsForGeneration)", prepare)
        self.assertIn("initialPositionMsForGeneration", prepare)
        self.assertIn("player.playWhenReady = shouldPlayWhenReady", prepare)
        self.assertLess(
            prepare.index("player.playWhenReady = shouldPlayWhenReady"),
            prepare.index("player.prepare()"),
        )
        self.assertNotIn("player.playWhenReady = false", prepare)
        self.assertNotIn("RESUME_SEEK_REQUESTED", PLAYER)
        self.assertNotIn("RESUME_SEEK_APPLIED", PLAYER)
        self.assertNotIn(
            "val appliedResumePositionMs = seekToSavedPosition(restoredPositionMs ?: savedPosition)",
            PLAYER,
        )
        self.assertIn("RESUME_POSITION_ALREADY_PRELOADED", PLAYER)

    def test_resume_position_is_clamped_by_existing_policy(self):
        self.assertIn("fun safeResumePosition", POLICY)
        self.assertIn("requestedMs.coerceIn(0L, lastPlayable)", POLICY)

    def test_invalid_continue_source_is_rejected_before_native_handoff(self):
        self.assertIn("validateLocalSource(localUri)", PLAYER)
        self.assertIn("SourceValidationFailure", PLAYER)
        self.assertIn("FILE_NOT_FOUND", PLAYER)
        self.assertIn("performOpenPlayer(", (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8"))
        self.assertIn("PLAYER_HANDOFF_REJECTED", (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8"))

    def test_progress_writes_are_session_bound(self):
        consumption = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixConsumptionService.kt").read_text(encoding="utf-8")
        self.assertIn("playerSessionId", PLAYER)
        self.assertIn("playerGeneration", PLAYER)
        self.assertIn("transitionGeneration", PLAYER)
        self.assertIn("suspend fun saveProgress(", consumption)
        self.assertIn("episodeId: Long", consumption)

    def test_stale_session_cannot_overwrite_new_session_progress(self):
        from core.library_store import LibraryStore

        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("stage27", {"title": "earlier validation stage 27", "genres": "[]"})
            store.upsert_episode(
                anime,
                "content://stage27/e01",
                "E01.mkv",
                1,
                1,
                media_identity="stage27:e01",
            )
            self.assertTrue(store.activate_playback_session("session-a"))
            self.assertTrue(
                store.save_progress(
                    "content://stage27/e01",
                    300,
                    1000,
                    episode_id=1,
                    event_created_at=1_000,
                    session_id="session-a",
                )
            )
            self.assertTrue(store.activate_playback_session("session-b"))
            self.assertFalse(
                store.save_progress(
                    "content://stage27/e01",
                    300,
                    1000,
                    episode_id=1,
                    event_created_at=2_000,
                    session_id="session-a",
                )
            )
            self.assertTrue(
                store.save_progress(
                    "content://stage27/e01",
                    500,
                    1000,
                    episode_id=1,
                    event_created_at=3_000,
                    session_id="session-b",
                )
            )
            self.assertEqual(500, store.physical_row("content://stage27/e01")["progress"])

    def test_same_session_can_update_progress_without_creating_parallel_state(self):
        from core.library_store import LibraryStore

        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("fixture_27-b", {"title": "earlier validation stage 27 B", "genres": "[]"})
            store.upsert_episode(
                anime,
                "content://stage27/e02",
                "E02.mkv",
                1,
                2,
                media_identity="stage27:e02",
            )
            episode = store.physical_row("content://stage27/e02")
            self.assertTrue(store.activate_playback_session("same"))
            self.assertTrue(
                store.save_progress(
                    episode["path"],
                    180,
                    900,
                    episode_id=episode["id"],
                    event_created_at=1_000,
                    session_id="same",
                )
            )
            self.assertTrue(
                store.save_progress(
                    episode["path"],
                    240,
                    900,
                    episode_id=episode["id"],
                    event_created_at=2_000,
                    session_id="same",
                )
            )
            row = store.physical_row(episode["path"])
            self.assertEqual(240, row["progress"])
            self.assertEqual(900, row["duration"])

    def test_continue_projection_excludes_completed_episode(self):
        from core.library_store import LibraryStore

        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("fixture_27-c", {"title": "earlier validation stage 27 C", "genres": "[]"})
            store.upsert_episode(
                anime,
                "content://stage27/e03",
                "E03.mkv",
                1,
                3,
                media_identity="stage27:e03",
            )
            episode = store.physical_row("content://stage27/e03")
            self.assertTrue(store.save_progress(episode["path"], 899, 1000, episode_id=episode["id"], event_created_at=1_000))
            self.assertEqual(1, len(store.continue_watching()))
            self.assertTrue(store.save_progress(episode["path"], 900, 1000, episode_id=episode["id"], event_created_at=2_000))
            self.assertEqual([], store.continue_watching())

    def test_no_home_file_change_is_required_for_continue_identity_fix(self):
        self.assertNotIn('episode_id=episode.get("anime_id")', HOME)
        self.assertIn('episode_id=episode.get("id")', HOME)


if __name__ == "__main__":
    unittest.main()
