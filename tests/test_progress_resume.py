import tempfile
import unittest
from pathlib import Path

from core.consumption import consumption_state, progress_ratio
from core.library_store import LibraryStore
from core.settings import SettingsStore

ROOT = Path(__file__).resolve().parents[1]

class ProgressResumeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = LibraryStore(self.tmp.name)
        self.anime_a = self.store.upsert_anime("fixture_10-a", {"title": "stage10 A", "genres": "[]"}, source="local")
        self.anime_b = self.store.upsert_anime("fixture_10-b", {"title": "stage10 B", "genres": "[]"}, source="local")

    def tearDown(self):
        self.tmp.cleanup()

    def episode(self, anime_id, path, number, identity):
        self.store.upsert_episode(anime_id, path, Path(path).name, 1, number, media_identity=identity)
        return self.store.physical_row(path)

    def test_episode_id_is_canonical_and_survives_path_reconciliation(self):
        first = self.episode(self.anime_a, "content://stage10/a-e1", 1, "stage10:e1")
        second = self.episode(self.anime_a, "content://stage10/a-e2", 2, "stage10:e2")
        self.assertTrue(self.store.save_progress(first["path"], 12, 100, episode_id=first["id"], event_created_at=1000))
        self.assertFalse(self.store.save_progress(second["path"], 88, 100, episode_id=first["id"], event_created_at=1100))
        self.assertEqual(12, self.store.physical_row(first["path"])["progress"])
        self.assertEqual(0, self.store.physical_row(second["path"])["progress"])
        self.store.upsert_episode(self.anime_a, "content://stage10/a-e1-renamed", "a-e1-renamed.mkv", 1, 1, media_identity="stage10:e1")
        migrated = self.store.physical_row("content://stage10/a-e1-renamed")
        self.assertEqual(first["id"], migrated["id"])
        self.assertEqual(12, migrated["progress"])
        self.assertTrue(self.store.save_progress(None, 20, 100, episode_id=first["id"], event_created_at=1200))
        self.assertEqual(20, self.store.physical_row("content://stage10/a-e1-renamed")["progress"])

    def test_favorite_persists_after_store_reopen_without_parallel_state(self):
        anime_id = self.anime_a
        initial = self.store.catalog(anime_ids=[anime_id])[0]
        self.assertFalse(initial["favorite"])

        self.store.toggle_favorite(anime_id)
        self.assertTrue(self.store.catalog(anime_ids=[anime_id])[0]["favorite"])

        reopened = LibraryStore(self.tmp.name)
        self.assertTrue(reopened.catalog(anime_ids=[anime_id])[0]["favorite"])

        reopened.toggle_favorite(anime_id)
        self.assertFalse(reopened.catalog(anime_ids=[anime_id])[0]["favorite"])

    def test_state_combinations_keep_favorite_and_consumption_independent(self):
        unwatched = self.episode(
            self.anime_a,
            "content://stage10/unwatched",
            1,
            "stage10:unwatched",
        )
        partial = self.episode(
            self.anime_a,
            "content://stage10/partial",
            2,
            "stage10:partial",
        )
        completed = self.episode(
            self.anime_a,
            "content://stage10/completed",
            3,
            "stage10:completed",
        )

        self.store.toggle_favorite(self.anime_a)
        self.store.save_progress(
            partial["path"],
            25,
            100,
            episode_id=partial["id"],
            event_created_at=10,
        )
        self.store.save_progress(
            completed["path"],
            90,
            100,
            episode_id=completed["id"],
            event_created_at=11,
        )

        catalog = self.store.catalog(anime_ids=[self.anime_a])[0]
        self.assertTrue(catalog["favorite"])

        episodes = {
            episode["id"]: episode
            for season in catalog["seasons"]
            for episode in season["episodes"]
        }
        self.assertEqual("unwatched", episodes[unwatched["id"]]["consumption_state"])
        self.assertEqual("in_progress", episodes[partial["id"]]["consumption_state"])
        self.assertIn(
            episodes[completed["id"]]["consumption_state"],
            {"completed", "watched"},
        )

    def test_progress_boundaries_and_completion_threshold_are_consistent(self):
        ep = self.episode(self.anime_a, "content://stage10/bounds", 3, "stage10:bounds")
        for stamp, position in enumerate((0, 1, 2, 10, 50, 90, 99, 100), start=1):
            self.assertTrue(self.store.save_progress(ep["path"], position, 100, episode_id=ep["id"], event_created_at=stamp))
            row = self.store.physical_row(ep["path"])
            self.assertGreaterEqual(row["progress"], 0)
            self.assertLessEqual(row["progress"], row["duration"])
            self.assertGreaterEqual(progress_ratio(row), 0.0)
            self.assertLessEqual(progress_ratio(row), 1.0)
            expected = "watched" if position >= 90 else ("unwatched" if position == 0 else "in_progress")
            self.assertEqual(expected, consumption_state(row).value)
        self.assertEqual(1.0, progress_ratio(self.store.physical_row(ep["path"])))

    def test_invalid_and_unknown_duration_values_do_not_create_impossible_state(self):
        ep = self.episode(self.anime_a, "content://stage10/invalid", 4, "stage10:invalid")
        self.assertFalse(self.store.save_progress(ep["path"], -1, 100, episode_id=ep["id"], event_created_at=1))
        self.assertTrue(self.store.save_progress(ep["path"], 150, 100, episode_id=ep["id"], event_created_at=2))
        row = self.store.physical_row(ep["path"])
        self.assertEqual((100, 100, 1.0), (row["progress"], row["duration"], progress_ratio(row)))
        self.assertTrue(row["watched"])
        unknown = self.episode(self.anime_a, "content://stage10/unknown", 5, "stage10:unknown")
        self.assertTrue(self.store.save_progress(unknown["path"], 25, 0, episode_id=unknown["id"], event_created_at=3))
        row = self.store.physical_row(unknown["path"])
        known = self.episode(self.anime_a, "content://stage10/known-duration", 5, "stage10:known-duration")
        self.assertTrue(self.store.save_progress(known["path"], 60, 100, episode_id=known["id"], event_created_at=4))
        self.assertTrue(self.store.save_progress(known["path"], 61, 0, episode_id=known["id"], event_created_at=5))
        known_row = self.store.physical_row(known["path"])
        self.assertEqual((61, 100), (known_row["progress"], known_row["duration"]))
        self.assertEqual((25, 0, 0.0), (row["progress"], row["duration"], progress_ratio(row)))
        self.assertEqual("in_progress", consumption_state(row).value)

    def test_multiple_episodes_and_animes_never_mix_progress(self):
        rows = [
            self.episode(self.anime_a, "content://stage10/a1", 1, "stage10:a1"),
            self.episode(self.anime_a, "content://stage10/a2", 2, "stage10:a2"),
            self.episode(self.anime_a, "content://stage10/a3", 3, "stage10:a3"),
            self.episode(self.anime_b, "content://stage10/b1", 1, "stage10:b1"),
        ]
        for stamp, row, position in zip((10, 20, 30, 40), rows, (10, 30, 50, 40)):
            self.assertTrue(self.store.save_progress(row["path"], position, 100, episode_id=row["id"], event_created_at=stamp))
        self.assertEqual((10, 30, 50, 40), tuple(self.store.physical_row(row["path"])["progress"] for row in rows))

    def test_legacy_resume_setting_migrates_and_main_uses_canonical_key(self):
        self.store.set_preference("resume_playback", "false")
        settings = SettingsStore(self.store)
        self.assertFalse(settings.get("player.resume"))
        self.assertEqual("false", self.store.get_preference("player.resume"))
        main = (ROOT / "main.py").read_text(encoding="utf-8")
        self.assertIn('settings.get("player.resume")', main)
        self.assertNotIn("get_preference('resume_playback'", main)

    def test_next_previous_and_media3_carry_episode_identity(self):
        main = (ROOT / "main.py").read_text(encoding="utf-8")
        player = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8")
        self.assertIn('episode_id=target.get("id")', main)
        self.assertIn('anime_id=target.get("anime_id")', main)
        self.assertIn("currentMediaId()", player)
        self.assertIn(".setMediaId(currentMediaId())", player)

    def test_explicit_exit_does_not_duplicate_pause_and_stop_saves(self):
        player = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8")
        pause = player[player.index("override fun onPause()"):player.index("override fun onStop()")]
        stop = player[player.index("override fun onStop()"):player.index("override fun onWindowFocusChanged")]
        self.assertIn("sessionState != SessionState.EXITING || !exitProgressPublished", pause)
        self.assertIn("sessionState != SessionState.EXITING || !exitProgressPublished", stop)
        self.assertIn("exitProgressPublished = true", player)
        self.assertIn("exitProgressPublished = false", player)
        self.assertIn("reportPlayerExit(reason)", player)

    def test_out_of_order_events_remain_rejected_after_switching_to_episode_id(self):
        ep = self.episode(self.anime_a, "content://stage10/order", 6, "stage10:order")
        self.assertTrue(self.store.save_progress(ep["path"], 80, 100, episode_id=ep["id"], event_created_at=2000))
        self.assertFalse(self.store.save_progress(None, 40, 100, episode_id=ep["id"], event_created_at=1500))
        self.assertEqual(80, self.store.physical_row(ep["path"])["progress"])

if __name__ == "__main__":
    unittest.main()
