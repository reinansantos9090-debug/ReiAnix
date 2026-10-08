import tempfile
import unittest

from core.collector_journey import build_collector_journey
from core.library_service import LibraryService
from core.library_store import LibraryStore
from core.navigation import NavigationController


def row(anime_id, *, watched=False, progress=0, duration=100, year=2020, genres=None,
        last_played_at=None, format="TV", media_kind="series"):
    return {
        "id": f"{anime_id}-{progress}-{watched}",
        "anime_id": anime_id,
        "progress": progress,
        "duration": duration,
        "watched": watched,
        "missing": 0,
        "last_played_at": last_played_at,
        "episode_type": "regular",
        "anime_title": f"Anime {anime_id}",
        "year": year,
        "genres": genres or ["Action"],
        "format": format,
        "media_kind": media_kind,
    }


class CollectorJourneyAlgorithmTests(unittest.TestCase):
    def test_zero_state_is_safe_and_deterministic(self):
        first = build_collector_journey([])
        second = build_collector_journey([])
        self.assertEqual(first, second)
        self.assertEqual(0, first["xp"])
        self.assertEqual(1, first["level"])
        self.assertEqual(0, first["achievements_unlocked"])
        self.assertIsNone(first["active_title"])

    def test_one_episode_unlocks_first_step_and_one_xp(self):
        journey = build_collector_journey([row(1, watched=True, duration=120, last_played_at=1700000000)])
        self.assertEqual(1, journey["xp"])
        self.assertEqual(1, journey["episodes_completed"])
        self.assertEqual(1, journey["animes_completed"])
        self.assertEqual("Primeiro Passo", next(a for a in journey["achievements"] if a["id"] == "first_episode")["title"])
        self.assertTrue(next(a for a in journey["achievements"] if a["id"] == "first_episode")["unlocked"])

    def test_level_boundaries_are_exact(self):
        at_9 = build_collector_journey([row(1, watched=True) for _ in range(9)])
        at_10 = build_collector_journey([row(1, watched=True) for _ in range(10)])
        at_50 = build_collector_journey([row(i, watched=True) for i in range(50)])
        at_100 = build_collector_journey([row(i, watched=True) for i in range(100)])
        self.assertEqual(1, at_9["level"])
        self.assertEqual(2, at_10["level"])
        self.assertEqual(6, at_50["level"])
        self.assertEqual(11, at_100["level"])
        self.assertTrue(next(a for a in at_50["achievements"] if a["id"] == "episodes_50")["unlocked"])
        self.assertTrue(next(a for a in at_100["achievements"] if a["id"] == "episodes_100")["unlocked"])

    def test_exploration_metrics_use_completed_titles_only(self):
        rows = [
            row(1, watched=True, year=1995, genres=["Action", "Drama"], format="TV"),
            row(2, watched=True, year=2005, genres=["Comedy"], format="Movie", media_kind="movie"),
            row(3, watched=True, year=2015, genres=["Fantasy"], format="OVA"),
            row(4, watched=False, progress=20, year=1985, genres=["Romance"], format="TV"),
        ]
        journey = build_collector_journey(rows)
        self.assertEqual(3, journey["animes_completed"])
        self.assertEqual(4, journey["library_titles"])
        self.assertEqual(4, journey["genres_explored"])
        self.assertEqual(3, journey["decades_explored"])
        self.assertEqual(3, journey["formats_explored"])

    def test_active_days_require_meaningful_consumption_and_do_not_count_app_open(self):
        rows = [
            row(1, watched=True, last_played_at=1700000000),
            row(2, watched=False, progress=10, duration=100, last_played_at=1700086400),
            row(3, watched=False, progress=2, duration=100, last_played_at=1700172800),
            row(4, watched=False, progress=0, duration=100, last_played_at=1700259200),
        ]
        journey = build_collector_journey(rows)
        # First two meet the activity rule; 2% progress and zero progress do not.
        self.assertEqual(2, journey["active_days"])

    def test_rebuild_from_same_canonical_rows_is_identical(self):
        rows = [
            row(1, watched=True, year=2001, genres=["Action", "Comedy"], last_played_at=1700000000),
            row(1, watched=True, year=2001, genres=["Action", "Comedy"], last_played_at=1700086400),
            row(2, watched=True, year=2011, genres=["Drama"], last_played_at=1700172800),
        ]
        first = build_collector_journey(rows)
        second = build_collector_journey(rows)
        self.assertEqual(first, second)

    def test_corrupt_active_title_rebuilds_to_first_unlocked_title(self):
        journey = build_collector_journey([row(1, watched=True)])
        rebuilt = build_collector_journey([row(1, watched=True)], active_title="does-not-exist")
        self.assertEqual(journey["active_title"], rebuilt["active_title"])
        self.assertTrue(rebuilt["active_title"])

    def test_genre_niche_title_is_objective_and_deterministic(self):
        rows = [
            row(1, watched=True, genres=["Comedy"]),
            row(2, watched=True, genres=["Comedy"]),
            row(3, watched=True, genres=["Comedy"]),
        ]
        journey = build_collector_journey(rows)
        title = next(item for item in journey["titles"] if item["id"] == "genre_comedy")
        self.assertTrue(title["unlocked"])
        self.assertIn("3 títulos", title["condition"])


