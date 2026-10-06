import tempfile
import unittest
from pathlib import Path

from core.consumption import is_in_progress, playback_action
from core.library_store import LibraryStore


ROOT = Path(__file__).resolve().parents[1]


class DetailsSemanticsTests(unittest.TestCase):
    def _episode(self, store, anime_id, path, number):
        store.upsert_episode(
            anime_id,
            path,
            Path(path).name,
            1,
            number,
            media_identity=f"stage53:{Path(path).name}",
        )
        return store.physical_row(path)

    def test_next_episode_is_not_a_continue_without_progress(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime(
                "fixture_53-next",
                {"title": "earlier validation stage 5.3 Next", "genres": "[]"},
            )
            first = self._episode(store, anime, "content://stage53/e01", 1)
            current = store.current_episode(anime)

            self.assertEqual(first["id"], current["id"])
            self.assertFalse(is_in_progress(current))
            self.assertEqual("watch", playback_action(current))

    def test_partial_current_episode_is_a_real_continue_target(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime(
                "fixture_53-partial",
                {"title": "earlier validation stage 5.3 Partial", "genres": "[]"},
            )
            first = self._episode(store, anime, "content://stage53/p01", 1)
            second = self._episode(store, anime, "content://stage53/p02", 2)
            self.assertTrue(
                store.save_progress(
                    second["path"],
                    20,
                    100,
                    episode_id=second["id"],
                    event_created_at=200,
                )
            )

            current = store.current_episode(anime)
            self.assertEqual(second["id"], current["id"])
            self.assertTrue(is_in_progress(current))
            self.assertEqual("continue", playback_action(current))

    def test_details_continue_section_requires_real_in_progress_state(self):
        source = (ROOT / "views" / "details_view.py").read_text(encoding="utf-8")
        block_start = source.index("progress_bars = []")
        block_end = source.index("additional = []", block_start)
        block = source[block_start:block_end]

        self.assertIn("if current and is_in_progress(current):", block)
        self.assertNotIn(
            "if current:\n            current_ratio = ratio(current)",
            block,
        )

    def test_details_primary_button_keeps_action_semantics_separate_from_current_episode(self):
        source = (ROOT / "views" / "details_view.py").read_text(encoding="utf-8")
        primary_start = source.index("primary_ratio = ratio(primary_target)")
        primary_end = source.index("metadata_status =", primary_start)
        block = source[primary_start:primary_end]

        self.assertIn("primary_action = playback_action(primary_target)", block)
        self.assertIn('primary_action == "continue"', block)
        self.assertIn('primary_action == "watch"', block)
        self.assertIn("elif is_next_after_completion:", block)


if __name__ == "__main__":
    unittest.main()
