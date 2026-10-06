import tempfile
import unittest
from pathlib import Path

from core.consumption import playback_action
from core.library_store import LibraryStore


class LibraryExperienceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = LibraryStore(self.tmp.name)
        self.series = self.store.upsert_anime("series-case", {"title": "Library series case", "genres": "[]"})

    def tearDown(self):
        self.tmp.cleanup()

    def episode(self, path, season, number, *, episode_type="regular", missing=False):
        self.store.upsert_episode(self.series, path, Path(path).name + ".mkv", season, number, episode_type=episode_type)
        if missing:
            with self.store._conn() as con:
                con.execute("UPDATE episodes SET missing=1 WHERE path=?", (path,))
        return path

    def test_season_projection_exposes_consistent_counts_without_new_schema(self):
        e1 = self.episode("content://p14/s1e1", 1, 1)
        self.episode("content://p14/s1e2", 1, 2)
        self.episode("content://p14/s1e3", 1, 3, missing=True)
        self.store.save_progress(e1, 90, 100)
        season = self.store.catalog()[0]["seasons"][0]
        self.assertEqual(2, season["available_count"])
        self.assertEqual(1, season["watched_count"])
        self.assertEqual(1, season["remaining_count"])
        self.assertEqual(0, season["active_count"])
        self.assertEqual(0.5, season["progress_ratio"])
        self.assertEqual(29, self.store.SCHEMA_VERSION)

    def test_movie_is_first_class_and_never_enters_episode_navigation(self):
        movie = self.store.upsert_anime("movie-case", {"title": "Movie", "genres": "[]", "media_kind": "movie"})
        path = "content://p14/movie"
        self.store.upsert_episode(movie, path, "Movie.mkv", 0, 1, episode_type="movie")
        self.store.save_progress(path, 50, 100)
        catalog = [item for item in self.store.catalog() if item["id"] == movie][0]
        self.assertEqual("movie", catalog["media_kind"])
        self.assertEqual(path, catalog["media_files"][0]["path"])
        self.assertIsNone(self.store.next_episode(path))
        self.assertIsNone(self.store.previous_episode(path))

    def test_all_special_kinds_remain_outside_regular_navigation(self):
        for index, kind in enumerate(("special", "ova", "oad", "ona", "extra"), 1):
            self.episode(f"content://p14/{kind}", 1, index, episode_type=kind)
        regular = self.episode("content://p14/regular", 1, 20)
        self.assertIsNone(self.store.previous_episode(regular))
        special_group = self.store.catalog()[0]["specials"][0]["episodes"]
        self.assertEqual({"special", "ova", "oad", "ona", "extra"}, {item["episode_type"] for item in special_group})

    def test_replay_does_not_move_sequence_continuation_backwards(self):
        e1 = self.episode("content://p14/e1", 1, 1)
        e2 = self.episode("content://p14/e2", 1, 2)
        e3 = self.episode("content://p14/e3", 1, 3)
        self.store.save_progress(e1, 100, 100)
        self.store.save_progress(e2, 100, 100)
        self.store.save_progress(e1, 20, 100)
        self.assertEqual(e3, self.store.playback_target(self.series)["path"])

    def test_playback_action_matches_the_central_consumption_policy(self):
        self.assertEqual("watch", playback_action({"progress": 0, "duration": 100, "watched": False, "missing": False}))
        self.assertEqual("continue", playback_action({"progress": 50, "duration": 100, "watched": False, "missing": False}))
        self.assertEqual("replay", playback_action({"progress": 90, "duration": 100, "watched": False, "missing": False}))
        self.assertEqual("replay", playback_action({"progress": 0, "duration": 0, "watched": True, "missing": False}))
        self.assertEqual("unavailable", playback_action({"progress": 50, "duration": 100, "watched": False, "missing": True}))

    def test_movie_details_uses_movie_artwork_entity_contract(self):
        details = (Path(__file__).resolve().parents[1] / "views" / "details_view.py").read_text(encoding="utf-8")
        self.assertIn('artwork_entity = "movie" if is_movie else "anime"', details)
        self.assertIn('resolve_artwork(\n                artwork_entity,\n                anime_group["id"],\n                "poster",', details)

    def test_movie_artwork_discovery_uses_movie_entity(self):
        artwork = (Path(__file__).resolve().parents[1] / "core" / "artwork.py").read_text(encoding="utf-8")
        self.assertIn('entity_type = "movie" if anime and str(anime["media_kind"] or "series").casefold() == "movie" else "anime"', artwork)
        self.assertIn('self.add_local(entity_type, anime_id, kind, candidate)', artwork)

    def test_continue_watching_home_keeps_details_access_and_explicit_continue_action(self):
        home = (Path(__file__).resolve().parents[1] / "views" / "home_view.py").read_text(encoding="utf-8")
        self.assertIn("request_continuation_details(entry)", home)
        self.assertIn("library.catalog_by_ids", home)
        self.assertRegex(home, r'ft\.(?:OutlinedButton|TextButton)\(\s*"Continuar"')

    def test_home_exposes_all_consumption_state_filters(self):
        home = (Path(__file__).resolve().parents[1] / "views" / "home_view.py").read_text(encoding="utf-8")
        for state in ("Todos", "Favoritos", "Fixados", "Não assistidos", "Em andamento", "Concluídos", "Assistidos"):
            self.assertIn('ft.dropdown.Option(v)', home)
            self.assertIn(state, home)

    def test_home_filters_have_clear_all_action(self):
        home = (Path(__file__).resolve().parents[1] / "views" / "home_view.py").read_text(encoding="utf-8")
        self.assertIn("async def clear_filters", home)
        self.assertIn('selected_sort[0] = "Mais recentes"', home)
        self.assertIn('search.value = ""', home)
        self.assertIn('ft.TextButton("Limpar"', home)


if __name__ == "__main__":
    unittest.main()
