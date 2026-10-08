import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class HomeResilienceContractTests(unittest.TestCase):
    def test_flet_image_uses_box_fit_compatibility_api(self):
        ui_source = (ROOT / 'core' / 'ui.py').read_text(encoding='utf-8')
        home_source = (ROOT / 'views' / 'home_view.py').read_text(encoding='utf-8')
        self.assertNotIn('ImageFit', ui_source + home_source)
        self.assertIn('ft.BoxFit.COVER', ui_source + home_source)

    def test_catalog_load_is_authoritative_and_secondary_projections_are_isolated(self):
        source = (ROOT / 'views' / 'home_view.py').read_text(encoding='utf-8')
        self.assertIn('library.browse_catalog_page', source)
        self.assertIn('page_size = settings.get("library.page_size")', source)
        self.assertIn('home_page_size = min(page_size, 48)', source)
        self.assertIn('page_size=home_page_size', source)
        self.assertIn('catalog.extend(fresh_items)', source)
        self.assertIn('render_generation', source)
        self.assertNotIn('def load_data():', source)

    def test_home_hydration_never_blocks_first_catalog_render(self):
        source = (ROOT / 'views' / 'home_view.py').read_text(encoding='utf-8')
        self.assertIn('await load_library_page(reset=True)', source)
        self.assertIn('hydrate_metadata_and_artwork(list(fresh_items), token)', source)
        self.assertIn('schedule_background(run_hydration_batch)', source)

    def test_home_diagnostic_log_keeps_failure_context_without_exposing_traceback(self):
        source = (ROOT / 'views' / 'home_view.py').read_text(encoding='utf-8')
        self.assertIn("Home local projections load failed", source)
        self.assertIn("'screen':'home'", source)
        self.assertIn("\"screen\":\"home\"", source)
        self.assertNotIn('traceback' , source.lower())


if __name__ == '__main__':
    unittest.main()