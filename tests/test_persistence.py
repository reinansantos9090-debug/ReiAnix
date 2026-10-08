import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "main.py").read_text(encoding="utf-8")


class DurableProgressTests(unittest.TestCase):
    def test_dispatcher_allows_durable_replay_when_no_live_player_exists(self):
        start = MAIN.index("elif event_type in {'player_progress', 'player_paused', 'player_completed'}:")
        end = MAIN.index("elif event_type == 'player_mark_watched':", start)
        block = MAIN[start:end]
        self.assertGreaterEqual(
            block.count('require_active=bool(player_session_active["value"])'),
            2,
        )

    def test_one_episode_replay_after_process_restart_is_persisted(self):
        from core.library_store import LibraryStore

        with tempfile.TemporaryDirectory() as directory:
            first = LibraryStore(directory)
            anime = first.upsert_anime("fixture_51-one", {"title": "earlier validation stage 5.1 One", "genres": "[]"})
            first.upsert_episode(
                anime,
                "content://stage51/e01",
                "E01.mkv",
                1,
                1,
                media_identity="stage51:e01",
            )
            episode = first.physical_row("content://stage51/e01")

            # The native durable event survives in the mailbox while Python is
            # restarted before it can consume it. The new process has no live
            # playback session in memory.
            restarted = LibraryStore(directory)
            self.assertTrue(
                restarted.save_progress(
                    episode["path"],
                    200,
                    1000,
                    episode_id=episode["id"],
                    event_created_at=2_000,
                    session_id="session-from-previous-process",
                )
            )
            restored = restarted.physical_row(episode["path"])
            self.assertEqual(200, restored["progress"])
            self.assertEqual(1000, restored["duration"])

    def test_five_episodes_keep_independent_progress_across_restart(self):
        from core.library_store import LibraryStore

        with tempfile.TemporaryDirectory() as directory:
            first = LibraryStore(directory)
            episodes = []
            for index, progress in enumerate((100, 200, 300, 400, 500), start=1):
                anime = first.upsert_anime(
                    f"fixture_51-five-{index}",
                    {"title": f"earlier validation stage 5.1 {index}", "genres": "[]"},
                )
                first.upsert_episode(
                    anime,
                    f"content://stage51/e0{index}",
                    f"E0{index}.mkv",
                    1,
                    index,
                    media_identity=f"stage51:e0{index}",
                )
                episodes.append(first.physical_row(f"content://stage51/e0{index}"))

            restarted = LibraryStore(directory)
            for index, (episode, progress) in enumerate(
                zip(episodes, (100, 200, 300, 400, 500)),
                start=1,
            ):
                self.assertTrue(
                    restarted.save_progress(
                        episode["path"],
                        progress,
                        1000,
                        episode_id=episode["id"],
                        event_created_at=10_000 + index,
                        session_id=f"old-session-{index}",
                    )
                )

            rows = [restarted.episode_by_id(episode["id"]) for episode in episodes]
            self.assertEqual([100, 200, 300, 400, 500], [row["progress"] for row in rows])
            self.assertEqual(
                {episode["id"] for episode in episodes},
                {row["id"] for row in restarted.continue_watching(limit=10)},
            )

    def test_replay_is_idempotent_and_stale_event_cannot_regress_progress(self):
        from core.library_store import LibraryStore

        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("fixture_51-order", {"title": "earlier validation stage 5.1 Order", "genres": "[]"})
            store.upsert_episode(
                anime,
                "content://stage51/order",
                "Order.mkv",
                1,
                1,
                media_identity="stage51:order",
            )
            episode = store.physical_row("content://stage51/order")

            self.assertTrue(
                store.save_progress(
                    episode["path"],
                    500,
                    1000,
                    episode_id=episode["id"],
                    event_created_at=2_000,
                    session_id="session-b",
                )
            )
            self.assertFalse(
                store.save_progress(
                    episode["path"],
                    500,
                    1000,
                    episode_id=episode["id"],
                    event_created_at=2_000,
                    session_id="session-b",
                )
            )
            self.assertFalse(
                store.save_progress(
                    episode["path"],
                    200,
                    1000,
                    episode_id=episode["id"],
                    event_created_at=1_000,
                    session_id="session-a",
                )
            )
            self.assertEqual(500, store.physical_row(episode["path"])["progress"])

    def test_episode_id_and_path_mismatch_is_rejected(self):
        from core.library_store import LibraryStore

        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("fixture_51-identity", {"title": "earlier validation stage 5.1 Identity", "genres": "[]"})
            store.upsert_episode(
                anime,
                "content://stage51/identity-e01",
                "E01.mkv",
                1,
                1,
                media_identity="stage51:identity-e01",
            )
            store.upsert_episode(
                anime,
                "content://stage51/identity-e02",
                "E02.mkv",
                1,
                2,
                media_identity="stage51:identity-e02",
            )
            first = store.physical_row("content://stage51/identity-e01")
            second = store.physical_row("content://stage51/identity-e02")

            self.assertFalse(
                store.save_progress(
                    second["path"],
                    700,
                    1000,
                    episode_id=first["id"],
                    event_created_at=3_000,
                    session_id="session-x",
                )
            )
            self.assertEqual(0, store.physical_row(first["path"])["progress"])
            self.assertEqual(0, store.physical_row(second["path"])["progress"])


if __name__ == "__main__":
    unittest.main()
