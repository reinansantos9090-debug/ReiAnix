import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "main.py"
SETTINGS = ROOT / "views" / "settings_view.py"
COLLECTOR = ROOT / "views" / "collector_view.py"
DETAILS = ROOT / "views" / "details_view.py"
NAVIGATION = ROOT / "core" / "navigation.py"
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"


class NavigationLifecycleTests(unittest.TestCase):
    def test_cache_invalidation_marks_render_state_dirty(self):
        source = MAIN.read_text(encoding="utf-8")
        start = source.index("def _invalidate_cached_view")
        end = source.index("def _invalidate_catalog_views", start)
        helper = source[start:end]
        self.assertIn("screen_cache.pop(route, None)", helper)
        self.assertIn("_mark_ui_dirty()", helper)

    def test_duplicate_details_same_anime_is_ignored(self):
        source = MAIN.read_text(encoding="utf-8")
        start = source.index("def navigate_details")
        end = source.index("async def refresh_current_details", start)
        handler = source[start:end]
        self.assertIn('navigation.current == "details" and anime_id == current_id', handler)
        self.assertIn('navigation.duplicate_details_ignored', handler)
        self.assertIn('if navigation.current != "details":', handler)
        self.assertNotIn('navigation.push("details")\n            navigation.push("details")', handler)

    def test_duplicate_settings_category_does_not_grow_nested_path(self):
        source = NAVIGATION.read_text(encoding="utf-8")
        start = source.index("def push_settings")
        end = source.index("def back", start)
        block = source[start:end]
        self.assertIn("if self._settings_path and self._settings_path[-1] == normalized:", block)
        self.assertIn("return", block)

    def test_duplicate_settings_root_navigation_is_ignored(self):
        source = MAIN.read_text(encoding="utf-8")
        start = source.index("def navigate_settings")
        end = source.index("def navigate_settings_category", start)
        handler = source[start:end]
        self.assertIn('navigation.current == "settings" and not navigation.settings_path', handler)
        self.assertIn('navigation.duplicate_settings_ignored', handler)
        self.assertIn('_drop_screen_cache("settings")', handler)

    def test_back_closes_dialog_before_invalidating_settings_tasks(self):
        source = MAIN.read_text(encoding="utf-8")
        back = source[source.index("def navigate_back"):source.index("page.on_view_pop =", source.index("def navigate_back"))]
        dialog = back.index("dialog = page.pop_dialog()")
        invalidate = back.index("settings_tasks.invalidate()")
        self.assertGreater(invalidate, dialog)
        self.assertIn('if dialog is not None:', back)

    def test_settings_render_invalidates_tasks_before_rebuilding_settings(self):
        source = MAIN.read_text(encoding="utf-8")
        start = source.index("def render_current")
        end = source.index("def handle_flet_view_pop", start)
        render = source[start:end]
        invalidate = render.index("settings_tasks.invalidate()")
        views = render.index("views = []")
        self.assertIn('if navigation.current == "settings":', render)
        self.assertIn("every executed", render)
        self.assertLess(invalidate, views)

    def test_settings_focus_task_has_single_registration_path(self):
        source = SETTINGS.read_text(encoding="utf-8")
        start = source.index("def handle_category_focus")
        end = source.index("def build_category_tile", start)
        handler = source[start:end]
        self.assertEqual(handler.count("register_settings_task("), 0)
        self.assertIn("focus_task = start_task(", handler)
        start_task = source.index("def start_task")
        end_task = source.index("async def execute_action", start_task)
        helper = source[start_task:end_task]
        self.assertEqual(helper.count("register_settings_task(task)"), 1)

    def test_settings_background_tasks_are_registered(self):
        source = SETTINGS.read_text(encoding="utf-8")
        self.assertIn("def start_task(handler, *args):", source)
        self.assertIn("register_settings_task(task)", source)
        direct_run_task_lines = [
            line for line in source.splitlines()
            if "page.run_task(" in line
        ]
        self.assertEqual(len(direct_run_task_lines), 1)
        self.assertIn("task = page.run_task(handler, *args)", direct_run_task_lines[0])

    def test_settings_inactive_instances_cannot_update_page(self):
        source = SETTINGS.read_text(encoding="utf-8")
        start = source.index("def safe_update")
        end = source.index("def notice", start)
        safe_update = source[start:end]
        self.assertIn("if not settings_is_active():", safe_update)
        self.assertIn("return", safe_update)

    def test_collector_has_instance_lifecycle_guard(self):
        collector = COLLECTOR.read_text(encoding="utf-8")
        self.assertIn("def build(page: ft.Page, library, on_back, is_active=None):", collector)
        self.assertIn("is_active = is_active or (lambda: True)", collector)
        self.assertIn("if not is_active():", collector)
        main = MAIN.read_text(encoding="utf-8")
        self.assertIn("collector_instance_generation = [0]", main)
        self.assertIn("collector_instance_token = collector_instance_generation[0]", main)
        self.assertIn("collector_instance_generation[0] == token", main)

    def test_details_instance_guard_includes_lifecycle_connection_state(self):
        main = MAIN.read_text(encoding="utf-8")
        start = main.index("is_active=lambda token=detail_instance_token")
        end = main.index("            )\n        elif route == \"collector\":", start)
        fragment = main[start:end]
        self.assertIn("ui_alive[0]", fragment)
        self.assertIn("details_instance_generation[0] == token", fragment)

    def test_android_back_still_has_single_native_dispatch(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        self.assertEqual(source.count("onBackPressedDispatcher.addCallback"), 1)
        self.assertIn("engine.navigationChannel.popRoute()", source)
        self.assertNotIn('put("type", "android_back")', source)


if __name__ == "__main__":
    unittest.main()
