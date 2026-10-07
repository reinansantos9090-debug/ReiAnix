import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "main.py").read_text(encoding="utf-8")


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
        start = MAIN.index("elif event_type in {'player_progress', 'player_paused', 'player_completed'}:")
        end = MAIN.index("elif event_type == 'player_error':", start)
        block = MAIN[start:end]
        publish = "compose_library_bridge.request_publish("
        reason = 'f"player_progress:{payload.get('episodeId') or 0}"'
        self.assertIn(publish, block)
        self.assertIn(reason, block)
        publish_pos = block.index(publish)
        reason_pos = block.index(reason)
        self.assertIn("if updated:", block[max(0, reason_pos - 320):reason_pos + len(reason)])
        self.assertEqual(block.count(reason), 1)

    def test_progress_change_does_not_create_a_second_persistence_source(self):
        bridge = (ROOT / "core/compose_library_bridge.py").read_text(encoding="utf-8")
        self.assertIn('getattr(self.library, "continue_watching", None)', bridge)
        self.assertNotIn("CREATE TABLE", bridge)
        self.assertNotIn("sqlite3.connect", bridge)


if __name__ == "__main__":
    unittest.main()
