import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
HOME = (ROOT / "views" / "home_view.py").read_text(encoding="utf-8")
SERVICE = (ROOT / "core" / "library_service.py").read_text(encoding="utf-8")


class HomeRenderingTests(unittest.TestCase):
    def test_home_uses_batched_local_artwork_resolution(self):
        self.assertIn("resolve_artwork_batch", HOME)
        self.assertIn("artwork_pending_items", HOME)
        self.assertIn("artwork_batch_scheduled", HOME)
        self.assertNotIn("library.resolve_artwork, entity, item_id, kind, allow_network=False", HOME)
        self.assertNotIn("asyncio.Semaphore(4)", HOME)

    def test_home_artwork_updates_are_coalesced_without_fixed_render_delay(self):
        self.assertNotIn("await asyncio.sleep(0)", HOME)
        self.assertNotIn("await asyncio.sleep(0.05)", HOME)
        self.assertIn('performance.counter("home.page_updates.artwork")', HOME)
        self.assertIn("schedule_artwork_ui_update()", HOME)

    def test_home_artwork_is_invalidated_with_the_view_generation(self):
        self.assertIn("artwork_tasks.clear()", HOME)
        self.assertIn("artwork_pending_items.clear()", HOME)
        batch_start = HOME.index("async def _flush_artwork_batch")
        batch_end = HOME.index("def schedule_artwork_batch_prefetch", batch_start)
        batch = HOME[batch_start:batch_end]
        self.assertIn("generation = render_generation[0]", batch)
        self.assertIn("generation != render_generation[0]", batch)

    def test_home_render_metrics_are_explicit(self):
        for token in (
            'performance.counter("home.cards_created")',
            'performance.counter("home.page_updates.catalog")',
            'performance.counter("home.page_updates.sections")',
            'performance.gauge("home.artwork.pending"',
        ):
            self.assertIn(token, HOME)

    def test_media_center_home_enriches_secondary_sections_once(self):
        block_start = SERVICE.index("    def media_center_home")
        block_end = SERVICE.index("    def browse_catalog_page", block_start)
        block = SERVICE[block_start:block_end]
        self.assertEqual(1, block.count("genre_registry.enrich_catalog"))
        self.assertIn("enrich_items", block)
        self.assertIn("enrich_keys", block)

    def test_home_keeps_existing_incremental_catalog_and_scroll_contracts(self):
        for token in (
            "async def load_library_page",
            "home_page_size = min(page_size, 48)",
            "load_library_page(reset=False)",
            "restore_scroll_position()",
            "catalog_focus_targets",
            "page.run_task",
        ):
            self.assertIn(token, HOME)

    def test_home_does_not_introduce_a_second_artwork_cache_or_pipeline(self):
        self.assertNotIn("class HomeArtwork", HOME)
        self.assertNotIn("class HomeImageCache", HOME)
        self.assertNotIn("MAX_WORKERS", HOME)
        self.assertIn("library.resolve_artwork_batch", HOME)


if __name__ == "__main__":
    unittest.main()
