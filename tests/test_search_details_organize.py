import tempfile
import unittest
from pathlib import Path

from core.library_service import LibraryService
from core.library_store import LibraryStore


ROOT = Path(__file__).resolve().parents[1]


class SearchDetailsOrganizeTests(unittest.TestCase):
    def test_sqlite_search_uses_shared_normalization_for_case_punctuation_and_accents(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime_id = store.upsert_anime(
                "sao-paulo",
                {
                    "title": "São-Paulo Show",
                    "aliases": "[\"Sao Paulo Show\"]",
                    "genres": "[\"Drama\"]",
                },
            )
            store.upsert_episode(
                anime_id,
                "content://sao/1",
                "SÃO-PAULO SHOW S01E01.mkv",
                1,
                1,
            )
            service = LibraryService(store)

            for query in ("sao paulo", "SAO-PAULO", "São   Paulo"):
                page = service.catalog_page(page=0, page_size=12, query=query, sort="Nome A-Z")
                self.assertEqual(["São-Paulo Show"], [item["main_title"] for item in page["items"]])

    def test_search_filters_are_intersected_by_the_sql_page_query(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            favorite = store.upsert_anime(
                "alpha",
                {"title": "Alpha Show", "genres": "[\"Action\"]"},
            )
            other = store.upsert_anime(
                "beta",
                {"title": "Beta Show", "genres": "[\"Action\"]"},
            )
            store.upsert_episode(favorite, "content://alpha/1", "Alpha S01E01.mkv", 1, 1)
            store.upsert_episode(other, "content://beta/1", "Beta S01E01.mkv", 1, 1)
            store.toggle_favorite(favorite)

            service = LibraryService(store)
            page = service.catalog_page(
                page=0,
                page_size=12,
                query="show",
                state="Favoritos",
                genre="Action",
                sort="Nome A-Z",
            )

            self.assertEqual(["Alpha Show"], [item["main_title"] for item in page["items"]])
            self.assertEqual(1, page["total"])

    def test_startup_does_not_restore_transient_details_context(self):
        source = (ROOT / "main.py").read_text(encoding="utf-8")
        self.assertIn("navigation = NavigationController()", source)
        self.assertIn("navigation.reset_to_root()", source)
        self.assertNotIn("restore_details_context", source)
        self.assertNotIn('"details_media_id"', source)
        self.assertNotIn("catalog = library.catalog()", source)

    def test_details_mutations_invalidate_only_cached_library_projections(self):
        source = (ROOT / "main.py").read_text(encoding="utf-8")
        self.assertIn("def _invalidate_catalog_views()", source)
        self.assertIn('_invalidate_cached_view(home_state, "home")', source)
        self.assertIn('_invalidate_cached_view(organize_state, "organize")', source)
        for callback in (
            "_toggle_favorite_from_details",
            "_toggle_pin_from_details",
            "_set_tags_from_details",
            "_set_note_from_details",
            "_set_episode_identification_from_details",
        ):
            self.assertIn(callback, source)

    def test_details_manual_identification_dialog_has_real_lifecycle(self):
        source = (ROOT / "views" / "details_view.py").read_text(encoding="utf-8")
        target = source[source.index("def edit_identification"):source.index("def episode_item")]
        self.assertEqual(target.count("dialog = ft.AlertDialog("), 1)
        self.assertIn("dialog = None\n            saving", target)
        self.assertLess(
            target.index("dialog = None\n            saving"),
            target.index("dialog = ft.AlertDialog("),
        )

    def test_home_search_uses_the_existing_transient_search_state(self):
        home = (ROOT / "views" / "home_view.py").read_text(encoding="utf-8")
        navigation = (ROOT / "main.py").read_text(encoding="utf-8")
        self.assertIn("search_generation[0] += 1", home)
        self.assertIn("await asyncio.sleep(0.18)", home)
        self.assertNotIn("autofocus", home)
        self.assertIn('def close_home_search():', navigation)
        self.assertIn('"[NAV] SEARCH_BACK consumed on Home"', navigation)
        self.assertNotIn('navigation.push("search")', navigation)

    def test_home_exposes_only_domain_supported_state_filters(self):
        home = (ROOT / "views/home_view.py").read_text(encoding="utf-8")
        self.assertIn('states = [str(value) for value in (options.get("states") or []) if value]', home)
        self.assertIn('state_filter.options = [ft.dropdown.Option(value, value) for value in states]', home)
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            service = LibraryService(store)
            supported = set(service.search_options().get("states") or [])
        self.assertTrue({
            "Favoritos", "Fixados", "Assistidos", "Não assistidos",
            "Em andamento", "Concluídos", "Não iniciados", "Com nota",
            "Sem nota", "Sem metadata", "Sem capa",
        }.issubset(supported))

    def test_organize_stays_on_library_service_and_bounded_page_api(self):
        organize = (ROOT / "views" / "organize_view.py").read_text(encoding="utf-8")
        self.assertIn("library.browse_catalog_page", organize)
        self.assertIn("library.organize_summary_bounded", organize)
        self.assertNotIn("cursor.execute(", organize)
        self.assertNotIn("store._conn(", organize)

    def test_no_parallel_search_or_details_database_is_created(self):
        for path in (ROOT / "views" / "home_view.py", ROOT / "views" / "details_view.py", ROOT / "views" / "organize_view.py"):
            source = path.read_text(encoding="utf-8")
            for forbidden in ("search.db", "details.db", "organize.db", "ui_library.db", "search_catalog"):
                self.assertNotIn(forbidden, source)


if __name__ == "__main__":
    unittest.main()
