import tempfile
import unittest
from pathlib import Path
from tests.native_main_contract import native_main_source


ROOT = Path(__file__).resolve().parents[1]
MAIN = native_main_source()


class ContinueWatchingTests(unittest.TestCase):
    def test_continue_watching_boundary_states_are_source_driven(self):
        from core.library_store import LibraryStore

        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("stage08", {"title": "earlier validation stage 08", "genres": "[]"})
            for number in range(1, 5):
                store.upsert_episode(
                    anime,
                    f"content://stage08/e0{number}",
                    f"E0{number}.mkv",
                    1,
                    number,
                    media_identity=f"stage08:e0{number}",
                )

            rows = [
                store.physical_row("content://stage08/e01"),
                store.physical_row("content://stage08/e02"),
                store.physical_row("content://stage08/e03"),
                store.physical_row("content://stage08/e04"),
            ]
            self.assertTrue(store.save_progress(rows[0]["path"], 0, 1000, episode_id=rows[0]["id"], event_created_at=1_000))
            self.assertTrue(store.save_progress(rows[1]["path"], 100, 1000, episode_id=rows[1]["id"], event_created_at=2_000))
            self.assertTrue(store.save_progress(rows[2]["path"], 899, 1000, episode_id=rows[2]["id"], event_created_at=3_000))
            self.assertTrue(store.save_progress(rows[3]["path"], 900, 1000, episode_id=rows[3]["id"], event_created_at=4_000))

            continuation_ids = [item["id"] for item in store.continue_watching(limit=10)]
            self.assertEqual([rows[2]["id"], rows[1]["id"]], continuation_ids)

    def test_continue_watching_persists_after_store_reopen(self):
        from core.library_store import LibraryStore

        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("fixture_08-reopen", {"title": "earlier validation stage 08 Reopen", "genres": "[]"})
            store.upsert_episode(
                anime,
                "content://fixture_08-reopen/e01",
                "E01.mkv",
                1,
                1,
                media_identity="fixture_08-reopen:e01",
            )
            episode = store.physical_row("content://fixture_08-reopen/e01")
            self.assertTrue(
                store.save_progress(
                    episode["path"],
                    450,
                    1000,
                    episode_id=episode["id"],
                    event_created_at=5_000,
                )
            )
            before = store.continue_watching(limit=10)
            self.assertEqual([episode["id"]], [item["id"] for item in before])
            reopened = LibraryStore(directory)
            after = reopened.continue_watching(limit=10)
            self.assertEqual([episode["id"]], [item["id"] for item in after])
            self.assertEqual(450, after[0]["progress"])

    def test_player_progress_publishes_compose_only_after_a_real_persistence_change(self):
        player = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8")
        consumption = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixConsumptionService.kt").read_text(encoding="utf-8")
        view_model = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt").read_text(encoding="utf-8")
        self.assertIn('saveProgress("player_progress"', player)
        self.assertIn("suspend fun saveProgress(", consumption)
        self.assertIn("UI_REFRESH_EVENT_TYPES", consumption)
        self.assertIn("ReiAnixConsumptionService.playbackChanges", view_model)
        self.assertIn("refreshNativeCatalog()", view_model)

    def test_progress_change_does_not_create_a_second_persistence_source(self):
        bridge = (ROOT / "core/compose_library_bridge.py").read_text(encoding="utf-8")
        self.assertIn('getattr(self.library, "continue_watching", None)', bridge)
        self.assertNotIn("CREATE TABLE", bridge)
        self.assertNotIn("sqlite3.connect", bridge)


if __name__ == "__main__":
    unittest.main()