class CollectorJourneyStoreTests(unittest.TestCase):
    def test_schema_version_is_unchanged_and_no_gamification_tables_exist(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            with store._conn() as connection:
                version = connection.execute("SELECT MAX(version) FROM schema_migrations").fetchone()[0]
                tables = {
                    row[0]
                    for row in connection.execute(
                        "SELECT name FROM sqlite_master WHERE type='table'"
                    ).fetchall()
                }
            self.assertEqual(29, int(version))
            self.assertNotIn("gamification", tables)
            self.assertNotIn("achievements", tables)
            self.assertNotIn("collector", tables)

    def test_uses_existing_database_and_preferences_only(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("one", {"title": "One", "year": 2024, "genres": '["Action"]'})
            path = "/library/one.mkv"
            store.upsert_episode(anime, path, "One.mkv", 1, 1)
            store.save_progress(path, 90, 100)

            service = LibraryService(store)
            journey = service.collector_journey()
            self.assertEqual(1, journey["episodes_completed"])
            self.assertEqual(1, journey["animes_completed"])

            selected = journey["active_title"]
            self.assertIsNotNone(selected)
            service.set_collector_active_title(selected)
            self.assertEqual(selected, store.get_preference("collector.active_title"))

            self.assertFalse((__import__("pathlib").Path(directory) / "gamification.db").exists())
            self.assertFalse((__import__("pathlib").Path(directory) / "achievements.db").exists())
            self.assertFalse((__import__("pathlib").Path(directory) / "collector.db").exists())

    def test_collector_does_not_change_consumption(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("one", {"title": "One", "genres": '["Action"]'})
            path = "/library/one.mkv"
            store.upsert_episode(anime, path, "One.mkv", 1, 1)
            store.save_progress(path, 50, 100)
            before = dict(store.physical_row(path))
            service = LibraryService(store)
            service.collector_journey()
            service.collector_journey()
            after = dict(store.physical_row(path))
            self.assertEqual(before["progress"], after["progress"])
            self.assertEqual(before["watched"], after["watched"])
            self.assertEqual(before["last_played_at"], after["last_played_at"])

    def test_navigation_accepts_collector_as_top_level_screen(self):
        navigation = NavigationController()
        navigation.push("collector")
        self.assertEqual("collector", navigation.current)


class CollectorJourneyUiContractTests(unittest.TestCase):
    def test_collector_view_has_single_vertical_scroll_and_focusable_title_actions(self):
        from pathlib import Path
        source = (Path(__file__).resolve().parents[1] / "views" / "collector_view.py").read_text(encoding="utf-8")
        self.assertEqual(1, source.count("scroll=ft.ScrollMode.AUTO"))
        self.assertIn("focus_button_style", source)
        self.assertIn("D-pad", source) if "D-pad" in source else self.assertIn("OutlinedButton", source)

    def test_main_and_home_are_wired_without_player_changes(self):
        from pathlib import Path
        root = Path(__file__).resolve().parents[1]
        main = (root / "main.py").read_text(encoding="utf-8")
        home = (root / "views" / "home_view.py").read_text(encoding="utf-8")
        self.assertIn('"collector": "/collector"', main)
        self.assertIn("navigate_collector", main)
        self.assertIn('on_open_collector=navigate_collector', main)
        self.assertIn('"🏆 Collector"', home)
        player = (root / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "NativePlayerActivity.kt").read_text(encoding="utf-8")
        self.assertNotIn("CollectorActivity", player)


if __name__ == "__main__":
    unittest.main()
