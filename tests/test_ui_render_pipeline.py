import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "main.py"
ORGANIZE = ROOT / "views" / "organize_view.py"
SETTINGS = ROOT / "views" / "settings_view.py"


class UiRenderPipelineTests(unittest.TestCase):
    def test_render_current_has_unchanged_tree_fast_path(self):
        source = MAIN.read_text(encoding="utf-8")
        self.assertIn("view_shell_cache = {}", source)
        self.assertIn("render_state = {", source)
        self.assertIn('"dirty": True', source)
        self.assertIn("def _ui_render_signature():", source)
        self.assertIn("ui.render_current.skipped_unchanged", source)
        self.assertIn('metadata={\n                    "reason": reason,', source)

    def test_page_views_are_reused_when_the_shells_are_unchanged(self):
        source = MAIN.read_text(encoding="utf-8")
        self.assertIn("previous_view_ids = tuple(id(view) for view in page.views)", source)
        self.assertIn("next_view_ids = tuple(id(view) for view in views)", source)
        self.assertIn("if page_views_replaced:", source)
        self.assertIn("ui.render_current.page_views_replaced", source)
        self.assertIn("ui.render_current.page_views_reused", source)

    def test_settings_focus_invalidation_happens_only_after_fast_path(self):
        source = MAIN.read_text(encoding="utf-8")
        guard = source.index("if (\n            not force")
        invalidate = source.index("settings_tasks.invalidate()", guard)
        skip = source.index("ui.render_current.skipped_unchanged", guard)
        self.assertGreater(invalidate, skip)
        self.assertGreater(invalidate, guard)

    def test_details_thumbnail_update_is_control_local(self):
        source = (ROOT / "views" / "details_view.py").read_text(encoding="utf-8")
        start = source.index("def update_thumbnail_in_place")
        end = source.index("if isinstance(view_state, dict):", start)
        block = source[start:end]
        self.assertIn("updated_holder.update()", block)
        self.assertNotIn("page.update()", block)

    def test_spoiler_artwork_updates_only_its_shield(self):
        source = (ROOT / "core" / "ui.py").read_text(encoding="utf-8")
        start = source.index("def spoiler_artwork(")
        end = source.index("def empty_state(", start)
        block = source[start:end]
        self.assertIn("shield.update()", block)
        self.assertNotIn("page.update()", block)

    def test_render_origins_are_traced(self):
        source = MAIN.read_text(encoding="utf-8")
        for reason in (
            "return_home",
            "open_organize",
            "open_collector",
            "open_details",
            "details_refresh",
            "catalog_changed",
            "theme_changed",
            "runtime_setting_changed",
            "platform_brightness",
            "open_settings",
            "open_settings_category",
            "home_search_closed",
            "back",
            "settings_refresh",
        ):
            self.assertIn(f'reason="{reason}"', source)

    def test_cache_invalidations_mark_the_render_dirty(self):
        source = MAIN.read_text(encoding="utf-8")
        helper_start = source.index("def _drop_screen_cache")
        helper_end = source.index("def _view_shell", helper_start)
        helper = source[helper_start:helper_end]
        self.assertIn("screen_cache.pop(route, None)", helper)
        self.assertIn("_mark_ui_dirty()", helper)
        self.assertNotIn("_drop_screen_cache(route)\\n        _mark_ui_dirty()", helper)

        clear_start = source.index("def _clear_screen_cache")
        clear_end = source.index("def _view_shell", clear_start)
        clear_helper = source[clear_start:clear_end]
        self.assertIn("screen_cache.clear()", clear_helper)
        self.assertIn("_mark_ui_dirty()", clear_helper)
        self.assertNotIn("_clear_screen_cache()\\n        _mark_ui_dirty()", clear_helper)

    def test_settings_auto_scroll_protection_remains(self):
        source = SETTINGS.read_text(encoding="utf-8")
        self.assertIn("SettingsFocusState", source)
        self.assertIn("handle_category_focus", source)
        self.assertNotIn("page.run_task(restore_scroll)", source)
        self.assertNotIn("scroll_to(offset=float(stored)", source)

    def test_organize_collection_render_has_no_post_load_duplicate_update(self):
        source = ORGANIZE.read_text(encoding="utf-8")
        self.assertNotIn(
            "await render_collection(reset=True)\n            page.update()",
            source,
        )
        self.assertIn("load_collection_page() performs the single required UI update", source)

    def test_catalog_refresh_does_not_chain_into_settings_refresh(self):
        source = MAIN.read_text(encoding="utf-8")
        self.assertNotIn(
            "on_catalog_changed()\n        refresh_settings_if_active()",
            source,
        )
        self.assertNotIn(
            "on_catalog_changed()\n                            refresh_settings_if_active()",
            source,
        )
        self.assertNotIn(
            "on_catalog_changed()\n                                refresh_settings_if_active()",
            source,
        )

    def test_main_only_has_intentional_direct_screen_cache_operations(self):
        source = MAIN.read_text(encoding="utf-8")
        refs = [
            line.strip()
            for line in source.splitlines()
            if "screen_cache.pop(" in line or "screen_cache.clear(" in line
        ]
        self.assertLessEqual(len(refs), 4)
        self.assertTrue(any("screen_cache.pop(route, None)" in line for line in refs))
        self.assertTrue(any("screen_cache.pop(cache_key, None)" in line for line in refs))

    def test_startup_render_contract_is_preserved(self):
        source = MAIN.read_text(encoding="utf-8")
        self.assertIn(
            '    render_current()\n    performance.event("startup.first_render"',
            source,
        )


if __name__ == "__main__":
    unittest.main()
