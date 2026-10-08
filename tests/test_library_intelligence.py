import tempfile
import unittest
from core.library_store import LibraryStore
from core.library_service import LibraryService

class LibraryIntelligenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = LibraryStore(self.tmp.name)
        self.anime = self.store.upsert_anime("demo", {"title": "Demo", "genres": "[]"})
        self.store.upsert_episode(self.anime, "content://demo/1", "Demo E01.mkv", 1, 1)
        self.service = LibraryService(self.store)
    def tearDown(self): self.tmp.cleanup()

    def test_pin_note_and_reopen_are_private_and_durable(self):
        self.assertTrue(self.store.toggle_pinned(self.anime))
        self.assertEqual("assistir com Ana", self.store.set_personal_note(self.anime, "  assistir com Ana  "))
        reopened = LibraryStore(self.tmp.name).catalog()[0]
        self.assertTrue(reopened["is_pinned"])
        self.assertEqual("assistir com Ana", reopened["personal_note"])
        self.assertFalse(self.store.toggle_pinned(self.anime))
        self.assertIsNone(self.store.set_personal_note(self.anime, "   "))

    def test_note_limit_and_filters_are_safe(self):
        with self.assertRaises(ValueError): self.store.set_personal_note(self.anime, "x" * 2001)
        self.store.toggle_favorite(self.anime); self.store.toggle_pinned(self.anime)
        self.store.set_user_tags(self.anime, ["Prioridade"]); self.store.set_personal_note(self.anime, "nota")
        catalog = self.store.catalog()
        self.assertEqual(1, len(self.service.browse_catalog(catalog, state="Fixados", tag="Prioridade")))
        self.assertEqual(1, len(self.service.browse_catalog(catalog, state="Com nota")))
        self.assertEqual(0, len(self.service.browse_catalog(catalog, tag="Sem etiqueta")))


    def test_media_center_home_sections_group_entities_and_preserve_state(self):
        self.store.save_progress("content://demo/1", 40, 100)
        self.store.toggle_favorite(self.anime)
        self.store.toggle_pinned(self.anime)
        home = self.service.media_center_home()
        self.assertEqual(
            {"continue_watching", "favorites", "pinned", "movies"},
            set(home),
        )
        self.assertEqual(1, len(home["continue_watching"]))
        self.assertEqual(1, len(home["favorites"]))
        self.assertEqual(1, len(home["pinned"]))
        self.assertEqual(0, len(home["movies"]))
        self.assertEqual("Demo", home["favorites"][0]["main_title"])
        self.assertEqual(1, home["favorites"][0]["active_count"])

    def test_continue_limit_does_not_change_other_home_section_limits(self):
        for index in range(1, 4):
            anime_id = self.store.upsert_anime(
                f"favorite-{index}",
                {"title": f"Favorite {index}", "genres": "[]"},
            )
            path = f"content://favorites/{index}"
            self.store.upsert_episode(anime_id, path, f"Favorite {index} E01.mkv", 1, 1)
            self.store.save_progress(path, 20, 100)
            self.store.toggle_favorite(anime_id)

        home = self.service.media_center_home(limit=3, continue_limit=1)
        self.assertEqual(1, len(home["continue_watching"]))
        self.assertEqual(3, len(home["favorites"]))

    def test_media_center_home_empty_is_safe(self):
        empty_tmp = tempfile.TemporaryDirectory()
        try:
            empty_service = LibraryService(LibraryStore(empty_tmp.name))
            home = empty_service.media_center_home()
            self.assertTrue(all(not value for value in home.values()))
        finally:
            empty_tmp.cleanup()

    def test_statistics_and_last_scan_use_only_local_rows(self):
        self.store.save_progress("content://demo/1", 100, 100)
        self.store.toggle_pinned(self.anime); self.store.set_personal_note(self.anime, "n")
        stats = self.store.library_statistics()
        self.assertEqual(1, stats["animes"]); self.assertEqual(1, stats["episodes_watched"])
        self.assertEqual(1, stats["pinned"]); self.assertEqual(1, stats["notes"])
        self.assertIsNone(self.store.last_scan())


    def test_consumption_states_cover_progress_boundaries(self):
        cases = [
            (0, 100, False, "unwatched"), (1, 100, False, "in_progress"),
            (50, 100, False, "in_progress"), (89, 100, False, "in_progress"),
            (90, 100, False, "completed"), (99, 100, False, "completed"),
            (100, 100, False, "completed"), (0, 0, False, "unwatched"),
            (25, 0, False, "in_progress"), (250, 100, False, "completed"),
            (0, 100, True, "watched"),
        ]
        for progress, duration, watched, expected in cases:
            episode = {"progress": progress, "duration": duration, "watched": watched, "missing": 0, "path": "x"}
            self.assertEqual(expected, self.store.consumption_state(episode))

    def test_continue_watching_excludes_completed_and_missing(self):
        self.store.upsert_episode(self.anime, "content://demo/2", "Demo E02.mkv", 1, 2)
        self.store.upsert_episode(self.anime, "content://demo/3", "Demo E03.mkv", 1, 3)
        self.store.save_progress("content://demo/1", 47, 100)
        self.store.save_progress("content://demo/2", 100, 100)
        self.store.save_progress("content://demo/3", 20, 100)
        rows = self.store.continue_watching()
        self.assertEqual(
            ["content://demo/3", "content://demo/1"],
            [row["path"] for row in rows],
        )

    def test_next_episode_remains_a_player_navigation_contract(self):
        self.store.upsert_episode(self.anime, "content://demo/2", "Demo E02.mkv", 1, 2)
        self.store.save_progress("content://demo/1", 100, 100)
        next_item = self.store.next_episode("content://demo/1")
        self.assertIsNotNone(next_item)
        self.assertEqual("content://demo/2", next_item["path"])

    def test_special_and_movie_never_become_regular_next_episode(self):
        self.store.upsert_episode(self.anime, "content://demo/special", "Demo OVA.mkv", 1, 99, episode_type="ova")
        self.store.upsert_episode(self.anime, "content://demo/movie", "Demo Movie.mkv", 1, 100, episode_type="movie")
        self.assertIsNone(self.store.next_episode("content://demo/special"))
        self.assertIsNone(self.store.next_episode("content://demo/movie"))

    def test_zero_duration_preserves_resume_without_marking_complete(self):
        self.assertTrue(self.store.save_progress("content://demo/1", 25, 0))
        row = self.store.catalog()[0]["seasons"][0]["episodes"][0]
        self.assertEqual(25, row["progress"])
        self.assertEqual(0, row["duration"])
        self.assertFalse(row["watched"])
        self.assertFalse(self.store.save_progress("content://demo/1", -1, 100))

if __name__ == "__main__": unittest.main()
