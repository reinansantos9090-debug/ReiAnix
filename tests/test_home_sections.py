"""earlier validation stage 30 regression contracts for definitive Home section removal."""
from pathlib import Path
import tempfile
import unittest

from core.library_store import LibraryStore
from core.library_service import LibraryService


ROOT = Path(__file__).resolve().parents[1]
HOME = (ROOT / "views" / "home_view.py").read_text(encoding="utf-8")
SERVICE = (ROOT / "core" / "library_service.py").read_text(encoding="utf-8")
STORE = (ROOT / "core" / "library_store.py").read_text(encoding="utf-8")


REMOVED_TITLES = (
    "PRÓXIMO EPISÓDIO",
    "RECENTEMENTE ADICIONADOS",
    "RECENTEMENTE ASSISTIDOS",
    "SÉRIES / ANIMES",
    "ESPECIAIS",
)

REMOVED_KEYS = (
    "next_episode",
    "recently_added",
    "recently_watched",
    "series",
    "specials",
)


class HomeSectionRemovalTests(unittest.TestCase):
    def test_removed_home_titles_and_section_keys_are_absent_from_render_pipeline(self):
        refresh_start = HOME.index("async def refresh_home_sections")
        sections_start = HOME.index("sections_column = ft.Column")
        render_block = HOME[refresh_start:sections_start + 1800]
        for title in REMOVED_TITLES:
            self.assertNotIn(title, render_block)
        for key in REMOVED_KEYS:
            self.assertNotIn('"' + key + '"', render_block)

    def test_home_keeps_only_remaining_horizontal_sections(self):
        self.assertIn('("FAVORITOS", "favorites")', HOME)
        self.assertIn('("PINADOS", "pinned")', HOME)
        self.assertIn('("FILMES", "movies")', HOME)
        self.assertIn('"CONTINUAR ASSISTINDO"', HOME)
        for title in REMOVED_TITLES:
            self.assertNotIn('("' + title, HOME)

    def test_home_store_projection_does_not_query_removed_sections(self):
        start = STORE.index("def home_sections(")
        end = STORE.index("def organize_summary(", start)
        block = STORE[start:end]
        for key in REMOVED_KEYS:
            self.assertNotIn('"' + key + '"', block)
        self.assertNotIn("playback_history(", block)
        self.assertNotIn("next_episode_items(", block)
        self.assertIn('"favorites"', block)
        self.assertIn('"pinned"', block)
        self.assertIn('"movies"', block)
        self.assertIn("continue_watching(", block)

    def test_home_service_does_not_return_removed_sections(self):
        start = SERVICE.index("def media_center_home(")
        end = SERVICE.index("def browse_catalog_page(", start)
        block = SERVICE[start:end]
        for key in REMOVED_KEYS:
            self.assertNotIn('"' + key + '"', block)
        self.assertIn('"continue_watching"', block)
        self.assertIn('"favorites"', block)
        self.assertIn('"pinned"', block)
        self.assertIn('"movies"', block)

    def test_removed_next_episode_projection_is_not_present(self):
        self.assertNotIn("def next_episode_items(", STORE)

    def test_home_sections_return_only_live_projection_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            service = LibraryService(store)
            result = service.media_center_home(limit=8)
            self.assertEqual(
                {"continue_watching", "favorites", "pinned", "movies"},
                set(result),
            )


if __name__ == "__main__":
    unittest.main()
