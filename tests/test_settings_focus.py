import unittest
from pathlib import Path

from core.settings_focus import SettingsFocusState, SettingsTaskRegistry


ROOT = Path(__file__).resolve().parents[1]


class _FakeTask:
    def __init__(self):
        self.cancelled = False
        self.callbacks = []

    def cancel(self):
        self.cancelled = True
        for callback in tuple(self.callbacks):
            callback(self)
        return True

    def add_done_callback(self, callback):
        self.callbacks.append(callback)


class SettingsFocusBehaviorTests(unittest.TestCase):
    def test_initial_autofocus_does_not_request_scroll(self):
        state = SettingsFocusState()
        decision = state.on_focus("settings-category-Conta")
        self.assertFalse(decision.should_scroll)
        self.assertEqual("initial", decision.reason)

    def test_duplicate_focus_does_not_request_scroll(self):
        state = SettingsFocusState()
        state.on_focus("settings-category-Conta")
        decision = state.on_focus("settings-category-Conta")
        self.assertFalse(decision.should_scroll)
        self.assertEqual("duplicate", decision.reason)

    def test_focus_change_requests_reveal(self):
        state = SettingsFocusState()
        state.on_focus("settings-category-Conta")
        decision = state.on_focus("settings-category-Player")
        self.assertTrue(decision.should_scroll)
        self.assertEqual("navigation", decision.reason)

    def test_returning_to_a_previous_category_is_not_treated_as_duplicate(self):
        state = SettingsFocusState()
        state.on_focus("settings-category-Conta")
        state.on_focus("settings-category-Player")
        decision = state.on_focus("settings-category-Conta")
        self.assertTrue(decision.should_scroll)
        self.assertEqual("navigation", decision.reason)


class SettingsTaskLifecycleTests(unittest.TestCase):
    def test_invalidate_cancels_tasks_and_advances_generation(self):
        registry = SettingsTaskRegistry()
        task = _FakeTask()
        registry.register(task)
        before = registry.generation
        after = registry.invalidate()
        self.assertEqual(before + 1, after)
        self.assertTrue(task.cancelled)

    def test_register_and_invalidate_are_idempotent_for_old_tasks(self):
        registry = SettingsTaskRegistry()
        first = _FakeTask()
        second = _FakeTask()
        registry.register(first)
        registry.register(second)
        registry.invalidate()
        registry.invalidate()
        self.assertTrue(first.cancelled)
        self.assertTrue(second.cancelled)
        self.assertGreaterEqual(registry.generation, 2)


class SettingsFocusIntegrationTests(unittest.TestCase):
    def test_settings_view_has_no_direct_focus_to_scroll_task_binding(self):
        settings = (ROOT / "views" / "settings_view.py").read_text(encoding="utf-8")
        self.assertIn("SettingsFocusState", settings)
        self.assertIn("handle_category_focus", settings)
        self.assertIn("settings_stale_focus_tasks", settings)
        self.assertNotIn("on_focus=lambda _event, k=key: page.run_task(reveal_category_focus, k)", settings)
        self.assertNotIn("def restore_scroll", settings)
        self.assertNotIn("page.run_task(restore_scroll)", settings)
        self.assertNotIn('scroll_to(offset=float(stored)', settings)

    def test_main_binds_settings_tasks_to_navigation_generation(self):
        main = (ROOT / "main.py").read_text(encoding="utf-8")
        self.assertIn("SettingsTaskRegistry", main)
        self.assertIn("settings_generation_provider=lambda: settings_tasks.generation", main)
        self.assertIn("register_settings_task=settings_tasks.register", main)
        self.assertIn("settings_tasks.invalidate()", main)


if __name__ == "__main__":
    unittest.main()
