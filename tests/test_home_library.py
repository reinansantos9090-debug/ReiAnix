import tempfile
import unittest
from pathlib import Path

from core.library_service import LibraryService
from core.library_store import LibraryStore


ROOT = Path(__file__).resolve().parents[1]


class HomeLibraryTests(unittest.TestCase):
    def test_service_bounded_catalog_projection_reuses_canonical_store(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            first = store.upsert_anime("first", {"title": "First", "genres": "[]"})
            second = store.upsert_anime("second", {"title": "Second", "genres": "[]"})
            store.upsert_episode(first, "content://first/1", "First E01", 1, 1)
            store.upsert_episode(second, "content://second/1", "Second E01", 1, 1)

            service = LibraryService(store)
            result = service.catalog_by_ids([second])

            self.assertEqual([second], [item["id"] for item in result])
            self.assertEqual("Second", result[0]["main_title"])

    def test_continue_watching_item_can_resolve_its_anime_without_full_catalog(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            first = store.upsert_anime("first", {"title": "First", "genres": "[]"})
            second = store.upsert_anime("second", {"title": "Second", "genres": "[]"})
            store.upsert_episode(first, "content://first/1", "First E01", 1, 1)
            store.upsert_episode(second, "content://second/1", "Second E01", 1, 1)
            store.save_progress("content://second/1", 40, 100)

            service = LibraryService(store)
            continuation = service.continue_watching(limit=10)
            projected = service.catalog_by_ids([continuation[0]["anime_id"]])

            self.assertEqual("content://second/1", continuation[0]["path"])
            self.assertEqual([second], [item["id"] for item in projected])

    def test_continue_watching_home_identity_targets_episode_and_anime_separately(self):
        source = (ROOT / "views" / "home_view.py").read_text(encoding="utf-8")
        start = source.index("        def play_continuation(item):")
        end = source.index("        async def reveal_focus", start)
        block = source[start:end]
        self.assertIn('episode_id=episode.get("id")', block)
        self.assertIn('anime_id=item.get("anime_id")', block)
        self.assertNotIn('anime_id=item.get("id")', block)

    def test_home_continue_uses_configured_limit_without_secondary_slicing(self):
        source = (ROOT / "views" / "home_view.py").read_text(encoding="utf-8")
        self.assertIn('settings.get("library.continue_watching_limit")', source)
        self.assertIn(
            'library.media_center_home,\n                    limit=12,\n                    continue_limit=continue_limit',
            source,
        )
        self.assertIn("visible_items = list(continuing)", source)
        self.assertNotIn("visible_items = list(continuing[:6])", source)

    def test_home_uses_bounded_pages_and_incremental_loading(self):
        source = (ROOT / "views" / "home_view.py").read_text(encoding="utf-8")

        self.assertIn("library.browse_catalog_page", source)
        self.assertIn('page_size = settings.get("library.page_size")', source)
        self.assertIn("home_page_size = min(page_size, 48)", source)
        self.assertIn("page_size=home_page_size", source)
        self.assertIn("if remaining < 800", source)
        self.assertIn("catalog.extend(fresh_items)", source)
        self.assertNotIn("library.catalog()", source)

    def test_home_cards_use_configured_dimensions_and_existing_badges(self):
        source = (ROOT / "views" / "home_view.py").read_text(encoding="utf-8")

        self.assertIn("width=card_width", source)
        self.assertIn("artwork_holder(anime, card_width, card_height", source)
        self.assertIn('anime.get("favorite")', source)
        self.assertIn('anime.get("is_pinned")', source)
        self.assertIn("ft.Icons.PUSH_PIN", source)

    def test_episode_home_artwork_binds_to_anime_identity(self):
        source = (ROOT / "views" / "home_view.py").read_text(encoding="utf-8")

        self.assertIn(
            'binding_id = item.get("anime_id") if item.get("anime_id") is not None and binding_entity in {"anime", "movie"} else item.get("id")',
            source,
        )
        self.assertIn(
            'item.get("anime_id") if item.get("anime_id") is not None and entity in {"anime", "movie"} else item.get("id")',
            source,
        )

    def test_continue_details_uses_existing_service_projection(self):
        source = (ROOT / "views" / "home_view.py").read_text(encoding="utf-8")

        self.assertIn("request_continuation_details", source)
        self.assertIn("library.catalog_by_ids", source)
        self.assertIn('item.get("anime_id")', source)

    def test_home_header_and_library_bar_can_wrap(self):
        source = (ROOT / "views" / "home_view.py").read_text(encoding="utf-8")

        self.assertIn("], alignment=ft.MainAxisAlignment.SPACE_BETWEEN, wrap=True, run_spacing=8)", source)
        self.assertIn(
            "alignment=ft.MainAxisAlignment.SPACE_BETWEEN,\n            wrap=True,",
            source,
        )

    def test_home_keeps_single_catalog_and_no_parallel_database(self):
        source = (ROOT / "views" / "home_view.py").read_text(encoding="utf-8")

        for forbidden in ("home.db", "catalog.db", "anime_cache.db", "ui_library.db", "player_history"):
            self.assertNotIn(forbidden, source)

        self.assertNotIn("library_items =", source)
        self.assertNotIn("home_library_items =", source)


if __name__ == "__main__":
    unittest.main()
