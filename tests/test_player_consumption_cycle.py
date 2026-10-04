import tempfile
import time
import unittest

from core.library_store import LibraryStore


class PlaybackConsumptionCycleTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = LibraryStore(self.tmp.name)
        self.anime = self.store.upsert_anime(
            "cycle",
            {"title": "Cycle", "genres": "[]", "media_kind": "series"},
        )

    def tearDown(self):
        self.tmp.cleanup()

    def episode(self, path, season, number, *, episode_type="regular", missing=False):
        self.store.upsert_episode(
            self.anime, path, f"{path.rsplit('/', 1)[-1]}.mkv",
            season, number, episode_type=episode_type,
        )
        if missing:
            with self.store._conn() as connection:
                connection.execute("UPDATE episodes SET missing=1 WHERE path=?", (path,))
        return path

    def test_resume_completion_history_and_continue_are_one_durable_state(self):
        path = self.episode("content://cycle/1", 1, 1)
        self.store.save_progress(path, 47, 100)
        row = self.store.physical_row(path)
        self.assertEqual("in_progress", self.store.consumption_state(row))
        self.assertEqual(path, self.store.continue_watching()[0]["path"])

        self.store.save_progress(path, 90, 100)
        row = self.store.physical_row(path)
        self.assertTrue(row["watched"])
        self.assertEqual("watched", self.store.consumption_state(row))
        self.assertEqual([], self.store.continue_watching())
        self.assertEqual(path, self.store.playback_history()[0]["path"])
        self.assertIsNotNone(self.store.playback_history()[0]["last_played_at"])

    def test_progress_boundaries_and_zero_duration_are_safe(self):
        path = self.episode("content://cycle/boundary", 1, 2)
        for position, expected in (
            (0, "unwatched"),
            (1, "in_progress"),
            (10, "in_progress"),
            (50, "in_progress"),
            (89, "in_progress"),
            (90, "watched"),
            (91, "watched"),
            (99, "watched"),
            (100, "watched"),
        ):
            self.store.save_progress(path, position, 100)
            self.assertEqual(expected, self.store.consumption_state(self.store.physical_row(path)))

        zero = self.episode("content://cycle/zero", 1, 5)
        self.store.save_progress(zero, 25, 0)
        row = self.store.physical_row(zero)
        self.assertEqual(25, row["progress"])
        self.assertEqual(0, row["duration"])
        self.assertFalse(row["watched"])

    def test_invalid_playback_values_and_created_at_are_rejected(self):
        path = self.episode("content://cycle/invalid", 1, 4)
        self.assertFalse(self.store.save_progress(path, float("nan"), 100))
        self.assertFalse(self.store.save_progress(path, 50, float("inf")))
        self.assertFalse(self.store.save_progress(path, 50, 100, event_created_at=float("nan")))
        self.assertFalse(self.store.save_progress(path, 50, 100, event_created_at=float("inf")))
        row = self.store.physical_row(path)
        self.assertEqual(0, row["progress"])
        self.assertEqual(0, row["duration"])
        self.assertFalse(row["watched"])
        self.assertIsNone(row["last_played_at"])


    def test_duplicate_and_out_of_order_native_events_do_not_regress_state(self):
        path = self.episode("content://cycle/order", 1, 3)
        t1 = int(time.time() * 1000)
        self.assertTrue(self.store.save_progress(path, 50, 100, event_created_at=t1))
        self.assertFalse(self.store.save_progress(path, 50, 100, event_created_at=t1))
        self.assertFalse(self.store.save_progress(path, 40, 100, event_created_at=t1 - 100))
        self.assertEqual(50, self.store.physical_row(path)["progress"])
        self.assertTrue(self.store.save_progress(path, 30, 100, event_created_at=t1 + 100))
        self.assertEqual(30, self.store.physical_row(path)["progress"])

    def test_next_episode_skips_missing_and_specials_and_crosses_season(self):
        e1 = self.episode("content://cycle/s1e12", 1, 12)
        self.episode("content://cycle/special", 1, 99, episode_type="ova")
        self.episode("content://cycle/missing", 1, 13, missing=True)
        e2 = self.episode("content://cycle/s2e1", 2, 1)
        self.assertEqual(e2, self.store.next_episode(e1)["path"])
        self.store.save_progress(e1, 90, 100)
        self.assertEqual(e2, self.store.playback_target(self.anime)["path"])


    def test_event_order_survives_store_reopen(self):
        path = self.episode("content://cycle/reopen-order", 1, 6)
        first = int(time.time() * 1000)
        self.assertTrue(self.store.save_progress(path, 80, 100, event_created_at=first))
        reopened = LibraryStore(self.tmp.name)
        self.assertFalse(reopened.save_progress(path, 40, 100, event_created_at=first - 1000))
        self.assertEqual(80, reopened.physical_row(path)["progress"])
        self.assertTrue(reopened.save_progress(path, 30, 100, event_created_at=first + 1000))
        self.assertEqual(30, reopened.physical_row(path)["progress"])

    def test_completed_media_is_not_continue_watching_but_has_next_episode(self):
        first = self.episode("content://cycle/s1e1", 1, 1)
        second = self.episode("content://cycle/s1e2", 1, 2)
        self.store.save_progress(first, 90, 100, event_created_at=int(time.time() * 1000))
        self.assertEqual([], self.store.continue_watching())
        self.assertEqual(second, self.store.next_episode(first)["path"])

    def test_home_and_search_reflect_completion_from_the_same_store_state(self):
        from core.library_service import LibraryService

        first = self.episode("content://cycle/home-e1", 1, 1)
        second = self.episode("content://cycle/home-e2", 1, 2)
        self.store.save_progress(first, 90, 100, event_created_at=int(time.time() * 1000))
        service = LibraryService(self.store)
        home = service.media_center_home()
        self.assertFalse(any(item["path"] == first for item in home["continue_watching"]))
        self.assertNotIn("next_episode", home)
        self.assertEqual(second, self.store.next_episode(first)["path"])
        watched = service.browse_catalog(self.store.catalog(), state="Assistidos")
        active = service.browse_catalog(self.store.catalog(), state="Em andamento")
        self.assertEqual([item["main_title"] for item in watched], ["Cycle"])
        self.assertEqual([], active)

    def test_movie_has_progress_but_never_next_episode(self):
        movie = self.store.upsert_anime(
            "movie", {"title": "Movie", "genres": "[]", "media_kind": "movie"}
        )
        path = "content://movie/file"
        self.store.upsert_episode(movie, path, "Movie.mkv", 1, 1, episode_type="movie")
        self.store.save_progress(path, 50, 100)
        self.assertEqual(path, self.store.continue_watching()[0]["path"])
        self.assertIsNone(self.store.next_episode(path))
        self.store.save_progress(path, 90, 100)
        self.assertEqual([], [x for x in self.store.continue_watching() if x["path"] == path])
        self.assertEqual(path, self.store.playback_history()[0]["path"])

    def test_rescan_style_upsert_preserves_playback_state_for_same_identity(self):
        path = self.episode("content://cycle/move", 1, 4)
        self.store.save_progress(path, 47, 100)
        self.store.upsert_episode(
            self.anime, path, "renamed.mkv", 1, 4,
            media_identity="stable-cycle-4",
        )
        row = self.store.physical_row(path)
        self.assertEqual(47, row["progress"])


    def test_replaying_older_completed_episode_does_not_move_next_episode_backwards(self):
        e1 = self.episode("content://cycle/replay-e1", 1, 1)
        e2 = self.episode("content://cycle/replay-e2", 1, 2)
        e3 = self.episode("content://cycle/replay-e3", 1, 3)
        self.store.save_progress(e1, 100, 100)
        self.store.save_progress(e2, 100, 100)
        self.assertEqual(e3, self.store.next_episode(e2)["path"])
        self.store.save_progress(e1, 20, 100)
        replayed = self.store.physical_row(e1)
        self.assertTrue(replayed["watched"])
        self.assertEqual(20, replayed["progress"])
        self.assertEqual(e3, self.store.playback_target(self.anime)["path"])
        self.store.save_progress(e1, 100, 100)
        self.assertEqual(e3, self.store.playback_target(self.anime)["path"])

    def test_absolute_number_orders_unparsed_regular_media_deterministically(self):
        low = self.episode("content://cycle/absolute-100", 1, None)
        self.store.upsert_episode(
            self.anime, "content://cycle/absolute-101", "episode-101.mkv", 1, None,
            absolute_number=101,
        )
        self.store.upsert_episode(
            self.anime, low, "episode-100.mkv", 1, None,
            absolute_number=100,
        )
        self.assertEqual(
            self.store.next_episode(low)["path"],
            "content://cycle/absolute-101",
        )
        self.assertEqual(
            self.store.previous_episode("content://cycle/absolute-101")["path"],
            low,
        )

    def test_all_supported_special_types_never_enter_regular_navigation(self):
        from core.consumption import is_regular_episode
        for index, episode_type in enumerate(("special", "ova", "oad", "ona", "extra"), start=1):
            path = f"content://cycle/special-{index}"
            self.assertFalse(
                is_regular_episode({"episode_type": episode_type}),
                episode_type,
            )
            self.episode(path, 1, index, episode_type=episode_type)
        regular = self.episode("content://cycle/regular-after-specials", 1, 20)
        self.assertIsNone(self.store.previous_episode(regular))

    def test_special_only_library_has_playback_target_without_joining_regular_sequence(self):
        special = self.episode("content://cycle/special-only", 1, 1, episode_type="ova")
        self.assertEqual(
            self.store.playback_target(self.anime)["path"],
            "content://cycle/special-only",
        )
        self.assertIsNone(self.store.next_episode(special))

    def test_central_consumption_policy_treats_nonfinite_catalog_values_as_invalid(self):
        from core.consumption import consumption_state, progress_ratio
        invalid = {"progress": float("inf"), "duration": 100}
        self.assertEqual("unwatched", consumption_state(invalid).value)
        self.assertEqual(0.0, progress_ratio(invalid))

    def test_native_player_contract_uses_single_autoplay_preference(self):
        from pathlib import Path
        root = Path(__file__).resolve().parents[1]
        main = (root / "main.py").read_text(encoding="utf-8")
        bridge = (root / "core" / "android_bridge.py").read_text(encoding="utf-8")
        player = (root / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "NativePlayerActivity.kt").read_text(encoding="utf-8")
        self.assertIn('settings.get("player.autoplay_next")', main)
        self.assertIn('SettingDefinition("player.autoplay_next", "bool", True)', (root / "core" / "settings.py").read_text(encoding="utf-8"))
        self.assertIn('autoplay=str(bool(autoplay)).lower()', bridge)
        self.assertNotIn('get_preference("autoplay_next"', main)
        self.assertIn('intent.getBooleanExtra("autoplay", true)', player)
        self.assertIn('"player_autoplay_changed"', player)
        self.assertNotIn('getSharedPreferences("reiflix_player"', player)
        self.assertIn('onSaveInstanceState(outState: Bundle)', player)
        self.assertIn('savedInstanceState?.takeIf { it.containsKey("position_ms") }', player)
        self.assertIn('savedInstanceState?.takeIf { it.containsKey("autoplay_next") }', player)
        self.assertIn('saveProgress("player_progress", force = true)', player)
        self.assertIn('saveProgress("player_paused", force = true)', player)
        self.assertIn("val shouldReportExit = isFinishing && !suppressExitEvent && !exitReported && !isChangingConfigurations", player)
        self.assertIn("if (shouldReportExit)", player)
        self.assertIn('reportPlayerExit("activity_finish")', player)
        self.assertIn('val temp = File(queue, "$PREFIX$id.json.tmp")', (root / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "bridge" / "NativeMailbox.kt").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
