import tempfile
import unittest
from pathlib import Path

from core.library_discovery import duration_anomalies, marathon_plan, timeline_groups
from core.library_store import LibraryStore


class SmartLibraryAlgorithmsTests(unittest.TestCase):
    def test_timeline_groups_orders_known_years_and_puts_unknown_last(self):
        rows = [
            {"id": 1, "title": "A", "year": 2024},
            {"id": 2, "title": "B", "year": None},
            {"id": 3, "title": "C", "year": 2026},
            {"id": 4, "title": "D", "year": 2024},
        ]
        groups = timeline_groups(rows)
        self.assertEqual([2026, 2024, None], [group["year"] for group in groups])
        self.assertEqual(["A", "D"], [item["title"] for item in groups[1]["items"]])
        self.assertEqual("Ano desconhecido", groups[-1]["label"])

    def test_duration_anomalies_is_conservative_and_robust(self):
        baseline = [
            {"anime_id": 1, "anime_title": "Normal", "season": 1, "number": i, "duration": duration}
            for i, duration in enumerate((20 * 60, 21 * 60, 22 * 60, 21 * 60, 22 * 60), 1)
        ]
        normal = duration_anomalies(baseline)
        self.assertEqual(0, normal["anomaly_count"])

        unusual = baseline[:-2] + [
            {"anime_id": 1, "anime_title": "Normal", "season": 1, "number": 4, "duration": 45 * 60},
            {"anime_id": 1, "anime_title": "Normal", "season": 1, "number": 5, "duration": 22 * 60},
        ]
        report = duration_anomalies(unusual)
        self.assertEqual(1, report["anomaly_count"])
        anomaly = report["items"][0]["anomalies"][0]
        self.assertEqual("long", anomaly["direction"])
        self.assertIn("Duração", anomaly["label"])

        insufficient = duration_anomalies(unusual[:1])
        self.assertEqual(1, insufficient["series_with_insufficient_data"])
        self.assertEqual(0, insufficient["anomaly_count"])

    def test_marathon_uses_remaining_time_for_current_episode(self):
        rows = [
            {"path": "e1", "season": 1, "number": 1, "duration": 20 * 60, "progress": 20 * 60, "watched": 1},
            {"path": "e2", "season": 1, "number": 2, "duration": 23 * 60, "progress": 12 * 60, "watched": 0},
            {"path": "e3", "season": 1, "number": 3, "duration": 22 * 60, "progress": 0, "watched": 0},
        ]
        plan = marathon_plan(rows, current_path="e2")
        self.assertEqual(2, plan["episode_count"])
        self.assertEqual(11 * 60, plan["items"][0]["remaining_seconds"])
        self.assertEqual(22 * 60, plan["items"][1]["remaining_seconds"])
        self.assertEqual(33 * 60, plan["known_duration_seconds"])

    def test_store_queries_feed_discovery_without_a_second_database(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            first = store.upsert_anime("first", {"title": "First", "genres": "[]", "year": 2024})
            second = store.upsert_anime("second", {"title": "Second", "genres": "[]", "year": 2026})
            first_path = "/library/first-01.mkv"
            second_path = "/library/second-01.mkv"
            store.upsert_episode(first, first_path, "First 01.mkv", 1, 1)
            store.upsert_episode(second, second_path, "Second 01.mkv", 1, 1)
            store.save_progress(first_path, 0, 20 * 60)
            store.save_progress(second_path, 0, 45 * 60)

            self.assertEqual(2, len(store.timeline_items()))
            self.assertEqual(2, len(store.duration_observations()))
            self.assertEqual(1, len(store.marathon_episodes(first)))
            self.assertTrue((Path(directory) / "library.sqlite3").exists())
            self.assertFalse((Path(directory) / "gacha.db").exists())
            self.assertFalse((Path(directory) / "timeline.db").exists())
            self.assertFalse((Path(directory) / "marathon.db").exists())


class SmartLibraryIntegrationTests(unittest.TestCase):
    def test_ui_wires_all_tools_without_secondary_storage(self):
        root = Path(__file__).resolve().parents[1]
        home = (root / "views" / "home_view.py").read_text(encoding="utf-8")
        details = (root / "views" / "details_view.py").read_text(encoding="utf-8")
        ui = (root / "core" / "ui.py").read_text(encoding="utf-8")
        service = (root / "core" / "library_service.py").read_text(encoding="utf-8")

        for token in ("open_gacha", "open_timeline", "open_duration_anomalies", "smart_tools_row"):
            self.assertIn(token, home)
        for token in ("spoiler_artwork", "on_open_marathon", "Maratona"):
            self.assertIn(token, details)
        for token in ("GestureDetector", "on_long_press_start", "on_long_press_end", "blur=14"):
            self.assertIn(token, ui)
        for token in ("gacha_pick", "library_timeline", "duration_anomaly_report", "marathon"):
            self.assertIn(token, service)
        combined = home + details + ui + service
        for token in ("gacha.db", "timeline.db", "marathon.db", "smart_library.db"):
            self.assertNotIn(token, combined)

    def test_gacha_respects_filters_and_avoids_immediate_repetition_when_possible(self):
        with tempfile.TemporaryDirectory() as directory:
            from core.library_service import LibraryService

            store = LibraryStore(directory)
            first = store.upsert_anime("first", {"title": "First", "genres": "[]"})
            second = store.upsert_anime("second", {"title": "Second", "genres": "[]"})
            store.upsert_episode(first, "/library/first.mkv", "First.mkv", 1, 1)
            store.upsert_episode(second, "/library/second.mkv", "Second.mkv", 1, 1)
            service = LibraryService(store)

            first_pick = service.gacha_pick(filters={"state": "Todos"})
            self.assertIsNotNone(first_pick)
            second_pick = service.gacha_pick(filters={"state": "Todos"}, exclude_id=first_pick["anime"]["id"])
            self.assertIsNotNone(second_pick)
            self.assertNotEqual(first_pick["anime"]["id"], second_pick["anime"]["id"])

            filtered = service.gacha_pick(filters={"query": "First"})
            self.assertEqual(first, filtered["anime"]["id"])

    def test_gacha_does_not_change_consumption_state(self):
        with tempfile.TemporaryDirectory() as directory:
            from core.library_service import LibraryService

            store = LibraryStore(directory)
            anime = store.upsert_anime("first", {"title": "First", "genres": "[]"})
            path = "/library/first.mkv"
            store.upsert_episode(anime, path, "First.mkv", 1, 1)
            store.save_progress(path, 300, 1200)
            before = store.current_episode(anime)
            service = LibraryService(store)

            service.gacha_pick()

            after = store.current_episode(anime)
            self.assertEqual(before["progress"], after["progress"])
            self.assertEqual(before["watched"], after["watched"])


if __name__ == "__main__":
    unittest.main()
