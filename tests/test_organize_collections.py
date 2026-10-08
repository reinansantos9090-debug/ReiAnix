import unittest
from pathlib import Path

from core.library_service import LibraryService
from core.search_engine import LibrarySearchEngine


ROOT = Path(__file__).resolve().parents[1]
ORGANIZE = ROOT / "views/organize_view.py"
MAIN = ROOT / "main.py"


def episode(*, number, progress=0, duration=100, watched=False, file_name=None):
    return {
        "number": number,
        "season": 1,
        "progress": progress,
        "duration": duration,
        "watched": watched,
        "missing": False,
        "file_name": file_name or f"S01E{number:02d}.mkv",
        "episode_type": "regular",
        "last_played_at": number,
        "modified_at": number,
        "file_size": number * 100,
    }


def fixture_catalog():
    return [
        {
            "id": 1,
            "main_title": "Alpha",
            "favorite": True,
            "is_pinned": False,
            "genres": ["Action", "Drama"],
            "media_kind": "series",
            "meta": {"metadata_source": "local"},
            "seasons": [{"season": 1, "episodes": [episode(number=1, progress=50)]}],
            "specials": [],
            "media_files": [],
        },
        {
            "id": 2,
            "main_title": "Beta",
            "favorite": False,
            "is_pinned": True,
            "genres": ["action"],
            "media_kind": "series",
            "meta": {"metadata_source": "local"},
            "seasons": [{"season": 1, "episodes": [episode(number=1, watched=True)]}],
            "specials": [],
            "media_files": [],
        },
        {
            "id": 3,
            "main_title": "Gamma",
            "favorite": True,
            "is_pinned": True,
            "genres": ["Drama"],
            "media_kind": "series",
            "meta": {"metadata_source": "local"},
            "seasons": [{"season": 1, "episodes": [episode(number=1)]}],
            "specials": [],
            "media_files": [],
        },
    ]


class OrganizeCollectionTests(unittest.TestCase):
    def setUp(self):
        self.catalog = fixture_catalog()

    def ids(self, items):
        return [item["id"] for item in items]

    def test_all_returns_every_local_title(self):
        result = LibraryService.browse_catalog(self.catalog, state="Todos", sort="Nome A-Z")
        self.assertEqual(self.ids(result), [1, 2, 3])

    def test_main_state_categories_return_the_same_population_as_summary_counts(self):
        summary = LibraryService.organize_summary(self.catalog)
        counts = {item["name"]: item["count"] for item in summary["collections"]}
        expected = {
            "Todos": [1, 2, 3],
            "Favoritos": [1, 3],
            "Fixados": [2, 3],
            "Assistidos": [2],
            "Não assistidos": [3],
            "Em andamento": [1],
            "Concluídos": [2],
        }
        for state, expected_ids in expected.items():
            result = LibraryService.browse_catalog(self.catalog, state=state, sort="Nome A-Z")
            self.assertEqual(self.ids(result), expected_ids)
            self.assertEqual(counts[state], len(expected_ids))

    def test_existing_genres_group_case_variants_without_creating_new_registry(self):
        summary = LibraryService.organize_summary(self.catalog)
        genres = {item["name"].casefold(): item["count"] for item in summary["genres"]}
        self.assertEqual(genres["action"], 2)
        self.assertEqual(genres["drama"], 2)
        result = LibraryService.browse_catalog(self.catalog, genre="ACTION", sort="Nome A-Z")
        self.assertEqual(self.ids(result), [1, 2])

    def test_combined_filters_intersect(self):
        result = LibraryService.browse_catalog(
            self.catalog,
            query="alpha",
            state="Favoritos",
            genre="Action",
            sort="Nome A-Z",
        )
        self.assertEqual(self.ids(result), [1])

    def test_clear_filters_restores_the_full_population(self):
        filtered = LibraryService.browse_catalog(
            self.catalog,
            query="alpha",
            state="Favoritos",
            genre="Action",
            sort="Nome A-Z",
        )
        restored = LibraryService.browse_catalog(
            self.catalog,
            query="",
            state="Todos",
            genre="Todos",
            sort="Mais recentes",
        )
        self.assertEqual(len(filtered), 1)
        self.assertEqual(len(restored), 3)

    def test_local_library_does_not_require_anilist_metadata(self):
        result = LibraryService.browse_catalog(
            self.catalog,
            query="gamma",
            state="Todos",
            genre="Todos",
            sort="Nome A-Z",
        )
        self.assertEqual(self.ids(result), [3])
        self.assertIsNone(self.catalog[2]["meta"].get("anilist_id"))

    def test_none_of_the_collection_states_turn_empty_results_into_errors(self):
        result = LibraryService.browse_catalog(
            self.catalog,
            query="does-not-exist",
            state="Todos",
            genre="Todos",
            sort="Nome A-Z",
        )
        self.assertEqual(result, [])

    def test_sorting_is_deterministic(self):
        asc = LibraryService.browse_catalog(self.catalog, sort="Nome A-Z")
        desc = LibraryService.browse_catalog(self.catalog, sort="Nome Z-A")
        self.assertEqual(self.ids(asc), [1, 2, 3])
        self.assertEqual(self.ids(desc), [3, 2, 1])

    def test_search_engine_supports_pin_and_consumption_states_used_by_organize(self):
        for state in (
            "Fixados",
            "Assistidos",
            "Não assistidos",
            "Em andamento",
            "Concluídos",
        ):
            self.assertIn(state, LibrarySearchEngine.options(self.catalog)["states"])


class OrganizeHandlerContractTests(unittest.TestCase):
    def test_parameterized_category_clicks_use_real_async_handlers(self):
        source = ORGANIZE.read_text(encoding="utf-8")
        self.assertIn("async def handle(_event):", source)
        self.assertIn("page.run_task", source)
        self.assertNotIn("lambda _, value=label: open_collection", source)
        self.assertNotIn("lambda _, value=label: page.run_task", source)

    def test_card_clicks_do_not_return_an_unawaited_coroutine(self):
        source = ORGANIZE.read_text(encoding="utf-8")
        self.assertIn("def make_anime_click_handler(anime):", source)
        self.assertIn("def make_anime_click_handler(anime):", source)
        self.assertIn("_start_view_task(invoke)", source)
        self.assertIn("return None", source)

    def test_collection_render_has_generation_guard_for_rapid_filter_changes(self):
        source = ORGANIZE.read_text(encoding="utf-8")
        self.assertIn("render_generation", source)
        self.assertIn("token != render_generation[0]", source)

    def test_organize_load_only_reads_catalog_and_scan_state(self):
        source = ORGANIZE.read_text(encoding="utf-8")
        load = source[source.index("async def load_catalog"):source.index("save_view_state()", source.index("async def load_catalog"))]
        self.assertIn("library.last_scan", load)
        self.assertIn("library.organize_summary_bounded", source)
        self.assertNotIn("scan_coordinator", load)
        self.assertNotIn("request_video_access", load)

    def test_returning_to_organize_refreshes_durable_details_changes_without_scanning(self):
        source = MAIN.read_text(encoding="utf-8")
        self.assertIn('if navigation.current == "organize":', source)
        self.assertIn('_drop_screen_cache("organize")', source)
        back_block = source[source.index('if action in {"previous", "settings_inner"}:'):source.index('elif action == "exit_requested":', source.index('if action in {"previous", "settings_inner"}:'))]
        self.assertIn('if navigation.current == "organize":', back_block)
        self.assertIn('_drop_screen_cache("organize")', back_block)
        self.assertNotIn("refresh_library(", back_block)


if __name__ == "__main__":
    unittest.main()
