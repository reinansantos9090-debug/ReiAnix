import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class ContinueWatchingTests(unittest.TestCase):
    def _store(self, directory):
        from core.library_store import LibraryStore
        return LibraryStore(directory)

    def _episode(self, store, anime_id, path, number):
        store.upsert_episode(
            anime_id,
            path,
            Path(path).name,
            1,
            number,
            media_identity=f"stage52:{Path(path).name}",
        )
        return store.physical_row(path)

    def test_same_anime_keeps_multiple_resumable_episodes(self):
        with tempfile.TemporaryDirectory() as directory:
            store = self._store(directory)
            anime = store.upsert_anime("fixture_52-a", {"title": "earlier validation stage 5.2 A", "genres": "[]"})
            episodes = [
                self._episode(store, anime, f"content://stage52/a/e0{n}", n)
                for n in (1, 2, 3)
            ]
            for stamp, episode, progress in zip((100, 200, 300), episodes, (10, 20, 30)):
                self.assertTrue(
                    store.save_progress(
                        episode["path"],
                        progress,
                        100,
                        episode_id=episode["id"],
                        event_created_at=stamp,
                    )
                )

            rows = store.continue_watching(limit=10)
            self.assertEqual(
                [episodes[2]["id"], episodes[1]["id"], episodes[0]["id"]],
                [row["id"] for row in rows],
            )
            self.assertEqual([30, 20, 10], [row["progress"] for row in rows])
            self.assertEqual(
                [episodes[2]["id"], episodes[1]["id"], episodes[0]["id"]],
                [row["episode_id"] for row in rows],
            )

    def test_multiple_animes_keep_each_episode_as_an_independent_item(self):
        with tempfile.TemporaryDirectory() as directory:
            store = self._store(directory)
            anime_a = store.upsert_anime("fixture_52-b-a", {"title": "earlier validation stage 5.2 B A", "genres": "[]"})
            anime_b = store.upsert_anime("fixture_52-b-b", {"title": "earlier validation stage 5.2 B B", "genres": "[]"})
            a1 = self._episode(store, anime_a, "content://stage52/b/a1", 1)
            a2 = self._episode(store, anime_a, "content://stage52/b/a2", 2)
            b1 = self._episode(store, anime_b, "content://stage52/b/b1", 1)

            for stamp, episode in ((100, a1), (200, a2), (300, b1)):
                self.assertTrue(
                    store.save_progress(
                        episode["path"],
                        stamp // 10,
                        100,
                        episode_id=episode["id"],
                        event_created_at=stamp,
                    )
                )

            rows = store.continue_watching(limit=10)
            self.assertEqual(
                [b1["id"], a2["id"], a1["id"]],
                [row["id"] for row in rows],
            )
            self.assertEqual(
                {anime_a, anime_b},
                {row["anime_id"] for row in rows},
            )

    def test_completed_episode_is_excluded_without_hiding_other_episode(self):
        with tempfile.TemporaryDirectory() as directory:
            store = self._store(directory)
            anime = store.upsert_anime("fixture_52-c", {"title": "earlier validation stage 5.2 C", "genres": "[]"})
            completed = self._episode(store, anime, "content://stage52/c/e1", 1)
            active = self._episode(store, anime, "content://stage52/c/e2", 2)
            store.save_progress(completed["path"], 90, 100, episode_id=completed["id"], event_created_at=100)
            store.save_progress(active["path"], 20, 100, episode_id=active["id"], event_created_at=200)

            rows = store.continue_watching(limit=10)
            self.assertEqual([active["id"]], [row["id"] for row in rows])
            self.assertTrue(all(not row["watched"] for row in rows))

    def test_missing_episode_is_excluded_without_mutating_missing_state(self):
        with tempfile.TemporaryDirectory() as directory:
            store = self._store(directory)
            anime = store.upsert_anime("fixture_52-d", {"title": "earlier validation stage 5.2 D", "genres": "[]"})
            missing = self._episode(store, anime, "content://stage52/d/e1", 1)
            active = self._episode(store, anime, "content://stage52/d/e2", 2)
            store.save_progress(missing["path"], 20, 100, episode_id=missing["id"], event_created_at=100)
            store.save_progress(active["path"], 30, 100, episode_id=active["id"], event_created_at=200)
            with store._conn() as con:
                con.execute("UPDATE episodes SET missing=1 WHERE id=?", (missing["id"],))

            rows = store.continue_watching(limit=10)
            self.assertEqual([active["id"]], [row["id"] for row in rows])
            self.assertEqual(1, store.physical_row(missing["path"])["missing"])

    def test_activity_order_is_last_played_at_descending(self):
        with tempfile.TemporaryDirectory() as directory:
            store = self._store(directory)
            anime = store.upsert_anime("fixture_52-e", {"title": "earlier validation stage 5.2 E", "genres": "[]"})
            e1 = self._episode(store, anime, "content://stage52/e/e1", 1)
            e2 = self._episode(store, anime, "content://stage52/e/e2", 2)
            e3 = self._episode(store, anime, "content://stage52/e/e3", 3)
            for stamp, episode in ((100, e1), (200, e2), (150, e3)):
                store.save_progress(
                    episode["path"],
                    10,
                    100,
                    episode_id=episode["id"],
                    event_created_at=stamp,
                )

            rows = store.continue_watching(limit=10)
            self.assertEqual([e2["id"], e3["id"], e1["id"]], [row["id"] for row in rows])
            self.assertEqual([200, 150, 100], [row["last_played_at"] for row in rows])

    def test_limit_is_applied_after_episode_ordering(self):
        with tempfile.TemporaryDirectory() as directory:
            store = self._store(directory)
            anime = store.upsert_anime("fixture_52-f", {"title": "earlier validation stage 5.2 F", "genres": "[]"})
            episodes = [
                self._episode(store, anime, f"content://stage52/f/e{n}", n)
                for n in range(1, 6)
            ]
            for stamp, episode in enumerate(episodes, start=100):
                store.save_progress(
                    episode["path"],
                    10,
                    100,
                    episode_id=episode["id"],
                    event_created_at=stamp,
                )

            rows = store.continue_watching(limit=3)
            self.assertEqual([episodes[4]["id"], episodes[3]["id"], episodes[2]["id"]], [row["id"] for row in rows])

    def test_restart_preserves_all_resumable_episode_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            first = self._store(directory)
            anime = first.upsert_anime("fixture_52-g", {"title": "earlier validation stage 5.2 G", "genres": "[]"})
            episodes = [
                self._episode(first, anime, f"content://stage52/g/e{n}", n)
                for n in (1, 2, 3)
            ]
            for stamp, episode, progress in zip((100, 200, 300), episodes, (20, 30, 40)):
                first.save_progress(
                    episode["path"],
                    progress,
                    100,
                    episode_id=episode["id"],
                    event_created_at=stamp,
                )

            reopened = self._store(directory)
            rows = reopened.continue_watching(limit=10)
            self.assertEqual(
                [episodes[2]["id"], episodes[1]["id"], episodes[0]["id"]],
                [row["id"] for row in rows],
            )
            self.assertEqual([40, 30, 20], [row["progress"] for row in rows])
            self.assertEqual([300, 200, 100], [row["last_played_at"] for row in rows])

    def test_episode_rows_are_not_deduplicated_by_anime_or_title(self):
        with tempfile.TemporaryDirectory() as directory:
            store = self._store(directory)
            anime = store.upsert_anime("fixture_52-h", {"title": "Same Title", "genres": "[]"})
            first = self._episode(store, anime, "content://stage52/h/one", 1)
            second = self._episode(store, anime, "content://stage52/h/two", 2)
            store.save_progress(first["path"], 10, 100, episode_id=first["id"], event_created_at=100)
            store.save_progress(second["path"], 20, 100, episode_id=second["id"], event_created_at=200)

            rows = store.continue_watching(limit=10)
            self.assertEqual({first["id"], second["id"]}, {row["id"] for row in rows})
            self.assertEqual(2, len({row["episode_id"] for row in rows}))

    def test_static_contract_preserves_current_episode_separation(self):
        store_source = (ROOT / "core/library_store.py").read_text(encoding="utf-8")
        service_source = (ROOT / "core/library_service.py").read_text(encoding="utf-8")
        self.assertIn("def continue_watching(self, limit=12):", store_source)
        self.assertIn("def current_episode(self, anime_id):", store_source)
        self.assertNotIn("PARTITION BY e.anime_id", store_source)
        self.assertNotIn("resume_rank=1", store_source)
        self.assertIn("def continue_watching(self, limit=12): return self.store.continue_watching(limit)", service_source)


if __name__ == "__main__":
    unittest.main()
